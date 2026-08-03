import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../widgets/page_header.dart';

/// 时间线桌面形态 — 左月历面板 + 右当月记录。
class TimelinePage extends StatefulWidget {
  final ApiService api;

  const TimelinePage({super.key, required this.api});

  @override
  State<TimelinePage> createState() => _TimelinePageState();
}

class _TimelinePageState extends State<TimelinePage> {
  List<TimelineEntryResponse> _entries = [];
  DateTime _month = DateTime(DateTime.now().year, DateTime.now().month);
  String? _selectedDay;
  bool _loading = true;

  /// 有记录的日期集合（yyyy-MM-dd）。
  late final Set<String> _daysWithEntries = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final entries = await widget.api.getTimeline(limit: 500);
      if (!mounted) return;
      setState(() {
        _entries = entries;
        _daysWithEntries.clear();
        _daysWithEntries.addAll(entries.map((e) => _dateOf(e.dateTime)));
        _loading = false;
      });
      _selectedDay ??= _today();
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  String _dateOf(String dateTime) => dateTime.length >= 10 ? dateTime.substring(0, 10) : '';
  String _today() {
    final now = DateTime.now();
    return '${now.year.toString().padLeft(4, '0')}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
  }

  void _changeMonth(int delta) {
    setState(() => _month = DateTime(_month.year, _month.month + delta));
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      const PageHeader(title: '时间线', subtitle: '记录的时间序列'),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  SizedBox(width: 280, child: _buildCalendar()),
                  const VerticalDivider(width: 1, color: AppColors.darkBorder),
                  Expanded(child: _buildDayEntries()),
                ],
              ),
      ),
    ]);
  }

  Widget _buildCalendar() {
    final firstDay = DateTime(_month.year, _month.month, 1);
    final daysInMonth = DateTime(_month.year, _month.month + 1, 0).day;
    final leading = firstDay.weekday - 1; // 周一开头
    const weekLabels = ['一', '二', '三', '四', '五', '六', '日'];

    return Column(children: [
      // 月切换
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
        child: Row(children: [
          IconButton(
            onPressed: () => _changeMonth(-1),
            icon: const Icon(Icons.chevron_left, size: 18),
            color: AppColors.darkGrey4,
          ),
          Expanded(
            child: Text(
              '${_month.year}年${_month.month}月',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1),
            ),
          ),
          IconButton(
            onPressed: () => _changeMonth(1),
            icon: const Icon(Icons.chevron_right, size: 18),
            color: AppColors.darkGrey4,
          ),
        ]),
      ),
      // 星期表头
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Row(
          children: weekLabels.map((l) => Expanded(
            child: Center(
              child: Text(l, style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
            ),
          )).toList(),
        ),
      ),
      const SizedBox(height: 4),
      // 日期网格
      Expanded(
        child: GridView.count(
          crossAxisCount: 7,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          childAspectRatio: 1.1,
          children: [
            for (var i = 0; i < leading; i++) const SizedBox.shrink(),
            for (var day = 1; day <= daysInMonth; day++)
              _buildDayCell(day, DateTime(_month.year, _month.month, day)),
          ],
        ),
      ),
    ]);
  }

  Widget _buildDayCell(int day, DateTime date) {
    final dateStr = '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
    final selected = dateStr == _selectedDay;
    final hasEntry = _daysWithEntries.contains(dateStr);

    return GestureDetector(
      onTap: () => setState(() => _selectedDay = dateStr),
      child: Container(
        margin: const EdgeInsets.all(2),
        decoration: BoxDecoration(
          color: selected ? AppColors.darkGreen.withValues(alpha: 0.18) : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
          border: selected ? Border.all(color: AppColors.darkGreen.withValues(alpha: 0.6)) : null,
        ),
        child: Stack(alignment: Alignment.center, children: [
          Text('$day',
              style: TextStyle(
                fontSize: 12,
                color: selected ? AppColors.darkGrey1 : AppColors.darkGrey3,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w400,
              )),
          if (hasEntry)
            Positioned(
              bottom: 3,
              child: Container(
                width: 4,
                height: 4,
                decoration: BoxDecoration(
                  color: hasEntry ? AppColors.darkGreen : Colors.transparent,
                  shape: BoxShape.circle,
                ),
              ),
            ),
        ]),
      ),
    );
  }

  Widget _buildDayEntries() {
    final dayEntries = _selectedDay == null
        ? <TimelineEntryResponse>[]
        : _entries.where((e) => _dateOf(e.dateTime) == _selectedDay).toList();
    if (dayEntries.isEmpty) {
      return const Center(child: Text('该日暂无记录', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)));
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
      itemCount: dayEntries.length,
      itemBuilder: (_, i) {
        final e = dayEntries[i];
        return Container(
          margin: const EdgeInsets.only(bottom: 8),
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: AppColors.darkSurface,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: AppColors.darkBorder),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: AppColors.darkGreen.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(5),
                  ),
                  child: Text(e.type,
                      style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
                ),
                const Spacer(),
                Text(_timeOf(e.dateTime),
                    style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              ]),
              const SizedBox(height: 8),
              Text(e.title, style: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGrey1)),
              if (e.mediaPath != null) ...[
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerLeft,
                  child: GestureDetector(
                    onTap: () => _showFullImage(e.id),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: Image.network(
                        widget.api.mediaUrl(e.id),
                        headers: widget.api.mediaHeaders,
                        width: 120,
                        height: 90,
                        fit: BoxFit.cover,
                        errorBuilder: (_, _, _) => Container(
                          width: 120, height: 90,
                          color: AppColors.darkSurface2,
                          child: const Icon(Icons.broken_image_outlined, size: 20, color: AppColors.darkGrey5),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
              if (e.tags.isNotEmpty) ...[
                const SizedBox(height: 6),
                Wrap(
                  spacing: 4,
                  runSpacing: 4,
                  children: e.tags.map((t) => Text('#$t',
                      style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5))).toList(),
                ),
              ],
            ],
          ),
        );
      },
    );
  }

  String _timeOf(String dateTime) {
    if (dateTime.length < 16) return '';
    return dateTime.substring(11, 16);
  }

  /// 点击缩略图 → 全图 Dialog（点任意处关闭）。
  void _showFullImage(String id) {
    showDialog(
      context: context,
      builder: (_) => Dialog(
        backgroundColor: Colors.transparent,
        insetPadding: const EdgeInsets.all(24),
        child: GestureDetector(
          onTap: () => Navigator.pop(context),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: Image.network(widget.api.mediaUrl(id), headers: widget.api.mediaHeaders, fit: BoxFit.contain),
          ),
        ),
      ),
    );
  }
}
