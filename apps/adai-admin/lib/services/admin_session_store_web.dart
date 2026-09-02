import 'package:web/web.dart' as web;

/// 管理端登录 token 持久化 — Web 实现（localStorage）。
///
/// 键名 `admin_auth_token` 与产品端 `auth_token` 区分（同源部署互不顶掉）。
/// localStorage 不可用（隐私模式/禁用）→ 静默降级为不持久化（本次会话仍可用）。
class AdminSessionStore {
  static const _keyToken = 'admin_auth_token';

  /// 读取登录 token；无记录返回 null。
  static Future<String?> loadToken() async {
    try {
      return web.window.localStorage.getItem(_keyToken);
    } catch (e) {
      // ignore: avoid_print
      print('AdminSessionStore loadToken failed: $e');
      return null;
    }
  }

  /// 保存登录 token。
  static Future<void> saveToken(String token) async {
    try {
      web.window.localStorage.setItem(_keyToken, token);
    } catch (e) {
      // ignore: avoid_print
      print('AdminSessionStore saveToken failed: $e');
    }
  }

  /// 清除登录 token（登出 / 会话失效）。
  static Future<void> clearToken() async {
    try {
      web.window.localStorage.removeItem(_keyToken);
    } catch (e) {
      // ignore: avoid_print
      print('AdminSessionStore clearToken failed: $e');
    }
  }
}
