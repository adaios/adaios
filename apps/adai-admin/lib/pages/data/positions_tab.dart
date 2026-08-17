import 'package:flutter/material.dart';
import '../../models/data_models.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../utils/format.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';

/// 持仓页签 — 表格列表（symbol / name / quantity / avgCost / currentPrice / 盈亏）。
/// 真实后端 /trading/positions。
class PositionsTab extends StatefulWidget {
  const PositionsTab({super.key, required this.store});

  final DataStore store;

  @override
  State<PositionsTab> createState() => _PositionsTabState();
}

class _PositionsTabState extends State<PositionsTab> {
  static const _headers = <String>['代码', '名称', '数量', '成本价', '现价', '盈亏', '市值'];

  late final DataStore _store = widget.store;

  List<Position>? _positions;
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
      final positions = await _store.loadPositions();
      if (!mounted) return;
      setState(() {
        _positions = positions;
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
    final positions = _positions ?? const <Position>[];

    final totalValue = positions.fold<double>(0, (s, p) => s + p.marketValue);
    final totalProfit = positions.fold<double>(0, (s, p) => s + p.profit);

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        AppCard(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _statItem('持仓数', '${positions.length}', AppColors.darkGrey1),
              _statItem('总市值', formatPrice(totalValue), AppColors.darkBlue),
              _statItem('浮动盈亏', formatPrice(totalProfit),
                  totalProfit >= 0 ? AppColors.darkRed : AppColors.darkGreen),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _buildTable(positions),
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
            Text('加载持仓失败：$_error',
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

  Widget _statItem(String label, String value, Color color) {
    return Column(children: [
      Text(value,
          style: TextStyle(
              fontSize: 16, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _buildTable(List<Position> positions) {
    // 表头
    final header = Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(10)),
        border: Border.all(color: AppColors.darkBorder, width: 0.5),
      ),
      child: Row(
        children: [
          for (final h in _headers)
            Expanded(
              flex: h == '名称' ? 2 : 1,
              child: Text(h,
                  style: const TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: AppColors.darkGrey5)),
            ),
        ],
      ),
    );

    return Column(
      children: [
        header,
        for (final p in positions) _buildRow(p),
      ],
    );
  }

  Widget _buildRow(Position p) {
    // 红涨绿亏（A股，2026-08-17 走查）：盈=红、亏=绿（此前绿/橙与 web 端相反）
    final profitColor =
        p.profit >= 0 ? AppColors.darkRed : AppColors.darkGreen;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        border: Border.all(color: AppColors.darkBorder, width: 0.5),
      ),
      child: Row(
        children: [
          Expanded(
            flex: 1,
            child: AppBadge(
                label: p.symbol,
                color: AppColors.darkBlue,
                icon: Icons.tag),
          ),
          Expanded(
            flex: 2,
            child: Text(p.name,
                style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: AppColors.darkGrey1)),
          ),
          Expanded(
            flex: 1,
            child: Text(_qty(p.quantity),
                style:
                    const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
          ),
          Expanded(
            flex: 1,
            child: Text(formatPrice(p.avgCost),
                style:
                    const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
          ),
          Expanded(
            flex: 1,
            child: Text(formatPrice(p.currentPrice),
                style:
                    const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
          ),
          Expanded(
            flex: 1,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(formatPrice(p.profit),
                    style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: profitColor)),
                Text(formatPercent(p.profitPercent),
                    style:
                        TextStyle(fontSize: 10, color: profitColor)),
              ],
            ),
          ),
          Expanded(
            flex: 1,
            child: Text(formatPrice(p.marketValue),
                style:
                    const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
          ),
        ],
      ),
    );
  }

  String _qty(double q) => q == q.roundToDouble()
      ? q.toInt().toString()
      : q.toStringAsFixed(2);
}
