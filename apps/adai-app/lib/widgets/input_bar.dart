import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../theme/app_colors.dart';

/// 用户选择的图片（多模态 L4，交给宿主上传）。
class PickedImage {
  final List<int> bytes;
  final String name;
  final String? extension;

  PickedImage(this.bytes, this.name, this.extension);

  /// 缓存的 Uint8List（避免每次 build 新建导致 Image.memory 缓存 miss 重新解码 → 预览闪烁）
  late final Uint8List bytesU8 = Uint8List.fromList(bytes);
}

/// Input bar — text input + [+] menu（图片/文件/链接）。
/// Supports ask placeholder mode (triggered from parent).
/// 语音：v2 方向，已移除误导性 stub（2026-08-03，原 REVIEW #164）。
class InputBar extends StatefulWidget {
  final ValueChanged<String> onSend;
  final bool hasActiveChat;          // true when chatting with AI
  final VoidCallback? onAskActivated; // called when ask mode starts typing
  final ValueChanged<PickedImage>? onImage; // 多模态：单图立即上传（兼容旧调用）
  final void Function(List<PickedImage> images, String caption)? onSendMedia; // 多图 + 可选文字一起提交，逐张上传

  const InputBar({
    super.key,
    required this.onSend,
    this.hasActiveChat = false,
    this.onAskActivated,
    this.onImage,
    this.onSendMedia,
  });

  @override
  State<InputBar> createState() => InputBarState();
}

class InputBarState extends State<InputBar> {
  final TextEditingController _textCtrl = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  bool _hasText = false;
  final List<PickedImage> _pendingImages = []; // 输入栏内联附件：选图后待发送，非立即上传

  bool get _hasPending => _pendingImages.isNotEmpty;

  /// 从外部预设输入框文本并聚焦。
  void prefillText(String text) {
    _textCtrl.text = text;
    _textCtrl.selection = TextSelection.collapsed(offset: text.length);
    _focusNode.requestFocus();
  }

  static const _placeholders = [
    '记录点什么…',
    '在想什么…',
    '随手记一笔…',
    '此刻在想什么？',
    '有什么想记录的吗？',
    '刚才发生了什么？',
  ];
  late final String _placeholder;

  @override
  void initState() {
    super.initState();
    final day = DateTime.now().day;
    _placeholder = _placeholders[day % _placeholders.length];
    _textCtrl.addListener(() {
      setState(() => _hasText = _textCtrl.text.trim().isNotEmpty);
    });
  }

  @override
  void didUpdateWidget(InputBar old) {
    super.didUpdateWidget(old);
    if (widget.hasActiveChat && !old.hasActiveChat) {
      // Ask mode just activated – focus input
      _focusNode.requestFocus();
    }
  }

  @override
  void dispose() {
    _textCtrl.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _send() {
    final text = _textCtrl.text.trim();
    final images = List<PickedImage>.of(_pendingImages);
    if (images.isEmpty && text.isEmpty) return;
    _textCtrl.clear();
    setState(() => _pendingImages.clear());
    // 发送后收起键盘（阿呆 08-13 反馈）：记录/提问发出后不再霸屏遮挡 Feed。
    _focusNode.unfocus();
    if (images.isNotEmpty) {
      // 图 + 文字（可空，caption 共享）一起提交，逐张上传
      if (widget.onSendMedia != null) {
        widget.onSendMedia!(images, text);
        return;
      }
      // 无 onSendMedia 时回退：逐张交给单图上传回调
      for (final img in images) {
        widget.onImage?.call(img);
      }
      return;
    }
    widget.onSend(text);
  }

  /// REVIEW #257 测试钩子：测试注入待发送图片（等价用户选图挂到输入栏），
  /// 不触发 ImagePicker（widget 测试环境无平台通道）。随后点发送键即走 onSendMedia。
  /// Phase 1：与真实选图路径一致，尊重数量上限（超出截断），防测试绕过上限。
  @visibleForTesting
  void debugInjectImages(List<PickedImage> images) {
    setState(() {
      final room = _maxImages - _pendingImages.length;
      _pendingImages.addAll(room >= images.length ? images : images.take(room));
    });
  }

  /// Phase 1 图片数量上限（阿呆 08-14 拍板：带图最多 3 张）。
  static const int _maxImages = 3;

  /// 超限提示（选图/拍照共用）。
  void _showImageLimitToast() {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: const Text('最多选择 3 张图片', style: TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2, behavior: SnackBarBehavior.floating,
    ));
  }

  void _pickImage() async {
    try {
      // 数量上限 3：剩余额度才可选（Phase 1 带图 ask 配套，防无限堆叠）
      final remaining = _maxImages - _pendingImages.length;
      if (remaining <= 0) {
        _showImageLimitToast();
        return;
      }
      // 相册多选用 image_picker（与拍照同组件，交互统一；阿呆 08-13 反馈割裂感）
      final files = await ImagePicker().pickMultiImage(
        maxWidth: 1920, // 限制长边，避免超大字节压栈
        imageQuality: 85, // 压缩，上传更快
        limit: remaining,
      );
      if (files.isEmpty) return;
      // 关闭附件底部弹层
      if (context.mounted) Navigator.pop(context);
      final picked = <PickedImage>[];
      for (final f in files) {
        final bytes = await f.readAsBytes();
        picked.add(PickedImage(
          bytes,
          f.name,
          f.name.contains('.') ? f.name.split('.').last : 'jpg',
        ));
      }
      // 选图后先挂到输入栏（内联预览），发送时才真正上传
      // 保险：limit 已控剩余额度，合并后仍可能越界（如竞态）→ 截断 + 提示
      setState(() {
        final room = _maxImages - _pendingImages.length;
        _pendingImages.addAll(room >= picked.length ? picked : picked.take(room));
        if (room < picked.length) _showImageLimitToast();
      });
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('图片选择失败: $e', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
          backgroundColor: AppColors.darkSurface2, behavior: SnackBarBehavior.floating,
        ));
      }
    }
  }

  /// 拍照（阿呆 08-13：相机拍摄入口，image_picker）。拍到的图挂到输入栏内联预览，随发送上传。
  Future<void> _pickCamera() async {
    // Phase 1 数量上限 3：已满则拦截
    if (_pendingImages.length >= _maxImages) {
      _showImageLimitToast();
      return;
    }
    try {
      final picked = await ImagePicker().pickImage(
        source: ImageSource.camera,
        maxWidth: 1920, // 限制长边，避免超大字节压栈
        imageQuality: 85, // 压缩，上传更快（与选图统一）
      );
      if (picked == null) return;
      final bytes = await picked.readAsBytes();
      final name = picked.name;
      // 关闭附件底部弹层
      if (context.mounted) Navigator.pop(context);
      setState(() {
        _pendingImages.add(PickedImage(
          bytes,
          name,
          name.contains('.') ? name.split('.').last : 'jpg',
        ));
      });
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('拍照失败: $e', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
          backgroundColor: AppColors.darkSurface2, behavior: SnackBarBehavior.floating,
        ));
      }
    }
  }

  /// 输入栏上方的图片附件预览（横向缩略图列表，每张可单独移除）。
  Widget _buildImagePreview() {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface.withAlpha(90),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder.withAlpha(120)),
      ),
      child: Stack(
        children: [
          SizedBox(
            height: 56,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: _pendingImages.length,
              separatorBuilder: (_, _) => const SizedBox(width: 8),
              itemBuilder: (_, i) => _buildThumb(_pendingImages[i], i),
            ),
          ),
          // Phase 1 数量角标（n/3，与上限呼应，元宝/ChatGPT 同款位置）
          Positioned(
            top: 0, right: 0,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text('${_pendingImages.length}/$_maxImages',
                  style: const TextStyle(fontSize: 10, color: AppColors.darkGrey4)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildThumb(PickedImage image, int index) {
    return Stack(
      clipBehavior: Clip.none,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: Image.memory(
            image.bytesU8,
            width: 56,
            height: 56,
            fit: BoxFit.cover,
            // 图片预览闪烁/加载失败修复：cacheWidth 降采样解码（Web 大图解码慢/易失败）
            // + gaplessPlayback 解码期间保留旧帧（打字触发 rebuild 不闪烁）
            cacheWidth: 168,
            gaplessPlayback: true,
            errorBuilder: (_, _, _) => Container(
              width: 56,
              height: 56,
              color: AppColors.darkSurface,
              child: const Icon(Icons.broken_image_outlined, size: 20, color: AppColors.darkGrey5),
            ),
          ),
        ),
        Positioned(
          top: -6,
          right: -6,
          child: GestureDetector(
            onTap: () => setState(() => _pendingImages.removeAt(index)),
            child: Container(
              padding: const EdgeInsets.all(2),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.darkBorder),
              ),
              child: const Icon(Icons.close, size: 12, color: AppColors.darkGrey4),
            ),
          ),
        ),
      ],
    );
  }

  /// 未实现功能的占位提示（文件/链接）。
  void _showNotImplemented(String feature) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('$feature 功能开发中', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2, behavior: SnackBarBehavior.floating,
    ));
  }

  void _showAttach(BuildContext context) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (_) => Container(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
        decoration: const BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Center(
              child: Container(
                width: 36, height: 4,
                margin: const EdgeInsets.only(bottom: 20),
                decoration: BoxDecoration(
                  color: AppColors.darkGrey5.withAlpha(76),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                // 拍照（阿呆 08-13：相册之外需相机拍摄入口，image_picker camera）
                _attachItem(Icons.photo_camera_outlined, '拍照', onTap: _pickCamera),
                _attachItem(Icons.image_outlined, '图片', onTap: _pickImage),
                _attachItem(Icons.description_outlined, '文件', onTap: () => _showNotImplemented('文件上传')),
                _attachItem(Icons.link_outlined, '链接', onTap: () => _showNotImplemented('链接')),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _attachItem(IconData icon, String label, {VoidCallback? onTap}) {
    return GestureDetector(
      onTap: onTap ?? () => Navigator.pop(context),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(width: 52, height: 52,
            decoration: BoxDecoration(
              color: AppColors.darkSurface2,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Icon(icon, color: AppColors.darkGrey4, size: 24),
          ),
          const SizedBox(height: 6),
          Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(8, 6, 8, 10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        border: Border(
          top: BorderSide(color: AppColors.darkBorder.withAlpha(76), width: 0.5),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 内联图片附件预览（多图横向缩略图，每张可移除）
            if (_pendingImages.isNotEmpty) _buildImagePreview(),
            SizedBox(
              height: 40,
              child: Row(
                children: [
                  // Input
                  Expanded(child: _buildText()),
                  const SizedBox(width: 8),
                  // Right button（有图或文字时绿色发送，否则附件菜单）
                  GestureDetector(
                    onTap: _hasPending || _hasText ? _send : () => _showAttach(context),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      width: 40, height: 40,
                      decoration: BoxDecoration(
                        color: _hasPending
                            ? AppColors.darkGreen
                            : _hasText
                                ? (widget.hasActiveChat ? AppColors.darkGreen : AppColors.darkGrey1)
                                : AppColors.darkSurface2,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Icon(
                        (_hasPending || _hasText) ? Icons.arrow_upward_rounded : Icons.add_rounded,
                        size: 20,
                        color: (_hasPending || _hasText) ? AppColors.darkBg : AppColors.darkGrey5,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildText() {
    return Container(
      height: 40,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(14),
        border: widget.hasActiveChat
            ? Border.all(color: AppColors.darkGreen.withAlpha(100), width: 0.5)
            : null,
      ),
      child: TextField(
        controller: _textCtrl,
        focusNode: _focusNode,
        style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1),
        decoration: InputDecoration(
          hintText: _hasPending ? '添加说明（可空）…' : (widget.hasActiveChat ? '问点什么…' : _placeholder),
          hintStyle: TextStyle(
            fontSize: 15,
            color: _hasPending
                ? AppColors.darkGreen.withAlpha(180)
                : (widget.hasActiveChat
                    ? AppColors.darkGreen.withAlpha(180)
                    : AppColors.darkGrey6),
          ),
          border: InputBorder.none,
          filled: false,
          contentPadding: EdgeInsets.zero,
          isDense: true,
        ),
        onSubmitted: (_) => _send(),
      ),
    );
  }
}
