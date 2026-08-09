import 'package:web/web.dart' as web;

/// 当前用户 ID 持久化 — Web 实现（localStorage）。
///
/// dart2wasm 下 shared_preferences 的 web 插件不自动注册（MissingPluginException
/// 启动白屏），改用 package:web 直接读写 localStorage（纯同步 js_interop，无插件通道）。
/// 只存"上次所选账号"这一偏好，业务数据都在后端 `data/{userId}/`，与本地缓存无关。
class UserStore {
  static const _keyUserId = 'current_user_id';

  /// 读取上次所选账号；无记录返回 null。
  static Future<String?> loadUserId() async =>
      web.window.localStorage.getItem(_keyUserId);

  /// 保存所选账号。
  static Future<void> saveUserId(String userId) async =>
      web.window.localStorage.setItem(_keyUserId, userId);

  /// 清除记录（切换回首次选号用）。
  static Future<void> clearUserId() async =>
      web.window.localStorage.removeItem(_keyUserId);
}
