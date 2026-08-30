import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// 案例 K 线图（第四阶段 2026-08-30：完美买点案例画面还原）。
///
/// 三区：主图（蜡烛 + MA10 白线 + MA60 黄线 + 买点日标记）→ 成交量 → KDJ + MACD。
/// 指标序列前端从 OHLCV 重算（KDJ 9,3,3 / MACD 12,26,9），口径对齐后端
/// `CaseFeatureExtractor`（黄线 ≈ MA60 为「黄白线」语义近似，白线 = MA10）。
/// A 股配色：涨红跌绿（darkRed/darkGreen，对齐 AppColors 硬规则）。
class CaseKlineChart extends StatelessWidget {
  const CaseKlineChart({super.key, required this.kline, this.buyDate, this.height = 320});

  /// 窗口日 K：每项 {date, open, high, low, close, volume}（旧→新）。
  final List<Map<String, dynamic>> kline;
  /// 买点日期（yyyy-MM-dd），命中则画竖线 + 顶部标记。
  final String? buyDate;
  final double height;

  @override
  Widget build(BuildContext context) {
    if (kline.isEmpty) {
      return const SizedBox(
        height: 120,
        child: Center(
          child: Text('K 线暂不可用（数据源重放失败）',
              style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
        ),
      );
    }
    return SizedBox(
      height: height,
      width: double.infinity,
      child: CustomPaint(painter: _CaseKlinePainter(kline, buyDate)),
    );
  }
}

/// KDJ/MACD 序列计算（纯函数，可单测；口径对齐后端 KdjIndicator/MacdIndicator）。
class CaseIndicators {
  final List<double> ma10;
  final List<double> ma60;
  final List<double> kdjK;
  final List<double> kdjD;
  final List<double> kdjJ;
  final List<double> macdDif;
  final List<double> macdDea;
  final List<double> macdHist;

  const CaseIndicators(this.ma10, this.ma60, this.kdjK, this.kdjD, this.kdjJ,
      this.macdDif, this.macdDea, this.macdHist);

  static CaseIndicators compute(List<Map<String, dynamic>> kline) {
    final closes = kline.map((e) => (e['close'] as num).toDouble()).toList();
    final n = closes.length;
    final ma10 = <double>[];
    final ma60 = <double>[];
    for (var i = 0; i < n; i++) {
      ma10.add(_ma(closes, i, 10));
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
    return CaseIndicators(ma10, ma60, kdjK, kdjD, kdjJ, macdDif, macdDea, macdHist);
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
  _CaseKlinePainter(this.kline, this.buyDate);

  final List<Map<String, dynamic>> kline;
  final String? buyDate;

  static const double _mainH = 170;
  static const double _volH = 48;
  static const double _kdjH = 52;
  static const double _macdH = 50;
  static const double _topPad = 12;

  @override
  void paint(Canvas canvas, Size size) {
    final indicators = CaseIndicators.compute(kline);
    final n = kline.length;
    final width = size.width;
    // 网格横分区
    final mainRect = Rect.fromLTWH(0, _topPad, width, _mainH);
    final volTop = _topPad + _mainH;
    final volRect = Rect.fromLTWH(0, volTop, width, _volH);
    final kdjTop = volTop + _volH;
    final kdjRect = Rect.fromLTWH(0, kdjTop, width, _kdjH);
    final macdRect = Rect.fromLTWH(0, kdjTop + _kdjH, width, _macdH);

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

    // 价格范围（含均线）
    var minPrice = double.infinity, maxPrice = double.negativeInfinity;
    for (final e in kline) {
      minPrice = math.min(minPrice, (e['low'] as num).toDouble());
      maxPrice = math.max(maxPrice, (e['high'] as num).toDouble());
    }
    for (final v in indicators.ma60) {
      if (v > 0) {
        minPrice = math.min(minPrice, v);
        maxPrice = math.max(maxPrice, v);
      }
    }
    if (minPrice.isInfinite || maxPrice.isInfinite || maxPrice <= minPrice) return;
    final pad = (maxPrice - minPrice) * 0.05;
    minPrice -= pad;
    maxPrice += pad;

    double x(int i) => n <= 1 ? width / 2 : (width / n) * (i + 0.5);
    double y(double price) =>
        mainRect.bottom - (price - minPrice) / (maxPrice - minPrice) * mainRect.height;

    // ── 主图：蜡烛 ──
    final candleW = math.max(1.5, (width / n) * 0.6);
    for (var i = 0; i < n; i++) {
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

    // ── 主图：MA10（白线）+ MA60（黄线 = 黄白线语义近似）──
    _drawLine(canvas, indicators.ma10, x, y, AppColors.darkGrey2, 1);
    _drawLine(canvas, indicators.ma60, x, y, const Color(0xFFE6C34A), 1.2);

    // ── 主图：买点日标记（竖线 + 顶部 ▲ + 日期）──
    if (buyIdx >= 0) {
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
      text.paint(canvas, Offset(cx - text.width / 2, mainRect.top - 10));
    }

    // ── 副图：成交量（红涨绿亏）──
    var maxVol = 0.0;
    for (final e in kline) {
      maxVol = math.max(maxVol, (e['volume'] as num).toDouble());
    }
    if (maxVol > 0) {
      for (var i = 0; i < n; i++) {
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

    // ── 副图：KDJ（K/D/J）──
    _drawScaled(canvas, kdjRect, [
      (indicators.kdjK, AppColors.darkGreen),
      (indicators.kdjD, const Color(0xFFE6C34A)),
      (indicators.kdjJ, AppColors.darkPurple),
    ], x, 0, 100);

    // ── 副图：MACD（柱红绿 + DIF/DEA 线）──
    var maxAbs = 0.0;
    for (final v in indicators.macdHist) {
      maxAbs = math.max(maxAbs, v.abs());
    }
    if (maxAbs <= 0) maxAbs = 1;
    for (var i = 0; i < n; i++) {
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

    // 分区分隔线
    final sep = Paint()
      ..color = AppColors.darkBorder.withValues(alpha: 0.4)
      ..strokeWidth = 0.5;
    canvas.drawLine(Offset(0, volTop), Offset(width, volTop), sep);
    canvas.drawLine(Offset(0, kdjTop), Offset(width, kdjTop), sep);
    canvas.drawLine(Offset(0, kdjTop + _kdjH), Offset(width, kdjTop + _kdjH), sep);
  }

  void _drawLine(Canvas canvas, List<double> values, double Function(int) x,
      double Function(double) y, Color color, double strokeWidth) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = strokeWidth
      ..style = PaintingStyle.stroke;
    final path = Path();
    var started = false;
    for (var i = 0; i < values.length; i++) {
      if (values[i] <= 0) continue; // MA 前 60 根无值
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
      for (var i = 0; i < values.length; i++) {
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
    for (var i = 0; i < values.length; i++) {
      final midY = rect.center.dy;
      final p = Offset(x(i), midY - values[i] / 8); // 柱归一化 8 单位/像素
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
      oldDelegate.kline != kline || oldDelegate.buyDate != buyDate;
}
