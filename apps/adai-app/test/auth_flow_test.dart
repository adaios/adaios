import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:adai_app/main.dart';
import 'package:adai_app/pages/login_page.dart';
import 'package:adai_app/services/api_service.dart';

/// 登录体系流程测试（RFC 20260901-auth-login）。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});

  group('RootApp 登录流程', () {
    testWidgets('forceLogin 且无 token → 显示登录页', (tester) async {
      await tester.pumpWidget(const RootApp(forceLogin: true));
      await tester.pumpAndSettle();
      expect(find.byType(LoginPage), findsOneWidget);
    });

    testWidgets('forceLogin 且有有效 token → 校验通过进主界面', (tester) async {
      await tester.pumpWidget(RootApp(
        userId: 'adai',
        initialToken: 'tok_1',
        forceLogin: true,
        apiFactory: (userId) => _apiStub(userId, authMeStatus: 200),
      ));
      await tester.pumpAndSettle();
      expect(find.byType(LoginPage), findsNothing);
    });

    testWidgets('token 失效（authMe 401）→ 清 token 回登录页', (tester) async {
      await tester.pumpWidget(RootApp(
        userId: 'adai',
        initialToken: 'stale',
        forceLogin: true,
        apiFactory: (userId) => _apiStub(userId, authMeStatus: 401),
      ));
      await tester.pumpAndSettle();
      expect(find.byType(LoginPage), findsOneWidget);
    });
  });

  group('LoginPage 交互', () {
    testWidgets('输入账号密码 → 登录成功回调（token/userId 透传）', (tester) async {
      AuthSession? session;
      final api = _apiStub('adai', loginStatus: 200);
      await tester.pumpWidget(MaterialApp(
        home: LoginPage(api: api, onLoggedIn: (s) => session = s),
      ));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField).at(0), 'adai');
      await tester.enterText(find.byType(TextField).at(1), 'secret123');
      await tester.tap(find.text('登 录'));
      await tester.pumpAndSettle();

      expect(session, isNotNull);
      expect(session!.userId, 'adai');
      expect(session!.token, isNotEmpty);
    });

    testWidgets('密码错误（401）→ 人话提示', (tester) async {
      final api = _apiStub('adai', loginStatus: 401);
      await tester.pumpWidget(MaterialApp(
        home: LoginPage(api: api, onLoggedIn: (_) {}),
      ));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField).at(0), 'adai');
      await tester.enterText(find.byType(TextField).at(1), 'wrong');
      await tester.tap(find.text('登 录'));
      await tester.pumpAndSettle();

      expect(find.text('账号或密码错误'), findsOneWidget);
    });
  });

  group('DualWorldShell token 传递（回归：2026-09-02 线上全 401）', () {
    testWidgets('注入带 token 的 api → 页面请求带 Authorization', (tester) async {
      // 生产路径：RootApp 无 apiFactory，把带 token 的 ApiService 注入 DualWorldShell
      final authHeaders = <String>[];
      final api = ApiService(
        userId: 'adai',
        token: 'tok_prod',
        client: MockClient((request) async {
          final auth = request.headers['Authorization'];
          if (auth != null) authHeaders.add(auth);
          // 主页三连：plugins/feed/brief 全部 200 空数据（避免错误态干扰断言）
          return http.Response(
              '{"plugins":{"trading":false,"project":false}}', 200,
              headers: {'content-type': 'application/json; charset=utf-8'});
        }),
      );

      // 无 apiFactory、无 userId 直传时壳内自建会丢 token——注入 api 必须优先
      await tester.pumpWidget(MaterialApp(
        home: DualWorldShell(userId: 'adai', api: api),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(authHeaders, isNotEmpty,
          reason: '主页请求必须携带 Authorization: Bearer（否则后端 401）');
      expect(authHeaders.every((h) => h == 'Bearer tok_prod'), isTrue);
    });
  });

  group('multipart 请求带 token（回归：2026-09-02 截图/发图 401）', () {
    testWidgets('截图入账 + 发图 multipart 均携带 Authorization', (tester) async {
      final authHeaders = <String>[];
      final api = ApiService(
        userId: 'adai',
        token: 'tok_prod',
        client: MockClient((request) async {
          final auth = request.headers['Authorization'];
          if (auth != null) authHeaders.add(auth);
          return http.Response(
              '{"content":"{}"}', 200,
              headers: {'content-type': 'application/json; charset=utf-8'});
        }),
      );

      // 截图入账（multipart）
      await api.uploadTradingScreenshots(
        bytesList: [
          Uint8List.fromList([1, 2, 3])
        ],
        filenames: ['a.png'],
        mimeTypes: ['image/png'],
      );
      // 发图（records/media multipart）
      await api.uploadImage(
        bytes: Uint8List.fromList([1, 2, 3]),
        filename: 'b.png',
        mimeType: 'image/png',
      );

      expect(authHeaders.length, 2,
          reason: '截图入账和发图两个 multipart 请求都必须带 Bearer（否则后端 401）');
      expect(authHeaders.every((h) => h == 'Bearer tok_prod'), isTrue);
    });
  });
}

/// 构造 stub 认证端点的 ApiService（其余请求 404 不会走到）。
ApiService _apiStub(String userId, {int? loginStatus, int? authMeStatus}) {
  return ApiService(
    userId: userId,
    token: authMeStatus == null ? null : 'tok_1',
    client: MockClient((request) async {
      final path = request.url.path;
      if (path == '/api/v1/auth/login' && loginStatus != null) {
        return http.Response(
          loginStatus == 200
              ? '{"token":"tok_abc","userId":"adai","role":"admin","plugins":[]}'
              : '{"error":"账号或密码错误"}',
          loginStatus,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      if (path == '/api/v1/auth/me' && authMeStatus != null) {
        return http.Response(
          authMeStatus == 200
              ? '{"userId":"adai","role":"admin","plugins":[]}'
              : '{"error":"会话已失效"}',
          authMeStatus,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      return http.Response('not found', 404);
    }),
  );
}
