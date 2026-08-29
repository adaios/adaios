import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// Memory page - AI understanding by day with tag grouping.
/// Opens to most recent date with data.
/// P-role-02（app 记忆修正）：记忆卡可「修正」（PATCH /memory/{id}），
/// 待办记忆可「完成」（PATCH /memory/{id}/done）——P-app-03 闭环补全。
class MemoryPage extends StatefulWidget {
  final ApiService api;
  const MemoryPage({super.key, required this.api});
  @override
  State<MemoryPage> createState() => _MemoryPageState();
}

class _MemoryPageState extends State<MemoryPage> {
  DateTime _currentDate = DateTime.now();
  List<MemoryEntryResponse> _entries = [];
  bool _loading = true;
  int _loadGen = 0; // W-P2-2 代际令牌：日期连点防乱序覆盖
  String? _error; // 后端故障时的人话错误（#108 区分「故障」vs「无数据」）
  List<String> _allDates = [];
  String? _activeTag;

  @override
  void initState() {
    super.initState();
    _initDate();
  }

  Future<void> _initDate() async {
    try {
      _allDates = await widget.api.getMemoryDates();
      if (!mounted) return;
      if (_allDates.isNotEmpty) _currentDate = DateTime.parse(_allDates.first);
    } catch (_) {}
    _load();
  }

  String get _dateLabel =>
      '${_currentDate.year}-${_currentDate.month.toString().padLeft(2, '0')}-${_currentDate.day.toString().padLeft(2, '0')}';

  String _dateDisplay(DateTime d) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final yesterday = DateTime(now.year, now.month, now.day - 1);
    final date = DateTime(d.year, d.month, d.day);
    if (date == today) return '今天';
    if (date == yesterday) return '昨天';
    // REVIEW #254：与 adai-web 统一 MM-dd（补零）；跨年记忆补年份
    // （REVIEW #125，防 12/31 vs 1/1 难区分）——今年内 MM-dd，跨年 yyyy-MM-dd。
    final mmdd = '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
    if (d.year == now.year) return mmdd;
    return '${d.year}-$mmdd';
  }

  void _prevDay() {
    setState(() { _currentDate = _currentDate.subtract(const Duration(days: 1)); _loading = true; });
    _load();
  }

  void _nextDay() {
    if (_currentDate.isAfter(DateTime.now().subtract(const Duration(days: 1)))) return;
    setState(() { _currentDate = _currentDate.add(const Duration(days: 1)); _loading = true; });
    _load();
  }

  Future<void> _load() async {
    // W-P2-2（2026-08-17）：代际令牌——日期连点时旧响应不覆盖新日期（web 有守卫 app 无）
    final gen = ++_loadGen;
    try {
      final entries = await widget.api.getMemory(date: _dateLabel);
      if (!mounted || gen != _loadGen) return; // 旧代丢弃
      setState(() { _entries = entries; _loading = false; _error = null; _activeTag = null; });
    } catch (e) {
      if (mounted && gen == _loadGen) setState(() { _loading = false; _error = _errText(e); });
    }
  }

  /// 后端故障提取人话（#108：故障不再伪装成「无数据」）。
  String _errText(dynamic e) {
    final str = e.toString();
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器，请确认后端已启动';
    return '加载失败，请重试';
  }

  Widget _buildError() {
    return Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.error_outline, size: 28, color: AppColors.darkOrange),
        const SizedBox(height: 10),
        Text(_error ?? '加载失败', style: const TextStyle(fontSize: 15, color: AppColors.darkGrey4)),
        const SizedBox(height: 14),
        GestureDetector(
          onTap: _load,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
            decoration: BoxDecoration(
              color: AppColors.darkSurface2,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3)),
            ),
            child: const Text('重试', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
          ),
        ),
      ]),
    );
  }

  Map<String, int> get _tagCounts {
    final c = <String, int>{};
    for (final e in _entries) {
      for (final t in e.tags) c[t] = (c[t] ?? 0) + 1;
    }
    return c;
  }

  List<MemoryEntryResponse> get _filtered =>
      _activeTag == null ? _entries : _entries.where((e) => e.tags.contains(_activeTag)).toList();

  /// 修正记忆（P-role-02）：弹窗编辑 kind/summary/tags/actionable → PATCH /memory/{id}。
  Future<void> _edit(MemoryEntryResponse entry) async {
    final result = await showDialog<({String kind, String summary, List<String> tags, bool actionable})>(
      context: context,
      builder: (_) => _MemoryEditDialog(entry: entry),
    );
    if (result == null || !mounted) return;
    try {
      await widget.api.updateMemory(
        entry.id,
        kind: result.kind,
        summary: result.summary,
        tags: result.tags,
        actionable: result.actionable,
      );
      if (!mounted) return;
      _toast('好，我按你说的记下了');
      await _load();
    } catch (e) {
      if (!mounted) return;
      _toast('没改成功，稍后再试');
    }
  }

  /// 待办记忆完成（P-app-03 闭环）：PATCH /memory/{id}/done。
  Future<void> _done(MemoryEntryResponse entry) async {
    try {
      await widget.api.markMemoryDone(entry.id);
      if (!mounted) return;
      _toast('好，这件事完成了');
      await _load();
    } catch (e) {
      if (!mounted) return;
      _toast('没标记上，稍后再试');
    }
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(message,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
        backgroundColor: AppColors.darkSurface2,
        duration: const Duration(seconds: 2),
      ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(children: [
          _buildHeader(),
          if (_entries.isNotEmpty && _tagCounts.isNotEmpty) _buildTagBar(),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _error != null
                    ? _buildError()
                    : _entries.isEmpty
                        ? Center(child: Text('今天暂无记忆',
                            style: TextStyle(fontSize: 14, color: AppColors.darkGrey4)))
                        : _filtered.isEmpty
                            ? Center(child: Text('没有匹配「$_activeTag」的记忆',
                                style: TextStyle(fontSize: 14, color: AppColors.darkGrey4)))
                            : ListView.builder(
                                padding: const EdgeInsets.symmetric(horizontal: 20),
                                itemCount: _filtered.length,
                                itemBuilder: (_, i) => _buildCard(_filtered[i]),
                              ),
          ),
        ]),
      ),
    );
  }

  Widget _buildHeader() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      child: Row(children: [
        GestureDetector(
          onTap: () => Navigator.pop(context),
          child: Padding(
            padding: const EdgeInsets.only(right: 8),
            child: Icon(Icons.arrow_back, size: 20, color: AppColors.darkGrey4),
          ),
        ),
        Icon(Icons.psychology_outlined, size: 20, color: AppColors.darkGrey3),
        const SizedBox(width: 8),
        Text('记忆', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        if (_activeTag != null) ...[
          const SizedBox(width: 6),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: AppColors.darkGreen.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text('#$_activeTag', style: TextStyle(fontSize: 11, color: AppColors.darkGreen)),
          ),
        ],
        const Spacer(),
        GestureDetector(
          onTap: _prevDay,
          child: Icon(Icons.chevron_left, size: 24, color: AppColors.darkGrey5),
        ),
        const SizedBox(width: 8),
        Text(_dateDisplay(_currentDate), style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        const SizedBox(width: 8),
        GestureDetector(
          onTap: _currentDate.isBefore(DateTime.now().subtract(const Duration(days: 1))) ? _nextDay : null,
          child: Icon(Icons.chevron_right, size: 24,
              color: _currentDate.isAfter(DateTime.now().subtract(const Duration(days: 1)))
                  ? AppColors.darkGrey6 : AppColors.darkGrey5),
        ),
      ]),
    );
  }

  Widget _buildTagBar() {
    final entries = _tagCounts.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));
    return SizedBox(
      height: 36,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
        children: [
          _tagChip('all', _activeTag == null, _entries.length),
          ...entries.take(8).map((e) => _tagChip(e.key, _activeTag == e.key, e.value)),
        ],
      ),
    );
  }

  Widget _tagChip(String label, bool active, int count) {
    return GestureDetector(
      onTap: () => setState(() => _activeTag = active ? null : (label == 'all' ? null : label)),
      child: Container(
        margin: const EdgeInsets.only(right: 6),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: active ? AppColors.darkGreen.withValues(alpha: 0.15) : AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(7),
          border: Border.all(
              color: active ? AppColors.darkGreen.withValues(alpha: 0.3) : AppColors.darkBorder, width: 0.5),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Text(label, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500,
              color: active ? AppColors.darkGreen : AppColors.darkGrey5)),
          const SizedBox(width: 4),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
            decoration: BoxDecoration(
              color: (active ? AppColors.darkGreen : AppColors.darkGrey5).withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(3),
            ),
            child: Text('$count', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600,
                color: active ? AppColors.darkGreen : AppColors.darkGrey5)),
          ),
        ]),
      ),
    );
  }

  Widget _buildCard(MemoryEntryResponse entry) {
    final icon = entry.sentiment == 'positive' ? Icons.sentiment_satisfied_alt
        : entry.sentiment == 'negative' ? Icons.sentiment_dissatisfied : Icons.sentiment_neutral;
    final iconColor = entry.sentiment == 'positive' ? AppColors.darkGreen
        : entry.sentiment == 'negative' ? Colors.orange : AppColors.darkGrey5;

    final time = entry.createdAt.length >= 16 ? entry.createdAt.substring(11, 16) : '';
    final isSuperseded = entry.superseded;

    return Opacity(
      opacity: isSuperseded ? 0.45 : 1,
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.darkSurface.withAlpha(180),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: isSuperseded ? AppColors.darkBorder.withAlpha(40) : AppColors.darkBorder.withAlpha(80)),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          // 记忆进化：kind 徽标 + 已取代/待办标记
          Row(children: [
            _buildKindBadge(entry.kind),
            if (isSuperseded) ...[
              const SizedBox(width: 6),
              _buildMetaTag('已取代', AppColors.darkGrey5),
            ],
            // 172（2026-08-17）：已取代记忆不再显示「待办/已完成」——语义矛盾（已被新记忆替代，无待办含义）
            if (!isSuperseded && entry.actionable) ...[
              const SizedBox(width: 6),
              _buildMetaTag(entry.doneAt == null ? '待办' : '已完成', AppColors.darkOrange),
            ],
          ]),
          const SizedBox(height: 8),
          Text(entry.summary, style: TextStyle(fontSize: 14, color: AppColors.darkGrey1, height: 1.4,
              decoration: isSuperseded ? TextDecoration.lineThrough : null,
              decorationColor: AppColors.darkGrey5)),
          if (entry.tags.isNotEmpty) ...[
            const SizedBox(height: 6),
            Wrap(spacing: 4, runSpacing: 4,
              children: entry.tags.map((t) => GestureDetector(
                onTap: () => setState(() => _activeTag = t),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: _activeTag == t ? AppColors.darkGreen.withValues(alpha: 0.15) : AppColors.darkSurface2,
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(t, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500,
                      color: _activeTag == t ? AppColors.darkGreen : AppColors.darkGrey5)),
                ),
              )).toList(),
            ),
          ],
          const SizedBox(height: 4),
          Row(children: [
            Icon(icon, size: 12, color: iconColor),
            const SizedBox(width: 4),
            Text('$time  ${_dateDisplay(_currentDate)}',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
            const Spacer(),
            // P-app-03 闭环：待办记忆可「完成」（PATCH /memory/{id}/done）
            if (entry.actionable && entry.doneAt == null) ...[
              _doneButton(entry),
              const SizedBox(width: 2),
            ],
            // P-role-02：记忆可「修正」（PATCH /memory/{id}）
            IconButton(
              icon: const Icon(Icons.edit_outlined,
                  size: 15, color: AppColors.darkGrey5),
              onPressed: () => _edit(entry),
              tooltip: '修正这条记忆',
              visualDensity: VisualDensity.compact,
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
            ),
          ]),
        ]),
      ),
    );
  }

  /// 待办记忆「完成」按钮（P-app-03）。
  Widget _doneButton(MemoryEntryResponse entry) {
    return GestureDetector(
      onTap: () => _done(entry),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: AppColors.darkGreen.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
              color: AppColors.darkGreen.withValues(alpha: 0.35), width: 0.5),
        ),
        child: const Text('完成',
            style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w500,
                color: AppColors.darkGreen)),
      ),
    );
  }

  /// kind 徽标（fact/insight/preference/pattern/decision → 中文标签）
  Widget _buildKindBadge(String kind) {
    final (label, color) = switch (kind) {
      'preference' => ('偏好', AppColors.darkOrange),
      'pattern' => ('模式', AppColors.darkBlue),
      'decision' => ('决策', AppColors.darkGreen),
      'fact' => ('事实', AppColors.darkGrey5),
      _ => ('洞察', AppColors.darkGrey4),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(4)),
      child: Text(label, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: color)),
    );
  }

  /// 元信息小标签（已取代 / 待办）
  Widget _buildMetaTag(String label, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(4)),
      child: Text(label, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: color)),
    );
  }
}

/// 记忆修正弹窗 — 阿呆理解的人工修正（P-role-02）。
/// 可改 kind / summary / tags / actionable；保存返回结果记录，取消返回 null。
class _MemoryEditDialog extends StatefulWidget {
  const _MemoryEditDialog({required this.entry});

  final MemoryEntryResponse entry;

  @override
  State<_MemoryEditDialog> createState() => _MemoryEditDialogState();
}

class _MemoryEditDialogState extends State<_MemoryEditDialog> {
  static const List<(String, String)> _kinds = [
    ('insight', '洞察'),
    ('preference', '偏好'),
    ('pattern', '模式'),
    ('decision', '决策'),
    ('fact', '事实'),
  ];

  late String _kind =
      _kinds.any((k) => k.$1 == widget.entry.kind) ? widget.entry.kind : 'insight';
  late final TextEditingController _summaryCtrl =
      TextEditingController(text: widget.entry.summary);
  late final TextEditingController _tagsCtrl =
      TextEditingController(text: widget.entry.tags.join(', '));
  late bool _actionable = widget.entry.actionable;

  @override
  void dispose() {
    _summaryCtrl.dispose();
    _tagsCtrl.dispose();
    super.dispose();
  }

  List<String> get _parsedTags => _tagsCtrl.text
      .split(RegExp(r'[,，]'))
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty)
      .toList();

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface,
      title: const Text('修正这条记忆',
          style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('这是阿呆记下的内容，改完我就按新的记。',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 12),
            const Text('内容',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            const SizedBox(height: 6),
            TextField(
              key: const Key('memory-edit-summary'),
              controller: _summaryCtrl,
              maxLines: 3,
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
              decoration: InputDecoration(
                isDense: true,
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                filled: true,
                fillColor: AppColors.darkBg,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(6),
                  borderSide:
                      const BorderSide(color: AppColors.darkBorder, width: 0.5),
                ),
              ),
            ),
            const SizedBox(height: 12),
            const Text('标签（逗号分隔）',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            const SizedBox(height: 6),
            TextField(
              key: const Key('memory-edit-tags'),
              controller: _tagsCtrl,
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
              decoration: InputDecoration(
                isDense: true,
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                filled: true,
                fillColor: AppColors.darkBg,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(6),
                  borderSide:
                      const BorderSide(color: AppColors.darkBorder, width: 0.5),
                ),
              ),
            ),
            const SizedBox(height: 12),
            const Text('类型',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            const SizedBox(height: 6),
            Wrap(
              spacing: 6,
              runSpacing: 4,
              children: [
                for (final (value, label) in _kinds)
                  ChoiceChip(
                    label: Text(label,
                        style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w500,
                            color: _kind == value
                                ? AppColors.darkGreen
                                : AppColors.darkGrey5)),
                    selected: _kind == value,
                    onSelected: (_) => setState(() => _kind = value),
                    selectedColor:
                        AppColors.darkGreen.withValues(alpha: 0.18),
                    backgroundColor: AppColors.darkSurface2,
                    side: BorderSide(
                        color: _kind == value
                            ? AppColors.darkGreen.withValues(alpha: 0.4)
                            : AppColors.darkBorder,
                        width: 0.5),
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
              ],
            ),
            const SizedBox(height: 6),
            SwitchListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: const Text('标记为待办',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
              value: _actionable,
              onChanged: (v) => setState(() => _actionable = v),
              activeTrackColor: AppColors.darkGreen.withValues(alpha: 0.4),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child:
              const Text('取消', style: TextStyle(color: AppColors.darkGrey5)),
        ),
        TextButton(
          onPressed: () {
            final summary = _summaryCtrl.text.trim();
            Navigator.pop(context, (
              kind: _kind,
              summary: summary.isEmpty ? widget.entry.summary : summary,
              tags: _parsedTags,
              actionable: _actionable,
            ));
          },
          child: const Text('保存',
              style: TextStyle(color: AppColors.darkGreen)),
        ),
      ],
    );
  }
}
