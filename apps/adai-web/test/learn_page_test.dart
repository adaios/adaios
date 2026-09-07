import 'dart:convert';
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
        {List<String> keyPoints = const [], String status = 'new'}) =>
    {
      'type': type,
      'title': title,
      'platform': 'bilibili',
      'author': '某UP',
      'created': '2026-09-06',
      'status': status,
      'tags': const ['rag'],
      'coreView': '$title 的核心观点',
      'keyPoints': keyPoints,
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

      await tester.enterText(find.byType(TextField), 'RAG 是检索增强，Agent 自主规划，互补');
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
  });
}
