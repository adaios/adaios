import 'package:web/web.dart' as web;

/// 当前用户 ID 持久化 — Web 实现（localStorage）。
///
/// dart2wasm 下 shared_preferences 的 web 插件不自动注册（MissingPluginException
/// 启动白屏），改用 package:web 直接读写 localStorage（纯同步 js_interop，无插件通道）。
/// 只存"上次所选账号"这一偏好，业务数据都在后端 `data/{userId}/`，与本地缓存无关。
class UserStore {
  static const _keyUserId = 'current_user_id';

  /// 读取上次所选账号；无记录返回 null。
  static Future<String?> loadUserId() async {
    try {
      return web.window.localStorage.getItem(_keyUserId);
    } catch (e) {
      // ignore: avoid_print
      print('UserStore loadUserId localStorage failed: $e');
      return null; // localStorage 不可用（隐私模式/禁用）→ 走首屏选号
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

  /// 清除记录（切换回首次选号用）。
  static Future<void> clearUserId() async {
    try {
      web.window.localStorage.removeItem(_keyUserId);
    } catch (e) {
      // ignore: avoid_print
      print('UserStore clearUserId localStorage failed: $e');
    }
  }
}
