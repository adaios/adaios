// ApiStore 映射单元测试 — DTO → 页面模型（注入 MockClient 的 ApiService）。

import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_admin/services/api_service.dart';
import 'package:adai_admin/services/data_api_store.dart';
import 'package:adai_admin/services/knowledge_api_store.dart';
import 'package:adai_admin/services/system_api_store.dart';

const _jsonHeaders = {'content-type': 'application/json; charset=utf-8'};

http.Response _json(Object body, [int status = 200]) =>
    http.Response(jsonEncode(body), status, headers: _jsonHeaders);

http.Response _error(String message) =>
    http.Response(jsonEncode({'error': message}), 400, headers: _jsonHeaders);

ApiService _api(MockClient client, {String userId = 'default'}) =>
    ApiService(client: client, userId: userId);

void main() {
  group('DataApiStore', () {
    test('loadRecords 从 Feed 映射为 ContentRecord（intent=question → 提问）', () async {
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/feed') {
          return _json({
            'entries': [
              {
                'type': 'record',
                'id': 'rec_001',
                'title': '标题',
                'content': '上证会突破吗',
                'tags': ['行情'],
                'time': '19:05',
                'intent': 'question',
                'summary': '摘要',
                'domain': 'life',
              },
              {
                'type': 'record',
                'id': 'rec_002',
                'title': '标题2',
                'content': 'v0.2.0 验收完成',
                'tags': ['ship'],
                'time': '21:30',
                'intent': 'log',
                'summary': '摘要',
                'domain': 'life',
              },
            ],
            'totalToday': 2,
          });
        }
        return http.Response('{}', 404);
      });
      final store = DataApiStore(api: _api(client), userId: 'default');
      final records = await store.loadRecords();

      expect(records, hasLength(2));
      // loadRecords 按时间倒序：21:30 的 statement 在前，19:05 的 question 在后
      expect(records.first.type, 'statement');
      expect(records.first.content, 'v0.2.0 验收完成');
      expect(records.last.type, 'question');
      expect(records.last.content, '上证会突破吗');
    });

    test('loadMemories 自动选最近日期并映射 kind/superseded', () async {
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/memory/dates') {
          return _json(['2026-07-30', '2026-08-01']);
        }
        if (request.url.path == '/api/v1/memory') {
          expect(request.url.queryParameters['date'], '2026-08-01');
          return _json([
            {
              'id': 'mem_001',
              'recordId': 'rec_001',
              'kind': 'insight',
              'summary': '行情条需要独立渲染',
              'tags': [],
              'sentiment': 'neutral',
              'createdAt': '2026-08-01T20:00:00',
              'superseded': true,
            },
          ]);
        }
        return http.Response('{}', 404);
      });
      final store = DataApiStore(api: _api(client), userId: 'default');
      final memories = await store.loadMemories();

      expect(memories.single.kind, 'insight');
      expect(memories.single.content, '行情条需要独立渲染');
      expect(memories.single.superseded, isTrue);
    });

    test('loadTasks 透传后端 P0/DONE → P0/done', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/project/tasks');
        return _json([
          {
            'id': 'task_001',
            'title': '接真实数据',
            'description': '',
            'status': 'DONE',
            'priority': 'P0',
            'tags': [],
            'createdAt': '2026-08-01',
            'updatedAt': '2026-08-02',
          },
        ]);
      });
      final store = DataApiStore(api: _api(client), userId: 'default');
      final task = (await store.loadTasks()).single;

      expect(task.done, isTrue);
      expect(task.priority, 'P0'); // #140：优先级透传后端 P0-P3，不再 high/medium/low 映射
    });

    test('loadPositions 映射 Position', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/trading/positions');
        return _json([
          {
            'symbol': '510300',
            'name': '沪深300ETF',
            'quantity': 10000,
            'avgCost': 3.85,
            'currentPrice': 4.02,
            'marketValue': 40200.0,
            'pnl': 1700.0,
            'pnlPercent': 4.42,
          },
        ]);
      });
      final store = DataApiStore(api: _api(client), userId: 'default');
      final position = (await store.loadPositions()).single;

      expect(position.symbol, '510300');
      expect(position.quantity, 10000);
      expect(position.avgCost, 3.85);
      expect(position.currentPrice, 4.02);
    });

    test('loadFiles 映射 /admin/files 条目为 TreeNode', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/admin/files');
        return _json([
          {'name': 'records', 'path': 'records', 'isDir': true},
          {'name': 'profile.md', 'path': 'identity/profile.md', 'isDir': false, 'size': 2048},
        ]);
      });
      final store = DataApiStore(api: _api(client), userId: 'default');
      final nodes = await store.loadFiles('');

      expect(nodes.first.isDir, isTrue);
      expect(nodes.first.path, 'records');
      expect(nodes.last.isDir, isFalse);
      expect(nodes.last.meta, '2.0 KB');
    });
  });

  group('SystemApiStore', () {
    test('loadFeed 映射 FeedItem（title/content → title/subtitle）', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/feed');
        return _json({
          'entries': [
            {
              'type': 'market',
              'id': 'market_001',
              'title': '大盘行情',
              'content': '沪深300 4021.34 +0.85%',
              'tags': ['行情'],
              'time': '10:30',
            },
          ],
          'totalToday': 0,
        });
      });
      final store = SystemApiStore(api: _api(client), userId: 'default');
      final feed = await store.loadFeed();

      expect(feed.single.type, 'market');
      expect(feed.single.title, '大盘行情');
      expect(feed.single.subtitle, '沪深300 4021.34 +0.85%');
    });

    test('loadReviews 映射日期列表 + generated 状态', () async {
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/trading/reviews') {
          return _json(['2026-07-31', '2026-08-01']);
        }
        if (request.url.path == '/api/v1/trading/review') {
          final date = request.url.queryParameters['date'];
          // 07-31 已生成，08-01 未生成
          return date == '2026-07-31'
              ? _json({'date': date, 'content': '# 复盘内容'})
              : http.Response('', 404);
        }
        return http.Response('{}', 404);
      });
      final store = SystemApiStore(api: _api(client), userId: 'default');
      final reviews = await store.loadReviews();

      expect(reviews, hasLength(2));
      final generated = reviews.firstWhere((r) => r.generated);
      expect(generated.date, DateTime(2026, 7, 31));
      final pending = reviews.firstWhere((r) => !r.generated);
      expect(pending.date, DateTime(2026, 8, 1));
    });

    test('rebuildMemory 映射为 MaintenanceResult（P-be-01：/admin/** + userId 查询参数）', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/admin/memory/rebuild');
        expect(request.url.queryParameters['userId'], 'default');
        expect(request.headers.containsKey('X-User-Id'), isFalse); // 系统级请求
        return _json({'success': 3, 'failed': 1, 'total': 4, 'errors': []});
      });
      final store = SystemApiStore(api: _api(client), userId: 'default');
      final result = await store.rebuildMemory();

      expect(result.success, isFalse);
      expect(result.message, contains('成功 3 / 失败 1'));
    });

    test('importTdxPackage 上传 .zip → 摘要消息（MD17，multipart /admin/market/tdx-import）', () async {
      final client = MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/api/v1/admin/market/tdx-import');
        expect(request.headers['content-type'], startsWith('multipart/form-data'));
        expect(request.body, contains('sh_sz.zip'));
        return _json({
          'status': 'ok',
          'imported': 9367,
          'skipped': 2,
          'failed': <String>[],
          'markets': {'sh': 4922, 'sz': 4445},
          'dayFilesAfter': 9367,
        });
      });
      final store = SystemApiStore(api: _api(client), userId: 'default');
      final result =
          await store.importTdxPackage(Uint8List.fromList([1, 2, 3]), 'sh_sz.zip');

      expect(result.success, isTrue);
      expect(result.message, contains('成功 9367 个 .day'));
      expect(result.message, contains('沪 4922 · 深 4445'));
    });

    test('importTdxPackage 部分失败 → 摘要含失败清单（MD17）', () async {
      final client = MockClient((request) async {
        return _json({
          'status': 'ok',
          'imported': 9366,
          'skipped': 0,
          'failed': ['sz000001.day（无法解析出任何 K 线）'],
          'markets': {'sh': 4922, 'sz': 4444},
          'dayFilesAfter': 9366,
        });
      });
      final store = SystemApiStore(api: _api(client), userId: 'default');
      final result =
          await store.importTdxPackage(Uint8List.fromList([1]), 'bad.zip');

      expect(result.success, isFalse);
      expect(result.message, contains('失败 1 个'));
      // P2-10：失败明细完整透出到 failures（展示层对话框复查，消息体不再塞截断清单）
      expect(result.failures, ['sz000001.day（无法解析出任何 K 线）']);
    });

    test('importTdxPackage 后端拒绝 → 失败消息透出人话（MD17）', () async {
      final client =
          MockClient((request) async => _error('数据包内没有任何 .day 文件（需含 sh/sz + 6 位代码的 .day）'));
      final store = SystemApiStore(api: _api(client), userId: 'default');
      final result =
          await store.importTdxPackage(Uint8List.fromList([1]), 'empty.zip');

      expect(result.success, isFalse);
      expect(result.message, contains('没有任何 .day'));
    });
  });

  group('KnowledgeApiStore', () {
    test('loadTerms 从 rules.md 解析真实规则', () async {
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/admin/knowledge/content') {
          return _json({
            'path': 'trading-engine/knowledge/context/rules.md',
            'size': 512,
            'content': '**R1 活跃市值4%启动信号**\n> 活跃市值单日涨幅≥4% → 新波段开始\n\n**R96 四不原则**\n> 不追高不抄底',
          });
        }
        return http.Response('{}', 404);
      });
      final store = KnowledgeApiStore(api: _api(client));
      final terms = await store.loadTerms();
      final rules = terms.where((t) => t.category == '规则').toList();

      expect(rules, isNotEmpty);
      expect(rules.first.name, 'R1');
      expect(rules.first.definition, contains('活跃市值4%启动信号'));
    });

    test('loadOsDir 映射 /admin/knowledge 条目', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/admin/knowledge');
        expect(request.url.queryParameters['domain'], 'trading-engine');
        return _json([
          {'name': 'knowledge', 'path': 'trading-engine/knowledge', 'isDir': true},
          {
            'name': 'rules.md',
            'path': 'trading-engine/knowledge/context/rules.md',
            'isDir': false,
            'size': 512,
          },
        ]);
      });
      final store = KnowledgeApiStore(api: _api(client));
      final nodes = await store.loadOsDir(domain: 'trading-engine', path: 'trading-engine');

      expect(nodes, hasLength(2));
      expect(nodes.first.isDir, isTrue);
      expect(nodes.last.path, 'trading-engine/knowledge/context/rules.md');
    });
  });
}
