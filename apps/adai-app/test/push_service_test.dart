import 'dart:convert';

import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/push_service.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

// ────────────────────────────────────────────────────────────────
// PushService 测试（RFC 20260913 APNs 批）
//
// 覆盖：非 iOS 环境不碰原生通道（PWA/Android 不能被通知能力弄崩）、
// 首启申请权限的调用顺序、token 上报后端的请求体、拒绝授权不静默、
// 原生回调 onToken/onNotificationTap（含冷启动 pendingTap 补投与类型去前缀）、
// 旧版本 App 无原生通道时降级不抛、登出注销。
//
// 原生侧（Swift）不在此覆盖——那是 AppDelegate 的职责，真机验证见交付说明。
// ────────────────────────────────────────────────────────────────

const MethodChannel _channel = MethodChannel('adai/push');

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

const String _token = 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789';

Map<String, Object?> _status({
  String authorization = 'authorized',
  String? token = _token,
  String environment = 'sandbox',
  bool? registered,
  String? lastError,
  String? pendingTap,
}) =>
    {
      'authorization': authorization,
      'token': token,
      'environment': environment,
      'registered': registered ?? token != null,
      'lastError': lastError,
      'pendingTap': pendingTap,
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<MethodCall> calls;
  late List<Map<String, dynamic>> uploaded;

  /// 装原生假通道：按 call.method 返回，getStatus 可按次数给不同结果。
  void installChannel({
    required Object? Function(String method, int nth) respond,
  }) {
    final counters = <String, int>{};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      final nth = (counters[call.method] ?? 0) + 1;
      counters[call.method] = nth;
      return respond(call.method, nth);
    });
  }

  /// 模拟原生 → Dart 回调。
  Future<void> emitFromNative(String method, Object? args) async {
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      'adai/push',
      const StandardMethodCodec().encodeMethodCall(MethodCall(method, args)),
      (_) {},
    );
    // 让 handler 里的 await 排空
    await Future<void>.delayed(Duration.zero);
  }

  ApiService apiWith(MockClientHandler handler) =>
      ApiService(baseUrl: 'http://test', client: MockClient(handler));

  /// 假后端：记录 push/devices 的请求体，其余 404。
  ApiService pushRecordingApi() {
    return apiWith((req) async {
      if (req.url.path.endsWith('/push/devices') && req.method == 'POST') {
        uploaded.add(jsonDecode(req.body) as Map<String, dynamic>);
        return _json({'token': _token});
      }
      if (req.url.path.contains('/push/devices/') && req.method == 'DELETE') {
        uploaded.add({'deleted': req.url.pathSegments.last});
        return _json({'removed': true});
      }
      return _json({'error': 'not found'}, status: 404);
    });
  }

  setUp(() {
    calls = [];
    uploaded = [];
    PushService.resetForTest();
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  // ── 平台守卫（PWA/Android 安全）──

  test('非 iOS 环境：整体降级为 unavailable，不触碰原生通道', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    installChannel(respond: (m, n) => _status());

    final status = await PushService.init(api: pushRecordingApi());

    expect(status.authorization, PushAuthorization.unavailable);
    expect(status.isUnavailable, isTrue);
    expect(calls, isEmpty, reason: 'Android/Web 不该调用 adai/push 通道');
    expect(uploaded, isEmpty);
  });

  test('旧版本 App（原生无此通道）：MissingPluginException 降级不抛', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null); // 通道不存在

    final status = await PushService.init(api: pushRecordingApi());

    expect(status.authorization, PushAuthorization.unavailable);
  });

  // ── 首启授权流程 ──

  test('未询问过 → 先 requestPermission 再复查状态，并把 token 上报后端', () async {
    installChannel(respond: (m, n) {
      if (m == 'getStatus') {
        return n == 1 ? _status(authorization: 'notDetermined', token: null) : _status();
      }
      if (m == 'requestPermission') return true;
      return null;
    });

    final status = await PushService.init(api: pushRecordingApi());

    expect(calls.map((c) => c.method).toList(),
        ['getStatus', 'requestPermission', 'getStatus'],
        reason: '必须先问权限、拿到结论后再取 token（否则拿不到弹窗后的结果）');
    expect(status.granted, isTrue);
    expect(status.token, _token);
    expect(uploaded, hasLength(1));
    expect(uploaded.first['token'], _token);
    expect(uploaded.first['platform'], 'ios');
    expect(uploaded.first['environment'], 'sandbox');
    expect(uploaded.first.containsKey('bundleId'), isFalse,
        reason: 'apns-topic 以后端配置为单一事实源，客户端不重复上报');
  });

  test('已询问过 → 不重复弹权限框（直接取状态）', () async {
    installChannel(respond: (m, n) => m == 'getStatus' ? _status() : null);

    await PushService.init(api: pushRecordingApi());

    expect(calls.map((c) => c.method), ['getStatus'],
        reason: 'authorized 时不该再调 requestPermission');
  });

  test('production 环境原样上报（TestFlight/上架后 json 不该仍写 sandbox）', () async {
    installChannel(respond: (m, n) => _status(environment: 'production'));

    await PushService.init(api: pushRecordingApi());

    expect(uploaded.first['environment'], 'production');
  });

  test('用户拒绝授权 → 返回 denied 且不上报 token', () async {
    installChannel(respond: (m, n) {
      if (m == 'getStatus') {
        return n == 1 ? _status(authorization: 'notDetermined', token: null) : _status(authorization: 'denied', token: null);
      }
      if (m == 'requestPermission') return false;
      return null;
    });

    final status = await PushService.init(api: pushRecordingApi());

    expect(status.authorization, PushAuthorization.denied);
    expect(status.granted, isFalse);
    expect(uploaded, isEmpty, reason: '没授权就没有 token，不该发上报请求');
  });

  test('授权通过但注册失败 → lastError 透出（fail-visible，不静默）', () async {
    installChannel(respond: (m, n) => _status(
        authorization: 'authorized', token: null, registered: false,
        lastError: 'no valid aps-environment entitlement'));

    final status = await PushService.init(api: pushRecordingApi());

    expect(status.lastError, 'no valid aps-environment entitlement');
    expect(uploaded, isEmpty);
  });

  test('后端上报失败不抛给 UI（下次启动/onToken 会重试）', () async {
    installChannel(respond: (m, n) => _status());
    final failing = apiWith((req) async => _json({'error': 'boom'}, status: 500));

    final status = await PushService.init(api: failing);

    expect(status.token, _token, reason: '上报失败不影响本地状态');
  });

  // ── 原生回调 ──

  test('onToken（原生异步送达）→ 立即上报', () async {
    installChannel(respond: (m, n) => _status(authorization: 'authorized', token: null));
    await PushService.init(api: pushRecordingApi());
    expect(uploaded, isEmpty, reason: '初次拿不到 token');

    await emitFromNative('onToken', {'token': _token, 'environment': 'sandbox'});

    expect(uploaded, hasLength(1));
    expect(uploaded.first['token'], _token);
  });

  test('onNotificationTap → 回调类型去掉 adai- 前缀', () async {
    installChannel(respond: (m, n) => _status());
    final taps = <String>[];
    await PushService.init(api: pushRecordingApi(), onTap: taps.add);

    await emitFromNative('onNotificationTap', 'adai-close-summary');

    expect(taps, ['close-summary']);
  });

  test('onNotificationTap 无前缀（原生用标题兜底）→ 原样传递', () async {
    installChannel(respond: (m, n) => _status());
    final taps = <String>[];
    await PushService.init(api: pushRecordingApi(), onTap: taps.add);

    await emitFromNative('onNotificationTap', '收盘小结');

    expect(taps, ['收盘小结']);
  });

  test('冷启动由通知唤起 → init 时补投 pendingTap', () async {
    installChannel(respond: (m, n) => _status(pendingTap: 'adai-stop-loss'));
    final taps = <String>[];
    await PushService.init(api: pushRecordingApi(), onTap: taps.add);

    expect(taps, ['stop-loss'], reason: '点通知冷启动时原生侧先收到点击、通道还没建好，状态里带回补投');
  });

  test('onRegisterFailed → lastError 记录下来', () async {
    installChannel(respond: (m, n) => _status(authorization: 'authorized', token: null));
    await PushService.init(api: pushRecordingApi());

    await emitFromNative('onRegisterFailed', '网络不可达');

    expect(PushService.status.lastError, '网络不可达');
    expect(PushService.status.registered, isFalse);
  });

  // ── 其它入口 ──

  test('openSettings 走原生（拒绝授权后的补救入口）', () async {
    installChannel(respond: (m, n) => m == 'openSettings' ? true : _status());

    expect(await PushService.openSettings(), isTrue);
    expect(calls.map((c) => c.method), contains('openSettings'));
  });

  test('非 iOS 时 openSettings 不发调用', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    installChannel(respond: (m, n) => true);

    expect(await PushService.openSettings(), isFalse);
    expect(calls, isEmpty);
  });

  test('登出注销本机设备（换账号不串推送）', () async {
    installChannel(respond: (m, n) => _status());
    final api = pushRecordingApi();
    await PushService.init(api: api);

    await PushService.unregister(api: api);

    expect(uploaded.last['deleted'], _token);
    expect(PushService.status.token, isNull, reason: '注销后本地状态清空，避免下个账号复用旧 token');
  });

  test('没有 token 时登出不发注销请求', () async {
    installChannel(respond: (m, n) => _status(authorization: 'denied', token: null));
    final api = pushRecordingApi();
    await PushService.init(api: api);

    await PushService.unregister(api: api);

    expect(uploaded, isEmpty);
  });

  // ── PushStatus 纯解析 ──

  test('PushStatus.fromNative：字段缺失/畸形一律回落到安全值', () {
    expect(PushStatus.fromNative(null).authorization, PushAuthorization.unavailable);

    final s = PushStatus.fromNative({'authorization': '什么鬼', 'token': '   ', 'environment': ''});
    expect(s.authorization, PushAuthorization.unavailable);
    expect(s.token, isNull, reason: '空白 token 视为没有');
    expect(s.environment, 'sandbox', reason: '环境缺失回落 sandbox（侧载路径的真实环境）');

    expect(PushStatus.fromNative({'authorization': 'provisional'}).granted, isTrue,
        reason: '临时授权也算拿到许可（通知能进中心）');
  });
}
