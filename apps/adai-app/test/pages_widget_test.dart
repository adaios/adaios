import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/pages/launcher_page.dart';
import 'package:adai_app/pages/memory_page.dart';
import 'package:adai_app/pages/timeline_page.dart';
import 'package:adai_app/pages/search_page.dart';
import 'package:adai_app/pages/trading_page.dart';
import 'package:adai_app/pages/project_task_page.dart';
import 'package:adai_app/pages/profile_page.dart';
import 'package:adai_app/widgets/timeline_modal.dart'; // P1-G6-1 回归：await 后 setState 守卫

// ────────────────────────────────────────────────────────────────
// 6 页面 widget 测试（#117 剩余）
//
// 覆盖 memory / timeline / search / trading / task / profile 六页：
// 数据渲染 + 错误态（#108 故障人话）+ 重试按钮。
// 复用批 F 的 MockClient 基建，不依赖真实后端。
// ────────────────────────────────────────────────────────────────

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

/// RFC 20260822：当日复盘聚合（GET /trading/trades?date=）默认响应——今日无成交不显示行。
/// 覆盖 /trading/trades handler 的测试用它分流 GET（daily 聚合）与 POST（记录交易）。
http.Response _dailyOk() => _json({
      'trades': <Object>[],
      'daily': {'date': '2026-08-15', 'count': 0, 'buyCount': 0, 'sellCount': 0,
        'buyAmount': 0.0, 'sellAmount': 0.0,
        'sessions': [
          {'name': '早盘', 'range': '09:30-11:30', 'count': 0},
          {'name': '午盘', 'range': '13:00-14:30', 'count': 0},
          {'name': '尾盘', 'range': '14:30-15:00', 'count': 0},
        ],
        'firstTradeTime': null, 'lastTradeTime': null},
    });

class _Backend {
  final Map<String, Future<http.Response> Function(http.Request)> handlers = {};
  Future<http.Response> handle(http.Request req) {
    final h = handlers[req.url.path];
    if (h != null) return h(req);
    return Future.value(_json({'error': 'not mocked'}, status: 404));
  }
}

ApiService _apiFor(_Backend b) =>
    ApiService(baseUrl: 'http://test', client: MockClient(b.handle));

/// 今天的 yyyy-MM-dd（memory/timeline 依赖当前日期做分组）。
String get _todayStr {
  final n = DateTime.now();
  return '${n.year}-${n.month.toString().padLeft(2, '0')}-${n.day.toString().padLeft(2, '0')}';
}

void main() {
  group('MemoryPage', () {
    testWidgets('数据渲染：kind 徽标 + 摘要 + 今天日期', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/memory/dates'] = (_) async => _json([_todayStr]);
      b.handlers['/api/v1/memory'] = (_) async => _json([
          {
            'id': 'm1', 'recordId': 'r1', 'kind': 'preference',
            'summary': '喜欢早睡', 'tags': ['生活'], 'sentiment': 'positive',
            'createdAt': '${_todayStr}T07:30:00', 'superseded': false,
            'actionable': false,
          },
        ]);
      await tester.pumpWidget(MaterialApp(home: MemoryPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.text('记忆'), findsOneWidget);
      expect(find.text('喜欢早睡'), findsOneWidget);
      expect(find.text('偏好'), findsOneWidget); // kind 徽标
      expect(find.text('今天'), findsOneWidget); // 日期标签
    });

    testWidgets('错误态：加载失败人话 + 重试成功', (tester) async {
      final b = _Backend()
        ..handlers['/api/v1/memory/dates'] = (_) async => _json([]);
      var fail = true;
      b.handlers['/api/v1/memory'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json([
              {
                'id': 'm1', 'recordId': 'r1', 'kind': 'insight',
                'summary': '喜欢早睡', 'tags': ['生活'], 'sentiment': 'positive',
                'createdAt': '${_todayStr}T07:30:00', 'superseded': false,
                'actionable': false,
              },
            ]);
      await tester.pumpWidget(MaterialApp(home: MemoryPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.text('加载失败，请重试'), findsOneWidget); // #108 故障人话
      expect(find.text('重试'), findsOneWidget);

      fail = false;
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('喜欢早睡'), findsOneWidget);
      expect(find.text('加载失败，请重试'), findsNothing);
    });

    testWidgets('记忆修正（P-role-02）：修正弹窗 → PATCH /memory/{id} → 列表更新', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/memory/dates'] = (_) async => _json([_todayStr]);
      Map<String, dynamic>? patched;
      // 保存后的刷新按 PATCH body 回显后端新值（模拟后端已持久化）
      b.handlers['/api/v1/memory'] = (_) async => _json([
          {
            'id': 'm1', 'recordId': 'r1',
            'kind': patched?['kind'] ?? 'preference',
            'summary': patched?['summary'] ?? '喜欢早睡',
            'tags': patched?['tags'] ?? ['生活'],
            'sentiment': 'positive',
            'createdAt': '${_todayStr}T07:30:00',
            'superseded': false,
            'actionable': false,
          },
        ]);
      b.handlers['/api/v1/memory/m1'] = (req) async {
        patched = jsonDecode(utf8.decode(req.bodyBytes)) as Map<String, dynamic>;
        return _json({'success': true});
      };
      await tester.pumpWidget(MaterialApp(home: MemoryPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      // 初始渲染原内容
      expect(find.text('喜欢早睡'), findsOneWidget);

      // 打开修正弹窗
      await tester.tap(find.byIcon(Icons.edit_outlined));
      await tester.pumpAndSettle();
      expect(find.text('修正这条记忆'), findsOneWidget);

      // 改内容 + 标签 + 类型
      await tester.enterText(find.byKey(const Key('memory-edit-summary')), '喜欢早起散步');
      await tester.enterText(find.byKey(const Key('memory-edit-tags')), '生活, 健康');
      await tester.tap(find.text('模式'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();

      // PATCH 已发：路径 + body（kind/summary/tags/actionable）
      expect(patched, isNotNull);
      expect(patched!['summary'], '喜欢早起散步');
      expect(patched!['kind'], 'pattern');
      expect(patched!['tags'], ['生活', '健康']);
      expect(patched!['actionable'], false);

      // 保存后列表按后端新值刷新
      expect(find.text('喜欢早起散步'), findsOneWidget);
      expect(find.text('模式'), findsOneWidget); // kind 徽标已更新
      expect(find.text('喜欢早睡'), findsNothing);
    });

    testWidgets('待办记忆完成（P-app-03）：完成按钮 → PATCH /memory/{id}/done → 标记已完成', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/memory/dates'] = (_) async => _json([_todayStr]);
      String? doneAt;
      b.handlers['/api/v1/memory'] = (_) async => _json([
          {
            'id': 'm2', 'recordId': 'r2', 'kind': 'decision',
            'summary': '下周开始每天记录交易', 'tags': ['交易'], 'sentiment': 'neutral',
            'createdAt': '${_todayStr}T09:00:00', 'superseded': false,
            'actionable': true, 'doneAt': doneAt,
          },
        ]);
      final doneRequests = <String>[];
      b.handlers['/api/v1/memory/m2/done'] = (req) async {
        doneRequests.add(req.method);
        doneAt = '${_todayStr}T10:00:00';
        return _json({'success': true});
      };
      await tester.pumpWidget(MaterialApp(home: MemoryPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      // 待办记忆：显示「待办」徽标 + 完成按钮
      expect(find.text('待办'), findsOneWidget);
      expect(find.text('完成'), findsOneWidget);

      await tester.tap(find.text('完成'));
      await tester.pumpAndSettle();

      // PATCH done 已发
      expect(doneRequests, ['PATCH']);
      // 完成后：徽标变「已完成」，完成按钮消失
      expect(find.text('已完成'), findsOneWidget);
      expect(find.text('完成'), findsNothing);
    });
  });

  group('TimelinePage', () {
    testWidgets('数据渲染：日历月视图 + 日记录', (tester) async {
      final b = _Backend()
        ..handlers['/api/v1/timeline'] = (_) async => _json([
            {
              'id': 't1', 'type': 'note', 'title': '晨间跑步',
              'tags': ['运动'], 'dateTime': '${_todayStr}T07:30:00', 'mediaPath': null,
            },
          ]);
      await tester.pumpWidget(MaterialApp(home: TimelinePage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      final now = DateTime.now();
      expect(find.text('时间线'), findsOneWidget);
      expect(find.text('${now.year}年${now.month}月'), findsOneWidget);
      expect(find.text('晨间跑步'), findsOneWidget); // 日记录
    });

    testWidgets('错误态：加载失败 + 重试成功', (tester) async {
      final b = _Backend();
      var fail = true;
      b.handlers['/api/v1/timeline'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json([
              {
                'id': 't1', 'type': 'note', 'title': '晨间跑步',
                'tags': ['运动'], 'dateTime': '${_todayStr}T07:30:00', 'mediaPath': null,
              },
            ]);
      await tester.pumpWidget(MaterialApp(home: TimelinePage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.text('加载失败，请重试'), findsOneWidget);
      expect(find.text('重试'), findsOneWidget);

      fail = false;
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('晨间跑步'), findsOneWidget);
    });
  });

  group('SearchPage', () {
    testWidgets('初始空态：提示输入关键词', (tester) async {
      final b = _Backend();
      await tester.pumpWidget(MaterialApp(home: SearchPage(api: _apiFor(b))));
      await tester.pumpAndSettle();
      expect(find.text('输入关键词搜索记录'), findsOneWidget);
    });

    testWidgets('搜索有结果：标题 + 结果计数 + 内容高亮', (tester) async {
      final b = _Backend()
        ..handlers['/api/v1/search'] = (_) async => _json({
            'results': [
              {
                'id': 's1', 'type': 'record', 'title': '买入记录',
                'content': '今天天气很好', 'tags': ['生活'],
                'dateTime': '${_todayStr}T09:00:00',
              },
            ],
            'total': 1,
          });
      await tester.pumpWidget(MaterialApp(home: SearchPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '天气');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(find.text('共 1 条结果'), findsOneWidget);
      expect(find.text('买入记录'), findsOneWidget);
      // 内容高亮片段（RichText）
      expect(find.textContaining('今天天气很好', findRichText: true), findsOneWidget);
    });

    testWidgets('搜索无结果：未找到相关记录', (tester) async {
      final b = _Backend()
        ..handlers['/api/v1/search'] = (_) async => _json({'results': [], 'total': 0});
      await tester.pumpWidget(MaterialApp(home: SearchPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '不存在的词');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(find.text('未找到相关记录'), findsOneWidget);
    });

    testWidgets('错误态：搜索失败 + 重试成功', (tester) async {
      final b = _Backend();
      var fail = true;
      b.handlers['/api/v1/search'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json({
              'results': [
                {
                  'id': 's1', 'type': 'record', 'title': '买入记录',
                  'content': '今天天气很好', 'tags': [], 'dateTime': '${_todayStr}T09:00:00',
                },
              ],
              'total': 1,
            });
      await tester.pumpWidget(MaterialApp(home: SearchPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '天气');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(find.text('搜索失败，请重试'), findsOneWidget);
      expect(find.text('重试'), findsOneWidget);

      fail = false;
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('买入记录'), findsOneWidget);
    });
  });

  group('TradingPage', () {
    // 按 hint 定位输入框（表单字段无外部 label finder，hint 唯一）
    Finder fieldByHint(String hint) => find.byWidgetPredicate(
        (w) => w is TextField && w.decoration?.hintText == hint);

    Future<void> pumpTrading(WidgetTester tester, _Backend b) async {
      await tester.pumpWidget(MaterialApp(home: TradingPage(api: _apiFor(b))));
      await tester.pumpAndSettle();
    }

    // 通用基础 handler（positions/portfolio/has-activity）
    void mockBase(_Backend b, {List<Map<String, dynamic>> positions = const []}) {
      b.handlers['/api/v1/trading/positions'] = (_) async =>
          _json({'positions': positions});
      b.handlers['/api/v1/trading/portfolio'] = (_) async => _json({
            'totalValue': 0.0, 'totalPnl': 0.0, 'cashBalance': 100000.0,
            'positionCount': positions.length,
          });
      b.handlers['/api/v1/trading/has-activity'] = (_) async =>
          _json({'date': '2026-08-15', 'hasActivity': false});
      // 2026-08-17 对齐 web：账户快照（_loadAux 异步加载）
      b.handlers['/api/v1/trading/account'] = (_) async => _json({
            'assets': 100000.0, 'cash': 50000.0, 'available': 50000.0,
            'withdrawable': 50000.0, 'marketValue': 50000.0, 'pnl': 0.0,
            'todayPnl': 0.0, 'principal': 150000.0,
          });
      // RFC 20260822：当日复盘聚合（今日无成交 → 不显示行）；覆盖此 handler 的测试须分流 GET/POST
      b.handlers['/api/v1/trading/trades'] = (req) async {
        if (req.method == 'GET') return _dailyOk();
        return _json({'positions': []});
      };
    }

    testWidgets('数据渲染：快照 + 持仓明细', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/trading/positions'] = (_) async => _json({
          'positions': [
            {
              'symbol': '600519', 'name': '贵州茅台', 'quantity': 100,
              'avgCost': 1500.0, 'currentPrice': 1600.0,
              'marketValue': 160000.0, 'pnl': 10000.0, 'pnlPercent': 6.7,
            },
          ],
        });
      b.handlers['/api/v1/trading/portfolio'] = (_) async => _json({
          'totalValue': 160000.0, 'totalPnl': 10000.0,
          'cashBalance': 50000.0, 'positionCount': 1,
        });
      await tester.pumpWidget(MaterialApp(home: TradingPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.text('交易'), findsOneWidget);
      expect(find.text('总资产'), findsOneWidget);
      expect(find.text('贵州茅台'), findsOneWidget);
      expect(find.text('持仓明细'), findsOneWidget);
    });

    testWidgets('账户卡渲染：券商口径总盈亏 = 资产 - 本金（2026-08-22：自选/清仓区块已移除）', (tester) async {
      final b = _Backend();
      mockBase(b);
      b.handlers['/api/v1/trading/account'] = (_) async => _json({
            'assets': 110504.88, 'cash': 292.88, 'available': 292.88,
            'withdrawable': 292.88, 'marketValue': 110212.0, 'pnl': 15235.55,
            'todayPnl': 0.0, 'principal': 150000.0,
          });
      await pumpTrading(tester, b);

      // 账户卡：总盈亏 = 资产 - 本金 = -39495.12（亏，绿；app 万单位显示 -3.9万）
      expect(find.text('总资产'), findsOneWidget);
      expect(find.text('本金'), findsOneWidget);
      expect(find.textContaining('-3.9万'), findsOneWidget);
      // 2026-08-22：自选/清仓区块已移除（管理归 web，能力不删）
      expect(find.text('自选股 · 买点信号'), findsNothing);
      expect(find.text('清仓复盘'), findsNothing);
    });

    testWidgets('当日交易复盘：今日 N 笔 · 买/卖 · 时段分布（RFC 20260822 纯客观）', (tester) async {
      final b = _Backend();
      mockBase(b);
      b.handlers['/api/v1/trading/trades'] = (req) async {
        if (req.method != 'GET') return _json({'positions': []});
        return _json({
          'trades': <Object>[],
          'daily': {
            'date': '2026-08-15', 'count': 4, 'buyCount': 3, 'sellCount': 1,
            'buyAmount': 12345.6, 'sellAmount': 6789.0,
            'sessions': [
              {'name': '早盘', 'range': '09:30-11:30', 'count': 2},
              {'name': '午盘', 'range': '13:00-14:30', 'count': 1},
              {'name': '尾盘', 'range': '14:30-15:00', 'count': 1},
            ],
            'firstTradeTime': '09:41:00', 'lastTradeTime': '14:52:00',
          },
        });
      };
      await pumpTrading(tester, b);

      // 今日 N 笔 · 买/卖 · 时段分布 · 首末笔时间（纯客观数字）
      expect(find.textContaining('今日 4 笔'), findsOneWidget);
      expect(find.textContaining('买 3 卖 1'), findsOneWidget);
      expect(find.textContaining('早盘 2'), findsOneWidget);
      expect(find.textContaining('午盘 1'), findsOneWidget);
      expect(find.textContaining('尾盘 1'), findsOneWidget);
      expect(find.textContaining('09:41-14:52'), findsOneWidget);
    });

    testWidgets('错误态：加载失败 + 重试成功', (tester) async {
      final b = _Backend();
      var fail = true;
      b.handlers['/api/v1/trading/positions'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json({
              'positions': [
                {
                  'symbol': '600519', 'name': '贵州茅台', 'quantity': 100,
                  'avgCost': 1500.0, 'currentPrice': 1600.0,
                  'marketValue': 160000.0, 'pnl': 10000.0, 'pnlPercent': 6.7,
                },
              ],
            });
      b.handlers['/api/v1/trading/portfolio'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json({
              'totalValue': 160000.0, 'totalPnl': 10000.0,
              'cashBalance': 50000.0, 'positionCount': 1,
            });
      await tester.pumpWidget(MaterialApp(home: TradingPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.text('重试'), findsOneWidget);

      fail = false;
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('贵州茅台'), findsOneWidget);
      expect(find.text('重试'), findsNothing);
    });

    testWidgets('NL 解析 → 确认卡回显 → 确认记录（2026-08-18 简化：止损/买点不回填不发送）', (tester) async {
      final b = _Backend();
      mockBase(b);
      b.handlers['/api/v1/trading/trades/parse'] = (_) async => _json({
            'matched': true, 'symbol': '000725', 'name': '京东方A',
            'direction': 'BUY', 'price': 5.2, 'volume': 1000,
            'stopLossPrice': 4.9, 'buyPoint': 'B1',
          });
      var traded = false;
      Map<String, dynamic>? tradeBody;
      b.handlers['/api/v1/trading/trades'] = (req) async {
        if (req.method == 'GET') return _dailyOk(); // RFC 20260822：当日复盘聚合分流
        traded = true;
        tradeBody = jsonDecode(utf8.decode(req.bodyBytes)) as Map<String, dynamic>;
        return _json({'positions': []});
      };
      await pumpTrading(tester, b);

      // 输入一句话 → 解析
      await tester.enterText(find.byType(TextField).first, '买了 1000 股京东方 @5.2，止损 4.9，B1');
      await tester.tap(find.text('解析'));
      await tester.pumpAndSettle();

      // 确认卡回显：名称+代码只读行、方向徽标、数量/价格可编辑
      expect(find.text('京东方A (000725)'), findsOneWidget);
      expect(find.text('买入'), findsOneWidget); // 方向徽标
      expect(find.text('确认记录'), findsOneWidget);
      expect(tester.widget<TextField>(fieldByHint('股数')).controller?.text, '1000');
      expect(tester.widget<TextField>(fieldByHint('成交单价')).controller?.text, '5.20');
      // 2026-08-18 简化：NL 带回的止损/买点不再回填（app 不展示这两项，归 web 端）
      expect(fieldByHint('止损价'), findsNothing);
      expect(find.text('B1'), findsNothing);

      // 确认 → POST /trading/trades（只带买卖四要素）
      await tester.tap(find.text('确认记录'));
      await tester.pumpAndSettle();

      expect(traded, isTrue);
      expect(tradeBody!['symbol'], '000725');
      expect(tradeBody!['name'], '京东方A');
      expect(tradeBody!['direction'], 'BUY');
      expect(tradeBody!['price'], 5.2);
      expect(tradeBody!['volume'], 1000);
      expect(tradeBody!.containsKey('stopLossPrice'), isFalse); // 止损归 web，app 不发
      expect(tradeBody!.containsKey('buyPoint'), isFalse);
      expect(find.textContaining('已买入'), findsOneWidget); // 人话 SnackBar
      expect(find.text('确认记录'), findsNothing); // 确认卡已收起
    });

    testWidgets('NL 解析失败（matched=false）：提示 + 自动展开精确表单', (tester) async {
      final b = _Backend();
      mockBase(b);
      b.handlers['/api/v1/trading/trades/parse'] = (_) async =>
          _json({'matched': false});
      await pumpTrading(tester, b);

      await tester.enterText(find.byType(TextField).first, '帮我看看行情');
      await tester.tap(find.text('解析'));
      await tester.pumpAndSettle();

      expect(find.textContaining('没听懂这句交易'), findsOneWidget);
      expect(find.text('标的（代码或名称）'), findsOneWidget); // 精确表单自动展开
      expect(find.text('买入'), findsOneWidget); // 底部双按钮
      expect(find.text('卖出'), findsOneWidget);
    });

    testWidgets('校验拦截：价格 ≤ 0 → 人话提示，不提交', (tester) async {
      final b = _Backend();
      mockBase(b);
      var traded = false;
      b.handlers['/api/v1/trading/trades'] = (req) async {
        if (req.method == 'GET') return _dailyOk(); // RFC 20260822：当日复盘聚合分流
        traded = true;
        return _json({'positions': []});
      };
      await pumpTrading(tester, b);

      await tester.tap(find.text('精确填写'));
      await tester.pumpAndSettle();

      await tester.enterText(fieldByHint('如 600519 或 贵州茅台'), '600519');
      await tester.enterText(fieldByHint('成交单价'), '0');
      await tester.enterText(fieldByHint('股数'), '100');
      await tester.tap(find.text('买入'));
      await tester.pumpAndSettle();

      expect(find.text('价格必须大于 0'), findsOneWidget);
      expect(traded, isFalse); // 校验拦截，未发请求
    });

    testWidgets('SELL 预检：超过持仓 → 人话拦截', (tester) async {
      final b = _Backend();
      mockBase(b, positions: [
        {
          'symbol': '000725', 'name': '京东方A', 'quantity': 1000,
          'avgCost': 5.2, 'currentPrice': 5.46,
          'marketValue': 5460.0, 'pnl': 260.0, 'pnlPercent': 5.0,
        },
      ]);
      var traded = false;
      b.handlers['/api/v1/trading/trades'] = (req) async {
        if (req.method == 'GET') return _dailyOk(); // RFC 20260822：当日复盘聚合分流
        traded = true;
        return _json({'positions': []});
      };
      await pumpTrading(tester, b);

      await tester.tap(find.text('精确填写'));
      await tester.pumpAndSettle();

      await tester.enterText(fieldByHint('如 600519 或 贵州茅台'), '000725');
      await tester.enterText(fieldByHint('成交单价'), '5.2');
      await tester.enterText(fieldByHint('股数'), '2000');
      await tester.tap(find.text('卖出'));
      await tester.pumpAndSettle();

      expect(find.text('卖出 2000 股超过持仓 1000 股'), findsOneWidget);
      expect(traded, isFalse);
    });

    testWidgets('精确表单 BUY：无止损/买点字段，直接提交（简化后归 web 设置）', (tester) async {
      final b = _Backend();
      mockBase(b);
      Map<String, dynamic>? tradeBody;
      b.handlers['/api/v1/trading/trades'] = (req) async {
        if (req.method == 'GET') return _dailyOk(); // RFC 20260822：当日复盘聚合分流
        tradeBody = jsonDecode(utf8.decode(req.bodyBytes)) as Map<String, dynamic>;
        return _json({'positions': []});
      };
      await pumpTrading(tester, b);

      await tester.tap(find.text('精确填写'));
      await tester.pumpAndSettle();

      // 2026-08-18 简化：精确表单只有 标的/价格/数量，无止损/买点字段
      expect(find.text('止损位'), findsNothing);
      expect(find.text('买点'), findsNothing);

      await tester.enterText(fieldByHint('如 600519 或 贵州茅台'), '000725');
      await tester.enterText(fieldByHint('成交单价'), '5.2');
      await tester.enterText(fieldByHint('股数'), '1000');
      await tester.tap(find.text('买入'));
      await tester.pumpAndSettle();

      expect(tradeBody, isNotNull);
      expect(tradeBody!['direction'], 'BUY');
      expect(tradeBody!.containsKey('stopLossPrice'), isFalse); // 止损归 web，app 不发
      expect(tradeBody!.containsKey('buyPoint'), isFalse);
      expect(find.textContaining('已买入'), findsOneWidget);
    });

    testWidgets('精确表单 SELL：不填止损也能卖，请求不带止损/买点', (tester) async {
      final b = _Backend();
      mockBase(b, positions: [
        {
          'symbol': '000725', 'name': '京东方A', 'quantity': 1000,
          'avgCost': 5.2, 'currentPrice': 5.46,
          'marketValue': 5460.0, 'pnl': 260.0, 'pnlPercent': 5.0,
        },
      ]);
      Map<String, dynamic>? tradeBody;
      b.handlers['/api/v1/trading/trades'] = (req) async {
        if (req.method == 'GET') return _dailyOk(); // RFC 20260822：当日复盘聚合分流
        tradeBody = jsonDecode(utf8.decode(req.bodyBytes)) as Map<String, dynamic>;
        return _json({'positions': []});
      };
      await pumpTrading(tester, b);

      await tester.tap(find.text('精确填写'));
      await tester.pumpAndSettle();

      await tester.enterText(fieldByHint('如 600519 或 贵州茅台'), '000725');
      await tester.enterText(fieldByHint('成交单价'), '5.46');
      await tester.enterText(fieldByHint('股数'), '1000');
      await tester.tap(find.text('卖出'));
      await tester.pumpAndSettle();

      expect(tradeBody, isNotNull);
      expect(tradeBody!['direction'], 'SELL');
      expect(tradeBody!.containsKey('stopLossPrice'), isFalse); // SELL 空字段不发
      expect(tradeBody!.containsKey('buyPoint'), isFalse);
      expect(find.textContaining('已卖出'), findsOneWidget);
    });

    testWidgets('NL SELL：确认卡隐藏止损/买点，请求不带止损字段', (tester) async {
      final b = _Backend();
      mockBase(b, positions: [
        {
          'symbol': '000725', 'name': '京东方A', 'quantity': 1000,
          'avgCost': 5.2, 'currentPrice': 5.46,
          'marketValue': 5460.0, 'pnl': 260.0, 'pnlPercent': 5.0,
        },
      ]);
      b.handlers['/api/v1/trading/trades/parse'] = (_) async => _json({
            'matched': true, 'symbol': '000725', 'name': '京东方A',
            'direction': 'SELL', 'price': 5.46, 'volume': 1000,
          });
      Map<String, dynamic>? tradeBody;
      b.handlers['/api/v1/trading/trades'] = (req) async {
        if (req.method == 'GET') return _dailyOk(); // RFC 20260822：当日复盘聚合分流
        tradeBody = jsonDecode(utf8.decode(req.bodyBytes)) as Map<String, dynamic>;
        return _json({'positions': []});
      };
      await pumpTrading(tester, b);

      await tester.enterText(find.byType(TextField).first, '卖了 1000 股京东方 @5.46');
      await tester.tap(find.text('解析'));
      await tester.pumpAndSettle();

      // SELL 确认卡：方向徽标 + 止损/买点隐藏（RFC 20260816：SELL 可空）
      expect(find.text('卖出'), findsOneWidget); // 方向徽标
      expect(fieldByHint('止损价'), findsNothing);
      expect(find.text('B1'), findsNothing);

      await tester.tap(find.text('确认记录'));
      await tester.pumpAndSettle();

      expect(tradeBody, isNotNull);
      expect(tradeBody!['direction'], 'SELL');
      expect(tradeBody!.containsKey('stopLossPrice'), isFalse);
      expect(tradeBody!.containsKey('buyPoint'), isFalse);
      expect(find.textContaining('已卖出'), findsOneWidget);
    });

    testWidgets('持仓卡渲染：名称/代码 + 盈亏大字 + 盈亏%', (tester) async {
      final b = _Backend();
      mockBase(b, positions: [
        {
          'symbol': '000725', 'name': '京东方A', 'quantity': 1000,
          'avgCost': 5.2, 'currentPrice': 5.46,
          'marketValue': 5460.0, 'pnl': 260.0, 'pnlPercent': 5.0,
        },
        {
          'symbol': '600519', 'name': '贵州茅台', 'quantity': 100,
          'avgCost': 1500.0, 'currentPrice': 1490.0,
          'marketValue': 149000.0, 'pnl': -1000.0, 'pnlPercent': -0.7,
        },
      ]);
      await pumpTrading(tester, b);

      expect(find.text('京东方A'), findsOneWidget);
      expect(find.text('000725'), findsOneWidget);
      expect(find.text('+260'), findsOneWidget); // 盈=红大字
      expect(find.text('+5.0%'), findsOneWidget);
      expect(find.text('贵州茅台'), findsOneWidget);
      expect(find.text('-1000'), findsOneWidget); // 亏=绿大字
      expect(find.text('-0.7%'), findsOneWidget);
      expect(find.text('共 2 只'), findsOneWidget);
    });

    testWidgets('持仓卡批次简版：批次数 + 最近买入 + 含底仓徽标（RFC 20260825）', (tester) async {
      final b = _Backend();
      mockBase(b, positions: [
        {
          'symbol': '600000', 'name': '浦发银行', 'quantity': 1500,
          'avgCost': 10.2, 'currentPrice': 10.5,
          'marketValue': 15750.0, 'pnl': 400.0, 'pnlPercent': 2.6,
        },
      ]);
      b.handlers['/api/v1/trading/lots'] = (_) async => _json({
            'lots': [
              {
                'lotId': '600000_2026-07-20_A', 'symbol': '600000', 'name': '浦发银行',
                'buyDate': '2026-07-20', 'volume': 500, 'remaining': 500,
                'costPrice': 10.5, 'currentPrice': 10.5, 'marketValue': 5250.0,
                'pnl': 0.0, 'pnlPct': 0.0, 'stopLossPrice': 9.77,
                'stopLossDistancePct': 7.5, 'buyPoint': null, 'role': null,
                'initial': true, 'closed': false, 'realizedPnl': null,
              },
              {
                'lotId': '600000_2026-07-28_B', 'symbol': '600000', 'name': '浦发银行',
                'buyDate': '2026-07-28', 'volume': 500, 'remaining': 500,
                'costPrice': 10.2, 'currentPrice': 10.5, 'marketValue': 5250.0,
                'pnl': 150.0, 'pnlPct': 2.94, 'stopLossPrice': 9.49,
                'stopLossDistancePct': 10.6, 'buyPoint': 'B1', 'role': null,
                'initial': false, 'closed': false, 'realizedPnl': null,
              },
              {
                'lotId': '600000_2026-08-03_C', 'symbol': '600000', 'name': '浦发银行',
                'buyDate': '2026-08-03', 'volume': 1000, 'remaining': 500,
                'costPrice': 10.0, 'currentPrice': 10.5, 'marketValue': 5250.0,
                'pnl': 250.0, 'pnlPct': 5.0, 'stopLossPrice': 9.3,
                'stopLossDistancePct': 12.9, 'buyPoint': null, 'role': null,
                'initial': false, 'closed': false, 'realizedPnl': null,
              },
              // 已清仓回合不计入开放批次（closed + remaining=0 被过滤）
              {
                'lotId': '600000_2026-06-01_INIT', 'symbol': '600000', 'name': '浦发银行',
                'buyDate': '2026-06-01', 'volume': 1000, 'remaining': 0,
                'costPrice': 9.0, 'currentPrice': 10.5, 'marketValue': 0.0,
                'pnl': 0.0, 'pnlPct': 0.0, 'stopLossPrice': 8.37,
                'stopLossDistancePct': 25.5, 'buyPoint': null, 'role': null,
                'initial': true, 'closed': true, 'realizedPnl': 1200.0,
              },
            ],
            'reconcile': <Object>[],
          });
      await pumpTrading(tester, b);

      // 3 个开放批次 · 最近买入 8/03（yyyy-MM-dd → M/d）· 含底仓徽标（07-20 初始批次仍开放）
      expect(find.textContaining('3 个批次'), findsOneWidget);
      expect(find.textContaining('最近买入 8/03'), findsOneWidget);
      expect(find.text('含底仓'), findsOneWidget);
      // 无批次破止损 → 不出现警示
      expect(find.text('有批次破止损'), findsNothing);
    });

    testWidgets('持仓卡批次警示：有批次破止损未走 → 警示文字（距止损 < 0 且剩余 > 0）', (tester) async {
      final b = _Backend();
      mockBase(b, positions: [
        {
          'symbol': '000725', 'name': '京东方A', 'quantity': 1000,
          'avgCost': 5.2, 'currentPrice': 4.6,
          'marketValue': 4600.0, 'pnl': -600.0, 'pnlPercent': -11.5,
        },
      ]);
      b.handlers['/api/v1/trading/lots'] = (_) async => _json({
            'lots': [
              {
                'lotId': '000725_2026-08-01_A', 'symbol': '000725', 'name': '京东方A',
                'buyDate': '2026-08-01', 'volume': 1000, 'remaining': 1000,
                'costPrice': 5.2, 'currentPrice': 4.6, 'marketValue': 4600.0,
                'pnl': -600.0, 'pnlPct': -11.54, 'stopLossPrice': 4.84,
                'stopLossDistancePct': -4.96, 'buyPoint': null, 'role': null,
                'initial': false, 'closed': false, 'realizedPnl': null,
              },
            ],
            'reconcile': <Object>[],
          });
      await pumpTrading(tester, b);

      expect(find.text('有批次破止损'), findsOneWidget);
      expect(find.textContaining('1 个批次'), findsOneWidget);
      expect(find.text('含底仓'), findsNothing); // 无初始批次 → 无徽标
    });

    testWidgets('批次拉取失败静默降级：持仓卡原样展示，不整页报错', (tester) async {
      final b = _Backend();
      mockBase(b, positions: [
        {
          'symbol': '600519', 'name': '贵州茅台', 'quantity': 100,
          'avgCost': 1500.0, 'currentPrice': 1600.0,
          'marketValue': 160000.0, 'pnl': 10000.0, 'pnlPercent': 6.7,
        },
      ]);
      b.handlers['/api/v1/trading/lots'] = (_) async =>
          _json({'error': 'boom'}, status: 500);
      await pumpTrading(tester, b);

      // 持仓卡原样渲染（无批次行、无整页错误态、无重试按钮）
      expect(find.text('贵州茅台'), findsOneWidget);
      expect(find.text('+1.0万'), findsOneWidget); // 盈亏大字（万单位）
      expect(find.textContaining('个批次'), findsNothing);
      expect(find.text('重试'), findsNothing);
    });

    testWidgets('点按持仓卡 → 阿呆建议弹层（advice 端点 + 依据规则号）', (tester) async {
      final b = _Backend();
      mockBase(b, positions: [
        {
          'symbol': '000725', 'name': '京东方A', 'quantity': 1000,
          'avgCost': 5.2, 'currentPrice': 5.46,
          'marketValue': 5460.0, 'pnl': 260.0, 'pnlPercent': 5.0,
        },
      ]);
      b.handlers['/api/v1/trading/advice'] = (_) async => _json({
            'summary': '今天整体还行',
            'items': [
              {
                'symbol': '000725', 'name': '京东方A', 'action': '减仓',
                'advice': '京东方仓位 30% 超 R81 单仓上限，建议减到 20%',
                'rules': ['R81', 'R66'],
              },
            ],
          });
      await pumpTrading(tester, b);

      await tester.tap(find.text('京东方A'));
      await tester.pumpAndSettle();

      // 阿呆建议弹层：自然对话 + 动作徽标 + 依据 + 去 web
      expect(find.text('阿呆说 · 京东方A'), findsOneWidget);
      expect(find.textContaining('建议减到 20%'), findsOneWidget);
      expect(find.text('减仓'), findsOneWidget); // 动作徽标
      expect(find.text('查看建议依据'), findsOneWidget);
      expect(find.text('管理持仓（去 web）'), findsOneWidget);

      await tester.tap(find.text('查看建议依据'));
      await tester.pumpAndSettle();
      expect(find.text('R81'), findsOneWidget);
      expect(find.text('R66'), findsOneWidget);
    });

    testWidgets('复盘横幅：has-activity → 生成复盘 → dialog', (tester) async {
      final b = _Backend();
      mockBase(b);
      b.handlers['/api/v1/trading/has-activity'] = (_) async =>
          _json({'date': '2026-08-15', 'hasActivity': true});
      b.handlers['/api/v1/trading/review'] = (_) async =>
          _json({'date': '2026-08-15', 'content': '## 今日复盘\n执行了纪律'});
      await pumpTrading(tester, b);

      expect(find.text('今日有交易 · 生成今日复盘？'), findsOneWidget);

      await tester.tap(find.text('生成复盘'));
      await tester.pumpAndSettle();

      expect(find.text('2026-08-15 复盘'), findsOneWidget); // dialog
      expect(find.text('反哺入库'), findsOneWidget);
    });

    testWidgets('空态：暂无持仓 + 引导去电脑端导入', (tester) async {
      final b = _Backend();
      mockBase(b);
      await pumpTrading(tester, b);

      expect(find.text('暂无持仓'), findsOneWidget);
      expect(find.text('有历史持仓？到电脑端导入'), findsOneWidget);

      await tester.tap(find.text('有历史持仓？到电脑端导入'));
      await tester.pumpAndSettle();
      expect(find.text('详细管理去电脑端'), findsOneWidget); // web 引导弹层
    });
  });

  group('ProjectTaskPage', () {
    testWidgets('数据渲染：任务列表 + 统计', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/project/tasks'] = (_) async => _json([
          {
            'id': 'task1', 'title': '写周报', 'description': '',
            'status': 'TODO', 'priority': 'P2', 'tags': [],
            'createdAt': _todayStr, 'updatedAt': _todayStr,
          },
        ]);
      b.handlers['/api/v1/project/tasks/stats'] = (_) async => _json({
          'total': 1, 'todo': 1, 'doing': 0, 'done': 0, 'cancelled': 0,
        });
      await tester.pumpWidget(MaterialApp(home: ProjectTaskPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.text('任务'), findsOneWidget);
      expect(find.text('写周报'), findsOneWidget);
    });

    testWidgets('错误态：加载失败 + 重试成功', (tester) async {
      final b = _Backend();
      var fail = true;
      b.handlers['/api/v1/project/tasks'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json([
              {
                'id': 'task1', 'title': '写周报', 'description': '',
                'status': 'TODO', 'priority': 'P2', 'tags': [],
                'createdAt': _todayStr, 'updatedAt': _todayStr,
              },
            ]);
      b.handlers['/api/v1/project/tasks/stats'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json({'total': 1, 'todo': 1, 'doing': 0, 'done': 0, 'cancelled': 0});
      await tester.pumpWidget(MaterialApp(home: ProjectTaskPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.text('加载失败，请重试'), findsOneWidget);
      expect(find.text('重试'), findsOneWidget);

      fail = false;
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('写周报'), findsOneWidget);
    });
  });

  group('ProfilePage', () {
    testWidgets('数据渲染：档案信息 + 关注标签', (tester) async {
      final b = _Backend()
        ..handlers['/api/v1/identity'] = (_) async => _json({
            'name': '阿呆',
            'preferences': {'language': '中文', 'style': '简洁', 'focus': '交易'},
            'rules': {'confirmation': 'yes'},
            'tags': ['跑步', '阅读'],
          });
      await tester.pumpWidget(MaterialApp(home: ProfilePage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.text('个人档案'), findsOneWidget);
      expect(find.text('阿呆'), findsOneWidget);
      expect(find.text('关注标签'), findsOneWidget);
      expect(find.text('跑步'), findsOneWidget); // 标签 chip
      expect(find.text('交易类操作需确认'), findsOneWidget); // 规则开关（confirmation 有值）
    });

    testWidgets('错误态：无法加载档案 + 重试成功', (tester) async {
      final b = _Backend();
      var fail = true;
      b.handlers['/api/v1/identity'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json({
              'name': '阿呆',
              'preferences': {'language': '中文'},
              'rules': {},
              'tags': ['跑步'],
            });
      await tester.pumpWidget(MaterialApp(home: ProfilePage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.textContaining('无法加载个人档案'), findsOneWidget); // ⚠️ 前缀
      expect(find.text('重试'), findsOneWidget);

      fail = false;
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('阿呆'), findsOneWidget);
      expect(find.textContaining('无法加载个人档案'), findsNothing);
    });
  });

  group('LauncherPage 插件门控（RFC 20260814 T2.9）', () {
    Future<void> pumpLauncher(WidgetTester tester, List<String> plugins) async {
      final b = _Backend();
      b.handlers['/api/v1/identity'] = (_) async => _json({'name': '测试', 'preferences': <String, dynamic>{}});
      b.handlers['/api/v1/tags'] = (_) async => _json({'tags': [], 'total': 0});
      b.handlers['/api/v1/timeline'] = (_) async => _json([]);
      b.handlers['/api/v1/memory/count'] = (_) async => _json({'count': 0});
      b.handlers['/api/v1/me/plugins'] = (_) async => _json(plugins);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: LauncherPage(api: _apiFor(b), onNavigateBack: () {})),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('无插件用户：隐藏交易/阿呆系统，基础服务常驻', (tester) async {
      await pumpLauncher(tester, []);

      expect(find.text('交易'), findsNothing, reason: '无 trading 插件 → 隐藏交易入口');
      expect(find.text('阿呆系统'), findsNothing, reason: '无 project 插件 → 隐藏阿呆系统入口');
      expect(find.text('任务'), findsOneWidget, reason: '任务=待办=基础服务，人人都有');
      expect(find.text('关于我'), findsOneWidget);
      expect(find.text('脑瓜子正在装...'), findsOneWidget);
      expect(find.text('时间都去哪了'), findsOneWidget);
    });

    testWidgets('adai 全插件用户：显示交易与阿呆系统', (tester) async {
      await pumpLauncher(tester, ['trading', 'project']);

      expect(find.text('交易'), findsOneWidget);
      expect(find.text('阿呆系统'), findsOneWidget);
      expect(find.text('任务'), findsOneWidget);
    });

    testWidgets('只开 project 插件：有阿呆系统无交易', (tester) async {
      await pumpLauncher(tester, ['project']);

      expect(find.text('阿呆系统'), findsOneWidget);
      expect(find.text('交易'), findsNothing);
    });

    testWidgets('只开 trading 插件：有交易无阿呆系统（P2-R2 分支补齐）', (tester) async {
      await pumpLauncher(tester, ['trading']);

      expect(find.text('交易'), findsOneWidget);
      expect(find.text('阿呆系统'), findsNothing, reason: '无 project 插件 → 隐藏阿呆系统入口');
    });

    testWidgets('插件拉取失败（500）：核心数据正常渲染，交易/阿呆系统隐藏（P2-R2 降级分支）',
        (WidgetTester tester) async {
      final b = _Backend();
      b.handlers['/api/v1/identity'] = (_) async => _json({'name': '测试', 'preferences': <String, dynamic>{}});
      b.handlers['/api/v1/tags'] = (_) async => _json({'tags': [], 'total': 0});
      b.handlers['/api/v1/timeline'] = (_) async => _json([]);
      b.handlers['/api/v1/memory/count'] = (_) async => _json({'count': 0});
      b.handlers['/api/v1/me/plugins'] = (_) async => _json({'error': 'boom'}, status: 500);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: LauncherPage(api: _apiFor(b), onNavigateBack: () {})),
      ));
      await tester.pumpAndSettle();

      // P1-6 拆独立 try/catch 后：插件失败不影响核心数据渲染
      expect(find.text('关于我'), findsOneWidget, reason: '核心数据（身份）正常渲染');
      expect(find.text('交易'), findsNothing, reason: '插件失败默认只显基础服务');
      expect(find.text('阿呆系统'), findsNothing);
      // REVIEW S-R1：失败给 SnackBar 反馈 + 重试入口（与 web 对拍）；flush 自动关闭计时器
      expect(find.text('插件加载失败，仅显示基础服务'), findsOneWidget);
      expect(find.text('重试'), findsOneWidget);
      await tester.pump(const Duration(seconds: 5));
    });
  });

  group('TimelineModal await 后守卫（REVIEW P1-G6-1）', () {
    testWidgets('modal 关闭后响应到达：mounted 守卫拦截，不 setState 崩溃', (tester) async {
      // 用 Completer 挂起 /timeline 响应，模拟慢网络
      final completer = Completer<http.Response>();
      final b = _Backend()
        ..handlers['/api/v1/timeline'] = (_) => completer.future;
      final api = _apiFor(b);

      // 打开 modal（Completer 挂起 → loading spinner 永动，不能用 pumpAndSettle，用固定帧）
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: Builder(builder: (ctx) => Center(
          child: TextButton(
            onPressed: () => TimelineModal.show(ctx, api: api),
            child: const Text('打开时间线'),
          ),
        ))),
      ));
      await tester.tap(find.text('打开时间线'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400)); // bottom sheet 动画
      expect(find.text('时间线'), findsOneWidget);

      // 响应未到时关闭 modal（组件销毁）
      await tester.tapAt(const Offset(20, 20)); // 点 barrier 关闭
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400)); // 关闭动画
      expect(find.text('时间线'), findsNothing);

      // 响应此刻才到达 → 旧实现 setState after dispose 抛异常；守卫后安全
      completer.complete(_json([
        {'id': 't1', 'type': 'note', 'title': '迟到响应', 'tags': [], 'dateTime': '${_todayStr}T07:30:00', 'mediaPath': null},
      ]));
      await tester.pump(); // 不用 pumpAndSettle：守卫拦截后无新帧；异常若存在在微任务间抛出
      // 无异常即通过；且不残留 modal
      expect(tester.takeException(), isNull);
      expect(find.text('时间线'), findsNothing);
    });

    testWidgets('正常路径：响应到达后渲染日记录', (tester) async {
      final b = _Backend()
        ..handlers['/api/v1/timeline'] = (_) async => _json([
            {
              'id': 't1', 'type': 'note', 'title': '晨间跑步',
              'tags': ['运动'], 'dateTime': '${_todayStr}T07:30:00', 'mediaPath': null,
            },
          ]);
      final api = _apiFor(b);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: Builder(builder: (ctx) => Center(
          child: TextButton(
            onPressed: () => TimelineModal.show(ctx, api: api),
            child: const Text('打开时间线'),
          ),
        ))),
      ));
      await tester.tap(find.text('打开时间线'));
      await tester.pumpAndSettle();
      expect(find.text('时间线'), findsOneWidget);
      // _buildRow 拼成 '$time  $title'（如「07:30  晨间跑步」），用 textContaining 匹配
      expect(find.textContaining('晨间跑步'), findsOneWidget); // 数据渲染成功
    });
  });
}
