import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/trading_page.dart';
import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/theme/app_colors.dart';
import 'package:adai_web/utils/trade_import_parser.dart';

/// UTF-8 JSON 响应：MockClient 默认 Latin-1 编码 body，中文会炸，必须显式 charset=utf-8。
http.Response _json(Object body) => http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

const _portfolioJson = {
  'totalValue': 5220.0,
  'totalPnl': 160.0,
  'cashBalance': 2000.0,
  'positionCount': 1,
};

Map<String, dynamic> _positionJson({Map<String, dynamic>? extra}) => {
      'symbol': '600123',
      'name': '立昂微',
      'quantity': 200,
      'avgCost': 25.30,
      'currentPrice': 26.10,
      'marketValue': 5220.00,
      'pnl': 160.00,
      'pnlPercent': 3.16,
      'stopLossPrice': 22.80,
      'buyPoint': 'B2',
      ...?extra,
    };

/// 基础交易页 mock：portfolio + positions + 空 trades/reviews。
Map<String, dynamic> _accountJson() => {
  'assets': 110504.88, 'cash': 292.88, 'available': 292.88, 'withdrawable': 292.88,
  'marketValue': 110212.00, 'pnl': 15235.55, 'todayPnl': 0.0, 'snapshotDate': '2026-08-16',
};

MockClient _tradingMock() {
  return MockClient((request) async {
    final path = request.url.path;
    if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
    if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
    if (path == '/api/v1/trading/account') return _json(_accountJson());
    if (path == '/api/v1/trading/watchlist') return _json([]);
    if (path == '/api/v1/trading/sold') return _json([]);
    if (path == '/api/v1/trading/buy-points') return _json([]);
    if (path == '/api/v1/trading/sold/score') return _json([]);

    if (path == '/api/v1/trading/equity-curve') {
      return _json({
        'points': [
          {'date': '2026-08-03', 'totalAssets': 95000.0, 'cash': 20000.0, 'marketValue': 75000.0, 'invested': 100000.0, 'netValue': 0.95, 'drawdown': 0.05},
          {'date': '2026-08-04', 'totalAssets': 108000.0, 'cash': 8000.0, 'marketValue': 100000.0, 'invested': 100000.0, 'netValue': 1.08, 'drawdown': 0.0},
          {'date': '2026-08-05', 'totalAssets': 112000.0, 'cash': 5000.0, 'marketValue': 107000.0, 'invested': 100000.0, 'netValue': 1.12, 'drawdown': 0.0},
        ],
        'skippedDays': 0,
        'startDate': '2026-08-03',
        'endDate': '2026-08-05',
      });
    }

    if (path == '/api/v1/trading/trades') return _json([]);
    if (path == '/api/v1/trading/reviews') return _json([]);
    return http.Response('not found', 404);
  });
}

/// 挂载交易页（宽视口，12 列 DataTable 全可见）。
Future<void> _pumpTrading(WidgetTester tester, ApiService api) async {
  await tester.binding.setSurfaceSize(const Size(1800, 900));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: TradingPage(api: api))));
  await tester.pumpAndSettle();
}

/// 按 InputDecoration labelText 找 TextField。
Finder _field(String label) => find.widgetWithText(TextField, label);

void main() {
  _marketStageGroup();
  group('批量导入格式识别（2026-08-18：通达信持仓 / 历史成交 / 交易 CSV 三格式分流）', () {
    test('历史成交查询导出被识别（isTdxHistoryExport）', () {
      const history = '''
-------------------------------------------------------------------------------------------------------

成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
20260803        14:52:56        600206          有研新材        卖出            -200.00         33.12000000         6624.00         151117          69351117                6620.05          A511358384
''';
      expect(isTdxHistoryExport(history), isTrue, reason: '含成交日期/证券代码/买卖标志/成交编号');
      expect(isTdxExport(history), isFalse, reason: '无成本价列，不误判为持仓导出');
    });

    test('持仓导出不被误判为历史成交', () {
      const positions = '代码\t名称\t涨幅%\t现价\t成本价\t证券数量\n000725\t京东方Ａ\t6.41\t6.47\t6.203\t4800\n';
      expect(isTdxHistoryExport(positions), isFalse);
      expect(isTdxExport(positions), isTrue);
    });

    test('交易 CSV 不被误判为任何通达信导出', () {
      expect(isTdxHistoryExport('600519,贵州茅台,BUY,1500,100,1350,B1'), isFalse);
      expect(isTdxExport('600519,贵州茅台,BUY,1500,100,1350,B1'), isFalse);
    });
  });

  group('批量导入解析（parseImportTrades）', () {
    test('BUY 行完整解析（含 reason，第 8 列）', () {
      final r = parseImportTrades('600519,贵州茅台,BUY,1500,100,1350,B1,季报前埋伏');
      expect(r.errors, isEmpty);
      expect(r.rows.length, 1);
      final row = r.rows.first;
      expect(row.symbol, '600519');
      expect(row.name, '贵州茅台');
      expect(row.direction, 'BUY');
      expect(row.price, 1500);
      expect(row.volume, 100);
      expect(row.stopLossPrice, 1350);
      expect(row.buyPoint, 'B1');
      expect(row.reason, '季报前埋伏');
    });

    test('表头行自动跳过', () {
      final r = parseImportTrades(
          '代码,名称,方向,价格,数量,止损,买点\n600123,立昂微,买,25.30,200,22.8,B2');
      expect(r.errors, isEmpty);
      expect(r.rows.length, 1);
      expect(r.rows.first.symbol, '600123');
    });

    test('中文逗号 + 方向别名（买/卖）', () {
      final r = parseImportTrades(
          '600123，立昂微，买，25.30，200，22.8，B2\n600519，贵州茅台，卖，1500，100，');
      expect(r.errors, isEmpty);
      expect(r.rows.length, 2);
      expect(r.rows[0].direction, 'BUY');
      expect(r.rows[1].direction, 'SELL');
      expect(r.rows[1].stopLossPrice, isNull);
      expect(r.rows[1].buyPoint, isNull);
    });

    test('BUY 缺止损位 → 人话错误（带行号）', () {
      final r = parseImportTrades('600519,贵州茅台,BUY,1500,100,,B1');
      expect(r.rows, isEmpty);
      expect(r.errors.single, contains('第 1 行'));
      expect(r.errors.single, contains('止损'));
    });

    test('BUY 缺买点 → 错误', () {
      final r = parseImportTrades('600519,贵州茅台,BUY,1500,100,1350,');
      expect(r.rows, isEmpty);
      expect(r.errors.single, contains('买点'));
    });

    test('价格/数量非法 → 逐行错误', () {
      final r = parseImportTrades(
          '600519,贵州茅台,BUY,abc,100,1350,B1\n600519,贵州茅台,BUY,1500,0,1350,B1');
      expect(r.rows, isEmpty);
      expect(r.errors.length, 2);
      expect(r.errors[0], contains('价格'));
      expect(r.errors[1], contains('数量'));
    });

    test('买点不在白名单 → 错误', () {
      final r = parseImportTrades('600519,贵州茅台,BUY,1500,100,1350,XX');
      expect(r.rows, isEmpty);
      expect(r.errors.single, contains('不在可选范围'));
    });

    test('空行跳过', () {
      final r = parseImportTrades(
          '600123,立昂微,BUY,25.30,200,22.8,B2\n\n600519,贵州茅台,BUY,1500,100,1350,B1');
      expect(r.rows.length, 2);
      expect(r.errors, isEmpty);
    });

    test('toJson 与 recordTrade 字段一致；SELL 不带止损/买点', () {
      final r = parseImportTrades('600519,贵州茅台,卖,1500,100,,');
      final json = r.rows.first.toJson();
      expect(json['direction'], 'SELL');
      expect(json.containsKey('stopLossPrice'), isFalse);
      expect(json.containsKey('buyPoint'), isFalse);
      expect(json.containsKey('reason'), isFalse);
    });
  });

  group('交易 DTO 解析（RFC 20260816 新字段）', () {
    test('TradeRecordItem 解析 entryDate/止损/买点/目标价/原因', () {
      final t = TradeRecordItem.fromJson({
        'id': 'trade_1',
        'symbol': '600123',
        'name': '立昂微',
        'direction': 'buy',
        'price': 25.3,
        'volume': 200,
        'entryDate': '2026-08-12',
        'stopLossPrice': 22.8,
        'buyPoint': 'B2',
        'targetPrice': 30.0,
        'reason': '平台突破',
      });
      expect(t.isBuy, isTrue);
      expect(t.symbol, '600123');
      expect(t.entryDate, '2026-08-12');
      expect(t.stopLossPrice, 22.8);
      expect(t.buyPoint, 'B2');
      expect(t.targetPrice, 30.0);
      expect(t.reason, '平台突破');
      expect(t.amount, closeTo(5060.0, 0.001)); // price × volume 兜底
    });

    test('TradeRecordItem timestamp 兜底取日期部分', () {
      final t = TradeRecordItem.fromJson({
        'symbol': '600519',
        'direction': 'SELL',
        'price': 1500.0,
        'volume': 100,
        'timestamp': '2026-08-11T10:30:00',
      });
      expect(t.direction, 'SELL');
      expect(t.isBuy, isFalse);
      expect(t.entryDate, '2026-08-11');
      expect(t.stopLossPrice, isNull);
    });

    test('TradeRecordItem 解析 tradeTime（RFC 20260822 成交时间，可空）', () {
      final t = TradeRecordItem.fromJson({
        'symbol': '600206',
        'direction': 'SELL',
        'price': 33.12,
        'volume': 200,
        'entryDate': '2026-08-03',
        'tradeTime': '14:52:56',
      });
      expect(t.tradeTime, '14:52:56');
      // 旧数据无 tradeTime → null（不报错）
      final old = TradeRecordItem.fromJson({
        'symbol': '600519', 'direction': 'BUY', 'price': 10.0, 'volume': 100,
      });
      expect(old.tradeTime, isNull);
    });

    test('DailyTradeSummaryDto 解析（RFC 20260822 时段分桶）', () {
      final d = DailyTradeSummaryDto.fromJson({
        'date': '2026-08-22', 'count': 4, 'buyCount': 3, 'sellCount': 1,
        'sessions': [
          {'name': '早盘', 'range': '09:30-11:30', 'count': 2},
          {'name': '午盘', 'range': '13:00-14:30', 'count': 1},
          {'name': '尾盘', 'range': '14:30-15:00', 'count': 1},
        ],
        'firstTradeTime': '09:41:00', 'lastTradeTime': '14:52:00',
      });
      expect(d.count, 4);
      expect(d.buyCount, 3);
      expect(d.sessions.length, 3);
      expect(d.sessions[0].name, '早盘');
      expect(d.sessions[0].count, 2);
      expect(d.firstTradeTime, '09:41:00');
    });

    test('BatchImportResponse 解析 success + failures（row/message）', () {
      final r = BatchImportResponse.fromJson({
        'success': 3,
        'failures': [
          {'row': 2, 'symbol': '600123', 'message': '价格不是有效正数'},
        ],
      });
      expect(r.success, 3);
      expect(r.hasFailures, isTrue);
      expect(r.failures.length, 1);
      expect(r.failures.first.row, 2);
      expect(r.failures.first.message, '价格不是有效正数');
    });

    test('BatchImportResponse errors 别名 + 空失败项过滤', () {
      final r = BatchImportResponse.fromJson({
        'ok': 1,
        'errors': [
          {'line': 4, 'error': '买入缺止损'},
          {'message': ''},
        ],
      });
      expect(r.success, 1);
      expect(r.failures.length, 1);
      expect(r.failures.first.message, '买入缺止损');
    });

    test('PositionItem 新字段解析 + 旧数据缺省兜底', () {
      final p = PositionItem.fromJson(_positionJson(extra: {
        'entryDate': '2026-08-01',
        'role': '防守·主仓',
        'targetPrice': 30.0,
        'computedStopLossPrice': 21.9,
        'effectiveStopLoss': 22.8,
      }));
      expect(p.stopLossPrice, 22.8);
      expect(p.buyPoint, 'B2');
      expect(p.entryDate, '2026-08-01');
      expect(p.role, '防守·主仓');
      expect(p.targetPrice, 30.0);
      // 双止损位（trading-risk-plan）：计算止损 + 生效止损 = max(人工, 计算)
      expect(p.computedStopLossPrice, 21.9);
      expect(p.effectiveStopLoss, 22.8);
      // 旧 positions.md 无新列 → null 兜底
      final old = PositionItem.fromJson(
          {'symbol': '600123', 'quantity': 100, 'avgCost': 1.0, 'currentPrice': 1.0});
      expect(old.stopLossPrice, isNull);
      expect(old.buyPoint, isNull);
      expect(old.role, isNull);
      expect(old.computedStopLossPrice, isNull);
      expect(old.effectiveStopLoss, isNull);
    });
  });

  group('ApiService 新端点契约', () {
    test('recordTrade 带 stopLoss/buyPoint/targetPrice/reason', () async {
      Map<String, dynamic>? sent;
      final client = MockClient((request) async {
        sent = jsonDecode(request.body) as Map<String, dynamic>;
        return _json([_positionJson()]);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.recordTrade(
        symbol: '600519',
        name: '贵州茅台',
        direction: 'BUY',
        price: 1500,
        volume: 100,
        stopLossPrice: 1350,
        buyPoint: 'B1',
        targetPrice: 1800,
        reason: '季报前埋伏',
      );
      expect(sent!['symbol'], '600519');
      expect(sent!['stopLossPrice'], 1350);
      expect(sent!['buyPoint'], 'B1');
      expect(sent!['targetPrice'], 1800);
      expect(sent!['reason'], '季报前埋伏');
    });

    test('recordTrade SELL 空字段不发送', () async {
      Map<String, dynamic>? sent;
      final client = MockClient((request) async {
        sent = jsonDecode(request.body) as Map<String, dynamic>;
        return _json([_positionJson()]);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.recordTrade(
          symbol: '600519', name: '', direction: 'SELL', price: 1500, volume: 100);
      expect(sent!.containsKey('stopLossPrice'), isFalse);
      expect(sent!.containsKey('buyPoint'), isFalse);
      expect(sent!.containsKey('reason'), isFalse);
    });

    test('updatePosition → PUT /positions/{symbol}，body 只带非空字段', () async {
      String? method;
      String? path;
      Map<String, dynamic>? sent;
      final client = MockClient((request) async {
        method = request.method;
        path = request.url.path;
        sent = jsonDecode(request.body) as Map<String, dynamic>;
        return _json(_positionJson(extra: {'role': '前锋·主仓', 'stopLossPrice': 24.0}));
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      final p = await api.updatePosition('600123',
          role: '前锋·主仓', stopLossPrice: 24.0);
      expect(method, 'PUT');
      expect(path, '/api/v1/trading/positions/600123');
      expect(sent!['role'], '前锋·主仓');
      expect(sent!['stopLossPrice'], 24.0);
      expect(p.role, '前锋·主仓');
      expect(p.stopLossPrice, 24.0);
    });

    test('updatePosition 返回数组（宽松解析取首条）', () async {
      final client = MockClient((request) async {
        return _json([_positionJson(extra: {'role': '机动·主仓'})]);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      final p = await api.updatePosition('600123', role: '机动·主仓');
      expect(p.role, '机动·主仓');
    });

    test('getTrades 带 from/to 参数 + 列表解析', () async {
      String? from;
      String? to;
      final client = MockClient((request) async {
        from = request.url.queryParameters['from'];
        to = request.url.queryParameters['to'];
        return _json([
          {'symbol': '600123', 'direction': 'BUY', 'price': 25.3, 'volume': 200, 'entryDate': '2026-08-12'},
        ]);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      final trades = await api.getTrades(from: '2026-07-01', to: '2026-08-12');
      expect(from, '2026-07-01');
      expect(to, '2026-08-12');
      expect(trades.length, 1);
      expect(trades.first.symbol, '600123');
    });

    test('importTrades → POST /trades/batch body {"trades": [...]}', () async {
      Map<String, dynamic>? sent;
      final client = MockClient((request) async {
        sent = jsonDecode(request.body) as Map<String, dynamic>;
        return _json({'success': 2, 'failures': []});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      final r = await api.importTrades([
        {'symbol': '600123', 'name': '立昂微', 'direction': 'BUY', 'price': 25.3, 'volume': 200, 'stopLossPrice': 22.8, 'buyPoint': 'B2'},
      ]);
      expect(sent!['trades'], isA<List>());
      expect((sent!['trades'] as List).length, 1);
      expect(r.success, 2);
      expect(r.hasFailures, isFalse);
    });
  });

  group('记录交易 Dialog（RFC 20260816 新字段）', () {
    testWidgets('BUY 缺止损位 → 阻止提交 + 人话提示，弹窗不关闭', (tester) async {
      final api = ApiService(baseUrl: 'http://test', client: _tradingMock());
      await _pumpTrading(tester, api);

      await tester.tap(find.widgetWithText(FilledButton, '记录交易'));
      await tester.pumpAndSettle();
      // 默认 BUY：止损位/买点类型可见
      expect(find.text('止损位'), findsOneWidget);
      expect(find.text('买点类型'), findsOneWidget);

      await tester.enterText(_field('代码'), '600519');
      await tester.enterText(_field('价格'), '1500');
      await tester.enterText(_field('数量'), '100');

      // 2026-08-17：价格填完止损位自动带出（默认 -7% = 1395），清空后提交才缺止损
      expect(find.text('1395.00'), findsOneWidget, reason: '价格 1500 → 默认止损 1500×0.93=1395');
      await tester.enterText(_field('止损位'), '');

      await tester.tap(find.text('提交'));
      await tester.pump();

      expect(find.textContaining('买入请填止损位'), findsOneWidget);
      expect(find.byType(AlertDialog), findsOneWidget); // 弹窗仍打开
    });

    testWidgets('BUY 填止损 → 提交，请求带 stopLossPrice/buyPoint', (tester) async {
      Map<String, dynamic>? sent;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
    if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);

    if (path == '/api/v1/trading/watchlist') return _json([]);
    if (path == '/api/v1/trading/sold') return _json([]);
    if (path == '/api/v1/trading/buy-points') return _json([]);
    if (path == '/api/v1/trading/sold/score') return _json([]);

        if (path == '/api/v1/trading/trades') {
          sent = jsonDecode(request.body) as Map<String, dynamic>;
          return _json([_positionJson()]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.widgetWithText(FilledButton, '记录交易'));
      await tester.pumpAndSettle();
      await tester.enterText(_field('代码'), '600519');
      await tester.enterText(_field('价格'), '1500');
      await tester.enterText(_field('数量'), '100');
      await tester.enterText(_field('止损位'), '1350');
      await tester.enterText(_field('目标价（可选）'), '1800');
      await tester.enterText(_field('交易原因（可选）'), '季报前埋伏');
      await tester.tap(find.text('提交'));
      await tester.pumpAndSettle();

      expect(sent, isNotNull);
      expect(sent!['symbol'], '600519');
      expect(sent!['stopLossPrice'], 1350);
      expect(sent!['buyPoint'], 'B1'); // 默认买点
      expect(sent!['targetPrice'], 1800);
      expect(sent!['reason'], '季报前埋伏');
    });

    testWidgets('SELL 隐藏止损位/买点，提交不带这两字段', (tester) async {
      Map<String, dynamic>? sent;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
    if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);

    if (path == '/api/v1/trading/watchlist') return _json([]);
    if (path == '/api/v1/trading/sold') return _json([]);
    if (path == '/api/v1/trading/buy-points') return _json([]);
    if (path == '/api/v1/trading/sold/score') return _json([]);

        if (path == '/api/v1/trading/trades') {
          sent = jsonDecode(request.body) as Map<String, dynamic>;
          return _json([_positionJson()]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.widgetWithText(FilledButton, '记录交易'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('卖出'));
      await tester.pump();
      expect(find.text('止损位'), findsNothing);
      expect(find.text('买点类型'), findsNothing);

      await tester.enterText(_field('代码'), '600519');
      await tester.enterText(_field('价格'), '1500');
      await tester.enterText(_field('数量'), '100');
      await tester.tap(find.text('提交'));
      await tester.pumpAndSettle();

      expect(sent, isNotNull);
      expect(sent!['direction'], 'SELL');
      expect(sent!.containsKey('stopLossPrice'), isFalse);
      expect(sent!.containsKey('buyPoint'), isFalse);
    });
  });

  group('持仓编辑（web 独有，PUT）', () {
    testWidgets('行「编辑」→ 改角色/止损 → PUT /positions/{symbol} → 表格更新', (tester) async {
      var putCalls = 0;
      Map<String, dynamic>? sentBody;
      var current = _positionJson();
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/positions' && request.method == 'GET') {
          return _json([current]);
        }
        if (path == '/api/v1/trading/positions/600123' && request.method == 'PUT') {
          putCalls++;
          sentBody = jsonDecode(request.body) as Map<String, dynamic>;
          current = _positionJson(extra: {
            'role': sentBody!['role'] as String?,
            'stopLossPrice': (sentBody!['stopLossPrice'] as num?)?.toDouble(),
          });
          return _json(current);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('编辑'));
      await tester.pumpAndSettle();
      expect(find.textContaining('编辑持仓'), findsOneWidget);

      // 角色下拉：默认 机动·副仓 → 选 前锋·主仓
      await tester.tap(find.text('机动·副仓'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('前锋·主仓').last);
      await tester.pumpAndSettle();

      await tester.enterText(_field('止损位'), '24.0');
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();

      expect(putCalls, 1);
      expect(sentBody!['role'], '前锋·主仓');
      expect(sentBody!['stopLossPrice'], 24.0);
      // 刷新后表格显示新角色
      expect(find.text('前锋·主仓'), findsOneWidget);
      expect(find.text('24.000'), findsOneWidget);
    });

    testWidgets('止损位填 0 或负数 → 人话提示，不发请求', (tester) async {
      var putCalls = 0;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
    if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);

    if (path == '/api/v1/trading/watchlist') return _json([]);
    if (path == '/api/v1/trading/sold') return _json([]);
    if (path == '/api/v1/trading/buy-points') return _json([]);
    if (path == '/api/v1/trading/sold/score') return _json([]);

        if (request.method == 'PUT') putCalls++;
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('编辑'));
      await tester.pumpAndSettle();
      await tester.enterText(_field('止损位'), '0');
      await tester.tap(find.text('保存'));
      await tester.pump();

      expect(find.text('止损位需要是大于 0 的数字'), findsOneWidget);
      expect(putCalls, 0);
    });
  });

  group('持仓 Tab 导入（2026-08-23：页头「批量导入」移除，持仓导入归持仓 Tab）', () {
    testWidgets('通达信持仓导出 → POST /positions/import?replace=true → 提示导入数', (tester) async {
      List<dynamic>? sentBody;
      Uri? sentUri;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions' && request.method == 'GET') {
          return _json([_positionJson()]);
        }
        if (path == '/api/v1/trading/positions/import' && request.method == 'POST') {
          sentBody = jsonDecode(request.body) as List<dynamic>;
          sentUri = request.url;
          return _json({'imported': 2, 'missingStopLoss': ['600519']});
        }
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      // 持仓 Tab 内导入按钮（页头批量导入已移除，不再有 upload_file_outlined 图标按钮）
      expect(find.byIcon(Icons.upload_file_outlined), findsNothing);
      await tester.tap(find.text('导入持仓'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byType(TextField),
        '代码\t名称\t成本价\t证券数量\n600123\t立昂微\t25.30\t200\n600519\t贵州茅台\t1350\t100\n',
      );
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      expect(sentBody, isNotNull);
      expect(sentBody!.length, 2);
      expect(sentBody![0]['symbol'], '600123');
      expect(sentBody![0]['avgCost'], 25.30);
      expect(sentUri!.queryParameters['replace'], 'true');
      expect(find.textContaining('持仓导入 2 只'), findsOneWidget);
      expect(find.textContaining('未设止损 1 只'), findsOneWidget);
    });

    // ── 负成本事故（2026-09-13）核心行为：看不懂的行 → 拒绝全量覆盖，不发请求 ──
    // 现场：用户 3 只持仓只进来 2 只、界面无任何提示。根因是解析器把负成本当脏数据静默丢弃，
    // 而持仓导入是 replace=true 全量覆盖——漏一行 = 该持仓被静默删除。
    testWidgets('有看不懂的行 → fail-closed：不发请求、持仓不动、逐行摆出原因', (tester) async {
      var postCalls = 0;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions' && request.method == 'GET') {
          return _json([_positionJson()]);
        }
        if (path == '/api/v1/trading/positions/import' && request.method == 'POST') {
          postCalls++;
          return _json({'imported': 1, 'missingStopLoss': []});
        }
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('导入持仓'));
      await tester.pumpAndSettle();
      // 第 2 行成本列不是数字（真看不懂）→ 整份不得覆盖
      await tester.enterText(
        find.byType(TextField),
        '证券代码\t证券名称\t股票余额\t成本价\n'
        '600206\t有研新材\t900\t46.012\n'
        '600601\t方正科技\t100\t--\n',
      );
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      expect(postCalls, 0, reason: '有看不懂的行时绝不能发全量覆盖请求（会误删持仓）');
      expect(find.text('先不动你的持仓'), findsOneWidget, reason: '必须显式说明为什么没导');
      expect(find.textContaining('不是数字'), findsOneWidget, reason: '要逐行摆出原因，不能只说「失败」');
      expect(find.textContaining('看懂了的 1 行是：600206'), findsOneWidget, reason: '告知看懂了几行');
    });

    testWidgets('负成本持仓正常导入（不再被当脏数据丢掉）', (tester) async {
      List<dynamic>? sentBody;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions' && request.method == 'GET') {
          return _json([_positionJson()]);
        }
        if (path == '/api/v1/trading/positions/import' && request.method == 'POST') {
          sentBody = jsonDecode(request.body) as List<dynamic>;
          return _json({'imported': 3, 'missingStopLoss': []});
        }
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('导入持仓'));
      await tester.pumpAndSettle();
      // 用户那份生产文件的同构内容：3 只有持仓（含负成本）+ 1 行 0 股残留
      await tester.enterText(
        find.byType(TextField),
        '代码\t名称\t成本价\t持仓量\n'
        '600206\t有研新材\t46.012\t900\n'
        '002428\t云南锗业\t53.765\t400\n'
        '600601\t方正科技\t-5.078\t100\n'
        '603113\t金能科技\t5.569\t0\n',
      );
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      expect(sentBody, isNotNull, reason: '3 只全解析成功 → 正常导入');
      expect(sentBody!.length, 3, reason: '3 只有持仓全部要送进后端（事故时只送了 2 只）');
      final fangzheng = sentBody!.firstWhere((e) => e['symbol'] == '600601');
      expect(fangzheng['avgCost'], closeTo(-5.078, 0.0001), reason: '负成本原样送后端');
      expect(find.textContaining('持仓导入 3 只'), findsOneWidget);
      expect(find.textContaining('另有 1 行已清空未计入'), findsOneWidget,
          reason: '文件 4 行、进来 3 只，如实告知（0 股残留不是错误，但要让人知道）');
    });

    testWidgets('非通达信持仓文本 → 前端拒绝，不发请求', (tester) async {
      var postCalls = 0;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions' && request.method == 'GET') {
          return _json([_positionJson()]);
        }
        if (path == '/api/v1/trading/positions/import' && request.method == 'POST') {
          postCalls++;
          return _json({'imported': 0, 'missingStopLoss': []});
        }
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('导入持仓'));
      await tester.pumpAndSettle();
      // 清仓股文本（无成本价列）不应被当作持仓导入，也不应走交易 CSV 的「买点」校验
      await tester.enterText(
        find.byType(TextField),
        '代码\t名称\t介入日期\t清仓日期\t持仓天数\t买卖次数\t持仓期涨幅%\n600519\t贵州茅台\t20260801\t20260810\t9\t1\t-5.0\n',
      );
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      expect(postCalls, 0);
      expect(find.textContaining('无法识别通达信持仓导出'), findsOneWidget);
    });
  });

  group('历史成交 Tab（RFC 20260823，取代交易历史 Dialog）', () {
    testWidgets('按日期分组渲染：方向/时间/代码/名称/数量/价格/金额/费用/成交编号（2026-08-25 精简——历史成交无止损/买点/原因，删列）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') {
          return _json([
            {
              'id': 't1',
              'symbol': '600123',
              'name': '立昂微',
              'direction': 'BUY',
              'price': 25.3,
              'volume': 200,
              'entryDate': '2026-08-12',
              'tradeTime': '09:41:00',
              'stopLossPrice': 22.8,
              'buyPoint': 'B2',
              'reason': '平台突破',
              'fee': 1.23,
              'orderId': '69351117',
            },
            {
              'id': 't2',
              'symbol': '600519',
              'name': '贵州茅台',
              'direction': 'SELL',
              'price': 1500.0,
              'volume': 100,
              'entryDate': '2026-08-11',
              'timestamp': '2026-08-11T10:30:00',
            },
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      // RFC 20260823：历史成交从页头 Dialog 升级为第 5 Tab，点击 Tab 进入
      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();

      // 日期分组（页面其它普通文本可能同日期字串，分组头按加粗精确匹配）
      expect(find.text('2026-08-12'), findsWidgets);
      expect(
        find.byWidgetPredicate((w) =>
            w is Text && w.data == '2026-08-11' && w.style?.fontWeight == FontWeight.w600),
        findsOneWidget,
        reason: '2026-08-11 分组头应存在',
      );
      // 方向 + 成交时间（tradeTime 显示 HH:mm；旧数据无 → '—'）
      expect(find.text('买入'), findsOneWidget);
      expect(find.text('卖出'), findsOneWidget);
      expect(find.text('09:41'), findsOneWidget);
      // 2026-08-25 列设计（用户拍板）：源文件字段（成交金额/发生金额）在前，系统计算的「费用」放最后
      expect(find.text('25.300'), findsOneWidget);
      expect(find.text('200'), findsOneWidget);
      expect(find.text('5,060.00'), findsOneWidget); // 成交金额（源文件）
      expect(find.text('-5,061.23'), findsOneWidget); // 发生金额（买入负扣款 = −(金额+费用)）
      expect(find.text('69351117'), findsOneWidget); // 成交编号
      expect(find.text('1.23'), findsOneWidget); // 费用（系统计算，放最后）
      expect(find.text('成交金额'), findsOneWidget);
      expect(find.text('发生金额'), findsOneWidget);
      expect(find.text('止损'), findsNothing, reason: '历史成交列已删止损（源文件无此字段）');
      expect(find.text('买点'), findsNothing, reason: '历史成交列已删买点');
      expect(find.text('原因'), findsNothing, reason: '历史成交列已删原因');
      // 旧数据无 tradeTime/fee/orderId → '—' 占位
      expect(find.text('—'), findsWidgets);
      // 区间统计行
      expect(find.textContaining('共 2 笔 · 买 1 卖 1'), findsOneWidget);
    });

    testWidgets('空区间 → 空态文案 + 导入入口', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      expect(find.text('这段时间还没有历史成交'), findsOneWidget);
      expect(find.text('导入通达信历史成交导出'), findsOneWidget);
    });

    testWidgets('导入历史成交：非历史成交格式 → 人话拒绝（RFC 20260823 只认历史成交格式）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      // 粘贴交易 CSV（非历史成交格式）→ 人话拒绝，不静默落零
      await tester.enterText(
        find.byType(TextField).last,
        '600123,立昂微,BUY,25.3,200,22.8,B2',
      );
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();
      expect(find.textContaining('无法识别'), findsOneWidget);
    });

    testWidgets('股息类资金事件显示「股息入账/红利税」类型标签，不再误显示「买入 0 股」（P2-批次6）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') {
          return _json([
            {
              'id': 't1', 'symbol': '600123', 'name': '立昂微', 'direction': 'BUY',
              'price': 25.3, 'volume': 200, 'amount': 5060.0, 'entryDate': '2026-08-12',
              'tradeTime': '09:41:00', 'reason': '平台突破', 'fee': 1.23, 'orderId': '69351117',
            },
            {
              'id': 't2', 'symbol': '600519', 'name': '贵州茅台', 'direction': 'SELL',
              'price': 1500.0, 'volume': 100, 'entryDate': '2026-08-11', 'timestamp': '2026-08-11T10:30:00',
            },
            {
              // 股息入账：BUY + volume 0 + reason=源文件备注（2026-08-25 方案 A 落流水形态）
              'id': 'd1', 'symbol': '600519', 'name': '贵州茅台', 'direction': 'BUY',
              'price': 0, 'volume': 0, 'amount': 152.5, 'entryDate': '2026-08-10', 'reason': '股息入账',
            },
            {
              // 红利税：SELL + volume 0
              'id': 'd2', 'symbol': '600519', 'name': '贵州茅台', 'direction': 'SELL',
              'price': 0, 'volume': 0, 'amount': 7.5, 'entryDate': '2026-08-10', 'reason': '股息红利税',
            },
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();

      // 类型标签取代「买入/卖出」，普通行不受影响
      expect(find.text('股息入账'), findsOneWidget);
      expect(find.text('红利税'), findsOneWidget);
      expect(find.text('买入'), findsOneWidget, reason: '仅普通 BUY 行显示买入');
      expect(find.text('卖出'), findsOneWidget);
      // 股息行数量/价格 '—'；发生金额 = ±amount（入账正 / 税负）
      expect(find.text('152.50'), findsOneWidget, reason: '股息入账发生金额为正');
      expect(find.text('-7.50'), findsOneWidget, reason: '红利税发生金额为负');
      // 统计行：股息事件不计入买卖，单列计数
      expect(find.textContaining('共 4 笔 · 买 1 卖 1 · 股息/红利 2'), findsOneWidget);
    });
  });

  group('历史成交导入聚合（P2-批次4 多文件批量）', () {
    test('多份结果计数求和 + 对账行去重 + summary 取首份 sync', () {
      final a = HistoricalTradeImportResult(
        imported: 3, updated: 1, skipped: 2, nonTrades: 1,
        lines: [
          ReconcileLine(symbol: '600123', name: '立昂微', count: 2, netVolume: 200, holdings: 200, note: '持仓匹配'),
        ],
        syncMode: 'sync',
        summary: TradeImportSummary(
          date: '2026-08-12', buyCount: 1, sellCount: 1,
          buyAmount: 5060, sellAmount: 150000, newLots: 1, deductedLots: 0, behaviors: const [],
        ),
      );
      final b = HistoricalTradeImportResult(
        imported: 2, updated: 0, skipped: 1, nonTrades: 0,
        lines: [
          // 与 a 相同的对账行 → 聚合去重
          ReconcileLine(symbol: '600123', name: '立昂微', count: 2, netVolume: 200, holdings: 200, note: '持仓匹配'),
          ReconcileLine(symbol: '600519', name: '贵州茅台', count: 1, netVolume: -100, holdings: 0, note: '已清仓'),
        ],
        syncMode: 'append',
      );
      final agg = aggregateImportResults([a, b]);
      expect(agg.imported, 5);
      expect(agg.updated, 1);
      expect(agg.skipped, 3);
      expect(agg.nonTrades, 1);
      expect(agg.lines.length, 2, reason: '对账行按 (symbol, netVolume, note) 去重');
      expect(agg.syncMode, 'sync', reason: '任一文件 sync 即 sync');
      expect(agg.summary, isNotNull);
    });

    test('TradeRecordItem 股息事件识别与标签（P2-批次6）', () {
      final divIn = TradeRecordItem.fromJson({
        'id': 'd1', 'symbol': '600519', 'name': '贵州茅台', 'direction': 'BUY',
        'price': 0, 'volume': 0, 'amount': 152.5, 'entryDate': '2026-08-12', 'reason': '股息入账',
      });
      expect(divIn.isDividendEvent, isTrue);
      expect(divIn.dividendLabel, '股息入账');
      final divTax = TradeRecordItem.fromJson({
        'id': 'd2', 'symbol': '600519', 'name': '贵州茅台', 'direction': 'SELL',
        'price': 0, 'volume': 0, 'amount': 7.5, 'entryDate': '2026-08-12', 'reason': '股息红利税',
      });
      expect(divTax.isDividendEvent, isTrue);
      expect(divTax.dividendLabel, '红利税');
      final normal = TradeRecordItem.fromJson({
        'id': 't1', 'symbol': '600123', 'name': '立昂微', 'direction': 'BUY',
        'price': 25.3, 'volume': 200, 'amount': 5060, 'entryDate': '2026-08-12', 'reason': '平台突破',
      });
      expect(normal.isDividendEvent, isFalse, reason: '普通成交（volume>0）不是股息事件');
    });
  });

  group('复盘历史 Dialog', () {
    testWidgets('日期列表 → 点击加载复盘内容', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
    if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);

    if (path == '/api/v1/trading/watchlist') return _json([]);
    if (path == '/api/v1/trading/sold') return _json([]);
    if (path == '/api/v1/trading/buy-points') return _json([]);
    if (path == '/api/v1/trading/sold/score') return _json([]);

        if (path == '/api/v1/trading/reviews') return _json(['2026-08-12', '2026-08-11']);
        if (path == '/api/v1/trading/review') {
          final date = request.url.queryParameters['date'] ?? '';
          return _json({'date': date, 'content': '## $date 复盘\n今天执行得不错。'});
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.byIcon(Icons.calendar_month_outlined));
      await tester.pumpAndSettle();

      expect(find.text('复盘历史'), findsOneWidget);
      expect(find.text('2026-08-12'), findsOneWidget);
      expect(find.text('2026-08-11'), findsOneWidget);
      // 默认打开最新复盘内容
      expect(find.textContaining('2026-08-12 复盘'), findsOneWidget);

      // 点旧日期 → 内容切换
      await tester.tap(find.text('2026-08-11'));
      await tester.pumpAndSettle();
      expect(find.textContaining('2026-08-11 复盘'), findsOneWidget);
    });
  });

  group('通达信持仓导入', () {
    test('制表符导出 → 持仓快照行', () {
      const text = '市场\t证券代码\t证券名称\t股票余额\t可用余额\t成本价\t市价\n'
          '上海A\t600519\t贵州茅台\t100\t100\t1400.00\t1420.00\n'
          '深圳A\t000725\t京东方A\t1000\t1000\t5.20\t5.46\n';
      final r = parseTdxPositions(text);
      expect(r.errors, isEmpty);
      expect(r.rows.length, 2);
      expect(r.rows[0].symbol, '600519');
      expect(r.rows[0].name, '贵州茅台');
      expect(r.rows[0].quantity, 100);
      expect(r.rows[0].avgCost, 1400.00);
      expect(r.rows[1].symbol, '000725');
    });

    test('空格分隔 + 千分位数量', () {
      const text = '证券代码 证券名称 股票余额 成本价\n'
          '600519 贵州茅台 1,000 1400.50\n';
      final r = parseTdxPositions(text);
      expect(r.errors, isEmpty);
      expect(r.rows.single.quantity, 1000);
      expect(r.rows.single.avgCost, 1400.50);
    });

    test('非法行收集人话错误；0 股归「已清空跳过」不算错误（2026-09-13 负成本批改口径）', () {
      const text = '证券代码\t证券名称\t股票余额\t成本价\n'
          'ABCD\t非法代码\t100\t10\n'
          '600519\t贵州茅台\t0\t1400\n';
      final r = parseTdxPositions(text);
      expect(r.rows, isEmpty);
      // 代码不是 6 位 = 真看不懂 → errors；0 股 = 券商文件里保留的已清空标的 → skipped
      expect(r.errors.length, 1, reason: 'errors=${r.errors}');
      expect(r.errors.join(' '), contains('六位数字'));
      expect(r.skipped.length, 1, reason: 'skipped=${r.skipped}');
      expect(r.skipped.join(' '), contains('已清空'));
    });

    // ── 负成本持仓批（2026-09-13）用户实测事故回归 ──
    // 现场：用户 3 只持仓只进来 2 只。被丢的是 600601 方正科技 100 股 / 成本 −5.078——
    // 反复做 T / 分红把成本摊到 0 以下是合法且券商就这么记的，原实现 cost <= 0 一律当脏数据丢弃。
    group('负成本持仓（2026-09-13 事故回归）', () {
      // 与生产文件同构：4 行 = 3 只有持仓 + 1 行 0 股残留
      const realFile = '代码\t名称\t涨幅%\t现价\t涨跌\t换手%\t涨跌%\t成本价\t持仓量\t市值\t盈亏\t盈亏%\t当日盈亏\t币种\t代码2\t交易所\t板块\n'
          '600206\t有研新材\t-2.65\t45.55\t-1.24\t8.70\t-0.03\t46.012\t900\t40995.00\t-415.62\t-1.00\t-1116.00\tCNY\t600206\t沪主板\t北京\n'
          '002428\t云南锗业\t-1.79\t88.43\t-1.61\t6.23\t0.02\t53.765\t400\t35372.00\t13865.88\t64.47\t-644.00\tCNY\t002428\t深主板\t云南\n'
          '600601\t方正科技\t0.07\t14.83\t0.01\t5.76\t-0.06\t-5.078\t100\t1483.00\t1990.77\t134.24\t1.00\tCNY\t600601\t沪主板\t上海\n'
          '603113\t金能科技\t-4.92\t5.02\t-0.26\t3.89\t0.20\t5.569\t0\t0.00\t-0.00\t-9.86\t-0.00\tCNY\t603113\t沪主板\t山东\n'
          '#数据来源:通达信\n';

      test('用户那份真实文件的 3 只有持仓必须全部解析出来（含成本 −5.078 的 600601）', () {
        expect(isTdxExport(realFile), isTrue);
        final r = parseTdxPositions(realFile);
        expect(r.errors, isEmpty, reason: '真实文件不应有任何「看不懂」的行：errors=${r.errors}');
        expect(r.rows.length, 3, reason: '3 只有持仓全部要进来（事故时只进来 2 只）');
        expect(r.rows.map((e) => e.symbol).toList(), ['600206', '002428', '600601']);
        final fangzheng = r.rows.firstWhere((e) => e.symbol == '600601');
        expect(fangzheng.name, '方正科技');
        expect(fangzheng.quantity, 100);
        expect(fangzheng.avgCost, closeTo(-5.078, 0.0001),
            reason: '负成本必须原样保留（不做符号修正，它就是券商口径）');
        // 0 股残留行如实归入 skipped
        expect(r.skipped.length, 1);
        expect(r.skipped.single, contains('603113'));
      });

      test('负成本与零成本都接受（只有「取不到数」才是错误）', () {
        const text = '证券代码\t证券名称\t股票余额\t成本价\n'
            '600601\t方正科技\t100\t-5.078\n'
            '600602\t云赛智联\t200\t0\n'
            '600603\t广汇物流\t300\t--\n';
        final r = parseTdxPositions(text);
        expect(r.rows.length, 2, reason: '负成本/零成本合法；成本列不是数字才是错误');
        expect(r.rows[0].avgCost, closeTo(-5.078, 0.0001));
        expect(r.rows[1].avgCost, 0);
        expect(r.errors.length, 1);
        expect(r.errors.join(' '), contains('不是数字'));
      });

      test('短行不再抛 RangeError（原实现只校验数量列却读成本列 → 粘贴半截文件即崩）', () {
        const text = '证券代码\t证券名称\t股票余额\t成本价\n'
            '600601\t方正科技\t100\n';
        final r = parseTdxPositions(text);
        expect(r.rows, isEmpty);
        expect(r.errors.single, contains('字段不足'));
      });

      test('负数数量是真错误（不是「已清空」）', () {
        const text = '证券代码\t证券名称\t股票余额\t成本价\n'
            '600601\t方正科技\t-100\t5.078\n';
        final r = parseTdxPositions(text);
        expect(r.rows, isEmpty);
        expect(r.skipped, isEmpty);
        expect(r.errors.single, contains('为负'));
      });
    });

    test('isTdxExport 识别通达信 vs 交易 CSV', () {
      expect(isTdxExport('证券代码\t证券名称\t股票余额\t成本价\n600519\t贵州茅台\t100\t1400'),
          isTrue);
      expect(isTdxExport('600519,贵州茅台,BUY,1500,100,1350,B1'), isFalse);
    });
  });


    test('真实通达信导出（证券数量列 + # 注释行）', () {
      const text = '代码\t名称\t涨幅%\t现价\t涨跌\t换手%\t涨速%\t成本价\t证券数量\t最新市值\t持仓盈亏\n'
          '000725\t京东方Ａ\t-0.85\t5.81\t-0.05\t3.56\t0.17\t6.042\t5300\t30793\t-1230.13\n'
          '002131\t利欧股份\t-4.14\t5.33\t-0.23\t17.94\t-0.18\t5.567\t3500\t18655\t-830.2\n'
          '601066\t中信建投\t-1.66\t25.54\t-0.43\t0.36\t0.04\t26.191\t1100\t28094\t-716.65\n'
          '002428\t云南锗业\t1.38\t101.59\t1.38\t11.96\t-0.03\t12.05\t200\t20318\t17908.1\n'
          '600809\t山西汾酒\t-2.39\t123.52\t-3.03\t0.57\t-0.01\t122.385\t100\t12352\t113.51\n'
          '#数据来源:通达信\n';
      expect(isTdxExport(text), isTrue);
      final r = parseTdxPositions(text);
      expect(r.errors, isEmpty, reason: 'errors=${r.errors}');
      expect(r.rows.length, 5);
      expect(r.rows[0].symbol, '000725');
      expect(r.rows[0].quantity, 5300);
      expect(r.rows[0].avgCost, 6.042);
      expect(r.rows[4].avgCost, 122.385);
    });

  group('自选股买点信号（C2）', () {
    testWidgets('命中 B1 显示红色信号，未命中显示 —', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') {
          return _json([
            {'symbol': '000725', 'name': '京东方A', 'industry': '面板', 'industry2': '',
             'longForm': 1, 'midForm': 2, 'shortForm': 3, 'signal': '金叉', 'addedAt': '2026-08-16'},
            {'symbol': '600519', 'name': '贵州茅台', 'industry': '白酒', 'industry2': '',
             'longForm': 0, 'midForm': 0, 'shortForm': 0, 'signal': '', 'addedAt': '2026-08-16'},
          ]);
        }
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/buy-points') {
          return _json([
            {'symbol': '000725', 'name': '京东方A', 'buyPoint': 'B1', 'score': 87,
             'signals': ['回调 52% ≥ 50%', '缩量 0.6', 'KDJ.J 12 < 20']},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      // 切到自选 Tab
      await tester.tap(find.text('自选'));
      await tester.pumpAndSettle();

      // 命中：B1 87%（score 0-100 量纲，F53）
      expect(find.text('B1 87%'), findsOneWidget);
      // 未命中：买点信号列 '—'（账户卡总盈亏也显 '—'——mock 无 principal=0，P2-交易31 不给误导数值）
      expect(find.text('—'), findsWidgets);
      expect(find.text('B1 87%'), findsOneWidget);
    });

    testWidgets('P2-案例2：buyPoint=case 显示「案例相似 N%」，不再渲染「case 0%」', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') {
          return _json([
            {'symbol': '000725', 'name': '京东方A', 'industry': '面板', 'industry2': '',
             'longForm': 1, 'midForm': 2, 'shortForm': 3, 'signal': '', 'addedAt': '2026-08-16'},
          ]);
        }
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/buy-points') {
          // 二期开关开：规则未命中但案例相似 → buyPoint="case"（score=0 无意义，附 caseMatches）
          return _json([
            {'symbol': '000725', 'name': '京东方A', 'buyPoint': 'case', 'score': 0,
             'signals': <String>[],
             'caseMatches': [
               {'caseId': '2026-08-03_000725', 'buyDate': '2026-08-03', 'buyType': 'B1',
                'similarityPercent': 92.0},
             ]},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('自选'));
      await tester.pumpAndSettle();

      // case 参考：显示「案例相似 92%」（取 caseMatches 最高相似度），不出现「case 0%」异常
      expect(find.text('案例相似 92%'), findsOneWidget);
      expect(find.textContaining('case 0%'), findsNothing);
    });
  });

  group('清仓复盘三维打分（D3）', () {
    testWidgets('清仓表显示买点/执行/总分', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0, 'verdict': '盈利了结', 'psychology': ''},
          ]);
        }
        if (path == '/api/v1/trading/sold/score') {
          return _json([
            {'symbol': '600519', 'name': '贵州茅台', 'buyPointScore': 88, 'buyPointSignal': 'B1',
             'buyPointExplain': '回调 52%', 'executionScore': 90, 'executionExplain': '盈利了结，执行到位',
             'totalScore': 89, 'verdict': '盈利了结'},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('清仓'));
      await tester.pumpAndSettle();

      // 三维打分列渲染（分数是参考不是指令）
      expect(find.text('88'), findsOneWidget); // 买点分
      expect(find.text('90'), findsOneWidget); // 执行分
      expect(find.text('89'), findsOneWidget); // 总分
    });

    testWidgets('来源徽标：flow 行显示「流水」、import 行不显示（三官深审 2026-09-09）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0, 'verdict': '盈利了结',
             'psychology': '', 'provenance': 'flow'},
            {'symbol': '600584', 'name': '长电科技', 'buyDate': '2026-07-01', 'sellDate': '2026-07-20',
             'holdDays': 19, 'tradeCount': '2', 'holdPnlPct': -3.0, 'verdict': '扛单超5%',
             'psychology': '', 'provenance': 'import'},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('清仓'));
      await tester.pumpAndSettle();

      expect(find.text('流水'), findsOneWidget, reason: '只有 provenance=flow 的行显示来源徽标');
      expect(find.text('长电科技'), findsOneWidget);
      expect(find.text('贵州茅台'), findsOneWidget);
      // 徽标不渲染在 import 行上：整页仅 1 个「流水」
      expect(find.text('流水'), findsOneWidget);
      expect(find.textContaining('名称旁「流水」徽标'), findsOneWidget, reason: '来源徽标图例独立行可见');
    });
  });

  group('清仓行为模式统计（D2）', () {
    testWidgets('心理标注按关键词归类显示', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0, 'verdict': '盈利了结', 'psychology': ''},
            {'symbol': '000725', 'name': '京东方A', 'buyDate': '2026-07-01', 'sellDate': '2026-07-05',
             'holdDays': 4, 'tradeCount': '1+1', 'holdPnlPct': -8.0, 'verdict': 'R53', 'psychology': '追高后恐慌割肉'},
            {'symbol': '601066', 'name': '中信建投', 'buyDate': '2026-06-01', 'sellDate': '2026-06-20',
             'holdDays': 19, 'tradeCount': '1+1', 'holdPnlPct': -12.0, 'verdict': 'R66', 'psychology': '套牢死扛'},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('清仓'));
      await tester.pumpAndSettle();

      // 行为模式归类：追高 1 笔 + 恐慌 1 笔（同一笔命中两个词）+ 死扛 1 笔
      expect(find.textContaining('你的行为模式'), findsOneWidget);
      expect(find.textContaining('已标 2 笔'), findsOneWidget);
      expect(find.text('追高 1 笔'), findsOneWidget);
      expect(find.text('恐慌割肉 1 笔'), findsOneWidget);
      expect(find.text('套牢死扛 1 笔'), findsOneWidget);
    });
  });

  group('P1-交易7 可降级请求', () {
    testWidgets('buy-points 失败（500）：主数据正常渲染，不整页白屏', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') {
          return http.Response('boom', 500); // 可降级：失败不打断页面
        }
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      // 主数据正常（错误页不出现）
      expect(find.text('加载失败'), findsNothing);
      expect(find.text('总资产'), findsOneWidget);
      expect(find.text('立昂微'), findsOneWidget);
    });
  });

  group('P2 口径修复', () {
    testWidgets('P2-11 纪律遵守率=verdict 口径（非胜率）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/sold') {
          // 2 笔：1 盈（无违规）+ 1 亏含 R66 → 胜率 50%，纪律遵守率 50%（1-1违/2）
          return _json([
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0, 'verdict': '盈利了结', 'psychology': ''},
            {'symbol': '601066', 'name': '中信建投', 'buyDate': '2026-07-01', 'sellDate': '2026-07-20',
             'holdDays': 19, 'tradeCount': '1+1', 'holdPnlPct': -8.0, 'verdict': '扛单超 5%——按 R66 只输一根K线，止损位早该执行', 'psychology': ''},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('清仓'));
      await tester.pumpAndSettle();

      expect(find.text('胜率 50%'), findsOneWidget);
      expect(find.text('纪律遵守率 50%'), findsOneWidget);
    });

    testWidgets('B3-5 久持小亏（R53 延展）计入违规——遵守率不再虚高', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/sold') {
          // 2 笔：1 盈（无违规）+ 1 久持小亏（verdict 含 R53 延展）→ 遵守率 50%（旧实现 100% 虚高）
          return _json([
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0, 'verdict': '盈利了结', 'psychology': ''},
            {'symbol': '601066', 'name': '中信建投', 'buyDate': '2026-06-01', 'sellDate': '2026-07-01',
             'holdDays': 30, 'tradeCount': '1+1', 'holdPnlPct': -4.5,
             'verdict': '亏损持仓——按纪律复盘：止损/卖点是否按计划执行（R53）', 'psychology': ''},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('清仓'));
      await tester.pumpAndSettle();

      expect(find.text('违反 R53 1 笔'), findsOneWidget);
      expect(find.text('纪律遵守率 50%'), findsOneWidget);
    });

    testWidgets('P2-12 行为模式否定词不误配（「不贪」不算贪心）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0, 'verdict': '盈利了结', 'psychology': '这次不贪心，及时走了'},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('清仓'));
      await tester.pumpAndSettle();

      // 「不贪心」不应归入贪心模式 → 行为模式行不出现（patternCounts 空）
      expect(find.textContaining('你的行为模式'), findsNothing);
    });

    testWidgets('P2-14 账户卡大数值千分位不溢出（-39,495.12）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') {
          return _json({'assets': 110504.88, 'cash': 292.88, 'available': 292.88,
            'withdrawable': 292.88, 'marketValue': 110212.0, 'pnl': 15235.55,
            'todayPnl': 0.0, 'principal': 150000.0, 'snapshotDate': '2026-08-16'});
        }
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      // 总盈亏 = 资产 - 本金 = -39495.12 → 千分位显示
      expect(find.text('¥-39,495.12'), findsOneWidget);
      expect(find.textContaining('本金 ¥150,000'), findsOneWidget);
    });
  });

  // ── B2-2（2026-08-23）：资金区块总盈亏 principal=0 不把全部资产当总盈亏 ──

  testWidgets('B2-2+P2-交易31 本金未设（principal=0）资金区块总盈亏不给误导数值（显示设本金提示）', (tester) async {
    final client = MockClient((request) async {
      final path = request.url.path;
      if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
      if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
      if (path == '/api/v1/trading/account') {
        // principal=0（新账号未设本金），pnl=15235.55（浮盈——旧实现回落此处漏已实现盈亏）
        return _json({'assets': 110504.88, 'cash': 292.88, 'available': 292.88,
          'withdrawable': 292.88, 'marketValue': 110212.0, 'pnl': 15235.55,
          'todayPnl': 0.0, 'principal': 0.0, 'snapshotDate': '2026-08-16'});
      }
      if (path == '/api/v1/trading/watchlist') return _json([]);
      if (path == '/api/v1/trading/buy-points') return _json([]);
      if (path == '/api/v1/trading/sold') return _json([]);
      if (path == '/api/v1/trading/sold/score') return _json([]);
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);

    await tester.tap(find.text('资金'));
    await tester.pumpAndSettle();
    // P2-交易31（2026-08-29，U32）：principal=0 → 总盈亏 null → 显示「—（设置本金后显示）」，
    // 不再回落浮盈（漏已实现盈亏误导）；也不把全部资产当总盈亏
    expect(find.textContaining('总盈亏 —（设置本金后显示）'), findsOneWidget);
    expect(find.textContaining('总盈亏 ¥15,235.55'), findsNothing);
    expect(find.textContaining('总盈亏 ¥110,504.88'), findsNothing);
    // 账户卡总盈亏同样不给误导数值：statCard 显示 '—' + 「未设本金，设后显示」小字
    expect(find.text('未设本金，设后显示'), findsOneWidget);
    expect(find.text('—'), findsWidgets);
  });

  // ── B2-3（2026-08-23）：历史成交导入后端人话透出（不再吞成「检查网络」）──

  testWidgets('B2-3 历史成交导入失败透出后端人话 error', (tester) async {
    final client = MockClient((request) async {
      final path = request.url.path;
      if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
      if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
      if (path == '/api/v1/trading/account') return _json(_accountJson());
      if (path == '/api/v1/trading/watchlist') return _json([]);
      if (path == '/api/v1/trading/sold') return _json([]);
      if (path == '/api/v1/trading/buy-points') return _json([]);
      if (path == '/api/v1/trading/sold/score') return _json([]);
      if (path == '/api/v1/trading/trades') return _json([]);
      if (path == '/api/v1/trading/trades/import') {
        return http.Response(
          jsonEncode({'error': '无法识别历史成交导出——请确认表头含「成交日期/证券代码/买卖标志」且为通达信历史成交查询导出'}),
          400,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);

    await tester.tap(find.text('历史成交'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('导入历史成交'));
    await tester.pumpAndSettle();
    // 表头四关键词齐全 → 过本地 isTdxHistoryExport 校验 → 请求打后端 → 400 人话透出
    await tester.enterText(find.byType(TextField).last,
        '成交日期 证券代码 证券名称 买卖标志 成交编号');
    await tester.tap(find.text('导入'));
    await tester.pumpAndSettle();

    // 后端人话 error 透出（原实现 contains('无法识别') 恒 false → 吞成「检查网络」）
    // RFC 20260912：预检阶段就失败——人话同时出现在「这份文件的处理状态行」和「失败卡」两处
    expect(find.textContaining('无法识别历史成交导出'), findsNWidgets(2));
    expect(find.textContaining('请检查网络后重试'), findsNothing);
    // 预检失败 → 不给「确认导入」（不落盘），但给两条路
    expect(find.text('确认导入'), findsNothing);
    expect(find.text('先导快照'), findsOneWidget);
    expect(find.text('仅补流水'), findsOneWidget);
  });

  // ── RFC 20260825：逐笔批次跟踪（GET /trading/lots）──

  group('RFC 20260825 批次明细 DTO', () {
    test('LotsResponse 解析批次字段（initial/closed/回合盈亏）+ reconcile', () {
      final resp = LotsResponse.fromJson({
        'lots': [
          {
            'lotId': '600000_2026-08-03_INIT',
            'symbol': '600000', 'name': '浦发银行', 'buyDate': '2026-08-03',
            'volume': 1000, 'remaining': 500,
            'costPrice': 10.0011, 'currentPrice': 10.5, 'marketValue': 5250.0,
            'pnl': 249.45, 'pnlPct': 4.99,
            'stopLossPrice': 9.3, 'stopLossDistancePct': 11.43,
            'buyPoint': 'B1', 'role': null,
            'initial': true, 'closed': false, 'realizedPnl': 250.0,
          },
          {
            'lotId': '600000_2026-07-10_B',
            'symbol': '600000', 'name': '浦发银行', 'buyDate': '2026-07-10',
            'volume': 500, 'remaining': 0,
            'costPrice': 9.8, 'currentPrice': 10.5, 'marketValue': 0.0,
            'pnl': 0.0, 'pnlPct': 0.0,
            'stopLossPrice': 9.1, 'stopLossDistancePct': 15.38,
            'buyPoint': 'B2', 'role': '防守·主仓',
            'initial': false, 'closed': true, 'realizedPnl': -80.0,
          },
        ],
        'reconcile': [
          {'symbol': '600000', 'name': '浦发银行', 'count': 7, 'netVolume': -400, 'holdings': 4800,
           'note': '当前持仓 4800 ≠ 流水净 -400——存在窗口前基线或未导入成交（持仓快照为准，差额已按初始批次兜底）'},
        ],
      });
      expect(resp.lots.length, 2);
      final init = resp.lots[0];
      expect(init.lotId, '600000_2026-08-03_INIT');
      expect(init.initial, isTrue);
      expect(init.closed, isFalse);
      expect(init.volume, 1000);
      expect(init.remaining, 500);
      expect(init.costPrice, closeTo(10.0011, 1e-9));
      expect(init.pnl, closeTo(249.45, 1e-9));
      expect(init.stopLossPrice, closeTo(9.3, 1e-9));
      expect(init.stopLossDistancePct, closeTo(11.43, 1e-9));
      expect(init.buyPoint, 'B1');
      expect(init.role, isNull);
      final closed = resp.lots[1];
      expect(closed.closed, isTrue);
      expect(closed.realizedPnl, closeTo(-80.0, 1e-9));
      expect(closed.role, '防守·主仓');
      expect(resp.reconcile.single.note, contains('≠'));
      expect(resp.reconcile.single.holdings, 4800);
    });

    test('LotItem 缺省字段兜底（缺失/旧后端不炸）', () {
      final l = LotItem.fromJson({'symbol': '600000'});
      expect(l.symbol, '600000');
      expect(l.volume, 0);
      expect(l.remaining, 0);
      expect(l.initial, isFalse);
      expect(l.closed, isFalse);
      expect(l.stopLossPrice, isNull);
      expect(l.stopLossDistancePct, isNull);
      expect(l.realizedPnl, 0);
    });

    test('getLots 带 state/symbol query 参数', () async {
      String? state;
      String? symbol;
      final client = MockClient((request) async {
        state = request.url.queryParameters['state'];
        symbol = request.url.queryParameters['symbol'];
        return _json({'lots': [], 'reconcile': []});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      final resp = await api.getLots(state: 'all', symbol: '600000');
      expect(state, 'all');
      expect(symbol, '600000');
      expect(resp.lots, isEmpty);
      expect(resp.reconcile, isEmpty);
    });
  });

  // ── RFC 20260825：导入 syncMode + 每日操作总结 ──

  group('RFC 20260825 导入 syncMode + summary DTO', () {
    test('sync 模式解析 summary + behaviors（亏损加仓）', () {
      final r = HistoricalTradeImportResult.fromJson({
        'imported': 3, 'updated': 0, 'skipped': 0, 'nonTrades': 0, 'lines': [],
        'syncMode': 'sync',
        'summary': {
          'date': '2026-08-25', 'buyCount': 2, 'sellCount': 1,
          'buyAmount': 10600.0, 'sellAmount': 3900.0,
          'newLots': 1, 'deductedLots': 1,
          'behaviors': [
            {'type': 'loss-avg-down', 'label': '亏损加仓', 'symbol': '600000', 'name': '浦发银行',
             'date': '2026-08-25',
             'message': '买价 9.2 低于上一买批成本 10.0——越跌越买/补仓摊薄，注意别把短线补成死扛'},
          ],
        },
      });
      expect(r.syncMode, 'sync');
      final s = r.summary!;
      expect(s.date, '2026-08-25');
      expect(s.buyCount, 2);
      expect(s.sellCount, 1);
      expect(s.buyAmount, closeTo(10600.0, 1e-9));
      expect(s.sellAmount, closeTo(3900.0, 1e-9));
      expect(s.newLots, 1);
      expect(s.deductedLots, 1);
      expect(s.behaviors.single.type, 'loss-avg-down');
      expect(s.behaviors.single.label, '亏损加仓');
      expect(s.behaviors.single.message, contains('越跌越买'));
    });

    test('append 模式无 summary 不报错；旧后端无 syncMode → append 兜底', () {
      final r = HistoricalTradeImportResult.fromJson({
        'imported': 2, 'updated': 0, 'skipped': 0, 'nonTrades': 0, 'lines': [],
        'syncMode': 'append',
      });
      expect(r.syncMode, 'append');
      expect(r.summary, isNull);
      // 旧后端完全不返回 syncMode/summary → 默认 append，不炸
      final old = HistoricalTradeImportResult.fromJson({'imported': 1});
      expect(old.syncMode, 'append');
      expect(old.summary, isNull);
    });
  });

  // ── RFC 20260825：持仓批次弹窗 ──

  group('持仓批次弹窗（RFC 20260825）', () {
    testWidgets('「批次」→ 弹窗展示明细：初始底仓/持有中/已清仓-回合盈亏 + 红涨绿亏 + 对账警告', (tester) async {
      Map<String, String>? lotsQuery;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/lots') {
          lotsQuery = request.url.queryParameters;
          return _json({
            'lots': [
              {
                'lotId': '600123_2026-08-01_INIT', 'symbol': '600123', 'name': '立昂微', 'buyDate': '2026-08-01',
                'volume': 100, 'remaining': 100, 'costPrice': 25.0, 'currentPrice': 26.1,
                'marketValue': 2610.0, 'pnl': 110.0, 'pnlPct': 4.4,
                'stopLossPrice': 22.8, 'stopLossDistancePct': 12.63, 'buyPoint': null, 'role': null,
                'initial': true, 'closed': false, 'realizedPnl': 0.0,
              },
              {
                'lotId': '600123_2026-08-05_B', 'symbol': '600123', 'name': '立昂微', 'buyDate': '2026-08-05',
                'volume': 200, 'remaining': 100, 'costPrice': 25.3, 'currentPrice': 26.1,
                'marketValue': 2610.0, 'pnl': 80.0, 'pnlPct': 3.16,
                'stopLossPrice': 22.8, 'stopLossDistancePct': 12.63, 'buyPoint': 'B3', 'role': '防守·主仓',
                'initial': false, 'closed': false, 'realizedPnl': 0.0,
              },
              {
                'lotId': '600123_2026-07-20_B', 'symbol': '600123', 'name': '立昂微', 'buyDate': '2026-07-20',
                'volume': 300, 'remaining': 0, 'costPrice': 24.0, 'currentPrice': 26.1,
                'marketValue': 0.0, 'pnl': 0.0, 'pnlPct': 0.0,
                'stopLossPrice': 22.3, 'stopLossDistancePct': 17.0, 'buyPoint': 'B1', 'role': null,
                'initial': false, 'closed': true, 'realizedPnl': 250.0,
              },
              {
                'lotId': '600123_2026-07-01_B', 'symbol': '600123', 'name': '立昂微', 'buyDate': '2026-07-01',
                'volume': 500, 'remaining': 0, 'costPrice': 10.0, 'currentPrice': 9.5,
                'marketValue': 0.0, 'pnl': 0.0, 'pnlPct': 0.0,
                'stopLossPrice': 9.3, 'stopLossDistancePct': -2.1, 'buyPoint': null, 'role': null,
                'initial': false, 'closed': true, 'realizedPnl': -80.0,
              },
              {
                // initial && closed 并存（初始底仓被卖完）：状态列必须显示「已清仓」（closed 优先），盈亏列显示回合
                'lotId': '600123_2026-06-15_INIT', 'symbol': '600123', 'name': '立昂微', 'buyDate': '2026-06-15',
                'volume': 100, 'remaining': 0, 'costPrice': 22.0, 'currentPrice': 26.1,
                'marketValue': 0.0, 'pnl': 0.0, 'pnlPct': 0.0,
                'stopLossPrice': 20.5, 'stopLossDistancePct': 27.3, 'buyPoint': null, 'role': null,
                'initial': true, 'closed': true, 'realizedPnl': 90.0,
              },
            ],
            'reconcile': [
              {'symbol': '600123', 'name': '立昂微', 'count': 4, 'netVolume': 100, 'holdings': 200,
               'note': '当前持仓 200 ≠ 流水净 100——存在窗口前基线或未导入成交（持仓快照为准，差额已按初始批次兜底）'},
              {'symbol': '600123', 'name': '立昂微', 'count': 1, 'netVolume': 0, 'holdings': 200, 'note': '对账一致'},
              // 其他股票的对账行：弹窗按当前 symbol 过滤，不得串进来
              {'symbol': '600519', 'name': '贵州茅台', 'count': 2, 'netVolume': 50, 'holdings': 500,
               'note': '当前持仓 500 ≠ 流水净 50——存在窗口前基线或未导入成交（持仓快照为准，差额已按初始批次兜底）'},
            ],
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('批次'));
      await tester.pumpAndSettle();

      // 请求参数：state=all + symbol（一次拿全，含回合/初始底仓）
      expect(lotsQuery!['state'], 'all');
      expect(lotsQuery!['symbol'], '600123');
      // 状态标注：初始底仓 / 持有中 / 已清仓（3 个回合，含 initial&&closed 的初始底仓被卖完 → 显示已清仓）
      expect(find.text('初始底仓'), findsOneWidget);
      expect(find.text('持有中'), findsOneWidget);
      expect(find.text('已清仓'), findsNWidgets(3));
      // 回合盈亏（已清仓批次，含初始底仓回合 90.00——状态与盈亏口径一致）
      expect(find.text('回合 250.00'), findsOneWidget);
      expect(find.text('回合 -80.00'), findsOneWidget);
      expect(find.text('回合 90.00'), findsOneWidget);
      // 剩余/买入量
      expect(find.text('100 / 100'), findsOneWidget);
      expect(find.text('100 / 200'), findsOneWidget);
      expect(find.text('0 / 300'), findsOneWidget);
      expect(find.text('0 / 500'), findsOneWidget);
      expect(find.text('0 / 100'), findsOneWidget);
      // 买点/角色（B3 仅弹窗内；B1 在已清仓批次）
      expect(find.text('B3'), findsOneWidget);
      expect(find.text('B1'), findsOneWidget);
      expect(find.text('防守·主仓'), findsOneWidget);
      // 距止损%（正=安全，负=已破）
      expect(find.text('12.63%'), findsNWidgets(2));
      expect(find.text('-2.10%'), findsOneWidget);
      // 红涨绿亏：盈利=红、亏损=绿
      final red = tester.widget<Text>(find.text('110.00'));
      expect(red.style?.color, AppColors.darkRed);
      final green = tester.widget<Text>(find.text('回合 -80.00'));
      expect(green.style?.color, AppColors.darkGreen);
      // 盈亏%：开放批次浮动 pnlPct；已清仓回合收益率（realizedPnl / 成本×买入量，前端算）。
      // 限定弹窗内：持仓表本身也有盈亏% 列（pnlPercent 3.16），避免与弹窗批次盈亏% 撞文本
      final inLotsDialog = find.byType(Dialog);
      expect(find.descendant(of: inLotsDialog, matching: find.text('4.40%')), findsOneWidget);
      expect(find.descendant(of: inLotsDialog, matching: find.text('3.16%')), findsOneWidget);
      expect(find.descendant(of: inLotsDialog, matching: find.text('回合 3.47%')), findsOneWidget); // 250 / (24.0×300)
      expect(find.descendant(of: inLotsDialog, matching: find.text('回合 -1.60%')), findsOneWidget); // -80 / (10.0×500)
      expect(find.descendant(of: inLotsDialog, matching: find.text('回合 4.09%')), findsOneWidget); // 90 / (22.0×100)
      // 百分比颜色同盈亏（红涨绿亏）
      final pctRed = tester.widget<Text>(find.descendant(of: inLotsDialog, matching: find.text('4.40%')));
      expect(pctRed.style?.color, AppColors.darkRed);
      final pctGreen = tester.widget<Text>(find.descendant(of: inLotsDialog, matching: find.text('回合 -1.60%')));
      expect(pctGreen.style?.color, AppColors.darkGreen);
      // 对账不一致 → 橙色警告行（以持仓快照为准）；其他股票的对账行被过滤（不串股）
      expect(find.byIcon(Icons.warning_amber_rounded), findsOneWidget);
      expect(find.textContaining('当前持仓 200 ≠ 流水净 100'), findsOneWidget);
      expect(find.textContaining('贵州茅台'), findsNothing);
      expect(find.textContaining('当前持仓 500 ≠ 流水净 50'), findsNothing);
    });

    testWidgets('批次接口失败 → 弹窗内人话错误，不打断页面', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/lots') return http.Response('boom', 500);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('批次'));
      await tester.pumpAndSettle();
      // 弹窗仍在（不整页白屏），失败透出
      expect(find.text('批次明细 · 600123 立昂微'), findsOneWidget);
      expect(find.textContaining('批次明细加载失败'), findsOneWidget);
    });
  });

  // ── RFC 20260825：历史成交导入 syncMode + 每日操作总结 ──

  group('历史成交导入 syncMode + 每日操作总结（RFC 20260825）', () {
    const tdxText = '''
成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
20260825        14:52:56        600000          浦发银行        买入            200.00         9.20000000         1840.00         151117          69351117                1840.00          A511358384
''';

    testWidgets('sync 模式 → 当日操作总结卡（标题带成交日期）+ 行为标注（Dialog 与 Tab inline 都展示）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/trades/import') {
          return _json({
            'imported': 3, 'updated': 0, 'skipped': 0, 'nonTrades': 0, 'lines': [],
            'syncMode': 'sync',
            'summary': {
              'date': '2026-08-25', 'buyCount': 2, 'sellCount': 1,
              'buyAmount': 10600.0, 'sellAmount': 3900.0,
              'newLots': 1, 'deductedLots': 1,
              'behaviors': [
                {'type': 'loss-avg-down', 'label': '亏损加仓', 'symbol': '600000', 'name': '浦发银行',
                 'date': '2026-08-25',
                 'message': '买价 9.2 低于上一买批成本 10.0——越跌越买/补仓摊薄，注意别把短线补成死扛'},
                {'type': 'chase-high', 'label': '追高', 'symbol': '600519', 'name': '贵州茅台',
                 'date': '2026-08-25', 'message': '买价 1500 高于上一买批成本 1400——追涨买入，注意回撤风险'},
              ],
            },
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();
      // RFC 20260912：两段式——先预检（dryRun），点「确认导入」才落盘
      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();

      // Dialog 内：总结卡（标题带成交日期 8/25，sync 窗口跨多日未必是今天）+ 行为标注
      //（Tab inline 同步展示 → Dialog + inline 共 2 份）
      expect(find.text('8/25 操作：买 2 笔 ¥10,600.00 · 卖 1 笔 ¥3,900.00 · 新增批次 1 · 扣减批次 1'),
          findsNWidgets(2));
      expect(find.textContaining('亏损加仓'), findsNWidgets(2));
      expect(find.textContaining('越跌越买'), findsNWidgets(2));
      expect(find.textContaining('追高'), findsNWidgets(2));

      // 关闭 Dialog → 历史成交 Tab inline 保留 1 份
      await tester.tap(find.text('关闭'));
      await tester.pumpAndSettle();
      expect(find.text('8/25 操作：买 2 笔 ¥10,600.00 · 卖 1 笔 ¥3,900.00 · 新增批次 1 · 扣减批次 1'),
          findsOneWidget);
      expect(find.textContaining('亏损加仓'), findsOneWidget);
    });

    testWidgets('append 模式 → 补录提示，无总结卡不报错', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/trades/import') {
          return _json({
            'imported': 2, 'updated': 0, 'skipped': 0, 'nonTrades': 0, 'lines': [],
            'syncMode': 'append',
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();
      // RFC 20260912：两段式——预检后才落盘
      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();

      // append：Dialog + Tab inline 都显示补录提示（共 2 份），不出现总结卡
      expect(find.text('已按历史补录处理（只补流水，持仓未动）'), findsNWidgets(2));
      expect(find.textContaining('今日操作'), findsNothing);

      // 关闭 Dialog → inline 保留 1 份
      await tester.tap(find.text('关闭'));
      await tester.pumpAndSettle();
      expect(find.text('已按历史补录处理（只补流水，持仓未动）'), findsOneWidget);
    });
  });



    testWidgets('一键同步持仓：确认后调用 /trading/sync 并提示移除残留（2026-08-25）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/lots') return _json({'lots': [], 'reconcile': []});
        if (path == '/api/v1/trading/sync' && request.method == 'POST') {
          return _json({'positionCount': 3, 'removed': ['603988'], 'keptInitial': []});
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);
      await tester.tap(find.text('历史成交')); // 一键同步按钮在历史成交 Tab 工具行
      await tester.pumpAndSettle();

      await tester.tap(find.text('一键同步'));
      await tester.pumpAndSettle();
      expect(find.text('以流水为准重建持仓：已清仓的股票会自动从持仓移除，流水解释不了的真实底仓会保留。确认同步？'), findsOneWidget);
      await tester.tap(find.text('确认同步'));
      await tester.pumpAndSettle();

      expect(find.textContaining('已移除已清仓残留 1 只（603988）'), findsOneWidget);
    });

  testWidgets('规则 Tab：加载并展示我的交易规则参数', (tester) async {
    final getRequests = <String>[];
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/rules' && request.method == 'GET') {
        getRequests.add(request.url.path);
        return _json({
          'exists': true,
          'params': {
            'positionLimitPercent': '25',
            'defaultStopLossRatio': '0.93',
            'buyKdjLow': '13.0',
            'constraintRuleMin': '66',
          },
        });
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    // 规则数据在 initState 即加载（_loadRules），不依赖切 Tab 可见
    await tester.pumpAndSettle();
    expect(getRequests, contains('/api/v1/trading/rules'),
        reason: 'initState 应请求规则参数');
  });

  testWidgets('规则 Tab：编辑保存调用 PUT /trading/rules', (tester) async {
    var putCalled = false;
    var putBody = '';
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/rules' && request.method == 'GET') {
        return _json({
          'exists': true,
          'params': {'positionLimitPercent': '25', 'buyKdjLow': '13.0'},
        });
      }
      if (request.url.path == '/api/v1/trading/rules' && request.method == 'PUT') {
        putCalled = true;
        putBody = request.body;
        return _json({'updated': true});
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    // 规则数据已加载（initState），通过 ApiService 层直接验证 PUT 契约（widget 层 Tab 6 需滚动）
    await api.updateTradingRules({'positionLimitPercent': 30});
    await tester.pumpAndSettle();

    expect(putCalled, isTrue, reason: '更新规则应调用 PUT /trading/rules');
    expect(putBody, contains('positionLimitPercent'), reason: 'PUT body 应含参数');
  });

  // ── 第四阶段（2026-08-30）：完美买点案例库（环 1-2）──

  Map<String, dynamic> caseJson() => {
        'id': '2026-08-03_000725',
        'symbol': '000725',
        'name': '京东方A',
        'buyDate': '2026-08-03',
        'buyType': 'B1',
        'description': '回踩 60 日线 + 地量',
        'features': {
          'drawdownFromHighPct': 52.3,
          'volumeShrinkRatio': 0.62,
          'kdjJ': 8.4,
          'distToMa60Pct': 1.8,
          'yellowLineState': 'near',
          'sidewaysDays': 5,
          'breakoutFromHigh': false,
        },
        'verify': {
          '+5dReturnPct': 18.2,
          '+10dReturnPct': 24.5,
          'maxDrawdownAfterBuyPct': -2.1,
          'stopLossHit': false,
        },
      };

  testWidgets('案例 Tab：initState 加载案例列表（GET /trading/cases）', (tester) async {
    final getRequests = <String>[];
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        getRequests.add(request.url.path);
        return _json([caseJson()]);
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();
    expect(getRequests, contains('/api/v1/trading/cases'),
        reason: 'initState 应请求案例列表');
  });

  testWidgets('案例 Tab：标注调用 POST /trading/cases（契约：symbol/buyDate/buyType）', (tester) async {
    var postCalled = false;
    var postBody = '';
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'POST') {
        postCalled = true;
        postBody = request.body;
        return _json(caseJson());
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    // 通过 ApiService 层验证标注契约（widget 层 Tab 7 需滚动）
    await api.annotateCase(symbol: '000725', buyDate: '2026-08-03', buyType: 'B1');
    await tester.pumpAndSettle();

    expect(postCalled, isTrue, reason: '标注应调用 POST /trading/cases');
    expect(postBody, contains('000725'), reason: 'POST body 应含 symbol');
    expect(postBody, contains('2026-08-03'), reason: 'POST body 应含 buyDate');
    expect(postBody, contains('B1'), reason: 'POST body 应含 buyType');
  });

  testWidgets('案例 Tab：详情带 kline=true 参数（GET /trading/cases/{id}）', (tester) async {
    String? detailUrl;
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/2026-08-03_000725') {
        detailUrl = request.url.toString();
        return _json({
          'caseRecord': caseJson(),
          'kline': <Map<String, dynamic>>[],
        });
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await api.getCaseDetail('2026-08-03_000725', kline: true);
    expect(detailUrl, contains('kline=true'), reason: '详情应带 kline=true 供画图');
  });

  testWidgets('案例 Tab：标的搜索 GET /trading/search（拼音首字母/名称/代码，2026-08-30）', (tester) async {
    String? searchQuery;
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/search') {
        searchQuery = request.url.queryParameters['q'];
        return _json([
          {'symbol': '000831', 'name': '中国稀土'},
          {'symbol': '600831', 'name': '广电网络'},
        ]);
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    final result = await api.searchSymbols('zgxt');
    await tester.pumpAndSettle();

    expect(searchQuery, 'zgxt', reason: '搜索参数 q 应透传');
    expect(result.length, 2);
    expect(result.first['symbol'], '000831');
    expect(result.first['name'], '中国稀土');
  });

  testWidgets('案例 Tab：生成 AI 理解调用 POST /cases/{id}/insight（环 3）', (tester) async {    var insightCalled = false;
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/2026-08-03_000725/insight' &&
          request.method == 'POST') {
        insightCalled = true;
        final updated = caseJson();
        updated['aiInsight'] = {
          'summary': '缩量回踩黄线获支撑，教科书式 B1',
          'keyFeatures': ['缩量回踩', '黄线支撑'],
          'confidence': 0.9,
          'reviewed': false,
        };
        return _json(updated);
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    final resp = await api.generateCaseInsight('2026-08-03_000725');
    await tester.pumpAndSettle();

    expect(insightCalled, isTrue, reason: '生成理解应调用 POST /cases/{id}/insight');
    final insight = resp['aiInsight'] as Map<String, dynamic>;
    expect(insight['summary'], contains('教科书式 B1'), reason: 'aiInsight 应含结构化理解');
    expect((insight['keyFeatures'] as List).length, 2);
  });

  testWidgets('案例 Tab：匹配买点调用 POST /cases/match（环 4，核心价值）', (tester) async {
    var matchCalled = false;
    String? matchBody;
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/match' && request.method == 'POST') {
        matchCalled = true;
        matchBody = request.body;
        return _json({
          'symbol': '000725',
          'matches': [
            {
              'caseId': '2026-08-03_000725',
              'symbol': '000725',
              'name': '京东方A',
              'buyDate': '2026-08-03',
              'buyType': 'B1',
              'similarityPercent': 92.5,
              'plus5dReturnPct': 18.2,
              'aiInsightSummary': '缩量回踩黄线获支撑',
            },
          ],
        });
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    final resp = await api.matchCases('000725');
    await tester.pumpAndSettle();

    expect(matchCalled, isTrue, reason: '匹配应调用 POST /cases/match');
    expect(matchBody, contains('000725'), reason: '请求体应含 symbol');
    final matches = resp['matches'] as List<dynamic>;
    expect(matches.length, 1);
    final m = matches.first as Map<String, dynamic>;
    expect(m['similarityPercent'], 92.5);
    expect(m['caseId'], '2026-08-03_000725');
  });

  testWidgets('案例 Tab：匹配响应含双轨判定（B1/B2 命中 + 类型 + 失败警示，2026-08-31）', (tester) async {
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/match' && request.method == 'POST') {
        return _json({
          'symbol': '000831',
          'matches': <Map<String, dynamic>>[],
          'type': 'B1',
          'b1': {'hits': 5, 'total': 6, 'similarity': 82.5},
          'b2': {'hits': 1, 'total': 6, 'similarity': 45.0},
          'failedSimilarity': 78.5,
          'consensus': null,
        });
      }
      return http.Response('not found', 404);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    final resp = await api.matchCases('000831');
    await tester.pumpAndSettle();

    expect(resp['type'], 'B1', reason: '双轨判定类型');
    expect((resp['b1'] as Map<String, dynamic>)['hits'], 5);
    expect((resp['b2'] as Map<String, dynamic>)['hits'], 1);
    expect(resp['failedSimilarity'], 78.5, reason: '失败画像相似警示');
  });

  // ── RFC 20260912：账实一致性批（锚定 fail-closed + 预检确认 + rejected 可见性 + 对账闸门）──

  group('RFC 20260912 账实一致性 DTO（rejected / anchor / plan / integrity）', () {
    test('导入结果解析 rejected + anchor + dryRun + plan（字段齐全）', () {
      final r = HistoricalTradeImportResult.fromJson({
        'imported': 3, 'updated': 0, 'skipped': 1, 'nonTrades': 0, 'lines': [],
        'syncMode': 'sync', 'dryRun': false,
        'rejected': [
          {
            'symbol': '600519', 'name': '贵州茅台', 'direction': 'SELL', 'volume': 100,
            'price': 1500.0, 'entryDate': '2026-09-10',
            'reason': '未持有 600519（快照基线/流水缺该标的的买入）——已落流水，未动持仓与现金',
          },
        ],
        'anchor': {
          'positionsReplace': '2026-09-08', 'cashImport': '2026-09-09',
          'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-09',
        },
      });
      expect(r.dryRun, isFalse);
      expect(r.rejected.single.symbol, '600519');
      expect(r.rejected.single.direction, 'SELL');
      expect(r.rejected.single.directionLabel, '卖出', reason: '方向枚举名转人话');
      expect(r.rejected.single.volume, 100);
      expect(r.rejected.single.price, closeTo(1500.0, 1e-9));
      expect(r.rejected.single.entryDate, '2026-09-10');
      expect(r.rejected.single.reason, contains('未持有'));
      expect(r.rejected.single.display, '卖出 贵州茅台（600519）100 股 @ 1500.00 · 未持有 600519'
          '（快照基线/流水缺该标的的买入）——已落流水，未动持仓与现金');
      expect(r.anchor!.known, isTrue);
      expect(r.anchor!.holdingsKnown, isTrue);
      expect(r.anchor!.anchorDate, '2026-09-09');
      expect(r.anchor!.positionsReplace, '2026-09-08');
      expect(r.plan, isNull, reason: '正式导入无 plan');
    });

    test('dryRun 响应解析 plan（JSON 键 new → newCount）+ anchorKnown=false', () {
      final r = HistoricalTradeImportResult.fromJson({
        'imported': 2, 'updated': 1, 'skipped': 0, 'nonTrades': 0, 'lines': [],
        'syncMode': 'sync', 'dryRun': true,
        'rejected': [
          {'symbol': '600123', 'name': '立昂微', 'direction': 'SELL', 'volume': 200, 'price': null,
           'entryDate': '2026-09-11', 'reason': '未持有 600123'},
        ],
        'anchor': {'positionsReplace': null, 'cashImport': null, 'known': false, 'holdingsKnown': false},
        'plan': {
          'new': 2, 'merged': 1, 'skipped': 0, 'nonTrades': 0,
          'wouldReject': 1, 'anchorKnown': false, 'syncMode': 'sync',
        },
      });
      expect(r.dryRun, isTrue);
      final p = r.plan!;
      expect(p.newCount, 2);
      expect(p.merged, 1);
      expect(p.skipped, 0);
      expect(p.nonTrades, 0);
      expect(p.wouldReject, 1);
      expect(p.anchorKnown, isFalse);
      expect(p.syncMode, 'sync');
      expect(r.anchor!.known, isFalse);
      expect(r.anchor!.anchorDate, isNull);
      expect(r.rejected.single.price, isNull, reason: '价格可空保持可空');
    });

    test('字段缺失 / 非 map 兜底（旧后端不炸）', () {
      // 旧后端：无 rejected/anchor/dryRun/plan
      final old = HistoricalTradeImportResult.fromJson({'imported': 1});
      expect(old.rejected, isEmpty);
      expect(old.anchor, isNull);
      expect(old.dryRun, isFalse);
      expect(old.plan, isNull);
      // 非 map 兜底
      final bad = HistoricalTradeImportResult.fromJson('boom');
      expect(bad.imported, 0);
      expect(bad.rejected, isEmpty);
      // 行级 DTO 非 map / 缺字段兜底
      final rl = RejectedLineDto.fromJson(null);
      expect(rl.symbol, '');
      expect(rl.volume, 0);
      expect(rl.price, isNull);
      expect(rl.reason, '');
      expect(RejectedLineDto.fromJson('x').directionLabel, '');
      final a = AnchorStatusDto.fromJson(42);
      expect(a.known, isFalse);
      expect(a.holdingsKnown, isFalse);
      expect(a.positionsReplace, isNull);
      final p = ImportPlanDto.fromJson('x');
      expect(p.newCount, 0);
      expect(p.anchorKnown, isFalse);
      expect(p.syncMode, 'append', reason: '缺省回落 append（只补流水的保守口径）');
      final ir = IntegrityReportDto.fromJson(null);
      expect(ir.hasIssue, isFalse);
      expect(ir.drift, isEmpty);
      expect(ir.gaps, isEmpty);
      final dl = DriftLineDto.fromJson([]);
      expect(dl.ledgerDelta, 0);
      expect(dl.snapshotQty, isNull, reason: '基线可空保持可空');
      expect(dl.holdings, isNull);
    });

    test('integrity 报告解析 drift + gaps + note', () {
      final r = IntegrityReportDto.fromJson({
        'anchor': {'positionsReplace': '2026-09-08', 'cashImport': null,
                   'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-08'},
        'holdingsKnown': true,
        'drift': [
          {'symbol': '600123', 'name': '立昂微', 'snapshotQty': 200, 'ledgerDelta': -100,
           'derived': 100, 'holdings': 200, 'diff': 100,
           'note': '派生持仓 100 ≠ 落地持仓 200——快照之后有未导入的成交或重复流水'},
        ],
        'gaps': [
          {'symbol': '600519', 'name': '贵州茅台', 'direction': 'SELL', 'volume': 100,
           'price': 1500.0, 'entryDate': '2026-09-10', 'reason': '未持有 600519'},
        ],
        'note': '账实不符（1 只标的持仓不一致 / 1 笔回放缺口，锚定日 2026-09-08）',
      });
      expect(r.hasIssue, isTrue);
      expect(r.holdingsKnown, isTrue);
      expect(r.anchor!.anchorDate, '2026-09-08');
      expect(r.drift.single.symbol, '600123');
      expect(r.drift.single.snapshotQty, 200);
      expect(r.drift.single.ledgerDelta, -100);
      expect(r.drift.single.derived, 100);
      expect(r.drift.single.holdings, 200);
      expect(r.drift.single.diff, 100);
      expect(r.drift.single.note, contains('≠'));
      expect(r.gaps.single.directionLabel, '卖出');
      expect(r.note, contains('账实不符'));
      // 无差异 → hasIssue false（页面不显示任何横幅）
      final clean = IntegrityReportDto.fromJson({'holdingsKnown': true, 'drift': [], 'gaps': [],
        'note': '账实一致：派生持仓与落地持仓逐标的相符（锚定日 2026-09-08）'});
      expect(clean.hasIssue, isFalse);
    });

    test('文件名解析快照日（yyyymmdd / yyyy-MM-dd / 假日期判掉）', () {
      expect(parseSnapshotDateFromFilename('持仓股20260912.txt'), '2026-09-12');
      expect(parseSnapshotDateFromFilename('资金股份查询-2026-09-12.csv'), '2026-09-12');
      expect(parseSnapshotDateFromFilename('持仓股_2026_09_08.TXT'), '2026-09-08');
      expect(parseSnapshotDateFromFilename('历史成交查询.txt'), isNull, reason: '无日期 → 不传，退回导入日');
      expect(parseSnapshotDateFromFilename(''), isNull);
      expect(parseSnapshotDateFromFilename('导出20261345.txt'), isNull, reason: '假日期不当作锚定日');
      expect(parseSnapshotDateFromFilename('导出20260230.txt'), isNull, reason: '2/30 不存在');
    });
  });

  group('RFC 20260912 历史成交导入：预检 → 确认两段式', () {
    const tdxText = '''
成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
20260912        14:52:56        600000          浦发银行        买入            200.00         9.20000000         1840.00         151117          69351117                1840.00          A511358384
''';

    testWidgets('先发 dryRun 预检；未确认前不发正式导入；确认后才落盘', (tester) async {
      final posts = <String>[];
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/trades/import') {
          final dry = request.url.queryParameters['dryRun'] == 'true';
          posts.add('${request.url.queryParameters['mode']}|$dry');
          return _json({
            'imported': 2, 'updated': 0, 'skipped': 1, 'nonTrades': 0, 'lines': [],
            'syncMode': dry ? 'sync' : 'append',
            'dryRun': dry,
            'anchor': {'positionsReplace': '2026-09-10', 'cashImport': null,
                       'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-10'},
            if (dry)
              'plan': {'new': 2, 'merged': 0, 'skipped': 1, 'nonTrades': 0,
                       'wouldReject': 0, 'anchorKnown': true, 'syncMode': 'sync'},
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      // 预检阶段：只发了一次 dryRun=true；计划卡可见（未落盘）
      expect(posts, ['auto|true'], reason: '点「导入」只做预检，不落盘');
      expect(find.text('先看一眼会记什么（还没落盘）'), findsOneWidget);
      expect(find.text('新增 2 笔 · 合并 0 笔 · 跳过 1 笔'), findsOneWidget);
      expect(find.text('会按成交更新持仓与现金（锚定日已对上）'), findsOneWidget);
      expect(find.text('确认导入'), findsOneWidget);

      // 点确认 → 才发正式导入（dryRun=false）
      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();
      expect(posts, ['auto|true', 'auto|false'], reason: '确认后才真正落盘');
      // Dialog + Tab inline 各一份
      expect(find.textContaining('导入完成：新增 2 笔'), findsNWidgets(2));
      expect(find.text('先看一眼会记什么（还没落盘）'), findsNothing);
    });

    testWidgets('rejected 明细逐条展示（Dialog + Tab inline 各一份）', (tester) async {
      const rejectedReason = '未持有 600519（快照基线/流水缺该标的的买入）——已落流水，未动持仓与现金';
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/trades/import') {
          final dry = request.url.queryParameters['dryRun'] == 'true';
          return _json({
            'imported': 0, 'updated': 0, 'skipped': 1, 'nonTrades': 0, 'lines': [],
            'syncMode': 'append',
            'dryRun': dry,
            'anchor': {'positionsReplace': '2026-09-10', 'cashImport': null,
                       'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-10'},
            'rejected': [
              {'symbol': '600519', 'name': '贵州茅台', 'direction': 'SELL', 'volume': 100,
               'price': 1500.0, 'entryDate': '2026-09-10', 'reason': rejectedReason},
            ],
            if (dry)
              'plan': {'new': 0, 'merged': 0, 'skipped': 1, 'nonTrades': 0,
                       'wouldReject': 1, 'anchorKnown': true, 'syncMode': 'append'},
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      // 预检阶段就可见（用户确认前就知道有 N 笔要落成「未并入持仓」）
      expect(find.text('有 1 笔成交没能并入持仓（已记账，未动持仓/现金）'), findsOneWidget);
      expect(find.textContaining('卖出 贵州茅台（600519）100 股 @ 1500.00'), findsOneWidget);

      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();

      // 落盘后：Dialog + Tab inline 各一份（findsNWidgets(2)）
      expect(find.text('有 1 笔成交没能并入持仓（已记账，未动持仓/现金）'), findsNWidgets(2));
      expect(find.textContaining('卖出 贵州茅台（600519）100 股 @ 1500.00 · $rejectedReason'),
          findsNWidgets(2));
      // 关闭 Dialog → inline 保留一份
      await tester.tap(find.text('关闭'));
      await tester.pumpAndSettle();
      expect(find.text('有 1 笔成交没能并入持仓（已记账，未动持仓/现金）'), findsOneWidget);
      expect(find.textContaining('未持有 600519'), findsOneWidget);
    });

    testWidgets('锚定缺失 400：人话原样透出 + 「仅补流水」以 append 重新预检并落盘', (tester) async {
      const anchorError = '券商快照锚定缺失（trading/snapshot-anchor.json 缺失或损坏）：本次有 2 笔近日成交'
          '需要回放持仓/现金，但没有锚定日就无法判断哪些成交已包含在券商口径内——照旧回放会把它们重复'
          '计算一遍。请先导入「持仓股」或「资金股份查询」快照建立锚定；若只想补逐笔流水（不动持仓/现金），'
          '用「仅补流水」模式重试';
      final calls = <String>[];
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/trades/import') {
          final mode = request.url.queryParameters['mode'] ?? '';
          final dry = request.url.queryParameters['dryRun'] == 'true';
          calls.add('$mode|$dry');
          // auto 模式 = 锚定缺失 fail-closed 400；append 模式 = 只补流水成功
          if (mode == 'auto') {
            return http.Response(jsonEncode({'error': anchorError}), 400,
                headers: {'content-type': 'application/json; charset=utf-8'});
          }
          return _json({
            'imported': 2, 'updated': 0, 'skipped': 0, 'nonTrades': 0, 'lines': [],
            'syncMode': 'append', 'dryRun': dry, 'rejected': [],
            'anchor': {'positionsReplace': null, 'cashImport': null,
                       'known': false, 'holdingsKnown': false, 'anchorDate': null},
            if (dry)
              'plan': {'new': 2, 'merged': 0, 'skipped': 0, 'nonTrades': 0,
                       'wouldReject': 0, 'anchorKnown': false, 'syncMode': 'append'},
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      // 400 人话原样透出（不吞成「检查网络」）+ 两条路
      // （同一句同时出现在「这份文件的处理状态行」和「失败卡」→ 2 处）
      expect(calls, ['auto|true']);
      expect(find.textContaining('券商快照锚定缺失'), findsNWidgets(2));
      expect(find.textContaining('请先导入「持仓股」或「资金股份查询」快照建立锚定'), findsWidgets);
      expect(find.text('先导快照'), findsOneWidget);
      expect(find.text('仅补流水'), findsOneWidget);
      expect(find.text('确认导入'), findsNothing, reason: '预检失败不给确认（不落盘）');

      // 「仅补流水」→ 以 mode=append 重新预检
      await tester.tap(find.text('仅补流水'));
      await tester.pumpAndSettle();
      expect(calls, ['auto|true', 'append|true'], reason: '仅补流水以 append 重新预检');
      expect(find.text('先看一眼会记什么（还没落盘） · 仅补流水'), findsOneWidget);
      expect(find.text('只补逐笔流水，持仓与现金不动'), findsOneWidget);
      expect(find.textContaining('券商快照锚定缺失'), findsNothing, reason: 'append 路径不再报锚定缺失');
      // 锚定缺失提示条仍在（让用户知道可以先补快照）
      expect(find.textContaining('还没拿到券商快照的锚定日'), findsOneWidget);

      // 确认 → append 模式落盘
      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();
      expect(calls, ['auto|true', 'append|true', 'append|false']);
      expect(find.textContaining('导入完成：新增 2 笔'), findsNWidgets(2));
    });

    testWidgets('「先导快照」→ 关掉导入弹窗并打开持仓导入弹窗', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/trades/import') {
          return http.Response(jsonEncode({'error': '券商快照锚定缺失（x）：请先导入「持仓股」快照建立锚定'}), 400,
              headers: {'content-type': 'application/json; charset=utf-8'});
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('先导快照'));
      await tester.pumpAndSettle();

      // 历史成交导入弹窗已关，持仓导入弹窗打开（用户在这里补一份「持仓股」建立锚定）
      expect(find.textContaining('选好后我先算一遍给你看'), findsNothing, reason: '历史成交导入弹窗已关');
      expect(find.textContaining('粘贴通达信持仓导出'), findsOneWidget);
      expect(find.textContaining('无法识别'), findsNothing);
    });
    testWidgets('anchor.holdingsKnown=false → 「快照基线未记录，对账无法判定」提示', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/trades/import') {
          final dry = request.url.queryParameters['dryRun'] == 'true';
          return _json({
            'imported': 1, 'updated': 0, 'skipped': 0, 'nonTrades': 0, 'lines': [],
            'syncMode': 'append', 'dryRun': dry, 'rejected': [],
            // 有锚定日（known=true）但快照持仓基线没落下来（holdingsKnown=false）
            'anchor': {'positionsReplace': '2026-09-10', 'cashImport': null,
                       'known': true, 'holdingsKnown': false, 'anchorDate': '2026-09-10'},
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();

      expect(find.textContaining('快照基线未记录，账实对账暂时无法判定'), findsNWidgets(2),
          reason: 'Dialog + Tab inline 各一份');
      expect(find.textContaining('还没拿到券商快照的锚定日'), findsNothing, reason: '锚定日有，不报锚定缺失');
    });

    testWidgets('确认落盘后重算对账闸门（GET /integrity 二次请求 → 横幅出现）', (tester) async {
      var integrityCalls = 0;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/trades') return _json([]);
        if (path == '/api/v1/trading/integrity') {
          integrityCalls++;
          final dirty = integrityCalls > 1; // 导入后才出现缺口
          return _json({
            'anchor': {'positionsReplace': '2026-09-10', 'cashImport': null,
                       'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-10'},
            'holdingsKnown': true,
            'drift': dirty
                ? [
                    {'symbol': '600123', 'name': '立昂微', 'snapshotQty': 200, 'ledgerDelta': -100,
                     'derived': 100, 'holdings': 200, 'diff': 100, 'note': '派生 100 ≠ 落地 200'},
                  ]
                : <Map<String, dynamic>>[],
            'gaps': <Map<String, dynamic>>[],
            'note': dirty ? '账实不符（1 只标的持仓不一致，锚定日 2026-09-10）' : '账实一致',
          });
        }
        if (path == '/api/v1/trading/trades/import') {
          final dry = request.url.queryParameters['dryRun'] == 'true';
          return _json({
            'imported': 1, 'updated': 0, 'skipped': 0, 'nonTrades': 0, 'lines': [],
            'syncMode': 'append', 'dryRun': dry, 'rejected': [],
            'anchor': {'positionsReplace': '2026-09-10', 'cashImport': null,
                       'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-10'},
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      expect(find.textContaining('阿呆发现账对不上'), findsNothing);
      await tester.tap(find.text('历史成交'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();

      expect(integrityCalls, greaterThan(1), reason: '落盘后重算对账闸门');
      expect(find.text('阿呆发现账对不上：1 只标的持仓不一致'), findsOneWidget);
    });
  });

  group('RFC 20260912 账实不符闸门横幅（GET /trading/integrity）', () {
    testWidgets('drift/gaps 非空 → 顶部橙色横幅（可展开明细，文案第一原则）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/integrity') {
          return _json({
            'anchor': {'positionsReplace': '2026-09-08', 'cashImport': '2026-09-09',
                       'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-09'},
            'holdingsKnown': true,
            'drift': [
              {'symbol': '600123', 'name': '立昂微', 'snapshotQty': 200, 'ledgerDelta': -100,
               'derived': 100, 'holdings': 200, 'diff': 100, 'note': '派生 100 ≠ 落地 200'},
            ],
            'gaps': [
              {'symbol': '600519', 'name': '贵州茅台', 'direction': 'SELL', 'volume': 100,
               'price': 1500.0, 'entryDate': '2026-09-10', 'reason': '未持有 600519'},
            ],
            'note': '账实不符（1 只标的持仓不一致 / 1 笔回放缺口，锚定日 2026-09-09）',
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      expect(find.text('阿呆发现账对不上：1 只标的持仓不一致 / 1 笔回放缺口'), findsOneWidget);
      expect(find.text('看明细'), findsOneWidget);
      // 未展开 → 明细不显示（不制造噪音）
      expect(find.textContaining('应有 100 股'), findsNothing);

      await tester.tap(find.text('看明细'));
      await tester.pumpAndSettle();
      expect(find.text('600123 立昂微：应有 100 股（快照基线 200 + 锚点后流水 -100），落地 200 股，差 +100'),
          findsOneWidget);
      expect(find.textContaining('回放缺口 · 卖出 贵州茅台（600519）100 股 @ 1500.00'), findsOneWidget);
      expect(find.text('先导一次「持仓股」或「资金股份查询」快照，我就能重新对上了。'), findsOneWidget);
    });

    testWidgets('无差异 → 不显示任何横幅；接口失败静默降级不打断页面', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/integrity') {
          return _json({
            'anchor': {'positionsReplace': '2026-09-09', 'cashImport': null,
                       'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-09'},
            'holdingsKnown': true, 'drift': [], 'gaps': [],
            'note': '账实一致：派生持仓与落地持仓逐标的相符（锚定日 2026-09-09）',
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      expect(find.textContaining('阿呆发现账对不上'), findsNothing);
      expect(find.textContaining('账实一致'), findsNothing, reason: '一致时不刷存在感');
      // 页面正常（持仓表在）
      expect(find.textContaining('持仓 1 只'), findsOneWidget);
    });

    testWidgets('integrity 404（旧后端）→ 静默降级，页面正常', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404); // /integrity 也 404
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      expect(find.textContaining('阿呆发现账对不上'), findsNothing);
      expect(find.textContaining('加载失败'), findsNothing, reason: '可降级请求失败不整页错误态');
      expect(find.textContaining('持仓 1 只'), findsOneWidget);
    });
  });

  group('RFC 20260912 快照导入带 snapshotDate（锚定日 = 快照自身日期）', () {
    test('importPositions 传 snapshotDate（replace=true 并存）', () async {
      Map<String, String>? query;
      final client = MockClient((request) async {
        query = request.url.queryParameters;
        return _json({'imported': 1, 'missingStopLoss': []});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.importPositions([
        {'symbol': '600123', 'name': '立昂微', 'quantity': 200}
      ], replace: true, snapshotDate: '2026-09-08');
      expect(query!['replace'], 'true');
      expect(query!['snapshotDate'], '2026-09-08');
    });

    test('importPositions 不传 snapshotDate → query 里没有该参数（后端退回导入日）', () async {
      Map<String, String>? query;
      final client = MockClient((request) async {
        query = request.url.queryParameters;
        return _json({'imported': 0, 'missingStopLoss': []});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.importPositions([], replace: true);
      expect(query!.containsKey('snapshotDate'), isFalse);
    });

    test('importCash 传 snapshotDate（body）', () async {
      Map<String, dynamic>? body;
      final client = MockClient((request) async {
        body = jsonDecode(request.body) as Map<String, dynamic>;
        return _json({'cash': 1000.0, 'updatedCost': 2});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.importCash('资金导出文本', snapshotDate: '2026-09-09');
      expect(body!['content'], '资金导出文本');
      expect(body!['snapshotDate'], '2026-09-09');
      // 不传 → body 里没有该字段
      await api.importCash('资金导出文本');
      expect(body!.containsKey('snapshotDate'), isFalse);
    });

    test('getTradingIntegrity / getAnchorStatus / backfillAnchor 请求形态', () async {
      final calls = <String>[];
      final putBodies = <Map<String, dynamic>>[];
      final client = MockClient((request) async {
        calls.add('${request.method} ${request.url.path}');
        if (request.url.path == '/api/v1/trading/integrity') {
          return _json({'anchor': null, 'holdingsKnown': false, 'drift': [], 'gaps': [], 'note': '锚定缺失'});
        }
        if (request.method == 'PUT') {
          final b = jsonDecode(request.body) as Map<String, dynamic>;
          putBodies.add(b);
          return _json({'positionsReplace': b['positionsReplace'], 'cashImport': b['cashImport'],
                        'known': true, 'holdingsKnown': b.containsKey('holdings'),
                        'anchorDate': b['positionsReplace']});
        }
        return _json({'positionsReplace': '2026-09-08', 'cashImport': null,
                      'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-08'});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);

      final ig = await api.getTradingIntegrity();
      expect(ig.hasIssue, isFalse);
      expect(ig.note, '锚定缺失');
      final a = await api.getAnchorStatus();
      expect(a.known, isTrue);
      expect(a.anchorDate, '2026-09-08');
      final after = await api.backfillAnchor(
        positionsReplace: '2026-09-08',
        cashImport: '2026-09-09',
        holdings: [
          {'symbol': '600123', 'name': '立昂微', 'quantity': 200}
        ],
      );
      expect(after.holdingsKnown, isTrue);
      expect(putBodies.single['positionsReplace'], '2026-09-08');
      expect(putBodies.single['cashImport'], '2026-09-09');
      expect((putBodies.single['holdings'] as List).first['symbol'], '600123');
      // 只回填日期（holdings 不传）→ body 无 holdings 键
      await api.backfillAnchor(positionsReplace: '2026-09-10');
      expect(putBodies.length, 2);
      expect(putBodies[1].containsKey('holdings'), isFalse);
      expect(putBodies[1].containsKey('cashImport'), isFalse);
      expect(calls, [
        'GET /api/v1/trading/integrity',
        'GET /api/v1/trading/anchor',
        'PUT /api/v1/trading/anchor',
        'PUT /api/v1/trading/anchor',
      ]);
    });
  });
}
// ── v3.41（2026-09-04）：活跃市值区间开关（用户手动判定，红涨绿亏）──

class _StageApi extends ApiService {
  _StageApi({required MockClient client}) : super(baseUrl: 'http://test', client: client);
}

void _marketStageGroup() {
  group('活跃市值区间开关（v3.41，2026-09-04）', () {
    testWidgets('GET bear → 渲染空头区间（绿）+ 显示手动判定', (WidgetTester tester) async {
      final requests = <String>[];
      final client = MockClient((request) async {
        requests.add('${request.method} ${request.url.path}');
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/market-stage') {
          return _json({'exists': true, 'stage': 'bear', 'updatedAt': '2026-09-04T09:00:00'});
        }
        return http.Response('not found', 404);
      });
      final api = _StageApi(client: client);
      await _pumpTrading(tester, api);
      await tester.pumpAndSettle();

      expect(requests, contains('GET /api/v1/trading/market-stage'));
      expect(find.text('空头区间'), findsOneWidget, reason: '用户判定空头应渲染「空头区间」');
      expect(find.text('活跃市值（指南针）'), findsOneWidget, reason: '开关条标题');
      expect(find.textContaining('手动'), findsOneWidget, reason: '手动判定副文案');
    });

    testWidgets('点「多头」→ PUT /market-stage 切换 + 乐观更新多头区间', (WidgetTester tester) async {
      var stage = 'bear';
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/market-stage') {
          if (request.method == 'PUT') {
            stage = (jsonDecode(request.body) as Map)['stage'] as String;
            return _json({'updated': true, 'stage': stage, 'updatedAt': '2026-09-04T10:00:00'});
          }
          return _json({'exists': true, 'stage': stage, 'updatedAt': '2026-09-04T09:00:00'});
        }
        return http.Response('not found', 404);
      });
      final api = _StageApi(client: client);
      await _pumpTrading(tester, api);
      await tester.pumpAndSettle();

      expect(find.text('空头区间'), findsOneWidget);
      await tester.tap(find.text('多头'));
      await tester.pumpAndSettle();

      expect(stage, 'bull', reason: 'PUT body stage=bull');
      expect(find.text('多头区间'), findsOneWidget, reason: '切换后乐观更新为多头');
    });
  });
}
