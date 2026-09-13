import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'api_service.dart';

/// 通知授权状态（与 iOS 侧 `getStatus.authorization` 取值一一对应）。
enum PushAuthorization {
  /// 尚未询问过（首启会弹系统授权框）。
  notDetermined,

  /// 已授权（完整横幅/声音/角标）。
  authorized,

  /// 临时授权（只进通知中心，不弹横幅）——本项目未主动申请，留作识别。
  provisional,

  /// App Clip 类临时授权——留作识别。
  ephemeral,

  /// 用户明确拒绝（需去系统设置里改）。
  denied,

  /// 非 iOS 原生环境（Web / Android / 桌面）或原生通道不可用。
  unavailable,
}

/// 推送链路状态快照。
@immutable
class PushStatus {
  final PushAuthorization authorization;

  /// APNs deviceToken（未注册成功为 null）。
  final String? token;

  /// `sandbox` / `production`（由 iOS 侧读包内 entitlements 得出）。
  final String environment;

  /// 原生侧是否已拿到 token。
  final bool registered;

  /// 原生侧最近一次错误（注册失败 / 权限申请失败），用于把「为什么收不到」显示出来。
  final String? lastError;

  /// 冷启动由通知点击唤起时，原生侧缓存下来的通知类型（`adai-<type>`）。
  final String? pendingTap;

  const PushStatus({
    required this.authorization,
    this.token,
    this.environment = 'sandbox',
    this.registered = false,
    this.lastError,
    this.pendingTap,
  });

  /// 非 iOS 原生环境：本服务整体不介入（Web/Android 不弹系统通知）。
  static const PushStatus unavailable = PushStatus(authorization: PushAuthorization.unavailable);

  /// 是否已获得系统许可（临时授权也算——通知能进中心）。
  bool get granted =>
      authorization == PushAuthorization.authorized ||
      authorization == PushAuthorization.provisional ||
      authorization == PushAuthorization.ephemeral;

  bool get isUnavailable => authorization == PushAuthorization.unavailable;

  factory PushStatus.fromNative(Map<dynamic, dynamic>? raw) {
    if (raw == null) return unavailable;
    return PushStatus(
      authorization: _parseAuthorization(raw['authorization']?.toString()),
      token: (raw['token'] as String?)?.trim().isEmpty ?? true ? null : (raw['token'] as String).trim(),
      environment: (raw['environment'] as String?)?.trim().isNotEmpty ?? false
          ? (raw['environment'] as String).trim()
          : 'sandbox',
      registered: raw['registered'] == true,
      lastError: raw['lastError'] as String?,
      pendingTap: raw['pendingTap'] as String?,
    );
  }

  static PushAuthorization _parseAuthorization(String? raw) {
    switch (raw) {
      case 'authorized':
        return PushAuthorization.authorized;
      case 'provisional':
        return PushAuthorization.provisional;
      case 'ephemeral':
        return PushAuthorization.ephemeral;
      case 'denied':
        return PushAuthorization.denied;
      case 'notDetermined':
        return PushAuthorization.notDetermined;
      default:
        return PushAuthorization.unavailable;
    }
  }
}

/// PushService — 阿呆自有 iOS 推送接入（RFC 20260913 APNs 批）。
///
/// 职责边界：**本服务只管「把 deviceToken 交给后端」**，不产生任何推送内容——
/// 内容由后端既有的推送生产方（盘前/止损/行情异动/收盘小结/复习提醒）决定，
/// 经 `ApnsPushChannel` 直连 APNs。所以这里不做插件门控、不判断交易时段、不缓存消息。
///
/// 三端安全：只在 **iOS 原生**生效（`_supported`）。Web（含 PWA）与 Android
/// 一律返回 [PushStatus.unavailable] 并静默跳过——App 的 web 构建还要部署到
/// `adaiadai.com/m/`，绝不能因为通知能力把 PWA 弄崩。所有平台调用都包了
/// try/catch（原生通道缺失、旧版本 App 无原生实现时 → 降级而非抛错）。
class PushService {
  PushService._();

  static const MethodChannel _channel = MethodChannel('adai/push');

  /// 仅 iOS 原生：Web 走浏览器通知（本项目未接），Android 未接。
  static bool get supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;

  static ApiService? _api;
  static void Function(String type)? _onTap;
  static bool _handlerInstalled = false;
  static PushStatus _status = PushStatus.unavailable;

  /// 最近一次状态（UI 可读，不必再问原生）。
  static PushStatus get status => _status;

  @visibleForTesting
  static void resetForTest() {
    _api = null;
    _onTap = null;
    _handlerInstalled = false;
    _status = PushStatus.unavailable;
  }

  /// 启动接入：申请权限（若还没问过）→ 拿 token → 上报后端。
  ///
  /// 调用时机由壳层决定（登录后、拿到带 token 的 [ApiService] 之后）。
  /// [onTap] 是用户点击通知时的回调，参数为通知类型（`adai-<type>` → 传 `<type>`）。
  /// 返回状态供 UI 决定是否提示「通知没开，去设置里开」。
  static Future<PushStatus> init({
    required ApiService api,
    void Function(String type)? onTap,
  }) async {
    _api = api;
    if (onTap != null) _onTap = onTap;
    if (!supported) {
      _status = PushStatus.unavailable;
      return _status;
    }
    _installHandler();

    try {
      var raw = await _invokeStatus();
      // 首次：先征求许可（已决定过时系统直接返回既有结论，不会重复弹窗）
      if (raw.authorization == PushAuthorization.notDetermined) {
        await _channel.invokeMethod<bool>('requestPermission');
        raw = await _invokeStatus();
      }
      _status = raw;
      await _uploadIfPossible(_status);
      // 冷启动由通知点击唤起：原生侧会把点击缓存到 pendingTap，这里补处理
      final tap = _status.pendingTap;
      if (tap != null && tap.isNotEmpty) _dispatchTap(tap);
      return _status;
    } on MissingPluginException {
      // 旧版本 App（原生侧还没这个通道）/ 非 iOS 环境：静默降级
      _status = PushStatus.unavailable;
      return _status;
    } on PlatformException catch (e) {
      _status = PushStatus(
        authorization: PushAuthorization.unavailable,
        environment: 'sandbox',
        lastError: e.message,
      );
      return _status;
    }
  }

  /// 打开发送「去系统设置开启通知」——用户点过「不允许」后的唯一补救路径。
  static Future<bool> openSettings() async {
    if (!supported) return false;
    try {
      return await _channel.invokeMethod<bool>('openSettings') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 登出时注销本机（避免下一个账号的推送发到这台设备）。
  static Future<void> unregister({required ApiService api}) async {
    final token = _status.token;
    _api = null;
    _status = PushStatus.unavailable;
    if (token == null || token.isEmpty) return;
    try {
      await api.unregisterPushDevice(token);
    } catch (_) {
      // 注销失败不阻断登出流程（下次登录会重新上报覆盖）
    }
  }

  static Future<PushStatus> _invokeStatus() async {
    final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('getStatus');
    return PushStatus.fromNative(raw);
  }

  /// 上报 token（token 为空或未授权则跳过）。
  ///
  /// 刻意**不上报 bundleId**：apns-topic 以后端配置（`adai.push.apns.bundle-id`）为单一事实源，
  /// 客户端再传一份等于两个真相源，不一致时会在 APNs 侧变成
  /// `DeviceTokenNotForTopic`（且完全看不出是拼错 bundle id）。`label` 只是给人看的备注。
  static Future<void> _uploadIfPossible(PushStatus status) async {
    final api = _api;
    final token = status.token;
    if (api == null || token == null || token.isEmpty) return;
    try {
      await api.registerPushDevice(
        token: token,
        environment: status.environment,
        label: 'iPhone',
      );
    } catch (_) {
      // 上报失败不抛给 UI：下次 onToken / 下次启动会重试
    }
  }

  /// 装一次原生回调：token 到达 / 注册失败 / 通知点击。
  static void _installHandler() {
    if (_handlerInstalled) return;
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onToken':
          final args = call.arguments;
          if (args is Map) {
            final status = PushStatus.fromNative(args);
            _status = status;
            await _uploadIfPossible(status);
          }
          break;
        case 'onRegisterFailed':
          _status = PushStatus(
            authorization: _status.authorization,
            token: _status.token,
            environment: _status.environment,
            registered: false,
            lastError: call.arguments?.toString(),
          );
          break;
        case 'onNotificationTap':
          _dispatchTap(call.arguments?.toString());
          break;
      }
      return null;
    });
    _handlerInstalled = true;
  }

  /// 通知类型归一：原生侧给的是 `adai-<type>`，交给 UI 时去掉前缀。
  static void _dispatchTap(String? raw) {
    if (raw == null || raw.isEmpty) return;
    final type = raw.startsWith('adai-') ? raw.substring('adai-'.length) : raw;
    _onTap?.call(type);
  }
}
