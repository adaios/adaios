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
