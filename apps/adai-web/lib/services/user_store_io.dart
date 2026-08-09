import 'package:shared_preferences/shared_preferences.dart';

/// 当前用户 ID 持久化 — 非 Web 实现（Android/iOS/VM 测试）。
class UserStore {
  static const _keyUserId = 'current_user_id';

  /// 读取上次所选账号；无记录返回 null。
  static Future<String?> loadUserId() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_keyUserId);
  }

  /// 保存所选账号。
  static Future<void> saveUserId(String userId) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_keyUserId, userId);
  }

  /// 清除记录（切换回首次选号用）。
  static Future<void> clearUserId() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_keyUserId);
  }
}
