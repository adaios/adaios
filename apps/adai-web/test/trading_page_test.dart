import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/trading_page.dart';
import 'package:adai_web/services/api_service.dart';
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

  group('批量导入 Dialog（web 独有）', () {
    testWidgets('粘贴多行 → POST /trades/batch → 成功 N 条', (tester) async {
      Map<String, dynamic>? sentBody;
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

        if (path == '/api/v1/trading/trades/batch') {
          sentBody = jsonDecode(request.body) as Map<String, dynamic>;
          return _json({'success': 2, 'failures': []});
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.byIcon(Icons.upload_file_outlined));
      await tester.pumpAndSettle();
      expect(find.text('批量导入交易'), findsOneWidget);

      await tester.enterText(
        find.byType(TextField),
        '600123,立昂微,BUY,25.30,200,22.8,B2\n600519,贵州茅台,买,1500,100,1350,B1,季报前埋伏',
      );
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      expect(sentBody, isNotNull);
      final trades = (sentBody!['trades'] as List).cast<Map<String, dynamic>>();
      expect(trades.length, 2);
      expect(trades[0]['symbol'], '600123');
      expect(trades[0]['stopLossPrice'], 22.8);
      expect(trades[0]['buyPoint'], 'B2');
      expect(trades[1]['direction'], 'BUY');
      expect(trades[1]['reason'], '季报前埋伏');
      expect(find.text('成功导入 2 条'), findsOneWidget);
    });

    testWidgets('本地解析失败 → 人话错误列表，不发请求', (tester) async {
      var batchCalls = 0;
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

        if (path == '/api/v1/trading/trades/batch') {
          batchCalls++;
          return _json({'success': 0, 'failures': []});
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.byIcon(Icons.upload_file_outlined));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '600519,贵州茅台,BUY,1500,100,,B1');
      await tester.tap(find.text('导入'));
      await tester.pumpAndSettle();

      expect(batchCalls, 0);
      expect(find.textContaining('买入必须填止损位'), findsOneWidget);
    });
  });

  group('交易历史 Dialog（web 独有）', () {
    testWidgets('按日期分组渲染：方向/代码/数量/价格/止损/买点/原因', (tester) async {
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
          return _json([
            {
              'id': 't1',
              'symbol': '600123',
              'name': '立昂微',
              'direction': 'BUY',
              'price': 25.3,
              'volume': 200,
              'entryDate': '2026-08-12',
              'stopLossPrice': 22.8,
              'buyPoint': 'B2',
              'reason': '平台突破',
            },
            {
              'id': 't2',
              'symbol': '600519',
              'name': '贵州茅台',
              'direction': 'SELL',
              'price': 1500.0,
              'volume': 100,
              'timestamp': '2026-08-11T10:30:00',
            },
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.byIcon(Icons.receipt_long_outlined));
      await tester.pumpAndSettle();

      // 页面 DataTable 也在树中（覆盖层 Dialog 之后）→ 断言限定在 Dialog 内
      Finder inDialog(Finder f) => find.descendant(of: find.byType(Dialog), matching: f);
      expect(inDialog(find.text('交易历史')), findsOneWidget);
      // 日期分组
      expect(inDialog(find.text('2026-08-12')), findsOneWidget);
      expect(inDialog(find.text('2026-08-11')), findsOneWidget);
      // 流水字段
      expect(inDialog(find.text('买入')), findsOneWidget);
      expect(inDialog(find.text('卖出')), findsOneWidget);
      expect(inDialog(find.text('25.300')), findsOneWidget);
      expect(inDialog(find.text('200')), findsOneWidget);
      expect(inDialog(find.text('22.800')), findsOneWidget);
      expect(inDialog(find.text('B2')), findsOneWidget);
      expect(inDialog(find.text('平台突破')), findsOneWidget);
    });

    testWidgets('空区间 → 空态文案', (tester) async {
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

        if (path == '/api/v1/trading/trades') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.byIcon(Icons.receipt_long_outlined));
      await tester.pumpAndSettle();
      expect(find.text('这段时间还没有交易记录'), findsOneWidget);
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
            {'symbol': '000725', 'name': '京东方A', 'buyPoint': 'B1', 'score': 0.8,
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

      // 命中：B1 80%（判定是提示不是指令，红色标出）
      expect(find.text('B1 80%'), findsOneWidget);
      // 未命中：—
      expect(find.text('—'), findsOneWidget);
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

}
