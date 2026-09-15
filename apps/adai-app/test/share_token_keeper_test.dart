import 'dart:convert';

import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/share_extension_service.dart';
import 'package:adai_app/services/share_token_keeper.dart';
import 'package:flutter/foundation.dart' show debugDefaultTargetPlatformOverride;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

// ────────────────────────────────────────────────────────────────
// ShareTokenKeeper（RFC 20260915 分享扩展凭据方案）
//
// 这批的全部意义是「用户零操作」，所以测试要锁的不是"能签发"，而是几个
// **错了就会静默坏事**的点：
// ① 没钥匙 / 快到期 / 老数据没记到期时间 → 都要补签；
// ② **先写容器成功、再撤销旧钥匙**——顺序反了会让两把同时失效，分享直接不可用；
// ③ 写入失败时**绝不**撤销旧的；
// ④ 任何异常都不冒泡（否则登录会被它拖垮）；
// ⑤ 非 iOS 一律不动作（web / PWA / Android 没有这个扩展）。
// ────────────────────────────────────────────────────────────────

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

String _iso(Duration fromNow) =>
    DateTime.now().toUtc().add(fromNow).toIso8601String();

/// 共享容器的假实现（真实实现在 `ios/Runner/ShareBridge.swift`）。
class _Container {
  _Container(this.order);

  final List<String> order;

  bool available = true;
  bool saveSucceeds = true;

  String? token;
  String? id;
  String? expiresAt;

  Future<Object?> handle(MethodCall call) async {
    switch (call.method) {
      case 'status':
        order.add('status');
        return <String, Object?>{
          'available': available,
          'hasToken': token != null && token!.isNotEmpty,
          if (id != null) 'id': id,
          if (expiresAt != null) 'expiresAt': expiresAt,
          'savedAt': 1.0,
        };
      case 'saveToken':
        final args = (call.arguments as Map).cast<String, dynamic>();
        if (!saveSucceeds) {
          order.add('save-failed');
          return false;
        }
        token = args['token'] as String?;
        id = args['id'] as String?;
        expiresAt = args['expiresAt'] as String?;
        order.add('save');
        return true;
      case 'clearToken':
        order.add('clear');
        token = null;
        id = null;
        expiresAt = null;
        return true;
    }
    return null;
  }
}

/// 后端假实现：只关心「签发了几把、撤了哪几把」，并把顺序记进同一个 order。
class _Backend {
  _Backend(this.order);

  final List<String> order;

  bool issueFails = false;
  int issueCount = 0;
  final List<String> revoked = [];

  Future<http.Response> handle(http.Request req) async {
    final path = req.url.path;
    if (path.endsWith('/api/v1/auth/tokens') && req.method == 'POST') {
      issueCount++;
      order.add('issue');
      if (issueFails) return _json({'error': '签发失败'}, status: 500);
      final n = issueCount;
      return _json({
        'token': 'adai_plain_$n',
        'id': 'id_$n',
        'prefix': 'adai_pref$n',
        'expiresAt': _iso(const Duration(days: 90)),
      });
    }
    if (path.contains('/api/v1/auth/tokens/') && req.method == 'DELETE') {
      final target = req.url.pathSegments.last;
      order.add('revoke:$target');
      revoked.add(target);
      return _json({'ok': true});
    }
    return _json({'error': 'unexpected ${req.method} $path'}, status: 500);
  }
}

void main() {
  // 需要 binding 才能安装 method channel 的假实现（否则 TestDefaultBinaryMessengerBinding.instance 不可用）
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<String> order;
  late _Container container;
  late _Backend backend;
  late ApiService api;

  ShareExtensionStatus statusOf({String? token, String? id, String? expiresAt}) =>
      ShareExtensionStatus(
        available: true,
        hasToken: token != null,
        id: id,
        expiresAt: expiresAt,
      );

  setUp(() {
    order = [];
    container = _Container(order);
    backend = _Backend(order);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
            const MethodChannel('adai/share'), container.handle);
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    api = ApiService(
      baseUrl: 'http://test.local',
      userId: 'adai',
      token: 'session-token',
      client: MockClient(backend.handle),
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('adai/share'), null);
    debugDefaultTargetPlatformOverride = null;
  });

  group('needsIssue（纯判断，坏了会让用户「明明登录了却用不了分享」）', () {
    test('容器里没有钥匙 → 要签', () {
      expect(ShareTokenKeeper.needsIssue(statusOf()), isTrue);
    });

    test('有钥匙且还很新（90 天）→ 不签', () {
      expect(
        ShareTokenKeeper.needsIssue(
            statusOf(token: 't', id: 'i', expiresAt: _iso(const Duration(days: 90)))),
        isFalse,
      );
    });

    test('有钥匙但只剩 3 天（< 7 天阈值）→ 换新的', () {
      expect(
        ShareTokenKeeper.needsIssue(
            statusOf(token: 't', id: 'i', expiresAt: _iso(const Duration(days: 3)))),
        isTrue,
      );
    });

    test('已过期 → 换新的', () {
      expect(
        ShareTokenKeeper.needsIssue(
            statusOf(token: 't', id: 'i', expiresAt: _iso(const Duration(days: -1)))),
        isTrue,
      );
    });

    test('老数据没记到期时间 → 补签一次（顺带把到期时间带上，此后能正常判断）', () {
      expect(
        ShareTokenKeeper.needsIssue(statusOf(token: 't', id: 'i')),
        isTrue,
      );
    });

    test('到期时间解析不出来 → 当老数据补签', () {
      expect(
        ShareTokenKeeper.needsIssue(
            statusOf(token: 't', id: 'i', expiresAt: '不是时间')),
        isTrue,
      );
    });
  });

  group('ensure（编排，关键是顺序与静默）', () {
    test('全新安装：没有钥匙 → 签一把、写进容器；没有旧的可撤', () async {
      await ShareTokenKeeper.ensure(api);

      expect(backend.issueCount, 1);
      expect(backend.revoked, isEmpty);
      expect(container.token, 'adai_plain_1');
      expect(container.id, 'id_1');
      expect(container.expiresAt, isNotNull, reason: '到期时间必须一起写进去，否则续期判断永远不准');
      expect(order, ['status', 'issue', 'save']);
    });

    test('快到期：先写新的进容器，**再**撤销旧的（顺序反了会两把同时失效）', () async {
      container.token = 'old-token';
      container.id = 'id_old';
      container.expiresAt = _iso(const Duration(days: 2));

      await ShareTokenKeeper.ensure(api);

      expect(container.token, 'adai_plain_1');
      expect(backend.revoked, ['id_old']);
      expect(
        order,
        ['status', 'issue', 'save', 'revoke:id_old'],
        reason: 'save 必须在 revoke 之前',
      );
    });

    test('写容器失败 → 绝不撤销旧钥匙（宁可多一把，不能两把都没）', () async {
      container.token = 'old-token';
      container.id = 'id_old';
      container.expiresAt = _iso(const Duration(days: 2));
      container.saveSucceeds = false;

      await ShareTokenKeeper.ensure(api);

      expect(backend.issueCount, 1, reason: '会尝试签发');
      expect(backend.revoked, isEmpty, reason: '没写进去就不能撤旧的');
      expect(container.id, 'id_old', reason: '旧钥匙必须还在');
    });

    test('钥匙还很新 → 一个请求都不发（幂等，不做无用功）', () async {
      container.token = 'fresh';
      container.id = 'id_fresh';
      container.expiresAt = _iso(const Duration(days: 80));

      await ShareTokenKeeper.ensure(api);

      expect(backend.issueCount, 0);
      expect(order, ['status']);
    });

    test('容器本身不可用（Entitlements 没配好）→ 不折腾签发', () async {
      container.available = false;

      await ShareTokenKeeper.ensure(api);

      expect(backend.issueCount, 0);
      expect(order, ['status']);
    });

    test('后端签发失败 → 静默，不抛异常（绝不能拖垮登录）', () async {
      backend.issueFails = true;

      await expectLater(ShareTokenKeeper.ensure(api), completes);
      expect(container.token, isNull);
      expect(backend.revoked, isEmpty);
    });

    test('容器调用抛异常 → 同样静默', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel('adai/share'),
              (call) async => throw PlatformException(code: 'boom'));

      await expectLater(ShareTokenKeeper.ensure(api), completes);
      expect(backend.issueCount, 0);
    });

    test('非 iOS（web / PWA / Android）→ 一律不动作', () async {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;

      await ShareTokenKeeper.ensure(api);

      expect(backend.issueCount, 0);
      expect(order, isEmpty);
    });

    test('并发调用只签一把（登录钩子与启动钩子会几乎同时触发）', () async {
      await Future.wait([
        ShareTokenKeeper.ensure(api),
        ShareTokenKeeper.ensure(api),
        ShareTokenKeeper.ensure(api),
      ]);

      expect(backend.issueCount, 1, reason: '防重入生效，不该白造令牌');
    });
  });
}
