import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// Memory page - AI understanding by day with tag grouping.
/// Opens to most recent date with data.
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
    if (date == today) return 'today';
    if (date == yesterday) return 'yesterday';
    return '${d.month}/${d.day}';
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
    try {
      final entries = await widget.api.getMemory(date: _dateLabel);
      if (!mounted) return;
      setState(() { _entries = entries; _loading = false; _activeTag = null; });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
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
                : _entries.isEmpty
                    ? Center(child: Text('no memories today',
                        style: TextStyle(fontSize: 14, color: AppColors.darkGrey6)))
                    : _filtered.isEmpty
                        ? Center(child: Text('no matches for "$_activeTag"',
                            style: TextStyle(fontSize: 14, color: AppColors.darkGrey6)))
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
        Text('memory', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
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
            child: Text('$count', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w600,
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

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface.withAlpha(180),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder.withAlpha(80)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(entry.summary, style: TextStyle(fontSize: 14, color: AppColors.darkGrey1, height: 1.4)),
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
                child: Text(t, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w500,
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
              style: TextStyle(fontSize: 10, color: AppColors.darkGrey6)),
        ]),
      ]),
    );
  }
}
