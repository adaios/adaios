import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/pages/memory_page.dart';
import 'package:adai_web/pages/timeline_page.dart';
import 'package:adai_web/pages/account_select_page.dart';
import 'package:adai_web/desktop_shell.dart';

/// UTF-8 JSON 响应：MockClient 默认 Latin-1 编码 body，中文会炸，必须显式 charset=utf-8。
http.Response _json(Object body) => http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

/// #101：构建一个返回分页 Feed 的 MockClient。
/// 总 8 条核心记录（page0=3 / page1=3 / page2=2），totalToday=8。
MockClient _pagedFeedMock() {
  return MockClient((request) async {
    final path = request.url.path;
    if (path == '/api/v1/brief') {
      return _json({'content': '今日概览'});
    }
    if (path == '/api/v1/feed') {
      final page = int.parse(request.url.queryParameters['page'] ?? '0');
      List<Map<String, dynamic>> entries = [];
      if (page == 0) {
        entries = [for (var i = 0; i < 3; i++) _feedEntry('r$i', '记录 $i')];
      } else if (page == 1) {
        entries = [for (var i = 3; i < 6; i++) _feedEntry('r$i', '记录 $i')];
      } else if (page == 2) {
        entries = [for (var i = 6; i < 8; i++) _feedEntry('r$i', '记录 $i')];
      }
      return _json({'entries': entries, 'totalToday': 8});
    }
    if (path == '/api/v1/tags') {
      return _json({'tags': [], 'total': 0, 'updatedAt': ''});
    }
    if (path == '/api/v1/project/tasks/stats') {
      return _json({'total': 0, 'todo': 0, 'doing': 0, 'done': 0, 'cancelled': 0});
    }
    return http.Response('not found', 404);
  });
}

Map<String, dynamic> _feedEntry(String id, String content) => {
      'type': 'record',
      'id': id,
      'title': content,
      'content': content,
      'tags': <String>[],
      'time': '10:00',
    };

void main() {
  group('ApiService #103 force 参数 + #229 缓存清理', () {
    test('getTimeline 参数感知缓存 + force 绕过', () async {
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        return _json([]);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.getTimeline(limit: 50);
      await api.getTimeline(limit: 50); // 命中缓存
      expect(calls, 1);
      await api.getTimeline(limit: 50, force: true); // #103 强制重拉
      expect(calls, 2);
    });

    test('getMemory 参数感知缓存 + force 绕过', () async {
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        return _json([]);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.getMemory(date: '2026-08-12');
      await api.getMemory(date: '2026-08-12'); // 命中缓存
      expect(calls, 1);
      await api.getMemory(date: '2026-08-12', force: true); // #103 强制重拉
      expect(calls, 2);
    });

    test('askMedia 清空标签云缓存 (#229)', () async {
      var tagCalls = 0;
      final client = MockClient((request) async {
        if (request.url.path.endsWith('/tags')) {
          tagCalls++;
          return _json({'tags': [], 'total': 0, 'updatedAt': ''});
        }
        if (request.url.path.endsWith('/ask')) {
          return _json({'recordId': 'qa1', 'answer': 'A', 'imageRecordId': 'img1'});
        }
        return _json({});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.getTags();
      await api.askMedia(imageRecordId: 'img1', question: 'q');
      // image_qa 带 tags → _tagsCache 已清，下一次 getTags 应重新请求
      await api.getTags();
      expect(tagCalls, 2);
    });

    test('askBatch 请求 /ask-batch 带 imageRecordIds/question + 清标签云缓存 (S-1)', () async {
      var tagCalls = 0;
      List<String>? sentIds;
      String? sentQuestion;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path.endsWith('/tags')) {
          tagCalls++;
          return _json({'tags': [], 'total': 0, 'updatedAt': ''});
        }
        if (path.endsWith('/ask-batch')) {
          final body = jsonDecode(request.body) as Map<String, dynamic>;
          sentIds = (body['imageRecordIds'] as List).cast<String>();
          sentQuestion = body['question'] as String;
          return _json({
            'intent': 'question', 'answer': '左图是持仓，右图是走势。',
            'recordId': 'qa1', 'imageRecordIds': sentIds,
          });
        }
        return _json({});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await api.getTags();
      final qa = await api.askBatch(
          imageRecordIds: ['rec_1', 'rec_2'], question: '这两张分别是什么？');
      await api.getTags();
      // 请求契约
      expect(sentIds, ['rec_1', 'rec_2']);
      expect(sentQuestion, '这两张分别是什么？');
      // 响应解析
      expect(qa.intent, 'question');
      expect(qa.answer, '左图是持仓，右图是走势。');
      expect(qa.recordId, 'qa1');
      expect(qa.imageRecordIds, ['rec_1', 'rec_2']);
      // image_qa 带 tags → 标签云缓存已清，下一次 getTags 应重新请求
      expect(tagCalls, 2);
    });

    test('AskBatchResponse 解析：log 陈述 intent 时 answer 空 + 缺字段兜底 (S-1)', () async {
      final client = MockClient((request) async {
        if (request.url.path.endsWith('/ask-batch')) {
          return _json({'intent': 'log'});
        }
        return _json({});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      final qa = await api.askBatch(
          imageRecordIds: ['rec_1'], question: '这是今天的持仓截图');
      expect(qa.intent, 'log');
      expect(qa.answer, '');
      expect(qa.recordId, '');
      expect(qa.imageRecordIds, isEmpty);
    });
  });

  group('Feed #101 加载更早分页', () {
    testWidgets('首屏不足 totalToday → 底部「加载更早」；点击追加更早记录', (tester) async {
      // 高视口：8 条核心记录 + 底部 banner 全部可见（懒加载 ListView 只 build 可视项）
      await tester.binding.setSurfaceSize(const Size(1200, 2000));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      final api = ApiService(baseUrl: 'http://test', client: _pagedFeedMock());
      // 输入栏 TextField 需要 Material 祖先 → 包一层 Scaffold
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
      await tester.pumpAndSettle();

      // 首屏 3 条 + 底部加载更早入口
      expect(find.text('记录 0'), findsOneWidget);
      expect(find.text('今日 8 条记录'), findsOneWidget);
      expect(find.text('加载更早'), findsOneWidget);

      // 点击 → 追加 page1 的 3 条（记录 3/4/5），仍有更多（6 < 8）
      await tester.tap(find.text('加载更早'));
      await tester.pumpAndSettle();
      expect(find.text('记录 3'), findsOneWidget);
      expect(find.text('记录 5'), findsOneWidget);
      expect(find.text('加载更早'), findsOneWidget);

      // 再点 → 追加 page2 的 2 条（记录 6/7），8 条拉齐 → 入口消失
      await tester.tap(find.text('加载更早'));
      await tester.pumpAndSettle();
      expect(find.text('记录 7'), findsOneWidget);
      expect(find.text('加载更早'), findsNothing);
    });
  });

  group('Feed #234 附加条目不计入分页终止', () {
    testWidgets('page 0 含 action/market 附加条目时「加载更早」仍显示，点击可加载更早核心记录', (tester) async {
      await tester.binding.setSurfaceSize(const Size(1200, 2000));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      final api = ApiService(baseUrl: 'http://test', client: MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/brief') return _json({'content': '今日概览'});
        if (path == '/api/v1/feed') {
          final page = int.parse(request.url.queryParameters['page'] ?? '0');
          if (page == 0) {
            // 2 个附加条目（action/market，仅 page 0 附加）+ 3 条核心记录；totalToday 只计核心 = 4
            return _json({
              'entries': [
                {'type': 'action', 'id': 'a1', 'title': '交房租', 'content': '交房租', 'tags': <String>[], 'time': '09:00'},
                {'type': 'market', 'id': 'm1', 'title': '行情', 'content': '上证指数 3200 +0.5%', 'tags': <String>[], 'time': '09:30'},
                _feedEntry('r0', '记录 0'),
                _feedEntry('r1', '记录 1'),
                _feedEntry('r2', '记录 2'),
              ],
              'totalToday': 4,
            });
          }
          // page 1：剩余 1 条核心记录
          return _json({'entries': [_feedEntry('r3', '记录 3')], 'totalToday': 4});
        }
        if (path == '/api/v1/tags') return _json({'tags': [], 'total': 0, 'updatedAt': ''});
        if (path == '/api/v1/project/tasks/stats') return _json({'total': 0, 'todo': 0, 'doing': 0, 'done': 0, 'cancelled': 0});
        return http.Response('not found', 404);
      }));

      await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
      await tester.pumpAndSettle();

      // 5 张卡（2 附加 + 3 核心），核心 3 < totalToday 4 → 「加载更早」应显示
      // （旧逻辑 _cards.length 5 >= 4 误判无更多，最旧核心记录永不可达）
      expect(find.text('记录 2'), findsOneWidget);
      expect(find.text('加载更早'), findsOneWidget);

      // 点击 → 追加 page1 核心记录 r3，4 条核心拉齐 → 入口消失
      await tester.tap(find.text('加载更早'));
      await tester.pumpAndSettle();
      expect(find.text('记录 3'), findsOneWidget);
      expect(find.text('加载更早'), findsNothing);
    });
  });

  group('Memory #236 刷新保留当前选中日期', () {
    testWidgets('浏览旧记忆刷新不跳回最新日期', (tester) async {
      final api = ApiService(baseUrl: 'http://test', client: MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/memory/dates') {
          return _json(['2026-08-10', '2026-08-11', '2026-08-12']);
        }
        if (path == '/api/v1/memory') {
          final date = request.url.queryParameters['date'] ?? '';
          return _json([
            {
              'id': 'm$date', 'recordId': 'r$date', 'kind': 'insight', 'summary': '记忆 $date',
              'tags': <String>[], 'sentiment': 'neutral', 'createdAt': date,
            }
          ]);
        }
        return _json({});
      }));

      await tester.pumpWidget(MaterialApp(home: MemoryPage(api: api)));
      await tester.pumpAndSettle();

      // 初始选中最新日期（dates.first = 2026-08-10）
      expect(find.text('记忆 2026-08-10'), findsOneWidget);

      // 选旧日期 08-11
      await tester.tap(find.text('08-11'));
      await tester.pumpAndSettle();
      expect(find.text('记忆 2026-08-11'), findsOneWidget);

      // 刷新 → 保留 08-11，不跳回 dates.first（#236）
      await tester.tap(find.byIcon(Icons.refresh));
      await tester.pumpAndSettle();
      expect(find.text('记忆 2026-08-11'), findsOneWidget);
    });
  });

  group('Timeline #103 保活刷新入口', () {
    testWidgets('刷新按钮存在，点击强制重拉（绕过缓存）', (tester) async {
      var timelineCalls = 0;
      final client = MockClient((request) async {
        if (request.url.path == '/api/v1/timeline') {
          timelineCalls++;
          return _json([
            {'id': 't1', 'type': 'record', 'title': '记录', 'tags': <String>[], 'dateTime': '2026-08-12T10:00:00'}
          ]);
        }
        return _json({});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await tester.pumpWidget(MaterialApp(home: TimelinePage(api: api)));
      await tester.pump();
      await tester.pump();

      expect(timelineCalls, 1);

      // 同参数无 force 走缓存，不发新请求
      await api.getTimeline(limit: 500);
      expect(timelineCalls, 1);

      // 点页头刷新按钮 → force 重拉
      await tester.tap(find.byIcon(Icons.refresh));
      await tester.pump();
      await tester.pump();
      expect(timelineCalls, 2);
    });
  });

  group('Memory #103 保活刷新入口', () {
    testWidgets('刷新按钮存在，点击强制重拉记忆列表', (tester) async {
      var memoryCalls = 0;
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/memory/dates') {
          return _json(['2026-08-12']);
        }
        if (path == '/api/v1/memory') {
          memoryCalls++;
          return _json([
            {
              'id': 'm1', 'recordId': 'r1', 'kind': 'insight', 'summary': '记忆',
              'tags': <String>[], 'sentiment': 'neutral', 'createdAt': '2026-08-12',
            }
          ]);
        }
        return _json({});
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await tester.pumpWidget(MaterialApp(home: MemoryPage(api: api)));
      await tester.pump();
      await tester.pump();

      expect(memoryCalls, 1);

      // 点页头刷新按钮 → force 重拉记忆
      await tester.tap(find.byIcon(Icons.refresh));
      await tester.pump();
      await tester.pump();
      expect(memoryCalls, 2);
    });
  });

  group('DesktopShell #201 溢出 + #229 tooltip', () {
    testWidgets('超长 userId 不横向溢出 + @userId 入口有 Tooltip', (tester) async {
      await tester.binding.setSurfaceSize(const Size(1200, 800));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      const longId = 'a-very-long-user-id-that-would-overflow-the-200px-nav-rail';
      await tester.pumpWidget(const MaterialApp(
        home: DesktopShell(userId: longId),
      ));
      await tester.pump();

      // #201：Expanded+ellipsis 吸收溢出，无 RenderFlex overflow 异常
      expect(tester.takeException(), isNull);
      expect(find.text('@$longId', skipOffstage: false), findsOneWidget);
      // #229：tooltip 提示切换账号
      expect(find.byTooltip('切换账号（@$longId）'), findsOneWidget);
    });
  });

  group('AccountSelectPage #198/#230', () {
    MockClient accountsMock(List<String> accounts) {
      return MockClient((request) async {
        if (request.url.path == '/api/v1/accounts/available') {
          return _json(accounts);
        }
        return _json({});
      });
    }

    testWidgets('loading 态显示进度条（#198）', (tester) async {
      final api = ApiService(baseUrl: 'http://test', client: accountsMock(['alice']));
      await tester.pumpWidget(MaterialApp(
        home: AccountSelectPage(api: api, onSelect: (_) {}),
      ));
      // 首帧：请求未返回 → 进度条
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      await tester.pumpAndSettle();
      expect(find.text('alice'), findsOneWidget);
    });

    testWidgets('点击账号 → 切换确认 SnackBar「已切换至 @xxx」+ 回调（#230）', (tester) async {
      final api = ApiService(baseUrl: 'http://test', client: accountsMock(['alice', 'bob']));
      String? selected;
      await tester.pumpWidget(MaterialApp(
        home: AccountSelectPage(api: api, onSelect: (uid) => selected = uid),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('alice'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300)); // SnackBar 入场动画

      expect(selected, 'alice');
      expect(find.text('已切换至 @alice'), findsOneWidget);
    });

    testWidgets('空态文案「请先在阿呆控制台创建账号」', (tester) async {
      final api = ApiService(baseUrl: 'http://test', client: accountsMock([]));
      await tester.pumpWidget(MaterialApp(
        home: AccountSelectPage(api: api, onSelect: (_) {}),
      ));
      await tester.pumpAndSettle();
      expect(find.text('请先在阿呆控制台创建账号'), findsOneWidget);
    });
  });
}
