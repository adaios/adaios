import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_web/desktop_shell.dart';
import 'package:adai_web/services/api_service.dart';

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

/// 壳内改密流程测试用 ApiService（MockClient 注入，模式同 desktop_shell_test）：
/// 基础端点给 200（feed/brief 空数据，防首屏错误弹层干扰交互），plugins 空（只显基础服务），
/// `/api/v1/auth/password` 走 [onPassword]（默认成功 +2 被踢会话）并把请求体记入 [passwordCalls]。
({ApiService api, List<Map<String, dynamic>> passwordCalls}) _shellApi({
  http.Response Function()? onPassword,
  void Function()? onUnauthorized,
}) {
  final passwordCalls = <Map<String, dynamic>>[];
  final api = ApiService(
    baseUrl: 'http://test',
    userId: 'adai',
    token: 'tok_1',
    onUnauthorized: onUnauthorized,
    client: MockClient((req) async {
      final p = req.url.path;
      if (p == '/api/v1/me/plugins') return _json(const []);
      if (p == '/api/v1/feed') return _json({'entries': <Object>[], 'totalToday': 0});
      if (p == '/api/v1/brief/cached') return _json(const {});
      if (p == '/api/v1/brief') return _json({'content': ''});
      if (p == '/api/v1/auth/password') {
        passwordCalls.add(jsonDecode(req.body) as Map<String, dynamic>);
        final custom = onPassword?.call();
        if (custom != null) return custom;
        return _json({'message': '密码已更新', 'kickedSessions': 2});
      }
      return _json({'error': 'not mocked'}, status: 404);
    }),
  );
  return (api: api, passwordCalls: passwordCalls);
}

void main() {
  Future<void> pumpShell(WidgetTester tester, ApiService api) async {
    await tester.binding.setSurfaceSize(const Size(1200, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(home: DesktopShell(userId: 'adai', api: api)));
    await tester.pump(); // plugins/feed 响应落地
    await tester.pump();
  }

  /// 完整入口链路：点底部用户会话菜单 → 选「修改密码」→ 弹窗出现。
  Future<void> openPasswordDialog(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('session-menu')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('修改密码'));
    await tester.pumpAndSettle();
  }

  Future<void> fillAndSubmit(WidgetTester tester,
      {required String oldPwd,
      required String newPwd,
      required String confirm}) async {
    final fields =
        find.descendant(of: find.byType(AlertDialog), matching: find.byType(TextField));
    expect(fields, findsNWidgets(3), reason: '弹窗应有 原密码/新密码/确认 三个输入框');
    await tester.enterText(fields.at(0), oldPwd);
    await tester.enterText(fields.at(1), newPwd);
    await tester.enterText(fields.at(2), confirm);
    await tester.tap(find.text('确认修改'));
    await tester.pumpAndSettle();
  }

  group('DesktopShell 修改密码入口（2026-09-04 web 自助改密）', () {
    testWidgets('底部用户行是可点会话菜单：含「修改密码 / 退出登录」', (tester) async {
      final built = _shellApi();
      await pumpShell(tester, built.api);

      final menu = find.byKey(const ValueKey('session-menu'));
      expect(menu, findsOneWidget, reason: '底部用户行即会话菜单入口（任意页面可达）');
      expect(find.text('@adai'), findsOneWidget);

      await tester.tap(menu);
      await tester.pumpAndSettle();
      expect(find.text('修改密码'), findsOneWidget, reason: '菜单应提供改密入口');
      expect(find.text('退出登录'), findsOneWidget, reason: '菜单应保留退出登录');
    });

    testWidgets('提交成功：SnackBar 含被踢会话数，请求体带 old/new 密码', (tester) async {
      final built = _shellApi();
      await pumpShell(tester, built.api);
      await openPasswordDialog(tester);

      await fillAndSubmit(tester,
          oldPwd: 'old-pass-1', newPwd: 'new-pass-123', confirm: 'new-pass-123');

      expect(find.byType(AlertDialog), findsNothing, reason: '成功后弹窗应关闭');
      expect(find.text('密码已更新（已退出其他 2 处登录）'), findsOneWidget,
          reason: '成功 SnackBar 文案应含被踢会话数');
      expect(built.passwordCalls, hasLength(1));
      expect(built.passwordCalls.single['oldPassword'], 'old-pass-1');
      expect(built.passwordCalls.single['newPassword'], 'new-pass-123');
    });

    testWidgets('成功且无其他登录（kickedSessions=0）：提示不带括号', (tester) async {
      final built = _shellApi(
          onPassword: () => _json({'message': '密码已更新', 'kickedSessions': 0}));
      await pumpShell(tester, built.api);
      await openPasswordDialog(tester);

      await fillAndSubmit(tester,
          oldPwd: 'old-pass-1', newPwd: 'new-pass-123', confirm: 'new-pass-123');

      expect(find.text('密码已更新'), findsOneWidget);
      expect(find.textContaining('已退出其他'), findsNothing);
    });

    testWidgets('两次新密码不一致：弹窗内拦截，不发请求', (tester) async {
      final built = _shellApi();
      await pumpShell(tester, built.api);
      await openPasswordDialog(tester);

      await fillAndSubmit(tester,
          oldPwd: 'old-pass-1', newPwd: 'new-pass-123', confirm: 'new-pass-999');

      expect(find.text('两次输入的新密码不一致'), findsOneWidget);
      expect(find.byType(AlertDialog), findsOneWidget, reason: '校验失败弹窗保留可重试');
      expect(built.passwordCalls, isEmpty, reason: '本地校验不过不发网络请求');
    });

    testWidgets('新密码不足 8 位：弹窗内拦截，不发请求', (tester) async {
      final built = _shellApi();
      await pumpShell(tester, built.api);
      await openPasswordDialog(tester);

      await fillAndSubmit(tester,
          oldPwd: 'old-pass-1', newPwd: 'short', confirm: 'short');

      expect(find.text('新密码长度至少 8 位'), findsOneWidget);
      expect(find.byType(AlertDialog), findsOneWidget);
      expect(built.passwordCalls, isEmpty);
    });

    testWidgets('原密码错误（401）：弹窗内展示后端人话，不触发全局登出', (tester) async {
      var unauthorizedFired = 0;
      final built = _shellApi(
        onPassword: () => _json({'error': '原密码错误'}, status: 401),
        onUnauthorized: () => unauthorizedFired++,
      );
      await pumpShell(tester, built.api);
      await openPasswordDialog(tester);

      await fillAndSubmit(tester,
          oldPwd: 'wrong-old', newPwd: 'new-pass-123', confirm: 'new-pass-123');

      expect(find.text('原密码错误'), findsOneWidget, reason: '后端 401 人话应透出');
      expect(find.byType(AlertDialog), findsOneWidget, reason: '失败后弹窗保留可重试');
      expect(find.byType(DesktopShell), findsOneWidget);
      expect(unauthorizedFired, 0,
          reason: '「原密码错误」= 会话仍有效，不能误判会话失效把用户踢回登录页');
      expect(built.passwordCalls, hasLength(1));
    });
  });

  group('ApiService.changePassword（401 语义区分）', () {
    test('成功：返回被踢会话数，body 契约 oldPassword/newPassword', () async {
      Map<String, dynamic>? sentBody;
      var unauthorizedFired = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        token: 'tok_1',
        onUnauthorized: () => unauthorizedFired++,
        client: MockClient((req) async {
          expect(req.url.path, '/api/v1/auth/password');
          sentBody = jsonDecode(req.body) as Map<String, dynamic>;
          return _json({'message': '密码已更新', 'kickedSessions': 3});
        }),
      );

      final kicked =
          await api.changePassword(oldPassword: 'old-pass-1', newPassword: 'new-pass-123');

      expect(kicked, 3);
      expect(sentBody?['oldPassword'], 'old-pass-1');
      expect(sentBody?['newPassword'], 'new-pass-123');
      expect(unauthorizedFired, 0);
    });

    test('401 原密码错误：抛 ApiException 且不触发 onUnauthorized', () async {
      var unauthorizedFired = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        token: 'tok_1',
        onUnauthorized: () => unauthorizedFired++,
        client: MockClient((_) async => _json({'error': '原密码错误'}, status: 401)),
      );

      await expectLater(
        api.changePassword(oldPassword: 'bad', newPassword: 'new-pass-123'),
        throwsA(isA<ApiException>()),
      );
      expect(unauthorizedFired, 0, reason: '原密码错误 401 ≠ 会话失效');
    });

    test('401 会话失效（AuthFilter 文案）：抛 ApiException 且触发 onUnauthorized', () async {
      var unauthorizedFired = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        token: 'stale',
        onUnauthorized: () => unauthorizedFired++,
        client: MockClient((_) async =>
            _json({'error': '未登录或会话已失效，请先登录'}, status: 401)),
      );

      await expectLater(
        api.changePassword(oldPassword: 'old', newPassword: 'new-pass-123'),
        throwsA(isA<ApiException>()),
      );
      expect(unauthorizedFired, 1, reason: '真会话失效应走全局 401 回登录页');
    });
  });
}
