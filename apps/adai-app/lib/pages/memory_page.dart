import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// 记忆页 — 按日展示 AI 理解沉淀。
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

  @override
  void initState() {
    super.initState();
    _load();
  }

  String get _dateLabel {
    return '${_currentDate.year}-${_currentDate.month.toString().padLeft(2, '0')}-${_currentDate.day.toString().padLeft(2, '0')}';
  }

  void _prevDay() {
    setState(() {
      _currentDate = _currentDate.subtract(const Duration(days: 1));
      _loading = true;
    });
    _load();
  }

  void _nextDay() {
    setState(() {
      _currentDate = _currentDate.add(const Duration(days: 1));
      _loading = true;
    });
    _load();
  }

  Future<void> _load() async {
    try {
      final entries = await widget.api.getMemory(date: _dateLabel);
      if (!mounted) return;
      setState(() {
        _entries = entries;
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(
          children: [
            _buildHeader(),
            const SizedBox(height: 8),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _entries.isEmpty
                      ? Center(
                          child: Text('这天没有记录',
                              style: TextStyle(fontSize: 14, color: AppColors.darkGrey6)))
                      : ListView.builder(
                          padding: const EdgeInsets.symmetric(horizontal: 20),
                          itemCount: _entries.length,
                          itemBuilder: (_, i) => _buildCard(_entries[i]),
                        ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      child: Row(
        children: [
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
          const Spacer(),
          GestureDetector(
            onTap: _prevDay,
            child: Icon(Icons.chevron_left, size: 24, color: AppColors.darkGrey5),
          ),
          const SizedBox(width: 8),
          Text(_dateLabel, style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
          const SizedBox(width: 8),
          GestureDetector(
            onTap: _nextDay,
            child: Icon(Icons.chevron_right, size: 24, color: AppColors.darkGrey5),
          ),
        ],
      ),
    );
  }

  Widget _buildCard(MemoryEntryResponse entry) {
    final sentimentIcon = entry.sentiment == 'positive'
        ? Icons.sentiment_satisfied_alt
        : entry.sentiment == 'negative'
            ? Icons.sentiment_dissatisfied
            : Icons.sentiment_neutral;
    final sentimentColor = entry.sentiment == 'positive'
        ? AppColors.darkGreen
        : entry.sentiment == 'negative'
            ? Colors.orange
            : AppColors.darkGrey5;

    final time = entry.createdAt.length >= 16 ? entry.createdAt.substring(11, 16) : '';

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.darkSurface.withAlpha(200),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.darkBorder.withAlpha(100)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(entry.summary, style: TextStyle(fontSize: 15, color: AppColors.darkGrey1, height: 1.4)),
              ),
            ],
          ),
          if (entry.tags.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 4,
              runSpacing: 4,
              children: entry.tags.map((t) => Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(t, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              )).toList(),
            ),
          ],
          const SizedBox(height: 6),
          Row(
            children: [
              Icon(sentimentIcon, size: 14, color: sentimentColor),
              const SizedBox(width: 4),
              Text(time, style: TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
            ],
          ),
        ],
      ),
    );
  }
}
