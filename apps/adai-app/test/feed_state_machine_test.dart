import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/main_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/widgets/input_bar.dart';

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
        {String intent = 'log', String time = '14:00', String date = '08-04',
        String? summary, String? mediaPath}) =>
    {
      'type': 'record', 'id': id, 'title': '', 'content': content,
      'tags': <String>[], 'time': time, 'date': date,
      'intent': intent, 'summary': summary, 'turns': null,
      'domain': 'life', 'mediaPath': mediaPath,
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

/// 图片记录卡条目 JSON（L4：mediaPath 非空 → 卡带缩略图 + 可追问）。
Map<String, dynamic> _imageRecord(String id, String summary) => {
  'type': 'record', 'id': id, 'title': '', 'content': summary,
  'tags': <String>[], 'time': '14:00', 'date': '08-04',
  'intent': 'log', 'summary': summary, 'turns': null,
  'domain': 'life', 'mediaPath': 'media/$id.png',
};

/// 附加条目 JSON（action 待办 / market 行情 / push 推送——仅 page 0 附带，不占分页进度）。
Map<String, dynamic> _attached(String type, String id, String content) => {
  'type': type, 'id': id, 'title': '', 'content': content,
  'tags': <String>[], 'time': '14:00', 'date': '08-04',
  'intent': null, 'summary': null, 'turns': null,
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

    testWidgets('初始 Feed：记录卡显示内容 + 提问按钮（2026-08-17 第一原则：无记录/领域徽章）', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      await _pump(tester, b);
      expect(find.text('今天买了立昂微'), findsOneWidget);
      expect(find.text('记录'), findsNothing); // 第一原则：无「记录」系统徽章
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

    testWidgets('图片卡 ask：点提问 → 输入问题 → VLM 回答显示 + 走 ask 端点', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_imageRecord('img1', '持仓截图：浦发银行')]
        ..feedTotalToday = 1;
      b.handlers['/api/v1/records/media/img1/ask'] = (_) => Future.value(_json(
          {'recordId': 'qa1', 'answer': '这是浦发银行，持仓约 1000 股。', 'imageRecordId': 'img1'}));
      await _pump(tester, b);

      // 图片卡：无「记录」系统徽章 + 底部提问按钮（summary 同时渲染于 body 与干净摘要行）
      expect(find.text('持仓截图：浦发银行'), findsWidgets);
      expect(find.text('提问'), findsOneWidget);

      // 点提问 → 进入追问态，但不触发文本 createRecord
      await tester.tap(find.text('提问'));
      await tester.pump();
      expect(b.requests.where((r) => r.url.path == '/api/v1/records'), isEmpty,
          reason: '图片 ask 不应走文本 createRecord');

      // 输入问题并发送
      await tester.enterText(find.byType(TextField), '这是什么股票？');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      // VLM 回答显示在图片卡下
      expect(find.textContaining('这是浦发银行，持仓约 1000 股。', findRichText: true), findsOneWidget);
      // 请求走了 ask 端点，body 带问题
      final askReqs = b.requests.where((r) => r.url.path == '/api/v1/records/media/img1/ask');
      expect(askReqs.length, 1);
      expect(jsonDecode(askReqs.first.body)['question'], '这是什么股票？');
    });

    testWidgets('文本卡 ask 竞态：首轮保持「卡片原内容 + 新消息」（P2 回归）', (tester) async {
      // 文本卡点提问后 ask 请求挂起期间直接输入 → _appendToActiveCard 走空 turns 分支。
      // 该分支必须保留原行为：首轮 = 卡片原内容（作上下文）+ 用户新消息。
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

      // 点提问（ask 挂起）→ 直接输入发送
      await tester.tap(find.text('提问'));
      await tester.pump();
      await tester.enterText(find.byType(TextField), '再问一句');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();

      // 空 turns 分支：卡片原内容作为首轮 + 新消息（restore 后行为）
      expect(find.text('今天买了立昂微'), findsWidgets);
      expect(find.text('再问一句'), findsOneWidget);

      // 释放 ask，避免悬挂
      askGate.complete(_json({
        'intent': 'question', 'recordId': 'r-q',
        'rawResponse': 'AI 回答内容', 'summary': 'AI 回答内容', 'tags': [], 'domain': 'life',
      }));
      await tester.pumpAndSettle();
    });

    testWidgets('点提问：已有对话的卡重开，不重复 POST', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_cardWithTurns('r1', '今天的天气如何')]
        ..feedTotalToday = 1;
      await _pump(tester, b);

      // question 卡：无「提问」系统徽章，仅底部按钮（第一原则 2026-08-17）
      expect(find.text('提问'), findsOneWidget);
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
      await tester.tap(find.text('结束对话'));
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      // 回到 feed：ended 卡显示总结，底部提问按钮仍在，无结束按钮
      expect(find.text('对话总结'), findsOneWidget);
      expect(find.text('结束对话'), findsNothing);
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
      await tester.tap(find.text('删除')); // 菜单项
      await tester.pumpAndSettle();
      // REVIEW P1-W8：确认弹窗
      await tester.tap(find.text('删除').last); // 弹窗确认按钮
      await tester.pumpAndSettle();

      expect(find.text('待删除记录'), findsNothing);
      expect(find.text('还没有记录'), findsOneWidget);
      expect(b.requests.where((r) => r.method == 'DELETE').length, 1);
    });

    testWidgets('加载更早：page1 追加更早记录，横幅消失', (tester) async {
      // 3 卡保证初始在视口内不滚动（atTop=true 横幅可见）；totalToday=5 触发加载
      final b = _Backend()
        ..feedPage0 = [
          _record('r3', '今日3'), _record('r2', '今日2'), _record('r1', '今日1'),
        ]
        ..feedPage1 = [_record('o2', '昨日2'), _record('o1', '昨日1')]
        ..feedTotalToday = 5;
      await _pump(tester, b);

      expect(find.text('加载更早'), findsOneWidget);
      await tester.tap(find.text('加载更早'));
      await tester.pumpAndSettle();

      expect(find.text('昨日2'), findsOneWidget);
      expect(find.text('昨日1'), findsOneWidget);
      expect(find.text('加载更早'), findsNothing);
    });

    testWidgets('REVIEW #234：附加条目不占分页进度，核心未加载完仍显示「加载更早」', (tester) async {
      // page 0 带 2 条附加条目（action/market）+ 1 条核心记录；totalToday=3（只计核心）。
      // 旧逻辑 _cards.length(3) >= totalToday(3) → 误判「无更多」，「加载更早」消失、最旧核心不可达；
      // 修复后按已加载核心数 1 < 3 → 仍显示「加载更早」，追加后按核心数 3 >= 3 隐藏。
      final b = _Backend()
        ..feedPage0 = [
          _record('r1', '今日核心记录'),
          _attached('action', 'a1', '提醒：完成复盘'),
          _attached('market', 'm1', '上证指数 3456.78 +0.12%'),
        ]
        ..feedPage1 = [_record('o2', '昨日2'), _record('o1', '昨日1')]
        ..feedTotalToday = 3;
      await _pump(tester, b);

      expect(find.text('加载更早'), findsOneWidget);
      await tester.tap(find.text('加载更早'));
      await tester.pumpAndSettle();

      expect(find.text('昨日2'), findsOneWidget);
      expect(find.text('昨日1'), findsOneWidget);
      expect(find.text('加载更早'), findsNothing);
    });

    testWidgets('RFC 20260817：push 推送卡渲染类型徽章 + 内容', (tester) async {
      final b = _Backend()
        ..feedPage0 = [
          {
            'type': 'push', 'id': 'p1',
            'title': '尾盘建议', 'content': '· 京东方A 现价 6.08（+0.63%） → 持有',
            'tags': <String>[], 'time': '14:50', 'date': '08-17',
            'intent': null, 'summary': null, 'turns': null,
            'domain': 'trading', 'mediaPath': null,
          },
        ]
        ..feedTotalToday = 1;
      await _pump(tester, b);

      expect(find.text('尾盘建议'), findsOneWidget); // 类型徽章
      expect(find.textContaining('京东方A'), findsOneWidget); // 内容
      expect(find.text('左滑删除 · 右滑设置推送'), findsOneWidget);
    });

    testWidgets('RFC 20260817：今日操作确认卡显示「确认并入账」按钮', (tester) async {
      final b = _Backend()
        ..feedPage0 = [
          {
            'type': 'push', 'id': 'p2',
            'title': '今日操作确认',
            'content': '· 京东方A 卖出 5300 股 @6.10\n是否完整？不完整说一声。',
            'tags': <String>[], 'time': '15:15', 'date': '08-17',
            'intent': null, 'summary': null, 'turns': null,
            'domain': 'trading', 'mediaPath': null,
          },
        ]
        ..feedTotalToday = 1;
      await _pump(tester, b);

      // 徽章映射：今日操作确认 → 尾盘建议色/文案
      expect(find.text('尾盘建议'), findsOneWidget);
      expect(find.textContaining('卖出 5300 股'), findsOneWidget);
      expect(find.text('确认并入账'), findsOneWidget);
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
      await tester.tap(find.text('结束对话'));
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

  group('MD1 世界切回 Feed 刷新', () {
    testWidgets('refreshTick 递增后重载 Feed（覆盖 admin 记忆重建后陈旧）', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '重建前的内容')]
        ..feedTotalToday = 1;
      final tick = ValueNotifier<int>(0);
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient(b.handle),
      );
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: MainPage(api: api, refreshTick: tick)),
      ));
      await tester.pumpAndSettle();
      expect(find.text('重建前的内容'), findsOneWidget);

      // 模拟 adai-admin 记忆重建后：后端数据变化 → 壳层递增信号 → Feed 重载
      b.feedPage0 = [_record('r2', '重建后的新内容')];
      tick.value++;
      await tester.pumpAndSettle();

      expect(find.text('重建后的新内容'), findsOneWidget);
      expect(find.text('重建前的内容'), findsNothing);
    });
  });

  group('REVIEW #235/#245 图片上传占位卡', () {
    testWidgets('上传失败 → 占位卡 error → 重试走 media 接口重传成功（非降级文本记录）', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      // 首次 /records/media 失败，重试成功
      var mediaCalls = 0;
      final mediaRequests = <http.Request>[];
      b.handlers['/api/v1/records/media'] = (req) {
        mediaCalls++;
        mediaRequests.add(req);
        if (mediaCalls == 1) {
          return Future.value(http.Response('{"error":"模拟超时"}', 500,
              headers: {'content-type': 'application/json'}));
        }
        // 上传成功 → Feed 里出现该媒体记录（重试后 _loadFeed 能读到）
        b.feedPage0 = [_record('rec_media_001', '我的截图',
            summary: '图片内容理解', mediaPath: 'records/2026/08/media/x.png')];
        b.feedTotalToday = 1;
        return Future.value(_json({
          'recordId': 'rec_media_001', 'intent': 'log',
          'summary': '图片内容理解', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/x.png',
        }));
      };
      await _pump(tester, b);

      // 注入一张待发送图片并发送
      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_001.jpg', 'jpg')]);
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // 首次失败 → 占位卡 error 态（含重试按钮）
      expect(mediaCalls, 1);
      expect(find.text('重试'), findsWidgets);
      // 关键断言：失败后没有把图片文件名写成文本记录（未走 /records 文本接口）
      expect(b.requests.where((r) => r.url.path == '/api/v1/records').length, 0);

      // 点重试 → 重走 media 接口（不降级文本）
      await tester.tap(find.text('重试').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      expect(mediaCalls, 2);
      // 成功卡替换占位卡，显示 AI 理解文本，不再显示重试
      expect(find.text('图片内容理解'), findsOneWidget);
      expect(find.text('重试'), findsNothing);
    });

    testWidgets('上传成功：content 保留 caption（fallback），summary 单独放 AI 文本，不同源不重复渲染', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media'] = (req) {
        b.feedPage0 = [_record('rec_media_002', '我的截图',
            summary: 'AI 图片理解', mediaPath: 'records/2026/08/media/y.png')];
        b.feedTotalToday = 1;
        return Future.value(_json({
          'recordId': 'rec_media_002', 'intent': 'log',
          'summary': 'AI 图片理解', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/y.png',
        }));
      };
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([4, 5, 6], 'IMG_002.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '我的截图');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // caption「我的截图」保留为记录内容（卡 content 或成功 SnackBar 至少一处）；
      // AI 理解文本作为 summary 展示且只渲染一次（#245 核心：content 与 summary 不同源，不重复）
      expect(find.textContaining('我的截图', findRichText: true), findsWidgets);
      expect(find.textContaining('AI 图片理解', findRichText: true), findsOneWidget);
    });

    testWidgets('RFC 20260815 发图带问句：ask-batch 返回 question → 直进对话态（不刷新 Feed）', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media'] = (req) {
        return Future.value(_json({
          'recordId': 'rec_media_ask', 'intent': 'log',
          'summary': '图片理解', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/ask.png',
        }));
      };
      b.handlers['/api/v1/records/media/ask-batch'] = (req) {
        final body = jsonDecode(req.body);
        return Future.value(_json({
          'intent': 'question', 'answer': '左图是持仓，右图是走势。',
          'recordId': 'qa1', 'imageRecordIds': body['imageRecordIds'],
        }));
      };
      await _pump(tester, b);
      final feedCallsBefore = b.requests.where((r) => r.url.path == '/api/v1/feed').length;

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_A.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '这两张图分别是什么？');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // ask-batch 已调用且携带图片 id 与问题原文
      final askReqs = b.requests.where((r) => r.url.path == '/api/v1/records/media/ask-batch');
      expect(askReqs.length, 1);
      final body = jsonDecode(askReqs.first.body);
      expect(body['imageRecordIds'], ['rec_media_ask']);
      expect(body['question'], '这两张图分别是什么？');

      // P0 核心：不再只是 SnackBar——直进对话态（对话 badge + 问句/回答气泡 + 结束对话）
      expect(find.text('结束对话'), findsOneWidget);
      expect(find.text('对话'), findsOneWidget); // 对话 badge
      expect(find.text('这两张图分别是什么？'), findsOneWidget); // 用户问句气泡
      expect(find.textContaining('左图是持仓，右图是走势。', findRichText: true), findsOneWidget); // 阿呆回答气泡
      expect(find.textContaining('💬', findRichText: true), findsNothing); // 不再 SnackBar 截断回答

      // 不刷新 Feed（宿主卡身份稳定：本地首图卡 id=真实图片记录 id，S-2 聚合 id 漂移前端先避开）
      expect(b.requests.where((r) => r.url.path == '/api/v1/feed').length, feedCallsBefore);
    });

    testWidgets('RFC 20260815 发图带问句：判定中「🔍 阿呆正在看图…」状态条 → 返回后直进对话态', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      final uploadGate = Completer<http.Response>();
      final askGate = Completer<http.Response>();
      b.handlers['/api/v1/records/media'] = (req) => uploadGate.future;
      b.handlers['/api/v1/records/media/ask-batch'] = (req) => askGate.future;
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_J.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '这是什么？');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();

      // 上传中：占位卡本地预览（内存图，非空白/文件名）+ 上传进度 n/m
      expect(find.byType(Image), findsOneWidget);
      expect(find.text('📤 上传中 0/1'), findsOneWidget);

      // 上传完成 → 判定中状态条（上传进度条槽位复用，「🔍 阿呆正在看图…」）
      // 注：判定条为不定进度动画，此阶段只用显式 pump，不用 pumpAndSettle
      uploadGate.complete(_json({
        'recordId': 'rec_media_judge', 'intent': 'log',
        'summary': '一张截图', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/judge.png',
      }));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('🔍 阿呆正在看图…'), findsOneWidget);
      expect(find.text('📤 上传中 0/1'), findsNothing);

      // ask-batch 返回 question → 直进对话态（判定条消失，回答成为气泡）
      askGate.complete(_json({
        'intent': 'question', 'answer': '这是一张截图。',
        'recordId': 'qa', 'imageRecordIds': ['rec_media_judge'],
      }));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      await tester.pumpAndSettle();
      expect(find.text('🔍 阿呆正在看图…'), findsNothing);
      expect(find.text('结束对话'), findsOneWidget);
      expect(find.text('这是什么？'), findsOneWidget);
      expect(find.textContaining('这是一张截图。', findRichText: true), findsOneWidget);
    });

    testWidgets('RFC 20260815 发图纯图（无 caption）：log 落卡 + 阿呆自然回执（VLM summary，无系统文案）', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media'] = (req) {
        b.feedPage0 = [_record('rec_media_pure', 'IMG_P.jpg',
            summary: '傍晚的江边 🌇', mediaPath: 'records/2026/08/media/pure.png')];
        b.feedTotalToday = 1;
        return Future.value(_json({
          'recordId': 'rec_media_pure', 'intent': 'log',
          'summary': '傍晚的江边 🌇', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/pure.png',
        }));
      };
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_P.jpg', 'jpg')]);
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // 纯图无文字 → 不触发 ask-batch（拍下即记录，直接 log）
      expect(b.requests.where((r) => r.url.path == '/api/v1/records/media/ask-batch').length, 0);
      // 阿呆自然回执：VLM summary 拼「看到你…，已记下」（无第三视角，非「已记录 N 张」系统文案）
      expect(find.textContaining('看到你傍晚的江边 🌇，已记下', findRichText: true), findsOneWidget);
      expect(find.textContaining('已记录', findRichText: true), findsNothing);
      // log 落卡：刷新后记录卡可见（summary 行）
      expect(find.text('傍晚的江边 🌇'), findsWidgets);
    });

    testWidgets('RFC 20260815 附图 + 陈述文本：ask-batch 返回 log → 自然回执落卡，无回答气泡', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media'] = (req) => Future.value(_json({
        'recordId': 'rec_media_log', 'intent': 'log',
        'summary': '图片理解', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/log.png',
      }));
      b.handlers['/api/v1/records/media/ask-batch'] = (req) => Future.value(_json({
        'intent': 'log', 'answer': '',
        'recordId': '', 'imageRecordIds': ['rec_media_log'],
      }));
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_B.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '这是今天的持仓截图');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // ask-batch 调用但返回 log → 阿呆自然回执「看到你…，已记下」，不直进对话态、无 💬 回答
      expect(b.requests.where((r) => r.url.path == '/api/v1/records/media/ask-batch').length, 1);
      expect(find.text('结束对话'), findsNothing, reason: 'log 不进入对话态');
      expect(find.textContaining('💬', findRichText: true), findsNothing);
      expect(find.textContaining('看到你图片理解，已记下', findRichText: true), findsOneWidget);
    });

    testWidgets('RFC 20260815 ask-batch 失败：判定条复位 + 阿呆提示，卡片保持记录卡形态，不崩', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media'] = (req) => Future.value(_json({
        'recordId': 'rec_media_fail', 'intent': 'log',
        'summary': '图片理解', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/fail.png',
      }));
      b.handlers['/api/v1/records/media/ask-batch'] = (req) =>
          Future.value(_json({'error': 'AI 超时'}, status: 500));
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_F.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '这图什么情况？');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      // 判定条复位（不再「正在看图」），不进对话态；阿呆自然提示（B10）
      expect(find.text('🔍 阿呆正在看图…'), findsNothing);
      expect(find.text('结束对话'), findsNothing);
      expect(find.textContaining('阿呆没看懂这张图，再试一次？', findRichText: true), findsOneWidget);
    });

    testWidgets('RFC 20260815 发图带问句 → 对话态连续追问（askMedia）→ 结束沉淀为带图总结卡', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media'] = (req) => Future.value(_json({
        'recordId': 'rec_media_chat', 'intent': 'log',
        'summary': '一张 K 线图', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/chat.png',
      }));
      b.handlers['/api/v1/records/media/ask-batch'] = (req) => Future.value(_json({
        'intent': 'question', 'answer': '这是一张 K 线图，近期震荡。',
        'recordId': 'qa1', 'imageRecordIds': ['rec_media_chat'],
      }));
      b.handlers['/api/v1/records/media/rec_media_chat/ask'] = (_) => Future.value(_json({
        'recordId': 'qa2', 'answer': '压力位在 3500 附近。', 'imageRecordId': 'rec_media_chat',
      }));
      await _pump(tester, b);

      // 发图 + 问句 → 直进对话态
      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_C.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '这图怎么看？');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();
      expect(find.text('结束对话'), findsOneWidget);
      expect(find.text('这图怎么看？'), findsOneWidget);

      // 连续追问 → 走单图 ask（图即上下文，VLM 看图回答），气泡追加
      await tester.enterText(find.byType(TextField), '压力位在哪？');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      expect(find.text('压力位在哪？'), findsOneWidget);
      expect(find.textContaining('压力位在 3500 附近。', findRichText: true), findsOneWidget);
      final askReqs = b.requests.where((r) => r.url.path == '/api/v1/records/media/rec_media_chat/ask');
      expect(askReqs.length, 1);
      expect(jsonDecode(askReqs.first.body)['question'], '压力位在哪？');

      // 结束对话 → ended 带图总结卡（✓总结 banner + 提问入口，无「结束对话」按钮）
      // 注：active 布局外层 onDoubleTap，tap 需等 double-tap 超时（300ms）才触发
      await tester.tap(find.text('结束对话'));
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();
      expect(find.text('对话总结'), findsOneWidget);
      expect(find.text('结束对话'), findsNothing);
      expect(find.text('提问'), findsWidgets);
    });

    testWidgets('P3-8：上传批次锁——上传期间再次发送被拒（进度条不互相覆盖）', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      // media 接口挂起（不返回），让上传处于进行中
      final mediaGate = Completer<http.Response>();
      b.handlers['/api/v1/records/media'] = (_) => mediaGate.future;
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      // 第一批：注入并发送（上传挂起）
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_1.jpg', 'jpg')]);
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      // 上传中：第二次发送应被批次锁拒绝，提示等待
      inputState.debugInjectImages([PickedImage([4, 5, 6], 'IMG_2.jpg', 'jpg')]);
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.textContaining('上一批图片还在上传'), findsOneWidget);

      // 放行第一批 → 上传完成，进度条隐藏
      mediaGate.complete(_json({
        'recordId': 'rec_media_1', 'intent': 'log',
        'summary': '第一张', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/1.png',
      }));
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();
      expect(find.textContaining('上传中'), findsNothing);
    });
  });

  group('P0-1/P1-1/P1-2 deep 审核修复回归', () {
    testWidgets('P0-1/P1-1：对话态发媒体 → 静默退出对话视图，图片正常记录，不崩溃', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      b.handlers['/api/v1/records/media'] = (req) {
        b.feedPage0 = [_record('rec_media_exit', '我的截图',
            summary: 'AI 图片理解', mediaPath: 'records/2026/08/media/exit.png')];
        b.feedTotalToday = 1;
        return Future.value(_json({
          'recordId': 'rec_media_exit', 'intent': 'log',
          'summary': 'AI 图片理解', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/exit.png',
        }));
      };
      b.handlers['/api/v1/records/media/ask-batch'] = (req) => Future.value(_json(
          {'intent': 'log', 'answer': '', 'recordId': '', 'imageRecordIds': []}));
      await _pump(tester, b);

      // 进入对话态（chatting）
      await tester.tap(find.text('提问'));
      await tester.pumpAndSettle();
      expect(find.text('结束对话'), findsOneWidget); // 对话视图已打开

      // 对话态直接发媒体（带 caption）→ 不应崩溃（P0-1 触发链：_loadFeed 挤出活动卡）
      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_EXIT.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '这是一张截图');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // P1-1：对话视图已退出（无「结束对话」按钮），回到普通 Feed；图片已记录
      expect(find.text('结束对话'), findsNothing);
      expect(find.text('AI 图片理解'), findsOneWidget);
    });

    testWidgets('P0-1：对话态 refreshTick 刷新挤出活动卡 → 静默退出对话，不崩溃', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '今天买了立昂微')]
        ..feedTotalToday = 1;
      final tick = ValueNotifier<int>(0);
      final api = ApiService(baseUrl: 'http://test', client: MockClient(b.handle));
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: MainPage(api: api, refreshTick: tick)),
      ));
      await tester.pumpAndSettle();

      // 进入对话态（activeCardId = r1）
      await tester.tap(find.text('提问'));
      await tester.pumpAndSettle();
      expect(find.text('结束对话'), findsOneWidget);

      // 刷新后活动卡 r1 被挤出 page0（新记录替换）→ 不崩溃，静默退出对话态
      b.feedPage0 = [_record('r2', '新记录把对话挤出')];
      b.feedTotalToday = 1;
      tick.value++;
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull); // 无 activeCard! 空值崩溃
      expect(find.text('结束对话'), findsNothing); // 对话态已退出
      expect(find.text('新记录把对话挤出'), findsOneWidget);
    });

    testWidgets('P1-2：部分上传失败 → 重试全部成功后补跑 ask-batch（问句不静默丢失）', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      var mediaCalls = 0;
      b.handlers['/api/v1/records/media'] = (req) {
        mediaCalls++;
        if (mediaCalls == 2) {
          return Future.value(http.Response('{"error":"模拟超时"}', 500,
              headers: {'content-type': 'application/json'}));
        }
        final id = mediaCalls == 1 ? 'rec_A' : 'rec_B';
        return Future.value(_json({
          'recordId': id, 'intent': 'log',
          'summary': '图片 $id', 'tags': ['图片'], 'mediaPath': 'records/2026/08/media/$id.png',
        }));
      };
      var askBatchCalls = 0;
      List<dynamic>? askBatchIds;
      b.handlers['/api/v1/records/media/ask-batch'] = (req) {
        askBatchCalls++;
        final body = jsonDecode(req.body);
        askBatchIds = body['imageRecordIds'];
        return Future.value(_json({
          'intent': 'question', 'answer': '两张图已看懂。',
          'recordId': 'qa', 'imageRecordIds': askBatchIds,
        }));
      };
      await _pump(tester, b);

      // 发 2 张图 + 问句：第一张成功、第二张失败
      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([
        PickedImage([1, 2, 3], 'IMG_C1.jpg', 'jpg'),
        PickedImage([4, 5, 6], 'IMG_C2.jpg', 'jpg'),
      ]);
      await tester.enterText(find.byType(TextField), '这两张是什么？');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // 部分失败：第二张占位卡 error，ask-batch 尚未补跑（问句 pending 保留）
      expect(mediaCalls, 2);
      expect(askBatchCalls, 0, reason: '还有失败卡未重试，问句应等全部完成后一并补跑');

      // 重试第二张成功 → 无剩余失败卡 → ask-batch 补跑且携带全部图片 id
      await tester.tap(find.text('重试').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      expect(mediaCalls, 3);
      expect(askBatchCalls, 1, reason: '重试完成后问句补跑，不静默丢失');
      expect(askBatchIds, ['rec_A', 'rec_B']);
      // 回答进 SnackBar 的渲染机制已在既有 ask-batch 测试验证（本测试聚焦补跑触发 + 全 id 覆盖，
      // 避免被重试后连续 SnackBar 排队的时序干扰）
    });
  });
}
