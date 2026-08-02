import 'package:flutter/material.dart';
import '../../models/system_models.dart';
import '../../services/system_api_store.dart';
import '../../theme/app_colors.dart';
import '../../utils/format.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';
import '../../widgets/snack.dart';

/// 复盘页签 — 日期列表 + 生成 / 查看 / 反哺入库（真实后端 /trading/reviews）。
class ReviewsTab extends StatefulWidget {
  const ReviewsTab({super.key, required this.store});

  final SystemStore store;

  @override
  State<ReviewsTab> createState() => _ReviewsTabState();
}

class _ReviewsTabState extends State<ReviewsTab> {
  late final SystemStore _store = widget.store;

  List<TradingReview>? _reviews;
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
      final reviews = await _store.loadReviews();
      if (!mounted) return;
      setState(() {
        _reviews = reviews;
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

  Future<void> _generate(TradingReview review) async {
    final ok = await _store.generateReview(review.id);
    if (!mounted) return;
    showAppSnack(context,
        ok ? '已生成 ${review.title}' : '生成失败：后端不可用或无可生成内容',
        ok ? AppColors.darkGreen : AppColors.darkOrange);
    await _load();
  }

  Future<void> _view(TradingReview review) async {
    final content = await _store.reviewContent(review.id);
    if (!mounted) return;
    if (content == null || content.isEmpty) {
      showAppSnack(context, '该复盘尚未生成', AppColors.darkOrange);
      return;
    }
    showDialog<void>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: Text(review.title,
            style: const TextStyle(color: AppColors.darkGrey1, fontSize: 16)),
        content: SizedBox(
          width: 520,
          child: SingleChildScrollView(
            child: Text(content,
                style: const TextStyle(
                    fontSize: 13, height: 1.5, color: AppColors.darkGrey3)),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭',
                style: TextStyle(color: AppColors.darkGrey5)),
          ),
        ],
      ),
    );
  }

  /// 反哺入库：POST /trading/reviews/{date}/promote。
  Future<void> _promote(TradingReview review) async {
    final noteCtrl = TextEditingController();
    final note = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: const Text('反哺入库',
            style: TextStyle(color: AppColors.darkGrey1, fontSize: 16)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('将 ${review.title} 提升为 os/trading-os/99-inbox/ 入库候选。',
                style:
                    const TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 10),
            TextField(
              controller: noteCtrl,
              maxLines: 3,
              decoration: const InputDecoration(
                hintText: '备注（可选）',
                hintStyle: TextStyle(fontSize: 12, color: AppColors.darkGrey6),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消',
                style: TextStyle(color: AppColors.darkGrey5)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, noteCtrl.text.trim()),
            child: const Text('确认提升',
                style: TextStyle(color: AppColors.darkGreen)),
          ),
        ],
      ),
    );
    if (note == null || !mounted) return;

    try {
      final result =
          await _store.promoteReview(review.date.toString(), note: note.isEmpty ? null : note);
      if (!mounted) return;
      showAppSnack(
        context,
        '反哺成功：${result.status}（${result.path}）',
        AppColors.darkGreen,
      );
    } catch (e) {
      if (!mounted) return;
      showAppSnack(context, '反哺失败：$e', AppColors.darkOrange);
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
    final reviews = _reviews ?? const <TradingReview>[];
    final generatedCount = reviews.where((r) => r.generated).length;

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        AppCard(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _statItem('复盘总数', '${reviews.length}', AppColors.darkGrey1),
              _statItem('已生成', '$generatedCount', AppColors.darkGreen),
              _statItem('未生成', '${reviews.length - generatedCount}',
                  AppColors.darkOrange),
            ],
          ),
        ),
        const SizedBox(height: 12),
        if (reviews.isEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 40),
            child: Center(
              child: Text('暂无复盘',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
            ),
          )
        else
          for (final review in reviews) _buildReviewCard(review),
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
            Text('加载复盘失败：$_error',
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

  Widget _statItem(String label, String count, Color color) {
    return Column(children: [
      Text(count,
          style: TextStyle(
              fontSize: 18, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _buildReviewCard(TradingReview review) {
    return AppCard(
      margin: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          const Icon(Icons.description_outlined,
              size: 18, color: AppColors.darkGrey4),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(review.title,
                    style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: AppColors.darkGrey1)),
                const SizedBox(height: 3),
                Text(formatDate(review.date),
                    style: const TextStyle(
                        fontSize: 11, color: AppColors.darkGrey6)),
              ],
            ),
          ),
          AppBadge(
            label: review.generated ? '已生成' : '未生成',
            color: review.generated ? AppColors.darkGreen : AppColors.darkOrange,
          ),
          const SizedBox(width: 6),
          if (!review.generated)
            TextButton(
              onPressed: () => _generate(review),
              style: TextButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                minimumSize: Size.zero,
              ),
              child: const Text('生成',
                  style:
                      TextStyle(fontSize: 12, color: AppColors.darkGreen)),
            )
          else ...[
            TextButton(
              onPressed: () => _view(review),
              style: TextButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                minimumSize: Size.zero,
              ),
              child: const Text('查看',
                  style: TextStyle(fontSize: 12, color: AppColors.darkBlue)),
            ),
            TextButton(
              onPressed: () => _promote(review),
              style: TextButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                minimumSize: Size.zero,
              ),
              child: const Text('反哺',
                  style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
            ),
          ],
        ],
      ),
    );
  }
}
