import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/desktop_shell.dart';
import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/services/api_service.dart';

/// UTF-8 JSON 响应（MockClient 默认 Latin-1，中文会炸 → 显式 charset=utf-8）。
http.Response _json(Object body, {int status = 200}) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

/// Feed 页首屏所需的最小 mock（Feed/简报/标签/任务快照）。
MockClient _feedMock(Future<http.Response> Function(http.Request req) learn) {
  return MockClient((req) async {
    final p = req.url.path;
    if (p == '/api/v1/brief') return _json({'content': '今日概览'});
    if (p == '/api/v1/brief/cached') return _json({'content': '今日概览'});
    if (p == '/api/v1/feed') return _json({'entries': <Object>[], 'totalToday': 0});
    if (p == '/api/v1/tags') return _json({'tags': [], 'total': 0, 'updatedAt': ''});
    if (p == '/api/v1/project/tasks/stats') {
      return _json({'total': 0, 'todo': 0, 'doing': 0, 'done': 0, 'cancelled': 0});
    }
    return learn(req);
  });
}

/// 学习卡片全文样例（含 frontmatter / H1 / 产品没建模的段）。
const _cardMd = '''---
title: Harness engineering
type: ai
---

# Harness engineering

## 一、核心观点
人类掌舵，agent 执行。

## 二、关键内容详解
- 环境必须对 agent 可读
''';

void main() {
  Future<void> pump(WidgetTester tester, ApiService api,
      {void Function(String type, String title)? onOpenLearnCard}) async {
    await tester.binding.setSurfaceSize(const Size(1200, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: FeedPage(api: api, learnEnabled: true, onOpenLearnCard: onOpenLearnCard),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> send(WidgetTester tester, String text) async {
    await tester.enterText(find.byType(TextField).first, text);
    await tester.pump();
    await tester.tap(find.byIcon(Icons.arrow_upward));
    await tester.pump();
  }

  group('对话流 learn 入口：整理链接（2026-09-12）', () {
    testWidgets('「整理 + 链接」走 learn：不吃 /records，轮询 done 给标题+主题与跳转入口', (tester) async {
      var digestCalls = 0;
      var recordCalls = 0;
      var statusCalls = 0;
      Map<String, dynamic>? digestBody;
      (String, String)? opened;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: _feedMock((req) async {
          final p = req.url.path;
          if (p == '/api/v1/records') {
            recordCalls++;
            return _json({'intent': 'log', 'summary': '已记录'});
          }
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            digestCalls++;
            digestBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            statusCalls++;
            if (statusCalls >= 2) {
              return _json({
                'status': 'done',
                'type': 'ai',
                'title': 'Harness engineering',
                'topic': 'harness',
              });
            }
            return _json({'status': 'running', 'stage': 'fetching'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api, onOpenLearnCard: (t, ti) => opened = (t, ti));

      await send(tester, '帮我整理一下这个 https://www.bilibili.com/video/BV1xx411c7mD');

      // 用户气泡 + 阿呆回执先出现
      expect(find.textContaining('帮我整理一下这个'), findsWidgets);
      expect(find.textContaining('整理成学习卡片'), findsOneWidget);
      expect(digestCalls, 1);
      expect(digestBody!['url'], 'https://www.bilibili.com/video/BV1xx411c7mD');
      expect(recordCalls, 0, reason: '链接整理是动作，不进普通记录流');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.textContaining('正在抓取原文'), findsOneWidget, reason: 'stage 说人话');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.text('整理好了：《Harness engineering》（harness）'), findsOneWidget);

      // 跳转入口：点了把 (type,title) 交给壳层
      await tester.tap(find.text('去学习页看这张卡'));
      await tester.pumpAndSettle();
      expect(opened, ('ai', 'Harness engineering'));
      expect(recordCalls, 0);
    });

    testWidgets('没字幕要转写：气泡里摆报价 + 继续/先不转写，点头前不花钱', (tester) async {
      var confirmCalls = 0;
      var statusCalls = 0;
      Map<String, dynamic>? confirmBody;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: _feedMock((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            statusCalls++;
            return _json({
              'status': 'needs_confirmation',
              'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元',
            });
          }
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmCalls++;
            confirmBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({'status': 'cancelled', 'message': '已取消转写（没花钱）'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await send(tester, '这个链接帮我归档一下 https://example.com/a');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.textContaining('这个视频没有字幕，需要转写'), findsOneWidget);
      expect(find.text('继续转写'), findsOneWidget);
      expect(find.text('先不转写'), findsOneWidget);
      expect(confirmCalls, 0, reason: '没点头之前一分钱都不能花');

      // 停下轮询：再等也不问进度
      final seen = statusCalls;
      await tester.pump(const Duration(seconds: 6));
      await tester.pump();
      expect(statusCalls, seen, reason: '等你拍板期间不轮询');

      await tester.tap(find.text('先不转写'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(confirmBody, {'confirm': false});
      expect(find.textContaining('已取消转写'), findsOneWidget);
    });

    testWidgets('learn 插件没启用：同样的话照旧走普通记录流（门控）', (tester) async {
      var digestCalls = 0;
      var recordCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: _feedMock((req) async {
          final p = req.url.path;
          if (p == '/api/v1/records') {
            recordCalls++;
            return _json({'intent': 'log', 'summary': '已记录'});
          }
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            digestCalls++;
            return _json({'status': 'pending'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await tester.binding.setSurfaceSize(const Size(1200, 900));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: FeedPage(api: api)), // learnEnabled 默认 false
      ));
      await tester.pumpAndSettle();

      await send(tester, '帮我整理一下这个 https://example.com/a');

      expect(digestCalls, 0, reason: '插件没启用 → learn 入口不接管');
      expect(recordCalls, 1);
    });
  });

  group('对话流 learn 入口：打开那篇（2026-09-12）', () {
    testWidgets('命中：find 找卡 → content 读全文 → 阿呆气泡贴全文 + 跳转入口', (tester) async {
      var findCalls = 0;
      var contentCalls = 0;
      String? findQ;
      (String, String)? opened;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: _feedMock((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/find')) {
            findCalls++;
            findQ = req.url.queryParameters['q'];
            return _json([
              {
                'type': 'ai',
                'title': 'Harness engineering',
                'topic': 'harness',
                'writable': false,
                'created': '2026-09-06',
                'coreView': '人类掌舵，agent 执行',
              }
            ]);
          }
          if (p.endsWith('/api/v1/learn/content')) {
            contentCalls++;
            expect(req.url.queryParameters['type'], 'ai');
            expect(req.url.queryParameters['title'], 'Harness engineering');
            return _json({
              'type': 'ai',
              'title': 'Harness engineering',
              'topic': 'harness',
              'writable': false,
              'content': _cardMd,
            });
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api, onOpenLearnCard: (t, ti) => opened = (t, ti));

      await send(tester, '打开《Harness engineering》');

      expect(findCalls, 1);
      expect(findQ, 'Harness engineering', reason: '去掉「打开」与书名号，剩下的当关键词');
      expect(contentCalls, 1, reason: '命中后读全文');
      expect(find.textContaining('找到了：《Harness engineering》（harness）'), findsOneWidget);
      // 全文进了气泡（md 标记轻量清理：不再有 # / ** 残留）
      expect(find.textContaining('环境必须对 agent 可读', findRichText: true), findsOneWidget);
      expect(find.textContaining('## 二、关键内容详解'), findsNothing);

      await tester.tap(find.text('去学习页看这张卡'));
      await tester.pumpAndSettle();
      expect(opened, ('ai', 'Harness engineering'));
    });

    testWidgets('未命中：人话兜底 + 照常走普通问答（不吞用户的问题）', (tester) async {
      var recordCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: _feedMock((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/find')) return _json(const []);
          if (p == '/api/v1/records') {
            recordCalls++;
            return _json({
              'intent': 'question',
              'rawResponse': '我按普通问答答你：这句话可以这样理解…',
              'summary': '普通回答',
            });
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await send(tester, '打开那篇');

      expect(find.textContaining('我没找到对应的那张卡'), findsOneWidget);
      expect(recordCalls, 1, reason: '没找到卡也要照常答用户的问题');
      expect(find.textContaining('我按普通问答答你'), findsOneWidget);
    });

    testWidgets('纯指代「打开那篇」：退回最近整理的那一篇（2026-09-12）', (tester) async {
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: _feedMock((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [
                {
                  'type': 'ai',
                  'title': '最近那篇',
                  'topic': '量价关系',
                  'writable': true,
                  'created': '2026-09-12',
                  'status': 'new',
                  'coreView': '最近的核心观点',
                },
              ],
            });
          }
          if (p.endsWith('/api/v1/learn/content')) {
            return _json({
              'type': 'ai',
              'title': '最近那篇',
              'topic': '量价关系',
              'writable': true,
              'content': '---\ntitle: 最近那篇\norigin: product\n---\n\n## 关键内容详解\n这是最近那篇的正文',
              'body': '## 关键内容详解\n这是最近那篇的正文',
            });
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await send(tester, '打开那篇');

      expect(find.textContaining('找到了：《最近那篇》'), findsOneWidget,
          reason: '纯指代没给标题 → 退回最近整理的那一篇（原先必然找不到）');
      expect(find.textContaining('这是最近那篇的正文'), findsOneWidget);
      expect(find.textContaining('origin: product'), findsNothing,
          reason: '展示用 body（不带内部字段），第一原则「无第三视角」');
    });

    testWidgets('不匹配的消息一字不变走原流程（不调 learn 端点）', (tester) async {      var recordCalls = 0;
      var learnCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: _feedMock((req) async {
          final p = req.url.path;
          if (p.startsWith('/api/v1/learn')) {
            learnCalls++;
            return _json({'error': 'not mocked'}, status: 404);
          }
          if (p == '/api/v1/records') {
            recordCalls++;
            return _json({'intent': 'log', 'summary': '已记录'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await send(tester, '今天下午三点开了个会，记一下');

      expect(recordCalls, 1);
      expect(learnCalls, 0, reason: '不匹配的消息绝不碰 learn');
      expect(find.textContaining('今天下午三点开了个会'), findsWidgets, reason: '原样落成记录卡');
    });
  });

  group('壳层跳转：对话流 → 学习页（2026-09-12）', () {
    testWidgets('点「去学习页看这张卡」：切到学习页并把该卡打开（读全文）', (tester) async {
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: _feedMock((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/me/plugins')) return _json(['learn']);
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [
                {
                  'type': 'ai',
                  'title': 'Harness engineering',
                  'topic': 'harness',
                  'writable': false,
                  'created': '2026-09-06',
                  'status': 'new',
                  'coreView': '人类掌舵，agent 执行',
                }
              ]
            });
          }
          if (p.endsWith('/api/v1/learn/find')) {
            return _json([
              {
                'type': 'ai',
                'title': 'Harness engineering',
                'topic': 'harness',
                'writable': false,
                'created': '2026-09-06',
                'coreView': '人类掌舵，agent 执行',
              }
            ]);
          }
          if (p.endsWith('/api/v1/learn/content')) {
            return _json({
              'type': 'ai',
              'title': 'Harness engineering',
              'topic': 'harness',
              'writable': false,
              'content': _cardMd,
            });
          }
          if (p.endsWith('/api/v1/learn/digest/status')) return _json({'status': 'idle'});
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await tester.binding.setSurfaceSize(const Size(1400, 900));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(home: DesktopShell(userId: 'adai', api: api)));
      await tester.pumpAndSettle();

      // 对话流里「打开那篇」→ 命中 → 点跳转入口
      await tester.enterText(find.byType(TextField).first, '打开《Harness engineering》');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward));
      await tester.pumpAndSettle();
      await tester.tap(find.text('去学习页看这张卡'));
      await tester.pumpAndSettle();

      // 学习页已打开该卡（详情 + 全文段都在），且搜索框（学习页顶部）就位
      expect(find.byKey(const ValueKey('learn-search')), findsOneWidget);
      expect(find.byKey(const ValueKey('learn-detail-ai-Harness engineering')), findsOneWidget);
      expect(find.text('关键内容详解'), findsOneWidget);
    });
  });
}
