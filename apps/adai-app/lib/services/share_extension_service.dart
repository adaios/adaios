import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 分享扩展的凭据状态（原生侧 `ShareBridgeHandler.status()` 的 Dart 投影）。
@immutable
class ShareExtensionStatus {
  /// App Group 容器是否真的可用（Entitlements / 签名配好了没）。
  ///
  /// **必须与 [hasToken] 分开看**：容器不可用 = 「这件事在这台机器上就不成立」，
  /// 容器可用但没令牌 = 「只差你点一次『发一把』」。两种状态的人话完全不同，
  /// 混成一句「还没就绪」会让用户不知道该干什么。
  final bool available;

  /// 共享容器里现在有没有那把限权令牌。
  final bool hasToken;

  /// 容器里令牌的 id（令牌哈希）；撤销时用来判断「扩展用的是不是这一把」。
  final String? id;

  /// 令牌写入时间（Unix 秒）。
  final double? savedAt;

  /// 令牌到期时间（ISO8601 字符串，RFC 20260915 自动续期批）。
  /// `null` = 容器里的老数据没记过（升级上来的），调用方应**补签一次**把它带上。
  final String? expiresAt;

  /// App Group id（排查用，界面不展示）。
  final String? appGroup;

  const ShareExtensionStatus({
    required this.available,
    required this.hasToken,
    this.id,
    this.savedAt,
    this.expiresAt,
    this.appGroup,
  });

  const ShareExtensionStatus.unavailable()
      : available = false,
        hasToken = false,
        id = null,
        savedAt = null,
        expiresAt = null,
        appGroup = null;

  @override
  String toString() =>
      'ShareExtensionStatus(available: $available, hasToken: $hasToken, id: $id)';
}

/// ShareExtensionService — 「分享面板里的阿呆阿呆」用的那把钥匙（RFC 20260914）。
///
/// **它在整条链路里的位置**：iOS 分享扩展是**独立进程**，读不到 App 的
/// `shared_preferences`。但扩展要在无人值守下把链接提交给后端，就必须有凭据。
/// 令牌明文只在**签发那一刻**的响应里出现一次（后端只存 SHA-256），过了就还原不出来——
/// 所以只能由 App 在签发成功时顺手把它搬进 App Groups 共享容器（`adai/share` 通道）。
///
/// 设计取舍：
/// - **只 iOS 原生生效**：Web/PWA/Android 没有这个扩展，一律不注册、不触碰原生通道
///   （app 的 web 构建还要部署到 `adaiadai.com/m/`）。所有调用包 try/catch，
///   失败返回安全默认值——**平台没有这个能力**与**能力在但写失败**，对 UI 是两种说法。
/// - **返回 bool 而不是抛异常**：调用方（签发弹窗）要的是「接上了没有」，
///   拿这个布尔值决定说哪句人话；异常会把「能力缺失」渲染成崩溃。
class ShareExtensionService {
  ShareExtensionService._();

  static const MethodChannel _channel = MethodChannel('adai/share');

  /// 仅 iOS 原生。Android/Web 上分享扩展这条链路不存在（Android 侧按需另开，见 RFC §3.6）。
  static bool get supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;

  /// 把令牌明文写进共享容器。返回 `true` 才算真的接上了——
  /// 原生侧做了**回读校验**，写进去读不出来（Entitlements 没配好）会返回 false，
  /// 不靠「调用没报错」下结论。
  static Future<bool> saveToken({
    required String token,
    String? id,
    String? expiresAt,
  }) async {
    if (!supported || token.trim().isEmpty) return false;
    try {
      final ok = await _channel.invokeMethod<bool>('saveToken', {
        'token': token,
        'id': id,
        'expiresAt': expiresAt,
      });
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 清空共享容器里的令牌（撤销 / 登出 / 换账号）。**不留孤儿钥匙**。
  static Future<bool> clearToken() async {
    if (!supported) return false;
    try {
      final ok = await _channel.invokeMethod<bool>('clearToken');
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 查共享容器状态（弹窗用它决定说「已就绪」还是「还没接上」）。
  static Future<ShareExtensionStatus> status() async {
    if (!supported) return const ShareExtensionStatus.unavailable();
    try {
      final raw = await _channel.invokeMethod<dynamic>('status');
      if (raw is! Map) return const ShareExtensionStatus.unavailable();
      return ShareExtensionStatus(
        available: raw['available'] == true,
        hasToken: raw['hasToken'] == true,
        id: raw['id']?.toString(),
        savedAt: raw['savedAt'] is num
            ? (raw['savedAt'] as num).toDouble()
            : null,
        expiresAt: raw['expiresAt']?.toString(),
        appGroup: raw['appGroup']?.toString(),
      );
    } catch (_) {
      return const ShareExtensionStatus.unavailable();
    }
  }
}
