import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_web/widgets/case_kline_chart.dart';

/// 案例 K 线图（第四阶段 2026-08-30）：
/// - CaseIndicators 纯函数（MA10/MA60/KDJ/MACD 序列）口径验证
/// - 空 K 线降级文案 + 有 K 线渲染不炸
void main() {
  List<Map<String, dynamic>> klineData(int n, {double base = 10.0}) {
    return List.generate(n, (i) {
      final c = base + i * 0.1;
      return {
        'date': '2026-01-${(i % 28) + 1}'.padLeft(10, '0'),
        'open': c - 0.05,
        'high': c + 0.2,
        'low': c - 0.2,
        'close': c,
        'volume': 1000 + (i % 5) * 100,
      };
    });
  }

  test('CaseIndicators：MA10/MA60/KDJ/MACD 序列长度与输入一致', () {
    final kline = klineData(90);
    final ind = CaseIndicators.compute(kline);
    expect(ind.ma10.length, 90);
    expect(ind.ma60.length, 90);
    expect(ind.kdjK.length, 90);
    expect(ind.kdjD.length, 90);
    expect(ind.kdjJ.length, 90);
    expect(ind.macdDif.length, 90);
    expect(ind.macdDea.length, 90);
    expect(ind.macdHist.length, 90);
  });

  test('CaseIndicators：MA 末值 = 近 N 根收盘均值（口径对齐后端 ma()）', () {
    final kline = klineData(90, base: 10.0);
    final ind = CaseIndicators.compute(kline);
    // MA10 末值 = 最近 10 根 close 均值
    final closes = kline.map((e) => (e['close'] as num).toDouble()).toList();
    var sum = 0.0;
    for (var i = 80; i < 90; i++) {
      sum += closes[i];
    }
    expect(ind.ma10.last, closeTo(sum / 10, 1e-9));
    // MA60 末值 = 最近 60 根 close 均值
    sum = 0.0;
    for (var i = 30; i < 90; i++) {
      sum += closes[i];
    }
    expect(ind.ma60.last, closeTo(sum / 60, 1e-9));
  });

  test('CaseIndicators：KDJ.J 范围 0-100（RSV 口径）', () {
    final ind = CaseIndicators.compute(klineData(90));
    for (final j in ind.kdjJ) {
      expect(j, inInclusiveRange(0, 100));
    }
  });

  testWidgets('CaseKlineChart：空 K 线显示降级文案', (tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: CaseKlineChart(kline: [], buyDate: null)),
    ));
    expect(find.textContaining('K 线暂不可用'), findsOneWidget);
  });

  testWidgets('CaseKlineChart：有 K 线渲染不炸（含买点标记）', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: CaseKlineChart(
            kline: klineData(90),
            buyDate: '2026-01-05',
            height: 320,
          ),
        ),
      ),
    ));
    await tester.pump();
    expect(find.byType(CaseKlineChart), findsOneWidget);
    expect(tester.takeException(), isNull, reason: 'K 线绘制不应抛异常');
  });

  testWidgets('CaseKlineChart：通达信风格——指标名标签 + 副图可切换（2026-08-30）', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: CaseKlineChart(kline: klineData(90), buyDate: null, height: 320),
        ),
      ),
    ));
    await tester.pump();
    // 默认主图 MA2 + 副图 KDJ，标签显示指标名
    expect(find.text('MA2(10,60)'), findsOneWidget, reason: '主图指标名标签');
    expect(find.text('KDJ(9,3,3)'), findsOneWidget, reason: '副图指标名标签');
    // 主图数值标签（MA10/MA60 最新值）
    expect(find.textContaining('MA10:'), findsOneWidget, reason: '主图左上角数值标签');

    // 切换副图 → MACD
    await tester.tap(find.text('KDJ(9,3,3)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('MACD(12,26,9)').last);
    await tester.pumpAndSettle();
    expect(find.text('MACD(12,26,9)'), findsWidgets, reason: '副图已切换为 MACD');
    expect(find.textContaining('DIF:'), findsOneWidget, reason: 'MACD 数值标签');

    // 切换主图 → MA4
    await tester.tap(find.text('MA2(10,60)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('MA4(5,10,20,60)').last);
    await tester.pumpAndSettle();
    expect(find.textContaining('MA5:'), findsOneWidget, reason: 'MA4 含 MA5 数值');
  });
}
