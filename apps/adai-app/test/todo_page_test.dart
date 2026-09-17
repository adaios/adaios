import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/pages/todo_page.dart';

// ────────────────────────────────────────────────────────────────
// 待办页专项测试（RFC 20260917：Kernel builtin 纯清单，两态 OPEN/DONE）
//
// 此前待办页零专项测试。覆盖：两态渲染 + 到期日人话（今天/明天/过期）、
// 新增（POST /todos）、勾选完成（PUT status=DONE）、删除（DELETE）、
// 加载失败可重试、写失败透出后端人话（不假装成功）。
// 复用 pages_widget_test 的 MockClient 基建，不依赖真实后端。
// ────────────────────────────────────────────────────────────────

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

class _Backend {
  final Map<String, Future<http.Response> Function(http.Request)> handlers = {};
  final List<http.Request> requests = [];
  Future<http.Response> handle(http.Request req) {
    requests.add(req);
    final h = handlers[req.url.path];
    if (h != null) return h(req);
    return Future.value(_json({'error': 'not mocked'}, status: 404));
  }
}

ApiService _apiFor(_Backend b) =>
    ApiService(baseUrl: 'http://test', client: MockClient(b.handle));

/// 相对今天偏移 [days] 天的 yyyy-MM-dd（到期日人话依赖当前日期）。
String _dateOffset(int days) {
  final n = DateTime.now().add(Duration(days: days));
  return '${n.year}-${n.month.toString().padLeft(2, '0')}-'
      '${n.day.toString().padLeft(2, '0')}';
}

Map<String, dynamic> _todo(String id, String title,
        {String status = 'OPEN', String? due}) =>
    {
      'id': id,
      'title': title,
      'status': status,
      'due': due,
      'sourceRecordId': null,
      'createdAt': _dateOffset(0),
      'updatedAt': _dateOffset(0),
    };

void main() {
  group('TodoItem / TodoStats 解析（防御式）', () {
    test('due 缺字段 / 空串 / 畸形串 → null（不崩、不假装有日期）', () {
      expect(TodoItem.fromJson({'id': '1', 'title': 'x'}).due, isNull);
      expect(TodoItem.fromJson({'id': '1', 'title': 'x', 'due': ''}).due, isNull);
      expect(TodoItem.fromJson({'id': '1', 'title': 'x', 'due': '  '}).due, isNull);
      expect(
          TodoItem.fromJson({'id': '1', 'title': 'x', 'due': 'not-a-date'}).due,
          isNull);
      expect(TodoItem.fromJson({'id': '1', 'title': 'x', 'due': 123}).due, isNull);
      expect(TodoItem.fromJson({'id': '1', 'title': 'x', 'due': '2026-09-20'}).due,
          DateTime(2026, 9, 20));
    });

    test('status 缺省 OPEN，isDone 只在 DONE 为真', () {
      final t = TodoItem.fromJson({'id': '1', 'title': 'x'});
      expect(t.status, TodoStatus.open);
      expect(t.isDone, isFalse);
      expect(TodoItem.fromJson({'id': '1', 'title': 'x', 'status': 'DONE'}).isDone,
          isTrue);
    });

    test('TodoStats 解析（total/open/done）', () {
      final s = TodoStats.fromJson({'total': 5, 'open': 3, 'done': 2});
      expect(s.total, 5);
      expect(s.open, 3);
      expect(s.done, 2);
      expect(TodoStats.fromJson({}).total, 0);
    });
  });

  group('todoDueLabel（人话）', () {
    final now = DateTime(2026, 9, 17, 22, 0);

    test('今天 / 明天 / 具体日子 / 跨年', () {
      expect(todoDueLabel(DateTime(2026, 9, 17), now: now), '今天');
      expect(todoDueLabel(DateTime(2026, 9, 18), now: now), '明天');
      expect(todoDueLabel(DateTime(2026, 9, 20), now: now), '9月20日');
      expect(todoDueLabel(DateTime(2027, 1, 3), now: now), '2027年1月3日');
      expect(todoDueLabel(null, now: now), isNull);
    });

    test('过期：默认标「已过期」，已完成条目不说过期', () {
      expect(todoDueLabel(DateTime(2026, 9, 15), now: now), '9月15日 · 已过期');
      expect(
          todoDueLabel(DateTime(2026, 9, 15), now: now, markOverdue: false),
          '9月15日');
    });

    test('todoDueOverdue 只对昨天及更早为真', () {
      expect(todoDueOverdue(DateTime(2026, 9, 16), now: now), isTrue);
      expect(todoDueOverdue(DateTime(2026, 9, 17), now: now), isFalse);
      expect(todoDueOverdue(DateTime(2026, 9, 18), now: now), isFalse);
      expect(todoDueOverdue(null, now: now), isFalse);
    });
  });

  group('TodoPage', () {
    testWidgets('两态渲染：未完成在上、已完成默认收起、到期日人话', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/todos'] = (_) async => _json([
            _todo('t1', '给妈打个电话', due: _dateOffset(0)),
            _todo('t2', '交水费', due: _dateOffset(1)),
            _todo('t3', '退快递', due: _dateOffset(-2)),
            _todo('t4', '写周报'),
            _todo('t5', '倒垃圾', status: 'DONE', due: _dateOffset(-5)),
          ]);
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      // 请求不带 status（两态一次取回，本地分组）
      final get = b.requests.firstWhere((r) => r.method == 'GET');
      expect(get.url.path, '/api/v1/todos');
      expect(get.url.queryParameters.containsKey('status'), isFalse);

      expect(find.text('给妈打个电话'), findsOneWidget);
      expect(find.text('今天'), findsOneWidget);
      expect(find.text('明天'), findsOneWidget);
      expect(find.textContaining('已过期'), findsOneWidget);
      expect(find.text('写周报'), findsOneWidget, reason: '没设到期日也能显示');

      // 已完成默认收起
      expect(find.text('已完成 (1)'), findsOneWidget);
      expect(find.text('倒垃圾'), findsNothing);

      await tester.tap(find.byKey(const Key('todo-done-toggle')));
      await tester.pumpAndSettle();
      expect(find.text('倒垃圾'), findsOneWidget);
      expect(find.textContaining('已过期'), findsOneWidget,
          reason: '已完成条目不再标过期（仍只算 t3 一条）');
    });

    testWidgets('空态是人话（没有待办时不摆空架子）', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/todos'] = (_) async => _json([]);
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.textContaining('还没有待办'), findsOneWidget);
    });

    testWidgets('新增一条：POST /todos，标题进入清单', (tester) async {
      final b = _Backend();
      final items = <Map<String, dynamic>>[];
      b.handlers['/api/v1/todos'] = (req) async {
        if (req.method == 'POST') {
          final body = jsonDecode(req.body) as Map<String, dynamic>;
          final t = _todo('t-new', body['title'] as String, due: body['due'] as String?);
          items.add(t);
          return _json(t);
        }
        return _json(items);
      };
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('todo-input')), '买牛奶');
      await tester.tap(find.byKey(const Key('todo-add')));
      await tester.pumpAndSettle();

      final post = b.requests.firstWhere((r) => r.method == 'POST');
      expect(post.url.path, '/api/v1/todos');
      final body = jsonDecode(post.body) as Map<String, dynamic>;
      expect(body['title'], '买牛奶');
      expect(body.containsKey('due'), isFalse, reason: '没选到期日就不发 due');
      expect(find.text('买牛奶'), findsOneWidget);
      expect(
          tester.widget<TextField>(find.byKey(const Key('todo-input'))).controller!.text,
          isEmpty,
          reason: '加成功后输入框清空');
    });

    testWidgets('选到期日再加：POST body 带 LocalDate（YYYY-MM-DD）', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/todos'] = (req) async =>
          req.method == 'POST' ? _json(_todo('t-new', '买牛奶')) : _json([]);
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('todo-due-picker')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('就这天'));
      await tester.pumpAndSettle();
      expect(find.textContaining('到期：'), findsOneWidget);
      expect(find.textContaining('今天'), findsOneWidget);

      await tester.enterText(find.byKey(const Key('todo-input')), '买牛奶');
      await tester.tap(find.byKey(const Key('todo-add')));
      await tester.pumpAndSettle();

      final post = b.requests.firstWhere((r) => r.method == 'POST');
      expect((jsonDecode(post.body) as Map<String, dynamic>)['due'], _dateOffset(0));
    });

    testWidgets('勾选完成：PUT /todos/{id} status=DONE；已完成再点回 OPEN', (tester) async {
      final b = _Backend();
      var serverStatus = 'OPEN';
      b.handlers['/api/v1/todos'] =
          (_) async => _json([_todo('t1', '给妈打个电话', status: serverStatus)]);
      b.handlers['/api/v1/todos/t1'] = (req) async {
        serverStatus =
            (jsonDecode(req.body) as Map<String, dynamic>)['status'] as String;
        return _json(_todo('t1', '给妈打个电话', status: serverStatus));
      };
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('todo-check-t1')));
      await tester.pumpAndSettle();

      final put = b.requests.firstWhere((r) => r.method == 'PUT');
      expect(put.url.path, '/api/v1/todos/t1');
      expect((jsonDecode(put.body) as Map<String, dynamic>)['status'], 'DONE');
      expect(serverStatus, 'DONE');
      expect(find.text('已完成 (1)'), findsOneWidget, reason: '勾完折进已完成区');

      // 展开「已完成」，再点一下 → 回到 OPEN
      await tester.tap(find.byKey(const Key('todo-done-toggle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('todo-check-t1')));
      await tester.pumpAndSettle();

      final puts = b.requests.where((r) => r.method == 'PUT').toList();
      expect(puts.length, 2);
      expect((jsonDecode(puts.last.body) as Map<String, dynamic>)['status'], 'OPEN');
      expect(find.text('已完成 (1)'), findsNothing, reason: '回到未完成区');
    });

    testWidgets('删除：确认后 DELETE /todos/{id}，先留着则不发请求', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/todos'] = (_) async => _json([_todo('t1', '给妈打个电话')]);
      b.handlers['/api/v1/todos/t1'] =
          (req) async => req.method == 'DELETE' ? http.Response('', 204) : _json({});
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('todo-delete-t1')));
      await tester.pumpAndSettle();
      expect(find.text('删掉这条？'), findsOneWidget);

      await tester.tap(find.text('先留着'));
      await tester.pumpAndSettle();
      expect(b.requests.any((r) => r.method == 'DELETE'), isFalse);

      await tester.tap(find.byKey(const Key('todo-delete-t1')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删掉'));
      await tester.pumpAndSettle();

      final del = b.requests.firstWhere((r) => r.method == 'DELETE');
      expect(del.url.path, '/api/v1/todos/t1');
    });

    testWidgets('加载失败：人话 + 重试成功后渲染', (tester) async {
      final b = _Backend();
      var fail = true;
      b.handlers['/api/v1/todos'] = (_) async => fail
          ? _json({'error': 'boom'}, status: 500)
          : _json([_todo('t1', '给妈打个电话')]);
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      expect(find.textContaining('没能取回你的待办'), findsOneWidget);
      expect(find.text('重试'), findsOneWidget);

      fail = false;
      await tester.tap(find.byKey(const Key('todo-retry')));
      await tester.pumpAndSettle();
      expect(find.text('给妈打个电话'), findsOneWidget);
    });

    testWidgets('勾选失败：后端 error 人话透出，不假装成功', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/todos'] = (_) async => _json([_todo('t1', '给妈打个电话')]);
      b.handlers['/api/v1/todos/t1'] =
          (_) async => _json({'error': '这条已经不在清单里了'}, status: 404);
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('todo-check-t1')));
      await tester.pumpAndSettle();

      expect(find.textContaining('这条已经不在清单里了'), findsOneWidget);
      // 没假装成功：条目还在未完成列表里
      expect(find.text('给妈打个电话'), findsOneWidget);
    });

    testWidgets('加一条失败：后端人话透出且输入不清空（可重试）', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/todos'] = (req) async => req.method == 'POST'
          ? _json({'error': '先登录一下'}, status: 401)
          : _json([]);
      await tester.pumpWidget(MaterialApp(home: TodoPage(api: _apiFor(b))));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('todo-input')), '买牛奶');
      await tester.tap(find.byKey(const Key('todo-add')));
      await tester.pumpAndSettle();

      expect(find.textContaining('先登录一下'), findsOneWidget);
      expect(
          tester.widget<TextField>(find.byKey(const Key('todo-input'))).controller!.text,
          '买牛奶',
          reason: '失败不丢用户输入，还能再点一次');
    });
  });
}
