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

  /// 清除 URL 中的 `?userId=`（REVIEW #186，与 web 实现签名一致）。
  /// 原生无 URL 概念，空实现。
  static Future<void> clearUrlUserId() async {}
}
