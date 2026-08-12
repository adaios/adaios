import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../widgets/page_header.dart';

/// 记忆桌面形态 — master-detail：左日期列表 + 右内容详情。
class MemoryPage extends StatefulWidget {
  final ApiService api;

  const MemoryPage({super.key, required this.api});

  @override
  State<MemoryPage> createState() => _MemoryPageState();
}

class _MemoryPageState extends State<MemoryPage> {
  List<String> _dates = [];
  String? _selectedDate;
  List<MemoryEntryResponse> _entries = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadDates();
  }

  Future<void> _loadDates({bool force = false}) async {
    try {
      final dates = await widget.api.getMemoryDates();
      if (!mounted) return;
      setState(() {
        _dates = dates;
        _loading = false;
      });
      if (dates.isNotEmpty) _selectDate(dates.first, force: force);
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  Future<void> _selectDate(String date, {bool force = false}) async {
    setState(() {
      _selectedDate = date;
      _entries = [];
    });
    try {
      // #103：保活页刷新时 force 绕过缓存，否则记忆列表陈旧
      final entries = await widget.api.getMemory(date: date, force: force);
      if (!mounted || _selectedDate != date) return;
      setState(() => _entries = entries);
    } catch (_) {}
  }

  /// #158 桌面记忆页：待办记忆项完成操作（PATCH /memory/{id}/done → 刷新当前日）。
  Future<void> _markDone(MemoryEntryResponse m) async {
    try {
      await widget.api.markMemoryDone(m.id);
      final date = _selectedDate;
      if (!mounted || date == null) return;
      final entries = await widget.api.getMemory(date: date);
      if (!mounted || _selectedDate != date) return;
      setState(() => _entries = entries);
    } catch (_) {
      if (mounted) _showError('标记完成失败');
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      PageHeader(
        title: '记忆',
        subtitle: 'AI 理解沉淀',
        // #103：IndexedStack 保活下 initState 只拉一次，补刷新入口
        actions: [
          IconButton(
            onPressed: () => _loadDates(force: true),
            icon: const Icon(Icons.refresh, size: 16),
            color: AppColors.darkGrey4,
            tooltip: '刷新',
          ),
        ],
      ),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _dates.isEmpty
                ? const Center(child: Text('暂无记忆', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)))
                : Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SizedBox(width: 200, child: _buildDateList()),
                      const VerticalDivider(width: 1, color: AppColors.darkBorder),
                      Expanded(child: _buildEntryList()),
                    ],
                  ),
      ),
    ]);
  }

  Widget _buildDateList() {
    return ListView.builder(
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: _dates.length,
      itemBuilder: (_, i) {
        final date = _dates[i];
        final selected = date == _selectedDate;
        return GestureDetector(
          onTap: () => _selectDate(date),
          behavior: HitTestBehavior.opaque,
          child: Container(
            margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 1),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: selected ? AppColors.darkGreen.withValues(alpha: 0.12) : Colors.transparent,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              _formatDate(date),
              style: TextStyle(
                fontSize: 13,
                color: selected ? AppColors.darkGrey1 : AppColors.darkGrey4,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              ),
            ),
          ),
        );
      },
    );
  }

  String _formatDate(String date) {
    // 后端 date 形如 2026-08-02 → 今年内显示 08-02，跨年记忆补年份（REVIEW #125）
    if (date.length >= 10) {
      var y = int.tryParse(date.substring(0, 4));
      if (y != null && y == DateTime.now().year) return date.substring(5);
      return date;
    }
    return date;
  }

  Widget _buildEntryList() {
    if (_entries.isEmpty) {
      return const Center(child: Text('该日暂无记忆', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)));
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
      itemCount: _entries.length,
      itemBuilder: (_, i) => _buildMemoryCard(_entries[i]),
    );
  }

  Widget _buildMemoryCard(MemoryEntryResponse m) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.superseded
            ? AppColors.darkSurface.withValues(alpha: 0.4)
            : AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: m.superseded
              ? AppColors.darkBorder.withValues(alpha: 0.3)
              : AppColors.darkBorder,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            _kindBadge(m),
            const Spacer(),
            // #158 待办项完成操作（未完成才显示按钮，完成后 badge 变「已完成」）
            if (m.actionable && m.doneAt == null) ...[
              GestureDetector(
                onTap: () => _markDone(m),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                  decoration: BoxDecoration(
                    color: AppColors.darkGreen.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Text('完成',
                      style: TextStyle(fontSize: 12, color: AppColors.darkGreen, fontWeight: FontWeight.w600)),
                ),
              ),
              const SizedBox(width: 8),
            ],
            if (m.superseded)
              Text('已合并', style: TextStyle(fontSize: 10, color: AppColors.darkGrey5.withValues(alpha: 0.7))),
          ]),
          const SizedBox(height: 8),
          Text(
            m.summary,
            style: TextStyle(
              fontSize: 13,
              height: 1.5,
              color: m.superseded ? AppColors.darkGrey5 : AppColors.darkGrey1,
              decoration: m.superseded ? TextDecoration.lineThrough : null,
              decorationColor: AppColors.darkGrey5,
            ),
          ),
          if (m.suggestion != null) ...[
            const SizedBox(height: 6),
            Text('→ ${m.suggestion}',
                style: const TextStyle(fontSize: 12, color: AppColors.darkOrange, height: 1.4)),
          ],
          if (m.tags.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 4,
              runSpacing: 4,
              children: m.tags.map((t) => Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
                ),
                child: Text('#$t', style: const TextStyle(fontSize: 10, color: AppColors.darkGrey4)),
              )).toList(),
            ),
          ],
        ],
      ),
    );
  }

  Widget _kindBadge(MemoryEntryResponse m) {
    // 待办/已完成：actionable 是独立布尔位（actionable && doneAt），不占用 kind 分支（#133）
    if (m.actionable) {
      final (label, color) = m.doneAt != null
          ? ('已完成', AppColors.darkGrey5)
          : ('待办', AppColors.darkOrange);
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(5)),
        child: Text(label, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: color)),
      );
    }
    // 后端真实 kind（Memory.java：fact/insight/preference/pattern/decision）
    final (label, color) = switch (m.kind) {
      'preference' => ('偏好', AppColors.darkOrange),
      'pattern' => ('模式', AppColors.darkBlue),
      'decision' => ('决策', AppColors.darkGreen),
      'fact' => ('事实', AppColors.darkGrey5),
      _ => ('洞察', AppColors.darkGrey4),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(5)),
      child: Text(label, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: color)),
    );
  }
}
