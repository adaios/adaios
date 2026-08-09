import 'package:shared_preferences/shared_preferences.dart';

/// 当前用户 ID 持久化 — 非 Web 实现（Android/iOS/VM 测试）。
class UserStore {
  static const _keyUserId = 'current_user_id';

  /// 读取上次所选账号；无记录返回 null。
  static Future<String?> loadUserId() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(_keyUserId);
    } catch (_) {
      return null; // 持久化不可用 → 走首屏选号
    }
  }

  /// 保存所选账号。
  static Future<void> saveUserId(String userId) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_keyUserId, userId);
    } catch (_) {
      // 持久化不可用 → 切换仍生效（仅丢失记住功能）
    }
  }

  /// 清除记录（切换回首次选号用）。
  static Future<void> clearUserId() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_keyUserId);
    } catch (_) {}
  }
}
