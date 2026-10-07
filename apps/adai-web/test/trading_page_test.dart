import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/trading_page.dart';
import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/theme/app_colors.dart';
import 'package:adai_web/utils/trade_import_parser.dart';

/// 选文件替身（P2-工程12④ 导入抽屉「选完文件撤掉旧错误」用例）：
/// 测试里不走真文件选择器（不弹系统窗口）。值复制自 media_batch_test 的同名替身。
class _FakePicker extends FilePicker {
  _FakePicker(this.names, {this.emptyBytes = false});
  final List<String> names;
  final bool emptyBytes; // true = 造空文件（抽屉「选到空文件」用例）

  @override
  Future<FilePickerResult?> pickFiles({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    bool allowCompression = true,
    int compressionQuality = 30,
    bool allowMultiple = false,
    bool withData = false,
    bool withReadStream = false,
    bool lockParentWindow = false,
    bool readSequential = false,
  }) async =>
      FilePickerResult([
        for (var i = 0; i < names.length; i++)
          PlatformFile(
            name: names[i],
            size: emptyBytes ? 0 : 4,
            bytes: emptyBytes ? Uint8List(0) : Uint8List.fromList([65 + i, 66, 67, 68]),
          ),
      ]);
}

/// 注入选文件替身（跑完还原）。
void _useFakePicker(List<String> names, {bool emptyBytes = false}) {
  FilePicker? original;
  try {
    original = FilePicker.platform;
  } catch (_) {
    original = null;
  }
  FilePicker.platform = _FakePicker(names, emptyBytes: emptyBytes);
  addTearDown(() {
    if (original != null) FilePicker.platform = original;
  });
}

/// UTF-8 JSON 响应：MockClient 默认 Latin-1 编码 body，中文会炸，必须显式 charset=utf-8。
http.Response _json(Object body) => http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

/// multipart 文本字段断言（统一导入的 dryRun 是 multipart field；同
/// trading_bundle_analysis_test.dart 口径）。
bool _hasField(http.Request r, String name, String value) {
  final body = utf8.decode(r.bodyBytes, allowMalformed: true);
  final i = body.indexOf('name="$name"');
  if (i < 0) return false;
  final seg = body.substring(i, i + 200 > body.length ? body.length : i + 200);
  return seg.contains('\r\n\r\n$value');
}

/// yyyy-MM-dd（P2-交易48 账户卡来源日期断言用；页面按「今天」判是否过期）。
String _ymd(DateTime d) => '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

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

/// 基础交易页 mock handler：portfolio + positions + 空 trades/reviews。
/// 抽成独立函数，便于测试在其上「再加几条路由」（P2-交易71 需额外 mock cases/search）。
Future<http.Response> _tradingHandler(http.Request request) async {
  final path = request.url.path;
  if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
  if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
  if (path == '/api/v1/trading/account') return _json(_accountJson());
  if (path == '/api/v1/trading/watchlist') return _json([]);
  if (path == '/api/v1/trading/sold') return _json([]);
  if (path == '/api/v1/trading/buy-points') return _json([]);
  if (path == '/api/v1/trading/sold/score') return _json([]);
  if (path == '/api/v1/trading/sold/after-close') return _json([]);

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
}

MockClient _tradingMock() => MockClient(_tradingHandler);

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
20260803        14:52:56        600206          有研新材        卖出            -200.00         33.12000000         6624.00         151117          69351117                6620.05          A000000001
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
    test('PnlPeriodsDto 解析日/周/月；pct 缺失保持 null（不编造 0%）', () {
      final p = PnlPeriodsDto.fromJson({
        'today': {'pnl': -503.9, 'pct': -0.62, 'partial': false},
        'week': {'pnl': 1234.0, 'pct': null, 'partial': false},
        'month': {'pnl': 2500.0, 'pct': 3.1, 'partial': true},
        'asOf': '2026-09-15', 'anchorDate': '2026-09-11', 'note': '',
      });
      expect(p.today!.pnl, -503.9);
      expect(p.today!.pct, -0.62);
      expect(p.week!.pct, isNull, reason: '比例不可追溯 → null，UI 显示「—」而不是 0%');
      expect(p.month!.partial, isTrue, reason: '本月跨锚定日 → 标注只有部分可追溯');
      expect(p.anchorDate, '2026-09-11');
      final empty = PnlPeriodsDto.fromJson(null);
      expect(empty.today, isNull, reason: '旧后端/空响应不崩');
      expect(empty.asOf, '');
    });

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

      // m5：右栏入驻后主区变窄 → 表格横滚，操作列按钮可能滚出视口——先滚到它再点
      await tester.ensureVisible(find.text('编辑'));
      await tester.pumpAndSettle();
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

      // m5：右栏入驻后主区变窄 → 表格横滚，操作列按钮可能滚出视口——先滚到它再点
      await tester.ensureVisible(find.text('编辑'));
      await tester.pumpAndSettle();
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
        find.byKey(const Key('tradeImportContent')),
        '代码\t名称\t成本价\t证券数量\n600123\t立昂微\t25.30\t200\n600519\t贵州茅台\t1350\t100\n',
      );
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
      await tester.pumpAndSettle();

      expect(sentBody, isNotNull);
      expect(sentBody!.length, 2);
      expect(sentBody![0]['symbol'], '600123');
      expect(sentBody![0]['avgCost'], 25.30);
      expect(sentUri!.queryParameters['replace'], 'true');
      expect(find.textContaining('持仓导入 2 只'), findsOneWidget);
      expect(find.textContaining('未设止损 1 只'), findsOneWidget);
    });

    // ── P2-交易84（2026-10-05）：显式「数据基准日」——09:26 导出、09:28 导入不再被退到上一交易日 ──
    testWidgets('填了数据基准日 → 请求带 basedOn，回执如实说依据（有据）', (tester) async {
      Uri? importUri;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions' && request.method == 'GET') {
          return _json([_positionJson()]);
        }
        if (path == '/api/v1/trading/positions/import' && request.method == 'POST') {
          if (request.url.queryParameters['dryRun'] == 'true') {
            // 对账阶段：逐只相符 → 不打扰，直接进正式导入
            return _json({'dryRun': true, 'fileCount': 1, 'systemCount': 1, 'diffs': [], 'note': '一致'});
          }
          importUri = request.url;
          return _json({
            'imported': 1,
            'missingStopLoss': <String>[],
            'anchor': {
              'basis': 'EXPLICIT',
              'withEvidence': true,
              'note': '锚定日 2026-09-18：按你指定的数据基准日（有据）',
            },
          });
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
      await tester.enterText(find.byKey(const Key('tradeImportBasis')), '2026-09-18');
      await tester.enterText(find.byKey(const Key('tradeImportContent')),
          '代码\t名称\t成本价\t证券数量\n600123\t立昂微\t25.30\t200\n');
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
      await tester.pumpAndSettle();

      expect(importUri, isNotNull, reason: '应发出正式导入请求');
      expect(importUri!.queryParameters['basedOn'], '2026-09-18',
          reason: '用户显式说清的基准日必须一路传到后端（不许被前端吞掉）');
      expect(find.textContaining('按你指定的数据基准日（有据）'), findsOneWidget,
          reason: '锚定日的依据必须如实回执，不许静默');
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
        find.byKey(const Key('tradeImportContent')),
        '证券代码\t证券名称\t股票余额\t成本价\n'
        '600206\t有研新材\t900\t46.012\n'
        '600601\t方正科技\t100\t--\n',
      );
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
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
        find.byKey(const Key('tradeImportContent')),
        '代码\t名称\t成本价\t持仓量\n'
        '600206\t有研新材\t46.012\t900\n'
        '002428\t云南锗业\t53.765\t400\n'
        '600601\t方正科技\t-5.078\t100\n'
        '603113\t金能科技\t5.569\t0\n',
      );
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
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
        find.byKey(const Key('tradeImportContent')),
        '代码\t名称\t介入日期\t清仓日期\t持仓天数\t买卖次数\t持仓期涨幅%\n600519\t贵州茅台\t20260801\t20260810\t9\t1\t-5.0\n',
      );
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
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
      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      // m6：金额/数量默认掩码——本组验证字段级渲染 → 先点 👁 显形（掩码态断言在 m6 组）
      await tester.tap(find.byKey(const Key('revealToggle')));
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

      await tester.tap(find.text('账'));
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      // 粘贴交易 CSV（非历史成交格式）→ 人话拒绝，不静默落零
      await tester.enterText(
        find.byType(TextField).last,
        '600123,立昂微,BUY,25.3,200,22.8,B2',
      );
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      // m6：发生金额（股息）默认掩码 → 先点 👁 显形
      await tester.tap(find.byKey(const Key('revealToggle')));
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

      // ── 券商「现价」列（2026-09-23 P2-交易65：这一列也被整列丢弃过）──
      // 现场：券商「持仓股」导出带现价（002428 现价 88.43 / 成本 53.765），而前端只读
      // 代码/名称/数量/成本四列、后端落库又拿 avgCost 顶替 → positions.md 里「现价」全等于成本价。
      // 平时被实时行情盖住，行情源一挂就会显示成「0 盈亏 + 市值退回成本」的假象。
      test('券商「现价」列必须带上送；无该列时不带该字段（P2-交易65）', () {
        final r = parseTdxPositions(realFile);
        final yunnan = r.rows.firstWhere((e) => e.symbol == '002428');
        expect(yunnan.avgCost, closeTo(53.765, 0.0001));
        expect(yunnan.currentPrice, closeTo(88.43, 0.0001),
            reason: '现价必须与成本分开保留（原实现整列丢弃，落库只能用成本价顶替）');
        expect(yunnan.toJson()['currentPrice'], closeTo(88.43, 0.0001),
            reason: '上送请求体里要带现价，后端才知道真实市价');

        // 无「现价」列的旧导出/旧文件 → currentPrice 为 null，且**不带该字段**
        // （后端看到「缺字段」会保留原有存储价；带 null 会被当成显式清空）
        final noPrice = parseTdxPositions(
            '证券代码\t证券名称\t股票余额\t成本价\n600123\t立昂微\t200\t25.30\n');
        expect(noPrice.rows.single.currentPrice, isNull);
        expect(noPrice.rows.single.toJson().containsKey('currentPrice'), isFalse,
            reason: '没有现价就不带该字段——后端据此保留原有存储价，而不是写回成本价');
      });

      // ── 券商「当日盈亏」列（2026-09-13 用户点出：这一列一直没被读）──
      // 现场：账户卡显示 −2837.00（周六重算 + 双计污染持仓的产物），而文件里这一列 Σ = −1759.00
      // 与逐股复算一字不差 —— 权威值一直在用户手上，只是系统从来没解析它。
      group('券商当日盈亏列（2026-09-13）', () {
        test('Σ 全表（含 0 股行）= −1759.00 —— 与券商/复算一致', () {
          final r = parseTdxPositions(realFile);
          expect(r.todayPnl, isNotNull);
          expect(r.todayPnl, closeTo(-1759.00, 0.001),
              reason: '−1116.00 + −644.00 + 1.00 + −0.00 = −1759.00；'
                  '必须把 0 股行一起加（当日清仓标的的已实现盈亏也在这一列里）');
        });

        test('缺列 → null（不传，后端保留账户旧值，绝不落零）', () {
          const text = '证券代码\t证券名称\t股票余额\t成本价\n'
              '600206\t有研新材\t900\t46.012\n';
          final r = parseTdxPositions(text);
          expect(r.rows.length, 1);
          expect(r.todayPnl, isNull, reason: '文件没这一列 → 不发，避免把账户值覆盖成 0');
        });

        test('有行取不到数 → null（不可靠就不发，不发半截假数）', () {
          const text = '证券代码\t证券名称\t股票余额\t成本价\t当日盈亏\n'
              '600206\t有研新材\t900\t46.012\t-1116.00\n'
              '002428\t云南锗业\t400\t53.765\t--\n';
          final r = parseTdxPositions(text);
          expect(r.rows.length, 2, reason: '持仓行本身照常解析（当日盈亏只是附带列）');
          expect(r.todayPnl, isNull, reason: '有一行取不到数 → 总额不可靠，宁可不发');
        });

        test('「持仓盈亏」（累计口径）不得被误认成「当日盈亏」', () {
          const text = '证券代码\t证券名称\t股票余额\t成本价\t持仓盈亏\n'
              '600206\t有研新材\t900\t46.012\t-415.62\n';
          final r = parseTdxPositions(text);
          expect(r.todayPnl, isNull,
              reason: '持仓盈亏是相对成本的累计口径，与「当日」不是一回事——张冠李戴会把账户值改错');
        });
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
      await tester.tap(find.byKey(const Key('posFilter2')));
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

      await tester.tap(find.byKey(const Key('posFilter2')));
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

      await tester.tap(find.byKey(const Key('posFilter3')));
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

      await tester.tap(find.byKey(const Key('posFilter3')));
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

      await tester.tap(find.byKey(const Key('posFilter3')));
      await tester.pumpAndSettle();

      // 行为模式归类：追高 1 笔 + 恐慌 1 笔（同一笔命中两个词）+ 死扛 1 笔
      expect(find.textContaining('你的行为模式'), findsOneWidget);
      expect(find.textContaining('已标 2 笔'), findsOneWidget);
      expect(find.text('追高 1 笔'), findsOneWidget);
      expect(find.text('恐慌割肉 1 笔'), findsOneWidget);
      expect(find.text('套牢死扛 1 笔'), findsOneWidget);
    });
  });

  group('清仓「卖掉之后到现在」（2026-10-08）', () {
    testWidgets('表格列：↑走早了 / ↓走对了 / 没动；顶部两数只计涨跌', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '603993', 'name': '洛阳钼业', 'buyDate': '2026-07-20', 'sellDate': '2026-08-19',
             'holdDays': 30, 'tradeCount': '1+1', 'holdPnlPct': 18.4, 'verdict': '盈利了结', 'psychology': ''},
            {'symbol': '000725', 'name': '京东方A', 'buyDate': '2026-05-01', 'sellDate': '2026-05-26',
             'holdDays': 25, 'tradeCount': '1+1', 'holdPnlPct': -6.0, 'verdict': 'R53', 'psychology': ''},
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 0.5, 'verdict': '盈利了结', 'psychology': ''},
          ]);
        }
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/sold/after-close') {
          return _json([
            {'symbol': '603993', 'name': '洛阳钼业', 'sellDate': '2026-08-19', 'baseDate': '2026-08-19',
             'baseClose': 12.34, 'latestDate': '2026-10-07', 'latestClose': 14.61, 'pct': 18.4, 'direction': 'up'},
            {'symbol': '000725', 'name': '京东方A', 'sellDate': '2026-05-26', 'baseDate': '2026-05-26',
             'baseClose': 10.0, 'latestDate': '2026-10-07', 'latestClose': 9.89, 'pct': -1.1, 'direction': 'down'},
            {'symbol': '600519', 'name': '贵州茅台', 'sellDate': '2026-08-11', 'baseDate': '2026-08-11',
             'baseClose': 1500.0, 'latestDate': '2026-10-07', 'latestClose': 1500.0, 'pct': 0.0, 'direction': 'flat'},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.byKey(const Key('posFilter3')));
      await tester.pumpAndSettle();

      expect(find.text('卖掉之后到现在'), findsOneWidget, reason: '列头可见');
      // 三种方向文案（绿走对了/橙走早了语义色，与「这笔」涨跌色不同）
      expect(find.text('+18.4% ↑ 走早了'), findsOneWidget);
      expect(find.text('-1.1% ↓ 走对了'), findsOneWidget);
      expect(find.text('0.0% 没动'), findsOneWidget);
      // m3b：顶部两数上屏条（只计涨/跌，flat 不计；键锚「值」文本，改文案不碎测试）
      expect(tester.widget<Text>(find.byKey(const Key('soldStatAfterUp'))).data, '1 只 · 走早了');
      expect(tester.widget<Text>(find.byKey(const Key('soldStatAfterDown'))).data, '1 只 · 走对了');
    });

    testWidgets('单笔拿不到：该行「—」+ tooltip 说原因；其余照常、统计只计有数的', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '600157', 'name': '永泰能源', 'buyDate': '2025-06-01', 'sellDate': '2025-06-20',
             'holdDays': 19, 'tradeCount': '1+1', 'holdPnlPct': 3.0, 'verdict': '盈利了结', 'psychology': ''},
            {'symbol': '603993', 'name': '洛阳钼业', 'buyDate': '2026-07-20', 'sellDate': '2026-08-19',
             'holdDays': 30, 'tradeCount': '1+1', 'holdPnlPct': 18.4, 'verdict': '盈利了结', 'psychology': ''},
          ]);
        }
        if (path == '/api/v1/trading/sold/score') {
          return _json([
            {'symbol': '600157', 'name': '永泰能源', 'buyPointScore': 88, 'buyPointSignal': 'B1',
             'buyPointExplain': '', 'executionScore': 90, 'executionExplain': '', 'totalScore': 89, 'verdict': '盈利了结'},
            {'symbol': '603993', 'name': '洛阳钼业', 'buyPointScore': 88, 'buyPointSignal': 'B1',
             'buyPointExplain': '', 'executionScore': 90, 'executionExplain': '', 'totalScore': 89, 'verdict': '盈利了结'},
          ]);
        }
        if (path == '/api/v1/trading/sold/after-close') {
          return _json([
            {'symbol': '600157', 'name': '永泰能源', 'sellDate': '2025-06-20', 'baseDate': null,
             'baseClose': null, 'latestDate': null, 'latestClose': null, 'pct': null, 'direction': null,
             'note': '行情覆盖不到卖掉那天'},
            {'symbol': '603993', 'name': '洛阳钼业', 'sellDate': '2026-08-19', 'baseDate': '2026-08-19',
             'baseClose': 12.34, 'latestDate': '2026-10-07', 'latestClose': 14.61, 'pct': 18.4, 'direction': 'up'},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.byKey(const Key('posFilter3')));
      await tester.pumpAndSettle();

      // 差的那笔：「—」+ tooltip 说清为什么（不拿邻近价格编）
      expect(find.byTooltip('行情覆盖不到卖掉那天'), findsOneWidget);
      expect(find.descendant(of: find.byType(DataTable), matching: find.text('—')), findsOneWidget);
      // 好的一笔照常，统计只计有数的
      expect(find.text('+18.4% ↑ 走早了'), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const Key('soldStatAfterUp'))).data, '1 只 · 走早了');
      expect(tester.widget<Text>(find.byKey(const Key('soldStatAfterDown'))).data, '—',
          reason: '拿不到的笔不计，也绝不编「0 只」');
    });

    testWidgets('整批拿不到（404）：列全「—」、无统计两数、页面主体正常', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '603993', 'name': '洛阳钼业', 'buyDate': '2026-07-20', 'sellDate': '2026-08-19',
             'holdDays': 30, 'tradeCount': '1+1', 'holdPnlPct': 18.4, 'verdict': '盈利了结', 'psychology': ''},
          ]);
        }
        if (path == '/api/v1/trading/sold/score') {
          return _json([
            {'symbol': '603993', 'name': '洛阳钼业', 'buyPointScore': 88, 'buyPointSignal': 'B1',
             'buyPointExplain': '', 'executionScore': 90, 'executionExplain': '', 'totalScore': 89, 'verdict': '盈利了结'},
          ]);
        }
        // 不 mock sold/after-close → 404 → 静默降级（该列回落「—」，不打断页面）
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.byKey(const Key('posFilter3')));
      await tester.pumpAndSettle();

      expect(find.text('卖掉之后到现在'), findsOneWidget, reason: '列头照在');
      expect(find.descendant(of: find.byType(DataTable), matching: find.text('—')), findsOneWidget);
      // m3b：整批拿不到 → 屏条两格如实说「—」（不编「0 只」，也不拆掉格子）
      expect(tester.widget<Text>(find.byKey(const Key('soldStatAfterUp'))).data, '—');
      expect(tester.widget<Text>(find.byKey(const Key('soldStatAfterDown'))).data, '—');
      expect(find.text('清仓股复盘'), findsOneWidget, reason: '页面主体正常，不炸');
    });
  });

  // ── m3b（2026-10-07）：屏条补齐 —— 清仓五格 + 案例四格（原型 .wd-strip）──
  //
  // 原型口径（trading-web-full.html）：清仓屏五格 = 清仓 N 只 / 合计（各笔相加）/
  // 卖掉之后又跌 N 只·走对了 / 卖掉之后又涨 N 只·走早了 / 最长拿着（买入季度 + 天数）；
  // 案例屏四格 = 已收下 N / 成功·失败 N/M / 本周新增 N / 等你认 N。
  // 拿不到就「—」或该格不显示（绝不编 0）；「值」文本挂 Key 锚，改文案不碎测试。
  group('m3b 屏条补齐（2026-10-07）', () {
    testWidgets('清仓五格：数量 / 合计 / 卖掉之后两格 / 最长拿着（含买入季度）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '603993', 'name': '洛阳钼业', 'buyDate': '2026-07-20', 'sellDate': '2026-08-19',
             'holdDays': 30, 'tradeCount': '1+1', 'holdPnlPct': 18.4, 'verdict': '盈利了结', 'psychology': ''},
            {'symbol': '000725', 'name': '京东方A', 'buyDate': '2026-05-01', 'sellDate': '2026-05-26',
             'holdDays': 25, 'tradeCount': '1+1', 'holdPnlPct': -6.0, 'verdict': 'R53', 'psychology': ''},
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 0.5, 'verdict': '盈利了结', 'psychology': ''},
          ]);
        }
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/sold/after-close') {
          return _json([
            {'symbol': '603993', 'name': '洛阳钼业', 'sellDate': '2026-08-19', 'baseDate': '2026-08-19',
             'baseClose': 12.34, 'latestDate': '2026-10-07', 'latestClose': 14.61, 'pct': 18.4, 'direction': 'up'},
            {'symbol': '000725', 'name': '京东方A', 'sellDate': '2026-05-26', 'baseDate': '2026-05-26',
             'baseClose': 10.0, 'latestDate': '2026-10-07', 'latestClose': 9.89, 'pct': -1.1, 'direction': 'down'},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);
      await tester.tap(find.byKey(const Key('posFilter3')));
      await tester.pumpAndSettle();

      Text stripCell(Key k) => tester.widget<Text>(find.byKey(k));
      expect(stripCell(const Key('soldStatCount')).data, '3 只');
      expect(stripCell(const Key('soldStatTotal')).data, '+12.90%',
          reason: '合计 = 各笔持仓期涨幅直接相加（18.4 - 6.0 + 0.5）');
      expect(stripCell(const Key('soldStatAfterDown')).data, '1 只 · 走对了');
      expect(stripCell(const Key('soldStatAfterUp')).data, '1 只 · 走早了');
      expect(stripCell(const Key('soldStatLongest')).data, '三季度 30 天',
          reason: '拿最久的一笔（07-20 买 = 三季度，30 天）');
    });

    testWidgets('最长拿着：买入日认不出 → 只显示天数，不猜季度', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold') {
          return _json([
            {'symbol': '600157', 'name': '永泰能源', 'buyDate': null, 'sellDate': '2025-06-20',
             'holdDays': 19, 'tradeCount': '1+1', 'holdPnlPct': 3.0, 'verdict': '盈利了结', 'psychology': ''},
            {'symbol': '603993', 'name': '洛阳钼业', 'buyDate': '不是日期', 'sellDate': '2026-08-19',
             'holdDays': 5, 'tradeCount': '1+1', 'holdPnlPct': 1.0, 'verdict': '盈利了结', 'psychology': ''},
          ]);
        }
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);
      await tester.tap(find.byKey(const Key('posFilter3')));
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(const Key('soldStatLongest'))).data, '19 天',
          reason: '买入日认不出就不缀季度（只报天数）');
    });

    testWidgets('案例四格：已收下 / 成功·失败 / 本周新增 / 等你认', (tester) async {
      final now = DateTime.now();
      // 数据内联（caseJson/candidateJson 是 main() 局部函数、声明在本组之后——Dart 不许先用后声明）
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
          return _json([
            {'id': '2026-08-03_000725', 'symbol': '000725', 'name': '京东方A',
             'buyDate': '2026-08-03', 'buyType': 'B1'},
            {'id': '2026-08-10_600519', 'symbol': '600519', 'name': '贵州茅台',
             'buyDate': '2026-08-10', 'buyType': 'FAILED'},
          ]);
        }
        if (request.url.path == '/api/v1/trading/cases/candidates' && request.method == 'GET') {
          return _json({
            'pending': [
              {'id': 'p1', 'outcome': 'EARLY', 'title': '洛阳钼业 08-19 卖了之后又涨 18.4%', 'notes': <String>[]},
              {'id': 'p2', 'outcome': 'SUCCESS', 'title': '紫金矿业 08-12 买在 5 日线上方', 'notes': <String>[]},
            ],
            'accepted': [
              // 本周收下的 1 条 + 上周的 1 条 → 「本周新增」只计本周
              {'id': 'a1', 'outcome': 'RIGHT', 'title': '跌破止损就走，没扛', 'notes': <String>[],
               'updatedAt': now.toIso8601String()},
              {'id': 'a2', 'outcome': 'RIGHT', 'title': '上周收下的那条', 'notes': <String>[],
               'updatedAt': now.subtract(const Duration(days: 7)).toIso8601String()},
            ],
          });
        }
        return _tradingHandler(request);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);
      await tester.pumpAndSettle();
      await tester.tap(find.text('案例'));
      await tester.pumpAndSettle();

      Text stripCell(Key k) => tester.widget<Text>(find.byKey(k));
      expect(stripCell(const Key('caseStatAccepted')).data, '2', reason: '案例库总数（1 B1 + 1 FAILED）');
      expect(stripCell(const Key('caseStatSuccessFail')).data, '1 / 1');
      expect(stripCell(const Key('caseStatWeekNew')).data, '1', reason: '本周（周一起算）收下 1 条；上周那条不计');
      expect(stripCell(const Key('caseStatPending')).data, '2', reason: '等你认 = pending 数');
    });

    testWidgets('案例候选拿不到（404）：本周新增/等你认 两格不显示（不编 0）；已收下照常', (tester) async {
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
          return _json([
            {'id': '2026-08-03_000725', 'symbol': '000725', 'name': '京东方A',
             'buyDate': '2026-08-03', 'buyType': 'B1'},
          ]);
        }
        return _tradingHandler(request);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);
      await tester.pumpAndSettle();
      await tester.tap(find.text('案例'));
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(const Key('caseStatAccepted'))).data, '1');
      expect(tester.widget<Text>(find.byKey(const Key('caseStatSuccessFail'))).data, '1 / 0');
      expect(find.byKey(const Key('caseStatWeekNew')), findsNothing, reason: '算不了就不显示，不编 0');
      expect(find.byKey(const Key('caseStatPending')), findsNothing);
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

      await tester.tap(find.byKey(const Key('posFilter3')));
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

      await tester.tap(find.byKey(const Key('posFilter3')));
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

      await tester.tap(find.byKey(const Key('posFilter3')));
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

      // m6：金额默认掩码 → 先点 👁 显形再验千分位
      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();

      // 总盈亏 = 资产 - 本金 = -39495.12 → 千分位显示
      expect(find.text('¥-39,495.12'), findsOneWidget);
      expect(find.textContaining('本金 ¥150,000'), findsOneWidget);
    });
  });

  // ── B2-2（2026-08-23）：资金区块总盈亏 principal=0 不把全部资产当总盈亏 ──

  testWidgets('B2-2+P2-交易31 本金未设（principal=0）不给误导数值（自证条本金格「—」+ 还没记过转入/转出）', (tester) async {
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

    await tester.tap(find.text('账'));
    await tester.pumpAndSettle();
    // P2-交易31（2026-08-29，U32）+ 2026-10-08 账三合一：principal=0 → 总盈亏 null →
    // 不给误导数值（不回落浮盈、不把全部资产当总盈亏）；旧「设置本金」入口随后端 410 退役已撤。
    expect(find.textContaining('总盈亏 ¥15,235.55'), findsNothing);
    expect(find.textContaining('总盈亏 ¥110,504.88'), findsNothing);
    expect(find.textContaining('设置本金后显示'), findsNothing);
    // 本金口径统一为「转入/转出自动算」：自证条本金格 '—' +「还没记过转入/转出」；
    // 顶部状态条总盈亏格 note 同一文案（m5 把原账户卡 statCard 收回状态条）——共 2 处
    expect(find.text('还没记过转入/转出'), findsNWidgets(2));
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

    await tester.tap(find.text('账'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('导入历史成交'));
    await tester.pumpAndSettle();
    // 表头四关键词齐全 → 过本地 isTdxHistoryExport 校验 → 请求打后端 → 400 人话透出
    await tester.enterText(find.byType(TextField).last,
        '成交日期 证券代码 证券名称 买卖标志 成交编号');
    // 2026-10-07：顶栏常驻「导入」与对话框内「导入」同文 → 限定在弹窗内点
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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

      // m5：右栏入驻后主区变窄 → 表格横滚，操作列按钮可能滚出视口——先滚到它再点
      // m6：批次明细属浏览类金额 → 默认掩码；本组验证字段级渲染 → 先点 👁 显形
      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('批次'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('批次'));
      await tester.pumpAndSettle();

      // 请求参数：state=all + symbol（一次拿全，含回合/初始底仓）
      expect(lotsQuery!['state'], 'all');
      expect(lotsQuery!['symbol'], '600123');
      // 2026-09-16 用户拍板：**只列还持有着的批次**——3 个已清仓回合一律不显示
      expect(find.text('初始底仓'), findsOneWidget);
      expect(find.text('持有中'), findsOneWidget);
      expect(find.text('已清仓'), findsNothing, reason: '已清仓批次不再出现在持仓批次里');
      expect(find.text('回合 250.00'), findsNothing);
      expect(find.text('回合 -80.00'), findsNothing);
      expect(find.text('回合 90.00'), findsNothing);
      // 剩余/买入量：只剩两个持有中批次
      expect(find.text('100 / 100'), findsOneWidget);
      expect(find.text('100 / 200'), findsOneWidget);
      expect(find.text('0 / 300'), findsNothing);
      expect(find.text('0 / 500'), findsNothing);
      expect(find.text('0 / 100'), findsNothing);
      // 买点/角色（B3 + 角色在持有中批次；B1 只挂在已清仓批次 → 不再显示）
      expect(find.text('B3'), findsOneWidget);
      expect(find.text('B1'), findsNothing);
      expect(find.text('防守·主仓'), findsOneWidget);
      // 距止损%：两个持有中批次同值；已破止损那条属已清仓批次 → 不显示
      expect(find.text('12.63%'), findsNWidgets(2));
      expect(find.text('-2.10%'), findsNothing);
      // 红涨绿亏：持有中批次盈利=红
      final red = tester.widget<Text>(find.text('110.00'));
      expect(red.style?.color, AppColors.darkRed);
      // 盈亏%：开放批次浮动 pnlPct；已清仓回合收益率（realizedPnl / 成本×买入量，前端算）。
      // 限定弹窗内：持仓表本身也有盈亏% 列（pnlPercent 3.16），避免与弹窗批次盈亏% 撞文本
      final inLotsDialog = find.byType(Dialog);
      expect(find.descendant(of: inLotsDialog, matching: find.text('4.40%')), findsOneWidget);
      expect(find.descendant(of: inLotsDialog, matching: find.text('3.16%')), findsOneWidget);
      // 已清仓回合的收益率不再显示（2026-09-16：只列还持有着的批次）
      expect(find.descendant(of: inLotsDialog, matching: find.text('回合 3.47%')), findsNothing);
      expect(find.descendant(of: inLotsDialog, matching: find.text('回合 -1.60%')), findsNothing);
      expect(find.descendant(of: inLotsDialog, matching: find.text('回合 4.09%')), findsNothing);
      // 百分比颜色同盈亏（红涨绿亏）
      final pctRed = tester.widget<Text>(find.descendant(of: inLotsDialog, matching: find.text('4.40%')));
      expect(pctRed.style?.color, AppColors.darkRed);
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

      // m5：右栏入驻后主区变窄 → 表格横滚，操作列按钮可能滚出视口——先滚到它再点
      await tester.ensureVisible(find.text('批次'));
      await tester.pumpAndSettle();
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
20260825        14:52:56        600000          浦发银行        买入            200.00         9.20000000         1840.00         151117          69351117                1840.00          A000000001
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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
      await tester.tap(find.text('账')); // 一键同步按钮在历史成交 Tab 工具行
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

  // 批 ③（2026-10-08）：案例候选（GET /trading/cases/candidates 的 pending/accepted 元素）。
  Map<String, dynamic> candidateJson({
    String id = 'sell-603993-2026-08-19',
    String kind = 'SELL',
    String outcome = 'EARLY',
    String symbol = '603993',
    String name = '洛阳钼业',
    String date = '2026-08-19',
    String title = '洛阳钼业 08-19 卖了之后又涨 18.4%',
    double? changePct = 18.44,
    String? ruleRel,
    String? ruleText,
    List<String>? notes,
  }) =>
      {
        'id': id,
        'state': 'CANDIDATE',
        'kind': kind,
        'outcome': outcome,
        'symbol': symbol,
        'name': name,
        'date': date,
        'title': title,
        'changePct': changePct,
        'ruleRel': ruleRel,
        'ruleId': null,
        'ruleText': ruleText,
        'notes': notes ?? ['卖掉之后到现在 +18.4%。'],
        'createdAt': '2026-10-08T10:00',
        'updatedAt': '2026-10-08T10:00',
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

  testWidgets('批 ③ 案例候选：候选卡 + 已收下小表渲染（买点/卖点标签、对照、计数）', (tester) async {
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/candidates' && request.method == 'GET') {
        return _json({
          'pending': [
            candidateJson(),
            candidateJson(
              id: 'buy-601899-2026-08-12',
              kind: 'BUY',
              outcome: 'SUCCESS',
              symbol: '601899',
              name: '紫金矿业',
              date: '2026-08-12',
              title: '紫金矿业 08-12 买在 5 日线上方',
              changePct: 6.1,
              ruleRel: 'SUPPORT',
              ruleText: '不在 5 日线上方不加',
              notes: ['支持你写的「不在 5 日线上方不加」—— 这一笔之后涨了 6.1%。', '买了之后到现在 +6.1%。'],
            ),
          ],
          'accepted': [
            candidateJson(
              id: 'sell-600048-2026-03-27',
              kind: 'SELL',
              outcome: 'RIGHT',
              symbol: '600048',
              name: '保利发展',
              date: '2026-03-27',
              title: '跌破止损就走，没扛',
              changePct: -4.2,
              ruleRel: 'SUPPORT',
              ruleText: '跌破止损先减一半',
              notes: ['支持你写的「跌破止损先减一半」—— 这一笔卖后跌了 4.2%。'],
            ),
          ],
        });
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();

    expect(find.text('从你的记录里长出来的'), findsOneWidget);
    expect(find.text('我不替你定，你认了才算 · 等你认 2 条'), findsOneWidget,
        reason: '计数如实（pending = 2）');
    expect(find.text('洛阳钼业 08-19 卖了之后又涨 18.4%'), findsOneWidget);
    expect(find.text('卖点 · 走早了'), findsOneWidget);
    expect(find.text('紫金矿业 08-12 买在 5 日线上方'), findsOneWidget);
    expect(find.text('买点 · 成功'), findsOneWidget);
    expect(find.text('支持你写的「不在 5 日线上方不加」—— 这一笔之后涨了 6.1%。'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '收下'), findsNWidgets(2));
    expect(find.widgetWithText(OutlinedButton, '改一改'), findsNWidgets(2));
    expect(find.widgetWithText(OutlinedButton, '不要'), findsNWidgets(2));

    expect(find.text('已经收下的'), findsOneWidget);
    expect(find.text('跌破止损就走，没扛'), findsOneWidget, reason: '收下时改过的名字留下');
    expect(find.text('保利发展 600048 · 2026-03-27'), findsOneWidget);
    expect(find.text('卖点 · 走对了'), findsOneWidget);
    expect(find.text('支持「跌破止损先减一半」'), findsOneWidget);
  });

  testWidgets('批 ③ 案例候选：点「收下」→ POST accept（不带 title）+ 回执', (tester) async {
    final acceptCalls = <String>[];
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/candidates' && request.method == 'GET') {
        return _json({
          'pending': [candidateJson()],
          'accepted': <Map<String, dynamic>>[],
        });
      }
      if (request.url.path == '/api/v1/trading/cases/candidates/sell-603993-2026-08-19/accept') {
        acceptCalls.add('${request.method} ${request.body}');
        return _json({'candidate': candidateJson()});
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, '收下').first);
    await tester.pumpAndSettle();

    expect(acceptCalls.length, 1, reason: '点一次只发一次 accept（幂等由后端兜底，前端不重复发）');
    expect(acceptCalls.single, startsWith('POST'));
    expect(acceptCalls.single, isNot(contains('title')),
        reason: '直接收下不传 title（保持原型标题）');
    expect(find.text('收下了 —— 它成了你的一条案例'), findsOneWidget, reason: '要有回执');
  });

  testWidgets('批 ③ 案例候选：「改一改」→ 对话框改名后收下（accept 带 title）', (tester) async {
    String acceptBody = '';
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/candidates' && request.method == 'GET') {
        return _json({
          'pending': [candidateJson()],
          'accepted': <Map<String, dynamic>>[],
        });
      }
      if (request.url.path == '/api/v1/trading/cases/candidates/sell-603993-2026-08-19/accept') {
        acceptBody = request.body;
        return _json({'candidate': candidateJson()});
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, '改一改').first);
    await tester.pumpAndSettle();

    final dialog = find.byType(AlertDialog);
    expect(dialog, findsOneWidget, reason: '应先打开改名对话框');
    final field = find.descendant(of: dialog, matching: find.byType(TextField));
    await tester.enterText(field, '卖飞案例：到线才放');
    await tester.tap(find.descendant(of: dialog, matching: find.widgetWithText(FilledButton, '收下')));
    await tester.pumpAndSettle();

    expect(acceptBody, contains('卖飞案例：到线才放'), reason: '「改一改」收下应带新名字');
  });

  testWidgets('批 ③ 案例候选：点「不要」→ POST dismiss（墓碑不复活）+ 回执', (tester) async {
    var dismissCalled = false;
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/candidates' && request.method == 'GET') {
        return _json({
          'pending': [candidateJson()],
          'accepted': <Map<String, dynamic>>[],
        });
      }
      if (request.url.path == '/api/v1/trading/cases/candidates/sell-603993-2026-08-19/dismiss') {
        dismissCalled = true;
        return _json({'dismissed': true, 'id': 'sell-603993-2026-08-19'});
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, '不要').first);
    await tester.pumpAndSettle();

    expect(dismissCalled, isTrue, reason: '「不要」应落成墓碑（POST dismiss）');
    expect(find.text('好，这条不再出现'), findsOneWidget, reason: '要有回执');
  });

  testWidgets('批 ③ 案例候选：拿不到（旧后端 404）→ 整区静默不显示，案例区照常', (tester) async {
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      return _tradingHandler(request); // candidates 未 mock → 404（旧后端场景）
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();

    expect(find.text('从你的记录里长出来的'), findsNothing, reason: '拿不到就整区不显示（零噪音）');
    expect(find.text('已经收下的'), findsNothing);
    expect(find.text('完美买点案例'), findsOneWidget, reason: '不打断案例版块');
  });

  testWidgets('批 ③ 案例候选：没有候选也没有收下过 → 整区不显示', (tester) async {
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/candidates' && request.method == 'GET') {
        return _json({'pending': <Map<String, dynamic>>[], 'accepted': <Map<String, dynamic>>[]});
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();

    expect(find.text('从你的记录里长出来的'), findsNothing, reason: '没数据不占位（沉默是默认）');
    expect(find.text('已经收下的'), findsNothing);
  });

  testWidgets('P2-交易71：标注弹窗只打字不点下拉 → 弹窗内明说原因、不发 POST、输入不丢', (tester) async {
    var postCalled = false;
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'POST') {
        postCalled = true;
        return _json(caseJson());
      }
      if (request.url.path == '/api/v1/trading/search') {
        return _json(<Map<String, dynamic>>[]);
      }
      return _tradingHandler(request); // 基础数据：保证页面正常态、TabBar 渲染
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('标注案例'));
    await tester.pumpAndSettle();

    final dialog = find.byType(AlertDialog);
    expect(dialog, findsOneWidget, reason: '应先打开标注弹窗');
    final fields = find.descendant(of: dialog, matching: find.byType(TextField));
    // 第 0 个 = 标的搜索框（只打字，不点下拉候选）；第 1 个 = 买点日期
    await tester.enterText(fields.at(0), '688656');
    await tester.pump(const Duration(milliseconds: 400)); // 越过 300ms 防抖
    await tester.enterText(fields.at(1), '2026-09-17');
    await tester.pumpAndSettle();

    await tester.tap(find.descendant(of: dialog, matching: find.widgetWithText(TextButton, '标注')));
    await tester.pumpAndSettle();

    expect(postCalled, isFalse,
        reason: '没点下拉候选就不该发 POST（2026-09-23 生产实证：连 CORS preflight 都没有）');
    expect(find.text('请从下拉列表里选择标的（只输入代码不算）'), findsOneWidget,
        reason: '提示要能照做，不能只丢一句「代码和日期必填」让人猜');
    expect(find.byType(AlertDialog), findsOneWidget, reason: '校验失败应留在弹窗内——不关窗、不丢已填内容');
    expect(find.text('2026-09-17'), findsOneWidget, reason: '已填的买点日期应保留');
  });

  testWidgets('P2-交易71：标注弹窗点选下拉候选后照常提交（原链路不回归）', (tester) async {
    var postBody = '';
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'POST') {
        postBody = request.body;
        return _json(caseJson());
      }
      if (request.url.path == '/api/v1/trading/search') {
        return _json([
          {'symbol': '000831', 'name': '中国稀土'},
        ]);
      }
      return _tradingHandler(request); // 基础数据：保证页面正常态、TabBar 渲染
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('标注案例'));
    await tester.pumpAndSettle();

    final dialog = find.byType(AlertDialog);
    final fields = find.descendant(of: dialog, matching: find.byType(TextField));
    await tester.enterText(fields.at(0), '中国稀土');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    await tester.tap(find.text('000831')); // 点候选 → 外层才拿到 symbol
    await tester.pumpAndSettle();
    await tester.enterText(fields.at(1), '2026-09-17');
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: dialog, matching: find.widgetWithText(TextButton, '标注')));
    await tester.pumpAndSettle();

    expect(postBody, contains('000831'), reason: '点选候选后应带 symbol 提交');
    expect(postBody, contains('2026-09-17'), reason: '应带 buyDate 提交');
  });

  testWidgets('P2-交易71：匹配弹窗只打字不点下拉 → 弹窗内红字报错（原先点了毫无反应）', (tester) async {
    var matchCalled = false;
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/cases/match') {
        matchCalled = true;
        return _json({'matches': <Map<String, dynamic>>[]});
      }
      if (request.url.path == '/api/v1/trading/search') {
        return _json(<Map<String, dynamic>>[]);
      }
      return _tradingHandler(request); // 基础数据：保证页面正常态、TabBar 渲染
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('匹配买点'));
    await tester.pumpAndSettle();

    final dialog = find.byType(AlertDialog);
    expect(dialog, findsOneWidget, reason: '应先打开匹配弹窗');
    final fields = find.descendant(of: dialog, matching: find.byType(TextField));
    await tester.enterText(fields.at(0), '688656');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();

    await tester.tap(find.descendant(of: dialog, matching: find.widgetWithText(TextButton, '匹配')));
    await tester.pumpAndSettle();

    expect(matchCalled, isFalse, reason: '没点下拉候选不该真的发起匹配');
    expect(find.text('请从下拉列表里选择标的（只输入代码不算）'), findsOneWidget,
        reason: '原先这里直接 return，点「匹配」毫无反应');
  });

  testWidgets('P2-交易71 同族：导入抽屉空内容点「导入」→ 抽屉内提示、不关窗', (tester) async {
    final api = ApiService(baseUrl: 'http://test', client: _tradingMock());
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('导入持仓'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('importDrawer')), findsOneWidget, reason: '应先打开导入抽屉');

    await tester.tap(find.byKey(const Key('importConfirmBtn')));
    await tester.pumpAndSettle();

    expect(find.text('先粘贴内容，或选择通达信导出的文件'), findsOneWidget,
        reason: '空内容要如实说明（原先直接 return，点了毫无反应）');
    expect(find.byKey(const Key('importDrawer')), findsOneWidget, reason: '校验失败不该关窗');
  });

  testWidgets('P2-交易71 同族：案例批量导入空内容 → 弹窗内提示（原先点了毫无反应）', (tester) async {
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('批量导入'));
    await tester.pumpAndSettle();

    final dialog = find.byType(AlertDialog);
    expect(dialog, findsOneWidget, reason: '应先打开批量导入弹窗');
    await tester.tap(find.descendant(of: dialog, matching: find.widgetWithText(TextButton, '导入')));
    await tester.pumpAndSettle();

    expect(find.text('先粘贴要导入的案例文本'), findsOneWidget,
        reason: '空内容要如实说明（原先直接 return，点了毫无反应）');
    expect(find.byType(AlertDialog), findsOneWidget, reason: '校验失败不该关窗');
  });

  // ── P2-工程12④：过期红字残留（2026-10-04）──
  // 作者在标的框刻意做了「用户重新输入即撤掉旧错误，不拿过期提示挡新动作」，
  // 但日期框（标注弹窗）与选文件（导入弹窗）漏了同一件清理。

  testWidgets('P2-工程12④ 标注弹窗：日期校验失败后一改日期 → 红字立刻撤掉', (tester) async {
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/search') {
        return _json([
          {'symbol': '000831', 'name': '中国稀土'},
        ]);
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('标注案例'));
    await tester.pumpAndSettle();

    final dialog = find.byType(AlertDialog);
    final fields = find.descendant(of: dialog, matching: find.byType(TextField));
    // 先选中标的（symbol 非空），日期留空 → 点「标注」触发的是**日期**校验
    await tester.enterText(fields.at(0), '中国稀土');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    await tester.tap(find.text('000831')); // 点候选 → symbolCtrl 才有值
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: dialog, matching: find.widgetWithText(TextButton, '标注')));
    await tester.pumpAndSettle();

    expect(find.text('买点日期必填（yyyy-MM-dd，如 2026-08-03）'), findsOneWidget,
        reason: '日期空要如实说明');

    await tester.enterText(fields.at(1), '2026-09-17');
    await tester.pumpAndSettle();
    expect(find.text('买点日期必填（yyyy-MM-dd，如 2026-08-03）'), findsNothing,
        reason: '改日期即撤掉旧错误（原先红字挂着，看着像新输入也不对）');
  });

  testWidgets('P3 标注弹窗：未选标的时敲日期 → 「请从下拉列表里选择标的」红字仍在', (tester) async {
    // formError 是跨字段单一变量：标的为空时它承载的是标的错误，日期框 onChanged 不该清掉
    // 仍然成立的提示（否则用户敲一下日期，红字消失、实际问题还在）。
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/cases' && request.method == 'GET') {
        return _json(<Map<String, dynamic>>[]);
      }
      if (request.url.path == '/api/v1/trading/search') {
        return _json([
          {'symbol': '000831', 'name': '中国稀土'},
        ]);
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('案例'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('标注案例'));
    await tester.pumpAndSettle();

    final dialog = find.byType(AlertDialog);
    final fields = find.descendant(of: dialog, matching: find.byType(TextField));
    // 只打字、**不点候选** → symbolCtrl 仍空（后端要的 symbol 为空）
    await tester.enterText(fields.at(0), '中国稀土');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    await tester.enterText(fields.at(1), '2026-09-17'); // 日期填好，确保错误只剩标的这一条
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: dialog, matching: find.widgetWithText(TextButton, '标注')));
    await tester.pumpAndSettle();
    expect(find.text('请从下拉列表里选择标的（只输入代码不算）'), findsOneWidget,
        reason: '漏点候选要如实说、要能照做');

    await tester.enterText(fields.at(1), '2026-09-18');
    await tester.pumpAndSettle();
    expect(find.text('请从下拉列表里选择标的（只输入代码不算）'), findsOneWidget,
        reason: '标的还没选，这条错误仍成立——敲日期不能把它抹掉');
  });

  testWidgets('P3 导入抽屉：选到空文件 → 「先粘贴内容…」红字仍在（只在真有内容时才清）', (tester) async {
    _useFakePicker(['空文件20261001.txt'], emptyBytes: true);
    final api = ApiService(baseUrl: 'http://test', client: _tradingMock());
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('导入持仓'));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('importConfirmBtn')));
    await tester.pumpAndSettle();
    expect(find.text('先粘贴内容，或选择通达信导出的文件'), findsOneWidget);

    await tester.tap(find.byKey(const Key('importPickZone')));
    await tester.pumpAndSettle();
    expect(find.text('选的文件都是空的——重新导出后再试'), findsOneWidget,
        reason: '空文件要当场说清，不让用户干等');
    expect(find.text('先粘贴内容，或选择通达信导出的文件'), findsOneWidget,
        reason: '空文件清掉这句等于把仍成立的话藏起来（用户仍不知道该粘什么）');
  });

  testWidgets('P2-工程12④ 导入抽屉：选完文件 → 自动预检 + 旧红字撤掉', (tester) async {
    _useFakePicker(['持仓20261001.txt']);
    final dryRuns = <String>[];
    final client = MockClient((request) async {
      if (request.url.path == '/api/v1/trading/import' && request.method == 'POST') {
        dryRuns.add(_hasField(request, 'dryRun', 'true') ? 'dry' : 'real');
        return _json({
          'dryRun': true, 'okCount': 1, 'failedCount': 0,
          'files': [
            {
              'filename': '持仓20261001.txt', 'kind': 'positions', 'kindLabel': '持仓股',
              'ok': true,
              'detail': {'dryRun': true, 'fileCount': 1, 'systemCount': 0, 'diffs': [], 'note': '一致'},
            },
          ],
        });
      }
      return _tradingHandler(request);
    });
    final api = ApiService(baseUrl: 'http://test', client: client);
    await _pumpTrading(tester, api);
    await tester.pumpAndSettle();

    await tester.tap(find.text('导入持仓'));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('importConfirmBtn')));
    await tester.pumpAndSettle();
    expect(find.text('先粘贴内容，或选择通达信导出的文件'), findsOneWidget);

    await tester.tap(find.byKey(const Key('importPickZone')));
    await tester.pumpAndSettle();

    expect(dryRuns, ['dry'], reason: '选完文件即自动预检（原型「选完就看计划」）');
    expect(find.text('先粘贴内容，或选择通达信导出的文件'), findsNothing,
        reason: '选完文件即撤掉旧错误（原先红字挂着，看着像文件也不行）');
    expect(find.text('这次认出来的'), findsOneWidget);
    expect(find.text('持仓20261001.txt'), findsNWidgets(2), reason: '文件列表 + 回执行各一份');
    expect(find.text('· 一致'), findsOneWidget, reason: '「对完账才发现的事」直出后端人话');
    expect(find.text('确认入账 1 条'), findsOneWidget, reason: '底部主按钮按计划给条数');
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

    // 2026-09-21（P1-交易61）：锚定日当天的成交被按「已含在快照内」处理 → 只记流水、没进持仓。
    // 后端新增 degraded（每行带 inferred）：锚定日明确时是事实说明；锚定日**被推断**时才是警告。
    test('integrity 降级流水：inferred 才计入 hasIssue（锚定日推断 → 要用户核对）', () {
      final warn = IntegrityReportDto.fromJson({
        'holdingsKnown': true,
        'drift': [],
        'gaps': [],
        'degraded': [
          {'symbol': '600206', 'name': '有研新材', 'direction': 'BUY', 'volume': 100,
           'price': 46.85, 'entryDate': '2026-09-17', 'inferred': true,
           'reason': '成交日 = 锚定日，而锚定日是由文件日期推断的'},
        ],
        'note': '账实一致；⚠️ 另有 1 笔成交只记了流水、没进持仓',
      });
      expect(warn.degraded.single.symbol, '600206');
      expect(warn.degraded.single.inferred, isTrue);
      expect(warn.hasIssue, isTrue, reason: '锚定日被推断 + 有降级成交 → 必须出横幅');

      // 锚定日明确（非推断）→ 只是事实说明，不制造噪音
      final info = IntegrityReportDto.fromJson({
        'holdingsKnown': true, 'drift': [], 'gaps': [],
        'degraded': [
          {'symbol': '600206', 'name': '有研新材', 'direction': 'SELL', 'volume': 200,
           'inferred': false, 'reason': '成交日 = 锚定日，已含在券商快照内'},
        ],
        'note': '账实一致',
      });
      expect(info.degraded.single.inferred, isFalse);
      expect(info.hasIssue, isFalse);

      // 旧后端不返回 degraded → 空列表、不崩
      final legacy = IntegrityReportDto.fromJson(
          {'holdingsKnown': true, 'drift': [], 'gaps': [], 'note': ''});
      expect(legacy.degraded, isEmpty);
      expect(legacy.hasIssue, isFalse);
    });

    // ── P2-交易84（2026-10-05）：显式「数据基准日」输入 ──
    test('基准日输入解析（合法才传，非法不猜）', () {
      expect(parseBasisDateInput('2026-09-18'), '2026-09-18');
      expect(parseBasisDateInput(' 2026/09/18 '), '2026-09-18');
      expect(parseBasisDateInput('20260918'), '2026-09-18');
      expect(parseBasisDateInput(''), isNull, reason: '留空 = 不传该字段（后端走既有归一化并标「无据」）');
      expect(parseBasisDateInput('   '), isNull);
      expect(parseBasisDateInput('2026-13-01'), isNull, reason: '非法月份不猜');
      expect(parseBasisDateInput('2026-02-30'), isNull, reason: '不存在的日期不猜');
      expect(parseBasisDateInput('昨天'), isNull, reason: '人话不能当日期');
    });

    test('导入回执的锚定依据可解析（旧后端缺字段 → 不编造）', () {
      final p = PositionImportResult.fromJson(<String, dynamic>{
        'imported': 1,
        'missingStopLoss': <String>[],
        'anchor': <String, dynamic>{
          'basis': 'EXPLICIT',
          'withEvidence': true,
          'note': '锚定日 2026-09-18：按你指定的数据基准日（有据）',
        },
      });
      expect(p.anchorWithEvidence, isTrue);
      expect(p.anchorNote, contains('有据'));

      final legacy = PositionImportResult.fromJson(<String, dynamic>{
        'imported': 1,
        'missingStopLoss': <String>[],
      });
      expect(legacy.anchorNote, isNull, reason: '旧后端没有该字段 → 不显示也不编造');
      expect(legacy.anchorWithEvidence, isFalse);

      final c = CashImportResult.fromJson(<String, dynamic>{
        'cash': 1,
        'assets': 2,
        'updatedCost': 0,
        'anchor': <String, dynamic>{
          'basis': 'CLOCK',
          'withEvidence': false,
          'note': '锚定日 2026-09-17：按导入时间推断（无据）',
        },
      });
      expect(c.anchorWithEvidence, isFalse);
      expect(c.anchorNote, contains('无据'));
    });
  });

  group('RFC 20260912 历史成交导入：预检 → 确认两段式', () {
    const tdxText = '''
成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
20260912        14:52:56        600000          浦发银行        买入            200.00         9.20000000         1840.00         151117          69351117                1840.00          A000000001
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
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
      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();

      expect(integrityCalls, greaterThan(1), reason: '落盘后重算对账闸门');
      // 2026-10-08 账三合一：横幅已撤，断点改在账区自证条当场指出（状态条 + 自证条同屏两份状态）
      expect(find.text('⚠ 有差异'), findsNWidgets(2), reason: '状态条 + 账区自证条各一份');
      expect(find.textContaining('1 只持仓不一致 · 看明细'), findsOneWidget);
    });
  });

  group('RFC 20260912 账实一致性（GET /trading/integrity · 状态条呈现）', () {
    // 2026-10-08 账三合一：原「顶部橙色横幅」已撤（与状态条+自证条叠在一起的第三份噪音）——
    // 断点明细改在账区自证条「当场指出」（见上面「账三合一 自证条」组）。

    testWidgets('无差异 → 状态条说「✓ 对上了」·不显示异常文案；接口失败静默降级不打断页面', (tester) async {
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

      // 一致性呈现：状态条「账实 ✓ 对上了」+ 右栏阿呆说「账对上了。」（m5：卡头即「阿呆说」，句子无前缀）——无横幅、无摘要噪音
      expect(find.text('✓ 对上了'), findsOneWidget);
      expect(find.text('账对上了。'), findsOneWidget);
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

      // 拿不到对账 → 不编「对上了」：右栏阿呆说「对账还没取到。」（m5 卡头即「阿呆说」；带句号与账区自证条 sub 区分）
      expect(find.text('对账还没取到。'), findsOneWidget);
      expect(find.textContaining('阿呆发现账对不上'), findsNothing);
      expect(find.textContaining('加载失败'), findsNothing, reason: '可降级请求失败不整页错误态');
      expect(find.textContaining('持仓 1 只'), findsOneWidget);
    });
  });

  // ── 2026-10-08 账三合一：账区自证条（现金自证 · 本金从数据推 · 断点当场指出）──
  // 顶部横幅（RFC 20260912）仍跨区可见；账区自证条是账内常驻的详细呈现，两者共用同一份明细渲染。

  group('账三合一 自证条（现金/本金/账实三格）', () {
    testWidgets('健康态：现金标券商日期 · 本金金额+总盈亏 · 账实对上了+锚定日', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') {
          return _json({'assets': 110504.88, 'cash': 292.88, 'available': 292.88,
            'withdrawable': 292.88, 'marketValue': 110212.0, 'pnl': 15235.55,
            'todayPnl': 0.0, 'principal': 150000.0, 'snapshotDate': '2026-09-16',
            'cashDate': '2026-09-16'});
        }
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/integrity') {
          return _json({
            'anchor': {'positionsReplace': '2026-09-09', 'cashImport': '2026-09-16',
                       'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-16'},
            'holdingsKnown': true, 'drift': [], 'gaps': [],
            'note': '账实一致：派生持仓与落地持仓逐标的相符（锚定日 2026-09-16）',
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      // m6：金额默认掩码 → 先点 👁 显形再验格值
      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();

      // 现金格：数是券商导进来的 → 标出对应快照日期（P2-交易69 同口径）
      expect(find.text('✓ 券商 2026-09-16 的余额'), findsOneWidget);
      // 本金格：转入/转出自动算 → 金额 + 总盈亏（资产 − 本金 = -39495.12）
      expect(find.text('¥150,000.00'), findsOneWidget);
      expect(find.text('总盈亏 -¥39,495.12'), findsOneWidget);
      // 账实格：对上了 + 锚定日（账内常驻状态，不是告警）——状态条 + 自证条同说「对上了」
      expect(find.text('✓ 对上了'), findsNWidgets(2), reason: '状态条 + 账区自证条各一份');
      expect(find.text('锚定日 2026-09-16'), findsOneWidget);
    });

    testWidgets('断点态：账实格当场指出（摘要可展开/收起，明细与旧横幅同口径）', (tester) async {
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

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();

      expect(find.text('⚠ 有差异'), findsNWidgets(2), reason: '状态条 + 账区自证条各一份');
      final summary = find.textContaining('1 只持仓不一致 · 1 笔回放缺口 · 看明细');
      expect(summary, findsOneWidget);
      // 未展开 → 明细不显示（不制造噪音）
      expect(find.textContaining('应有 100 股'), findsNothing);

      await tester.tap(summary);
      await tester.pumpAndSettle();
      expect(find.text('600123 立昂微：应有 100 股（快照基线 200 + 锚点后流水 -100），落地 200 股，差 +100'),
          findsOneWidget);
      expect(find.textContaining('回放缺口 · 卖出 贵州茅台（600519）100 股 @ 1500.00'), findsOneWidget);
      expect(find.text('先导一次「持仓股」或「资金股份查询」快照，我就能重新对上了。'), findsOneWidget);

      await tester.tap(find.textContaining('· 收起'));
      await tester.pumpAndSettle();
      expect(find.textContaining('应有 100 股'), findsNothing);
    });

    testWidgets('信息如实：现金异常文案 + 本金置信度说明 · integrity 拿不到 → 「对账还没取到」', (tester) async {
      const cashNote = '现金余额还是券商 08-16 的快照（已 31 天没更新）——先导一次「资金股份查询」';
      const principalNote = '本金含存量迁移调整（历史出入金无流水）——转账记录补全后自动校正';
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') {
          return _json({'assets': 110504.88, 'cash': -686.88, 'available': 0.0,
            'withdrawable': 0.0, 'marketValue': 110212.0, 'pnl': 15235.55,
            'todayPnl': 0.0, 'principal': 150000.0, 'snapshotDate': '2026-08-16',
            'cashDate': '2026-08-16', 'cashNote': cashNote, 'principalNote': principalNote});
        }
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        return http.Response('not found', 404); // /integrity 404（旧后端）
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();

      // 现金格：有异常 → 「⚠ 有异常」+ 条下橙色人话行（后端文案只渲染不加工）
      expect(find.text('⚠ 有异常'), findsOneWidget);
      expect(find.text(cashNote), findsOneWidget);
      // 本金置信度说明：中性灰，是说明不是告警
      expect(find.text(principalNote), findsOneWidget);
      // 对账拿不到 → 不编造：账实格「—」+「对账还没取到」（诚实原则）
      expect(find.text('对账还没取到'), findsOneWidget);
    });
  });

  // ── RFC 20260923 D 批：行情（K 线）链路可用性横幅 ──
  // 后端把「三源连续全失败」只写进日志、双端交易页只在 ok=false 时出横幅（无异常零显示）。

  group('RFC 20260923 行情链路横幅（GET /trading/market-data/health）', () {
    test('health DTO 正常解析（含 sources 取数链）；ok=true 不报警', () {
      final h = MarketDataHealthDto.fromJson({
        'ok': false,
        'note': '行情取数连续 12 次都没拿到（最近一次失败 09-22 23:38:00 · 600487）——'
            '资金曲线、自选信号、案例匹配可能不全，我在自动重试',
        'lastSuccessAt': '09-22 15:00:00',
        'lastSuccessSource': 'tdx',
        'lastFailureAt': '09-22 23:38:00',
        'consecutiveFailures': 12,
        'lastFailedSymbol': '600487',
        'sources': ['tdx', '腾讯', '东财', '新浪'],
        'tdxLastDate': '2026-09-04',
      });
      expect(h.ok, isFalse);
      expect(h.shouldWarn, isTrue);
      expect(h.note, contains('连续 12 次'));
      expect(h.lastSuccessAt, '09-22 15:00:00');
      expect(h.lastSuccessSource, 'tdx');
      expect(h.lastFailureAt, '09-22 23:38:00');
      expect(h.consecutiveFailures, 12);
      expect(h.lastFailedSymbol, '600487');
      expect(h.sources, ['tdx', '腾讯', '东财', '新浪']);
      expect(h.tdxLastDate, '2026-09-04', reason: 'P2-交易58：本地数据包最后一根日期如实解析');

      // 行情正常：lastFailureAt 可为 null（后端确实没失败过）——不报警、不显示
      final fine = MarketDataHealthDto.fromJson({
        'ok': true, 'note': '行情正常（最近一次 09-22 15:00:00 · tdx）',
        'lastSuccessAt': '09-22 15:00:00', 'lastSuccessSource': 'tdx',
        'lastFailureAt': null, 'consecutiveFailures': 0, 'lastFailedSymbol': null,
        'sources': ['tdx'],
      });
      expect(fine.ok, isTrue);
      expect(fine.shouldWarn, isFalse);
      expect(fine.lastFailureAt, isNull);
    });

    test('health DTO 防御式解析：字段缺失 / 非 map / ok 缺失 → 不误报且不抛', () {
      // 旧后端无此端点 / 响应不是对象：拿不到信息 = 不报警（漏报可接受，误报消耗信任）
      final empty = MarketDataHealthDto.fromJson(null);
      expect(empty.ok, isTrue);
      expect(empty.shouldWarn, isFalse);
      expect(empty.note, '');
      expect(empty.consecutiveFailures, 0);
      expect(empty.sources, isEmpty);
      expect(empty.lastFailureAt, isNull);
      expect(empty.tdxLastDate, isNull, reason: '旧后端没这字段 → null（拿不到 ≠ 滞后）');

      // ok 字段缺失但其它字段在（半残响应）→ 仍按正常处理
      final noOk = MarketDataHealthDto.fromJson({'note': '缺 ok', 'sources': <String>[]});
      expect(noOk.ok, isTrue);
      expect(noOk.shouldWarn, isFalse);

      // 类型错乱（次数是字符串 / sources 不是数组 / note 是数字）→ 安全默认，绝不抛
      final weird = MarketDataHealthDto.fromJson({
        'ok': false, 'consecutiveFailures': 'x', 'sources': 'tdx', 'note': 123,
      });
      expect(weird.consecutiveFailures, 0);
      expect(weird.sources, isEmpty);
      expect(weird.note, '123');
      expect(weird.shouldWarn, isTrue, reason: '明确的 ok=false 仍要报警');
    });

    testWidgets('ok=false → 顶部橙色横幅（人话标题 + 后端 note；展开见取数链/时刻/次数）', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/market-data/health') {
          return _json({
            'ok': false,
            'note': '行情取数连续 12 次都没拿到（最近一次失败 09-22 23:38:00 · 600487）——'
                '资金曲线、自选信号、案例匹配可能不全，我在自动重试',
            'lastSuccessAt': '09-22 15:00:00', 'lastSuccessSource': 'tdx',
            'lastFailureAt': '09-22 23:38:00', 'consecutiveFailures': 12,
            'lastFailedSymbol': '600487', 'sources': ['tdx', '腾讯', '东财', '新浪'],
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      expect(find.text('阿呆最近拿不到行情'), findsOneWidget);
      expect(find.textContaining('行情取数连续 12 次都没拿到'), findsOneWidget);
      // 未展开 → 细节不显示（第一眼只要「拿不到行情」，复查才看细节，不制造噪音）
      expect(find.textContaining('取数链'), findsNothing);

      await tester.tap(find.text('看明细'));
      await tester.pumpAndSettle();
      expect(find.text('最近成功 09-22 15:00:00 · tdx'), findsOneWidget);
      expect(find.text('最近失败 09-22 23:38:00'), findsOneWidget);
      expect(find.text('连续失败 12 次'), findsOneWidget);
      expect(find.text('取数链 tdx → 腾讯 → 东财 → 新浪'), findsOneWidget);
    });

    testWidgets('ok=true → 零显示（行情正常不刷存在感），页面正常', (tester) async {
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
        if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
        if (path == '/api/v1/trading/account') return _json(_accountJson());
        if (path == '/api/v1/trading/watchlist') return _json([]);
        if (path == '/api/v1/trading/sold') return _json([]);
        if (path == '/api/v1/trading/buy-points') return _json([]);
        if (path == '/api/v1/trading/sold/score') return _json([]);
        if (path == '/api/v1/trading/market-data/health') {
          return _json({
            'ok': true, 'note': '行情正常（最近一次 09-22 15:00:00 · tdx）',
            'lastSuccessAt': '09-22 15:00:00', 'lastSuccessSource': 'tdx',
            'lastFailureAt': null, 'consecutiveFailures': 0, 'lastFailedSymbol': null,
            'sources': ['tdx', '腾讯'],
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      expect(find.text('阿呆最近拿不到行情'), findsNothing);
      expect(find.textContaining('行情正常'), findsNothing, reason: '正常时不刷存在感');
      expect(find.textContaining('持仓 1 只'), findsOneWidget);
    });

    testWidgets('health 404（旧后端）/ 500（网络抖动）→ 静默降级，不弹错误不崩', (tester) async {
      Future<void> pumpWith(http.Response Function() healthResp) async {
        final client = MockClient((request) async {
          final path = request.url.path;
          if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
          if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
          if (path == '/api/v1/trading/account') return _json(_accountJson());
          if (path == '/api/v1/trading/watchlist') return _json([]);
          if (path == '/api/v1/trading/sold') return _json([]);
          if (path == '/api/v1/trading/buy-points') return _json([]);
          if (path == '/api/v1/trading/sold/score') return _json([]);
          if (path == '/api/v1/trading/market-data/health') return healthResp();
          return http.Response('not found', 404);
        });
        await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: client));
      }

      await pumpWith(() => http.Response('not found', 404));
      expect(find.text('阿呆最近拿不到行情'), findsNothing);
      expect(find.textContaining('加载失败'), findsNothing, reason: '可降级请求失败不整页错误态');
      expect(find.textContaining('持仓 1 只'), findsOneWidget);

      // 500 同样静默（不能因为健康端点坏了就影响看盘），页面数据照常
      await pumpWith(() => http.Response('boom', 500));
      expect(find.text('阿呆最近拿不到行情'), findsNothing);
      expect(find.textContaining('加载失败'), findsNothing);
      expect(find.textContaining('持仓 1 只'), findsOneWidget);
    });

    test('P2-交易58 行情滞后提示（纯函数）：阈值与后端 tdxStale 同口径（>3 天才说）', () {
      final now = DateTime(2026, 10, 4, 10, 30);
      expect(tdxLagNote(null, now: now), isNull, reason: '本地关掉 / 旧后端 → 拿不到 ≠ 滞后');
      expect(tdxLagNote('', now: now), isNull);
      expect(tdxLagNote('20260930', now: now), isNull, reason: '格式认不出 → 不编造日期');
      expect(tdxLagNote('abcd-09-30', now: now), isNull);
      expect(tdxLagNote('2026-10-04', now: now), isNull, reason: '差距 0 → 不制造噪音');
      // 阈值对齐后端 services/adai-core .../KlineService.java#tdxStale
      // （`ChronoUnit.DAYS.between(last, today) > 3`）：1~3 天仍用本地、零网络请求 → 一个字都不说
      expect(tdxLagNote('2026-10-03', now: now), isNull, reason: '滞后 1 天仍在「用本地」区间，没有缺口');
      expect(tdxLagNote('2026-10-01', now: now), isNull, reason: '滞后 3 天是后端仍用本地的最后一天');
      expect(tdxLagNote('2026-09-30', now: now), '我手上的行情只到 09-30，后面几天的我去网上补上',
          reason: '滞后 4 天起后端确实改走网络源 → 如实说一句');
      expect(tdxLagNote('2026-10-05', now: now), isNull, reason: '后端给了未来日 → 不报警');
      // 不存在的日期不照抄（DateTime 会把 02-31 规范化成 03-03、00-00 变成上一年 11-30）
      expect(tdxLagNote('2026-02-31', now: now), isNull, reason: '02-31 不存在 → 不编造日子');
      expect(tdxLagNote('2026-00-00', now: now), isNull, reason: '00-00 不存在 → 不编造日子');
    });

    testWidgets('P2-交易58 滞后 >3 天才说「我手上的行情只到 X」；≤3 天 / 今天 / 没给 → 零显示', (tester) async {
      Future<void> pumpWith(Object? tdxLastDate) async {
        final client = MockClient((request) async {
          if (request.url.path == '/api/v1/trading/market-data/health') {
            return _json({
              'ok': true, 'note': '行情正常（最近一次 10-04 15:00:00 · tdx）',
              'lastSuccessAt': '10-04 15:00:00', 'lastSuccessSource': 'tdx',
              'lastFailureAt': null, 'consecutiveFailures': 0, 'lastFailedSymbol': null,
              'sources': ['tdx', '腾讯'], 'tdxLastDate': tdxLastDate,
            });
          }
          return _tradingHandler(request);
        });
        // 先卸载：同类型 widget 直接重挂会**复用** TradingPage 的 State（旧 health 留着，
        // initState 不再跑）→ 必须真正重建，才验得到「这一份 health 说了什么」
        await tester.pumpWidget(const SizedBox.shrink());
        await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: client));
      }

      // 滞后 4 天（= 后端 tdxStale 判 true 的第 1 天；相对真实的今天算，避免写死日期跨日失效）
      final lag = _ymd(DateTime.now().subtract(const Duration(days: 4)));
      final expected = '我手上的行情只到 ${lag.substring(5)}，后面几天的我去网上补上';
      await pumpWith(lag);
      final line = find.text(expected);
      expect(line, findsOneWidget, reason: '第 4 天起后端确实去网上补 → 如实说一句');
      expect(find.text('阿呆最近拿不到行情'), findsNothing, reason: '滞后 ≠ 行情坏了，不借横幅报警');
      // 第一原则 B1：正面白名单——必须是这句「我」口吻的原话，且不含任何开发者术语
      expect(tester.widget<Text>(line).data, contains(expected));
      for (final banned in ['网络源', '本地行情', '本地数据包', '取数链', '系统', '接口']) {
        expect(tester.widget<Text>(line).data, isNot(contains(banned)),
            reason: '第一原则：不得出现「$banned」这类系统视角词');
      }

      // 滞后 ≤3 天：后端仍用本地（零网络请求）→ 一个字都不说（原先这里会误报，一年中大半时间常驻）
      await pumpWith(_ymd(DateTime.now().subtract(const Duration(days: 3))));
      expect(find.textContaining('我手上的行情只到'), findsNothing,
          reason: '阈值与后端一致：前 1~3 天用本地，没有缺口也没走网络');
      await pumpWith(_ymd(DateTime.now().subtract(const Duration(days: 1))));
      expect(find.textContaining('我手上的行情只到'), findsNothing, reason: '隔天更不该报');

      // 就是今天 → 零噪音（不刷存在感）
      await pumpWith(_ymd(DateTime.now()));
      expect(find.textContaining('我手上的行情只到'), findsNothing, reason: '差距 0 不制造噪音');

      // 后端没给（本地关掉 / 旧后端）→ 也零显示，页面照常
      await pumpWith(null);
      expect(find.textContaining('我手上的行情只到'), findsNothing);
      expect(find.textContaining('持仓 1 只'), findsOneWidget, reason: '不显示 ≠ 页面坏了');
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

    test('importPositions 传券商当日盈亏（2026-09-13）——两位小数进 query；不传则无该参数', () async {
      Map<String, String>? query;
      final client = MockClient((request) async {
        query = request.url.queryParameters;
        return _json({'imported': 1, 'missingStopLoss': []});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);

      await api.importPositions([
        {'symbol': '600206', 'name': '有研新材', 'quantity': 900}
      ], replace: true, snapshotDate: '2026-09-11', todayPnl: -1759.0);
      expect(query!['todayPnl'], '-1759.00', reason: '券商「持仓股」导出该列之和');

      // 缺列（null）→ 不传：后端保留账户旧值，绝不落零
      await api.importPositions([], replace: true, snapshotDate: '2026-09-11');
      expect(query!.containsKey('todayPnl'), isFalse);
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

  // ── P2-交易48（当日盈亏来源/日期）+ P2-交易43（导入丢行可见），2026-09-14 后端本批 ──
  // 共同红线：字段缺失/来源未知/解析失败 → 行为与现在完全一致（不报错、不显示、不崩）。

  group('P2-交易48 当日盈亏来源小字（纯函数）', () {
    final now = DateTime(2026, 9, 14, 10, 0);

    test('broker/calc → 人话口径 + MM-dd；当天不缀「已过期」', () {
      expect(todayPnlSourceNote('broker', '2026-09-14', now: now), '券商口径 · 09-14');
      expect(todayPnlSourceNote('calc', '2026-09-14', now: now), '系统计算 · 09-14');
    });

    test('快照日不是今天 → 缀「（已过期）」（陈值一眼可见）', () {
      expect(todayPnlSourceNote('calc', '2026-09-13', now: now), '系统计算 · 09-13（已过期）');
      expect(todayPnlSourceNote('broker', '2026-09-11', now: now), '券商口径 · 09-11（已过期）');
    });

    test('来源未知/缺失/认不出 → null（不标，绝不编造）', () {
      expect(todayPnlSourceNote('', '2026-09-14', now: now), isNull);
      expect(todayPnlSourceNote('  ', '2026-09-14', now: now), isNull);
      expect(todayPnlSourceNote('void-calc', '2026-09-14', now: now), isNull,
          reason: '认不出的值不硬翻人话');
    });

    test('大小写/空格容忍；日期缺失/非法 → 只报来源，不崩', () {
      expect(todayPnlSourceNote(' BROKER ', '2026-09-14', now: now), '券商口径 · 09-14');
      expect(todayPnlSourceNote('calc', '', now: now), '系统计算');
      expect(todayPnlSourceNote('calc', '2026-09', now: now), '系统计算');
      expect(todayPnlSourceNote('calc', '2026-0X-11', now: now), '系统计算');
      expect(todayPnlSourceNote('calc', 'not-a-date', now: now), '系统计算');
    });
  });

  group('P2-交易48/43 DTO 解析（旧后端缺字段不炸）', () {
    test('AccountSnapshotDto 解析 todayPnlSource；缺字段/类型不符安全兜底', () {
      final a = AccountSnapshotDto.fromJson({'assets': 1.0, 'todayPnlSource': 'broker'});
      expect(a.todayPnlSource, 'broker');
      expect(AccountSnapshotDto.fromJson({'assets': 1.0}).todayPnlSource, '',
          reason: '旧后端缺字段 → 空（前端不标来源）');
      expect(AccountSnapshotDto.fromJson({'todayPnlSource': 123}).todayPnlSource, '123',
          reason: '类型不符转字符串，不崩');
      expect(AccountSnapshotDto.fromJson(null).todayPnlSource, '');
    });

    test('CashImportResult 解析 unparsedRows；缺字段 → 0', () {
      expect(CashImportResult.fromJson({'cash': 1.0, 'updatedCost': 2, 'unparsedRows': 3}).unparsedRows, 3);
      expect(CashImportResult.fromJson({'cash': 1.0}).unparsedRows, 0);
      expect(CashImportResult.fromJson('boom').unparsedRows, 0);
    });

    test('HistoricalTradeImportResult 解析 unparsed + unparsedCount；旧后端 → 空/0', () {
      final r = HistoricalTradeImportResult.fromJson({
        'imported': 0,
        'unparsed': ['第 3 行「2026080X …」：成交日期「2026080X」不是 yyyyMMdd 格式', 42],
        'unparsedCount': 5,
      });
      expect(r.unparsed.length, 2);
      expect(r.unparsed.first, contains('不是 yyyyMMdd 格式'));
      expect(r.unparsed[1], '42', reason: '非字符串元素安全转字符串');
      expect(r.unparsedCount, 5, reason: '明细可能只给前几条，计数以字段为准');
      final old = HistoricalTradeImportResult.fromJson({'imported': 1});
      expect(old.unparsed, isEmpty);
      expect(old.unparsedCount, 0);
      expect(HistoricalTradeImportResult.fromJson('boom').unparsed, isEmpty);
    });

    test('计数缺省 → 退回明细条数；聚合去重合并 + 计数累加', () {
      final single = HistoricalTradeImportResult.fromJson({
        'imported': 0, 'unparsed': ['第 1 行：数量不是数字'],
      });
      expect(single.unparsedCount, 1, reason: '字段缺失 → 明细条数兜底');
      final a = HistoricalTradeImportResult.fromJson({
        'imported': 0, 'unparsed': ['第 3 行：日期不是 yyyyMMdd'],
      });
      final b = HistoricalTradeImportResult.fromJson({
        'imported': 0,
        'unparsed': ['第 3 行：日期不是 yyyyMMdd', '第 7 行：买卖标志认不出'],
      });
      final agg = aggregateImportResults([a, b]);
      expect(agg.unparsed.length, 2, reason: '相同文本去重合并');
      expect(agg.unparsedCount, 3, reason: '两份文件的没看懂行计数累加');
    });
  });

  group('P2-交易83 清仓/资金导入丢行明细 DTO（契约 v3.96，缺字段不炸）', () {
    test('SoldImportResult 解析 unparsed + unparsedCount；缺字段/类型不符安全兜底', () {
      final r = SoldImportResult.fromJson({
        'imported': 42,
        'unparsed': ['第 3 行「60021\t截断代码\t20260731\t…」：代码「60021」不是 6 位数字', 42],
        'unparsedCount': 3,
      });
      expect(r.imported, 42);
      expect(r.unparsed.length, 2);
      expect(r.unparsed.first, contains('不是 6 位数字'));
      expect(r.unparsed[1], '42', reason: '非字符串元素安全转字符串');
      expect(r.unparsedCount, 3, reason: '计数以字段为准');

      final old = SoldImportResult.fromJson({'imported': 5});
      expect(old.imported, 5, reason: '旧后端只回 imported，笔数照旧可用');
      expect(old.unparsed, isEmpty, reason: '缺字段 → 空列表');
      expect(old.unparsedCount, 0, reason: '缺字段 → 0');
      expect(SoldImportResult.fromJson('boom').imported, 0, reason: '响应形状不符不崩');
      expect(SoldImportResult.fromJson('boom').unparsed, isEmpty);
    });

    test('SoldImportResult 计数缺省 → 退回明细条数（与历史成交同口径）', () {
      final r = SoldImportResult.fromJson({'imported': 0, 'unparsed': ['第 1 行：清仓日期认不出']});
      expect(r.unparsedCount, 1);
    });

    test('CashImportResult 解析 unparsed + unparsedCount；unparsedRows 仍按 int（缺字段 → 0）', () {
      final r = CashImportResult.fromJson({
        'cash': 1381.93, 'assets': 79231.93, 'updatedCost': 3, 'unparsedRows': 1,
        'unparsed': ['第 4 行「这不是明细行 xxx」：证券代码「这不是明细行」不是 6 位数字'],
        'unparsedCount': 1,
      });
      expect(r.unparsedRows, 1, reason: '契约明确该字段保持 int，改类型会把导入打挂');
      expect(r.unparsed.single, contains('不是 6 位数字'));
      expect(r.unparsedCount, 1);

      final old = CashImportResult.fromJson({'cash': 1.0, 'updatedCost': 2});
      expect(old.unparsed, isEmpty);
      expect(old.unparsedCount, 0);
      expect(old.unparsedRows, 0, reason: '既有行为不回归');
      expect(CashImportResult.fromJson('boom').unparsed, isEmpty);
      expect(CashImportResult.fromJson('boom').unparsedCount, 0);
    });
  });

  group('P2-交易48 账户卡当日盈亏来源（widget）', () {
    // 页面按「今天」判过期，测试用运行时的今天/昨天构造，避免固定日期随日历失真。
    MockClient mock(Map<String, dynamic> account) => MockClient((request) async {
          final path = request.url.path;
          if (path == '/api/v1/trading/portfolio') return _json(_portfolioJson);
          if (path == '/api/v1/trading/positions') return _json([_positionJson()]);
          if (path == '/api/v1/trading/account') return _json(account);
          if (path == '/api/v1/trading/watchlist') return _json([]);
          if (path == '/api/v1/trading/sold') return _json([]);
          if (path == '/api/v1/trading/buy-points') return _json([]);
          if (path == '/api/v1/trading/sold/score') return _json([]);
          if (path == '/api/v1/trading/trades') return _json([]);
          if (path == '/api/v1/trading/reviews') return _json([]);
          if (path == '/api/v1/trading/equity-curve') {
            return _json({'points': [], 'skippedDays': 0, 'startDate': '', 'endDate': ''});
          }
          return http.Response('not found', 404);
        });

    Map<String, dynamic> account({required double todayPnl, String? source, String? date}) => {
          'assets': 110504.88, 'cash': 292.88, 'available': 292.88, 'withdrawable': 292.88,
          'marketValue': 110212.00, 'pnl': 15235.55, 'todayPnl': todayPnl,
          'snapshotDate': date ?? _ymd(DateTime.now()),
          'todayPnlSource': ?source,
        };

    testWidgets('券商口径 + 快照当天 → 当日盈亏下小字「券商口径 · MM-dd」', (tester) async {
      final today = DateTime.now();
      final md = '${today.month.toString().padLeft(2, '0')}-${today.day.toString().padLeft(2, '0')}';
      await _pumpTrading(tester,
          ApiService(baseUrl: 'http://test', client: mock(account(todayPnl: -1759.0, source: 'broker'))));
      expect(find.text('券商口径 · $md'), findsOneWidget);
    });

    testWidgets('系统计算 + 快照不是今天 → 缀「（已过期）」', (tester) async {
      final y = DateTime.now().subtract(const Duration(days: 1));
      final md = '${y.month.toString().padLeft(2, '0')}-${y.day.toString().padLeft(2, '0')}';
      await _pumpTrading(tester,
          ApiService(baseUrl: 'http://test', client: mock(account(todayPnl: -2837.0, source: 'calc', date: _ymd(y)))));
      expect(find.text('系统计算 · $md（已过期）'), findsOneWidget);
    });

    testWidgets('当日盈亏 0 / 来源缺失 / 来源认不出 → 都不标（宁可不说）', (tester) async {
      await _pumpTrading(tester,
          ApiService(baseUrl: 'http://test', client: mock(account(todayPnl: 0.0, source: 'broker'))));
      expect(find.textContaining('券商口径'), findsNothing, reason: '当日盈亏 0 不标');

      await _pumpTrading(tester,
          ApiService(baseUrl: 'http://test', client: mock(account(todayPnl: -1759.0))));
      expect(find.textContaining('券商口径'), findsNothing, reason: '旧后端无 todayPnlSource 不标');
      expect(find.textContaining('系统计算'), findsNothing);

      await _pumpTrading(tester,
          ApiService(baseUrl: 'http://test', client: mock(account(todayPnl: -1759.0, source: 'void-calc'))));
      expect(find.textContaining('void-calc'), findsNothing, reason: '认不出的来源不原样甩给用户');
      expect(find.textContaining('券商口径'), findsNothing);
    });
  });

  group('P2-交易43 历史成交导入「没看懂的行」（widget）', () {
    const tdxText = '''
成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
20260912        14:52:56        600000          浦发银行        买入            200.00         9.20000000         1840.00         151117          69351117                1840.00          A000000001
''';
    const unparsedLine = '第 3 行「2026080X …」：成交日期「2026080X」不是 yyyyMMdd 格式';

    testWidgets('unparsed 非空 → 橙色警示 + 明细可收起/展开（Dialog + Tab inline 各一份）', (tester) async {
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
            'unparsed': [unparsedLine], 'unparsedCount': 1,
            'anchor': {'positionsReplace': '2026-09-10', 'cashImport': null,
                       'known': true, 'holdingsKnown': true, 'anchorDate': '2026-09-10'},
            if (dry)
              'plan': {'new': 1, 'merged': 0, 'skipped': 0, 'nonTrades': 0,
                       'wouldReject': 0, 'anchorKnown': true, 'syncMode': 'append'},
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
      await tester.pumpAndSettle();

      // 预检阶段就可见（默认展开：丢数据第一眼可见）
      expect(find.text('有 1 行没能识别（这些成交没有导入）'), findsOneWidget);
      expect(find.text('· $unparsedLine'), findsOneWidget);

      // 收起 → 明细隐藏、警示还在；再展开
      await tester.tap(find.text('收起'));
      await tester.pumpAndSettle();
      expect(find.text('· $unparsedLine'), findsNothing);
      expect(find.text('有 1 行没能识别（这些成交没有导入）'), findsOneWidget);
      await tester.tap(find.text('看明细'));
      await tester.pumpAndSettle();
      expect(find.text('· $unparsedLine'), findsOneWidget);

      // 落盘后：Dialog + Tab inline 各一份
      await tester.tap(find.text('确认导入'));
      await tester.pumpAndSettle();
      expect(find.text('有 1 行没能识别（这些成交没有导入）'), findsNWidgets(2));
      expect(find.text('· $unparsedLine'), findsNWidgets(2));
    });

    testWidgets('unparsed 缺失（旧后端）→ 不显示任何警示（行为与现在一致）', (tester) async {
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
          return _json({'imported': 1, 'updated': 0, 'skipped': 0, 'nonTrades': 0,
                        'lines': [], 'syncMode': 'append', 'rejected': [],
                        'dryRun': request.url.queryParameters['dryRun'] == 'true'});
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);
      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入历史成交'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, tdxText);
      await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('导入')));
      await tester.pumpAndSettle();

      expect(find.textContaining('没能识别'), findsNothing);
      expect(find.textContaining('没有导入'), findsNothing);
    });
  });

  group('P2-交易43 资金股份导入「没认出来的明细行」（widget）', () {
    testWidgets('unparsedRows>0 → 成功提示追加「另有 N 行…精确成本本次没更新」', (tester) async {
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
        if (path == '/api/v1/trading/equity-curve') {
          return _json({'points': [], 'skippedDays': 0, 'startDate': '', 'endDate': ''});
        }
        if (path == '/api/v1/trading/imports/cash') {
          return _json({'cash': 1381.93, 'assets': 77850.0, 'updatedCost': 2, 'unparsedRows': 3});
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入资金'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, '资金导出文本');
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
      await tester.pumpAndSettle();

      expect(find.textContaining('资金已更新：现金 ¥1381.93 · 成本更新 2 只'), findsOneWidget);
      expect(find.textContaining('另有 3 行明细没认出来，这些持仓的精确成本本次没更新'), findsOneWidget);
    });

    testWidgets('unparsedRows 缺失（旧后端）/为 0 → 提示维持原样', (tester) async {
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
        if (path == '/api/v1/trading/equity-curve') {
          return _json({'points': [], 'skippedDays': 0, 'startDate': '', 'endDate': ''});
        }
        if (path == '/api/v1/trading/imports/cash') {
          return _json({'cash': 1381.93, 'assets': 77850.0, 'updatedCost': 2});
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入资金'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, '资金导出文本');
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
      await tester.pumpAndSettle();

      expect(find.textContaining('资金已更新：现金 ¥1381.93 · 成本更新 2 只'), findsOneWidget);
      expect(find.textContaining('没认出来'), findsNothing);
    });
  });

  group('P2-交易83 清仓股导入丢行明细（widget：用户可见面）', () {
    const soldUnparsedLine = '第 3 行「60021\t截断代码\t20260731\t…」：代码「60021」不是 6 位数字';

    MockClient mock(Map<String, dynamic> soldImport) => MockClient((request) async {
          if (request.url.path == '/api/v1/trading/sold/import') return _json(soldImport);
          return _tradingHandler(request);
        });

    Future<void> runSoldImport(WidgetTester tester, Map<String, dynamic> soldImport) async {
      await _pumpTrading(tester,
          ApiService(baseUrl: 'http://test', client: mock(soldImport)));
      await tester.tap(find.byKey(const Key('posFilter3')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入清仓'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, '清仓导出文本');
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
      await tester.pumpAndSettle();
    }

    testWidgets('有丢行 → 回执说清「可能少了几只」+ 逐条明细（行号/原文/原因）可收起/展开', (tester) async {
      await runSoldImport(tester,
          {'imported': 3, 'unparsed': [soldUnparsedLine], 'unparsedCount': 1});

      expect(find.text('清仓股导入 3 笔'), findsOneWidget);
      expect(find.text('有 1 行没能识别（你的清仓股可能少了几只）'), findsOneWidget);
      expect(find.text('· $soldUnparsedLine'), findsOneWidget, reason: '逐条明细含行号 + 原文 + 原因');

      await tester.tap(find.text('收起'));
      await tester.pumpAndSettle();
      expect(find.text('· $soldUnparsedLine'), findsNothing);
      expect(find.text('有 1 行没能识别（你的清仓股可能少了几只）'), findsOneWidget,
          reason: '收起只藏明细，警示还在');

      await tester.tap(find.text('看明细'));
      await tester.pumpAndSettle();
      expect(find.text('· $soldUnparsedLine'), findsOneWidget);
    });

    testWidgets('无丢行 / 旧后端缺字段 → 只回原话，不显示任何警示', (tester) async {
      await runSoldImport(tester, {'imported': 3});
      expect(find.text('清仓股导入 3 笔'), findsOneWidget);
      expect(find.textContaining('没能识别'), findsNothing);
      expect(find.textContaining('少了几只'), findsNothing);
    });
  });

  group('P2-交易83 资金股份导入丢行明细（widget：是哪只票的成本没更新）', () {
    const cashUnparsedLine = '第 4 行「这不是明细行 xxx」：证券代码「这不是明细行」不是 6 位数字';

    Future<void> runCashImport(WidgetTester tester, Map<String, dynamic> cashResp) async {
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/trading/imports/cash') return _json(cashResp);
        return _tradingHandler(request);
      });
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: client));
      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('导入资金'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, '资金导出文本');
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
      await tester.pumpAndSettle();
    }

    testWidgets('有明细 → 回执 + 逐条明细、可收起/展开（不再只说「另有 N 行」）', (tester) async {
      await runCashImport(tester, {
        'cash': 1381.93, 'assets': 77850.0, 'updatedCost': 2, 'unparsedRows': 1,
        'unparsed': [cashUnparsedLine], 'unparsedCount': 1,
      });
      expect(find.text('资金已更新：现金 ¥1381.93 · 成本更新 2 只'), findsOneWidget);
      expect(find.text('有 1 行没能识别（这些票的精确成本这次没更新）'), findsOneWidget);
      expect(find.text('· $cashUnparsedLine'), findsOneWidget, reason: '回答「是哪只票的精确成本没更新」');
      expect(find.textContaining('另有 1 行明细没认出来'), findsNothing,
          reason: '有明细就不再退回那句笼统的话');

      await tester.tap(find.text('收起'));
      await tester.pumpAndSettle();
      expect(find.text('· $cashUnparsedLine'), findsNothing);
      expect(find.text('有 1 行没能识别（这些票的精确成本这次没更新）'), findsOneWidget);
    });

    testWidgets('unparsedRows 缺失但明细在（只加字段的新后端）→ 仍显示明细', (tester) async {
      await runCashImport(tester, {
        'cash': 1381.93, 'assets': 77850.0, 'updatedCost': 2,
        'unparsed': [cashUnparsedLine], 'unparsedCount': 1,
      });
      expect(find.text('· $cashUnparsedLine'), findsOneWidget);
      expect(find.text('有 1 行没能识别（这些票的精确成本这次没更新）'), findsOneWidget);
    });

    testWidgets('明细为空数组、只有 unparsedRows → 维持原笼统提示（不编造明细、不弹空框）', (tester) async {
      await runCashImport(tester, {
        'cash': 1381.93, 'assets': 77850.0, 'updatedCost': 2,
        'unparsedRows': 3, 'unparsed': <String>[],
      });
      expect(find.textContaining('另有 3 行明细没认出来，这些持仓的精确成本本次没更新'), findsOneWidget);
      expect(find.textContaining('没能识别'), findsNothing);
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

  group('当日口径（GET /trading/positions/daily，2026-09-14）', () {
    var pumpSeq = 0;
    /// 每次用新 key 强制重建 State（pumpWidget 复用旧 State 时不会重新加载，
    /// 同一测试里换 mock 再 pump 就测不到新数据）。
    Future<void> pumpDaily(WidgetTester tester, ApiService api) async {
      await tester.binding.setSurfaceSize(const Size(1800, 900));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(
          home: Scaffold(body: TradingPage(key: ValueKey('daily-${pumpSeq++}'), api: api))));
      await tester.pumpAndSettle();
    }

    /// 当日端点响应：positions 与原端点同形状，daily 逐票当日盈亏/涨跌幅/仓位占比。
    Map<String, dynamic> dailyJson({
      Object? positionRatio = 33.27,
      Object? todayPnl = 756.00,
      Object? dayChangePct = 2.14,
      Object? totalPositionRatio = 62.24,
      Object? cashRatio = 37.76,
      List<Object?> notes = const [],
    }) =>
        {
          'positions': [_positionJson()],
          'daily': {
            '600123': {
              'todayPnl': todayPnl,
              'yesterdayClose': 25.55,
              'dayChangePct': dayChangePct,
              'positionRatio': positionRatio,
            },
          },
          'totalAssets': 81453.53,
          'totalMarketValue': 50696.00,
          'cashBalance': 30757.53,
          'totalPositionRatio': totalPositionRatio,
          'cashRatio': cashRatio,
          'notes': notes,
        };

    MockClient dailyMock(Object dailyBody, {int dailyStatus = 200}) => MockClient((request) async {
          final path = request.url.path;
          if (path == '/api/v1/trading/positions/daily') {
            return dailyStatus == 200 ? _json(dailyBody) : http.Response('boom', dailyStatus);
          }
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

    /// 定位持仓表（按首列名称+代码 600123 认，避免误取其它 Tab 的表格）。
    /// m3（2026-10-07）：首列已合并「名称 代码」（到线行还包一层橙条 Container）→ 递归认代码。
    DataTable positionsTable(WidgetTester tester) {
      bool hasCode(Widget c) {
        if (c is Text) return c.data == '600123';
        if (c is Row) return c.children.any((w) => w is Text && w.data == '600123');
        if (c is Container) return c.child != null && hasCode(c.child!);
        return false;
      }

      return tester
          .widgetList<DataTable>(find.byType(DataTable))
          .firstWhere((t) => t.rows.any((r) => r.cells.isNotEmpty && hasCode(r.cells.first.child)));
    }

    // 列序（m3 2026-10-07：名称/代码合并后）：0 名称+代码 / 1 数量 / 2 成本 / 3 现价 / 4 市值 /
    //       5 仓位占比 / 6 当日盈亏 / 7 今日涨跌幅 / 8 盈亏 / 9 盈亏% …
    String cellText(DataTable t, int i) => (t.rows.first.cells[i].child as Text).data!;
    Color? cellColor(DataTable t, int i) => (t.rows.first.cells[i].child as Text).style?.color;

    test('DTO 解析：字段缺失/null/类型不符 → null（不崩，也绝不兜底成 0）', () {
      final r = PositionsDailyResponse.fromJson({
        'positions': [_positionJson()],
        'daily': {
          '600123': {'todayPnl': 756.0, 'dayChangePct': null, 'positionRatio': '33.27'},
          '000001': {'todayPnl': null},
          'BAD': 'not-a-map',
          42: {'todayPnl': 1.0},
        },
        'totalPositionRatio': null,
        'cashRatio': 0,
        'notes': ['002428 云南锗业：缺昨收，这笔卖出的当日盈亏未计入', 42, null, ''],
      });
      expect(r.positions.single.symbol, '600123', reason: 'positions 与原端点同形状，直接复用解析');
      expect(r.daily['600123']!.todayPnl, 756.0);
      expect(r.daily['600123']!.dayChangePct, isNull, reason: '显式 null 保留 null（缺昨收），不写成 0');
      expect(r.daily['600123']!.positionRatio, 33.27, reason: '数字字符串也能读');
      expect(r.daily['000001']!.todayPnl, isNull, reason: '字段缺失 → null');
      expect(r.daily['BAD']!.todayPnl, isNull, reason: '非对象元素不崩');
      expect(r.daily['42']!.todayPnl, 1.0, reason: '非字符串键也能落表');
      expect(r.totalPositionRatio, isNull);
      expect(r.cashRatio, 0.0, reason: '0 是真实值（总资产为 0 时才是 null），两者不能混');
      expect(r.notes, ['002428 云南锗业：缺昨收，这笔卖出的当日盈亏未计入', '42'],
          reason: '非字符串转字符串、null/空串剔除');

      // 整体畸形 / 旧后端：一律空，不崩
      final junk = PositionsDailyResponse.fromJson('boom');
      expect(junk.positions, isEmpty);
      expect(junk.daily, isEmpty);
      expect(junk.totalPositionRatio, isNull);
      expect(junk.notes, isEmpty);
      final weird = PositionsDailyResponse.fromJson({'daily': 'x', 'notes': 'oops', 'positions': 5});
      expect(weird.daily, isEmpty);
      expect(weird.notes, isEmpty);
      expect(weird.positions, isEmpty);
      expect(PositionsDailyResponse.fromJson(null).totalPositionRatio, isNull);
    });

    testWidgets('正常渲染三样（当日盈亏 / 今日涨跌幅 / 仓位占比）+ 顶部「仓位 · 现金」一行', (tester) async {
      await pumpDaily(tester,
          ApiService(baseUrl: 'http://test', client: dailyMock(dailyJson())));
      // m6：金额默认掩码 → 先点 👁 显形
      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();
      final t = positionsTable(tester);
      expect(cellText(t, 5), '33.27%', reason: '仓位占比');
      expect(cellText(t, 6), '756.00', reason: '当日盈亏（金额）');
      expect(cellText(t, 7), '2.14%', reason: '今日涨跌幅');
      expect(find.text('仓位 62.24% · 现金 37.76%'), findsOneWidget, reason: '顶部总仓位一行');
    });

    testWidgets('涨跌着色＝红涨绿亏（token 含 darkRed），不是绿涨红跌', (tester) async {
      await pumpDaily(tester,
          ApiService(baseUrl: 'http://test', client: dailyMock(dailyJson(todayPnl: 756.0, dayChangePct: 2.14))));
      var t = positionsTable(tester);
      expect(cellColor(t, 6), AppColors.darkRed, reason: '赚=红');
      expect(cellColor(t, 7), AppColors.darkRed, reason: '涨=红');

      await pumpDaily(tester,
          ApiService(baseUrl: 'http://test', client: dailyMock(dailyJson(todayPnl: -321.50, dayChangePct: -1.28))));
      // m6：金额默认掩码 → 先点 👁 显形（重新 pump 后状态重置）
      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();
      t = positionsTable(tester);
      expect(cellText(t, 6), '-321.50');
      expect(cellText(t, 7), '-1.28%');
      expect(cellColor(t, 6), AppColors.darkGreen, reason: '亏=绿');
      expect(cellColor(t, 7), AppColors.darkGreen, reason: '跌=绿');
      expect(cellColor(t, 5), AppColors.darkGrey3, reason: '仓位占比是中性灰，不借涨跌色');
    });

    testWidgets('三项 null → 全「—」且不崩；绝不渲染成 0 / 0.00%', (tester) async {
      await pumpDaily(
          tester,
          ApiService(
              baseUrl: 'http://test',
              client: dailyMock(dailyJson(
                  positionRatio: null,
                  todayPnl: null,
                  dayChangePct: null,
                  totalPositionRatio: null,
                  cashRatio: null))));
      // m6：金额默认掩码 → 先点 👁 显形（当日三列 null → 「—」不属金额，不受掩码影响）
      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();
      final t = positionsTable(tester);
      expect(cellText(t, 5), '—');
      expect(cellText(t, 6), '—');
      expect(cellText(t, 7), '—');
      expect(cellText(t, 8), '160.00', reason: '累计浮盈列是另一口径，不受当日 null 影响');
      expect(cellColor(t, 6), AppColors.darkGrey5, reason: '「—」不借涨跌色');
      expect(find.text('0.00'), findsNothing, reason: 'null 不许渲染成 0');
      expect(find.text('0.00%'), findsNothing, reason: 'null 不许渲染成 0.00%');
      expect(find.textContaining('仓位 '), findsNothing, reason: '总仓位 null → 整段不显示');
      expect(find.textContaining('现金 '), findsNothing);
      expect(find.textContaining('持仓 1 只'), findsOneWidget, reason: '页面照常');
    });

    testWidgets('该票不在 daily 里（新票 / 降级残留）→ 三列「—」', (tester) async {
      await pumpDaily(
          tester,
          ApiService(
              baseUrl: 'http://test',
              client: dailyMock({
                'positions': [_positionJson()],
                'daily': <String, dynamic>{},
                'totalPositionRatio': 62.24,
                'cashRatio': 37.76,
                'notes': <String>[],
              })));
      final t = positionsTable(tester);
      expect(cellText(t, 5), '—');
      expect(cellText(t, 6), '—');
      expect(cellText(t, 7), '—');
      expect(find.text('仓位 62.24% · 现金 37.76%'), findsOneWidget,
          reason: '整体比例仍可用（个股缺条目不影响总仓位）');
    });

    testWidgets('当日端点失败（500 / 404 旧后端）→ 降级回旧端点，持仓主数据照常显示', (tester) async {
      for (final status in [500, 404]) {
        final requests = <String>[];
        final client = MockClient((request) async {
          final path = request.url.path;
          requests.add(path);
          if (path == '/api/v1/trading/positions/daily') return http.Response('boom', status);
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
        await pumpDaily(tester, ApiService(baseUrl: 'http://test', client: client));

        expect(requests, contains('/api/v1/trading/positions/daily'), reason: '先打当日端点');
        expect(requests, contains('/api/v1/trading/positions'), reason: '失败后降级回旧端点（status=$status）');
        expect(find.textContaining('持仓 1 只'), findsOneWidget, reason: '增强项失败不拖垮持仓主数据');
        expect(find.text('600123'), findsOneWidget);
        expect(find.textContaining('加载失败'), findsNothing, reason: '不整页错误态');
        final t = positionsTable(tester);
        expect(cellText(t, 6), '—', reason: '当日盈亏回落「—」');
        expect(find.textContaining('仓位 '), findsNothing);
      }
    });

    testWidgets('notes 非空 → 一行轻提示（橙，不是错误红；口语化无第三视角标签）', (tester) async {
      await pumpDaily(
          tester,
          ApiService(
              baseUrl: 'http://test',
              client: dailyMock(dailyJson(
                  notes: ['002428 云南锗业：缺昨收，这笔卖出（100 股）的当日盈亏未计入——当日盈亏偏小']))));
      final finder = find.textContaining('有几笔今天的盈亏还没算全');
      expect(finder, findsOneWidget);
      final text = tester.widget<Text>(finder);
      expect(text.data, contains('002428 云南锗业'));
      expect(text.data, contains('当日盈亏偏小'), reason: '如实提示偏小，不装作没事');
      expect(text.style?.color, AppColors.darkGrey3, reason: '轻提示不是错误红');
      // 第一原则 B1：无第三视角标签，也不加「阿呆说：」这类引述前缀（加了就是记录视角）——
      // 阿呆是对话的另一方，直接说话即可。
      for (final banned in ['系统', '数据', '记录', '接口', '阿呆说', '提示：']) {
        expect(text.data, isNot(contains(banned)), reason: '第一原则：无「$banned」这类第三视角/引述标签');
      }
    });

    testWidgets('notes 缺失/空 → 不显示提示（不刷存在感）', (tester) async {
      await pumpDaily(tester,
          ApiService(baseUrl: 'http://test', client: dailyMock(dailyJson())));
      expect(find.textContaining('有几笔今天的盈亏还没算全'), findsNothing);
    });
  });

  // ── m3（2026-10-07）：持仓表对齐原型 —— 名称/代码合并 · 近 20 日走势 · 到线行标 · 筛选带计数 ──
  //
  // 原型口径（trading-web-full.html 持仓主屏）：首列「名称 代码」（名在前）、「近 20 日」列
  // 54×14 迷你走势（走红跌绿＝首尾比较）、到线行浅橙 + 行首橙条（tr.on）、chips 带计数。
  group('m3 持仓表对齐（2026-10-07）', () {
    /// kline mock：返 N 根收盘（逐日 +0.3），其余回落基础 handler。
    MockClient sparkMock({int bars = 20}) => MockClient((request) async {
          if (request.url.path == '/api/v1/trading/kline') {
            return _json({
              'symbol': '600123',
              'window': 20,
              'candles': [
                for (var i = 0; i < bars; i++)
                  {'date': '2026-09-${(i + 1).toString().padLeft(2, '0')}', 'close': 20.0 + i * 0.3},
              ],
              'marks': <Object>[],
            });
          }
          return _tradingHandler(request);
        });

    testWidgets('近 20 日：拿到 K 线 → 迷你走势（Key 锚，54×14 画布）', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: sparkMock()));
      expect(find.byKey(const Key('spark_600123')), findsOneWidget);
    });

    testWidgets('近 20 日：拿不到（旧后端 404）→ 不渲染走势、不编形状；表格照常', (tester) async {
      // 必须独立成条：若在同一 testWidgets 里先有后无地重 pump，State 会复用、
      // 拿到过的走势留在缓存里继续渲染（这恰是产品要的：缓存的是真实数据，404 只是没重拉）。
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));
      expect(find.byKey(const Key('spark_600123')), findsNothing);
      expect(find.textContaining('持仓 1 只'), findsOneWidget);
    });

    testWidgets('首列合并「名称 代码」（名在前）；「近 20 日」列头在场', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));
      final row = tester.widget<Row>(find.ancestor(
        of: find.text('600123'),
        matching: find.byType(Row),
      ).first);
      expect((row.children[0] as Text).data, '立昂微', reason: '名称在前（原型 .wd-name）');
      expect((row.children[2] as Text).data, '600123', reason: '代码在后（原型 .wd-code）');
      expect(find.text('近 20 日'), findsOneWidget, reason: '近 20 日列头');
      expect(find.text('代码 / 名称'), findsOneWidget);
    });

    testWidgets('到线行标：现价 ≤ 生效止损 → 行首橙条 Key（原型 tr.on）', (tester) async {
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/trading/positions') {
          return _json([_positionJson(extra: {'effectiveStopLoss': 27.0})]);
        }
        return _tradingHandler(request);
      });
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: client));
      expect(find.byKey(const Key('online_600123')), findsOneWidget);
    });

    testWidgets('不到线没有行标；筛选 chips 带计数', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));
      expect(find.byKey(const Key('online_600123')), findsNothing, reason: '26.10 > 22.80 未到线');
      // m4：左导航子项与 chips 同文（「持仓 1」×2）→ Key 锚定 chips 内文本，裸文本断言会双命中
      String chipText(String key) => tester
          .widget<Text>(find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text)).first)
          .data!;
      expect(chipText('posFilter0'), '全部 1');
      expect(chipText('posFilter1'), '持仓 1');
      expect(chipText('posFilter2'), '自选 0');
      expect(chipText('posFilter3'), '清仓 0');
    });
  });

  // ── P2-交易72（2026-10-05）：今天没买卖也有落点（「今天没动」/「想动，没动」）──
  //
  // 用户原话：「那我今天没有买卖 怎么告诉你呢 你还在等我的数据」——系统在等一个他**没有地方填**的状态。
  // 本条补的是**当天事后**的回填入口（事前路径＝前晚写「明天不动」，RFC 20261003 已有）。
  // 判据两条：① 点 chip → 请求 → **如实回执**；② 重复点**不重复落**（本地已知就不发请求，且如实说
  // 「已经记着了」）；后端 `recorded=false` 时同样不许假报一次落库。
  group('P2-交易72 今天没动 / 想动，没动', () {
    /// 写请求记账：落了什么、落了几次。
    MockClient statusMock({
      required List<Map<String, dynamic>> writes,
      String dayStatus = 'NO_TRADE',
      bool recorded = true,
      Map<String, dynamic>? initialPlan,
    }) {
      final today = _ymd(DateTime.now());
      return MockClient((req) async {
        if (req.url.path == '/api/v1/trading/plans/$today/status') {
          writes.add(jsonDecode(req.body) as Map<String, dynamic>);
          return _json({
            'date': today,
            'note': '',
            'dayStatus': dayStatus,
            'recorded': recorded,
            'items': [],
          });
        }
        if (initialPlan != null &&
            req.method == 'GET' &&
            req.url.path == '/api/v1/trading/plans/$today') {
          return _json(initialPlan);
        }
        return _tradingHandler(req);
      });
    }

    testWidgets('点「今天没动」→ 写今天自己的记录 + 如实回执；重复点不再落一次', (tester) async {
      final writes = <Map<String, dynamic>>[];
      final api = ApiService(baseUrl: 'http://test', client: statusMock(writes: writes));

      await _pumpTrading(tester, api);
      expect(find.text('今天没买卖的话，点一下就行——没动也是一天的完整记录。'), findsOneWidget);

      await tester.ensureVisible(find.text('今天没动'));
      await tester.tap(find.text('今天没动'));
      await tester.pumpAndSettle();

      expect(writes.length, 1, reason: '点一次只落一次');
      expect(writes.first['status'], 'NO_TRADE', reason: '落的是「今天没动」，不是别的状态');
      expect(find.text('记下了：今天没动。'), findsOneWidget);
      expect(find.text('今天记的是：没动'), findsOneWidget);

      await tester.tap(find.text('今天没动'));
      await tester.pumpAndSettle();

      expect(writes.length, 1, reason: '重复点同一个 chip 不得再落一次（本地已知连请求都不发）');
      expect(find.text('今天已经记着了：没动。'), findsOneWidget);
    });

    testWidgets('点「想动，没动」→ 落的是另一种状态；后端说没写盘时如实说「已经记着了」', (tester) async {
      final writes = <Map<String, dynamic>>[];
      // 后端幂等命中（另一端刚记过同一个状态）→ recorded=false，前端**不许**假报「记下了」。
      final api = ApiService(
          baseUrl: 'http://test',
          client: statusMock(
              writes: writes, dayStatus: 'WANTED_NOT_ACTED', recorded: false));

      await _pumpTrading(tester, api);
      await tester.ensureVisible(find.text('想动，没动'));
      await tester.tap(find.text('想动，没动'));
      await tester.pumpAndSettle();

      expect(writes.length, 1);
      expect(writes.first['status'], 'WANTED_NOT_ACTED', reason: '「想动，没动」与「今天没动」是两种状态，不得折叠');
      expect(find.text('今天已经记着了：想动，但没动。'), findsOneWidget,
          reason: '后端 recorded=false → 必须如实说「早就记着了」，不许假报落库');
      expect(find.text('今天记的是：想动，但没动'), findsOneWidget);
    });

    testWidgets('进页带上今天已记的状态（不编造、不再问一遍）', (tester) async {
      final api = ApiService(
          baseUrl: 'http://test',
          client: statusMock(
              writes: <Map<String, dynamic>>[],
              initialPlan: {
                'date': _ymd(DateTime.now()),
                'note': '',
                'dayStatus': 'NO_TRADE',
                'items': [],
              }));

      await _pumpTrading(tester, api);

      expect(find.text('今天记的是：没动'), findsOneWidget);
      expect(find.text('今天没买卖的话，点一下就行——没动也是一天的完整记录。'), findsNothing);
    });
  });

  // ══════════════════════════════════════════════════════════════════════
  // m4（2026-10-07）：顶部横 Tab → 左侧竖导航（品牌 / 6 项 / 激活区子项 / 左下脚注）。
  // 子项交互规则：可点 ⇔ 该区有真实视图切换（持仓=切筛选、分析=切粒度）；
  // 账/案例子项是目录型标注（对应内容屏内同屏，不可点、不高亮）。
  // ══════════════════════════════════════════════════════════════════════
  group('m4 左导航骨架', () {
    testWidgets('品牌 + 六个分区都在；子项只出在激活区（持仓激活 → 四个筛选子项，计数同源）', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));

      // 品牌（原型 .wd-brand）。'交易' 在 PageHeader 也有一份 → 用 'AdaiOS' 锚品牌
      expect(find.text('AdaiOS'), findsOneWidget);
      for (var i = 0; i < 6; i++) {
        expect(find.byKey(Key('navItem$i')), findsOneWidget, reason: '六个分区 navItem$i 都应在');
      }
      // 默认激活持仓 → 四个子项，计数与 mock 数据一致（持仓 1 / 自选 0 / 清仓 0）
      expect(find.descendant(of: find.byKey(const Key('navSub_pos0')), matching: find.text('全部 1')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('navSub_pos1')), matching: find.text('持仓 1')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('navSub_pos2')), matching: find.text('自选 0')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('navSub_pos3')), matching: find.text('清仓 0')), findsOneWidget);
      // 未激活区的子项不渲染
      expect(find.byKey(const Key('navSub_acc0')), findsNothing);
      expect(find.byKey(const Key('navSub_ana0')), findsNothing);
      expect(find.byKey(const Key('navSub_case0')), findsNothing);
    });

    testWidgets('点导航切区：子项跟着换（账 3 项 / 分析 3 项 / 案例 2 项 / 规则无子项）', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));

      await tester.tap(find.byKey(const Key('navItem1')));
      await tester.pumpAndSettle();
      expect(find.descendant(of: find.byKey(const Key('navSub_acc0')), matching: find.text('资金')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('navSub_acc1')), matching: find.text('流水')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('navSub_acc2')), matching: find.text('对账')), findsOneWidget);
      expect(find.byKey(const Key('navSub_pos0')), findsNothing, reason: '离开持仓区，其子项应收起');

      await tester.tap(find.byKey(const Key('navItem2')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('navSub_ana0')), findsOneWidget);
      expect(find.byKey(const Key('navSub_ana1')), findsOneWidget);
      expect(find.byKey(const Key('navSub_ana2')), findsOneWidget);

      await tester.tap(find.byKey(const Key('navItem4')));
      await tester.pumpAndSettle();
      expect(find.descendant(of: find.byKey(const Key('navSub_case0')), matching: find.text('等你认 0')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('navSub_case1')), matching: find.text('已收下 0')), findsOneWidget);

      await tester.tap(find.byKey(const Key('navItem3')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('navSub_ana0')), findsNothing, reason: '规则区没有子项');
      expect(find.byKey(const Key('navSub_case0')), findsNothing);
    });

    testWidgets('持仓子项点击 = 切筛选（与 chips 同源）', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));

      await tester.tap(find.byKey(const Key('navSub_pos2')));
      await tester.pumpAndSettle();
      expect(find.text('暂无自选股——导入通达信自选导出，阿呆帮你盯买点'), findsOneWidget, reason: '点是「自选 0」→ 切到自选视图');
      expect(find.text('立昂微'), findsNothing);

      await tester.tap(find.byKey(const Key('navSub_pos3')));
      await tester.pumpAndSettle();
      expect(find.text('暂无清仓记录——导入通达信清仓导出，阿呆对照规则给你判对错'), findsOneWidget, reason: '点是「清仓 0」→ 切到清仓视图');

      await tester.tap(find.byKey(const Key('navSub_pos0')));
      await tester.pumpAndSettle();
      expect(find.text('立昂微'), findsOneWidget, reason: '回「全部」→ 混合表里有持仓行');
    });

    testWidgets('分析子项 ↔ 区内部粒度双向同步（这一笔/这只票/这一段）', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));

      await tester.tap(find.byKey(const Key('navItem2')));
      await tester.pumpAndSettle();
      // 默认「这一段」（global）：两种目标输入框都不该在
      expect(find.byKey(const Key('analysisSymbol')), findsNothing);
      expect(find.byKey(const Key('analysisRound')), findsNothing);

      await tester.tap(find.byKey(const Key('navSub_ana0')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('analysisRound')), findsOneWidget, reason: '「这一笔」= 单笔粒度 → 单笔目标输入框出现');

      await tester.tap(find.byKey(const Key('navSub_ana1')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('analysisSymbol')), findsOneWidget, reason: '「这只票」= 单标的粒度');
      expect(find.byKey(const Key('analysisRound')), findsNothing);

      await tester.tap(find.byKey(const Key('navSub_ana2')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('analysisSymbol')), findsNothing, reason: '「这一段」= 全局粒度');
    });

    testWidgets('左下脚注随区切换（持仓给账实实况、其它区给屏相关文案）', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));

      // 持仓：integrity 未给（mock 无该路由）→ 「账实未知」；日期取账户快照日兜底
      expect(find.text('账实未知'), findsOneWidget);
      expect(find.text('最近导入 08-16'), findsOneWidget);

      await tester.tap(find.byKey(const Key('navItem3')));
      await tester.pumpAndSettle();
      expect(find.text('看的是事实'), findsOneWidget);
      expect(find.text('不是预测'), findsOneWidget);
      expect(find.text('账实未知'), findsNothing);

      await tester.tap(find.byKey(const Key('navItem4')));
      await tester.pumpAndSettle();
      expect(find.text('案例是规则的出口'), findsOneWidget);
      expect(find.text('它从你的记录里长'), findsOneWidget);
    });

    testWidgets('「全部」= 持仓 + 自选混合表：自选轻行（名字 + 「自选」标），缺列不编数', (tester) async {
      final api = ApiService(
          baseUrl: 'http://test',
          client: MockClient((request) async {
            if (request.url.path == '/api/v1/trading/watchlist') {
              return _json([
                {'symbol': '600999', 'name': '招商银行'},
              ]);
            }
            return _tradingHandler(request);
          }));
      await _pumpTrading(tester, api);

      // 表头两计数（原型「全部」屏）：持仓 + 自选
      expect(find.text('持仓 1 只 · 自选 1 只'), findsOneWidget);
      // 自选轻行：名字 + 代码 + 「自选」标（一眼分清不是持仓行）
      expect(find.text('招商银行'), findsOneWidget);
      expect(find.text('600999'), findsOneWidget);
      expect(find.text('自选'), findsOneWidget);
      // chips 计数同源
      expect(find.descendant(of: find.byKey(const Key('posFilter0')), matching: find.text('全部 2')), findsOneWidget);

      // 切「持仓」→ 自选轻行不再混在表里
      await tester.tap(find.byKey(const Key('posFilter1')));
      await tester.pumpAndSettle();
      expect(find.text('招商银行'), findsNothing);
    });
  });

  // ══════════════════════════════════════════════════════════════════════
  // m5（2026-10-07 · 原型 .wd-strip + .wd-rail）：上方三坨（账户卡 / 盈亏条 /
  // 今天没动）收成一条 6 格状态条（总资产 · 当日 · 总盈亏 · 市值 · 到线 · 账实）
  // + 右栏固定三卡（阿呆说 / 今天 / 三条口径）。
  // ══════════════════════════════════════════════════════════════════════
  group('m5 状态条 6 格 + 右栏三卡', () {
    testWidgets('六格值（Key 锚）：总资产/当日/总盈亏/市值/到线/账实 + 本金引导 note', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));
      // m6：金额默认掩码 → 先点 👁 显形（掩码态断言在 m6 组）
      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();

      String cell(String key) => tester.widget<Text>(find.byKey(Key(key))).data!;
      expect(cell('stripAssets'), '¥110,504.88');
      expect(cell('stripToday'), '¥0.00', reason: 'mock todayPnl=0 且无 pnl/periods → 不给比例后缀（不编 0%）');
      expect(cell('stripTotalPnl'), '—', reason: 'principal 缺省 = 0 → 不给误导数值');
      expect(find.text('还没记过转入/转出'), findsOneWidget, reason: '总盈亏格 note 把「本金从哪来」说清楚');
      expect(cell('stripMarketValue'), '¥110,212.00');
      expect(cell('stripOnline'), '0 只', reason: 'mock 持仓未到线');
      expect(cell('stripIntegrity'), '—', reason: 'integrity 404（mock 无路由）→ 不编「对上了」');
    });

    testWidgets('右栏三卡在场：阿呆说（账句 + tail）· 今天 · 三条口径', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));

      expect(find.text('阿呆说'), findsOneWidget);
      expect(find.text('今天'), findsOneWidget);
      expect(find.text('三条口径'), findsOneWidget);
      // 阿呆说：integrity 拿不到 → 账句不编「对上了」；1 只持仓未到线 → tail 数出来
      expect(find.text('对账还没取到。'), findsOneWidget);
      expect(find.text('1 只都没有到线。'), findsOneWidget);
      // 今天卡：没记过 → 引导句 + 两个回填 chips（P2-交易72 数据在右栏照常渲染）
      expect(find.text('今天没买卖的话，点一下就行——没动也是一天的完整记录。'), findsOneWidget);
      expect(find.text('今天没动'), findsOneWidget);
      // 三条口径（原型全文；「每个数字点得进去」的钻取交互属后续批次）
      expect(find.text('数量与成本打码 · 现价与线不打码 · 每个数字点得进去'), findsOneWidget);
    });

    testWidgets('到线点名（破止损）：阿呆说指名 + 状态条计数 + 行首橙条同源', (tester) async {
      final api = ApiService(
          baseUrl: 'http://test',
          client: MockClient((request) async {
            if (request.url.path == '/api/v1/trading/positions') {
              return _json([_positionJson(extra: {'effectiveStopLoss': 27.0})]);
            }
            return _tradingHandler(request);
          }));
      await _pumpTrading(tester, api);

      // 点名句（原型句式「代码 现价 X，破了你的 Y」）；tail 换成「其它没有要动的。」
      expect(find.text('600123 现价 26.10，破了你的 27.00'), findsOneWidget);
      expect(find.text('其它没有要动的。'), findsOneWidget);
      expect(find.text('1 只都没有到线。'), findsNothing);
      expect(tester.widget<Text>(find.byKey(const Key('stripOnline'))).data, '1 只');
      expect(find.byKey(const Key('online_600123')), findsOneWidget, reason: '表格行标与到线计数同源（_onLine）');
    });

    testWidgets('到线点名（到放飞）：现价 ≥ 目标价 → 「到了你的 X」', (tester) async {
      final api = ApiService(
          baseUrl: 'http://test',
          client: MockClient((request) async {
            if (request.url.path == '/api/v1/trading/positions') {
              return _json([_positionJson(extra: {'targetPrice': 25.0})]);
            }
            return _tradingHandler(request);
          }));
      await _pumpTrading(tester, api);

      expect(find.text('600123 现价 26.10，到了你的 25.00'), findsOneWidget);
      expect(find.text('其它没有要动的。'), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const Key('stripOnline'))).data, '1 只',
          reason: '到放飞也算到线（m5 双向口径）');
    });
  });

  // ══════════════════════════════════════════════════════════════════════
  // m6（2026-10-07 · 原型 .wd-eye）：打码——金额与数量默认 ••••（状态条/持仓表/
  // 自证条/流水/批次弹窗/曲线「最新」），现价/止损/涨跌% 不打；
  // 页头 👁「看金额」一处解开全页显形、再点回掩码（不持久化）。
  // 「—」= 没数据，不属金额 → 掩码与缺数据是两回事。
  // ══════════════════════════════════════════════════════════════════════
  group('m6 打码（👁 看金额）', () {
    testWidgets('默认掩码：状态条/持仓表金额与数量 ••••，现价与盈亏% 不打', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));

      String cell(String key) => tester.widget<Text>(find.byKey(Key(key))).data!;
      expect(cell('stripAssets'), '¥••••');
      expect(cell('stripToday'), '¥••••');
      expect(cell('stripTotalPnl'), '—', reason: 'principal=0 → 「—」不属金额，不打（缺数据 vs 掩码）');
      expect(cell('stripMarketValue'), '¥••••');

      // 持仓表：数量/成本/市值/盈亏 打码；现价/止损/盈亏% 不打
      expect(find.text('••••'), findsWidgets, reason: '数量/成本/市值/盈亏四处');
      expect(find.text('200'), findsNothing);
      expect(find.text('160.00'), findsNothing);
      expect(find.text('5220.00'), findsNothing);
      expect(find.text('26.100'), findsOneWidget, reason: '现价不打（口径：现价与止损保留）');
      expect(find.text('22.800'), findsOneWidget, reason: '止损不打');
      expect(find.text('3.16%'), findsOneWidget, reason: '盈亏% 属涨跌，不打');
    });

    testWidgets('点 👁 全页显形 → 再点回掩码（不持久化）', (tester) async {
      await _pumpTrading(tester, ApiService(baseUrl: 'http://test', client: _tradingMock()));
      String cell(String key) => tester.widget<Text>(find.byKey(Key(key))).data!;
      expect(cell('stripAssets'), '¥••••');

      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();
      expect(cell('stripAssets'), '¥110,504.88', reason: '显形：金额恢复');
      expect(find.text('200'), findsOneWidget, reason: '显形：数量恢复');
      expect(find.text('160.00'), findsOneWidget, reason: '显形：盈亏恢复');

      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();
      expect(cell('stripAssets'), '¥••••', reason: '再点回掩码');
      expect(find.text('110,504.88'), findsNothing);
      expect(find.text('200'), findsNothing);
    });

    testWidgets('账页：自证条金额与流水数量/金额掩码，价格/编号/费用不打；👁 显形', (tester) async {
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
            {'id': 't1', 'symbol': '600123', 'name': '立昂微', 'direction': 'BUY',
             'price': 25.3, 'volume': 200, 'amount': 5060.0, 'entryDate': '2026-08-12',
             'tradeTime': '09:41:00', 'fee': 1.23, 'orderId': '69351117'},
          ]);
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('账'));
      await tester.pumpAndSettle();

      expect(find.text('¥••••'), findsWidgets, reason: '自证条：现金/可用/状态条 打码');
      expect(find.text('可取 ¥••••'), findsOneWidget);
      expect(find.text('••••'), findsWidgets, reason: '流水：数量/成交金额/发生金额 掩码');
      expect(find.text('200'), findsNothing);
      expect(find.text('5,060.00'), findsNothing);
      expect(find.text('-5,061.23'), findsNothing);
      expect(find.text('25.300'), findsOneWidget, reason: '价格不打');
      expect(find.text('69351117'), findsOneWidget, reason: '成交编号不打');
      expect(find.text('1.23'), findsOneWidget, reason: '费用不打（非规模信息）');

      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();
      expect(find.text('200'), findsOneWidget, reason: '显形：数量');
      expect(find.text('5,060.00'), findsOneWidget, reason: '显形：成交金额');
      expect(find.text('-5,061.23'), findsOneWidget, reason: '显形：发生金额');
      expect(find.text('¥292.88'), findsWidgets, reason: '显形：现金/可用');
    });

    testWidgets('批次弹窗：默认 •••• / ••••（现价/止损不打）；先 👁 再开 → 原值', (tester) async {
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
          return _json({
            'lots': [
              {'lotId': 'L1', 'symbol': '600123', 'name': '立昂微', 'buyDate': '2026-08-03',
               'volume': 100, 'remaining': 100, 'costPrice': 25.0, 'currentPrice': 26.1,
               'marketValue': 2610.0, 'pnl': 110.0, 'pnlPct': 4.4,
               'stopLossPrice': 22.8, 'stopLossDistancePct': 12.63, 'buyPoint': 'B3', 'role': null,
               'initial': false, 'closed': false, 'realizedPnl': 0.0},
            ],
            'reconcile': [],
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      // 默认掩码打开：剩余/买入 数量掩码；现价/止损 不打
      await tester.ensureVisible(find.text('批次'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('批次'));
      await tester.pumpAndSettle();
      expect(find.text('•••• / ••••'), findsOneWidget, reason: '剩余/买入 掩码');
      expect(find.descendant(of: find.byType(Dialog), matching: find.text('22.800')), findsOneWidget,
          reason: '止损不打');
      expect(find.text('110.00'), findsNothing);
      await tester.tap(
          find.descendant(of: find.byType(Dialog), matching: find.byIcon(Icons.close)));
      await tester.pumpAndSettle();

      // 先 👁 再开 → 原值（弹窗打开时继承页头状态）
      await tester.tap(find.byKey(const Key('revealToggle')));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('批次'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('批次'));
      await tester.pumpAndSettle();
      expect(find.text('100 / 100'), findsOneWidget, reason: '显形：剩余/买入');
      expect(find.text('110.00'), findsOneWidget, reason: '显形：盈亏');
      expect(find.text('25.000'), findsOneWidget, reason: '显形：成本');
    });
  });
}
