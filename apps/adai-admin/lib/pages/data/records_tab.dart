import 'package:flutter/material.dart';
import '../../models/data_models.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../utils/format.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';

/// 记录页签 — 治理视角查看记录列表（类型徽标 + 标签 + 时间，真实后端 Feed）。
/// 只读：记录删除归用户端 app/web（P-role-03），admin 不提供删除。
class RecordsTab extends StatefulWidget {
  const RecordsTab({super.key, required this.store});

  final DataStore store;

  @override
  State<RecordsTab> createState() => _RecordsTabState();
}

class _RecordsTabState extends State<RecordsTab> {
  late final DataStore _store = widget.store;

  List<ContentRecord>? _records;
  String? _error;
  bool _loading = true;

  Color get _typeColor => AppColors.darkBlue;

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
      final records = await _store.loadRecords();
      if (!mounted) return;
      setState(() {
        _records = records;
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
    final records = _records ?? const <ContentRecord>[];
    final counts = _countByType(records);

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        _statsRow(records.length, counts),
        const SizedBox(height: 12),
        if (records.isEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 40),
            child: Center(
              child: Text('暂无记录',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
            ),
          )
        else
          for (final r in records)
            AppCard(
              margin: const EdgeInsets.only(bottom: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      AppBadge(label: r.typeLabel, color: _typeColor),
                      const SizedBox(width: 6),
                      AppBadge(
                        label: r.id,
                        color: AppColors.darkGrey5,
                        icon: Icons.tag,
                      ),
                      const Spacer(),
                      Text(formatDateTime(r.createdAt),
                          style: const TextStyle(
                              fontSize: 11, color: AppColors.darkGrey6)),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(r.content,
                      style: const TextStyle(
                          fontSize: 13,
                          height: 1.4,
                          color: AppColors.darkGrey2)),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      for (final tag in r.tags)
                        Padding(
                          padding: const EdgeInsets.only(right: 6),
                          child: AppBadge(
                              label: tag, color: AppColors.darkPurple),
                        ),
                    ],
                  ),
                ],
              ),
            ),
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
            Text('加载记录失败：$_error',
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

  Widget _statsRow(int total, Map<String, int> counts) {
    return AppCard(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _statItem('全部', total, AppColors.darkGrey1),
          _statItem('陈述', counts['statement'] ?? 0, AppColors.darkBlue),
          _statItem('提问', counts['question'] ?? 0, AppColors.darkPurple),
          _statItem('待办', counts['todo'] ?? 0, AppColors.darkOrange),
        ],
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

  Map<String, int> _countByType(List<ContentRecord> records) {
    final map = <String, int>{};
    for (final r in records) {
      map[r.type] = (map[r.type] ?? 0) + 1;
    }
    return map;
  }
}
