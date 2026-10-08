import 'package:flutter/material.dart';

import '../services/api_service.dart';
import '../theme/app_colors.dart';
import '../widgets/review_dialog.dart';

/// 复盘历史（2026-10-09 app 端体验重构批 4）。
///
/// **为什么现在才有**：后端 `GET /trading/reviews`（列日期）与 `GET /trading/reviews/{date}`
/// （读当天）+ app 的 `getReviewDates()/getReview(date:)` **早就都有了**，但没有任何页面调用
/// ——是个「零入口」能力（同 P2-认知3 的形态）。这一页只做前端闭环：列日期（倒序）→ 点开看当天。
class ReviewHistoryPage extends StatefulWidget {
  final ApiService api;

  const ReviewHistoryPage({super.key, required this.api});

  @override
  State<ReviewHistoryPage> createState() => _ReviewHistoryPageState();
}

class _ReviewHistoryPageState extends State<ReviewHistoryPage> {
  List<String>? _dates;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final ds = await widget.api.getReviewDates();
      if (!mounted) return;
      // 倒序：最近的复盘在最上面（后端顺序不保证）
      final sorted = List<String>.of(ds)..sort((a, b) => b.compareTo(a));
      setState(() {
        _dates = sorted;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      // 取不到就如实说；不摆一个「暂无复盘」的空壳（那会被读成「我没写过复盘」）
      setState(() => _error = '复盘没取到，稍后再试');
    }
  }

  Future<void> _open(String date) async {
    ReviewResponse? review;
    try {
      review = await widget.api.getReview(date: date);
    } catch (_) {
      review = null;
    }
    if (!mounted) return;
    if (review == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('这天的复盘没取到', style: TextStyle(fontSize: 13)),
        backgroundColor: AppColors.darkSurface2,
      ));
      return;
    }
    final r = review; // 提升为非空，供闭包使用
    await showDialog<void>(
      context: context,
      builder: (_) => ReviewDialog(review: r, onPromote: () => _promote(r.date)),
    );
  }

  Future<void> _promote(String date) async {
    try {
      final result = await widget.api.promoteReview(date: date);
      if (!mounted) return;
      Navigator.pop(context); // 关掉复盘弹窗
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(result.message.isEmpty ? '已写入入库候选' : result.message, style: const TextStyle(fontSize: 13)),
        backgroundColor: AppColors.darkSurface2,
      ));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('反哺失败，稍后再试', style: TextStyle(fontSize: 13)),
        backgroundColor: AppColors.darkSurface2,
      ));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkBg,
        foregroundColor: AppColors.darkGrey1,
        title: const Text('复盘历史', style: TextStyle(fontSize: 16)),
      ),
      body: _error != null
          ? Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Text(_error!, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
                const SizedBox(height: 12),
                TextButton(onPressed: _load, child: const Text('重试')),
              ]),
            )
          : _dates == null
              ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
              : _dates!.isEmpty
                  ? const Center(
                      child: Text('还没有复盘——有成交的那天我会写一份',
                          style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)))
                  : ListView.separated(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                      itemCount: _dates!.length,
                      separatorBuilder: (_, __) =>
                          const Divider(height: 1, color: AppColors.darkBorder, thickness: 0.5),
                      itemBuilder: (_, i) {
                        final d = _dates![i];
                        return InkWell(
                          onTap: () => _open(d),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 14),
                            child: Row(children: [
                              const Icon(Icons.article_outlined, size: 16, color: AppColors.darkGrey4),
                              const SizedBox(width: 10),
                              Text(d, style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1)),
                              const Spacer(),
                              const Text('看这天的复盘',
                                  style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
                              const SizedBox(width: 4),
                              const Icon(Icons.chevron_right, size: 16, color: AppColors.darkGrey5),
                            ]),
                          ),
                        );
                      },
                    ),
    );
  }
}
