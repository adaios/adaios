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
  });
}
