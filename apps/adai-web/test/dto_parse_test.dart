import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_web/services/api_service.dart';

/// UTF-8 JSON 响应：MockClient 默认 Latin-1 编码 body，中文会炸，必须显式 charset=utf-8。
http.Response _json(Object body) => http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

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
        [{"symbol":"000725","name":"京东方A","buyPoint":"B1","score":87,
          "signals":["回调 52% ≥ 50%","3 日量 0.6×5 日量 ≤ 0.7","KDJ.J 12.3 < 20"]}]
      ''');
      final hits = (json as List).map((e) => BuyPointDto.fromJson(e)).toList();
      expect(hits.length, 1);
      expect(hits[0].symbol, '000725');
      expect(hits[0].name, '京东方A');
      expect(hits[0].buyPoint, 'B1');
      expect(hits[0].score, 87);
      expect(hits[0].signals.length, 3);
      expect(hits[0].signals[0], contains('回调'));
      expect(hits[0].caseMatches, isEmpty);
    });

    test('BuyPointDto parses case hit with caseMatches (P2-案例2)', () {
      final json = jsonDecode('''
        [{"symbol":"000725","name":"京东方A","buyPoint":"case","score":0,
          "signals":[],
          "caseMatches":[
            {"caseId":"2026-08-03_000725","buyDate":"2026-08-03","buyType":"B1","similarityPercent":92.0},
            {"caseId":"2026-07-20_000725","buyDate":"2026-07-20","buyType":"B2","similarityPercent":81.5}]}]
      ''');
      final hits = (json as List).map((e) => BuyPointDto.fromJson(e)).toList();
      expect(hits.length, 1);
      expect(hits[0].buyPoint, 'case');
      expect(hits[0].caseMatches.length, 2);
      expect(hits[0].caseMatches[0].caseId, '2026-08-03_000725');
      expect(hits[0].caseMatches[0].buyType, 'B1');
      expect(hits[0].caseMatches[0].similarityPercent, 92.0);
      expect(hits[0].caseMatches[1].similarityPercent, 81.5);
    });

    test('BuyPointDto empty defaults', () {
      final hit = BuyPointDto.fromJson({});
      expect(hit.symbol, '');
      expect(hit.buyPoint, '');
      expect(hit.score, 0);
      expect(hit.signals, isEmpty);
      expect(hit.caseMatches, isEmpty);
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

    test('SoldTradeDto provenance：flow 显式解析，缺省 import（RFC 20260909 批1）', () {
      final flow = SoldTradeDto.fromJson({
        'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
        'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0,
        'verdict': '盈利了结', 'psychology': '', 'provenance': 'flow',
      });
      expect(flow.provenance, 'flow');
      // 老行无 provenance 字段 → 读侧兼容视为 import
      final legacy = SoldTradeDto.fromJson({
        'symbol': '000725', 'name': '京东方A', 'sellDate': '2026-08-11',
        'verdict': '盈利了结',
      });
      expect(legacy.provenance, 'import');
    });

    test('PendingClearanceDto parses fields + nullable defaults', () {
      final p = PendingClearanceDto.fromJson({
        'symbol': '600519', 'name': '贵州茅台',
        'sellDate': '2026-09-08', 'reason': '买入基线在同步窗口外',
      });
      expect(p.symbol, '600519');
      expect(p.name, '贵州茅台');
      expect(p.sellDate, '2026-09-08');
      expect(p.reason, contains('窗口外'));
      final empty = PendingClearanceDto.fromJson({});
      expect(empty.symbol, '');
      expect(empty.name, '');
      expect(empty.sellDate, isNull);
      expect(empty.reason, isNull);
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

    test('AccountSnapshotDto empty defaults totalPnl null（本金未设不给误导数值，P2-交易31）', () {
      final a = AccountSnapshotDto.fromJson({});
      expect(a.assets, 0);
      expect(a.principal, 0);
      expect(a.totalPnl, isNull, reason: 'principal=0 → 总盈亏 null（UI 显示 — 引导设本金，U32）');
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

  group('ApiService.getSold 双轨对象契约（RFC 20260909 批1）', () {
    test('解析对象响应：sold + pendingClearances 两键分开', () async {
      final api = ApiService(baseUrl: 'http://test', client: MockClient((request) async {
        if (request.url.path != '/api/v1/trading/sold') {
          return http.Response('not found', 404);
        }
        return _json({
          'sold': [
            {'symbol': '600519', 'name': '贵州茅台', 'buyDate': '2026-08-01', 'sellDate': '2026-08-11',
             'holdDays': 10, 'tradeCount': '1+1', 'holdPnlPct': 5.0, 'verdict': '盈利了结',
             'psychology': '', 'provenance': 'flow'},
            {'symbol': '000725', 'name': '京东方A', 'buyDate': '2026-07-01', 'sellDate': '2026-07-05',
             'holdDays': 4, 'tradeCount': '1+1', 'holdPnlPct': -8.0, 'verdict': 'R53',
             'psychology': '追高后恐慌割肉'},
          ],
          'pendingClearances': [
            {'symbol': '601066', 'name': '中信建投', 'sellDate': '2026-09-09',
             'reason': '买入基线在同步窗口外'},
          ],
        });
      }));
      final ov = await api.getSold();
      expect(ov.sold.length, 2);
      expect(ov.sold[0].provenance, 'flow');
      expect(ov.sold[1].provenance, 'import', reason: '无 provenance 字段老行缺省 import');
      expect(ov.pending.length, 1);
      expect(ov.pending[0].name, '中信建投');
      expect(ov.pending[0].symbol, '601066');
      expect(ov.pending[0].sellDate, '2026-09-09');
    });

    test('过渡兼容：后端仍是裸数组时 sold=数组、pending 空', () async {
      final api = ApiService(baseUrl: 'http://test', client: MockClient((request) async {
        return _json([
          {'symbol': '600519', 'name': '贵州茅台', 'sellDate': '2026-08-11', 'verdict': '盈利了结'},
        ]);
      }));
      final ov = await api.getSold();
      expect(ov.sold.length, 1);
      expect(ov.sold[0].symbol, '600519');
      expect(ov.pending, isEmpty);
    });

    test('字段缺失/类型异常兜底：无 sold/pendingClearances 键不抛', () async {
      final api = ApiService(baseUrl: 'http://test', client: MockClient((request) async {
        return _json(<String, dynamic>{});
      }));
      final ov = await api.getSold();
      expect(ov.sold, isEmpty);
      expect(ov.pending, isEmpty);
    });
  });
}
