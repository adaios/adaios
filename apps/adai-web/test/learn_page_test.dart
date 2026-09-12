import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_web/pages/learn_page.dart';
import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/services/models/learn_models.dart';

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

Map<String, dynamic> _card(String type, String title,
        {List<String> keyPoints = const [],
        String status = 'new',
        String topic = '未归类',
        bool writable = true,
        bool tradeRelated = false,
        String created = '2026-09-06'}) =>
    {
      'type': type,
      'title': title,
      'platform': 'bilibili',
      'author': '某UP',
      'created': created,
      'status': status,
      'topic': topic,
      'writable': writable,
      'tradeRelated': tradeRelated,
      'tags': const ['rag'],
      'coreView': '$title 的核心观点',
      'keyPoints': keyPoints,
    };

/// GET /learn/content 的响应样例（2026-09-12 完整升级批：md 原文 + topic/writable）。
Map<String, dynamic> _contentJson(String type, String title, String md,
        {String topic = '未归类', bool writable = true}) =>
    {
      'type': type,
      'title': title,
      'topic': topic,
      'writable': writable,
      'content': md,
    };

/// 选图替身：测试里不走真文件选择器（返回两张 png 字节，走 submitLearnImages）。
class _FakeImagePicker extends FilePicker {
  @override
  Future<FilePickerResult?> pickFiles({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    bool allowCompression = true,
    int compressionQuality = 30,
    bool allowMultiple = false,
    bool withData = false,
    bool withReadStream = false,
    bool lockParentWindow = false,
    bool readSequential = false,
  }) async =>
      FilePickerResult([
        PlatformFile(name: 'page1.png', size: 4, bytes: Uint8List.fromList([65, 66, 67, 68])),
        PlatformFile(name: 'page2.png', size: 4, bytes: Uint8List.fromList([69, 70, 71, 72])),
      ]);
}

/// 本月转写额度样例（GET /learn/digest/quota）。
Map<String, dynamic> _quotaJson({int remainSeconds = 34800, bool asrAvailable = true}) => {
      'month': '2026-09',
      'usedSeconds': 1200,
      'usedYuan': 0.096,
      'quotaSeconds': 36000,
      'remainSeconds': remainSeconds,
      'yuanPerHour': 0.288,
      'asrAvailable': asrAvailable,
      'unavailableReason': null,
    };

/// 「视频没字幕 → 先报价等你点头」的轮询响应样例（RFC 20260912 §3.8 条 5）。
Map<String, dynamic> _needsConfirmJson() => {
      'status': 'needs_confirmation',
      'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 9.7 小时）',
      'stage': 'transcribing',
      'source': {
        'platform': 'bilibili',
        'title': '某视频',
        'author': '某UP',
        'durationSeconds': 2244,
      },
      'cost': {
        'durationSeconds': 2244,
        'durationKnown': true,
        'estimatedYuan': 0.1795,
        'monthUsedSeconds': 0,
        'quotaSeconds': 36000,
        'remainSeconds': 36000,
      },
    };

ApiService _api({required Map<String, dynamic> tree}) {
  return ApiService(
    baseUrl: 'http://test',
    userId: 'adai',
    client: MockClient((req) async {
      final p = req.url.path;
      if (p.endsWith('/api/v1/learn/tree')) return _json(tree);
      if (p.endsWith('/api/v1/learn/cards')) return _json(const []);
      return _json({'error': 'not mocked'}, status: 404);
    }),
  );
}

void main() {
  Future<void> pump(WidgetTester tester, ApiService api) async {
    await tester.binding.setSurfaceSize(const Size(1200, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: LearnPage(api: api)),
    ));
    await tester.pumpAndSettle();
  }

  group('LearnCardDto JSON parsing', () {
    test('parses full card fields', () {
      final json = jsonDecode('''
        {"type":"trading","title":"回调一半的判定","platform":"bilibili",
         "author":"某UP","url":"https://b23.tv/x","published":"2026-05-05",
         "created":"2026-09-06","status":"new","tradeRelated":true,
         "tradeNote":"与 R66 互补","tags":["止损"],
         "coreView":"回调一半是买点","keyPoints":["02:31 回调一半"],
         "questions":["口径一致？"]}
      ''');
      final card = LearnCardDto.fromJson(json);
      expect(card.type, 'trading');
      expect(card.title, '回调一半的判定');
      expect(card.tradeRelated, isTrue);
      expect(card.tradeNote, '与 R66 互补');
      expect(card.tags, ['止损']);
      expect(card.keyPoints, ['02:31 回调一半']);
      expect(card.questions, ['口径一致？']);
    });

    test('defaults when fields missing', () {
      final json = jsonDecode('{"type":"ai","title":"RAG 笔记","created":"2026-09-06"}');
      final card = LearnCardDto.fromJson(json);
      expect(card.coreView, '');
      expect(card.keyPoints, isEmpty);
      expect(card.tradeRelated, isFalse);
      expect(card.status, 'new');
    });

    test('LearnDigestJob 缺字段一律兜底：stage/source/cost 可空，脏数据不炸', () {
      final bare = LearnDigestJob.fromJson(jsonDecode('{"status":"running"}'));
      expect(bare.stage, '');
      expect(bare.source, isNull);
      expect(bare.cost, isNull);
      expect(bare.isActive, isTrue, reason: 'running 还在忙 → 轮询继续');
      expect(bare.stageLabel, '');

      final nulled = LearnDigestJob.fromJson(jsonDecode(
          '{"status":"needs_confirmation","message":"要转写","stage":null,"source":null,"cost":null}'));
      expect(nulled.isAwaitingConfirm, isTrue);
      expect(nulled.source, isNull);
      expect(nulled.cost, isNull);

      final dirty = LearnDigestJob.fromJson(jsonDecode(
          '{"status":"done","stage":"structuring","source":"怪东西","cost":123}'));
      expect(dirty.source, isNull, reason: 'source 不是对象 → 当没有，不能抛');
      expect(dirty.cost, isNull, reason: 'cost 不是对象 → 当没有，不能抛');
      expect(dirty.stageLabel, '正在整理成卡片');

      expect(LearnDigestJob.fromJson(jsonDecode('{"status":"cancelled"}')).isCancelled, isTrue);
      expect(LearnDigestJob.fromJson(jsonDecode('{"status":"pending"}')).isActive, isTrue);
    });

    test('LearnDigestJob 全量解析：抓到谁 + 转写要花多少钱', () {
      final job = LearnDigestJob.fromJson(jsonDecode('''
        {"status":"needs_confirmation","message":"这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元",
         "stage":"transcribing",
         "source":{"platform":"bilibili","title":"某视频","author":"某UP","durationSeconds":2244},
         "cost":{"durationSeconds":2244,"durationKnown":true,"estimatedYuan":0.1795,
                 "monthUsedSeconds":0,"quotaSeconds":36000,"remainSeconds":36000}}
      '''));
      expect(job.isAwaitingConfirm, isTrue);
      expect(job.stageLabel, '正在转写，可能要几分钟');
      expect(job.source!.platform, 'bilibili');
      expect(job.source!.durationSeconds, 2244);
      expect(job.source!.durationText, '37 分钟');
      expect(job.source!.summaryLine, '某视频 · 某UP · 37 分钟');
      expect(job.cost!.durationKnown, isTrue);
      expect(job.cost!.estimatedYuan, closeTo(0.1795, 1e-9));
      expect(job.cost!.estimateText, '约 0.18 元');
      expect(job.cost!.remainText, '本月还剩 10 小时');
    });

    test('LearnCostDto 时长未知/整数金额也说得出来', () {
      final unknown = LearnCostDto.fromJson(jsonDecode(
          '{"durationSeconds":null,"durationKnown":false,"estimatedYuan":0,"remainSeconds":0}'));
      expect(unknown!.durationText, '时长没查到');
      expect(unknown.estimateText, '约 0.00 元');
      expect(unknown.remainText, '本月还剩 0 分钟');
      expect(LearnCostDto.fromJson(null), isNull);
    });

    test('LearnQuotaDto 缺字段兜底 + 额度说人话', () {
      final empty = LearnQuotaDto.fromJson(jsonDecode('{}'));
      expect(empty.month, '');
      expect(empty.asrAvailable, isTrue);
      expect(empty.remainText, '0 分钟');

      final q = LearnQuotaDto.fromJson(jsonDecode('''
        {"month":"2026-09","usedSeconds":1200,"usedYuan":0.096,"quotaSeconds":36000,
         "remainSeconds":34800,"yuanPerHour":0.288,"asrAvailable":true,"unavailableReason":null}
      '''));
      expect(q.month, '2026-09');
      expect(q.usedText, '20 分钟');
      expect(q.remainText, '9.7 小时');
      expect(q.yuanPerHour, closeTo(0.288, 1e-9));
      expect(q.unavailableReason, '');
    });

    test('LearnTreeResponse parses groups and empty groups omitted', () {
      final tree = LearnTreeResponse.fromJson(jsonDecode('''
        {"ai":[{"type":"ai","title":"RAG","created":"2026-09-06"}],
         "trading":[{"type":"trading","title":"回调","created":"2026-09-06"}],
         "other":[]}
      '''));
      expect(tree.ai.length, 1);
      expect(tree.trading.length, 1);
      expect(tree.other, isEmpty);
      expect(tree.isEmpty, isFalse);
      expect(tree.groups.length, 3, reason: '分组迭代固定 ai→trading→other');
    });
  });

  group('LearnPage', () {
    testWidgets('渲染树 + 点选显示卡片全文', (tester) async {
      final api = _api(tree: {
        'ai': [_card('ai', 'RAG 与 Agent', keyPoints: ['RAG 是检索增强', 'Agent 自主行动'])],
      });
      await pump(tester, api);

      expect(find.text('RAG 与 Agent'), findsWidgets);
      // 默认选中第一张 → 右侧显示核心观点与要点
      expect(find.text('RAG 与 Agent 的核心观点'), findsOneWidget);
      expect(find.text('RAG 是检索增强'), findsOneWidget);
      expect(find.text('Agent 自主行动'), findsOneWidget);
    });

    testWidgets('空态提示', (tester) async {
      final api = _api(tree: const {});
      await pump(tester, api);
      expect(find.textContaining('还没有学习卡片'), findsOneWidget);
    });

    testWidgets('加载失败降级显示错误 + 可重试', (tester) async {
      var calls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          calls++;
          if (calls == 1) return _json({'error': 'boom'}, status: 500);
          return _json({
            'ai': [_card('ai', '恢复的卡')]
          });
        }),
      );
      await pump(tester, api);
      expect(find.text('学习卡片加载失败，请重试'), findsOneWidget);

      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('恢复的卡'), findsWidgets);
    });

    testWidgets('多组渲染：AI 与交易分组建卡片', (tester) async {
      final api = _api(tree: {
        'ai': [_card('ai', 'RAG 笔记')],
        'trading': [_card('trading', '回调一半', keyPoints: ['回调=(high+low)/2'])],
      });
      await pump(tester, api);
      expect(find.text('学习笔记 · AI'), findsOneWidget);
      expect(find.text('学习笔记 · 交易'), findsOneWidget);
      expect(find.text('RAG 笔记'), findsWidgets);
      expect(find.text('回调一半'), findsWidgets);
    });
  });

  group('LearnPage V2 操作（复习流转/复述/反哺）', () {
    testWidgets('new 卡显示「去复习」与状态徽标，点击流转为 review', (tester) async {
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [_card('ai', 'RAG 笔记')]
            });
          }
          if (p.endsWith('/api/v1/learn/cards/status')) {
            final body = jsonDecode(req.body) as Map<String, dynamic>;
            expect(body['status'], 'review');
            return _json(_card('ai', 'RAG 笔记', status: 'review'));
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      // 列表徽标「待复习」+ 详情动作「去复习」
      expect(find.text('待复习'), findsWidgets);
      expect(find.text('去复习'), findsOneWidget);

      await tester.tap(find.text('去复习'));
      await tester.pumpAndSettle();

      expect(find.text('复习中'), findsWidgets);
      expect(find.text('标记完成'), findsOneWidget);
    });

    testWidgets('retell 空卡显示引导，写复述后保存并就地渲染', (tester) async {
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [_card('ai', 'RAG 笔记')]
            });
          }
          if (p.endsWith('/api/v1/learn/cards')) {
            final body = jsonDecode(req.body) as Map<String, dynamic>;
            return _json(_card('ai', 'RAG 笔记', status: 'new')
              ..['retell'] = body['retell'] as String? ?? '');
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      expect(find.textContaining('还没写复述'), findsOneWidget);
      await tester.tap(find.text('写复述'));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.descendant(of: find.byType(AlertDialog), matching: find.byType(TextField)),
          'RAG 是检索增强，Agent 自主规划，互补');
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();

      expect(find.textContaining('RAG 是检索增强，Agent 自主规划'), findsOneWidget);
    });

    testWidgets('trading+tradeRelated 卡显示「反哺候选」，点击调用候选端点', (tester) async {
      var candidateCalled = false;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'trading': [
                {
                  'type': 'trading',
                  'title': '回调一半的判定',
                  'created': '2026-09-06',
                  'status': 'new',
                  'tradeRelated': true,
                  'coreView': '回调一半才是买点',
                  'keyPoints': const ['02:31 回调一半'],
                }
              ]
            });
          }
          if (p.endsWith('/api/v1/learn/cards/candidate') && req.method == 'POST') {
            candidateCalled = true;
            expect(jsonDecode(req.body), {'type': 'trading', 'title': '回调一半的判定'});
            return _json({
              'title': '回调一半的判定',
              'learnCardId': 'learn/trading/2026-09-06_回调一半的判定',
              'sourceType': 'trading',
              'created': '2026-09-07',
            });
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      expect(find.text('反哺候选'), findsOneWidget);
      await tester.tap(find.text('反哺候选'));
      await tester.pumpAndSettle();

      expect(candidateCalled, isTrue, reason: '应调用 POST /learn/cards/candidate');
      expect(find.textContaining('已生成规则候选'), findsOneWidget);
    });

    testWidgets('非 tradeRelated 卡不显示反哺按钮', (tester) async {
      final api = _api(tree: {
        'ai': [_card('ai', 'RAG 笔记')],
      });
      await pump(tester, api);
      expect(find.text('反哺候选'), findsNothing);
    });

    testWidgets('P2-learn5：trading 插件关闭时 trading 卡不显示可点反哺按钮', (tester) async {
      final api = _api(tree: {
        'trading': [
          {
            'type': 'trading',
            'title': '回调一半的判定',
            'created': '2026-09-06',
            'status': 'new',
            'tradeRelated': true,
            'coreView': '回调一半才是买点',
          }
        ],
      });
      await tester.binding.setSurfaceSize(const Size(1200, 800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
            body: LearnPage(api: api, tradingEnabled: false)),
      ));
      await tester.pumpAndSettle();
      // trading 关闭：反哺候选入口整体隐藏（门控 P2-learn5，不可达即不出现）
      expect(find.text('反哺候选'), findsNothing);
      expect(find.text('反哺候选（不可用）'), findsNothing);
    });

    testWidgets('P2-learn4：页头候选入口可打开列表并删除', (tester) async {
      var listCalled = false;
      var deleteCalled = false;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({'ai': [_card('ai', 'RAG 笔记')]});
          }
          if (p.endsWith('/api/v1/learn/cards/candidates')) {
            if (req.method == 'GET') {
              listCalled = true;
              return _json([
                {
                  'title': '回调一半候选',
                  'learnCardId': 'learn/trading/2026-09-06_回调一半的判定',
                  'sourceType': 'trading',
                  'created': '2026-09-07',
                  'coreView': '回调一半才是买点',
                }
              ]);
            }
            if (req.method == 'DELETE') {
              deleteCalled = true;
              expect(req.url.queryParameters['title'], '回调一半候选');
              return _json({'deleted': true});
            }
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await tester.tap(find.byTooltip('反哺候选（交易规则建议，审核后融合）'));
      await tester.pumpAndSettle();
      expect(listCalled, isTrue);
      expect(find.text('回调一半候选'), findsOneWidget);

      await tester.tap(find.byIcon(Icons.delete_outline));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删除'));
      await tester.pumpAndSettle();
      expect(deleteCalled, isTrue, reason: '应调用 DELETE 候选');
    });

    testWidgets('S-learn2：页头复习提醒开关读取并可切换', (tester) async {
      var putCalled = false;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
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
            expect(jsonDecode(req.body), {'enabled': false});
            return _json({'learn-review': false});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await tester.tap(find.byTooltip('复习提醒开关'));
      await tester.pumpAndSettle();
      expect(find.text('开启（每晚 20:00 提醒）'), findsOneWidget);

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(putCalled, isTrue, reason: '切换应调用 PUT /learn/push-settings/learn-review');
    });
  });

  group('LearnPage 喂入（2026-09-10 提交式）', () {
    testWidgets('整理新内容：提交 → 轮询 done → 打开新卡', (tester) async {
      var treeCalls = 0;
      var pollCount = 0;
      var submitted = false;
      var submitBody = <String, dynamic>{};
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            if (treeCalls == 1) return _json(const {});
            return _json({
              'ai': [_card('ai', '阿呆消化了新卡')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            submitted = true;
            submitBody = jsonDecode(req.body) as Map<String, dynamic>;
            expect(submitBody['content'], contains('字幕'));
            // 未手动选类型 → 不传 type，交给阿呆自动判定
            expect(submitBody.containsKey('type'), isFalse);
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            // 进页面时先查一次有没有等你拍板的任务（P2-learn17）——没提交前手头没任务
            if (!submitted) return _json({'status': 'idle'});
            pollCount++;
            if (pollCount >= 2) {
              return _json({'status': 'done', 'type': 'ai', 'title': '阿呆消化了新卡'});
            }
            return _json({'status': 'running', 'stage': 'structuring'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      expect(find.textContaining('还没有学习卡片'), findsOneWidget);

      // 空态与页头都有入口；点页头「＋」弹表单
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();
      expect(find.text('开始整理'), findsOneWidget);

      await tester.enterText(
          find.byKey(const ValueKey('learn-digest-content')), '这是一段视频字幕素材内容，讲 RAG…');
      await tester.pump();
      await tester.tap(find.text('开始整理'));
      await tester.pump();

      // 提交式：立即受理 → 弹窗转消化中（轮询每 2s）
      expect(find.text('整理新内容 · 消化中'), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在整理成卡片'), findsOneWidget, reason: 'stage 要说人话');
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));

      // done → 关弹窗 → snack + 整树刷新并选中新卡
      expect(pollCount, greaterThanOrEqualTo(2));
      expect(find.textContaining('已沉淀学习卡片'), findsOneWidget);
      await tester.pumpAndSettle();
      expect(find.text('阿呆消化了新卡'), findsWidgets);
      expect(find.text('阿呆消化了新卡 的核心观点'), findsOneWidget);
    });

    testWidgets('喂入校验：链接与素材都没填时「开始整理」不可点', (tester) async {
      var posted = false;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            posted = true;
            return _json({'status': 'pending'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();

      final button = tester.widget<FilledButton>(find.widgetWithText(FilledButton, '开始整理'));
      expect(button.onPressed, isNull, reason: '两个框都空 → 按钮不可点');

      await tester.tap(find.text('开始整理'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(posted, isFalse, reason: '空素材不应提交');
    });

    testWidgets('喂入失败：后端 400 人话透出，弹窗可重试', (tester) async {
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            return _json({'error': '这个链接我打不开（对方不给看），把正文粘进来也行'},
                status: 400);
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.byKey(const ValueKey('learn-digest-url')), 'https://example.com/a');
      await tester.pump();
      await tester.tap(find.text('开始整理'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('这个链接我打不开'), findsOneWidget);
      // 表单仍可编辑重试（弹窗未关）
      expect(find.text('开始整理'), findsOneWidget);
    });
  });

  // ── RFC 20260912 D 形态抓取批：链接喂入 + 转写费用确认（前端）──
  group('LearnPage 喂入·链接与转写费用确认（RFC 20260912）', () {
    testWidgets('① 丢链接就能整理：提交带 url → 轮询 done → 自动打开新卡', (tester) async {
      var treeCalls = 0;
      var pollCount = 0;
      var submitted = false;
      var submitBody = <String, dynamic>{};
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            if (treeCalls == 1) return _json(const {});
            return _json({
              'ai': [_card('ai', 'B站视频整理出来的卡')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest/quota')) return _json(_quotaJson());
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            submitted = true;
            submitBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            if (!submitted) return _json({'status': 'idle'}); // 进页面那次查询：手头没任务
            pollCount++;
            if (pollCount >= 2) {
              return _json({'status': 'done', 'type': 'ai', 'title': 'B站视频整理出来的卡'});
            }
            return _json({
              'status': 'running',
              'stage': 'fetching',
              'source': {'platform': 'bilibili', 'title': '某视频', 'author': '某UP'},
            });
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-url')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.pump();
      await tester.tap(find.text('开始整理'));
      await tester.pump();

      // 只给了链接 → 提交就是链接，不塞空素材
      expect(submitBody['url'], 'https://www.bilibili.com/video/BV1xx411c7mD');
      expect(submitBody.containsKey('content'), isFalse);

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在抓取原文'), findsOneWidget);
      expect(find.textContaining('某视频'), findsOneWidget, reason: '抓到谁要说一声');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));

      // done → 关弹窗 → 提示 + 整树刷新并选中新卡
      expect(find.textContaining('已沉淀学习卡片'), findsOneWidget);
      await tester.pumpAndSettle();
      expect(find.text('B站视频整理出来的卡 的核心观点'), findsOneWidget);
    });

    testWidgets('② 没字幕要先报价：摆出钱数、停下轮询，绝不自动花钱', (tester) async {
      var confirmCalls = 0;
      var statusCalls = 0;
      var submitted = false;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest/quota')) return _json(_quotaJson());
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            submitted = true;
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmCalls++;
            return _json({'status': 'running'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            if (!submitted) return _json({'status': 'idle'}); // 进页面那次查询：手头没任务
            statusCalls++;
            return _json(_needsConfirmJson());
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();

      // 表单上先把本月额度说清楚
      expect(find.textContaining('本月转写还剩 9.7 小时'), findsOneWidget);

      await tester.enterText(find.byKey(const ValueKey('learn-digest-url')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.pump();
      await tester.tap(find.text('开始整理'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();

      expect(find.text('整理新内容 · 转写要花钱，你说了算'), findsOneWidget);
      expect(find.textContaining('这个视频没有字幕，需要转写'), findsOneWidget);
      expect(find.textContaining('预计花 约 0.18 元'), findsOneWidget);
      expect(find.textContaining('来源：某视频 · 某UP · 37 分钟'), findsOneWidget);
      expect(find.text('继续转写'), findsOneWidget);
      expect(find.text('先不转写'), findsOneWidget);
      expect(confirmCalls, 0, reason: '没点头之前一分钱都不能花');

      // 停下轮询：再等 6 秒也不会再去问进度
      final seen = statusCalls;
      await tester.pump(const Duration(seconds: 6));
      await tester.pump();
      expect(statusCalls, seen, reason: '等待确认期间应停止轮询');
      expect(confirmCalls, 0);
    });

    testWidgets('③ 点「继续转写」：confirm(true) 之后恢复轮询，done 打开新卡', (tester) async {
      var treeCalls = 0;
      var statusCalls = 0;
      var submitted = false;
      Map<String, dynamic>? confirmBody;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            if (treeCalls == 1) return _json(const {});
            return _json({
              'ai': [_card('ai', '转写后整理出来的卡')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest/quota')) return _json(_quotaJson());
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            submitted = true;
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({'status': 'running', 'stage': 'transcribing'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            if (!submitted) return _json({'status': 'idle'}); // 进页面那次查询：手头没任务
            statusCalls++;
            if (statusCalls == 1) return _json(_needsConfirmJson());
            return _json({'status': 'done', 'type': 'ai', 'title': '转写后整理出来的卡'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('learn-digest-url')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.pump();
      await tester.tap(find.text('开始整理'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('继续转写'), findsOneWidget);

      await tester.tap(find.text('继续转写'));
      await tester.pump();
      await tester.pump();

      expect(confirmBody, {'confirm': true}, reason: '只发一次确认，且是继续');
      expect(find.text('整理新内容 · 消化中'), findsOneWidget, reason: '点头后恢复轮询');
      expect(find.text('正在转写，可能要几分钟'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.textContaining('已沉淀学习卡片'), findsOneWidget);
      await tester.pumpAndSettle();
      expect(find.text('转写后整理出来的卡 的核心观点'), findsOneWidget);
    });

    testWidgets('④ 点「先不转写」：confirm(false) 收尾，不进 done 流程', (tester) async {
      var treeCalls = 0;
      var statusCalls = 0;
      var submitted = false;
      Map<String, dynamic>? confirmBody;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            return _json(const {});
          }
          if (p.endsWith('/api/v1/learn/digest/quota')) return _json(_quotaJson());
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            submitted = true;
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({
              'status': 'cancelled',
              'message': '已取消转写（没花钱），抓到的元数据我留着了，回头想整理再说一声',
            });
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            if (!submitted) return _json({'status': 'idle'}); // 进页面那次查询：手头没任务
            statusCalls++;
            return _json(_needsConfirmJson());
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('learn-digest-url')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.pump();
      await tester.tap(find.text('开始整理'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('先不转写'), findsOneWidget);

      await tester.tap(find.text('先不转写'));
      await tester.pump();
      await tester.pump();

      expect(confirmBody, {'confirm': false}, reason: '只发一次确认，且是不转写');
      expect(find.textContaining('已取消转写（没花钱）'), findsOneWidget);
      expect(find.text('关闭'), findsOneWidget);
      expect(find.text('继续转写'), findsNothing);
      // 不进 done 流程：没有沉淀提示，也没重拉资产树
      expect(find.textContaining('已沉淀学习卡片'), findsNothing);
      expect(treeCalls, 1, reason: '取消 → 不刷新资产树、不打开新卡');

      // 轮询已停：再等也不问进度
      final seen = statusCalls;
      await tester.pump(const Duration(seconds: 6));
      await tester.pump();
      expect(statusCalls, seen);
    });

    testWidgets('⑤ 只填链接、素材留空时「开始整理」可用', (tester) async {
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest/quota')) return _json(_quotaJson());
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();

      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, '开始整理')).onPressed,
        isNull,
        reason: '两个框都空 → 不可点',
      );

      await tester.enterText(find.byKey(const ValueKey('learn-digest-url')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.pump();

      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, '开始整理')).onPressed,
        isNotNull,
        reason: '只给链接（素材留空）也能开始整理',
      );
      expect(find.textContaining('本月转写还剩 9.7 小时'), findsOneWidget);
    });

    testWidgets('⑥ 阶段说人话：抓取 → 转写 → 整理，各有各的说法', (tester) async {
      const stages = ['fetching', 'transcribing', 'structuring'];
      var i = 0;
      var submitted = false;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest/quota')) return _json(_quotaJson());
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            submitted = true;
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            if (!submitted) return _json({'status': 'idle'}); // 进页面那次查询：手头没任务
            if (i < stages.length) return _json({'status': 'running', 'stage': stages[i++]});
            return _json({'status': 'done', 'type': 'ai', 'title': '收尾的卡'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('learn-digest-url')), 'https://example.com/a');
      await tester.pump();
      await tester.tap(find.text('开始整理'));
      await tester.pump();

      for (final text in ['正在抓取原文', '正在转写，可能要几分钟', '正在整理成卡片']) {
        await tester.pump(const Duration(seconds: 2));
        await tester.pump();
        expect(find.text(text), findsOneWidget);
      }

      // 收尾到 done，别把轮询定时器留在测试里
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.textContaining('已沉淀学习卡片'), findsOneWidget);
      await tester.pumpAndSettle();
    });
  });

  // ── 2026-09-12 完整升级批：topic / writable / 全文 / 图片 / 确认恢复 ──

  group('learn 新契约解析（2026-09-12）', () {
    test('LearnCardDto 缺 topic/writable 字段：topic 空串（展示「未归类」）、writable 默认 true，旧响应不炸', () {
      final old = LearnCardDto.fromJson(jsonDecode('{"type":"ai","title":"老响应","created":"2026-09-06"}'));
      expect(old.topic, '');
      expect(old.topicLabel, '未归类');
      expect(old.writable, isTrue);
      expect(old.readOnly, isFalse);

      final readOnly = LearnCardDto.fromJson(jsonDecode(
          '{"type":"ai","title":"手工卡","created":"2026-09-06","topic":"harness","writable":false}'));
      expect(readOnly.topicLabel, 'harness');
      expect(readOnly.writable, isFalse);
      expect(readOnly.readOnly, isTrue);
    });

    test('LearnCardContentDto：全文 + 元信息，缺字段兜底', () {
      final c = LearnCardContentDto.fromJson(jsonDecode(
          '{"type":"ai","title":"卡","topic":"harness","writable":false,"content":"# 标题\\n正文"}'));
      expect(c.type, 'ai');
      expect(c.topic, 'harness');
      expect(c.writable, isFalse);
      expect(c.hasContent, isTrue);

      final bare = LearnCardContentDto.fromJson(jsonDecode('{}'));
      expect(bare.content, '');
      expect(bare.hasContent, isFalse);
      expect(bare.writable, isTrue, reason: '缺字段按可写兜底（不因解析把卡片变只读）');
    });

    test('LearnDigestJob stage=reading 说人话（正在读图）', () {
      final job = LearnDigestJob.fromJson(jsonDecode('{"status":"running","stage":"reading"}'));
      expect(job.stageLabel, '正在读图');
      expect(job.isActive, isTrue);
    });

    test('parseLearnMarkdown：跳 frontmatter/H1，段名归一化后可对齐产品建模的四段', () {
      final sections = parseLearnMarkdown('''
---
title: 卡片
type: ai
---

# 大标题

## 二、核心观点
观点正文

## 三、关键内容详解
- 细节一
- 细节二

## 四、金句
"Humans steer."
''');
      expect(sections.map((s) => s.title).toList(), ['二、核心观点', '三、关键内容详解', '四、金句']);
      expect(sections.first.normalizedTitle, '核心观点');
      expect(sections.first.isModeled, isTrue, reason: '「二、核心观点」= 产品已建模的段');
      expect(sections[1].normalizedTitle, '关键内容详解');
      expect(sections[1].isModeled, isFalse, reason: '关键内容详解是产品没建模的段，必须照原文读出来');
      expect(sections[1].body, contains('细节一'));
    });

    test('groupCardsByTopic：空 topic → 未归类；组间按组内最新卡倒序；组内保持传入顺序', () {
      final cards = [
        LearnCardDto(type: 'ai', title: '新', created: '2026-09-10', topic: 'harness'),
        LearnCardDto(type: 'ai', title: '旧', created: '2026-09-01', topic: 'harness'),
        LearnCardDto(type: 'ai', title: '散', created: '2026-09-05', topic: ''),
      ];
      final grouped = groupCardsByTopic(cards);
      expect(grouped.map((g) => g.$1).toList(), ['harness', '未归类']);
      expect(grouped.first.$2.map((c) => c.title).toList(), ['新', '旧']);
      expect(grouped.last.$2.single.title, '散');
    });
  });

  group('LearnPage topic 两级分组（2026-09-12）', () {
    testWidgets('type → topic 两级分组：topic 小标题带数量，组内按 created 倒序，空 topic 归「未归类」', (tester) async {
      final api = _api(tree: {
        'ai': [
          _card('ai', '旧卡', topic: 'harness', created: '2026-09-01'),
          _card('ai', '新卡', topic: 'harness', created: '2026-09-10'),
          _card('ai', '散卡', topic: '', created: '2026-09-05'),
        ],
      });
      await pump(tester, api);

      expect(find.text('学习笔记 · AI'), findsOneWidget);
      expect(find.text('harness（2）'), findsOneWidget);
      expect(find.text('未归类（1）'), findsOneWidget);
      // created 倒序：新卡(09-10) → 散卡(09-05) → 旧卡(09-01)，topic 分组不改线性下标
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('learn-card-ai-0')), matching: find.text('新卡')),
          findsOneWidget);
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('learn-card-ai-1')), matching: find.text('散卡')),
          findsOneWidget);
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('learn-card-ai-2')), matching: find.text('旧卡')),
          findsOneWidget);
    });
  });

  group('LearnPage 全文渲染（2026-09-12）', () {
    const md = '''---
title: Harness engineering
type: ai
---

# Harness engineering

## 二、核心观点
1. Humans steer. Agents execute.

## 三、关键内容详解
- 环境必须对 agent 可读

## 四、金句
"give Codex a map, not a 1,000-page manual."

## 五、与主题概念的关系
本文是组织层面的实践报告。
''';

    testWidgets('读 /learn/content 的 md 原文：产品没建模的段（关键内容详解/金句/概念关系）也显示出来', (tester) async {
      var contentCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [
                _card('ai', 'Harness engineering', topic: 'harness', keyPoints: const ['要点一']),
              ]
            });
          }
          if (p.endsWith('/api/v1/learn/content')) {
            contentCalls++;
            expect(req.url.queryParameters['type'], 'ai');
            expect(req.url.queryParameters['title'], 'Harness engineering');
            return _json(_contentJson('ai', 'Harness engineering', md, topic: 'harness'));
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      expect(contentCalls, greaterThanOrEqualTo(1), reason: '详情必须走全文端点');
      // 产品建模的四段仍用结构化渲染（来自列表接口）
      expect(find.text('Harness engineering 的核心观点'), findsOneWidget);
      expect(find.text('要点一'), findsOneWidget);
      // 产品没建模的段照样读得出来（md 原文渲染）
      expect(find.text('关键内容详解'), findsOneWidget);
      expect(find.textContaining('环境必须对 agent 可读', findRichText: true), findsOneWidget);
      expect(find.text('金句'), findsOneWidget);
      expect(find.textContaining('give Codex a map', findRichText: true), findsOneWidget);
      expect(find.text('与主题概念的关系'), findsOneWidget);
      // 已结构化渲染过的段不重复贴一份 md
      expect(find.text('二、核心观点'), findsNothing);
    });

    testWidgets('全文没读上来：给错误态 + 重试，重试成功就能读出来', (tester) async {
      var contentCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [_card('ai', 'Harness engineering', topic: 'harness')]
            });
          }
          if (p.endsWith('/api/v1/learn/content')) {
            contentCalls++;
            if (contentCalls == 1) return _json({'error': 'boom'}, status: 500);
            return _json(_contentJson('ai', 'Harness engineering', md, topic: 'harness'));
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      expect(find.textContaining('这篇的原文我没读上来'), findsOneWidget);
      expect(find.text('关键内容详解'), findsNothing);

      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('关键内容详解'), findsOneWidget);
      expect(contentCalls, 2);
    });
  });

  group('LearnPage 只读卡（2026-09-12）', () {
    testWidgets('writable=false：隐藏去复习/写复述/反哺候选，并给一行说明（全文照读）', (tester) async {
      final api = _api(tree: {
        'trading': [
          _card('trading', 'Mac 整理的原始卡',
              writable: false, tradeRelated: true, status: 'new', topic: 'harness'),
        ],
      });
      await pump(tester, api);

      expect(find.textContaining('这张是在 Mac 上整理的原始卡'), findsOneWidget);
      expect(find.text('去复习'), findsNothing, reason: '只读卡不给流转入口');
      expect(find.text('写复述'), findsNothing, reason: '只读卡不给写入口');
      expect(find.text('反哺候选'), findsNothing);
      expect(find.text('反哺候选（不可用）'), findsNothing);
      expect(find.textContaining('在这里写复述'), findsOneWidget);
      // 只读 ≠ 看不见：正文照常渲染
      expect(find.text('Mac 整理的原始卡 的核心观点'), findsOneWidget);
    });

    testWidgets('writable=true：写入口照旧（回归，行为不变）', (tester) async {
      final api = _api(tree: {
        'ai': [_card('ai', 'RAG 笔记', topic: 'harness')],
      });
      await pump(tester, api);

      expect(find.text('去复习'), findsOneWidget);
      expect(find.text('写复述'), findsOneWidget);
      expect(find.textContaining('这张是在 Mac 上整理的原始卡'), findsNothing);
      expect(find.textContaining('还没写复述'), findsOneWidget);
    });
  });

  group('LearnPage 图片喂入（2026-09-12）', () {
    testWidgets('选图整理：multipart 提交（带 Bearer）→ 轮询 reading「正在读图」→ done 打开新卡', (tester) async {
      // 真文件选择器在测试环境里没注册 → 换成替身；跑完还原（原本没注册就保持不设）
      FilePicker? originalPicker;
      try {
        originalPicker = FilePicker.platform;
      } catch (_) {
        originalPicker = null;
      }
      FilePicker.platform = _FakeImagePicker();
      addTearDown(() {
        if (originalPicker != null) FilePicker.platform = originalPicker;
      });

      var treeCalls = 0;
      var imageSubmitCalls = 0;
      var textSubmitCalls = 0;
      var pollCount = 0;
      var submitted = false;
      String? multipartBody;
      String? multipartAuth;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        token: 'tk-test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            if (treeCalls == 1) return _json(const {});
            return _json({
              'ai': [_card('ai', '从图片整理出来的卡', topic: 'harness')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest/quota')) return _json(_quotaJson());
          if (p.endsWith('/api/v1/learn/digest/image') && req.method == 'POST') {
            imageSubmitCalls++;
            submitted = true;
            multipartBody = req.body;
            multipartAuth = req.headers['authorization'];
            expect(req.headers['content-type'], contains('multipart/form-data'));
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            textSubmitCalls++;
            return _json({'status': 'pending'});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) {
            if (!submitted) return _json({'status': 'idle'}); // 进页面那次查询：手头没任务
            pollCount++;
            if (pollCount >= 2) {
              return _json({'status': 'done', 'type': 'ai', 'title': '从图片整理出来的卡', 'topic': 'harness'});
            }
            return _json({'status': 'running', 'stage': 'reading'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byTooltip('整理新内容（丢链接或粘素材，阿呆消化成卡片）'));
      await tester.pumpAndSettle();

      expect(find.text('选图整理（1~3 张）'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('learn-digest-pick-images')));
      await tester.pumpAndSettle();

      // 选好图：文件名列出，链接/素材框锁住（三条路径互斥），按钮变成「开始读图」
      expect(find.text('page1.png'), findsOneWidget);
      expect(find.text('page2.png'), findsOneWidget);
      expect(tester.widget<TextField>(find.byKey(const ValueKey('learn-digest-url'))).enabled, isFalse,
          reason: '选了图 → 链接框锁住，不让同时提交两份');
      expect(
          tester.widget<TextField>(find.byKey(const ValueKey('learn-digest-content'))).enabled,
          isFalse,
          reason: '选了图 → 素材框锁住');
      expect(find.text('开始整理'), findsNothing);

      await tester.tap(find.text('开始读图'));
      await tester.pump();

      expect(imageSubmitCalls, 1);
      expect(textSubmitCalls, 0, reason: '走图片入口就不再走链接/素材那份提交');
      expect(multipartAuth, 'Bearer tk-test', reason: 'multipart 也要显式带 Bearer');
      expect(RegExp('name="files"').allMatches(multipartBody!).length, 2, reason: '两张图两个 files 字段');
      expect(multipartBody, contains('page1.png'));

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在读图'), findsOneWidget, reason: 'reading 阶段说人话');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.textContaining('已沉淀学习卡片'), findsOneWidget);
      await tester.pumpAndSettle();
      expect(find.text('从图片整理出来的卡 的核心观点'), findsOneWidget);
    });
  });

  group('LearnPage 确认恢复入口（P2-learn17）', () {
    testWidgets('进页面发现有待拍板的任务：顶部提示条带报价 + 继续/先不转写（连点只发一份）', (tester) async {
      var confirmCalls = 0;
      Map<String, dynamic>? confirmBody;
      final confirmGate = Completer<void>(); // 卡住响应，观察「连点只发一份」的守卫
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [_card('ai', 'RAG 笔记', topic: 'harness')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest/status')) return _json(_needsConfirmJson());
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmCalls++;
            confirmBody = jsonDecode(req.body) as Map<String, dynamic>;
            await confirmGate.future;
            return _json(
                {'status': 'cancelled', 'message': '已取消转写（没花钱），抓到的元数据我留着了'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      expect(find.textContaining('有一件事等你拍板'), findsOneWidget);
      expect(find.textContaining('这个视频没有字幕，需要转写'), findsOneWidget, reason: '报价照后端给的摆出来');
      expect(find.text('继续转写'), findsOneWidget);
      expect(find.text('先不转写'), findsOneWidget);

      // 连点两次：请求在途时按钮已禁用，第二次点不出第二份
      await tester.tap(find.text('先不转写'));
      await tester.pump();
      expect(
          tester
              .widget<TextButton>(find.widgetWithText(TextButton, '先不转写'))
              .onPressed,
          isNull,
          reason: '在途守卫：按钮先禁用');
      await tester.tap(find.text('先不转写'), warnIfMissed: false);
      await tester.pump(const Duration(milliseconds: 100));

      confirmGate.complete();
      await tester.pumpAndSettle();

      expect(confirmCalls, 1, reason: '连点守卫：只发一份');
      expect(confirmBody, {'confirm': false});
      expect(find.textContaining('已取消转写（没花钱）'), findsOneWidget);
      expect(find.textContaining('有一件事等你拍板'), findsNothing, reason: '拍板完提示条收起');
    });

    testWidgets('页头提示条点「继续转写」：弹窗直接进消化中，轮询 done 打开新卡', (tester) async {
      var treeCalls = 0;
      var pollCount = 0;
      var confirmed = false;
      Map<String, dynamic>? confirmBody;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            treeCalls++;
            if (treeCalls == 1) {
              return _json({
                'ai': [_card('ai', 'RAG 笔记', topic: 'harness')]
              });
            }
            return _json({
              'ai': [_card('ai', '转写后整理出来的卡', topic: 'harness')]
            });
          }
          if (p.endsWith('/api/v1/learn/digest/quota')) return _json(_quotaJson());
          if (p.endsWith('/api/v1/learn/digest/status')) {
            if (!confirmed) return _json(_needsConfirmJson()); // 进页面那次查询：有待拍板
            pollCount++;
            if (pollCount >= 2) {
              return _json({'status': 'done', 'type': 'ai', 'title': '转写后整理出来的卡', 'topic': 'harness'});
            }
            return _json({'status': 'running', 'stage': 'transcribing'});
          }
          if (p.endsWith('/api/v1/learn/digest/confirm')) {
            confirmed = true;
            confirmBody = jsonDecode(req.body) as Map<String, dynamic>;
            return _json({'status': 'running', 'stage': 'transcribing'});
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      expect(find.textContaining('有一件事等你拍板'), findsOneWidget);

      await tester.tap(find.text('继续转写'));
      await tester.pump();
      await tester.pump();

      expect(confirmBody, {'confirm': true});
      // 已在转写 → 弹窗直接是「消化中」，不再摆一张空表单
      expect(find.text('整理新内容 · 消化中'), findsOneWidget);
      expect(find.text('正在接着转写，可能要几分钟'), findsOneWidget);
      expect(find.text('开始整理'), findsNothing);

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在转写，可能要几分钟'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.textContaining('已沉淀学习卡片'), findsOneWidget);
      await tester.pumpAndSettle();
      expect(find.text('转写后整理出来的卡 的核心观点'), findsOneWidget);
    });

    testWidgets('没有待拍板的任务：不显示提示条', (tester) async {
      final api = _api(tree: {
        'ai': [_card('ai', 'RAG 笔记')],
      });
      await pump(tester, api);
      expect(find.textContaining('有一件事等你拍板'), findsNothing);
    });
  });

  group('LearnPage 对话流跳进来（openCard，2026-09-12）', () {
    testWidgets('带 openCard 进页面：树加载完直接定位并打开该卡（含全文）', (tester) async {
      var contentCalls = 0;
      String? askedTitle;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [
                _card('ai', '第一张卡', topic: 'harness', created: '2026-09-10'),
                _card('ai', '第二张卡', topic: 'harness', created: '2026-09-01'),
              ]
            });
          }
          if (p.endsWith('/api/v1/learn/content')) {
            contentCalls++;
            askedTitle = req.url.queryParameters['title'];
            return _json(_contentJson('ai', '第二张卡', '''
# 第二张卡

## 关键内容详解
- 从对话流跳进来要能直接读全
''', topic: 'harness'));
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await tester.binding.setSurfaceSize(const Size(1200, 800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: LearnPage(api: api, openCard: (id: 1, type: 'ai', title: '第二张卡')),
        ),
      ));
      await tester.pumpAndSettle();

      expect(contentCalls, 1, reason: 'onCreate 就带请求 → 树到位后只读这一张的全文');
      expect(askedTitle, '第二张卡');
      expect(find.text('第二张卡 的核心观点'), findsOneWidget);
      expect(find.text('关键内容详解'), findsOneWidget);
    });

    testWidgets('页面已挂着时收到新请求（didUpdateWidget）也能定位打开', (tester) async {
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [
                _card('ai', '第一张卡', topic: 'harness', created: '2026-09-10'),
                _card('ai', '第二张卡', topic: 'harness', created: '2026-09-01'),
              ]
            });
          }
          if (p.endsWith('/api/v1/learn/content')) {
            return _json(_contentJson('ai', '第二张卡', '# 第二张卡\n\n## 关键内容详解\n- 后到的请求也要能打开\n'));
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await tester.binding.setSurfaceSize(const Size(1200, 800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      Widget wrap(({int id, String type, String title})? req) => MaterialApp(
            home: Scaffold(body: LearnPage(api: api, openCard: req)),
          );

      await tester.pumpWidget(wrap(null));
      await tester.pumpAndSettle();
      expect(find.text('第一张卡 的核心观点'), findsOneWidget, reason: '默认选中第一张');

      await tester.pumpWidget(wrap((id: 1, type: 'ai', title: '第二张卡')));
      await tester.pumpAndSettle();
      expect(find.text('第二张卡 的核心观点'), findsOneWidget);
      expect(find.text('关键内容详解'), findsOneWidget);
    });
  });

  group('LearnPage 搜索（2026-09-12）', () {
    testWidgets('顶部搜索框防抖 300ms → GET /learn/find → 点结果打开该卡全文', (tester) async {
      var findCalls = 0;
      String? lastQ;
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [_card('ai', 'Harness engineering', topic: 'harness')]
            });
          }
          if (p.endsWith('/api/v1/learn/find')) {
            findCalls++;
            lastQ = req.url.queryParameters['q'];
            return _json([
              _card('ai', 'Harness engineering', topic: 'harness')
            ]);
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await tester.enterText(find.byKey(const ValueKey('learn-search')), 'harness');
      await tester.pump(const Duration(milliseconds: 100));
      expect(findCalls, 0, reason: '防抖 300ms：还在打字就先不发');

      await tester.pump(const Duration(milliseconds: 300));
      await tester.pumpAndSettle();
      expect(findCalls, 1);
      expect(lastQ, 'harness');
      expect(find.text('找到 1 张卡'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('learn-search-hit-ai-Harness engineering')));
      await tester.pumpAndSettle();
      // 点结果 → 回到目录并打开该卡
      expect(find.text('找到 1 张卡'), findsNothing);
      expect(find.text('Harness engineering 的核心观点'), findsOneWidget);
    });
  });
}
