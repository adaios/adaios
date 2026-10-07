import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/widgets/case_kline_chart.dart';

/// R-04 通用 K 线（2026-10-07 晚）：一张图四处共用 —— 持仓 / 自选 / 清仓 / 案例。
///
/// 锁三条：
/// ① 后端给什么就画什么（蜡烛 / 我的买卖点 / 你定的止损线 / 峰值浮盈线）；
/// ② **缺数据不编**（缺 close 的行情行直接跳过，不补 0）；
/// ③ 行情取不到时**只说取不到**（hasData=false + note），前端不画空图。
void main() {
  Map<String, dynamic> payload() => {
        'symbol': '603993',
        'window': 90,
        'candles': [
          {'date': '2026-08-17', 'open': 15.0, 'high': 15.4, 'low': 14.9, 'close': 15.3, 'volume': 1200},
          {'date': '2026-08-18', 'open': 15.3, 'high': 15.9, 'low': 15.2, 'close': 15.8, 'volume': 1500},
          // 缺 close 的一行（脏数据）——不能被渲染成 0
          {'date': '2026-08-19', 'open': 15.8, 'high': 16.0, 'low': 15.7, 'volume': 900},
        ],
        'marks': [
          {'date': '2026-08-17', 'type': 'B', 'price': 15.3, 'quantity': 1000, 'note': '买入'},
          {'date': '2026-08-18', 'type': 'T', 'price': 15.8, 'quantity': 500, 'note': '加仓'},
        ],
        'stopLine': {'price': 14.8, 'from': '2026-08-17', 'note': '你定的止损'},
        'peakLine': {'price': 15.2, 'peakPrice': 16.0, 'peakDate': '2026-08-18'},
        'context': {'held': false, 'closedAt': '2026-08-19', 'holdPnlPct': 9.6},
      };

  test('R-04：后端给的蜡烛 / 买卖点 / 两条线都进得来', () {
    final d = TradingKlineDto.fromJson(payload());
    expect(d.symbol, '603993');
    expect(d.candles.length, 2, reason: '缺 close 的那一行要跳过，不能补 0');
    expect(d.candles.first['date'], '2026-08-17');
    expect(d.marks.length, 2);
    expect(d.marks[1]['type'], 'T');
    expect(d.stopLine, 14.8);
    expect(d.peakLine, 15.2);
    expect(d.held, false);
    expect(d.closedAt, '2026-08-19');
    expect(d.holdPnlPct, 9.6);
  });

  test('R-04：行情取不到时不画假图 —— hasData=false 且带一句人话', () {
    final d = TradingKlineDto.fromJson({
      'symbol': '603993',
      'window': 90,
      'candles': <dynamic>[],
      'marks': <dynamic>[],
      'note': '暂时取不到这只票的行情',
    });
    expect(d.hasData, false);
    expect(d.note, contains('取不到'));
    expect(d.marks, isEmpty);
    expect(d.stopLine, isNull);
    expect(d.peakLine, isNull);
  });

  testWidgets('R-04：带买卖点和两条线的图能渲染出来（不炸）', (tester) async {
    final d = TradingKlineDto.fromJson(payload());
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SizedBox(
          // 批 4：图例行后组件整体变高（切换行 + 图 430 + 图例）——给够高度
          height: 520,
          child: CaseKlineChart(
            kline: d.candles,
            marks: d.marks,
            stopLine: d.stopLine,
            peakLine: d.peakLine,
          ),
        ),
      ),
    ));
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.byType(CaseKlineChart), findsOneWidget);
  });

  testWidgets('R-04：统一壳 KlinePanel —— 标题「名称（代码）· 说明」+ 图例（批 4 · D5）', (
    tester,
  ) async {
    final d = TradingKlineDto.fromJson(payload());
    await tester.pumpWidget(MaterialApp(
      home: KlinePanel(
        name: '云南锗业',
        symbol: '002428',
        kline: d.candles,
        marks: d.marks,
        stopLine: d.stopLine,
        peakLine: d.peakLine,
      ),
    ));
    await tester.pump();
    expect(
      find.text('云南锗业（002428）· K 线'),
      findsOneWidget,
      reason: '四处共用同一标题口径（名称（代码）· 说明）',
    );
    expect(find.textContaining('你定的止损 14.80'), findsOneWidget, reason: '图例行有你定的止损');
    expect(find.textContaining('B 买'), findsOneWidget, reason: '买卖点图例字母口径');
    expect(tester.takeException(), isNull);
  });

  testWidgets('R-04：KlinePanel 行情取不到——只显示人话，不画空图（批 4）', (tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: KlinePanel(
        name: '云南锗业',
        symbol: '002428',
        emptyNote: '暂时取不到这只票的行情',
      ),
    ));
    await tester.pump();
    expect(find.textContaining('取不到'), findsOneWidget);
    expect(find.byType(CaseKlineChart), findsNothing, reason: '不画空图');
  });
}
