import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import '../theme/app_colors.dart';

/// 用户选择的图片（多模态 L4，交给宿主上传）。
class PickedImage {
  final List<int> bytes;
  final String name;
  final String? extension;

  const PickedImage(this.bytes, this.name, this.extension);
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
    'record something...',
    'thinking about...',
    'jot something down...',
    'what is on your mind?',
    'any thoughts to record?',
    'what happened just now?',
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

  void _pickImage() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.image,
        withData: true,
        allowMultiple: true, // 多选，逐张上传
      );
      if (result == null || result.files.isEmpty) return;
      // 关闭附件底部弹层
      if (context.mounted) Navigator.pop(context);
      // 选图后先挂到输入栏（内联预览），发送时才真正上传
      setState(() {
        _pendingImages.addAll(result.files
            .where((f) => f.bytes != null)
            .map((f) => PickedImage(f.bytes!, f.name, f.extension)));
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
      child: SizedBox(
        height: 56,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: _pendingImages.length,
          separatorBuilder: (_, _) => const SizedBox(width: 8),
          itemBuilder: (_, i) => _buildThumb(_pendingImages[i], i),
        ),
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
            Uint8List.fromList(image.bytes),
            width: 56,
            height: 56,
            fit: BoxFit.cover,
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
                _attachItem(Icons.image_outlined, 'image', onTap: _pickImage),
                _attachItem(Icons.description_outlined, 'file', onTap: () => _showNotImplemented('文件上传')),
                _attachItem(Icons.link_outlined, 'link', onTap: () => _showNotImplemented('链接')),
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
          hintText: _hasPending ? '添加说明（可空）…' : (widget.hasActiveChat ? 'ask your question...' : _placeholder),
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
