import 'package:flutter/foundation.dart' show debugDefaultTargetPlatformOverride;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:adai_app/main.dart';
import 'package:adai_app/pages/login_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/biometric_service.dart';
import 'package:adai_app/services/user_store.dart';

/// 假门禁：测试绝不碰真实 Face ID（RFC 20260914 L2）。
class _FakeGate implements BiometricGate {
  _FakeGate({required this.available, required this.pass});

  final bool available;
  final bool pass;
  int authenticateCalls = 0;

  @override
  Future<bool> isAvailable() async => available;

  @override
  Future<bool> authenticate({String reason = ''}) async {
    authenticateCalls++;
    return pass;
  }
}

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

    testWidgets('会话失效 → 同时清空分享扩展的共享容器（防跨账号串号）', (tester) async {
      // 那把钥匙**独立于登录会话**：登出既不会让它失效，后端也不连带撤销它。
      // 不清容器 = 换账号后 B站分享的链接会被扩展提交到**上一个账号**的学习卡里
      //（与 REVIEW P1-入口2 同族；2026-09-14 自查发现原实现漏了登出这条路径）。
      var shareCleared = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel('adai/share'), (call) async {
        if (call.method == 'clearToken') shareCleared++;
        return null;
      });
      // 只有 iOS 原生才会去碰这个通道（Web/PWA/Android 没有分享扩展，不该调）
      debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
      try {
        await tester.pumpWidget(RootApp(
          userId: 'adai',
          initialToken: 'stale',
          forceLogin: true,
          apiFactory: (userId) => _apiStub(userId, authMeStatus: 401),
        ));
        await tester.pumpAndSettle();

        expect(find.byType(LoginPage), findsOneWidget);
        expect(shareCleared, 1,
            reason: '登出必须清空共享容器，否则下一个账号的分享会落到上一个账号');
      } finally {
        debugDefaultTargetPlatformOverride = null;
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(const MethodChannel('adai/share'), null);
      }
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

  group('L1 自动填充语义（RFC 20260914：让 iOS 钥匙串记住密码 + Face ID 填充）', () {
    testWidgets('登录态：AutofillGroup + username/password 语义齐全', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: LoginPage(api: _apiStub('adai'), onLoggedIn: (_) {}),
      ));
      await tester.pumpAndSettle();

      // 没有 AutofillGroup，iOS 不会把这两个框认成「同一张登录表单」→ 不提示保存密码
      expect(find.byType(AutofillGroup), findsOneWidget,
          reason: '两个输入框必须包在 AutofillGroup 内，否则系统不认这张表单');

      final fields = tester.widgetList<TextField>(find.byType(TextField)).toList();
      expect(fields.length, 2);
      expect(fields[0].autofillHints, const [AutofillHints.username],
          reason: '账号框要 username 语义，钥匙串才会给建议');
      expect(fields[1].autofillHints, const [AutofillHints.password],
          reason: '密码框要 password 语义，Face ID 确认后才会自动填入');
      expect(fields[1].obscureText, isTrue);
    });

    testWidgets('设密码态：密码框语义切到 newPassword（触发系统「存储新密码」）', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: LoginPage(api: _apiStub('adai'), onLoggedIn: (_) {}),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('首次使用？设置密码'));
      await tester.pumpAndSettle();

      final fields = tester.widgetList<TextField>(find.byType(TextField)).toList();
      expect(fields[1].autofillHints, const [AutofillHints.newPassword],
          reason: '设密码态必须与登录态区分（newPassword 才会触发系统的强密码/保存流程）');
    });

    testWidgets('「记住我这台设备」开关默认勾选（RFC 20260914 L2）', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: LoginPage(api: _apiStub('adai'), onLoggedIn: (_) {}),
      ));
      await tester.pumpAndSettle();

      final checkbox = tester.widget<Checkbox>(find.byType(Checkbox));
      expect(checkbox.value, isTrue,
          reason: '默认记住：token 落钥匙串，下次可 Face ID 免密；取消勾选则只留内存');
    });
  });

  group('L2 Face ID 启动门禁（RFC 20260914）', () {
    testWidgets('记住过（有持久 token）且门禁通过 → 进主界面', (tester) async {
      await UserStore.saveToken('tok_1');
      final gate = _FakeGate(available: true, pass: true);
      await tester.pumpWidget(RootApp(
        userId: 'adai',
        initialToken: 'tok_1',
        forceLogin: true,
        biometric: gate,
        apiFactory: (userId) => _apiStub(userId, authMeStatus: 200),
      ));
      await tester.pumpAndSettle();

      expect(gate.authenticateCalls, 1, reason: '有持久 token 才需要过门禁');
      expect(find.byType(LoginPage), findsNothing);
    });

    testWidgets('门禁取消 → 回登录页但不清 token（下次打开还能 Face ID）', (tester) async {
      await UserStore.saveToken('tok_1');
      final gate = _FakeGate(available: true, pass: false);
      await tester.pumpWidget(RootApp(
        userId: 'adai',
        initialToken: 'tok_1',
        forceLogin: true,
        biometric: gate,
        apiFactory: (userId) => _apiStub(userId, authMeStatus: 200),
      ));
      await tester.pumpAndSettle();

      expect(find.byType(LoginPage), findsOneWidget);
      expect(await UserStore.loadToken(), 'tok_1',
          reason: '取消 Face ID 不等于登出——token 必须留着');
    });

    testWidgets('设备不支持生物识别 → 不弹窗，直接进', (tester) async {
      await UserStore.saveToken('tok_1');
      final gate = _FakeGate(available: false, pass: false);
      await tester.pumpWidget(RootApp(
        userId: 'adai',
        initialToken: 'tok_1',
        forceLogin: true,
        biometric: gate,
        apiFactory: (userId) => _apiStub(userId, authMeStatus: 200),
      ));
      await tester.pumpAndSettle();

      expect(gate.authenticateCalls, 0);
      expect(find.byType(LoginPage), findsNothing);
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
