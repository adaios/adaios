import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:adai_web/services/api_service.dart';

void main() {
  group('DTO JSON parsing', () {
    test('FeedResponse parses correctly', () {
      final json = jsonDecode('''
        {"entries": [], "totalToday": 0}
      ''');
      final feed = FeedResponse.fromJson(json);
      expect(feed.entries, isEmpty);
      expect(feed.totalToday, 0);
    });

    test('FeedResponse parses entries', () {
      final json = jsonDecode('''
        {
          "entries": [
            {"type": "record", "id": "r1", "title": "t", "content": "buy stock", "tags": ["invest"], "time": "14:30"}
          ],
          "totalToday": 2
        }
      ''');
      final feed = FeedResponse.fromJson(json);
      expect(feed.entries.length, 1);
      expect(feed.entries[0].content, 'buy stock');
      expect(feed.entries[0].tags, ['invest']);
      expect(feed.totalToday, 2);
    });

    test('FeedEntryResponse defaults', () {
      final json = jsonDecode('{"type": "record", "id": "r1", "content": "test", "time": "10:00"}');
      final entry = FeedEntryResponse.fromJson(json);
      expect(entry.title, '');
      expect(entry.tags, isEmpty);
      expect(entry.id, 'r1');
    });

    test('FeedEntryResponse market type has content', () {
      final json = jsonDecode(
          '{"type": "market", "id": "m1", "content": "上证指数 3200 +0.5%", "time": "15:00"}');
      final entry = FeedEntryResponse.fromJson(json);
      expect(entry.type, 'market');
      expect(entry.content, '上证指数 3200 +0.5%');
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

    test('AskMediaResponse parses answer + imageRecordId', () {
      final json = jsonDecode(
          '{"recordId": "qa1", "answer": "这是浦发银行，持仓约 1000 股。", "imageRecordId": "img1"}');
      final resp = AskMediaResponse.fromJson(json);
      expect(resp.recordId, 'qa1');
      expect(resp.answer, '这是浦发银行，持仓约 1000 股。');
      expect(resp.imageRecordId, 'img1');
    });

    test('AskMediaResponse empty answer defaults', () {
      final json = jsonDecode('{}');
      final resp = AskMediaResponse.fromJson(json);
      expect(resp.answer, '');
      expect(resp.imageRecordId, '');
    });

    test('EndConversationResponse parses', () {
      final json = jsonDecode('{"recordId": "r1", "summary": "done", "tags": ["chat"]}');
      final resp = EndConversationResponse.fromJson(json);
      expect(resp.recordId, 'r1');
      expect(resp.summary, 'done');
      expect(resp.tags, ['chat']);
    });
  });

  group('ApiException', () {
    test('carries statusCode and message', () {
      final e = ApiException(404, 'not found', '{"msg":"x"}');
      expect(e.statusCode, 404);
      expect(e.body, '{"msg":"x"}');
      expect(e.toString(), contains('404'));
    });
  });

  group('缓存参数感知', () {
    test('ApiService 实例化（缓存 Map 初始化）', () {
      final api = ApiService(userId: 'default');
      expect(api.userId, 'default');
      expect(api.baseUrl, isNotEmpty);
    });
  });
}
