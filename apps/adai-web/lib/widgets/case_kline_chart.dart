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
/// 指标序列前端从 OHLCV 重算（KDJ 9,3,3 / MACD 12,26,9），口径对齐后端
/// `CaseFeatureExtractor`。A 股配色：涨红跌绿。
class CaseKlineChart extends StatefulWidget {
  const CaseKlineChart({super.key, required this.kline, this.buyDate, this.height = 400});

  /// 窗口日 K：每项 {date, open, high, low, close, volume}（旧→新）。
  final List<Map<String, dynamic>> kline;
  /// 买点日期（yyyy-MM-dd），命中则画竖线 + 顶部标记。
  final String? buyDate;
  final double height;

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
    final indicators = CaseIndicators.compute(widget.kline);
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
                    widget.kline, widget.buyDate, _main, indicators, start, end, _hoverIdx),
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
      this.windowStart, this.windowEnd, this.hoverIdx);

  final List<Map<String, dynamic>> kline;
  final String? buyDate;
  final MainIndicator mainIndicator;
  final CaseIndicators indicators;
  final int windowStart;
  final int windowEnd;
  /// 悬停选中的 K 线索引（null = 无悬停）。
  final int? hoverIdx;

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
      final h = macdRect.height * v.abs() / maxAbs * 0.9;
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
    final paint = Paint()
      ..color = color
      ..strokeWidth = strokeWidth
      ..style = PaintingStyle.stroke;
    final path = Path();
    var started = false;
    for (var i = windowStart; i <= windowEnd && i < values.length; i++) {
      final midY = rect.center.dy;
      final p = Offset(x(i), midY - values[i] / 8);
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
      oldDelegate.hoverIdx != hoverIdx;
}
