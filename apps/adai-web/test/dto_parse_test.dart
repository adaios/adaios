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

    test('BuyPointDto parses B1 hit with signals', () {
      final json = jsonDecode('''
        [{"symbol":"000725","name":"京东方A","buyPoint":"B1","score":0.8,
          "signals":["回调 52% ≥ 50%","3 日量 0.6×5 日量 ≤ 0.7","KDJ.J 12.3 < 20"]}]
      ''');
      final hits = (json as List).map((e) => BuyPointDto.fromJson(e)).toList();
      expect(hits.length, 1);
      expect(hits[0].symbol, '000725');
      expect(hits[0].name, '京东方A');
      expect(hits[0].buyPoint, 'B1');
      expect(hits[0].score, 0.8);
      expect(hits[0].signals.length, 3);
      expect(hits[0].signals[0], contains('回调'));
    });

    test('BuyPointDto empty defaults', () {
      final hit = BuyPointDto.fromJson({});
      expect(hit.symbol, '');
      expect(hit.buyPoint, '');
      expect(hit.score, 0);
      expect(hit.signals, isEmpty);
    });

    test('SoldScoreDto parses three dimensions', () {
      final json = jsonDecode('''
        [{"symbol":"600519","name":"贵州茅台","buyPointScore":88,"buyPointSignal":"B1",
          "buyPointExplain":"回调 52%","executionScore":90,"executionExplain":"盈利了结",
          "totalScore":89.0,"verdict":"盈利了结"}]
      ''');
      final s = (json as List).map((e) => SoldScoreDto.fromJson(e)).first;
      expect(s.symbol, '600519');
      expect(s.buyPointScore, 88);
      expect(s.executionScore, 90);
      expect(s.totalScore, 89.0);
    });

    test('SoldScoreDto null scores default', () {
      final s = SoldScoreDto.fromJson({'symbol': '600519'});
      expect(s.buyPointScore, isNull);
      expect(s.totalScore, isNull);
    });

    test('SoldTradeDto parses verdict + psychology', () {
      final t = SoldTradeDto.fromJson({
        'symbol': '000725', 'name': '京东方A', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
        'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0,
        'verdict': '盈利了结', 'psychology': '追高后恐慌',
      });
      expect(t.symbol, '000725');
      expect(t.holdDays, 10);
      expect(t.holdPnlPct, 5.0);
      expect(t.verdict, contains('盈利'));
      expect(t.psychology, '追高后恐慌');
    });

    test('WatchlistItemDto parses fields', () {
      final w = WatchlistItemDto.fromJson({
        'symbol': '000725', 'name': '京东方A', 'industry': '面板', 'industry2': '',
        'longForm': 6, 'midForm': 8, 'shortForm': 1, 'signal': 'KDJ死叉', 'addedAt': '2026-08-16',
      });
      expect(w.symbol, '000725');
      expect(w.longForm, 6);
      expect(w.signal, contains('KDJ'));
    });

    test('AccountSnapshotDto totalPnl = assets - principal', () {
      final a = AccountSnapshotDto.fromJson({
        'assets': 110504.88, 'cash': 292.88, 'available': 292.88, 'withdrawable': 292.88,
        'marketValue': 110212.0, 'pnl': 15235.55, 'todayPnl': 0.0, 'principal': 150000.0,
      });
      expect(a.assets, 110504.88);
      expect(a.principal, 150000.0);
      expect(a.totalPnl, closeTo(-39495.12, 0.01));
    });

    test('AccountSnapshotDto empty defaults totalPnl 0', () {
      final a = AccountSnapshotDto.fromJson({});
      expect(a.assets, 0);
      expect(a.totalPnl, 0);
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
