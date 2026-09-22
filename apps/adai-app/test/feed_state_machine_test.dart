import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/main_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/sse_client.dart';
import 'package:adai_app/pages/todo_page.dart';
import 'package:adai_app/widgets/feed_card.dart';
import 'package:adai_app/widgets/input_bar.dart';
import 'package:adai_app/widgets/media_thumb_strip.dart';

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
  // 2026-09-18 空态分流：这个用户有没有过历史记录（服务端判，不限当天）。
  bool feedHasHistory = false;
  /// 是否在 Feed 响应里带 `hasHistory` 字段——false 用来模拟**旧后端**，
  /// 契约要求前端此时降级为 false（新账号空态），不得崩/报错。
  bool feedIncludesHasHistory = true;

  final List<http.Request> requests = [];
  final Map<String, Future<http.Response> Function(http.Request)> handlers = {};

  _Backend() {
    handlers['/api/v1/brief'] = (_) => Future.value(_json({'content': ''}));
    handlers['/api/v1/brief/cached'] = (_) => Future.value(_json({'content': ''}));
    handlers['/api/v1/feed'] = (req) {
      final page = int.tryParse(req.url.queryParameters['page'] ?? '0') ?? 0;
      return Future.value(_json({
        'entries': page == 0 ? feedPage0 : feedPage1,
        'totalToday': feedTotalToday,
        if (feedIncludesHasHistory) 'hasHistory': feedHasHistory,
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

/// 多图回合卡条目 JSON（2026-09-22 多图批：`mediaPaths` = 本回合全部原图）。
Map<String, dynamic> _multiImageRecord(String id, List<String> mediaIds, String summary) => {
  'type': 'record', 'id': id, 'title': '', 'content': summary,
  'tags': <String>[], 'time': '14:00', 'date': '08-04',
  'intent': 'log', 'summary': summary, 'turns': null,
  'domain': 'life',
  'mediaPath': 'records/2026/09/media/${mediaIds.first}.jpg',
  'mediaPaths': mediaIds.map((m) => 'records/2026/09/media/$m.jpg').toList(),
};

/// 新契约（2026-09-22 多图批）：`POST /records/media/batch` 响应 JSON。
/// 一次投递 = 一条主记录（recordId = 卡片 id）+ 按顺序的附件记录 id（mediaIds）。
Map<String, dynamic> _batchResp({
  String recordId = 'rec_media_001',
  List<String>? mediaIds,
  String type = 'image',
  String intent = 'log',
  String summary = '图片内容理解',
  String? answer,
  List<String> tags = const ['图片'],
  String domain = 'life',
  bool duplicated = false,
}) => {
  'recordId': recordId,
  'mediaIds': mediaIds ?? [recordId],
  'type': type,
  'intent': intent,
  'summary': summary,
  'answer': answer,
  'tags': tags,
  'domain': domain,
  'duplicated': duplicated,
};

/// 附加条目 JSON（market 行情 / push 推送——仅 page 0 附带，不占分页进度）。
/// RFC 20260917 撤掉 Feed 待办卡后，附加条目只剩这两类。
Map<String, dynamic> _attached(String type, String id, String content) => {
  'type': type, 'id': id, 'title': '', 'content': content,
  'tags': <String>[], 'time': '14:00', 'date': '08-04',
  'intent': null, 'summary': null, 'turns': null,
  'domain': 'life', 'mediaPath': null,
};

/// 注入 mock 后端渲染 MainPage。
///
/// 默认 sseClient 注入 fail-fast 替身：非流式用例的 ask 走 askStream → SSE 不通 →
/// 自动降级同步端点（MockClient 挂起/响应，断言语义不变）；流式用例显式传入替身。
Future<_Backend> _pump(WidgetTester tester, _Backend backend, {SseClient? sseClient}) async {
  final api = ApiService(
    baseUrl: 'http://test',
    client: MockClient(backend.handle),
    sseClient: sseClient ?? _FailSse(),
  );
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: MainPage(api: api))));
  await tester.pumpAndSettle();
  return backend;
}

/// 当前已构建的 FeedCard 的 id 列表（视图层实际渲染出来的，用于查「同 id 两份」）。
List<String> _renderedCardIds(WidgetTester tester) => tester
    .widgetList<FeedCard>(find.byType(FeedCard))
    .map((w) => w.data.id)
    .toList();

/// SSE 永远不通（逼 askStream 降级同步端点）。
class _FailSse extends SseClient {
  _FailSse() : super();

  @override
  Future<void> post(
    Uri url, {
    Map<String, String>? headers,
    Object? body,
    required void Function(String data) onData,
  }) async {
    throw SseHttpException(503, 'test: sse disabled');
  }
}

void main() {
  group('Feed 状态机', () {
    testWidgets('空态：无记录显示阿呆开口的能力引导（中性文案，不假设新用户）+ 三个开场问句', (tester) async {
      final b = _Backend();
      await _pump(tester, b);
      // 2026-09-16「第一次见面」批：空态由「还没有记录 + 两个点不出结果的冷词 chip」
      // 改为阿呆先开口 + 三个能直接发问的开场
      // 2026-09-17 P1-UI14：老用户今天恰好没记录也会走到这里——文案不得假设「第一次见」
      expect(find.text('今天还没听你说点什么，随便问我一句——点下面的也行。'), findsOneWidget);
      expect(find.textContaining('第一次见'), findsNothing,
          reason: 'P1-UI14：空 Feed ≠ 新用户，不得出现「第一次见」这类假设新用户的说法');
      expect(find.textContaining('新用户'), findsNothing);
      expect(find.textContaining('我是阿呆'), findsNothing, reason: '空态不再自我介绍（老用户不是陌生人）');
      expect(find.text('你能干什么？'), findsOneWidget);
      expect(find.text('你有什么特别的能力？'), findsOneWidget);
      expect(find.text('我该怎么用你？'), findsOneWidget);
      expect(find.text('还没有记录'), findsNothing);
      expect(find.text('📝 记录心情'), findsNothing);
    });

    testWidgets('空态（老用户 hasHistory:true）：不摆新账号能力引导，改说「接着上次的聊也行」', (tester) async {
      final b = _Backend()..feedHasHistory = true;
      await _pump(tester, b);

      // 2026-09-18：老用户（有过历史记录）当天没记录时，Feed 也为空，但不能再被当成新账号。
      // 三个「你能干什么」是给真·新账号的能力引导，对老用户是噪声 + 冒犯。
      expect(find.text('你能干什么？'), findsNothing);
      expect(find.text('你有什么特别的能力？'), findsNothing);
      expect(find.text('我该怎么用你？'), findsNothing);
      expect(find.text('今天还没听你说点什么。接着上次的聊也行，我记着。'), findsOneWidget,
          reason: '老用户空态要承认他来过（「我记着」），并给出延续性动作');
      expect(find.text('今天还没听你说点什么，随便问我一句——点下面的也行。'), findsNothing,
          reason: '新账号那句「点下面的也行」指向的正是老用户看不到的三个问句');
      expect(find.text('也可以直接说点什么，或者丢张图给我。'), findsOneWidget,
          reason: '底部入口对两种账号都成立，老用户保留');
      expect(find.textContaining('第一次见'), findsNothing);
      expect(find.textContaining('新用户'), findsNothing);
      expect(find.textContaining('我是阿呆'), findsNothing);
    });

    testWidgets('空态（旧后端响应缺 hasHistory 字段）：降级为新账号空态，能力引导仍在', (tester) async {
      final b = _Backend()..feedIncludesHasHistory = false;
      await _pump(tester, b);

      // 契约：旧后端不返回该字段时必须降级为 false，行为与改造前完全一致（不崩、不报错）。
      expect(find.text('今天还没听你说点什么，随便问我一句——点下面的也行。'), findsOneWidget);
      expect(find.text('你能干什么？'), findsOneWidget);
      expect(find.text('你有什么特别的能力？'), findsOneWidget);
      expect(find.text('我该怎么用你？'), findsOneWidget);
    });

    testWidgets('开场问句：点击直接发问（不是预填），欢迎卡让位', (tester) async {
      final b = _Backend();
      await _pump(tester, b);

      await tester.tap(find.text('你能干什么？'));
      await tester.pumpAndSettle();

      // 必须真的发出请求——旧版「🤔 问个问题」只 prefillText('') 聚焦输入框，等于空操作
      final recordReqs = b.requests.where((r) => r.url.path == '/api/v1/records');
      expect(recordReqs.isNotEmpty, isTrue, reason: '开场问句必须真的发问');
      expect(jsonDecode(recordReqs.last.body)['content'], '你能干什么？');
      expect(find.text('今天还没听你说点什么，随便问我一句——点下面的也行。'), findsNothing,
          reason: '有了第一条记录后欢迎卡应让位');
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

    testWidgets('ask 流式：草稿 90ms 节流显示 → meta 定稿（P2-用户2 批 2）', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '怎么玩铜')]
        ..feedTotalToday = 1;
      final holdGate = Completer<void>();
      final sse = _HoldSse(holdGate);
      await _pump(tester, b, sseClient: sse);

      // 点提问 → 走 ask-stream SSE（不再调旧同步端点）
      await tester.tap(find.text('提问'));
      await tester.pump();
      expect(sse.lastPath, '/api/v1/records/ask-stream');
      expect(b.requests.where((r) => r.url.path == '/api/v1/records'), isEmpty,
          reason: '流式 ask 不落旧同步端点');
      expect(find.text('正在思考…'), findsOneWidget);

      // delta 已回调但 90ms 节流 Timer 未到 → 草稿尚未显示
      // 节流到点 → 草稿 turn 边到边显示已收增量
      await tester.pump(const Duration(milliseconds: 90));
      expect(find.textContaining('阿呆说到一半', findRichText: true), findsOneWidget);
      expect(find.text('正在思考…'), findsNothing, reason: '收到增量后 loading 气泡让位草稿');

      // 放行 → meta 定稿替换草稿
      holdGate.complete();
      await tester.pumpAndSettle();
      expect(find.textContaining('定稿回答内容', findRichText: true), findsOneWidget);
      expect(find.textContaining('阿呆说到一半', findRichText: true), findsNothing,
          reason: '草稿被 meta 定稿替换');
    });

    testWidgets('续问流式：meta 定稿原位替换草稿 turn，回答不重复（2026-08-30 重复回答回归）', (tester) async {
      final b = _Backend()
        ..feedPage0 = [_record('r1', '怎么玩铜')]
        ..feedTotalToday = 1;
      // 门控回放：delta 渲染出草稿后挂起，放行才回 meta——草稿 turn 必须真实存在，
      // 否则（同步完成时 90ms 节流 Timer 被 cancel）草稿从未渲染，复现不了追加型重复。
      final firstRound = _Round('首问草稿', '首问定稿回答');
      final followRound = _Round('续问草稿回答', '续问定稿回答');
      final sse = _SequentialHoldSse([firstRound, followRound]);
      await _pump(tester, b, sseClient: sse);

      // 首问：点提问 → delta 草稿渲染 → 放行 meta 定稿（chatting 态）
      await tester.tap(find.text('提问'));
      await tester.pump(const Duration(milliseconds: 90));
      expect(find.textContaining('首问草稿', findRichText: true), findsOneWidget,
          reason: '前置：草稿 turn 已真实渲染');
      firstRound.gate.complete();
      await tester.pumpAndSettle();
      expect(find.textContaining('首问定稿回答', findRichText: true), findsOneWidget);

      // 续问：chatting 态直接输入发送 → 走 _appendToActiveCard + ask-stream
      await tester.enterText(find.byType(TextField), '接着问');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump(const Duration(milliseconds: 90));
      expect(find.textContaining('续问草稿回答', findRichText: true), findsOneWidget,
          reason: '前置：续问草稿 turn 已真实渲染');

      // 放行 meta → 定稿必须原位替换草稿：回答只出现一遍
      followRound.gate.complete();
      await tester.pumpAndSettle();
      expect(find.textContaining('续问定稿回答', findRichText: true), findsOneWidget,
          reason: 'meta 定稿原位替换草稿 turn——回答只出现一遍（修复前草稿+定稿各渲染一遍）');
      expect(find.textContaining('续问草稿回答', findRichText: true), findsNothing,
          reason: '草稿被定稿替换，不残留');
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
      expect(find.text('今天还没听你说点什么，随便问我一句——点下面的也行。'), findsOneWidget);
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
      // page 0 带 2 条附加条目（market/push）+ 1 条核心记录；totalToday=3（只计核心）。
      // 旧逻辑 _cards.length(3) >= totalToday(3) → 误判「无更多」，「加载更早」消失、最旧核心不可达；
      // 修复后按已加载核心数 1 < 3 → 仍显示「加载更早」，追加后按核心数 3 >= 3 隐藏。
      final b = _Backend()
        ..feedPage0 = [
          _record('r1', '今日核心记录'),
          _attached('market', 'm1', '上证指数 3456.78 +0.12%'),
          _attached('push', 'p1', '尾盘建议'),
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

    // P1-前端3（2026-09-17 真机复发；与 09-16 P2-UI12 同族）：
    // 旧 _refreshFeed 按**位置**切旧页（sublist(0, length - _pageSize)）且从不比对 id，
    // 只在「_cards 恰好是纯核心条目」时成立。但 page0 会附带**全部**附加条目
    // （market 行情 / push 推送），今日核心只有 1 条时长度也被撑过
    // _pageSize(=5)，于是那条唯一的卡既落在 older 里、又在 freshCards 里 → 同 id 两份都渲染。
    // 真机路径：收行情推送（附加条目最多）时，推送深链触发 _refreshFeed。
    testWidgets('P1-前端3：附加条目撑破 pageSize 时刷新合并按 id 去重，同 id 不得两份', (tester) async {
      // 视口放大，让全部 6~7 张卡都构建出来（ListView 懒构建，默认 600 高看不全）
      tester.view.physicalSize = const Size(800, 4000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      // 1 条核心记录 + 5 条附加条目 → _cards.length(6) > _pageSize(5)，
      // 且核心卡正好落在旧实现的「更早页」区间 sublist(0, 6-5) = [r-today] 内。
      List<Map<String, dynamic>> page0() => [
            _record('r-today', '今天只有这一条记录'),
            _attached('market', 'm1', '行情快照一'),
            _attached('push', 'p1', '行情推送一'),
            _attached('market', 'm2', '行情快照二'),
            _attached('push', 'p2', '行情推送二'),
            _attached('market', 'm3', '行情快照三'),
          ];
      final b = _Backend()
        ..feedPage0 = page0()
        ..feedTotalToday = 1;
      final tick = ValueNotifier<int>(0);
      final api = ApiService(baseUrl: 'http://test', client: MockClient(b.handle));
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: MainPage(api: api, refreshTick: tick)),
      ));
      await tester.pumpAndSettle();
      expect(_renderedCardIds(tester), contains('r-today'));

      // 刷新（行情推送深链 / 下拉 RefreshIndicator 走的是同一条 _refreshFeed）
      b.feedPage0 = page0();
      tick.value++;
      await tester.pumpAndSettle();

      final ids = _renderedCardIds(tester);
      expect(ids.toSet().length, ids.length,
          reason: '刷新合并后同 id 不得出现两份（P1-前端3：同一对话被重复展示）');
      expect(ids.where((id) => id == 'r-today').length, 1);
      expect(find.text('今天只有这一条记录'), findsOneWidget);
    });
  });

  group('REVIEW #235/#245 图片上传占位卡', () {
    testWidgets('上传失败 → 占位卡 error → 重试走 media 接口重传成功（非降级文本记录）', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      // 首次 /records/media/batch 失败，重试成功（2026-09-22：一次投递一次请求）
      var mediaCalls = 0;
      final mediaRequests = <http.Request>[];
      b.handlers['/api/v1/records/media/batch'] = (req) {
        mediaCalls++;
        mediaRequests.add(req);
        if (mediaCalls == 1) {
          return Future.value(http.Response('{"error":"模拟超时"}', 500,
              headers: {'content-type': 'application/json'}));
        }
        // 投递成功 → Feed 里出现该媒体记录（重试后 _loadFeed 能读到）
        b.feedPage0 = [_record('rec_media_001', '我的截图',
            summary: '图片内容理解', mediaPath: 'records/2026/08/media/x.png')];
        b.feedTotalToday = 1;
        return Future.value(_json(_batchResp(
            recordId: 'rec_media_001', summary: '图片内容理解')));
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
      // 2026-09-22 幂等：重试必须复用**同一个** Idempotency-Key（后端据此识别同一次投递，不重复落盘）
      expect(mediaRequests.length, 2);
      expect(mediaRequests[0].headers['Idempotency-Key'], isNotEmpty);
      expect(mediaRequests[1].headers['Idempotency-Key'],
          mediaRequests[0].headers['Idempotency-Key'],
          reason: '重试复用同一幂等键——生成新键会让后端当成第二次投递、重复落盘');
      // 成功卡替换占位卡，显示 AI 理解文本，不再显示重试
      expect(find.text('图片内容理解'), findsOneWidget);
      expect(find.text('重试'), findsNothing);
    });

    testWidgets('上传成功：content 保留 caption（fallback），summary 单独放 AI 文本，不同源不重复渲染', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media/batch'] = (req) {
        b.feedPage0 = [_record('rec_media_002', '我的截图',
            summary: 'AI 图片理解', mediaPath: 'records/2026/08/media/y.png')];
        b.feedTotalToday = 1;
        return Future.value(_json(_batchResp(
            recordId: 'rec_media_002', summary: 'AI 图片理解')));
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
      // AI 理解文本作为 summary 展示且只渲染一次（#245 核心：content 与 summary 不同源，不重复）。
      // 用 descendant 限定在卡内：2026-09-22 起自然回执直接用后端 summary，SnackBar 也含同一句，
      // 不把「回执」算成「卡内重复」。
      expect(find.textContaining('我的截图', findRichText: true), findsWidgets);
      expect(
        find.descendant(
            of: find.byType(FeedCard),
            matching: find.textContaining('AI 图片理解', findRichText: true)),
        findsOneWidget,
      );
    });

    testWidgets('2026-09-22 一次投递 3 张：一个 batch 请求 + 一张卡 + 卡内并列 3 图（共 3 张）',
        (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      final gate = Completer<http.Response>();
      final batchReqs = <http.Request>[];
      b.handlers['/api/v1/records/media/batch'] = (req) {
        batchReqs.add(req);
        // 投递成功后的 Feed（一次投递 = 一条主记录，mediaPaths 带全部 3 图）
        b.feedPage0 = [
          _multiImageRecord('rec_triple', ['rec_a', 'rec_b', 'rec_c'], '三张图都在：江边、猫、晚饭')
        ];
        b.feedTotalToday = 1;
        return gate.future;
      };
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([
        PickedImage([1], 'a.jpg', 'jpg'),
        PickedImage([2], 'b.jpg', 'jpg'),
        PickedImage([3], 'c.jpg', 'jpg'),
      ]);
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();

      // 投递中：**1 张**占位卡（本地预览全部 3 图 + 「共 3 张」角标），不是 3 张占位卡
      expect(find.byType(FeedCard), findsOneWidget);
      expect(find.text('共 3 张'), findsOneWidget);
      expect(find.text('📤 上传中 3 张…'), findsOneWidget);

      gate.complete(_json(_batchResp(
        recordId: 'rec_triple',
        mediaIds: ['rec_a', 'rec_b', 'rec_c'],
        summary: '三张图都在：江边、猫、晚饭',
      )));
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // 只发一个请求：multipart 里 3 个 files 字段（一次投递一次请求，而非 3 次单图请求）
      expect(batchReqs.length, 1, reason: '一次投递 = 一次请求');
      final body = utf8.decode(batchReqs.first.bodyBytes);
      expect('name="files"'.allMatches(body).length, 3,
          reason: '3 张图挂在同一个 multipart 的 files 字段里');
      // 完成后仍是一张卡：卡内 3 图并列（MediaThumbStrip）+ 「共 3 张」
      expect(find.byType(FeedCard), findsOneWidget, reason: '一次投递 = 一张卡（旧实现 3 张卡）');
      expect(find.byType(MediaThumbStrip), findsOneWidget);
      expect(find.text('共 3 张'), findsOneWidget);
    });

    testWidgets('2026-09-22 Feed 多图回合卡（mediaPaths 3 条）→ 追问走 ask-batch 且带全部图 id',
        (tester) async {
      final b = _Backend()
        ..feedPage0 = [
          _multiImageRecord('rec_triple', ['rec_a', 'rec_b', 'rec_c'], '三张图：江边、猫、晚饭')
        ]
        ..feedTotalToday = 1;
      List<dynamic>? askIds;
      b.handlers['/api/v1/records/media/ask-batch'] = (req) {
        askIds = jsonDecode(req.body)['imageRecordIds'];
        return Future.value(_json({
          'intent': 'question', 'answer': '三张分别是江边、猫和晚饭。',
          'recordId': 'qa2', 'imageRecordIds': askIds,
        }));
      };
      await _pump(tester, b);

      // mediaPaths 消费点：卡内并列 3 图（不再是只有首图）
      expect(find.byType(MediaThumbStrip), findsOneWidget);
      expect(find.text('共 3 张'), findsOneWidget);

      await tester.tap(find.text('提问'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '这三张分别是什么？');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(askIds, ['rec_a', 'rec_b', 'rec_c'],
          reason: '多图回合追问必须带全部图 id（只带首图会让阿呆「少看几张」）');
      expect(find.textContaining('三张分别是江边、猫和晚饭。', findRichText: true), findsOneWidget);
      // 不走单图 ask 端点
      expect(b.requests.where((r) => r.url.path.endsWith('/ask')).length, 0);
    });

    testWidgets('2026-09-22 一次投递带提问：batch 返回 question → 直进对话态（不再二段式 ask-batch）',
        (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      // 一次投递一次请求：后端在同一个响应里给判定 + 回答（旧实现是「先逐张传、再 ask-batch」两段）
      b.handlers['/api/v1/records/media/batch'] = (req) => Future.value(_json(_batchResp(
            recordId: 'rec_media_ask',
            mediaIds: ['rec_media_ask'],
            type: 'image_qa', intent: 'question',
            summary: '两张图',
            answer: '左图是持仓，右图是走势。',
          )));
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

      // 只发一个 batch 请求（问句随 multipart text 字段走）；不再有二次 ask-batch 调用
      final batchReqs = b.requests.where((r) => r.url.path == '/api/v1/records/media/batch');
      expect(batchReqs.length, 1);
      expect(utf8.decode(batchReqs.first.bodyBytes), contains('这两张图分别是什么？'));
      expect(b.requests.where((r) => r.url.path == '/api/v1/records/media/ask-batch').length, 0,
          reason: '2026-09-22 新契约：判定与回答都在 batch 响应里，不再补跑 ask-batch');

      // P0 核心：直进对话态（对话 badge + 问句/回答气泡 + 结束对话）
      expect(find.text('结束对话'), findsOneWidget);
      expect(find.text('对话'), findsOneWidget); // 对话 badge
      expect(find.text('这两张图分别是什么？'), findsOneWidget); // 用户问句气泡
      expect(find.textContaining('左图是持仓，右图是走势。', findRichText: true), findsOneWidget); // 阿呆回答气泡
      expect(find.textContaining('💬', findRichText: true), findsNothing); // 不再 SnackBar 截断回答

      // 不刷新 Feed（宿主卡 = 本次投递主记录 id，避免聚合后 id 漂移）
      expect(b.requests.where((r) => r.url.path == '/api/v1/feed').length, feedCallsBefore);
    });

    testWidgets('2026-09-22 一次投递：「📤 上传中…」→（超阈值）「🔍 阿呆正在看图…」→ 返回后直进对话态',
        (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      final batchGate = Completer<http.Response>();
      b.handlers['/api/v1/records/media/batch'] = (req) => batchGate.future;
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_J.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '这是什么？');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();

      // 投递中：占位卡本地预览（内存图，非空白/文件名）+ 上传反馈（单图 → 「📤 上传中…」）
      expect(find.byType(Image), findsOneWidget);
      expect(find.text('📤 上传中…'), findsOneWidget);

      // 超过阈值（3 秒）→ 切成判定条「🔍 阿呆正在看图…」（同一槽位二态）
      // 注：判定条为不定进度动画，此阶段只用显式 pump，不用 pumpAndSettle
      await tester.pump(const Duration(seconds: 3));
      expect(find.text('🔍 阿呆正在看图…'), findsOneWidget);
      expect(find.text('📤 上传中…'), findsNothing);

      // batch 返回 question → 直进对话态（判定条消失，回答成为气泡）
      batchGate.complete(_json(_batchResp(
        recordId: 'rec_media_judge', mediaIds: ['rec_media_judge'],
        type: 'image_qa', intent: 'question', summary: '一张截图', answer: '这是一张截图。',
      )));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      await tester.pumpAndSettle();
      expect(find.text('🔍 阿呆正在看图…'), findsNothing);
      expect(find.text('结束对话'), findsOneWidget);
      expect(find.text('这是什么？'), findsOneWidget);
      expect(find.textContaining('这是一张截图。', findRichText: true), findsOneWidget);
    });

    testWidgets('2026-09-22 一次投递纯图（无 caption）：log 落卡 + 阿呆自然回执用后端综合总结', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media/batch'] = (req) {
        b.feedPage0 = [_record('rec_media_pure', 'IMG_P.jpg',
            summary: '傍晚的江边 🌇', mediaPath: 'records/2026/08/media/pure.png')];
        b.feedTotalToday = 1;
        return Future.value(_json(_batchResp(
            recordId: 'rec_media_pure', summary: '傍晚的江边 🌇')));
      };
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_P.jpg', 'jpg')]);
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // 纯图无文字 → 一次 batch 请求（拍下即记录，直接 log）；不再二段式 ask-batch
      expect(b.requests.where((r) => r.url.path == '/api/v1/records/media/batch').length, 1);
      expect(b.requests.where((r) => r.url.path == '/api/v1/records/media/ask-batch').length, 0);
      // 自然回执：直接用后端 summary（一段综合总结），不出现「已记录 N 张」系统文案
      expect(find.textContaining('傍晚的江边 🌇', findRichText: true), findsWidgets);
      expect(find.textContaining('已记录', findRichText: true), findsNothing);
      // log 落卡：刷新后记录卡可见（summary 行）
      expect(find.text('傍晚的江边 🌇'), findsWidgets);
    });

    testWidgets('2026-09-22 一次投递附图 + 陈述文本：batch 返回 log → 自然回执落卡，无回答气泡', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media/batch'] = (req) => Future.value(_json(_batchResp(
        recordId: 'rec_media_log', summary: '图片理解',
      )));
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_B.jpg', 'jpg')]);
      await tester.enterText(find.byType(TextField), '这是今天的持仓截图');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      // 用户那句话随 multipart 的 text 字段一次发出（后端据此判定 intent）
      final batchReqs = b.requests.where((r) => r.url.path == '/api/v1/records/media/batch');
      expect(batchReqs.length, 1);
      expect(utf8.decode(batchReqs.first.bodyBytes), contains('这是今天的持仓截图'));

      // batch 返回 log → 阿呆自然回执（用后端 summary），不直进对话态、无 💬 回答
      expect(find.text('结束对话'), findsNothing, reason: 'log 不进入对话态');
      expect(find.textContaining('💬', findRichText: true), findsNothing);
      expect(find.textContaining('图片理解', findRichText: true), findsWidgets);
    });

    testWidgets('2026-09-22 一次投递失败：判定条复位 + 占位卡转 error 可重试，不崩', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      b.handlers['/api/v1/records/media/batch'] =
          (req) => Future.value(_json({'error': 'AI 超时'}, status: 500));
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
      // 判定条复位（不再「正在看图」），不进对话态；占位卡转 error（可重试，图片不丢）
      expect(find.text('🔍 阿呆正在看图…'), findsNothing);
      expect(find.text('结束对话'), findsNothing);
      expect(find.text('重试'), findsWidgets);
    });

    testWidgets('RFC 20260815 发图带问句 → 对话态连续追问（askMedia）→ 结束沉淀为带图总结卡', (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      // 2026-09-22 一次投递：判定 + 首答都在 batch 响应里（单图回合 → 首题由后端直接作答）
      b.handlers['/api/v1/records/media/batch'] = (req) => Future.value(_json(_batchResp(
        recordId: 'rec_media_chat', mediaIds: ['rec_media_chat'],
        type: 'image_qa', intent: 'question', summary: '一张 K 线图',
        answer: '这是一张 K 线图，近期震荡。',
      )));
      // 单图回合的后续追问仍走单图 askMedia（多图回合才走 ask-batch，见多图用例）
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
      // media 接口挂起（不返回），让投递处于进行中
      final mediaGate = Completer<http.Response>();
      b.handlers['/api/v1/records/media/batch'] = (_) => mediaGate.future;
      await _pump(tester, b);

      final inputState = tester.state<InputBarState>(find.byType(InputBar));
      // 第一批：注入并发送（投递挂起）
      inputState.debugInjectImages([PickedImage([1, 2, 3], 'IMG_1.jpg', 'jpg')]);
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      // 投递中：第二次发送应被批次锁拒绝，提示等待；且**输入栏保留图片**（2026-09-22 修静默丢图）
      inputState.debugInjectImages([PickedImage([4, 5, 6], 'IMG_2.jpg', 'jpg')]);
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.textContaining('上一批图片还在上传'), findsOneWidget);
      expect(find.text('1/3'), findsOneWidget,
          reason: '被拒时输入栏不清空——修复前先 clear 再回调，图片消失且未上传（静默丢图）');

      // 放行第一批 → 投递完成，进度条隐藏
      mediaGate.complete(_json(_batchResp(recordId: 'rec_media_1', summary: '第一张')));
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
      b.handlers['/api/v1/records/media/batch'] = (req) {
        b.feedPage0 = [_record('rec_media_exit', '我的截图',
            summary: 'AI 图片理解', mediaPath: 'records/2026/08/media/exit.png')];
        b.feedTotalToday = 1;
        return Future.value(_json(_batchResp(
            recordId: 'rec_media_exit', summary: 'AI 图片理解')));
      };
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
      // 用 descendant 限定在卡内（自然回执 SnackBar 也含同一句 summary）
      expect(
        find.descendant(of: find.byType(FeedCard), matching: find.text('AI 图片理解')),
        findsOneWidget,
      );
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

    testWidgets('2026-09-22 一次投递失败 → 重试复用同一 Idempotency-Key → 成功后按 batch 判定分流',
        (tester) async {
      final b = _Backend()
        ..feedPage0 = []
        ..feedTotalToday = 0;
      var mediaCalls = 0;
      final batchReqs = <http.Request>[];
      // 语义变更（2026-09-22 多图批）：一次投递要么整批成功、要么整批失败——不再存在
      // 「第 1 张成功、第 2 张失败」的部分失败（那正是逐张串行上传的旧形态，也是重复落盘的来源）。
      // 因此本用例锁的是「失败 → 重试 → 同一幂等键 → 成功后按判定分流」，替代旧的 pending 补跑断言。
      b.handlers['/api/v1/records/media/batch'] = (req) {
        mediaCalls++;
        batchReqs.add(req);
        if (mediaCalls == 1) {
          return Future.value(http.Response('{"error":"模拟超时"}', 500,
              headers: {'content-type': 'application/json'}));
        }
        return Future.value(_json(_batchResp(
          recordId: 'rec_qa', mediaIds: ['rec_A', 'rec_B'],
          type: 'image_qa', intent: 'question', summary: '两张图',
          answer: '两张图已看懂。',
        )));
      };
      await _pump(tester, b);

      // 发 2 张图 + 问句：整批失败
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

      expect(mediaCalls, 1, reason: '一次投递只发一个 batch 请求（旧实现逐张 = 2 个请求）');
      expect(find.text('重试'), findsWidgets);

      // 重试 → 复用同一幂等键 → 整批成功
      await tester.tap(find.text('重试').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      expect(mediaCalls, 2);
      expect(batchReqs[1].headers['Idempotency-Key'], batchReqs[0].headers['Idempotency-Key'],
          reason: '重试复用同一幂等键 → 后端识别同一次投递，不重复落盘');
      // 成功后按后端判定分流：question → 直进对话态（多图回合宿主卡 = 主记录 id）
      expect(find.text('结束对话'), findsOneWidget);
      expect(find.textContaining('两张图已看懂。', findRichText: true), findsOneWidget);
    });
  });

  // ── RFC 20260917：待办到期提醒的深链 `todo:today` ──

  testWidgets('待办到期推送点进来 → 直接打开待办清单，不在 Feed 里猜', (tester) async {
    final backend = _Backend();
    backend.handlers['/api/v1/todos'] = (_) => Future.value(_json(<Map<String, dynamic>>[]));
    final link = ValueNotifier<String?>(null);
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient(backend.handle),
      sseClient: _FailSse(),
    );
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: MainPage(api: api, pushDeepLink: link)),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(TodoPage), findsNothing);

    link.value = 'todo:today';
    await tester.pumpAndSettle();

    expect(find.byType(TodoPage), findsOneWidget,
        reason: '待办到期通知应带用户回到待办清单（Feed 已不再出现待办卡）');
  });
}

/// 流式 ask 替身：post 时同步回放两段增量后挂起，等 [holdGate] 放行再回 meta + [DONE]。
class _HoldSse extends SseClient {
  _HoldSse(this.holdGate) : super();

  final Completer<void> holdGate;

  String? lastPath;

  @override
  Future<void> post(
    Uri url, {
    Map<String, String>? headers,
    Object? body,
    required void Function(String data) onData,
  }) async {
    lastPath = url.path;
    onData(jsonEncode({'type': 'text', 'content': '阿呆说'}));
    onData(jsonEncode({'type': 'text', 'content': '到一半'}));
    await holdGate.future;
    onData(jsonEncode({
      'type': 'meta', 'recordId': 'rec_1', 'summary': '定稿回答内容',
      'tags': ['日常'], 'domain': 'life', 'content': '定稿回答内容',
    }));
    onData('[DONE]');
  }
}

/// 一轮流式问答：delta 草稿（渲染后挂起在 [gate]）→ 放行 → meta 定稿。
class _Round {
  _Round(this.draftText, this.finalText) : gate = Completer<void>();

  final String draftText;
  final String finalText;
  final Completer<void> gate;
}

/// 按调用次序回放多轮流式的 SSE 替身：每轮回 draftText 后挂起等 gate，放行回 meta。
class _SequentialHoldSse extends SseClient {
  _SequentialHoldSse(this.rounds) : super();

  final List<_Round> rounds;
  int _cursor = 0;

  @override
  Future<void> post(
    Uri url, {
    Map<String, String>? headers,
    Object? body,
    required void Function(String data) onData,
  }) async {
    if (_cursor >= rounds.length) {
      throw SseHttpException(503, 'test: no more scripted rounds');
    }
    final round = rounds[_cursor++];
    onData(jsonEncode({'type': 'text', 'content': round.draftText}));
    await round.gate.future;
    onData(jsonEncode({
      'type': 'meta', 'recordId': 'rec_$_cursor', 'summary': round.finalText,
      'tags': ['日常'], 'domain': 'life', 'content': round.finalText,
    }));
    onData('[DONE]');
  }
}
