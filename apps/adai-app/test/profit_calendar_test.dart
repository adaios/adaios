import 'dart:convert';

import 'package:adai_app/pages/profit_calendar_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

http.Response _json(Object body) => http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

void main() {
  group('EquityCurveDto（P2-交易52）', () {
    test('dailyPnl 的缺值必须保持 null —— 前端显示「—」，不许猜 0', () {
      final dto = EquityCurveDto.fromJson({
        'points': [
          {
            'date': '2026-09-15',
            'totalAssets': 100000.0,
            'cash': 1000.0,
            'marketValue': 99000.0,
            'invested': 100000.0,
            'netValue': 1.0,
            'drawdown': 0.0,
          },
        ],
        'dailyPnl': {'2026-09-15': 1234.5, '2026-09-16': null},
        'startDate': '2026-09-01',
        'endDate': '2026-09-16',
      });

      expect(dto.points.single.totalAssets, 100000.0);
      expect(dto.dailyPnl['2026-09-15'], 1234.5);
      expect(dto.dailyPnl.containsKey('2026-09-16'), isTrue);
      expect(dto.dailyPnl['2026-09-16'], isNull,
          reason: '缺收盘价的日子是 null（显示「—」）；变成 0 就是编造一个「当天不赚不亏」');
    });

    test('dailyPnl 缺失（旧后端）时退化为空 map，不炸', () {
      final dto = EquityCurveDto.fromJson({'points': const [], 'startDate': '', 'endDate': ''});
      expect(dto.dailyPnl, isEmpty);
      expect(dto.points, isEmpty);
    });
  });

  group('ProfitCalendarPage（P2-交易52）', () {
    testWidgets('渲染当月合计与格子；缺值那天显示「—」', (tester) async {
      final now = DateTime.now();
      final prefix = '${now.year}-${now.month.toString().padLeft(2, '0')}';
      final day1 = '$prefix-01';
      final day2 = '$prefix-02';

      final client = MockClient((req) async {
        if (req.url.path.endsWith('/trading/equity-curve')) {
          return _json({
            'points': [
              {
                'date': day1,
                'totalAssets': 100000.0,
                'cash': 0.0,
                'marketValue': 100000.0,
                'invested': 90000.0,
                'netValue': 1.1,
                'drawdown': 0.0,
              },
            ],
            'dailyPnl': {day1: 1000.0, day2: null},
            'startDate': day1,
            'endDate': day2,
          });
        }
        return http.Response('{}', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);

      await tester.pumpWidget(MaterialApp(home: ProfitCalendarPage(api: api)));
      await tester.pumpAndSettle();

      expect(find.text('收益日历'), findsOneWidget);
      expect(find.text('${now.year} 年 ${now.month} 月'), findsOneWidget);
      // 当月合计：只有 day1 有真值 → +¥1,000.00
      expect(find.textContaining('1,000.00'), findsWidgets);
      // 缺值那天在格子里是「—」（不是 ¥0.00）
      expect(find.text('—'), findsWidgets);
      expect(find.textContaining('¥0.00'), findsNothing);
    });

    testWidgets('取数失败：给人话 + 可重试，不伪装空日历', (tester) async {
      final client = MockClient((req) async => http.Response('boom', 500));
      final api = ApiService(baseUrl: 'http://test', client: client);

      await tester.pumpWidget(MaterialApp(home: ProfitCalendarPage(api: api)));
      await tester.pumpAndSettle();

      expect(find.textContaining('没取上来'), findsOneWidget);
      expect(find.text('再试一次'), findsOneWidget);
    });
  });
}
