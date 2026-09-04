import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/pages/launcher_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/widgets/change_password_dialog.dart';

/// 「修改密码」入口测试（RFC 20260901-auth-login / #178 对拍 admin）。
///
/// 覆盖：Launcher 会话区入口可达 → 弹窗打开；
/// 校验拦截（新密码过短 / 两次不一致，不发起请求）；
/// 提交成功（200 含被踢会话数 → 人话 SnackBar，0 时省略）；
/// 401 原密码错误（弹窗内人话展示，不崩溃）。
/// 复用 MockClient 基建，不依赖真实后端。
http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

class _Backend {
  final Map<String, Future<http.Response> Function(http.Request)> handlers = {};
  Future<http.Response> handle(http.Request req) {
    final h = handlers[req.url.path];
    if (h != null) return h(req);
    return Future.value(_json({'error': 'not mocked'}, status: 404));
  }
}

ApiService _apiFor(_Backend b) =>
    ApiService(baseUrl: 'http://test', client: MockClient(b.handle));

/// 拉 LauncherPage：先喂齐核心数据（身份/标签/时间线/记忆数/插件），列表加载完成。
Future<void> _pumpLauncher(WidgetTester tester, _Backend b) async {
  b.handlers['/api/v1/identity'] = (_) async =>
      _json({'name': '测试', 'preferences': <String, dynamic>{}});
  b.handlers['/api/v1/tags'] = (_) async => _json({'tags': [], 'total': 0});
  b.handlers['/api/v1/timeline'] = (_) async => _json([]);
  b.handlers['/api/v1/memory/count'] = (_) async => _json({'count': 0});
  b.handlers['/api/v1/me/plugins'] = (_) async => _json([]);
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(body: LauncherPage(api: _apiFor(b), onNavigateBack: () {})),
  ));
  await tester.pumpAndSettle();
}

/// 打开改密弹窗：入口在会话区（退出登录旁），点击后出现三个密码输入框。
Future<void> _openDialog(WidgetTester tester) async {
  expect(find.text('修改密码'), findsOneWidget,
      reason: 'Launcher 会话区应有「修改密码」入口');
  await tester.tap(find.text('修改密码'));
  await tester.pumpAndSettle();
  expect(find.byType(ChangePasswordDialog), findsOneWidget);
}

void main() {
  group('修改密码入口（Launcher 会话区）', () {
    testWidgets('入口可达：登录后会话区展示「修改密码」，点击弹出弹窗', (tester) async {
      final b = _Backend();
      await _pumpLauncher(tester, b);

      await _openDialog(tester);
      // 弹窗三个输入框：原密码 / 新密码 / 确认新密码
      expect(find.byKey(const Key('change-password-old')), findsOneWidget);
      expect(find.byKey(const Key('change-password-new')), findsOneWidget);
      expect(find.byKey(const Key('change-password-confirm')), findsOneWidget);
      expect(find.text('确认修改'), findsOneWidget);
      // 关闭弹窗不报错
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(find.byType(ChangePasswordDialog), findsNothing);
    });

    testWidgets('新密码过短 → 弹窗内拦截，不发起请求', (tester) async {
      final b = _Backend();
      var passwordCalls = 0;
      b.handlers['/api/v1/auth/password'] = (_) async {
        passwordCalls++;
        return _json({'message': '密码已更新', 'kickedSessions': 0});
      };
      await _pumpLauncher(tester, b);
      await _openDialog(tester);

      await tester.enterText(find.byKey(const Key('change-password-old')), 'old123456');
      await tester.enterText(find.byKey(const Key('change-password-new')), '1234567'); // 7 位
      await tester.enterText(find.byKey(const Key('change-password-confirm')), '1234567');
      await tester.tap(find.text('确认修改'));
      await tester.pumpAndSettle();

      expect(find.text('新密码长度至少 8 位'), findsOneWidget);
      expect(find.byType(ChangePasswordDialog), findsOneWidget,
          reason: '校验失败弹窗不关闭');
      expect(passwordCalls, 0, reason: '过短在前端拦截，不应打到后端');
    });

    testWidgets('两次新密码不一致 → 弹窗内拦截，不发起请求', (tester) async {
      final b = _Backend();
      var passwordCalls = 0;
      b.handlers['/api/v1/auth/password'] = (_) async {
        passwordCalls++;
        return _json({'message': '密码已更新', 'kickedSessions': 0});
      };
      await _pumpLauncher(tester, b);
      await _openDialog(tester);

      await tester.enterText(find.byKey(const Key('change-password-old')), 'old123456');
      await tester.enterText(find.byKey(const Key('change-password-new')), 'new123456');
      await tester.enterText(find.byKey(const Key('change-password-confirm')), 'new654321');
      await tester.tap(find.text('确认修改'));
      await tester.pumpAndSettle();

      expect(find.text('两次输入的新密码不一致'), findsOneWidget);
      expect(find.byType(ChangePasswordDialog), findsOneWidget);
      expect(passwordCalls, 0);
    });

    testWidgets('提交成功（踢 2 个其他会话）→ SnackBar 含被踢数，弹窗关闭且保持登录', (tester) async {
      final b = _Backend();
      Map<String, dynamic>? sentBody;
      b.handlers['/api/v1/auth/password'] = (req) async {
        sentBody = jsonDecode(req.body) as Map<String, dynamic>;
        return _json({'message': '密码已更新', 'kickedSessions': 2});
      };
      await _pumpLauncher(tester, b);
      await _openDialog(tester);

      await tester.enterText(find.byKey(const Key('change-password-old')), 'old123456');
      await tester.enterText(find.byKey(const Key('change-password-new')), 'new123456');
      await tester.enterText(find.byKey(const Key('change-password-confirm')), 'new123456');
      await tester.tap(find.text('确认修改'));
      await tester.pumpAndSettle();

      // 请求体字段正确
      expect(sentBody, {'oldPassword': 'old123456', 'newPassword': 'new123456'});
      // 弹窗关闭 + 成功 SnackBar（含被踢会话数）
      expect(find.byType(ChangePasswordDialog), findsNothing);
      expect(find.text('密码已更新（已退出其他 2 处登录）'), findsOneWidget);
      // Launcher 仍在（未登出）
      expect(find.text('退出登录'), findsOneWidget);
      await tester.pump(const Duration(seconds: 5)); // 排空 SnackBar 计时器
    });

    testWidgets('提交成功（未踢任何会话）→ SnackBar 不含会话数', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/auth/password'] = (_) async =>
          _json({'message': '密码已更新', 'kickedSessions': 0});
      await _pumpLauncher(tester, b);
      await _openDialog(tester);

      await tester.enterText(find.byKey(const Key('change-password-old')), 'old123456');
      await tester.enterText(find.byKey(const Key('change-password-new')), 'new123456');
      await tester.enterText(find.byKey(const Key('change-password-confirm')), 'new123456');
      await tester.tap(find.text('确认修改'));
      await tester.pumpAndSettle();

      expect(find.text('密码已更新'), findsOneWidget);
      await tester.pump(const Duration(seconds: 5));
    });

    testWidgets('401 原密码错误 → 弹窗内人话展示，不崩溃不登出', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/auth/password'] = (_) async =>
          _json({'error': '原密码错误'}, status: 401);
      await _pumpLauncher(tester, b);
      await _openDialog(tester);

      await tester.enterText(find.byKey(const Key('change-password-old')), 'wrong-old');
      await tester.enterText(find.byKey(const Key('change-password-new')), 'new123456');
      await tester.enterText(find.byKey(const Key('change-password-confirm')), 'new123456');
      await tester.tap(find.text('确认修改'));
      await tester.pumpAndSettle();

      expect(find.text('原密码错误'), findsOneWidget);
      expect(find.byType(ChangePasswordDialog), findsOneWidget,
          reason: '错误弹窗内提示，用户可重试');
      expect(find.text('退出登录'), findsOneWidget, reason: '原密码错误不登出');
    });

    testWidgets('401 会话已失效 → 弹窗内人话提示（不崩溃，用户可关闭）', (tester) async {
      final b = _Backend();
      b.handlers['/api/v1/auth/password'] = (_) async =>
          _json({'error': '会话已失效，请重新登录'}, status: 401);
      await _pumpLauncher(tester, b);
      await _openDialog(tester);

      await tester.enterText(find.byKey(const Key('change-password-old')), 'old123456');
      await tester.enterText(find.byKey(const Key('change-password-new')), 'new123456');
      await tester.enterText(find.byKey(const Key('change-password-confirm')), 'new123456');
      await tester.tap(find.text('确认修改'));
      await tester.pumpAndSettle();

      expect(find.text('会话已失效，请重新登录'), findsOneWidget);
      expect(find.byType(ChangePasswordDialog), findsOneWidget,
          reason: '人话提示后由用户关闭弹窗（无 onUnauthorized 注入时保持现状）');
    });
  });

  group('ApiService.changePassword', () {
    test('成功解析被踢会话数', () async {
      final b = _Backend()
        ..handlers['/api/v1/auth/password'] = (_) async =>
            _json({'message': '密码已更新', 'kickedSessions': 3});
      final api = _apiFor(b);
      expect(await api.changePassword(oldPassword: 'o1', newPassword: 'n1234567'), 3);
    });

    test('kickedSessions 缺失/为 0 → 返回 0', () async {
      final b = _Backend()
        ..handlers['/api/v1/auth/password'] = (_) async =>
            _json({'message': '密码已更新'});
      final api = _apiFor(b);
      expect(await api.changePassword(oldPassword: 'o1', newPassword: 'n1234567'), 0);
    });

    test('401 原密码错误 → 抛 ApiException，携带状态码与后端 body', () async {
      final b = _Backend()
        ..handlers['/api/v1/auth/password'] = (_) async =>
            _json({'error': '原密码错误'}, status: 401);
      final api = _apiFor(b);
      await expectLater(
        api.changePassword(oldPassword: 'o1', newPassword: 'n1234567'),
        throwsA(isA<ApiException>()
            .having((e) => e.statusCode, 'statusCode', 401)
            .having((e) => e.body, 'body', contains('原密码错误'))),
      );
    });

    test('401 原密码错误 → 不触发 onUnauthorized（弹窗内人话重试）', () async {
      final b = _Backend()
        ..handlers['/api/v1/auth/password'] = (_) async =>
            _json({'error': '原密码错误'}, status: 401);
      var unauthorizedCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient(b.handle),
        onUnauthorized: () => unauthorizedCalls++,
      );
      await expectLater(api.changePassword(oldPassword: 'o1', newPassword: 'n1234567'),
          throwsA(isA<ApiException>()));
      expect(unauthorizedCalls, 0, reason: '原密码错误≠会话失效，不能全局登出');
    });

    test('401 会话已失效 → 抛异常并触发 onUnauthorized（与常规请求 401 语义一致）', () async {
      final b = _Backend()
        ..handlers['/api/v1/auth/password'] = (_) async =>
            _json({'error': '会话已失效，请重新登录'}, status: 401);
      var unauthorizedCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient(b.handle),
        onUnauthorized: () => unauthorizedCalls++,
      );
      await expectLater(
        api.changePassword(oldPassword: 'o1', newPassword: 'n1234567'),
        throwsA(isA<ApiException>()
            .having((e) => e.body, 'body', contains('会话已失效'))),
      );
      expect(unauthorizedCalls, 1, reason: '会话失效 → 清 token 回登录页');
    });
  });
}
