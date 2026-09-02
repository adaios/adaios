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
