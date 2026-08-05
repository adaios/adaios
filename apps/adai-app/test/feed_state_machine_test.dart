import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/main_page.dart';
import 'package:adai_app/services/api_service.dart';

// ────────────────────────────────────────────────────────────────
// Feed 状态机 widget 测试（#117）
//
// 锁住批 B 修复的核心状态流转：ask→waiting→chatting→ended、
// 追加、错误重试、删除、加载更多、#100 竞态（追加挂起时结束对话）。
// 通过注入 MockClient 使 ApiService 走假后端，不依赖真实 HTTP。
// ────────────────────────────────────────────────────────────────

/// UTF-8 安全 JSON 响应（ApiService 用 utf8.decode(resp.bodyBytes)，
/// 直接传 String body 会被 latin1 编码，中文会损坏）。
http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

/// 假后端：按 path 路由 + 记录全部请求。
class _Backend {
  List<Map<String, dynamic>> feedPage0 = [];
  List<Map<String, dynamic>> feedPage1 = [];
  int feedTotalToday = 0;

  final List<http.Request> requests = [];
  final Map<String, Future<http.Response> Function(http.Request)> handlers = {};

  _Backend() {
    handlers['/api/v1/brief'] = (_) => Future.value(_json({'content': ''}));
    handlers['/api/v1/feed'] = (req) {
      final page = int.tryParse(req.url.queryParameters['page'] ?? '0') ?? 0;
      return Future.value(_json({
        'entries': page == 0 ? feedPage0 : feedPage1,
        'totalToday': feedTotalToday,
      }));
    };
    handlers['/api/v1/records'] = (req) {
      final intent = jsonDecode(req.body)['intent'];
      if (intent == 'question') {
        return Future.value(_json({
          'intent': 'question', 'recordId': 'r-q',
          'rawResponse': 'AI 回答内容', 'summary': 'AI 回答内容',
          'tags': ['问答'], 'domain': 'life',
        }));
      }
      return Future.value(_json({
        'intent': 'log', 'recordId': 'r-l',
        'summary': '记录完成', 'rawResponse': '补充回答',
        'tags': ['生活'], 'domain': 'life',
      }));
    };
    handlers['/api/v1/conversations/end'] = (_) =>
        Future.value(_json({'recordId': 'r1', 'summary': '对话总结', 'tags': ['总结']}));
  }

  Future<http.Response> handle(http.Request req) {
    requests.add(req);
    final h = handlers[req.url.path];
    if (h != null) return h(req);
    return Future.value(_json({'error': 'not mocked'}, status: 404));
  }
}

/// Feed 记录条目 JSON。
Map<String, dynamic> _record(String id, String content,
        {String intent = 'log', String time = '14:00', String date = '08-04'}) =>
    {
      'type': 'record', 'id': id, 'title': '', 'content': content,
      'tags': <String>[], 'time': time, 'date': date,
      'intent': intent, 'summary': null, 'turns': null,
      'domain': 'life', 'mediaPath': null,
    };

/// 有对话记录的卡条目 JSON。
Map<String, dynamic> _cardWithTurns(String id, String content,
        {List<Map<String, dynamic>>? turns}) =>
    {
      'type': 'card', 'id': id, 'title': '', 'content': content,
      'tags': <String>[], 'time': '14:00', 'date': '08-04',
      'intent': 'question', 'summary': '已总结',
      'turns': turns ??
          [
            {'isUser': true, 'text': content, 'time': '14:00'},
            {'isUser': false, 'text': '今天晴', 'time': '14:01'},
          ],
      'domain': 'life', 'mediaPath': null,
    };

/// 注入 mock 后端渲染 MainPage。
Future<_Backend> _pump(WidgetTester tester, _Backend backend) async {
  final api = ApiService(
    baseUrl: 'http://test',
    client: MockClient(backend.handle),
  );
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: MainPage(api: api))));
  await tester.pumpAndSettle();
  return backend;
}

void main() {
  group('Feed 状态机', () {
    testWidgets('空态：无记录显示引导', (tester) async {
      final b = _Backend();
      await _pump(tester, b);
      expect(find.text('还没有记录'), findsOneWidget);
      expect(find.text('📝 记录心情'), findsOneWidget);
    });

    testWidgets('初始 Feed：记录卡显示内容 + 记录徽标 + 提问按钮', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      await _pump(tester, b);
      expect(find.text('今天买了立昂微'), findsOneWidget);
      expect(find.text('记录'), findsOneWidget); // log 徽标
      expect(find.text('提问'), findsOneWidget); // 底部按钮
    });

    testWidgets('发送新记录：log 流 → 新卡 idle + 摘要', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '第一条')]
        ..feedTotalToday = 1;
      await _pump(tester, b);

      await tester.enterText(find.byType(TextField), '今日跑步 5 公里');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(find.text('今日跑步 5 公里'), findsOneWidget);
      expect(find.text('记录完成'), findsOneWidget); // log 摘要（干净行）
      final recordReqs = b.requests.where((r) => r.url.path == '/api/v1/records');
      expect(recordReqs.length, 1);
      // 新记录无 intent → 后端默认 log
      expect(jsonDecode(recordReqs.first.body)['intent'], isNull);
    });

    testWidgets('ask 流程：waiting（正在思考…）→ chatting 显示 AI 回复', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      final askGate = Completer<http.Response>();
      b.handlers['/api/v1/records'] = (req) {
        final intent = jsonDecode(req.body)['intent'];
        if (intent == 'question') return askGate.future;
        return Future.value(_json(
            {'intent': 'log', 'recordId': 'r-l', 'summary': '记录完成', 'tags': [], 'domain': 'life'}));
      };
      await _pump(tester, b);

      // 点提问 → waiting 态（loading 气泡 + 正在思考…）
      await tester.tap(find.text('提问'));
      await tester.pump();
      expect(find.text('正在思考…'), findsOneWidget);

      // ask 完成 → chatting，显示 AI 回复
      askGate.complete(_json({
        'intent': 'question', 'recordId': 'r-q',
        'rawResponse': 'AI 回答内容', 'summary': 'AI 回答内容',
        'tags': [], 'domain': 'life',
      }));
      await tester.pumpAndSettle();
      expect(find.textContaining('AI 回答内容', findRichText: true), findsOneWidget);
    });

    testWidgets('点提问：已有对话的卡重开，不重复 POST', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_cardWithTurns('r1', '今天的天气如何')]
        ..feedTotalToday = 1;
      await _pump(tester, b);

      // question 卡：提问徽标 + 提问按钮 = 2 个
      expect(find.text('提问'), findsNWidgets(2));
      await tester.tap(find.text('提问').last); // 底部按钮
      await tester.pumpAndSettle();

      // 重开对话：turns 直接展示，不发新请求
      expect(find.text('今天的天气如何'), findsOneWidget);
      expect(find.textContaining('今天晴', findRichText: true), findsOneWidget);
      expect(b.requests.where((r) => r.url.path == '/api/v1/records'), isEmpty);
    });

    testWidgets('end 流程：chatting → ended 显示总结', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      await _pump(tester, b);

      await tester.tap(find.text('提问'));
      await tester.pumpAndSettle(); // chatting

      // active 布局外层有 onDoubleTap，tap 需等 double-tap 超时（300ms）才触发
      await tester.tap(find.text('end conversation'));
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      // 回到 feed：ended 卡显示总结，底部提问按钮仍在，无结束按钮
      expect(find.text('对话总结'), findsOneWidget);
      expect(find.text('end conversation'), findsNothing);
      expect(find.text('提问'), findsWidgets);
    });

    testWidgets('chatting 中追加输入：新 turn + AI 回复', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      await _pump(tester, b);

      await tester.tap(find.text('提问'));
      await tester.pumpAndSettle(); // chatting

      await tester.enterText(find.byType(TextField), '追加问题');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(find.text('追加问题'), findsOneWidget); // 新 user turn
      expect(find.textContaining('补充回答', findRichText: true), findsOneWidget); // AI 回复
    });

    testWidgets('发送失败 → error 态（重试按钮）→ 重试成功', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '第一条')]
        ..feedTotalToday = 1;
      var failNext = true;
      b.handlers['/api/v1/records'] = (req) {
        if (failNext) {
          failNext = false;
          return Future.value(_json({'error': '服务器开小差'}, status: 500));
        }
        return Future.value(_json(
            {'intent': 'log', 'recordId': 'r-l', 'summary': '记录完成', 'tags': [], 'domain': 'life'}));
      };
      await _pump(tester, b);

      await tester.enterText(find.byType(TextField), '会失败的记录');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      // error 态：重试按钮 + 人话错误文本
      expect(find.text('重试'), findsOneWidget);
      expect(find.textContaining('请求失败 (500)'), findsOneWidget);
      expect(find.text('会失败的记录'), findsOneWidget);

      // 重试 → 重新创建成功
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('会失败的记录'), findsOneWidget);
      expect(find.text('重试'), findsNothing);
    });

    testWidgets('ask 失败：回 idle + 错误提示，不崩', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      b.handlers['/api/v1/records'] = (req) =>
          Future.value(_json({'error': '服务异常'}, status: 500));
      await _pump(tester, b);

      await tester.tap(find.text('提问'));
      await tester.pumpAndSettle();

      expect(find.textContaining('请求失败 (500)'), findsOneWidget); // SnackBar
      expect(tester.takeException(), isNull);
    });

    testWidgets('删除卡：菜单删除 → 记录移除 → 空态', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '待删除记录')]
        ..feedTotalToday = 1;
      b.handlers['/api/v1/records/r1'] = (_) => Future.value(_json({'ok': true}));
      await _pump(tester, b);

      await tester.tap(find.byIcon(Icons.more_vert_rounded));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删除'));
      await tester.pumpAndSettle();

      expect(find.text('待删除记录'), findsNothing);
      expect(find.text('还没有记录'), findsOneWidget);
      expect(b.requests.where((r) => r.method == 'DELETE').length, 1);
    });

    testWidgets('加载更多：page1 追加更早记录，横幅消失', (tester) async {
      // 3 卡保证初始在视口内不滚动（atTop=true 横幅可见）；totalToday=5 触发加载
      final b = _Backend()
        ..feedPage0 = [
          _record('r3', '今日3'), _record('r2', '今日2'), _record('r1', '今日1'),
        ]
        ..feedPage1 = [_record('o2', '昨日2'), _record('o1', '昨日1')]
        ..feedTotalToday = 5;
      await _pump(tester, b);

      expect(find.text('加载更多'), findsOneWidget);
      await tester.tap(find.text('加载更多'));
      await tester.pumpAndSettle();

      expect(find.text('昨日2'), findsOneWidget);
      expect(find.text('昨日1'), findsOneWidget);
      expect(find.text('加载更多'), findsNothing);
    });

    testWidgets('竞态 #100：追加挂起时结束对话，回复不丢不崩', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      final askGate = Completer<http.Response>();
      final appendGate = Completer<http.Response>();
      b.handlers['/api/v1/records'] = (req) {
        final intent = jsonDecode(req.body)['intent'];
        if (intent == 'question') return askGate.future;
        return appendGate.future; // 追加（无 intent）挂起
      };
      await _pump(tester, b);

      // 进入 chatting
      await tester.tap(find.text('提问'));
      await tester.pump();
      askGate.complete(_json({
        'intent': 'question', 'recordId': 'r-q',
        'rawResponse': 'AI 回答内容', 'summary': 'AI 回答内容',
        'tags': [], 'domain': 'life',
      }));
      await tester.pumpAndSettle();

      // 追加输入 → POST 挂起（appendGate 未完成）
      await tester.enterText(find.byType(TextField), '追加问题');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();

      // 追加未返回时结束对话（endConversation 立即完成）
      // 注：active 布局外层 onDoubleTap，需等 double-tap 超时 tap 才触发
      await tester.tap(find.text('end conversation'));
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pump(const Duration(milliseconds: 100));

      // 迟到的追加回复到达
      appendGate.complete(_json({
        'intent': 'log', 'recordId': 'r-l', 'summary': '记录完成',
        'rawResponse': '补充回答', 'tags': [], 'domain': 'life',
      }));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      // 不崩 + 追加的用户 turn 与 AI 回复都保留
      expect(tester.takeException(), isNull);
      expect(find.text('追加问题'), findsOneWidget);
      expect(find.textContaining('补充回答', findRichText: true), findsOneWidget);
    });
  });
}
