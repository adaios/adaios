import 'package:flutter/material.dart';
import '../../models/system_models.dart';
import '../../services/system_api_store.dart';
import '../../theme/app_colors.dart';
import '../../utils/format.dart';
import '../../widgets/app_card.dart';

/// 行情快照页签 — 持仓实时价（真实后端 /trading/positions）。
class MarketTab extends StatefulWidget {
  const MarketTab({super.key, required this.store});

  final SystemStore store;

  @override
  State<MarketTab> createState() => _MarketTabState();
}

class _MarketTabState extends State<MarketTab> {
  late final SystemStore _store = widget.store;

  List<PositionQuote>? _quotes;
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
      final quotes = await _store.loadQuotes();
      if (!mounted) return;
      setState(() {
        _quotes = quotes;
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

  // 红涨绿亏（A股，2026-08-17 走查）：涨=红、跌=绿（此前绿/橙与 web 端相反）
  Color _changeColor(double v) =>
      v >= 0 ? AppColors.darkRed : AppColors.darkGreen;

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
    final quotes = _quotes ?? const <PositionQuote>[];

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        _sectionHeader(Icons.bar_chart, '持仓实时价'),
        const SizedBox(height: 8),
        if (quotes.isEmpty)
          const AppCard(
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 24),
              child: Center(
                child: Text('暂无持仓数据',
                    style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
              ),
            ),
          )
        else
          AppCard(
            child: Column(
              children: [
                for (final q in quotes) _buildQuoteRow(q),
              ],
            ),
          ),
        const SizedBox(height: 16),
        const Text(
          '大盘指数行情已内嵌在 Feed 的 type=market 条目中（后端行情源 60s 缓存）。',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey6),
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
            Text('加载行情失败：$_error',
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

  Widget _buildQuoteRow(PositionQuote q) {
    final color = _changeColor(q.changePercent);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          SizedBox(
            width: 76,
            child: Text(q.name,
                style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: AppColors.darkGrey1)),
          ),
          Text(q.symbol,
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
          const Spacer(),
          Text(formatPrice(q.price),
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
          SizedBox(
            width: 74,
            child: Text(
              formatPercent(q.changePercent),
              textAlign: TextAlign.right,
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: color),
            ),
          ),
        ],
      ),
    );
  }
}
