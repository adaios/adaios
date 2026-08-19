// ApiService DTO 解析单元测试 — 注入 MockClient，验证 snake_case JSON 解析与错误映射。

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_admin/services/api_exception.dart';
import 'package:adai_admin/services/api_service.dart';

const _jsonHeaders = {'content-type': 'application/json; charset=utf-8'};

http.Response _json(Object body, [int status = 200]) =>
    http.Response(jsonEncode(body), status, headers: _jsonHeaders);

void main() {
  group('账号', () {
    test('GET /accounts 解析账号列表（createdAt 为 ISO 日期字符串）', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/accounts');
        expect(request.headers.containsKey('X-User-Id'), isFalse);
        return _json([
          {
            'userId': 'adai',
            'role': 'admin',
            'enabled': true,
            'createdAt': '2026-08-02',
          },
          {
            'userId': 'bob',
            'role': 'user',
            'enabled': false,
            'createdAt': '2026-07-20',
          },
        ]);
      });
      final api = ApiService(client: client);
      final accounts = await api.getAccounts();

      expect(accounts, hasLength(2));
      expect(accounts.first.userId, 'adai');
      expect(accounts.first.role, 'admin');
      expect(accounts.first.enabled, isTrue);
      expect(accounts.first.createdAt, DateTime(2026, 8, 2));
      expect(accounts.last.enabled, isFalse);
    });

    test('400 错误解析 error 字段为 ApiException message', () async {
      final client = MockClient((request) async =>
          _json({'error': '账号已存在: alice'}, 400));
      final api = ApiService(client: client);

      await expectLater(
        api.createAccount(userId: 'alice', role: 'user'),
        throwsA(isA<ApiException>()
            .having((e) => e.message, 'message', '账号已存在: alice')
            .having((e) => e.statusCode, 'statusCode', 400)),
      );
    });
  });

  group('Feed / 记录', () {
    test('GET /feed 解析 entries + totalToday', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/feed');
        expect(request.headers['X-User-Id'], 'default');
        return _json({
          'entries': [
            {
              'type': 'record',
              'id': 'rec_001',
              'title': '标题',
              'content': '内容',
              'tags': ['前端'],
              'time': '21:30',
              'intent': 'log',
              'summary': '摘要',
              'domain': 'life',
            },
          ],
          'totalToday': 1,
        });
      });
      final api = ApiService(client: client);
      final feed = await api.getFeed();

      expect(feed.totalToday, 1);
      expect(feed.entries.single.type, 'record');
      expect(feed.entries.single.id, 'rec_001');
      expect(feed.entries.single.time, '21:30');
      expect(feed.entries.single.tags, ['前端']);
    });
  });

  group('记忆', () {
    test('GET /memory 解析全字段', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/memory');
        return _json([
          {
            'id': 'mem_001',
            'recordId': 'rec_001',
            'kind': 'insight',
            'summary': '洞察内容',
            'tags': ['行情'],
            'sentiment': 'positive',
            'actionable': true,
            'suggestion': '关注回撤',
            'createdAt': '2026-08-01T20:00:00',
            'topic': 't-1',
            'superseded': true,
            'evolvedTo': 'mem_002',
            'doneAt': null,
            'lastConfirmed': null,
          },
        ]);
      });
      final api = ApiService(client: client);
      final memory = (await api.getMemory(date: '2026-08-01')).single;

      expect(memory.id, 'mem_001');
      expect(memory.kind, 'insight');
      expect(memory.summary, '洞察内容');
      expect(memory.superseded, isTrue);
      expect(memory.actionable, isTrue);
      expect(memory.evolvedTo, 'mem_002');
      expect(memory.createdAt, '2026-08-01T20:00:00');
    });
  });

  group('档案', () {
    test('GET /identity 解析 rules 为 Map（页面映射时取 values）', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/identity');
        return _json({
          'name': 'adai',
          'preferences': {'语言': '中文'},
          'rules': {'rule-1': '新功能先写 RFC'},
          'tags': ['单人开发'],
        });
      });
      final api = ApiService(client: client);
      final identity = await api.getIdentity();

      expect(identity.name, 'adai');
      expect(identity.preferences['语言'], '中文');
      expect(identity.rules['rule-1'], '新功能先写 RFC');
      expect(identity.tags, ['单人开发']);
    });
  });

  group('持仓 / 复盘 / 冲突', () {
    test('GET /trading/positions 解析数量为 int、价格转 double', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/trading/positions');
        return _json([
          {
            'symbol': '510300',
            'name': '沪深300ETF',
            'quantity': 10000,
            'avgCost': 3.85,
            'currentPrice': 4.02,
            'lastUpdated': '2026-08-02T10:00:00',
            'marketValue': 40200.0,
            'pnl': 1700.0,
            'pnlPercent': 4.42,
          },
        ]);
      });
      final api = ApiService(client: client);
      final p = (await api.getPositions()).single;

      expect(p.symbol, '510300');
      expect(p.quantity, 10000);
      expect(p.avgCost, 3.85);
      expect(p.currentPrice, 4.02);
      expect(p.pnlPercent, 4.42);
    });

    test('GET /admin/trading/knowledge/conflicts 解析 rule/description/category（P-be-01：/admin/** + userId 查询参数）', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/admin/trading/knowledge/conflicts');
        expect(request.url.queryParameters['userId'], 'default');
        expect(request.headers.containsKey('X-User-Id'), isFalse); // 系统级请求
        return _json({
          'conflicts': [
            {'rule': 'R96 四不原则', 'description': '当前仅持 1 个标的', 'category': '仓位'},
          ],
        });
      });
      final api = ApiService(client: client);
      final conflicts = (await api.getConflicts()).conflicts;

      expect(conflicts.single.rule, 'R96 四不原则');
      expect(conflicts.single.description, '当前仅持 1 个标的');
      expect(conflicts.single.category, '仓位');
    });

    test('GET /trading/review 404 返回 null', () async {
      final client = MockClient((request) async => http.Response('', 404));
      final api = ApiService(client: client);
      expect(await api.getReview('2026-08-01'), isNull);
    });
  });

  group('任务', () {
    test('GET /project/tasks 解析 status/priority', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/project/tasks');
        return _json([
          {
            'id': 'task_001',
            'title': '接真实数据',
            'description': '',
            'status': 'DONE',
            'priority': 'P0',
            'tags': ['admin'],
            'rfcRef': null,
            'createdAt': '2026-08-01',
            'updatedAt': '2026-08-02',
          },
        ]);
      });
      final api = ApiService(client: client);
      final task = (await api.getTasks()).single;

      expect(task.id, 'task_001');
      expect(task.isDone, isTrue);
      expect(task.priority, 'P0');
      expect(task.status, 'DONE');
    });
  });

  group('管理端文件 / 知识', () {
    test('GET /admin/files 解析 isDir/size', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/admin/files');
        expect(request.headers.containsKey('X-User-Id'), isFalse);
        return _json([
          {'name': 'records', 'path': 'records', 'isDir': true},
          {'name': 'profile.md', 'path': 'identity/profile.md', 'isDir': false, 'size': 2048},
        ]);
      });
      final api = ApiService(client: client);
      final entries = await api.listFiles();

      expect(entries, hasLength(2));
      expect(entries.first.isDir, isTrue);
      expect(entries.last.isDir, isFalse);
      expect(entries.last.size, 2048);
    });

    test('GET /admin/knowledge/content 解析文件内容', () async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/admin/knowledge/content');
        return _json({
          'path': 'trading-engine/knowledge/context/rules.md',
          'size': 512,
          'content': '**R1 活跃市值4%启动信号**',
        });
      });
      final api = ApiService(client: client);
      final content = await api.getKnowledgeContent('trading-engine/knowledge/context/rules.md');

      expect(content.path, 'trading-engine/knowledge/context/rules.md');
      expect(content.content, contains('R1'));
    });
  });
}
