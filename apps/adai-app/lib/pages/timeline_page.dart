import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// 时间线页 — 日历月视图 + 日记录列表。
class TimelinePage extends StatefulWidget {
  final ApiService api;

  const TimelinePage({super.key, required this.api});

  @override
  State<TimelinePage> createState() => _TimelinePageState();
}

class _TimelinePageState extends State<TimelinePage> {
  late DateTime _baseDate;
  int? _selectedDay;
  Map<int, List<TimelineEntryResponse>> _entryMap = {};
  bool _loading = true;

  static const _weekLabels = ['一', '二', '三', '四', '五', '六', '日'];

  @override
  void initState() {
    super.initState();
    _baseDate = DateTime(DateTime.now().year, DateTime.now().month);
    _load();
  }

  Future<void> _load() async {
    try {
      final entries = await widget.api.getTimeline(limit: 999);
      if (!mounted) return;
      final map = <int, List<TimelineEntryResponse>>{};
      for (final e in entries) {
        if (e.dateTime.length >= 10) {
          try {
            final dt = DateTime.parse(e.dateTime.substring(0, 10));
            if (dt.month == _baseDate.month && dt.year == _baseDate.year) {
              final day = dt.day;
              map.putIfAbsent(day, () => []);
              map[day]!.add(e);
            }
          } catch (_) {}
        }
      }
      setState(() {
        _entryMap = map;
        if (map.isNotEmpty) _selectedDay = map.keys.last;
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  int get _daysInMonth => DateTime(_baseDate.year, _baseDate.month + 1, 0).day;
  int get _startWeekday {
    final wd = DateTime(_baseDate.year, _baseDate.month, 1).weekday;
    return wd - 1;
  }

  void _prevMonth() {
    setState(() {
      _baseDate = DateTime(_baseDate.year, _baseDate.month - 1);
      _selectedDay = null;
      _loading = true;
    });
    _load();
  }

  void _nextMonth() {
    setState(() {
      _baseDate = DateTime(_baseDate.year, _baseDate.month + 1);
      _selectedDay = null;
      _loading = true;
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(
          children: [
            _buildHeader(),
            if (_loading)
              const Expanded(child: Center(child: CircularProgressIndicator()))
            else ...[
              _buildCalendar(),
              const SizedBox(height: 4),
              Divider(color: AppColors.darkBorder.withAlpha(50), height: 1),
              _buildDayEntries(),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    final today = DateTime.now();
    final isCurrentMonth = today.month == _baseDate.month && today.year == _baseDate.year;

    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      child: Column(
        children: [
          Row(
            children: [
              GestureDetector(
                onTap: () => Navigator.pop(context),
                child: Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: Icon(Icons.arrow_back, size: 20, color: AppColors.darkGrey4),
                ),
              ),
              Icon(Icons.calendar_today_outlined, size: 18, color: AppColors.darkGrey3),
              const SizedBox(width: 8),
              Text('时间线', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
              const Spacer(),
              GestureDetector(
                onTap: _prevMonth,
                child: Icon(Icons.chevron_left, size: 24, color: AppColors.darkGrey5),
              ),
              const SizedBox(width: 8),
              Text('${_baseDate.year}年${_baseDate.month}月', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
              const SizedBox(width: 8),
              GestureDetector(
                onTap: _nextMonth,
                child: Icon(Icons.chevron_right, size: 24, color: AppColors.darkGrey5),
              ),
            ],
          ),
          if (isCurrentMonth)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text('本月 ${_entryMap.length} 天有记录',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
            ),
        ],
      ),
    );
  }

  Widget _buildCalendar() {
    final today = DateTime.now();
    final isCurrentMonth = today.month == _baseDate.month && today.year == _baseDate.year;

    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: _weekLabels.map((l) => SizedBox(
              width: 32,
              child: Text(l, textAlign: TextAlign.center, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGrey5)),
            )).toList(),
          ),
          const SizedBox(height: 4),
          _buildGrid(isCurrentMonth, today),
        ],
      ),
    );
  }

  Widget _buildGrid(bool isCurrentMonth, DateTime today) {
    final cells = <Widget>[];
    for (int i = 0; i < _startWeekday; i++) {
      cells.add(const SizedBox(width: 32, height: 36));
    }
    for (int d = 1; d <= _daysInMonth; d++) {
      final hasEntry = _entryMap.containsKey(d);
      final isSelected = _selectedDay == d;
      final isToday = isCurrentMonth && today.day == d;
      cells.add(
        GestureDetector(
          onTap: () => setState(() => _selectedDay = d),
          child: Container(
            width: 32, height: 36,
            alignment: Alignment.center,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 28, height: 28,
                  decoration: BoxDecoration(
                    color: isSelected ? AppColors.darkGrey1
                        : isToday ? AppColors.darkSurface2 : Colors.transparent,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  alignment: Alignment.center,
                  child: Text('$d', style: TextStyle(
                      fontSize: 13,
                      fontWeight: isSelected || isToday ? FontWeight.w600 : FontWeight.w400,
                      color: isSelected ? AppColors.darkBg
                          : isToday ? AppColors.darkGrey1
                          : hasEntry ? AppColors.darkGrey3 : AppColors.darkGrey6)),
                ),
                if (hasEntry)
                  Container(
                    width: 3, height: 3,
                    margin: const EdgeInsets.only(top: 2),
                    decoration: BoxDecoration(
                      color: isSelected ? AppColors.darkBg : AppColors.darkGreen.withValues(alpha: 0.6),
                      shape: BoxShape.circle,
                    ),
                  )
                else
                  const SizedBox(height: 5),
              ],
            ),
          ),
        ),
      );
    }
    final totalCells = _startWeekday + _daysInMonth;
    final remainder = totalCells % 7;
    if (remainder > 0) {
      for (int i = 0; i < 7 - remainder; i++) {
        cells.add(const SizedBox(width: 32, height: 36));
      }
    }
    return Wrap(spacing: 0, runSpacing: 2, children: cells);
  }

  Widget _buildDayEntries() {
    if (_selectedDay == null || !_entryMap.containsKey(_selectedDay)) {
      return const Expanded(
        child: Center(child: Text('选择日期查看记录', style: TextStyle(fontSize: 13, color: AppColors.darkGrey6))),
      );
    }
    final entries = _entryMap[_selectedDay]!;
    return Expanded(
      child: ListView.builder(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
        itemCount: entries.length,
        itemBuilder: (_, i) {
          final e = entries[i];
          final time = e.dateTime.length >= 16 ? e.dateTime.substring(11, 16) : '';
          return Container(
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: AppColors.darkSurface.withAlpha(200),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.darkBorder.withAlpha(100)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(e.title, style: TextStyle(fontSize: 14, color: AppColors.darkGrey1)),
                if (e.tags.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 4, runSpacing: 4,
                    children: e.tags.map((t) => Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: AppColors.darkSurface2,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(t, style: TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
                    )).toList(),
                  ),
                ],
                const SizedBox(height: 4),
                Text(time, style: TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
              ],
            ),
          );
        },
      ),
    );
  }
}
