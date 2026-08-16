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
      expect(find.text('总市值'), findsOneWidget);
      expect(find.text('贵州茅台'), findsOneWidget);
      expect(find.text('持仓明细'), findsOneWidget);
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

    testWidgets('NL 解析 → 确认卡回显（含止损/买点回填）→ 确认记录', (tester) async {
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
      // RFC 20260816：NL 带回止损/买点 → 确认卡回填（用户可改）
      expect(tester.widget<TextField>(fieldByHint('止损价')).controller?.text, '4.90');
      expect(find.text('B1'), findsOneWidget); // 买点下拉默认/回填 B1

      // 确认 → POST /trading/trades（写真实交易，BUY 带止损/买点）
      await tester.tap(find.text('确认记录'));
      await tester.pumpAndSettle();

      expect(traded, isTrue);
      expect(tradeBody!['symbol'], '000725');
      expect(tradeBody!['name'], '京东方A');
      expect(tradeBody!['direction'], 'BUY');
      expect(tradeBody!['price'], 5.2);
      expect(tradeBody!['volume'], 1000);
      expect(tradeBody!['stopLossPrice'], 4.9);
      expect(tradeBody!['buyPoint'], 'B1');
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
      b.handlers['/api/v1/trading/trades'] = (_) async {
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
      b.handlers['/api/v1/trading/trades'] = (_) async {
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

    testWidgets('BUY 缺止损拦截：精确表单不填止损 → 人话提示，不提交', (tester) async {
      final b = _Backend();
      mockBase(b);
      var traded = false;
      b.handlers['/api/v1/trading/trades'] = (_) async {
        traded = true;
        return _json({'positions': []});
      };
      await pumpTrading(tester, b);

      await tester.tap(find.text('精确填写'));
      await tester.pumpAndSettle();

      await tester.enterText(fieldByHint('如 600519 或 贵州茅台'), '000725');
      await tester.enterText(fieldByHint('成交单价'), '5.2');
      await tester.enterText(fieldByHint('股数'), '1000');
      await tester.tap(find.text('买入'));
      await tester.pumpAndSettle();

      // RFC 20260816：BUY 缺止损 → 前端人话拦截（对齐后端 400 语义）
      expect(find.text('买入请填止损位，跌破就按计划处理'), findsOneWidget);
      expect(traded, isFalse); // 未发请求
    });

    testWidgets('精确表单 BUY：填止损 + 默认买点 B1 → 请求带上', (tester) async {
      final b = _Backend();
      mockBase(b);
      Map<String, dynamic>? tradeBody;
      b.handlers['/api/v1/trading/trades'] = (req) async {
        tradeBody = jsonDecode(utf8.decode(req.bodyBytes)) as Map<String, dynamic>;
        return _json({'positions': []});
      };
      await pumpTrading(tester, b);

      await tester.tap(find.text('精确填写'));
      await tester.pumpAndSettle();

      await tester.enterText(fieldByHint('如 600519 或 贵州茅台'), '000725');
      await tester.enterText(fieldByHint('成交单价'), '5.2');
      await tester.enterText(fieldByHint('股数'), '1000');
      await tester.enterText(fieldByHint('止损价，如 4.90'), '4.9');
      await tester.tap(find.text('买入'));
      await tester.pumpAndSettle();

      expect(tradeBody, isNotNull);
      expect(tradeBody!['direction'], 'BUY');
      expect(tradeBody!['stopLossPrice'], 4.9);
      expect(tradeBody!['buyPoint'], 'B1'); // 买点默认 B1
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
}
