import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_app/pages/trading_page.dart';
import 'package:adai_app/services/api_service.dart';

/// K 线/行情链路健康度（GET /api/v1/trading/market-data/health，2026-09-23，P1-交易62）。
///
/// 背景：tdx 数据包滞后 + 腾讯 K 线域名被 WAF 拦 + 东财被限 → 后端整段拿不到行情，
/// 而资金曲线/自选信号/案例匹配都建在它上面。后端新增本端点把这件事搬到用户面前，
/// app 端只消费：**`ok == false` 才出声**，「正常」「拿不到信息」「端点不存在」一律零显示。
///
/// 与 `integrity_degraded_test.dart` 同风格：DTO 防御式解析 + MockClient 驱动页面渲染，
/// 不依赖真实后端。

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

/// 当日复盘聚合（GET /trading/trades?date=）——交易页 initState 会拉，必须给个能解析的壳。
http.Response _dailyOk() => _json({
      'trades': <Object>[],
      'daily': {
        'date': '2026-09-23', 'count': 0, 'buyCount': 0, 'sellCount': 0,
        'buyAmount': 0.0, 'sellAmount': 0.0,
        'sessions': [
          {'name': '早盘', 'range': '09:30-11:30', 'count': 0},
          {'name': '午盘', 'range': '13:00-14:30', 'count': 0},
          {'name': '尾盘', 'range': '14:30-15:00', 'count': 0},
        ],
        'firstTradeTime': null, 'lastTradeTime': null,
      },
    });

class _Backend {
  final Map<String, Future<http.Response> Function(http.Request)> handlers = {};
  Future<http.Response> handle(http.Request req) {
    final h = handlers[req.url.path];
    if (h != null) return h(req);
    // 未 mock 一律 404：等价于「旧后端没有这个端点」，恰好也是降级路径的输入
    return Future.value(_json({'error': 'not mocked'}, status: 404));
  }
}

ApiService _apiFor(_Backend b) =>
    ApiService(baseUrl: 'http://test', client: MockClient(b.handle));

/// 交易页主数据（positions/portfolio）必须成功，否则整页错误态、横幅无从谈起。
void _mockBase(_Backend b) {
  b.handlers['/api/v1/trading/positions'] = (_) async => _json({'positions': <Object>[]});
  b.handlers['/api/v1/trading/portfolio'] = (_) async => _json({
        'totalValue': 0.0, 'totalPnl': 0.0, 'cashBalance': 100000.0, 'positionCount': 0,
      });
  b.handlers['/api/v1/trading/has-activity'] = (_) async =>
      _json({'date': '2026-09-23', 'hasActivity': false});
  b.handlers['/api/v1/trading/account'] = (_) async => _json({
        'assets': 100000.0, 'cash': 50000.0, 'available': 50000.0,
        'withdrawable': 50000.0, 'marketValue': 50000.0, 'pnl': 0.0,
        'todayPnl': 0.0, 'principal': 150000.0,
      });
  b.handlers['/api/v1/trading/trades'] = (req) async =>
      req.method == 'GET' ? _dailyOk() : _json(<Object>[]);
  b.handlers['/api/v1/trading/trade-log'] = (_) async => _json(<Object>[]);
  // 账实横幅默认无差异 → 不渲染，避免它的文案干扰「行情横幅有没有出现」的断言
  b.handlers['/api/v1/trading/integrity'] = (_) async => _json({
        'holdingsKnown': true, 'drift': <Object>[], 'gaps': <Object>[], 'note': '账实一致',
      });
}

void main() {
  group('MarketDataHealthDto 解析（P1-交易62）', () {
    test('正常响应：ok=false + 全字段（note / 时刻 / 连续失败 / 取数链）', () {
      final h = MarketDataHealthDto.fromJson({
        'ok': false,
        'note': '行情取数连续 12 次都没拿到（最近一次失败 09-22 23:38:00 · 600487）'
            '——资金曲线、自选信号、案例匹配可能不全，我在自动重试',
        'lastSuccessAt': '09-22 15:00:00',
        'lastSuccessSource': 'tdx',
        'lastFailureAt': '09-22 23:38:00',
        'consecutiveFailures': 12,
        'lastFailedSymbol': '600487',
        'sources': ['tdx', '腾讯', '东财', '新浪'],
      });

      expect(h.ok, isFalse);
      expect(h.hasIssue, isTrue);
      expect(h.note, contains('自动重试'));
      expect(h.lastSuccessAt, '09-22 15:00:00');
      expect(h.lastSuccessSource, 'tdx');
      expect(h.lastFailureAt, '09-22 23:38:00');
      expect(h.consecutiveFailures, 12);
      expect(h.lastFailedSymbol, '600487');
      expect(h.sources, ['tdx', '腾讯', '东财', '新浪']);
    });

    test('ok=true：无记录字段全 null → 不报警（hasIssue=false）', () {
      final h = MarketDataHealthDto.fromJson({
        'ok': true,
        'note': null,
        'lastSuccessAt': '09-22 15:00:00',
        'lastSuccessSource': 'tdx',
        'lastFailureAt': null,
        'consecutiveFailures': 0,
        'lastFailedSymbol': null,
        'sources': <Object>[],
      });

      expect(h.ok, isTrue);
      expect(h.hasIssue, isFalse, reason: '行情正常 → 零显示，不制造噪音');
      expect(h.lastFailureAt, isNull);
      expect(h.sources, isEmpty);
    });

    test('字段缺失 / ok 缺失 / 非法 JSON 形态 → 不抛异常，且不误报（ok 按 true）', () {
      // ok 缺失：这是「拿不到信息」而不是「行情挂了」——渲染成警报就是假警报
      final missing = MarketDataHealthDto.fromJson({'sources': ['tdx']});
      expect(missing.ok, isTrue);
      expect(missing.hasIssue, isFalse);
      expect(missing.consecutiveFailures, 0);
      expect(missing.note, isNull);
      expect(missing.lastSuccessAt, isNull);
      expect(missing.lastFailedSymbol, isNull);

      // 类型异常：sources 里混入数字 → 逐个 toString，多的那个空串被丢掉
      final weird = MarketDataHealthDto.fromJson({
        'ok': false,
        'consecutiveFailures': '12', // 字符串不是 num → 安全默认 0（宁少不说错）
        'sources': [1, '腾讯'],
      });
      expect(weird.consecutiveFailures, 0);
      expect(weird.sources, ['1', '腾讯']);

      // 后端返回裸数组/裸字符串等异常形态：整个 DTO 降级为「不报警」，绝不抛
      expect(MarketDataHealthDto.fromJson(<Object>[]).hasIssue, isFalse);
      expect(MarketDataHealthDto.fromJson('boom').hasIssue, isFalse);
    });
  });

  group('交易页行情横幅（P1-交易62）', () {
    Future<void> pumpTrading(WidgetTester tester, _Backend b) async {
      await tester.pumpWidget(MaterialApp(home: TradingPage(api: _apiFor(b))));
      await tester.pumpAndSettle();
    }

    testWidgets('ok=false → 出横幅、含后端 note 正文；展开看取数链与时刻', (tester) async {
      final b = _Backend();
      _mockBase(b);
      b.handlers['/api/v1/trading/market-data/health'] = (_) async => _json({
            'ok': false,
            'note': '行情取数连续 12 次都没拿到——资金曲线、自选信号、案例匹配可能不全，我在自动重试',
            'lastSuccessAt': '09-22 15:00:00',
            'lastSuccessSource': 'tdx',
            'lastFailureAt': '09-22 23:38:00',
            'consecutiveFailures': 12,
            'lastFailedSymbol': '600487',
            'sources': ['tdx', '腾讯', '东财', '新浪'],
          });

      await pumpTrading(tester, b);

      expect(find.text('阿呆最近拿不到行情'), findsOneWidget);
      expect(find.textContaining('我在自动重试'), findsOneWidget);
      // 细节默认收起（不占首屏）
      expect(find.textContaining('取数链'), findsNothing);

      await tester.tap(find.text('看明细'));
      await tester.pumpAndSettle();
      expect(find.text('取数链：tdx → 腾讯 → 东财 → 新浪'), findsOneWidget);
      expect(find.text('最近一次成功：09-22 15:00:00（tdx）'), findsOneWidget);
      expect(find.text('最近一次失败：09-22 23:38:00 · 600487'), findsOneWidget);
      expect(find.text('连续失败 12 次'), findsOneWidget);
    });

    testWidgets('ok=true → 零显示（行情正常不占地方）', (tester) async {
      final b = _Backend();
      _mockBase(b);
      b.handlers['/api/v1/trading/market-data/health'] = (_) async => _json({
            'ok': true,
            'note': null,
            'consecutiveFailures': 0,
            'sources': ['tdx', '腾讯'],
          });

      await pumpTrading(tester, b);

      expect(find.text('阿呆最近拿不到行情'), findsNothing);
      expect(find.text('看明细'), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('旧后端 404：静默降级——不出横幅、不弹错、页面其它数据照常', (tester) async {
      final b = _Backend();
      _mockBase(b);
      // 不注册 health handler → _Backend 兜底 404（等价旧后端没有这个端点）
      await pumpTrading(tester, b);

      expect(find.text('阿呆最近拿不到行情'), findsNothing);
      expect(tester.takeException(), isNull);
      // 主数据仍在：隐私条（日期 · 今天）照常渲染 → 说明 health 的失败没影响页面主体
      expect(find.textContaining('· 今天'), findsOneWidget);
    });

    testWidgets('health 返回 500：同样静默降级，不崩不报警', (tester) async {
      final b = _Backend();
      _mockBase(b);
      b.handlers['/api/v1/trading/market-data/health'] =
          (_) async => _json({'error': 'boom'}, status: 500);

      await pumpTrading(tester, b);

      expect(find.text('阿呆最近拿不到行情'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  });
}
