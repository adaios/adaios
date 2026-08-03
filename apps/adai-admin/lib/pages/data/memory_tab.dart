import 'package:flutter/material.dart';
import '../../models/data_models.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../utils/format.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';
import '../../widgets/dialogs.dart';
import '../../widgets/snack.dart';

/// 记忆页签 — 按 kind 筛选 + superseded 淡化 + 手动修正内容（真实后端 /memory）。
class MemoryTab extends StatefulWidget {
  const MemoryTab({super.key, required this.store});

  final DataStore store;

  @override
  State<MemoryTab> createState() => _MemoryTabState();
}

class _MemoryTabState extends State<MemoryTab> {
  late final DataStore _store = widget.store;

  List<MemoryItem>? _memories;
  String? _error;
  bool _loading = true;

  /// 筛选的 kind；null = 全部。
  String? _filter;

  static const List<(String, String)> _kinds = [
    ('insight', '洞察'),
    ('preference', '偏好'),
    ('pattern', '模式'),
    ('decision', '决策'),
    ('fact', '事实'),
  ];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final memories = await _store.loadMemories();
      if (!mounted) return;
      setState(() {
        _memories = memories;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Color _kindColor(String kind) => switch (kind) {
        'preference' => AppColors.darkOrange,
        'pattern' => AppColors.darkBlue,
        'decision' => AppColors.darkGreen,
        'fact' => AppColors.darkGrey5,
        _ => AppColors.darkPurple,
      };

  Future<void> _edit(MemoryItem m) async {
    final text = await showEditDialog(
      context,
      title: '修正记忆内容',
      initial: m.content,
      hint: '输入修正后的记忆内容',
      maxLines: 3,
    );
    if (text == null || text.isEmpty || !mounted) return;
    final success = await _store.updateMemory(m.id, text);
    if (!mounted) return;
    showAppSnack(context, success ? '已修正记忆' : '修正失败：记忆不存在或后端不可用',
        success ? AppColors.darkGreen : AppColors.darkOrange);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(
        child: CircularProgressIndicator(
            strokeWidth: 2, color: AppColors.darkGreen),
      );
    }
    if (_error != null) {
      return _buildError();
    }
    final all = _memories ?? const <MemoryItem>[];
    final visible =
        all.where((m) => _filter == null || m.kind == _filter).toList();

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        _buildFilterChips(),
        const SizedBox(height: 12),
        if (visible.isEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 40),
            child: Center(
              child: Text('暂无记忆',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
            ),
          )
        else
          for (final m in visible) _buildMemoryCard(m),
      ],
    );
  }

  Widget _buildError() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.cloud_off_outlined,
                size: 28, color: AppColors.darkOrange),
            const SizedBox(height: 10),
            Text('加载记忆失败：$_error',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: _load,
              child: const Text('重试',
                  style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFilterChips() {
    Widget chip(String? value, String label) {
      final selected = _filter == value;
      return GestureDetector(
        onTap: () => setState(() => _filter = value),
        child: Container(
          margin: const EdgeInsets.only(right: 6),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          decoration: BoxDecoration(
            color: selected
                ? AppColors.darkGreen.withValues(alpha: 0.15)
                : AppColors.darkSurface2,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(
              color: selected
                  ? AppColors.darkGreen.withValues(alpha: 0.3)
                  : AppColors.darkBorder,
              width: 0.5,
            ),
          ),
          child: Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w500,
              color: selected ? AppColors.darkGreen : AppColors.darkGrey5,
            ),
          ),
        ),
      );
    }

    return Wrap(
      children: [
        chip(null, '全部'),
        for (final (kind, label) in _kinds) chip(kind, label),
      ],
    );
  }

  Widget _buildMemoryCard(MemoryItem m) {
    final dimmed = m.superseded;

    return AppCard(
      margin: const EdgeInsets.only(bottom: 8),
      child: Opacity(
        opacity: dimmed ? 0.45 : 1,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                AppBadge(label: m.kindLabel, color: _kindColor(m.kind)),
                if (dimmed) ...[
                  const SizedBox(width: 6),
                  const AppBadge(
                    label: '已取代',
                    color: AppColors.darkGrey5,
                    icon: Icons.history,
                  ),
                ],
                const Spacer(),
                Text(formatDate(m.createdAt),
                    style: const TextStyle(
                        fontSize: 11, color: AppColors.darkGrey6)),
              ],
            ),
            const SizedBox(height: 8),
            Text(m.content,
                style: TextStyle(
                  fontSize: 13,
                  height: 1.4,
                  color: dimmed ? AppColors.darkGrey4 : AppColors.darkGrey2,
                  decoration: dimmed ? TextDecoration.lineThrough : null,
                )),
            const SizedBox(height: 6),
            Row(
              children: [
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.edit_outlined,
                      size: 16, color: AppColors.darkGrey5),
                  onPressed: () => _edit(m),
                  tooltip: '修正内容',
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
