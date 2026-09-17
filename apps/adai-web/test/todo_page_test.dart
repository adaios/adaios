import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_web/pages/todo_page.dart';
import 'package:adai_web/services/api_service.dart';

/// RFC 20260917：待办页专项测试（此前为零）——
/// 两态渲染（未完成在上 / 已完成折叠）、顶部新增、完成、删除、失败态。
http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

Map<String, dynamic> _todo(String id, String title, {String status = 'OPEN', String? due}) => {
      'id': id,
      'title': title,
      'status': status,
      'due': due,
      'sourceRecordId': null,
      'createdAt': '2026-09-17',
      'updatedAt': '2026-09-17',
    };

Map<String, dynamic> _body(http.Request req) =>
    jsonDecode(utf8.decode(req.bodyBytes)) as Map<String, dynamic>;

void main() {
  Future<void> pump(WidgetTester tester, ApiService api) async {
    await tester.binding.setSurfaceSize(const Size(1100, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: TodoPage(api: api))));
    await tester.pumpAndSettle();
  }

  testWidgets('两态渲染：未完成在上、已完成默认折叠，展开后可见', (tester) async {
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        if (req.url.path == '/api/v1/todos') {
          return _json([
            _todo('t1', '给妈打个电话', due: '2026-09-20'),
            _todo('t2', '交房租', status: 'DONE'),
          ]);
        }
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
    await pump(tester, api);

    expect(find.text('待办'), findsOneWidget);
    expect(find.text('给妈打个电话'), findsOneWidget);
    expect(find.text('还有 1 件没做'), findsOneWidget);
    // 已完成默认折叠
    expect(find.text('已完成 1'), findsOneWidget);
    expect(find.text('交房租'), findsNothing);
    // 到期日 chip 存在（不锁显示文案，避免与当前日期耦合）
    expect(find.byKey(const ValueKey('todo-due-t1')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('todo-done-toggle')));
    await tester.pumpAndSettle();
    expect(find.text('交房租'), findsOneWidget);
  });

  testWidgets('空态：人话引导，不出现系统口吻', (tester) async {
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        if (req.url.path == '/api/v1/todos') return _json(<Object>[]);
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
    await pump(tester, api);

    expect(find.text('还没有待办。想到什么，就在上面写一条吧。'), findsOneWidget);
  });

  testWidgets('顶部加一条：POST /todos 契约 + 列表刷新', (tester) async {
    final created = <String>[];
    Map<String, dynamic>? lastBody;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        if (req.url.path == '/api/v1/todos' && req.method == 'GET') {
          return _json([
            for (var i = 0; i < created.length; i++) _todo('t$i', created[i]),
          ]);
        }
        if (req.url.path == '/api/v1/todos' && req.method == 'POST') {
          lastBody = _body(req);
          created.add(lastBody!['title'] as String);
          return _json(_todo('t${created.length - 1}', created.last));
        }
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
    await pump(tester, api);

    await tester.enterText(find.byKey(const ValueKey('todo-input')), '买牛奶');
    await tester.tap(find.byKey(const ValueKey('todo-add')));
    await tester.pumpAndSettle();

    expect(lastBody!['title'], '买牛奶');
    // 没选到期日 → 契约上不发出 due（省略/null 均可）
    expect(lastBody!.containsKey('due'), false);
    expect(find.text('买牛奶'), findsOneWidget);
    expect(find.text('还有 1 件没做'), findsOneWidget);
  });

  testWidgets('完成：PUT status=DONE，条目移入已完成折叠', (tester) async {
    var todo = _todo('t1', '给妈打个电话');
    final puts = <Map<String, dynamic>>[];
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        if (req.url.path == '/api/v1/todos' && req.method == 'GET') return _json([todo]);
        if (req.url.path == '/api/v1/todos/t1' && req.method == 'PUT') {
          final body = _body(req);
          puts.add(body);
          todo = _todo('t1', '给妈打个电话', status: (body['status'] as String?) ?? 'OPEN');
          return _json(todo);
        }
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
    await pump(tester, api);

    await tester.tap(find.byKey(const ValueKey('todo-toggle-t1')));
    await tester.pumpAndSettle();

    expect(puts, [
      {'status': 'DONE'}
    ]);
    expect(find.text('给妈打个电话'), findsNothing, reason: '完成后移出未完成区');
    expect(find.text('已完成 1'), findsOneWidget);
    expect(find.text('手头的事都做完了。'), findsOneWidget);
  });

  testWidgets('删除：DELETE /todos/{id}，条目消失', (tester) async {
    var todos = [_todo('t1', '给妈打个电话')];
    final deleted = <String>[];
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        if (req.url.path == '/api/v1/todos' && req.method == 'GET') return _json(todos);
        if (req.url.path == '/api/v1/todos/t1' && req.method == 'DELETE') {
          deleted.add('t1');
          todos = [];
          return http.Response('', 204);
        }
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
    await pump(tester, api);

    await tester.tap(find.byKey(const ValueKey('todo-delete-t1')));
    await tester.pumpAndSettle();

    expect(deleted, ['t1']);
    expect(find.text('给妈打个电话'), findsNothing);
    expect(find.text('还没有待办。想到什么，就在上面写一条吧。'), findsOneWidget);
  });

  testWidgets('清除到期日：PUT due 传空串（契约：空串 = 清除）', (tester) async {
    var todo = _todo('t1', '给妈打个电话', due: '2026-09-20');
    final puts = <Map<String, dynamic>>[];
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        if (req.url.path == '/api/v1/todos' && req.method == 'GET') return _json([todo]);
        if (req.url.path == '/api/v1/todos/t1' && req.method == 'PUT') {
          final body = _body(req);
          puts.add(body);
          todo = _todo('t1', '给妈打个电话', due: (body['due'] as String?)?.isEmpty ?? false ? null : body['due'] as String?);
          return _json(todo);
        }
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
    await pump(tester, api);

    await tester.tap(find.byKey(const ValueKey('todo-due-clear-t1')));
    await tester.pumpAndSettle();

    expect(puts, [
      {'due': ''}
    ]);
    expect(find.byKey(const ValueKey('todo-due-t1')), findsOneWidget,
        reason: '清除后仍保留「加到期日」入口');
  });

  testWidgets('失败态：加载失败可见可重试，重试成功后渲染列表', (tester) async {
    var calls = 0;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        if (req.url.path == '/api/v1/todos') {
          calls++;
          if (calls == 1) return _json({'error': '崩了'}, status: 500);
          return _json([_todo('t1', '给妈打个电话')]);
        }
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
    await pump(tester, api);

    expect(find.text('没读出来，再试一下？'), findsOneWidget);
    expect(find.byKey(const ValueKey('todo-retry')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('todo-retry')));
    await tester.pumpAndSettle();

    expect(find.text('给妈打个电话'), findsOneWidget);
    expect(find.text('没读出来，再试一下？'), findsNothing);
  });

  testWidgets('失败态：新增失败保留输入 + 人话原因，可原地重试', (tester) async {
    var posts = 0;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        if (req.url.path == '/api/v1/todos' && req.method == 'GET') return _json(<Object>[]);
        if (req.url.path == '/api/v1/todos' && req.method == 'POST') {
          posts++;
          return _json({'error': '服务器打了个盹'}, status: 500);
        }
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
    await pump(tester, api);

    await tester.enterText(find.byKey(const ValueKey('todo-input')), '买牛奶');
    await tester.tap(find.byKey(const ValueKey('todo-add')));
    await tester.pumpAndSettle();

    expect(posts, 1);
    expect(find.textContaining('没加上：'), findsOneWidget);
    // 输入保留（失败不清空）
    final field = tester.widget<TextField>(find.byKey(const ValueKey('todo-input')));
    expect(field.controller!.text, '买牛奶');

    // 原地重试（第二次仍失败 → 仍是失败态，不是静默吞错）
    await tester.tap(find.text('再试一次'));
    await tester.pumpAndSettle();
    expect(posts, 2);
    expect(find.textContaining('没加上：'), findsOneWidget);
  });
}
