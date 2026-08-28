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
      }));
      expect(p.stopLossPrice, 22.8);
      expect(p.buyPoint, 'B2');
      expect(p.entryDate, '2026-08-01');
      expect(p.role, '防守·主仓');
      expect(p.targetPrice, 30.0);
      // 旧 positions.md 无新列 → null 兜底
      final old = PositionItem.fromJson(
          {'symbol': '600123', 'quantity': 100, 'avgCost': 1.0, 'currentPrice': 1.0});
      expect(old.stopLossPrice, isNull);
      expect(old.buyPoint, isNull);
      expect(old.role, isNull);
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

      // 日期分组
      expect(find.text('2026-08-12'), findsOneWidget);
      expect(find.text('2026-08-11'), findsOneWidget);
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

    test('非法行收集人话错误', () {
      const text = '证券代码\t证券名称\t股票余额\t成本价\n'
          'ABCD\t非法代码\t100\t10\n'
          '600519\t贵州茅台\t0\t1400\n';
      final r = parseTdxPositions(text);
      expect(r.rows, isEmpty);
      expect(r.errors.length, 2);
      expect(r.errors.join(' '), contains('六位数字'));
      expect(r.errors.join(' '), contains('数量'));
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
    expect(find.textContaining('无法识别历史成交导出'), findsOneWidget);
    expect(find.textContaining('请检查网络后重试'), findsNothing);
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
}
