import 'package:web/web.dart' as web;

/// 当前用户 ID + 登录 token 持久化 — Web 实现（localStorage）。
///
/// dart2wasm 下 shared_preferences 的 web 插件不自动注册（MissingPluginException
/// 启动白屏），改用 package:web 直接读写 localStorage（纯同步 js_interop，无插件通道）。
/// 业务数据都在后端 `data/{userId}/`，本地只存偏好与会话 token。
class UserStore {
  static const _keyUserId = 'current_user_id';
  static const _keyToken = 'auth_token';

  /// 读取上次所选账号；无记录返回 null。
  static Future<String?> loadUserId() async {
    try {
      return web.window.localStorage.getItem(_keyUserId);
    } catch (e) {
      // ignore: avoid_print
      print('UserStore loadUserId localStorage failed: $e');
      return null; // localStorage 不可用（隐私模式/禁用）→ 走登录
    }
  }

  /// 保存所选账号。
  static Future<void> saveUserId(String userId) async {
    try {
      web.window.localStorage.setItem(_keyUserId, userId);
    } catch (e) {
      // localStorage 不可用 → 不持久化，切换仍生效（仅丢失记住功能）
      // ignore: avoid_print
      print('UserStore saveUserId localStorage failed: $e');
    }
  }

  /// 读取登录 token；无记录返回 null。
  static Future<String?> loadToken() async {
    try {
      return web.window.localStorage.getItem(_keyToken);
    } catch (e) {
      // ignore: avoid_print
      print('UserStore loadToken localStorage failed: $e');
      return null;
    }
  }

  /// 保存登录 token。
  static Future<void> saveToken(String token) async {
    try {
      web.window.localStorage.setItem(_keyToken, token);
    } catch (e) {
      // ignore: avoid_print
      print('UserStore saveToken localStorage failed: $e');
    }
  }

  /// 清除登录 token（登出 / 会话失效）。
  static Future<void> clearToken() async {
    try {
      web.window.localStorage.removeItem(_keyToken);
    } catch (e) {
      // ignore: avoid_print
      print('UserStore clearToken localStorage failed: $e');
    }
  }

  /// 清除 URL 中的 `?userId=`（REVIEW #186）：
  /// 切换账号后调用，让持久化的上次账号成为刷新后的唯一决定源，
  /// 避免"带 ?userId=X 进入 → 切换至 Y → 刷新又回 X"的覆盖。
  static Future<void> clearUrlUserId() async {
    try {
      final uri = Uri.base;
      final params = Map<String, String>.from(uri.queryParameters);
      if (!params.containsKey('userId')) return;
      params.remove('userId');
      final newUri = uri.replace(queryParameters: params);
      web.window.history.replaceState(null, '', newUri.toString());
    } catch (e) {
      // ignore: avoid_print
      print('UserStore clearUrlUserId failed: $e');
    }
  }
}
