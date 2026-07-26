import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// LifeQuickEntry — 生活快速记录弹窗。
///
/// 提供运动/饮食/心情/睡眠四类预设模板，降低记录门槛。
/// 选择模板并填写后，通过 onSend 回调发送到主输入流。
class LifeQuickEntry extends StatefulWidget {
  final ValueChanged<String> onSend;

  const LifeQuickEntry({super.key, required this.onSend});

  @override
  State<LifeQuickEntry> createState() => _LifeQuickEntryState();
}

class _LifeQuickEntryState extends State<LifeQuickEntry> {
  final _textCtrl = TextEditingController();
  String _selectedType = 'mood';
  bool _showTemplate = true;

  static const _templates = {
    'mood': {
      'emoji': '😊',
      'label': '心情',
      'prefix': '今天心情',
      'hint': '今天心情怎么样？因为什么？',
    },
    'sport': {
      'emoji': '🏃',
      'label': '运动',
      'prefix': '今天运动了',
      'hint': '今天运动了多久？感觉如何？',
    },
    'diet': {
      'emoji': '🍜',
      'label': '饮食',
      'prefix': '今天吃了',
      'hint': '今天吃了什么？味道怎么样？',
    },
    'sleep': {
      'emoji': '😴',
      'label': '睡眠',
      'prefix': '昨晚睡了',
      'hint': '昨晚睡了几个小时？质量如何？',
    },
  };

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
    final t = _templates[_selectedType]!;
    _textCtrl.text = t['prefix'] as String;
    _showTemplate = false;
  }

  void _send() {
    final text = _textCtrl.text.trim();
    if (text.isEmpty) return;
    widget.onSend(text);
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final t = _templates[_selectedType]!;

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
            children: _templates.entries.map((e) {
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
                      child: Center(child: Text(e.value['emoji'] as String, style: TextStyle(fontSize: 22))),
                    ),
                    const SizedBox(height: 6),
                    Text(e.value['label'] as String,
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
            maxLines: 3,
            style: TextStyle(fontSize: 14, color: AppColors.darkGrey2),
            decoration: InputDecoration(
              hintText: _showTemplate ? (t['hint'] as String) : '',
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
Future<void> showLifeQuickEntry(BuildContext context, ValueChanged<String> onSend) {
  return showModalBottomSheet(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (_) => LifeQuickEntry(onSend: onSend),
  );
}
