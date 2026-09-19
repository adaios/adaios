import 'package:flutter/material.dart';

import '../services/api_service.dart';
import '../theme/app_colors.dart';

/// 收益日历（P2-交易52，2026-09-16 用户：「把每日、每周、每月收益加进去，
/// 大致就是整个日历，像通达信等券商 app 那样展示」）。
///
/// **数据只有一个来源**：`GET /trading/equity-curve` 的 `dailyPnl`——券商口径的逐日盈亏，
/// 与账户卡上的「今日 / 本周 / 本月」、每晚复盘走的是同一份回放。本页**不做任何自己的
/// 差分计算**，否则就是「卡片一个数、日历另一个数」（P2-交易50/51 的教训）。
///
/// 某天缺收盘价时后端如实留空 → 格子里显示「—」，**绝不写 ¥0.00**（那等于编造一个
/// 「当天不赚不亏」）。
class ProfitCalendarPage extends StatefulWidget {
  final ApiService api;

  const ProfitCalendarPage({super.key, required this.api});

  @override
  State<ProfitCalendarPage> createState() => _ProfitCalendarPageState();
}

class _ProfitCalendarPageState extends State<ProfitCalendarPage> {
  EquityCurveDto? _curve;
  /// B4（2026-09-18）：本月合计优先用**与账户卡同一份**数据（`GET /trading/pnl-periods`）。
  /// 缘由：原来这里是**前端**遍历 equity-curve 求和，而账户卡「本月」是**后端**算的
  /// （UI/UX 审查 P2-10）——同一屏两个入口可能给出两个数（P2-交易50/51 同族教训）。
  PnlPeriodsDto? _periods;
  String? _error;
  bool _loading = true;
  late DateTime _month;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _month = DateTime(now.year, now.month);
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final curve = await widget.api.getEquityCurve();
      // best-effort：本月口径（与账户卡同源）；失败不影响日历本身（降级为前端累加）
      PnlPeriodsDto? periods;
      try {
        periods = await widget.api.getPnlPeriods();
      } catch (_) {
        periods = null;
      }
      if (!mounted) return;
      setState(() {
        _curve = curve;
        _periods = periods;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = '收益日历这次没取上来，稍后再试一次';
        _loading = false;
      });
    }
  }

  static String _two(int v) => v.toString().padLeft(2, '0');

  String get _monthPrefix => '${_month.year}-${_two(_month.month)}-';

  bool _inMonth(String date) => date.startsWith(_monthPrefix);

  /// 当月合计：**当前月优先用后端口径**（与账户卡 `pnl-periods` 同源，避免"两个数"）；
  /// 历史月没有后端数据 → 前端按日累加，且只累加真实有值的日子（一天真值都没有 → null = 「—」）。
  double? get _monthTotal {
    final now = DateTime.now();
    final backendMonth = _periods?.month;
    if (_month.year == now.year && _month.month == now.month && backendMonth != null) {
      // P2-11（2026-09-19 前端审查）：后端 window 的 `pnl` 恒非 null（**无数据时是 0**），
      // 而 `partial=true` 表示区间起点不可追溯——直接返回会渲染成「+¥0.00」的**伪 0**
      // （与逐日格子全「—」自相矛盾，D9 同族）。真值缺失时保持 null → 显示「—」。
      if (backendMonth.partial && (backendMonth.pnl == null || backendMonth.pnl == 0)) return null;
      return backendMonth.pnl;
    }
    final curve = _curve;
    if (curve == null) return null;
    double sum = 0;
    var any = false;
    for (final e in curve.dailyPnl.entries) {
      if (!_inMonth(e.key)) continue;
      final v = e.value;
      if (v == null) continue;
      sum += v;
      any = true;
    }
    return any ? sum : null;
  }

  /// 本月合计是否来自后端口径（与账户卡同源）——UI 据此给一句口径说明。
  bool get _monthFromBackend {
    final now = DateTime.now();
    return _month.year == now.year && _month.month == now.month && _periods?.month != null;
  }

  int get _tradingDays {
    final curve = _curve;
    if (curve == null) return 0;
    return curve.dailyPnl.entries.where((e) => _inMonth(e.key) && e.value != null).length;
  }

  void _shiftMonth(int delta) {
    setState(() => _month = DateTime(_month.year, _month.month + delta));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkSurface,
        title: const Text('收益日历', style: TextStyle(fontSize: 15)),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
          : _error != null
              ? _errorView()
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(
                    padding: const EdgeInsets.all(16),
                    physics: const AlwaysScrollableScrollPhysics(),
                    children: [
                      _monthHeader(),
                      const SizedBox(height: 12),
                      _monthSummary(),
                      const SizedBox(height: 16),
                      _calendarGrid(),
                      const SizedBox(height: 10),
                      const Text('点某一天看当天明细。没有数的日子是「—」——缺收盘价时我不猜。',
                          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                    ],
                  ),
                ),
    );
  }

  Widget _errorView() {
    return Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Text(_error!, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        const SizedBox(height: 10),
        TextButton(onPressed: _load, child: const Text('再试一次')),
      ]),
    );
  }

  /// 是否已停在当前月（C 批，2026-09-19）：未来月份不再可翻、「点我回本月」据此显隐。
  bool get _atCurrentMonth {
    final now = DateTime.now();
    return _month.year == now.year && _month.month == now.month;
  }

  Widget _monthHeader() {
    return Row(children: [
      IconButton(
        onPressed: () => _shiftMonth(-1),
        icon: const Icon(Icons.chevron_left, size: 20),
        tooltip: '上个月',
        color: AppColors.darkGrey3,
      ),
      Expanded(
        child: Center(
          child: InkWell(
            // C（2026-09-19）：翻远了回不来——点月份直接回本月（时间线页早有「今日」同款入口）
            onTap: _atCurrentMonth
                ? null
                : () {
                    final n = DateTime.now();
                    setState(() => _month = DateTime(n.year, n.month));
                  },
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              child: Text(
                  _atCurrentMonth
                      ? '${_month.year} 年 ${_month.month} 月'
                      : '${_month.year} 年 ${_month.month} 月 · 点我回本月',
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
            ),
          ),
        ),
      ),
      IconButton(
        // C（2026-09-19）：不再能翻到未来月份（原来一路点到 2027 全是「—」）
        onPressed: _atCurrentMonth ? null : () => _shiftMonth(1),
        icon: const Icon(Icons.chevron_right, size: 20),
        tooltip: '下个月',
        color: AppColors.darkGrey3,
      ),
    ]);
  }

  /// P2-8（2026-09-19 前端审查）：**日历页也要默认打码**——它与首页只隔一次点击，旁人同样能扫到屏。
  /// 规则与交易页一致：**页面级金额打码**，**主动进入的日详情弹窗**显示真实金额。
  bool _revealed = false;

  Widget _monthSummary() {
    final total = _monthTotal;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(
                // B4（2026-09-18）：标出口径——当前月与账户卡同源（后端 `pnl-periods`），历史月按日累加
                child: Text(_monthFromBackend ? '本月收益（与账户卡同口径）' : '本月收益（按日累加）',
                    style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
              ),
              // P2-8：金额默认打码，点这里会话内揭开
              GestureDetector(
                onTap: () => setState(() => _revealed = !_revealed),
                behavior: HitTestBehavior.opaque,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                  child: Icon(_revealed ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                      size: 15, color: AppColors.darkGrey4),
                ),
              ),
            ]),
            const SizedBox(height: 4),
            Text(
              total == null
                  ? '—'
                  : (_revealed ? '${total >= 0 ? '+' : '-'}¥${_thousands(total.abs())}' : '¥ ••••'),
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: _pnlColor(total)),
            ),
          ]),
        ),
        Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
          const Text('有数的交易日', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          const SizedBox(height: 4),
          Text('$_tradingDays 天',
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
        ]),
      ]),
    );
  }

  Widget _calendarGrid() {
    final first = DateTime(_month.year, _month.month, 1);
    final daysInMonth = DateTime(_month.year, _month.month + 1, 0).day;
    final leading = first.weekday - 1; // 周一 = 0

    final today = DateTime.now();
    final cells = <Widget>[];
    for (var i = 0; i < leading; i++) {
      cells.add(const SizedBox.shrink());
    }
    for (var day = 1; day <= daysInMonth; day++) {
      final key = '$_monthPrefix${_two(day)}';
      final pnl = _curve?.dailyPnl[key];
      final isToday = today.year == _month.year && today.month == _month.month && today.day == day;
      cells.add(_dayCell(day, key, pnl, isToday));
    }
    // 补齐末行，避免最后一行格子被拉伸
    while (cells.length % 7 != 0) {
      cells.add(const SizedBox.shrink());
    }

    return Column(children: [
      Row(
        children: ['一', '二', '三', '四', '五', '六', '日']
            .map((w) => Expanded(
                  child: Center(
                    child: Text(w, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                  ),
                ))
            .toList(),
      ),
      const SizedBox(height: 6),
      GridView.count(
        crossAxisCount: 7,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        childAspectRatio: 0.82,
        mainAxisSpacing: 4,
        crossAxisSpacing: 4,
        children: cells,
      ),
    ]);
  }

  Widget _dayCell(int day, String dateKey, double? pnl, bool isToday) {
    final hasValue = pnl != null;
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: () => _showDayDetail(dateKey, pnl),
      child: Container(
        decoration: BoxDecoration(
          color: hasValue ? _pnlColor(pnl).withValues(alpha: 0.10) : AppColors.darkSurface,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: isToday ? AppColors.darkBlue : AppColors.darkBorder,
            width: isToday ? 1.4 : 1,
          ),
        ),
        padding: const EdgeInsets.symmetric(vertical: 5, horizontal: 2),
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Text('$day',
              style: TextStyle(
                fontSize: 11,
                fontWeight: isToday ? FontWeight.w700 : FontWeight.w500,
                color: isToday ? AppColors.darkBlue : AppColors.darkGrey3,
              )),
          const SizedBox(height: 3),
          Text(
            // P2-8：页面级金额打码（进日详情或点 👁 看真值）；
            // 顺带把 `TextOverflow.clip` 改成 `ellipsis`——clip 会把金额**静默切掉半个字**
            // 显示成一个错的数（P2-11 同族，宁可省略不可写错）。
            hasValue ? (_revealed ? _shortMoney(pnl) : '••') : '—',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: _pnlColor(pnl)),
          ),
        ]),
      ),
    );
  }

  void _showDayDetail(String dateKey, double? pnl) {
    double? totalAssets;
    for (final p in _curve?.points ?? const <EquityCurvePointDto>[]) {
      if (p.date == dateKey) {
        totalAssets = p.totalAssets;
        break;
      }
    }

    showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.darkSurface,
      builder: (ctx) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(dateKey, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const SizedBox(height: 14),
          _detailRow('当天盈亏',
              pnl == null ? '—' : '${pnl >= 0 ? '+' : '-'}¥${_thousands(pnl.abs())}', _pnlColor(pnl)),
          const SizedBox(height: 8),
          _detailRow('当天总资产',
              totalAssets == null ? '—' : '¥${_thousands(totalAssets)}', AppColors.darkGrey2),
          if (pnl == null) ...[
            const SizedBox(height: 12),
            const Text('这天我没有收盘价，所以不给数——不猜。',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ],
        ]),
      ),
    );
  }

  Widget _detailRow(String label, String value, Color color) {
    return Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
      Text(label, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
      Text(value, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: color)),
    ]);
  }

  /// A 股口径：红涨绿亏（与全站一致）。
  static Color _pnlColor(double? v) {
    if (v == null) return AppColors.darkGrey5;
    if (v > 0) return AppColors.darkRed;
    if (v < 0) return AppColors.darkGreen;
    return AppColors.darkGrey4;
  }

  /// 格子里的短金额（单位：元/万）——正负号保留，方便一眼看出方向。
  static String _shortMoney(double v) {
    final abs = v.abs();
    final sign = v > 0 ? '+' : (v < 0 ? '-' : '');
    if (abs >= 10000) return '$sign${(abs / 10000).toStringAsFixed(1)}万';
    return '$sign${abs.toStringAsFixed(0)}';
  }

  /// 千分位金额（明细/合计用，保留两位小数）。
  static String _thousands(double v) {
    final s = v.toStringAsFixed(2);
    final parts = s.split('.');
    final intPart = parts[0].replaceAllMapped(
        RegExp(r'(\d)(?=(\d{3})+$)'), (m) => '${m[1]},');
    return '$intPart.${parts[1]}';
  }
}
