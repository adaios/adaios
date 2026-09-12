import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// LifeTemplate — 生活记录模板（**单一事实源**，conventions D3）。
///
/// 弹窗（本文件）与输入栏快捷条（`widgets/input_bar.dart`）共用同一份定义，
/// 避免 emoji / 文案 / 模板前缀两处漂移（新增类型只改这里一处）。
class LifeTemplate {
  /// 稳定标识（如 mood/sport/diet/sleep），落库文案不含它，仅前端选型用。
  final String key;
  final String emoji;
  final String label;

  /// 「使用模板」时填入的句子前缀。
  final String prefix;

  /// 空输入时的引导问句（hint）。
  final String hint;

  const LifeTemplate({
    required this.key,
    required this.emoji,
    required this.label,
    required this.prefix,
    required this.hint,
  });
}

const List<LifeTemplate> kLifeTemplates = [
  LifeTemplate(
    key: 'mood',
    emoji: '😊',
    label: '心情',
    prefix: '今天心情',
    hint: '今天心情怎么样？因为什么？',
  ),
  LifeTemplate(
    key: 'sport',
    emoji: '🏃',
    label: '运动',
    prefix: '今天运动了',
    hint: '今天运动了多久？感觉如何？',
  ),
  LifeTemplate(
    key: 'diet',
    emoji: '🍜',
    label: '饮食',
    prefix: '今天吃了',
    hint: '今天吃了什么？味道怎么样？',
  ),
  LifeTemplate(
    key: 'sleep',
    emoji: '😴',
    label: '睡眠',
    prefix: '昨晚睡了',
    hint: '昨晚睡了几个小时？质量如何？',
  ),
];

/// 按 key 取模板；未知 key 回落「心情」——防外部传入非法初始值导致崩溃。
LifeTemplate lifeTemplateOf(String key) =>
    kLifeTemplates.firstWhere((t) => t.key == key, orElse: () => kLifeTemplates.first);

/// LifeQuickEntry — 生活快速记录弹窗。
///
/// 提供运动/饮食/心情/睡眠四类预设模板，降低记录门槛。
/// 选择模板并填写后，通过 onSend 回调发送到主输入流。
class LifeQuickEntry extends StatefulWidget {
  final ValueChanged<String> onSend;

  /// 打开时预选的类型（默认「心情」）。未知值回落「心情」。
  final String initialType;

  const LifeQuickEntry({super.key, required this.onSend, this.initialType = 'mood'});

  @override
  State<LifeQuickEntry> createState() => _LifeQuickEntryState();
}

class _LifeQuickEntryState extends State<LifeQuickEntry> {
  final _textCtrl = TextEditingController();
  late String _selectedType = lifeTemplateOf(widget.initialType).key;
  bool _showTemplate = true;

  @override
  void dispose() {
    _textCtrl.dispose();
    super.dispose();
  }

  void _selectType(String type) {
    setState(() {
      _selectedType = type;
      _textCtrl.clear();
      _showTemplate = true;
    });
  }

  void _applyTemplate() {
    final t = lifeTemplateOf(_selectedType);
    _textCtrl.text = t.prefix;
    // 光标落在句尾——否则接着打字会插到句首，把句子打乱（快捷记录的主路径）。
    _textCtrl.selection = TextSelection.collapsed(offset: t.prefix.length);
    setState(() => _showTemplate = false);
  }

  void _send() {
    final text = _textCtrl.text.trim();
    if (text.isEmpty) return;
    widget.onSend(text);
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final t = lifeTemplateOf(_selectedType);

    return Container(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
      decoration: const BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Drag handle
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
          // Title
          Text('生活记录', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const SizedBox(height: 16),
          // Type selector
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: kLifeTemplates.map((e) {
              final selected = _selectedType == e.key;
              return GestureDetector(
                onTap: () => _selectType(e.key),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 48, height: 48,
                      decoration: BoxDecoration(
                        color: selected
                            ? AppColors.darkGreen.withValues(alpha: 0.15)
                            : AppColors.darkSurface2,
                        borderRadius: BorderRadius.circular(14),
                        border: selected
                            ? Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3), width: 0.5)
                            : null,
                      ),
                      child: Center(child: Text(e.emoji, style: TextStyle(fontSize: 22))),
                    ),
                    const SizedBox(height: 6),
                    Text(e.label,
                        style: TextStyle(fontSize: 11, color: selected ? AppColors.darkGreen : AppColors.darkGrey5)),
                  ],
                ),
              );
            }).toList(),
          ),
          const SizedBox(height: 20),
          // Input
          TextField(
            controller: _textCtrl,
            autofocus: true,
            maxLines: 3,
            style: TextStyle(fontSize: 14, color: AppColors.darkGrey2),
            decoration: InputDecoration(
              hintText: _showTemplate ? t.hint : '',
              hintStyle: TextStyle(fontSize: 14, color: AppColors.darkGrey6),
              filled: true,
              fillColor: AppColors.darkSurface2,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: BorderSide.none,
              ),
              contentPadding: const EdgeInsets.all(12),
            ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 12),
          // Buttons
          Row(children: [
            if (_showTemplate)
              GestureDetector(
                onTap: _applyTemplate,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                  decoration: BoxDecoration(
                    color: AppColors.darkSurface2,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text('使用模板', style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
                ),
              ),
            const Spacer(),
            GestureDetector(
              onTap: () => Navigator.pop(context),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
              ),
            ),
            const SizedBox(width: 8),
            GestureDetector(
              onTap: _textCtrl.text.trim().isEmpty ? null : _send,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: _textCtrl.text.trim().isEmpty
                      ? AppColors.darkSurface2
                      : AppColors.darkGreen.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text('记录',
                    style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600,
                        color: _textCtrl.text.trim().isEmpty
                            ? AppColors.darkGrey6
                            : AppColors.darkGreen)),
              ),
            ),
          ]),
        ],
      ),
    );
  }
}

/// 显示生活快速记录弹窗的便捷函数。
///
/// [initialType] 可预选类型（输入栏快捷条点哪个就开哪个）；未知值回落「心情」。
Future<void> showLifeQuickEntry(
  BuildContext context,
  ValueChanged<String> onSend, {
  String initialType = 'mood',
}) {
  return showModalBottomSheet(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (_) => LifeQuickEntry(onSend: onSend, initialType: initialType),
  );
}
