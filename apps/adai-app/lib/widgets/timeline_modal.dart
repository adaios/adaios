import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// Timeline modal — calendar-style header with date selection.
/// Fetches data from API instead of Mock.
class TimelineModal extends StatefulWidget {
  final ApiService api;

  const TimelineModal({super.key, required this.api});

  static Future<void> show(BuildContext context, {required ApiService api}) {
    return showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (_) => TimelineModal(api: api),
    );
  }

  @override
  State<TimelineModal> createState() => _TimelineModalState();
}

class _TimelineModalState extends State<TimelineModal> {
  final DateTime _baseDate = DateTime(2026, 7);
  final Map<int, List<String>> _entryMap = {};
  int? _selectedDay;
  bool _loading = true;

  static const List<String> _weekLabels = ['一', '二', '三', '四', '五', '六', '日'];

  @override
  void initState() {
    super.initState();
    _loadTimeline();
  }

  Future<void> _loadTimeline() async {
    try {
      final entries = await widget.api.getTimeline(
        date: '${_baseDate.year}-${_baseDate.month.toString().padLeft(2, '0')}',
      );
      final map = <int, List<String>>{};
      for (final e in entries) {
        if (e.dateTime.length >= 10) {
          try {
            final day = DateTime.parse(e.dateTime.substring(0, 10)).day;
            final time = e.dateTime.length >= 16 ? e.dateTime.substring(11, 16) : '';
            map.putIfAbsent(day, () => []);
            map[day]!.add('$time  ${e.title}');
          } catch (_) {}
        }
      }
      setState(() {
        _entryMap..clear()..addAll(map);
        if (_entryMap.isNotEmpty) {
          _selectedDay = _entryMap.keys.last;
        }
        _loading = false;
      });
    } catch (_) {
      setState(() => _loading = false);
    }
  }

  int get _daysInMonth => DateTime(_baseDate.year, _baseDate.month + 1, 0).day;
  int get _startWeekday {
    final wd = DateTime(_baseDate.year, _baseDate.month, 1).weekday;
    return wd - 1;
  }

  @override
  Widget build(BuildContext context) {
    final entries = _selectedDay != null ? _entryMap[_selectedDay] : null;
    final today = DateTime.now();
    final isCurrentMonth = today.month == _baseDate.month && today.year == _baseDate.year;

    return Container(
      height: MediaQuery.of(context).size.height * 0.75,
      padding: const EdgeInsets.fromLTRB(0, 16, 0, 0),
      decoration: const BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: Container(
              width: 36, height: 4,
              margin: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(
                color: AppColors.darkGrey5.withValues(alpha: 0.3),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 24),
            child: Text('时间线', style: TextStyle(
                fontSize: 22, fontWeight: FontWeight.w600,
                color: AppColors.darkGrey1, letterSpacing: -0.3)),
          ),
          const SizedBox(height: 16),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(
              children: [
                Text('${_baseDate.year} 年 ${_baseDate.month} 月',
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w500,
                        color: AppColors.darkGrey4)),
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: _weekLabels.map((l) => SizedBox(
                    width: 32,
                    child: Text(l, textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGrey5)),
                  )).toList(),
                ),
                const SizedBox(height: 4),
                _buildCalendarGrid(isCurrentMonth, today),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Divider(color: AppColors.darkBorder.withValues(alpha: 0.3), height: 1),
          ),
          if (_loading)
            const Expanded(child: Center(child: CircularProgressIndicator()))
          else if (_selectedDay != null && entries != null) ...[
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 12, 24, 4),
              child: Text('7月$_selectedDay日',
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
            ),
            Expanded(
              child: ListView.builder(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                itemCount: entries.length,
                itemBuilder: (context, i) => Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        width: 5, height: 5,
                        margin: const EdgeInsets.only(top: 7, right: 10),
                        decoration: BoxDecoration(
                          color: AppColors.darkGrey5.withValues(alpha: 0.5),
                          shape: BoxShape.circle,
                        ),
                      ),
                      Expanded(
                        child: Text(entries[i], style: const TextStyle(
                            fontSize: 14, height: 1.6, color: AppColors.darkGrey1)),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ] else
            const Expanded(child: Center(child: Text('这天没有记录',
                style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)))),
        ],
      ),
    );
  }

  Widget _buildCalendarGrid(bool isCurrentMonth, DateTime today) {
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
}
