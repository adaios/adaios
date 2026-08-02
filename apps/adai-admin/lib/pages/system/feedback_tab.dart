import 'package:flutter/material.dart';
import '../../models/system_models.dart';
import '../../services/system_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';
import '../../widgets/snack.dart';

/// 知识反哺页签 — 真实规则冲突（/trading/knowledge/conflicts）。
/// 冲突的「已处理」标记为前端本地状态（后端为规则对照结果，无持久化）。
class FeedbackTab extends StatefulWidget {
  const FeedbackTab({super.key, required this.store});

  final SystemStore store;

  @override
  State<FeedbackTab> createState() => _FeedbackTabState();
}

class _FeedbackTabState extends State<FeedbackTab> {
  late final SystemStore _store = widget.store;

  List<ConflictItem>? _conflicts;
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
      final conflicts = await _store.loadConflicts();
      if (!mounted) return;
      setState(() {
        _conflicts = conflicts;
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

  void _toggleConflict(ConflictItem c) {
    setState(() => c.handled = !c.handled);
    showAppSnack(
        context, c.handled ? '已标记冲突为已处理' : '已撤销标记', AppColors.darkGreen);
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
    final conflicts = _conflicts ?? const <ConflictItem>[];

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        _sectionHeader(Icons.rule, 'Conflicts 冲突'),
        const SizedBox(height: 8),
        if (conflicts.isEmpty)
          const _EmptyHint('暂无冲突（当前持仓与交易规则对照通过）')
        else
          AppCard(
            child: Column(
              children: [
                for (final c in conflicts) _buildConflict(c),
              ],
            ),
          ),
        const SizedBox(height: 16),
        const _EmptyHint('复盘反哺入库操作在「复盘」页签对已生成复盘点击「反哺」按钮。'),
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
            Text('加载冲突失败：$_error',
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

  Widget _sectionHeader(IconData icon, String title) {
    return Row(
      children: [
        Icon(icon, size: 16, color: AppColors.darkGrey4),
        const SizedBox(width: 6),
        Text(title,
            style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: AppColors.darkGrey1)),
      ],
    );
  }

  Widget _buildConflict(ConflictItem c) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          AppBadge(
            label: c.handled ? '已解决' : '冲突',
            color: c.handled ? AppColors.darkGreen : AppColors.darkOrange,
            icon: c.handled ? null : Icons.error_outline,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _conflictSide('规则', c.sideA),
                const SizedBox(height: 4),
                _conflictSide('说明', c.sideB),
              ],
            ),
          ),
          IconButton(
            icon: Icon(
              c.handled ? Icons.undo : Icons.done_outline,
              size: 16,
              color: c.handled ? AppColors.darkGrey5 : AppColors.darkGreen,
            ),
            onPressed: () => _toggleConflict(c),
            tooltip: c.handled ? '撤销已处理' : '标记已处理',
            visualDensity: VisualDensity.compact,
          ),
        ],
      ),
    );
  }

  Widget _conflictSide(String tag, String text) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$tag · ',
            style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: AppColors.darkGrey5)),
        Expanded(
          child: Text(text,
              style: TextStyle(
                  fontSize: 12, height: 1.3, color: AppColors.darkGrey3)),
        ),
      ],
    );
  }
}

class _EmptyHint extends StatelessWidget {
  const _EmptyHint(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Center(
        child: Text(text,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
      ),
    );
  }
}
