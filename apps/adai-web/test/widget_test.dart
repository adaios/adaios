import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:adai_web/main.dart';
import 'package:adai_web/pages/login_page.dart';
import 'package:adai_web/services/api_service.dart';

void main() {
  // UserStore 的 io 实现（VM 测试）走 SharedPreferences —— 需要 mock 否则 getInstance 挂起
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});

  group('AdaiWebApp 会话流程（RFC 20260901-auth-login）', () {
    testWidgets('无 token → 显示登录页', (tester) async {
      await tester.pumpWidget(const AdaiWebApp());
      await tester.pumpAndSettle();
      expect(find.byType(LoginPage), findsOneWidget);
    });

    testWidgets('有 token → 启动校验后进入主界面（authMe 成功）', (tester) async {
      final app = AdaiWebApp(
        initialToken: 'tok_1',
        initialUserId: 'adai',
        apiFactory: (token, userId) => _apiWithAuthMe(200),
      );
      await tester.pumpWidget(app);
      await tester.pumpAndSettle();
      expect(find.byType(LoginPage), findsNothing);
    });

    testWidgets('有 token 但会话失效（authMe 401）→ 回登录页', (tester) async {
      final app = AdaiWebApp(
        initialToken: 'stale',
        initialUserId: 'adai',
        apiFactory: (token, userId) => _apiWithAuthMe(401),
      );
      await tester.pumpWidget(app);
      await tester.pumpAndSettle();
      expect(find.byType(LoginPage), findsOneWidget);
    });
  });

  group('L1 自动填充语义（RFC 20260914：让浏览器/钥匙串把这对框认成登录表单）', () {
    testWidgets('登录页：AutofillGroup + username/password 语义齐全', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: LoginPage(api: _apiWithAuthMe(200), onLoggedIn: (_) {}),
      ));
      await tester.pumpAndSettle();

      expect(find.byType(AutofillGroup), findsOneWidget,
          reason: '两个输入框必须包在 AutofillGroup 内，否则系统不认这张表单');

      final fields = tester.widgetList<TextField>(find.byType(TextField)).toList();
      expect(fields.length, 2);
      expect(fields[0].autofillHints, const [AutofillHints.username]);
      expect(fields[1].autofillHints, const [AutofillHints.password]);
      expect(fields[1].obscureText, isTrue);
    });
  });
}

/// 构造只 stub /auth/me 的 ApiService（其余请求 404 不会走到）。
ApiService _apiWithAuthMe(int status) {
  return ApiService(
    userId: 'adai',
    token: 'tok_1',
    client: MockClient((request) async {
      if (request.url.path == '/api/v1/auth/me') {
        return http.Response(
          status == 200 ? '{"userId":"adai","role":"admin","plugins":[]}' : '{"error":"会话已失效"}',
          status,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      return http.Response('not found', 404);
    }),
  );
}
