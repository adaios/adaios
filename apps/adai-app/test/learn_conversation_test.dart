import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/main_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/sse_client.dart';

// ────────────────────────────────────────────────────────────────
// 对话流 learn 入口测试（2026-09-12 完整升级批）
//
// 锁定三件事：
// ① 「整理 + 链接」→ 不走普通记录，改让阿呆整理成学习卡（提交式 + 轮询 status）；
// ② 「打开那篇 / 打开《X》」→ 找卡命中就把全文摊成阿呆气泡；
// ③ 不匹配 / 没接住 → 原路径一字不变（用户的问题绝不被吞掉）。
// ────────────────────────────────────────────────────────────────

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

class _Backend {
  List<String> plugins = ['learn'];
  List<Map<String, dynamic>> feedPage0 = [];
  int feedTotalToday = 0;

  /// /learn/digest/status 按序响应（用完一直用最后一个）
  List<Map<String, dynamic>> statusSeq = [
    {'status': 'idle'}
  ];
  int statusCalls = 0;

  /// /learn/tree 响应
  Map<String, dynamic> tree = const {};

  /// /learn/find 命中
  List<Map<String, dynamic>> findHits = [];

  /// 'type/title' → /learn/content 的 md 原文
  final Map<String, String> contents = {};

  Map<String, dynamic> confirmResponse = const {'status': 'running', 'stage': 'transcribing'};

  /// 非空 → confirm 请求挂在这里（连点守卫用例：控制回包时机）
  Completer<void>? confirmGate;

  int recordCalls = 0;
  final List<http.Request> requests = [];

  List<http.Request> requestsTo(String suffix) =>
      requests.where((r) => r.url.path.endsWith(suffix)).toList();

  Map<String, dynamic> card(String type, String title,
          {String created = '2026-09-12', String topic = 'harness'}) =>
      {
        'type': type, 'title': title, 'created': created, 'topic': topic,
        'writable': true, 'coreView': '$title 的核心观点',
      };

  void registerContent(String type, String title, String md, {String topic = 'harness'}) {
    contents['$type/$title'] = jsonEncode({
      'type': type, 'title': title, 'topic': topic, 'writable': true, 'content': md,
    });
  }

  Future<http.Response> handle(http.Request req) async {
    requests.add(req);
    final p = req.url.path;
    if (p.endsWith('/api/v1/brief/cached') || p.endsWith('/api/v1/brief')) {
      return _json({'content': ''});
    }
    if (p.endsWith('/api/v1/feed')) {
      return _json({'entries': feedPage0, 'totalToday': feedTotalToday});
    }
    if (p.endsWith('/api/v1/me/plugins')) return _json(plugins);
    if (p.endsWith('/api/v1/learn/digest/status')) {
      final i = statusCalls < statusSeq.length ? statusCalls : statusSeq.length - 1;
      statusCalls++;
      return _json(statusSeq[i]);
    }
    if (p.endsWith('/api/v1/learn/digest/confirm')) {
      final gate = confirmGate;
      if (gate != null) await gate.future;
      return _json(confirmResponse);
    }
    if (p.endsWith('/api/v1/learn/digest')) return _json(const {'status': 'running'});
    if (p.endsWith('/api/v1/learn/tree')) return _json(tree);
    if (p.endsWith('/api/v1/learn/find')) return _json(findHits);
    if (p.endsWith('/api/v1/learn/content')) {
      final key = '${req.url.queryParameters['type']}/${req.url.queryParameters['title']}';
      final body = contents[key];
      if (body == null) return _json({'error': '卡片不存在：$key'}, status: 400);
      return http.Response.bytes(utf8.encode(body), 200,
          headers: {'content-type': 'application/json'});
    }
    if (p.endsWith('/api/v1/records')) {
      recordCalls++;
      return _json({
        'intent': 'log', 'recordId': 'r1', 'summary': '已记下',
        'tags': <String>[], 'domain': 'life',
      });
    }
    return _json({'error': 'not mocked'}, status: 404);
  }
}

/// SSE 永远不通（逼 askStream 降级同步端点；这批用例不涉及流式）。
class _FailSse extends SseClient {
  @override
  Future<void> post(Uri url,
      {Map<String, String>? headers, Object? body, required void Function(String) onData}) async {
    throw SseHttpException(503, 'test: sse disabled');
  }
}

void main() {
  Future<_Backend> pump(WidgetTester tester, _Backend backend) async {
    final api = ApiService(
      baseUrl: 'http://test',
      userId: 'adai',
      client: MockClient(backend.handle),
      sseClient: _FailSse(),
    );
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: MainPage(api: api))));
    await tester.pumpAndSettle();
    return backend;
  }

  Future<void> send(WidgetTester tester, String text) async {
    await tester.enterText(find.byType(TextField), text);
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();
  }

  group('对话流：整理 + 链接 → 学习卡片', () {
    testWidgets('① 含链接 + 「整理」→ 不走普通记录，改让阿呆整理；done 后气泡给结果 + 可点去学习页',
        (tester) async {
      final backend = _Backend()
        ..statusSeq = [
          {'status': 'running', 'stage': 'fetching'},
          {'status': 'done', 'type': 'ai', 'title': 'B站视频整理'},
        ]
        ..registerContent('ai', 'B站视频整理',
            '# B站视频整理\n\n## 核心观点\n检索增强能压幻觉\n\n## 关键内容详解\n这段只在全文里有');
      await pump(tester, backend);

      await send(tester, '帮我整理一下这个 https://www.bilibili.com/video/BV1xx411c7mD');

      // 用户气泡 + 阿呆气泡（先应一声）
      expect(find.byKey(const ValueKey('learn-digest-user')), findsOneWidget);
      expect(find.textContaining('我去把这个链接整理成学习卡片'), findsOneWidget);

      // 轮询过程中气泡里滚阶段人话
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在抓取原文'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      final posts = backend.requestsTo('/api/v1/learn/digest')
          .where((r) => r.method == 'POST').toList();
      expect(posts.length, 1, reason: '应提交 POST /learn/digest');
      expect(jsonDecode(posts.first.body)['url'],
          'https://www.bilibili.com/video/BV1xx411c7mD');
      expect(backend.recordCalls, 0, reason: '整理链接不走普通记录流程');
      expect(find.textContaining('整理好了：《B站视频整理》（harness）'), findsOneWidget);
      expect(find.byKey(const ValueKey('learn-digest-open')), findsOneWidget);
    });

    testWidgets('② 点「去看看这张卡」→ 打开学习页该卡全文', (tester) async {
      final backend = _Backend()
        ..statusSeq = [
          {'status': 'running'},
          {'status': 'done', 'type': 'ai', 'title': 'B站视频整理'},
        ]
        ..tree = {
          'ai': [
            {
              'type': 'ai', 'title': 'B站视频整理', 'created': '2026-09-12',
              'topic': 'harness', 'writable': true, 'coreView': '检索增强能压幻觉',
            }
          ]
        }
        ..registerContent('ai', 'B站视频整理',
            '# B站视频整理\n\n## 核心观点\n检索增强能压幻觉\n\n## 关键内容详解\n这段只在全文里有');
      await pump(tester, backend);

      await send(tester, '帮我整理 https://example.com/post');
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('learn-digest-open')), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('learn-digest-open')));
      await tester.pumpAndSettle();

      // 学习页 + 该卡全文页都在导航栈上：直接断言看得见的那一页
      // （done 收尾取过一次全文拿 topic，详情页再取一次渲染全文）
      final contents = backend.requestsTo('/api/v1/learn/content');
      expect(contents.length, 2, reason: '气泡取 topic 一次 + 详情页渲染一次');
      expect(contents.last.url.queryParameters['title'], 'B站视频整理');
      expect(find.text('这段只在全文里有'), findsOneWidget, reason: '该卡全文已打开');
    });

    testWidgets('③ 转写要花钱：气泡内报价 + 继续转写 / 先不转写（连点只送一次）', (tester) async {
      final backend = _Backend()
        ..statusSeq = [
          {'status': 'running', 'stage': 'reading'},
          {
            'status': 'needs_confirmation', 'stage': 'transcribing',
            'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）',
          },
          {'status': 'done', 'type': 'ai', 'title': '转写后整理好的卡'},
        ]
        ..confirmGate = Completer<void>()
        ..registerContent('ai', '转写后整理好的卡', '# 转写后整理好的卡\n\n## 核心观点\n转写之后才整理');
      await pump(tester, backend);

      await send(tester, '把这个链接消化成卡片 https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在读图'), findsOneWidget, reason: 'stage=reading 的人话（图片路径同款文案）');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.textContaining('需要转写：37 分钟'), findsOneWidget, reason: '报价照后端 message');
      expect(find.textContaining('预计约 0.18 元'), findsOneWidget);
      expect(find.text('继续转写'), findsOneWidget);
      expect(find.text('先不转写'), findsOneWidget);
      expect(backend.requestsTo('/api/v1/learn/digest/confirm'), isEmpty,
          reason: '用户没点头前不替他花钱');

      // 连点守卫：确认还在途中（Gate 未放行）时重复点击，必须被吃掉
      await tester.tap(find.text('继续转写'));
      await tester.tap(find.text('继续转写'));
      await tester.pump();
      final confirms = backend.requestsTo('/api/v1/learn/digest/confirm');
      expect(confirms.length, 1, reason: '连点只送一次（重复确认 = 重复花钱）');
      expect(jsonDecode(confirms.first.body)['confirm'], isTrue);

      backend.confirmGate!.complete();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('继续转写'), findsNothing, reason: '点头后离开确认态');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.textContaining('整理好了：《转写后整理好的卡》（harness）'), findsOneWidget);
    });

    testWidgets('④ 先不转写 → confirm(false) + 人话，不产生卡片', (tester) async {
      final backend = _Backend()
        ..statusSeq = [
          {
            'status': 'needs_confirmation',
            'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元',
          },
        ]
        ..confirmResponse = {
          'status': 'cancelled',
          'message': '已取消转写（没花钱），抓到的元数据我留着了，回头想整理再说一声',
        };
      await pump(tester, backend);

      await send(tester, '整理这个链接 https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pumpAndSettle();

      await tester.tap(find.text('先不转写'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(jsonDecode(backend.requestsTo('/api/v1/learn/digest/confirm').first.body)['confirm'],
          isFalse);
      expect(find.textContaining('已取消转写（没花钱）'), findsOneWidget);
      expect(find.byKey(const ValueKey('learn-digest-open')), findsNothing);
    });

    testWidgets('⑤ learn 插件没开：链接照旧走普通记录（原路径不变）', (tester) async {
      final backend = _Backend()..plugins = [];
      await pump(tester, backend);

      await send(tester, '整理一下这个 https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.pumpAndSettle();

      expect(backend.requestsTo('/api/v1/learn/digest'), isEmpty,
          reason: '插件没开就不接管');
      expect(backend.recordCalls, 1, reason: '照旧走记录流程');
    });
  });

  group('对话流：打开那篇', () {
    testWidgets('⑥ 打开《X》命中 → 阿呆气泡里摊开全文（含产品没建模的段）', (tester) async {
      final backend = _Backend()
        ..findHits = [
          {'type': 'ai', 'title': 'RAG 笔记', 'created': '2026-09-12', 'topic': 'harness',
           'writable': true}
        ]
        ..registerContent('ai', 'RAG 笔记',
            '# RAG 笔记\n\n## 核心观点\n检索增强能压幻觉\n\n## 关键内容详解\n这段只在全文里有');
      await pump(tester, backend);

      await send(tester, '打开《RAG 笔记》看看');
      await tester.pumpAndSettle();

      final finds = backend.requestsTo('/api/v1/learn/find');
      expect(finds.length, 1, reason: '找卡走 /learn/find');
      expect(finds.first.url.queryParameters['q'], 'RAG 笔记', reason: '书名号里的词当关键词');
      expect(find.textContaining('找到了：《RAG 笔记》'), findsOneWidget);
      expect(find.text('这段只在全文里有'), findsOneWidget, reason: '全文进气泡');
      expect(backend.recordCalls, 0, reason: '命中就不该再走普通问答');
    });

    testWidgets('⑦ 看看上次整理的那篇 → 命中最近一篇', (tester) async {
      final backend = _Backend()
        ..tree = {
          'ai': [
            {'type': 'ai', 'title': '最近那篇', 'created': '2026-09-12', 'topic': 'harness',
             'writable': true}
          ]
        }
        ..registerContent('ai', '最近那篇', '# 最近那篇\n\n## 核心观点\n最近整理的');
      await pump(tester, backend);

      await send(tester, '看看上次整理的那篇');
      await tester.pumpAndSettle();

      expect(find.textContaining('找到了：《最近那篇》'), findsOneWidget);
      expect(find.text('最近整理的'), findsOneWidget);
    });

    testWidgets('⑧ 没命中 → 人话兜底 + 照常走普通问答（不吞用户问题）', (tester) async {
      final backend = _Backend(); // find 空 + tree 空
      await pump(tester, backend);

      await send(tester, '打开那篇');
      await tester.pumpAndSettle();

      expect(find.textContaining('我没找到你说的那篇'), findsOneWidget, reason: '人话兜底');
      expect(backend.recordCalls, 1, reason: '未命中照样走普通流程，不吞问题');
      expect(find.textContaining('接口'), findsNothing, reason: 'B1：不出现系统视角标签');
    });

    testWidgets('⑨ 普通消息：learn 相关请求一次都不发（原路径零开销）', (tester) async {
      final backend = _Backend();
      await pump(tester, backend);

      await send(tester, '今天跑步 5 公里');
      await tester.pumpAndSettle();

      expect(backend.recordCalls, 1);
      expect(backend.requestsTo('/api/v1/me/plugins'), isEmpty, reason: '不匹配就不查插件');
      expect(backend.requestsTo('/api/v1/learn/find'), isEmpty);
      expect(backend.requestsTo('/api/v1/learn/tree'), isEmpty);
      expect(backend.requestsTo('/api/v1/learn/digest'), isEmpty);
    });

    testWidgets('⑩ 「整理」+ 链接但 learn 插件查询失败：静默走原路径', (tester) async {
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/me/plugins')) return _json({'error': 'boom'}, status: 500);
          if (p.endsWith('/api/v1/records')) return _json({'intent': 'log', 'summary': '已记下'});
          if (p.endsWith('/api/v1/brief/cached')) return _json({'content': ''});
          if (p.endsWith('/api/v1/feed')) return _json({'entries': [], 'totalToday': 0});
          return _json({'error': 'not mocked'}, status: 404);
        }),
        sseClient: _FailSse(),
      );
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: MainPage(api: api))));
      await tester.pumpAndSettle();

      await send(tester, '整理 https://example.com/post');
      await tester.pumpAndSettle();

      expect(find.text('已记下'), findsOneWidget, reason: '插件查不到 → 照旧记录，不打扰用户');
    });
  });
}
