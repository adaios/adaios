import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:adai_app/main.dart';
import 'package:adai_app/widgets/feed_card.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:flutter/material.dart';

// ─── DTO Parsing Tests (unit, no widget tree needed) ───

void main() {
  group('DTO JSON parsing', () {
    test('FeedResponse parses correctly', () {
      final json = jsonDecode('''
        {"brief": "morning", "entries": [], "earlierCount": 0}
      ''');
      final feed = FeedResponse.fromJson(json);
      expect(feed.brief, 'morning');
      expect(feed.entries, isEmpty);
      expect(feed.earlierCount, 0);
    });

    test('FeedResponse parses entries', () {
      final json = jsonDecode('''
        {
          "brief": "hello",
          "entries": [
            {"type": "record", "id": "r1", "title": "t", "content": "buy stock", "tags": ["invest"], "time": "14:30"}
          ],
          "earlierCount": 2
        }
      ''');
      final feed = FeedResponse.fromJson(json);
      expect(feed.entries.length, 1);
      expect(feed.entries[0].content, 'buy stock');
      expect(feed.entries[0].tags, ['invest']);
      expect(feed.earlierCount, 2);
    });

    test('FeedEntryResponse defaults', () {
      final json = jsonDecode('{"type": "record", "id": "r1", "content": "test", "time": "10:00"}');
      final entry = FeedEntryResponse.fromJson(json);
      expect(entry.title, '');
      expect(entry.tags, isEmpty);
      expect(entry.id, 'r1');
    });

    test('RecordResponse log intent', () {
      final json = jsonDecode('{"intent": "log", "recordId": "r1", "tags": ["a", "b"], "summary": "done"}');
      final resp = RecordResponse.fromJson(json);
      expect(resp.intent, 'log');
      expect(resp.tags, ['a', 'b']);
      expect(resp.summary, 'done');
    });

    test('RecordResponse question intent', () {
      final json = jsonDecode('{"intent": "question", "recordId": "r1", "summary": "AI reply"}');
      final resp = RecordResponse.fromJson(json);
      expect(resp.intent, 'question');
      expect(resp.summary, 'AI reply');
    });

    test('RecordResponse defaults to log', () {
      final json = jsonDecode('{"intent": null}');
      final resp = RecordResponse.fromJson(json);
      expect(resp.intent, 'log');
    });

    test('EndConversationResponse parses', () {
      final json = jsonDecode('{"recordId": "r1", "summary": "done", "tags": ["chat"]}');
      final resp = EndConversationResponse.fromJson(json);
      expect(resp.recordId, 'r1');
      expect(resp.summary, 'done');
      expect(resp.tags, ['chat']);
    });
  });

  // ─── FeedCardData model tests ───

  group('FeedCardData model', () {
    test('copyWith preserves unset fields', () {
      final card = FeedCardData(id: '1', type: FeedCardType.record, time: '14:00', content: 'hi');
      final updated = card.copyWith(mode: CardMode.waiting);
      expect(updated.id, '1');
      expect(updated.content, 'hi');
      expect(updated.mode, CardMode.waiting);
    });

    test('copyWith changes mode', () {
      final card = FeedCardData(id: '1', type: FeedCardType.record, time: '14:00', content: 'hi');
      expect(card.mode, CardMode.idle);
      final chatting = card.copyWith(mode: CardMode.chatting);
      expect(chatting.mode, CardMode.chatting);
    });

    test('copyWith changes mode and loading', () {
      final card = FeedCardData(id: '1', type: FeedCardType.record, time: '14:00', content: 'hi');
      final ended = card.copyWith(mode: CardMode.ended, summary: 'done');
      expect(ended.mode, CardMode.ended);
      expect(ended.summary, 'done');
    });

    test('default values', () {
      final card = FeedCardData(id: '1', type: FeedCardType.record, time: '14:00', content: 'test');
      expect(card.mode, CardMode.idle);
      expect(card.loading, false);
      expect(card.tags, isNull);
      expect(card.turns, isNull);
    });

    test('turns round-trip', () {
      final turns = [ConversationTurn(isUser: true, text: 'hi', time: '14:00')];
      final card = FeedCardData(id: '1', type: FeedCardType.record, time: '14:00', content: 'hi', turns: turns);
      expect(card.turns!.length, 1);
      expect(card.turns![0].text, 'hi');
    });
  });

  // ─── FeedCard widget render tests ───

  group('FeedCard rendering', () {
    testWidgets('idle card shows ask label', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: FeedCard(
              data: FeedCardData(id: '1', type: FeedCardType.record, time: '14:00', content: 'buy stock'),
            ),
          ),
        ),
      ));

      expect(find.text('buy stock'), findsOneWidget);
      expect(find.text('ask'), findsOneWidget);
    });

    testWidgets('chatting card shows end and turns', (tester) async {
      final turns = [
        ConversationTurn(isUser: true, text: 'weather?', time: '14:00'),
        ConversationTurn(isUser: false, text: 'sunny', time: '14:01'),
      ];
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: FeedCard(
              data: FeedCardData(
                id: '1', type: FeedCardType.record, time: '14:00',
                content: 'weather?', turns: turns, mode: CardMode.chatting,
              ),
            ),
          ),
        ),
      ));

      expect(find.text('weather?'), findsOneWidget);
      expect(find.text('sunny'), findsOneWidget);
      expect(find.text('end'), findsOneWidget);
    });

    testWidgets('ended card shows summary and tags', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: FeedCard(
              data: FeedCardData(
                id: '1', type: FeedCardType.record, time: '14:00',
                content: 'weather?', summary: 'chat about weather',
                tags: ['weather'], mode: CardMode.ended,
              ),
            ),
          ),
        ),
      ));

      expect(find.text('chat about weather'), findsOneWidget);
      expect(find.text('weather'), findsOneWidget);
      expect(find.text('ask'), findsOneWidget);
    });

    testWidgets('idle card with tags renders them', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: FeedCard(
              data: FeedCardData(
                id: '1', type: FeedCardType.record, time: '14:00',
                content: 'buy stock', tags: ['invest', 'tech'],
              ),
            ),
          ),
        ),
      ));

      expect(find.text('invest'), findsOneWidget);
      expect(find.text('tech'), findsOneWidget);
    });

    testWidgets('onAsk callback fires on tap', (tester) async {
      bool asked = false;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: FeedCard(
              data: FeedCardData(id: '1', type: FeedCardType.record, time: '14:00', content: 'hi'),
              onAsk: () => asked = true,
            ),
          ),
        ),
      ));

      await tester.tap(find.text('ask'));
      expect(asked, true);
    });

    testWidgets('onEnd callback fires on tap', (tester) async {
      bool ended = false;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: FeedCard(
              data: FeedCardData(
                id: '1', type: FeedCardType.record, time: '14:00',
                content: 'hi', mode: CardMode.chatting,
              ),
              onEnd: () => ended = true,
            ),
          ),
        ),
      ));

      await tester.tap(find.text('end'));
      expect(ended, true);
    });
  });

  // ─── App Launches ───

  testWidgets('App launches without crash', (tester) async {
    await tester.pumpWidget(const RootApp());
    expect(find.byType(MaterialApp), findsOneWidget);
  });
}
