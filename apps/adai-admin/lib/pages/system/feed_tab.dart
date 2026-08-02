import 'package:flutter/material.dart';
import '../../models/system_models.dart';
import '../../services/system_api_store.dart';
import '../../theme/app_colors.dart';
import '../../utils/format.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';

/// Feed 预览页签 — 条目列表，type 徽标区分 record/card/action/market/ai_note。
class FeedTab extends StatefulWidget {
  const FeedTab({super.key, required this.store});

  final SystemStore store;

  @override
  State<FeedTab> createState() => _FeedTabState();
}

class _FeedTabState extends State<FeedTab> {
  late final SystemStore _store = widget.store;

  List<FeedItem>? _feed;
  String? _error;
  bool _loading = true;

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
      final feed = await _store.loadFeed();
      if (!mounted) return;
      setState(() {
        _feed = feed;
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

  Color _typeColor(String type) => switch (type) {
        'record' => AppColors.darkBlue,
        'card' => AppColors.darkPurple,
        'action' => AppColors.darkOrange,
        'market' => AppColors.darkGreen,
        'ai_note' => AppColors.darkYellow,
        _ => AppColors.darkGrey5,
      };

  IconData _typeIcon(String type) => switch (type) {
        'record' => Icons.notes_outlined,
        'card' => Icons.folder_copy_outlined,
        'action' => Icons.check_circle_outline,
        'market' => Icons.candlestick_chart_outlined,
        'ai_note' => Icons.auto_awesome_outlined,
        _ => Icons.circle_outlined,
      };

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
    final feed = _feed ?? const <FeedItem>[];

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        AppCard(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              for (final (type, label) in const [
                ('record', '记录'),
                ('card', '卡片'),
                ('action', '动作'),
                ('market', '行情'),
              ])
                _statItem(label, feed.where((f) => f.type == type).length,
                    _typeColor(type)),
            ],
          ),
        ),
        const SizedBox(height: 12),
        if (feed.isEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 40),
            child: Center(
              child: Text('暂无 Feed 条目',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
            ),
          )
        else
          for (final item in feed) _buildFeedCard(item),
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
            Text('加载 Feed 失败：$_error',
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

  Widget _statItem(String label, int count, Color color) {
    return Column(children: [
      Text('$count',
          style: TextStyle(
              fontSize: 18, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _buildFeedCard(FeedItem item) {
    return AppCard(
      margin: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Icon(_typeIcon(item.type), size: 18, color: _typeColor(item.type)),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.title,
                    style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: AppColors.darkGrey1)),
                const SizedBox(height: 3),
                Text(item.subtitle,
                    style: const TextStyle(
                        fontSize: 12, color: AppColors.darkGrey4)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              AppBadge(label: item.typeLabel, color: _typeColor(item.type)),
              const SizedBox(height: 4),
              Text(formatDateTime(item.time),
                  style: const TextStyle(
                      fontSize: 10, color: AppColors.darkGrey6)),
            ],
          ),
        ],
      ),
    );
  }
}
