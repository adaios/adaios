import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// 案例 K 线图（第四阶段 2026-08-30：完美买点案例画面还原）。
///
/// 通达信风格（验收反馈迭代）：
/// - 主图下**三个副图固定**：成交量 / MACD / KDJ（用户实际 4 个，先三区）
/// - **单日指标查看**：鼠标悬停某根 K 线 → 主/副图左上角数值标签跟随显示该日值
///   （离开恢复默认——买点日/最新日）+ 光标竖线
/// - 主图 MA2（白黄，黄白线语义）/ MA4（5/10/20/60 标准配色）/ 裸K 可切换
/// - 显示窗口：买点前 60 ~ 买点后 N 天（默认 3，◀▶ 移动查看后验，防涨幅压缩前期形态）
/// - **我的买卖点字母标记**（B 建仓 / T 加仓 / S 卖出，白描边）+ 图例行（批 4 · 2026-10-07）
/// 指标序列前端从 OHLCV 重算（KDJ 9,3,3 / MACD 12,26,9），口径对齐后端
/// `CaseFeatureExtractor`。A 股配色：涨红跌绿。
class CaseKlineChart extends StatefulWidget {
  const CaseKlineChart(
      {super.key,
      required this.kline,
      this.buyDate,
      this.height = 400,
      this.indicators,
      this.marks = const [],
      this.stopLine,
      this.peakLine});

  /// 窗口日 K：每项 {date, open, high, low, close, volume}（旧→新）。
  final List<Map<String, dynamic>> kline;
  /// 买点日期（yyyy-MM-dd），命中则画竖线 + 顶部标记。
  final String? buyDate;
  final double height;
  /// 后端指标全序列（2026-08-30 前后端一致：前端不重算，hover 值 = 特征同源）。
  /// 空 → 前端 CaseIndicators.compute 兜底（测试/降级）。
  final Map<String, dynamic>? indicators;

  /// R-04（2026-10-07）：**我的买卖点** —— 每项 `{date, type: "B"|"T"|"S", price, note}`。
  /// B=建仓（红 ▲）· T=加仓（红 ▲）· S=卖出（绿 ▼）；没给就不画，不硬编。
  final List<Map<String, dynamic>> marks;
  /// R-04：**你定的止损线**（水平虚线，橙）。
  final double? stopLine;
  /// R-04：**峰值浮盈线**（水平虚线，蓝）—— 见顶之后才存在，没有就不画。
  final double? peakLine;

  @override
  State<CaseKlineChart> createState() => _CaseKlineChartState();
}

/// 主图指标。
enum MainIndicator { ma2, ma4, none }

class _CaseKlineChartState extends State<CaseKlineChart> {
  MainIndicator _main = MainIndicator.ma2;
  /// 买点后显示天数（默认 3——后验涨幅不压缩前期形态；◀▶ 移动 0..30 步进 5）。
  int _afterDays = 3;
  /// 悬停选中的 K 线索引（null = 默认：买点日/最新日）。
  int? _hoverIdx;

  static const _mainLabels = {
    MainIndicator.ma2: 'MA2(10,60)',
    MainIndicator.ma4: 'MA4(5,10,20,60)',
    MainIndicator.none: '裸K',
  };

  /// 计算显示窗口 [start, end]（买点前 60 根 ~ 买点后 _afterDays；无买点 → 全窗口）。
  (int, int) _visibleRange(List<Map<String, dynamic>> kline) {
    final n = kline.length;
    final buyDate = widget.buyDate;
    if (buyDate == null || buyDate.isEmpty) return (0, n - 1);
    var buyIdx = -1;
    for (var i = 0; i < n; i++) {
      if ('${kline[i]['date']}' == buyDate) {
        buyIdx = i;
        break;
      }
    }
    if (buyIdx < 0) return (0, n - 1);
    final start = math.max(0, buyIdx - 60);
    final end = math.min(buyIdx + _afterDays, n - 1);
    return (start, end);
  }

  /// 标签取值索引：悬停日 > 买点日 > 最新日。
  int _labelIndex(int buyIdx) {
    if (_hoverIdx != null) return _hoverIdx!;
    if (buyIdx >= 0) return buyIdx;
    return widget.kline.length - 1;
  }

  @override
  Widget build(BuildContext context) {
    if (widget.kline.isEmpty) {
      return const SizedBox(
        height: 120,
        child: Center(
          child: Text('K 线暂不可用（数据源重放失败）',
              style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
        ),
      );
    }
    final indicators = widget.indicators != null
        ? CaseIndicators.fromJson(widget.indicators!)
        : CaseIndicators.compute(widget.kline);
    final (start, end) = _visibleRange(widget.kline);
    final hasBuyDate = widget.buyDate != null && widget.buyDate!.isNotEmpty;
    var buyIdx = -1;
    if (hasBuyDate) {
      for (var i = 0; i < widget.kline.length; i++) {
        if ('${widget.kline[i]['date']}' == widget.buyDate) {
          buyIdx = i;
          break;
        }
      }
    }
    final labelIdx = _labelIndex(buyIdx);
    final labels = _labelsAt(indicators, labelIdx);
    return Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      // 指标切换行 + 窗口移动
      Row(mainAxisSize: MainAxisSize.min, children: [
        _indicatorMenu(
          label: _mainLabels[_main]!,
          items: MainIndicator.values.map((e) => (e, _mainLabels[e]!)).toList(),
          selected: _main,
          onSelect: (v) => setState(() => _main = v),
        ),
        const Text('  量 | MACD | KDJ',
            style: TextStyle(fontSize: 10, color: AppColors.darkGrey4)),
        if (hasBuyDate) ...[
          const SizedBox(width: 12),
          _windowButton(Icons.chevron_left, '查看更早', () {
            if (_afterDays > 0) setState(() => _afterDays = math.max(0, _afterDays - 5));
          }),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text('买点后 $_afterDays 天',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
          ),
          _windowButton(Icons.chevron_right, '查看更晚', () {
            setState(() => _afterDays = math.min(30, _afterDays + 5));
          }),
        ],
      ]),
      const SizedBox(height: 4),
      SizedBox(
        height: widget.height,
        width: double.infinity,
        child: LayoutBuilder(builder: (ctx, constraints) {
          final width = constraints.maxWidth;
          return MouseRegion(
            onHover: (e) {
              final idx = _indexFromDx(e.localPosition.dx, width, start, end);
              if (idx != _hoverIdx) setState(() => _hoverIdx = idx);
            },
            onExit: (_) => setState(() => _hoverIdx = null),
            child: Stack(children: [
              CustomPaint(
                size: Size.infinite,
                painter: _CaseKlinePainter(
                    widget.kline, widget.buyDate, _main, indicators, start, end, _hoverIdx,
                    widget.marks, widget.stopLine, widget.peakLine),
              ),
              // 主图左上角指标数值标签
              Positioned(left: 4, top: 2, child: _labelChip(labels.main)),
              // 三个副图左上角数值标签（按区高偏移）
              Positioned(left: 4, top: widget.height * 0.40 + 2, child: _labelChip(labels.volume)),
              Positioned(left: 4, top: widget.height * 0.60 + 2, child: _labelChip(labels.macd)),
              Positioned(left: 4, top: widget.height * 0.80 + 2, child: _labelChip(labels.kdj)),
            ]),
          );
        }),
      ),
      // 批 4（D4）：图例行——「图下那一行」照原型（动态：图上没有的不列）。
      const SizedBox(height: 6),
      _legendRow(),
    ]);
  }

  /// 悬停 → 窗口内像素 x → 实际 K 线索引。
  int? _indexFromDx(double dx, double width, int start, int end) {
    final count = math.max(1, end - start + 1);
    final idx = start + (dx / width * count).floor();
    if (idx < start || idx > end || idx >= widget.kline.length) return null;
    return idx;
  }

  /// 某一天的指标标签（悬停/买点/最新）。
  ({String main, String volume, String macd, String kdj}) _labelsAt(
      CaseIndicators ind, int idx) {
    if (ind.ma10.isEmpty) {
      return (main: '', volume: '', macd: '', kdj: '');
    }
    final i = idx.clamp(0, ind.ma10.length - 1).toInt();
    String main;
    switch (_main) {
      case MainIndicator.ma2:
        main = 'MA10:${_f(ind.ma10[i])}  MA60:${_f(ind.ma60[i])}';
      case MainIndicator.ma4:
        main = 'MA5:${_f(ind.ma5[i])}  MA10:${_f(ind.ma10[i])}  MA20:${_f(ind.ma20[i])}  MA60:${_f(ind.ma60[i])}';
      case MainIndicator.none:
        main = '';
    }
    final vol = i < widget.kline.length ? (widget.kline[i]['volume'] as num?)?.toDouble() ?? 0.0 : 0.0;
    return (
      main: main,
      volume: '成交量 ${_fmtVol(vol)}手',
      macd: 'MACD(12,26,9)  DIF:${_f(ind.macdDif[i])}  DEA:${_f(ind.macdDea[i])}  MACD:${_f(ind.macdHist[i])}',
      kdj: 'KDJ(9,3,3)  K:${_f(ind.kdjK[i])}  D:${_f(ind.kdjD[i])}  J:${_f(ind.kdjJ[i])}',
    );
  }

  /// 图例行（批 4 · D4）：照原型——主图 / 你定的止损 / 峰值浮盈线 / 买卖点字母 / 副图口径。
  /// 动态：图上没有的东西不出图例（缺数据不编）；价格明文（打码口径同现价）。
  Widget _legendRow() {
    final types = widget.marks.map((m) => '${m['type']}').toSet();
    final buyParts = <String>[
      if (types.contains('B')) 'B 买',
      if (types.contains('T')) 'T 加仓',
    ];
    return Wrap(
      spacing: 14,
      runSpacing: 4,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        _legendItem('■', '日 K + 均线', AppColors.darkGrey3),
        if (widget.stopLine != null)
          _legendItem('- -', '你定的止损 ${widget.stopLine!.toStringAsFixed(2)}',
              const Color(0xFFE8963A)),
        if (widget.peakLine != null)
          _legendItem('- -', '峰值浮盈线 ${widget.peakLine!.toStringAsFixed(2)}（到顶之后才画）',
              const Color(0xFF5299FF)),
        if (buyParts.isNotEmpty)
          Text(buyParts.join(' · '),
              style: const TextStyle(fontSize: 11, color: AppColors.darkRed)),
        if (types.contains('S'))
          const Text('S 卖', style: TextStyle(fontSize: 11, color: AppColors.darkGreen)),
        const Text('副图：成交量 · MACD(12,26,9) · KDJ(9,3,3)',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      ],
    );
  }

  Widget _legendItem(String marker, String text, Color markerColor) {
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Text(marker,
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: markerColor)),
      const SizedBox(width: 4),
      Text(text, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
    ]);
  }

  /// 指标切换下拉。
  Widget _indicatorMenu<T>({
    required String label,
    required List<(T, String)> items,
    required T selected,
    required void Function(T) onSelect,
  }) {
    return PopupMenuButton<T>(
      tooltip: '切换主图指标',
      initialValue: selected,
      onSelected: onSelect,
      itemBuilder: (ctx) => items
          .map((e) => PopupMenuItem<T>(
                value: e.$1,
                child: Text(e.$2,
                    style: TextStyle(
                        fontSize: 11,
                        color: e.$1 == selected ? AppColors.darkGreen : AppColors.darkGrey2)),
              ))
          .toList(),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Text(label,
              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const Icon(Icons.arrow_drop_down, size: 14, color: AppColors.darkGrey4),
        ]),
      ),
    );
  }

  Widget _windowButton(IconData icon, String tooltip, VoidCallback onPressed) {
    return InkWell(
      onTap: onPressed,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
        ),
        child: Icon(icon, size: 14, color: AppColors.darkGrey2),
      ),
    );
  }

  Widget _labelChip(String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
      decoration: BoxDecoration(
        color: AppColors.darkSurface.withValues(alpha: 0.75),
        borderRadius: BorderRadius.circular(3),
      ),
      child: Text(text,
          style: const TextStyle(fontSize: 10, color: AppColors.darkGrey3, height: 1.2)),
    );
  }

  static String _f(double v) => v.toStringAsFixed(2);
  static String _fmtVol(double v) {
    if (v >= 10000) return '${(v / 10000).toStringAsFixed(1)}万';
    return v.toStringAsFixed(0);
  }
}

/// KDJ/MACD/MA 序列计算（纯函数，可单测；口径对齐后端 KdjIndicator/MacdIndicator）。
class CaseIndicators {
  final List<double> ma5;
  final List<double> ma10;
  final List<double> ma20;
  final List<double> ma60;
  final List<double> kdjK;
  final List<double> kdjD;
  final List<double> kdjJ;
  final List<double> macdDif;
  final List<double> macdDea;
  final List<double> macdHist;

  const CaseIndicators(this.ma5, this.ma10, this.ma20, this.ma60, this.kdjK, this.kdjD,
      this.kdjJ, this.macdDif, this.macdDea, this.macdHist);

  /// 从后端序列反序列化（2026-08-30：前端图直接用后端指标——单一事实源）。
  static CaseIndicators fromJson(Map<String, dynamic> json) {
    List<double> arr(String key) => (json[key] as List<dynamic>? ?? const [])
        .map((e) => (e as num).toDouble())
        .toList();
    return CaseIndicators(
      arr('ma5'), arr('ma10'), arr('ma20'), arr('ma60'),
      arr('kdjK'), arr('kdjD'), arr('kdjJ'),
      arr('macdDif'), arr('macdDea'), arr('macdHist'),
    );
  }

  static CaseIndicators compute(List<Map<String, dynamic>> kline) {
    final closes = kline.map((e) => (e['close'] as num).toDouble()).toList();
    final n = closes.length;
    final ma5 = <double>[];
    final ma10 = <double>[];
    final ma20 = <double>[];
    final ma60 = <double>[];
    for (var i = 0; i < n; i++) {
      ma5.add(_ma(closes, i, 5));
      ma10.add(_ma(closes, i, 10));
      ma20.add(_ma(closes, i, 20));
      ma60.add(_ma(closes, i, 60));
    }
    // KDJ 9,3,3（对齐 KdjIndicator：RSV → K/D 平滑 → J）
    final kdjK = <double>[];
    final kdjD = <double>[];
    final kdjJ = <double>[];
    var k = 50.0, d = 50.0;
    for (var i = 0; i < n; i++) {
      if (i >= 8) {
        var high = double.negativeInfinity, low = double.infinity;
        for (var j = i - 8; j <= i; j++) {
          high = math.max(high, (kline[j]['high'] as num).toDouble());
          low = math.min(low, (kline[j]['low'] as num).toDouble());
        }
        final c = closes[i];
        final rsv = high == low ? 50.0 : (c - low) / (high - low) * 100;
        k = 2 / 3 * k + 1 / 3 * rsv;
        d = 2 / 3 * d + 1 / 3 * k;
      }
      kdjK.add(k);
      kdjD.add(d);
      kdjJ.add(3 * k - 2 * d);
    }
    // MACD 12,26,9（对齐 MacdIndicator：EMA → DIF/DEA → 柱）
    final macdDif = <double>[];
    final macdDea = <double>[];
    final macdHist = <double>[];
    var ema12 = closes.isEmpty ? 0.0 : closes[0];
    var ema26 = closes.isEmpty ? 0.0 : closes[0];
    var dea = 0.0;
    for (var i = 0; i < n; i++) {
      final c = closes[i];
      ema12 = i == 0 ? c : ema12 + (c - ema12) * (2 / 13);
      ema26 = i == 0 ? c : ema26 + (c - ema26) * (2 / 27);
      final dif = ema12 - ema26;
      dea = i == 0 ? dif : dea + (dif - dea) * (2 / 10);
      macdDif.add(dif);
      macdDea.add(dea);
      macdHist.add(dif - dea);
    }
    return CaseIndicators(ma5, ma10, ma20, ma60, kdjK, kdjD, kdjJ, macdDif, macdDea, macdHist);
  }

  static double _ma(List<double> closes, int idx, int n) {
    final from = math.max(0, idx - n + 1);
    var sum = 0.0;
    for (var i = from; i <= idx; i++) {
      sum += closes[i];
    }
    return sum / (idx - from + 1);
  }
}

class _CaseKlinePainter extends CustomPainter {
  _CaseKlinePainter(this.kline, this.buyDate, this.mainIndicator, this.indicators,
      this.windowStart, this.windowEnd, this.hoverIdx,
      this.marks, this.stopLine, this.peakLine);

  final List<Map<String, dynamic>> kline;
  final String? buyDate;
  final MainIndicator mainIndicator;
  final CaseIndicators indicators;
  final int windowStart;
  final int windowEnd;
  /// 悬停选中的 K 线索引（null = 无悬停）。
  final int? hoverIdx;
  /// R-04：我的买卖点 / 你定的止损线 / 峰值浮盈线（都可空，空则不画）。
  final List<Map<String, dynamic>> marks;
  final double? stopLine;
  final double? peakLine;

  // 四区布局：主图 40% + 量 20% + MACD 20% + KDJ 20%
  static const double _mainRatio = 0.40;
  static const double _volRatio = 0.20;
  static const double _macdRatio = 0.20;
  static const double _topPad = 14;

  @override
  void paint(Canvas canvas, Size size) {
    final n = kline.length;
    final width = size.width;
    final mainH = size.height * _mainRatio;
    final volH = size.height * _volRatio;
    final macdH = size.height * _macdRatio;
    final mainRect = Rect.fromLTWH(0, _topPad, width, mainH - _topPad);
    final volRect = Rect.fromLTWH(0, mainH, width, volH);
    final macdRect = Rect.fromLTWH(0, mainH + volH, width, macdH);
    final kdjRect = Rect.fromLTWH(0, mainH + volH + macdH, width, size.height - mainH - volH - macdH);

    // 买点索引
    var buyIdx = -1;
    if (buyDate != null) {
      for (var i = 0; i < n; i++) {
        if ('${kline[i]['date']}' == buyDate) {
          buyIdx = i;
          break;
        }
      }
    }

    final count = math.max(1, windowEnd - windowStart + 1);
    double x(int i) => ((i - windowStart) + 0.5) / count * width;

    // 价格范围：仅窗口内蜡烛 + 窗口内均线
    var minPrice = double.infinity, maxPrice = double.negativeInfinity;
    for (var i = windowStart; i <= windowEnd && i < n; i++) {
      minPrice = math.min(minPrice, (kline[i]['low'] as num).toDouble());
      maxPrice = math.max(maxPrice, (kline[i]['high'] as num).toDouble());
    }
    if (mainIndicator == MainIndicator.ma2 || mainIndicator == MainIndicator.ma4) {
      for (var i = windowStart; i <= windowEnd && i < indicators.ma60.length; i++) {
        final v = indicators.ma60[i];
        if (v > 0) {
          minPrice = math.min(minPrice, v);
          maxPrice = math.max(maxPrice, v);
        }
      }
    }
    if (minPrice.isInfinite || maxPrice.isInfinite || maxPrice <= minPrice) return;
    final pad = (maxPrice - minPrice) * 0.05;
    minPrice -= pad;
    maxPrice += pad;

    double y(double price) =>
        mainRect.bottom - (price - minPrice) / (maxPrice - minPrice) * mainRect.height;

    // ── 主图：蜡烛（窗口内）──
    final candleW = math.max(1.5, (width / count) * 0.6);
    for (var i = windowStart; i <= windowEnd && i < n; i++) {
      final e = kline[i];
      final o = (e['open'] as num).toDouble();
      final c = (e['close'] as num).toDouble();
      final h = (e['high'] as num).toDouble();
      final l = (e['low'] as num).toDouble();
      final up = c >= o;
      final color = up ? AppColors.darkRed : AppColors.darkGreen;
      final cx = x(i);
      final paint = Paint()
        ..color = color
        ..strokeWidth = 1;
      canvas.drawLine(Offset(cx, y(h)), Offset(cx, y(l)), paint);
      final top = math.min(y(o), y(c));
      final bottom = math.max(y(o), y(c));
      canvas.drawRect(
        Rect.fromLTRB(cx - candleW / 2, top, cx + candleW / 2, math.max(top + 0.5, bottom)),
        paint,
      );
    }

    // ── 主图：均线（窗口段）──
    switch (mainIndicator) {
      case MainIndicator.ma2:
        _drawLine(canvas, indicators.ma10, x, y, AppColors.darkGrey2, 1);
        _drawLine(canvas, indicators.ma60, x, y, const Color(0xFFE6C34A), 1.2);
      case MainIndicator.ma4:
        _drawLine(canvas, indicators.ma5, x, y, AppColors.darkGrey2, 1);
        _drawLine(canvas, indicators.ma10, x, y, const Color(0xFFE6C34A), 1);
        _drawLine(canvas, indicators.ma20, x, y, const Color(0xFF9B7FD4), 1);
        _drawLine(canvas, indicators.ma60, x, y, AppColors.darkGreen, 1.2);
      case MainIndicator.none:
        break;
    }

    // ── 主图：买点日标记 ──
    if (buyIdx >= windowStart && buyIdx <= windowEnd) {
      final cx = x(buyIdx);
      final marker = Paint()
        ..color = AppColors.darkGreen
        ..strokeWidth = 1;
      canvas.drawLine(Offset(cx, mainRect.top), Offset(cx, mainRect.bottom), marker);
      final text = TextPainter(
        text: const TextSpan(
          text: '买点',
          style: TextStyle(fontSize: 9, color: AppColors.darkGreen, fontWeight: FontWeight.bold),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      text.paint(canvas, Offset(cx - text.width / 2, mainRect.top - 11));
    }

    // ── 主图 R-04（2026-10-07）：你定的止损线 / 峰值浮盈线 ──
    void drawLevel(double price, Color color, String label, {bool fromRight = false}) {
      final yp = y(price);
      if (yp < mainRect.top || yp > mainRect.bottom) return; // 不在窗口价域里就不画，不硬压到边上
      final dash = Paint()
        ..color = color
        ..strokeWidth = 1;
      for (var x0 = mainRect.left; x0 < mainRect.right; x0 += 9) {
        canvas.drawLine(Offset(x0, yp), Offset(math.min(x0 + 5, mainRect.right), yp), dash);
      }
      final t = TextPainter(
        text: TextSpan(text: label, style: TextStyle(fontSize: 9, color: color)),
        textDirection: TextDirection.ltr,
      )..layout();
      t.paint(canvas, Offset(fromRight ? math.max(0, mainRect.right - t.width - 2) : 2, yp - 11));
    }

    if (stopLine != null) {
      drawLevel(stopLine!, const Color(0xFFE8963A), '你定的止损 ${stopLine!.toStringAsFixed(2)}');
    }
    if (peakLine != null) {
      drawLevel(peakLine!, const Color(0xFF5299FF), '峰值浮盈线 ${peakLine!.toStringAsFixed(2)}',
          fromRight: true);
    }

    // ── 主图 R-04：我的买卖点 —— 字母标记 B 建仓 / T 加仓（红▲）/ S 卖出（绿▼）──
    // 批 4（D4）：三角加大（10×6 · 原 8×5），字母 11px 白描边（原 8px 汉字看不清）。
    for (final m in marks) {
      final ds = '${m['date']}';
      var idx = -1;
      for (var i = 0; i < n; i++) {
        if ('${kline[i]['date']}' == ds) {
          idx = i;
          break;
        }
      }
      if (idx < windowStart || idx > windowEnd) continue;
      final type = '${m['type']}';
      final isBuy = type != 'S';
      final color = isBuy ? AppColors.darkRed : AppColors.darkGreen;
      final cx = x(idx);
      final cy = isBuy
          ? y((kline[idx]['low'] as num).toDouble()) + 12
          : y((kline[idx]['high'] as num).toDouble()) - 12;
      final tri = Path();
      if (isBuy) {
        tri
          ..moveTo(cx - 5, cy + 6)
          ..lineTo(cx + 5, cy + 6)
          ..lineTo(cx, cy)
          ..close();
      } else {
        tri
          ..moveTo(cx - 5, cy - 6)
          ..lineTo(cx + 5, cy - 6)
          ..lineTo(cx, cy)
          ..close();
      }
      canvas.drawPath(tri, Paint()..color = color);
      final letter = type == 'S' ? 'S' : (type == 'T' ? 'T' : 'B');
      _paintOutlinedLetter(canvas, Offset(cx + 7, cy - 6), letter, color);
    }

    // ── 副图①：成交量（窗口段）──
    var maxVol = 0.0;
    for (var i = windowStart; i <= windowEnd && i < n; i++) {
      maxVol = math.max(maxVol, (kline[i]['volume'] as num).toDouble());
    }
    if (maxVol > 0) {
      for (var i = windowStart; i <= windowEnd && i < n; i++) {
        final e = kline[i];
        final o = (e['open'] as num).toDouble();
        final c = (e['close'] as num).toDouble();
        final v = (e['volume'] as num).toDouble();
        final h = volRect.height * v / maxVol;
        canvas.drawRect(
          Rect.fromLTRB(x(i) - candleW / 2, volRect.bottom - h, x(i) + candleW / 2, volRect.bottom),
          Paint()..color = (c >= o ? AppColors.darkRed : AppColors.darkGreen).withValues(alpha: 0.7),
        );
      }
    }

    // ── 副图②：MACD（柱 + DIF/DEA 线，窗口段）──
    var maxAbs = 0.0;
    for (var i = windowStart; i <= windowEnd && i < indicators.macdHist.length; i++) {
      maxAbs = math.max(maxAbs, indicators.macdHist[i].abs());
    }
    if (maxAbs <= 0) maxAbs = 1;
    for (var i = windowStart; i <= windowEnd && i < n; i++) {
      final v = indicators.macdHist[i];
      // 2026-08-30 修复：柱高按「区域半高」归一化（原 0.9×整高，单边伸出中线到区域顶
      // → 高柱画出 macdRect 顶部覆盖成交量区，航天发展实测复现）
      final h = macdRect.height / 2 * v.abs() / maxAbs * 0.9;
      final midY = macdRect.center.dy;
      canvas.drawRect(
        Rect.fromLTRB(x(i) - candleW / 2, v >= 0 ? midY - h : midY, x(i) + candleW / 2,
            v >= 0 ? midY : midY + h),
        Paint()..color = (v >= 0 ? AppColors.darkRed : AppColors.darkGreen).withValues(alpha: 0.8),
      );
    }
    _drawLine2(canvas, indicators.macdDif, x, macdRect, AppColors.darkGreen, 1);
    _drawLine2(canvas, indicators.macdDea, x, macdRect, const Color(0xFFE6C34A), 1);

    // ── 副图③：KDJ（K/D/J 三线，窗口段）──
    _drawScaled(canvas, kdjRect, [
      (indicators.kdjK, AppColors.darkGreen),
      (indicators.kdjD, const Color(0xFFE6C34A)),
      (indicators.kdjJ, AppColors.darkPurple),
    ], x, 0, 100);

    // ── 悬停光标竖线（全高淡线）──
    if (hoverIdx != null && hoverIdx! >= windowStart && hoverIdx! <= windowEnd) {
      final cx = x(hoverIdx!);
      final cursor = Paint()
        ..color = AppColors.darkGrey4.withValues(alpha: 0.6)
        ..strokeWidth = 0.8;
      canvas.drawLine(Offset(cx, mainRect.top), Offset(cx, size.height), cursor);
    }

    // 分区隔线
    final sep = Paint()
      ..color = AppColors.darkBorder.withValues(alpha: 0.4)
      ..strokeWidth = 0.5;
    canvas.drawLine(Offset(0, mainH), Offset(width, mainH), sep);
    canvas.drawLine(Offset(0, mainH + volH), Offset(width, mainH + volH), sep);
    canvas.drawLine(Offset(0, mainH + volH + macdH), Offset(width, mainH + volH + macdH), sep);
  }

  /// 买卖点字母标记（B/T 红 · S 绿）：先白描边再着色填充——深色底上看得清（批 4 · D4）。
  void _paintOutlinedLetter(Canvas canvas, Offset at, String letter, Color color) {
    final stroke = TextPainter(
      text: TextSpan(
        text: letter,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          foreground: Paint()
            ..style = PaintingStyle.stroke
            ..strokeWidth = 2.4
            ..color = const Color(0xFFF2EFEA),
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    stroke.paint(canvas, at);
    final fill = TextPainter(
      text: TextSpan(
        text: letter,
        style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: color),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    fill.paint(canvas, at);
  }

  void _drawLine(Canvas canvas, List<double> values, double Function(int) x,
      double Function(double) y, Color color, double strokeWidth) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = strokeWidth
      ..style = PaintingStyle.stroke;
    final path = Path();
    var started = false;
    for (var i = windowStart; i <= windowEnd && i < values.length; i++) {
      if (values[i] <= 0) continue;
      final p = Offset(x(i), y(values[i]));
      if (!started) {
        path.moveTo(p.dx, p.dy);
        started = true;
      } else {
        path.lineTo(p.dx, p.dy);
      }
    }
    canvas.drawPath(path, paint);
  }

  void _drawScaled(Canvas canvas, Rect rect, List<(List<double>, Color)> series,
      double Function(int) x, double min, double max) {
    for (final (values, color) in series) {
      final paint = Paint()
        ..color = color
        ..strokeWidth = 1
        ..style = PaintingStyle.stroke;
      final path = Path();
      var started = false;
      for (var i = windowStart; i <= windowEnd && i < values.length; i++) {
        final v = values[i].clamp(min, max);
        final p = Offset(x(i), rect.bottom - (v - min) / (max - min) * rect.height);
        if (!started) {
          path.moveTo(p.dx, p.dy);
          started = true;
        } else {
          path.lineTo(p.dx, p.dy);
        }
      }
      canvas.drawPath(path, paint);
    }
  }

  void _drawLine2(Canvas canvas, List<double> values, double Function(int) x, Rect rect,
      Color color, double strokeWidth) {
    // 2026-08-30：MACD 线按区域高度归一化（不再固定 /8 像素——大数值画出区域，
    // 视觉上「覆盖」上方成交量区）；以区域内最大绝对值映射，0 轴 = 中线。
    var maxAbs = 1.0;
    for (var i = windowStart; i <= windowEnd && i < values.length; i++) {
      maxAbs = math.max(maxAbs, values[i].abs());
    }
    final paint = Paint()
      ..color = color
      ..strokeWidth = strokeWidth
      ..style = PaintingStyle.stroke;
    final path = Path();
    var started = false;
    for (var i = windowStart; i <= windowEnd && i < values.length; i++) {
      final midY = rect.center.dy;
      final p = Offset(x(i), midY - values[i] / maxAbs * rect.height * 0.45);
      if (!started) {
        path.moveTo(p.dx, p.dy);
        started = true;
      } else {
        path.lineTo(p.dx, p.dy);
      }
    }
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant _CaseKlinePainter oldDelegate) =>
      oldDelegate.kline != kline ||
      oldDelegate.buyDate != buyDate ||
      oldDelegate.mainIndicator != mainIndicator ||
      oldDelegate.windowStart != windowStart ||
      oldDelegate.windowEnd != windowEnd ||
      oldDelegate.hoverIdx != hoverIdx ||
      oldDelegate.marks != marks ||
      oldDelegate.stopLine != stopLine ||
      oldDelegate.peakLine != peakLine;
}

/// K 线弹窗统一壳（批 4 · D4+D5「一张图四处共用」）：持仓 / 自选 / 清仓 / 案例
/// 打开的是同一个壳——标题（名称（代码）· 说明）→ 指标切换 + 图 + 图例（CaseKlineChart）
/// → 底部块（调用方给：案例=明细/绩效/AI 理解，通用=一句话）。
///
/// 宽度统一（原案例 620 / 通用 760 两样）：随视口放宽到最多 1280；图高统一
/// （原 400/430 四区被压 → 460）。行情取不到 → 只显示 [emptyNote] 人话，不画空图。
class KlinePanel extends StatelessWidget {
  const KlinePanel({
    super.key,
    required this.name,
    required this.symbol,
    this.note,
    this.kline = const [],
    this.buyDate,
    this.indicators,
    this.marks = const [],
    this.stopLine,
    this.peakLine,
    this.chartHeight = 460,
    this.emptyNote,
    this.bottom = const [],
  });

  /// 标的名称 / 代码——标题统一渲染为「名称（代码）· 说明」。
  final String name;
  final String symbol;

  /// 标题后缀：通用不传（默认「K 线」），案例传「B1 · 买点日期」。
  final String? note;

  /// 窗口日 K（旧→新，空则显示 [emptyNote] 而不画图）。
  final List<Map<String, dynamic>> kline;
  final String? buyDate;
  final Map<String, dynamic>? indicators;

  /// R-04：我的买卖点 / 你定的止损线 / 峰值浮盈线（都可空，空则不画不列图例）。
  final List<Map<String, dynamic>> marks;
  final double? stopLine;
  final double? peakLine;

  /// 图高（四处统一 460）。
  final double chartHeight;

  /// 行情取不到时的人话（不画假图）。
  final String? emptyNote;

  /// 图下块（每项前壳统一加 10px 间距；内容与项间距离由调用点自备）。
  final List<Widget> bottom;

  @override
  Widget build(BuildContext context) {
    final suffix = (note ?? '').isEmpty ? 'K 线' : note!;
    // 弹窗宽度统一（D4「放宽」）：随视口放宽，上限 1280；窄窗自适应不溢出。
    final screenW = MediaQuery.sizeOf(context).width;
    final width = (screenW - 96).clamp(320.0, 1280.0);
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: Text(
        '$name（$symbol）· $suffix',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1),
      ),
      content: SizedBox(
        width: width,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (kline.isEmpty)
                Text(
                  emptyNote ?? '暂时取不到这只票的行情',
                  style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4),
                )
              else
                CaseKlineChart(
                  kline: kline,
                  buyDate: buyDate,
                  indicators: indicators,
                  marks: marks,
                  stopLine: stopLine,
                  peakLine: peakLine,
                  height: chartHeight,
                ),
              if (bottom.isNotEmpty) ...[
                const SizedBox(height: 10),
                ...bottom,
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关掉'),
        ),
      ],
    );
  }
}
