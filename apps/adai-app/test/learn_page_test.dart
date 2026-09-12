import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/pages/learn_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/models/learn_models.dart';

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

Map<String, dynamic> _card(String type, String title,
        {String created = '2026-09-06', List<String> keyPoints = const []}) =>
    {
      'type': type,
      'title': title,
      'platform': 'bilibili',
      'author': '某UP',
      'created': created,
      'tags': const ['rag'],
      'coreView': '$title 的核心观点',
      'keyPoints': keyPoints,
      'questions': const ['存疑点一'],
    };

ApiService _api(Map<String, dynamic> tree) => ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        final p = req.url.path;
        if (p.endsWith('/api/v1/learn/tree')) return _json(tree);
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );

void main() {
  Future<void> pump(WidgetTester tester, ApiService api) async {
    await tester.pumpWidget(MaterialApp(home: LearnPage(api: api)));
    await tester.pumpAndSettle();
  }

  group('LearnCardDto JSON parsing', () {
    test('parses full card', () {
      final card = LearnCardDto.fromJson(jsonDecode('''
        {"type":"trading","title":"回调一半的判定","created":"2026-09-06",
         "status":"review","tradeRelated":true,"tradeNote":"与 R66 互补","tags":["止损"],
         "coreView":"回调一半是买点","keyPoints":["02:31 回调一半"],
         "questions":["口径一致？"],"retell":"自己写了一遍：回调一半=几何口径"}
      ''') as Map<String, dynamic>);
      expect(card.type, 'trading');
      expect(card.tradeRelated, isTrue);
      expect(card.keyPoints, ['02:31 回调一半']);
      expect(card.status, 'review');
      expect(card.retell, contains('几何口径'));
    });

    test('recentAll merged sorted desc', () {
      final tree = LearnTreeResponse.fromJson(jsonDecode('''
        {"ai":[{"type":"ai","title":"A","created":"2026-09-01"}],
         "trading":[{"type":"trading","title":"B","created":"2026-09-06"}]}
      ''') as Map<String, dynamic>);
      final all = tree.recentAll;
      expect(all.length, 2);
      expect(all.first.title, 'B', reason: 'created 倒序 → 最新在前');
      expect(tree.isEmpty, isFalse);
    });
  });

  group('LearnDigestJob 抓取批解析（stage/source/cost + 防御式 null）', () {
    test('解析 needs_confirmation 全字段（舞台/来源/费用）', () {
      final job = LearnDigestJob.fromJson(jsonDecode('''
        {"status":"needs_confirmation","type":"ai","title":"","message":"这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）",
         "stage":"transcribing",
         "source":{"platform":"bilibili","title":"某视频","author":"某UP","durationSeconds":2244},
         "cost":{"durationSeconds":2244,"durationKnown":true,"estimatedYuan":0.1795,
                 "monthUsedSeconds":0,"quotaSeconds":36000,"remainSeconds":36000}}
      ''') as Map<String, dynamic>);
      expect(job.isAwaitingConfirm, isTrue);
      expect(job.isInProgress, isFalse);
      expect(job.stageText, '正在转写，可能要几分钟');
      expect(job.source!.platform, 'bilibili');
      expect(job.source!.summaryLine, '《某视频》 · 某UP · 37 分钟');
      expect(job.cost!.durationLabel, '37 分钟');
      expect(job.cost!.estimatedYuanLabel, '0.18');
      expect(job.confirmText, contains('需要转写'));
    });

    test('null 防御：stage/source/cost 缺省不炸，兜底人话自己拼', () {
      final job = LearnDigestJob.fromJson(jsonDecode('''
        {"status":"needs_confirmation","stage":null,"source":null,
         "cost":{"durationSeconds":null,"durationKnown":false,"estimatedYuan":null,
                 "monthUsedSeconds":null,"quotaSeconds":null,"remainSeconds":null}}
      ''') as Map<String, dynamic>);
      expect(job.stage, '');
      expect(job.stageText, '');
      expect(job.source, isNull);
      expect(job.cost!.durationLabel, '时长未知');
      expect(job.cost!.estimatedYuanLabel, '');
      expect(job.confirmText, contains('没有字幕'), reason: '后端没给 message → 自己拼费用提示');
      expect(job.confirmText, contains('时长未知'));
    });

    test('连 cost 都没有时的确认兜底 + 取消人话', () {
      final job = LearnDigestJob.fromJson(jsonDecode('{"status":"needs_confirmation"}')
          as Map<String, dynamic>);
      expect(job.cost, isNull);
      expect(job.confirmText, contains('会花一点钱'));
      final cancelled =
          LearnDigestJob.fromJson(jsonDecode('{"status":"cancelled"}') as Map<String, dynamic>);
      expect(cancelled.isCancelled, isTrue);
      expect(cancelled.isAwaitingConfirm, isFalse);
      expect(cancelled.isDone, isFalse);
      expect(cancelled.cancelledText, contains('这钱没花'));
    });

    test('三种舞台人话 + 未知舞台给空串', () {
      String stageOf(String s) =>
          LearnDigestJob.fromJson({'status': 'running', 'stage': s}).stageText;
      expect(stageOf('fetching'), '正在抓取原文');
      expect(stageOf('transcribing'), '正在转写，可能要几分钟');
      expect(stageOf('structuring'), '正在整理成卡片');
      expect(stageOf('whatever'), '');
    });

    test('LearnQuotaDto 解析 + 剩余额度人话', () {
      final q = LearnQuotaDto.fromJson(jsonDecode('''
        {"month":"2026-09","usedSeconds":1200,"usedYuan":0.096,"quotaSeconds":36000,
         "remainSeconds":34800,"yuanPerHour":0.288,"asrAvailable":true,"unavailableReason":null}
      ''') as Map<String, dynamic>);
      expect(q.month, '2026-09');
      expect(q.usedSeconds, 1200);
      expect(q.remainSeconds, 34800);
      expect(q.yuanPerHour, closeTo(0.288, 0.0001));
      expect(q.asrAvailable, isTrue);
      expect(q.unavailableReason, '');
      expect(q.remainLabel, '还剩 9 小时 40 分');
    });
  });

  group('LearnPage', () {
    testWidgets('最近学习列表渲染 + 最新在前', (tester) async {
      await pump(tester, _api({
        'ai': [_card('ai', 'RAG 笔记', created: '2026-09-01')],
        'trading': [_card('trading', '回调一半', created: '2026-09-06', keyPoints: ['回调=(high+low)/2'])],
      }));

      expect(find.text('最近学习'), findsOneWidget);
      expect(find.text('回调一半'), findsOneWidget);
      expect(find.text('RAG 笔记'), findsOneWidget);
      // 列表顺序：交易(09-06) 在上
      final bY = tester.getTopLeft(find.text('回调一半')).dy;
      final aY = tester.getTopLeft(find.text('RAG 笔记')).dy;
      expect(bY < aY, isTrue, reason: 'created 倒序 → 09-06 在 09-01 之上');
    });

    testWidgets('空态提示', (tester) async {
      await pump(tester, _api(const {}));
      expect(find.textContaining('还没有学习卡片'), findsOneWidget);
    });

    testWidgets('加载失败降级 + 重试恢复', (tester) async {
      var calls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          calls++;
          if (calls == 1) return _json({'error': 'boom'}, status: 500);
          return _json({
            'ai': [_card('ai', '恢复的卡')]
          });
        }),
      );
      await pump(tester, api);
      expect(find.text('加载失败，请重试'), findsOneWidget);

      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('恢复的卡'), findsOneWidget);
    });

    testWidgets('点卡片打开单篇全文（核心观点/要点/疑问）', (tester) async {
      await pump(tester, _api({
        'ai': [_card('ai', 'RAG 与 Agent', keyPoints: ['RAG 是检索增强', 'Agent 自主行动'])],
      }));

      await tester.tap(find.text('RAG 与 Agent'));
      await tester.pumpAndSettle();

      expect(find.text('核心观点'), findsOneWidget);
      expect(find.text('RAG 与 Agent 的核心观点'), findsOneWidget);
      expect(find.text('RAG 是检索增强'), findsOneWidget);
      expect(find.text('Agent 自主行动'), findsOneWidget);
      expect(find.text('存疑点一'), findsOneWidget);
    });

    testWidgets('S-learn2：复习提醒铃铛可读开关并切换', (tester) async {
      var putCalled = false;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({'ai': [_card('ai', 'RAG 笔记')]});
          }
          if (p.endsWith('/api/v1/learn/push-settings') && req.method == 'GET') {
            return _json({'learn-review': true});
          }
          if (p.endsWith('/api/v1/learn/push-settings/learn-review') && req.method == 'PUT') {
            putCalled = true;
            return _json({'learn-review': false});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await tester.tap(find.byIcon(Icons.notifications_outlined));
      await tester.pumpAndSettle();
      expect(find.text('复习提醒'), findsOneWidget);
      expect(find.text('开启中'), findsOneWidget);

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(putCalled, isTrue, reason: '切换应调用 PUT /learn/push-settings/learn-review');
    });

    testWidgets('喂入：页头＋提交素材 → 轮询 done → 回列表打开新卡', (tester) async {
      var treeCalls = 0;
      var pollCount = 0;
      var posted = false;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            if (treeCalls == 1) return _json(const {});
            return _json({
              'ai': [_card('ai', '阿呆消化了新卡', created: '2026-09-10')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            posted = true;
            final body = jsonDecode(req.body) as Map<String, dynamic>;
            expect(body['content'], contains('字幕'));
            return _json({'status': 'running'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            pollCount++;
            if (pollCount >= 2) {
              return _json({'status': 'done', 'type': 'ai', 'title': '阿呆消化了新卡'});
            }
            return _json({'status': 'running'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      expect(find.textContaining('还没有学习卡片'), findsOneWidget);

      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();
      expect(find.text('整理新内容'), findsOneWidget);

      await tester.enterText(
          find.byKey(const ValueKey('learn-digest-content')), '一段视频字幕素材，讲 RAG…');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();

      expect(find.text('消化中'), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(posted, isTrue, reason: '应提交 POST /learn/digest');
      expect(pollCount, greaterThanOrEqualTo(2), reason: '应轮询 /learn/digest/status');
      // 回到列表页 → 打开新卡全文详情
      expect(find.text('阿呆消化了新卡 的核心观点'), findsOneWidget);
    });

    testWidgets('喂入失败：后端 400 人话透出且可重试', (tester) async {
      var postCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            postCalls++;
            return _json({'error': '素材已留存（learn/_raw/），可稍后重试'},
                status: 400);
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-content')), '素材内容');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('素材已留存'), findsOneWidget);
      expect(find.text('让阿呆消化'), findsOneWidget, reason: '失败后仍可重试');
      expect(postCalls, 1);
      expect(find.textContaining('后端'), findsNothing, reason: 'B1 无第三视角：不出现系统口径');
    });

    // ── 2026-09-12 抓取批：链接喂入 + 转写费用确认 ──

    testWidgets('① 链接喂入：只丢链接无素材也能提交 → 阶段人话 → 轮询 done → 回列表打开新卡',
        (tester) async {
      var treeCalls = 0;
      var pollCount = 0;
      Map<String, dynamic>? postedBody;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            if (treeCalls == 1) return _json(const {});
            return _json({
              'ai': [_card('ai', 'B站视频整理', created: '2026-09-12')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            postedBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            pollCount++;
            if (pollCount == 1) {
              return _json({
                'status': 'running',
                'stage': 'fetching',
                'source': {
                  'platform': 'bilibili',
                  'title': '某视频',
                  'author': '某UP',
                  'durationSeconds': 2244
                },
              });
            }
            if (pollCount == 2) {
              return _json({'status': 'running', 'stage': 'structuring'});
            }
            return _json({'status': 'done', 'type': 'ai', 'title': 'B站视频整理'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();
      expect(find.textContaining('粘贴 B站 / 文章链接'), findsOneWidget, reason: '链接框是首选入口');

      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      expect(find.text('消化中'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在抓取原文'), findsOneWidget, reason: 'stage=fetching 的人话');
      expect(find.textContaining('某视频'), findsOneWidget, reason: '抓到的来源要亮相');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在整理成卡片'), findsOneWidget, reason: 'stage=structuring 的人话');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(postedBody!['url'], 'https://www.bilibili.com/video/BV1xx411c7mD');
      expect(postedBody!.containsKey('content'), isFalse, reason: '只有链接 → 不塞空素材字段');
      expect(find.text('B站视频整理 的核心观点'), findsOneWidget, reason: 'done 回列表并打开新卡');
    });

    testWidgets('② 没字幕的视频：轮询到 needs_confirmation → 亮费用提示、停轮询、不自动继续',
        (tester) async {
      var confirmCalls = 0;
      var pollCount = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            return _json({'status': 'running'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            pollCount++;
            if (pollCount == 1) {
              return _json({
                'status': 'running',
                'stage': 'transcribing',
                'source': {'platform': 'bilibili', 'title': '某视频', 'author': '某UP'},
              });
            }
            return _json({
              'status': 'needs_confirmation',
              'stage': 'transcribing',
              'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）',
              'source': {
                'platform': 'bilibili',
                'title': '某视频',
                'author': '某UP',
                'durationSeconds': 2244
              },
              'cost': {
                'durationSeconds': 2244,
                'durationKnown': true,
                'estimatedYuan': 0.1795,
                'monthUsedSeconds': 0,
                'quotaSeconds': 36000,
                'remainSeconds': 36000
              },
            });
          }
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmCalls++;
            return _json({'status': 'running'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在转写，可能要几分钟'), findsOneWidget, reason: 'stage=transcribing 的人话');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('转写确认'), findsOneWidget);
      expect(find.textContaining('这个视频没有字幕，需要转写：37 分钟'), findsOneWidget);
      expect(find.textContaining('预计约 0.18 元'), findsOneWidget);
      expect(find.text('继续转写'), findsOneWidget);
      expect(find.text('先不转写'), findsOneWidget);
      expect(confirmCalls, 0, reason: '用户没点头前不能替他花钱');

      final pollsAtConfirm = pollCount;
      await tester.pump(const Duration(seconds: 6));
      await tester.pump();
      expect(pollCount, pollsAtConfirm, reason: 'needs_confirmation 后应停止轮询');
      expect(find.textContaining('后台'), findsNothing, reason: '不是超时兜底，而是等用户点头');
    });

    testWidgets('③ 继续转写 → confirm(true) → 恢复轮询 → done 打开新卡', (tester) async {
      var treeCalls = 0;
      var pollCount = 0;
      Map<String, dynamic>? confirmBody;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            if (treeCalls == 1) return _json(const {});
            return _json({
              'ai': [_card('ai', '转写后整理好的卡', created: '2026-09-12')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            return _json({'status': 'running'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            pollCount++;
            if (pollCount == 1) {
              return _json({
                'status': 'needs_confirmation',
                'stage': 'transcribing',
                'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）',
              });
            }
            return _json({'status': 'done', 'type': 'ai', 'title': '转写后整理好的卡'});
          }
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({'status': 'running', 'stage': 'transcribing'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pumpAndSettle();
      expect(find.text('继续转写'), findsOneWidget);

      await tester.tap(find.text('继续转写'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(confirmBody, isNotNull, reason: '应送达 /learn/digest/confirm');
      expect(confirmBody!['confirm'], isTrue, reason: '继续转写 = confirm(true)');
      expect(find.text('转写确认'), findsNothing, reason: '点头后离开确认态');
      expect(find.text('正在转写，可能要几分钟'), findsOneWidget, reason: '恢复轮询并显示阶段');

      await tester.pump(const Duration(milliseconds: 2500));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(pollCount, greaterThanOrEqualTo(2), reason: 'confirm 后应恢复轮询');
      expect(find.text('转写后整理好的卡 的核心观点'), findsOneWidget, reason: 'done → 回列表打开新卡');
    });

    testWidgets('④ 先不转写 → confirm(false) → 到此为止，不进入 done', (tester) async {
      var pollCount = 0;
      Map<String, dynamic>? confirmBody;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            return _json({'status': 'running'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            pollCount++;
            return _json({
              'status': 'needs_confirmation',
              'stage': 'transcribing',
              'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）',
            });
          }
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({
              'status': 'cancelled',
              'message': '已取消转写（没花钱），抓到的元数据我留着了，回头想整理再说一声',
            });
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pumpAndSettle();

      await tester.tap(find.text('先不转写'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(confirmBody!['confirm'], isFalse, reason: '先不转写 = confirm(false)');
      expect(find.textContaining('已取消转写（没花钱）'), findsOneWidget, reason: '展示结果人话');
      expect(find.text('继续转写'), findsNothing);
      final pollsAfterCancel = pollCount;
      await tester.pump(const Duration(seconds: 6));
      await tester.pump();
      expect(pollCount, pollsAfterCancel, reason: '取消后不再轮询');
      expect(find.text('核心观点'), findsNothing, reason: '没进入 done，不该打开新卡');
      expect(find.text('返回最近学习'), findsOneWidget, reason: '给用户一个收尾出口');
    });

    testWidgets('⑤ 链接与素材都空：当场给提示，不往外发', (tester) async {
      var postCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            postCalls++;
            return _json({'status': 'running'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            return _json({'status': 'done', 'type': 'ai', 'title': '链接来的卡'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('给我一个链接，或者把素材内容粘进来'), findsOneWidget);
      expect(postCalls, 0, reason: '两个都空 → 先提示，不发出去');

      // 只填链接即可提交（素材框可留空）
      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')), 'https://example.com/post');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      expect(postCalls, 1);
      expect(find.text('消化中'), findsOneWidget);

      // 让这轮轮询走到头（done 收尾），不留悬挂定时器
      await tester.pump(const Duration(milliseconds: 2500));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.text('整理新内容'), findsNothing, reason: 'done 后收起喂入页');
      expect(find.text('最近学习'), findsOneWidget);
    });
  });
}
