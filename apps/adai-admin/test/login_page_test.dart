// 登录页 Widget 测试（REVIEW #178：admin 并入统一登录）。
// 注入 MockClient 的 ApiService；验证：admin 登录回调、普通用户被拒（控制台仅 admin）、
// 错误人话展示、登录失败不触发全局登出。

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_admin/pages/login_page.dart';
import 'package:adai_admin/services/api_service.dart';

const _jsonHeaders = {'content-type': 'application/json; charset=utf-8'};

http.Response _json(Object body, [int status = 200]) =>
    http.Response(jsonEncode(body), status, headers: _jsonHeaders);

Widget _wrap(LoginPage page) => MaterialApp(home: page);

void main() {
  testWidgets('admin 账号登录成功 → onLoggedIn 携 token/userId/role', (WidgetTester tester) async {
    final client = MockClient((request) async {
      expect(request.url.path, '/api/v1/auth/login');
      return _json({
        'token': 'tok_abc',
        'userId': 'adai',
        'role': 'admin',
        'plugins': ['trading'],
        'expiresAt': '2026-10-01T00:00:00Z',
      });
    });
    AdminSession? session;
    final api = ApiService(client: client);

    await tester.pumpWidget(_wrap(LoginPage(api: api, onLoggedIn: (s) => session = s)));
    await tester.pumpAndSettle();

    // 账号框预填 adai；只填密码
    await tester.enterText(find.byType(TextField).at(1), 'secret123');
    await tester.tap(find.text('登 录'));
    await tester.pumpAndSettle();

    expect(session, isNotNull);
    expect(session!.token, 'tok_abc');
    expect(session!.userId, 'adai');
    expect(session!.role, 'admin');
  });

  testWidgets('普通用户登录被拒：提示非管理员，不进入控制台', (WidgetTester tester) async {
    final client = MockClient((request) async => _json({
          'token': 'tok_user',
          'userId': 'alice',
          'role': 'user',
          'plugins': <String>[],
          'expiresAt': '2026-10-01T00:00:00Z',
        }));
    AdminSession? session;
    final api = ApiService(client: client);

    await tester.pumpWidget(_wrap(LoginPage(api: api, onLoggedIn: (s) => session = s)));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).at(1), 'secret123');
    await tester.tap(find.text('登 录'));
    await tester.pumpAndSettle();

    expect(session, isNull, reason: '非 admin 不得进入控制台');
    expect(find.textContaining('不是管理员账号'), findsOneWidget);
  });

  testWidgets('密码错误 401：人话提示且不触发全局登出', (WidgetTester tester) async {
    var unauthorizedFired = false;
    final client = MockClient((request) async =>
        _json({'error': '账号或密码错误'}, 401));
    final api = ApiService(client: client, onUnauthorized: () => unauthorizedFired = true);

    await tester.pumpWidget(_wrap(LoginPage(api: api, onLoggedIn: (_) {})));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).at(1), 'wrong');
    await tester.tap(find.text('登 录'));
    await tester.pumpAndSettle();

    expect(find.text('账号或密码错误'), findsOneWidget);
    expect(unauthorizedFired, isFalse);
  });

  testWidgets('首访引导：未设密码 401 → 显示设置密码模式', (WidgetTester tester) async {
    final client = MockClient((request) async =>
        _json({'error': '该账号尚未设置密码，请先完成初始化'}, 401));
    final api = ApiService(client: client);

    await tester.pumpWidget(_wrap(LoginPage(api: api, onLoggedIn: (_) {})));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).at(1), 'anything');
    await tester.tap(find.text('登 录'));
    await tester.pumpAndSettle();

    expect(find.text('首次使用：为账号「adai」设置登录密码（一次性，此后直接登录）'), findsOneWidget);
    expect(find.text('设置密码'), findsWidgets);
  });
}
