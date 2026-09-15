import 'dart:io' show Platform;

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 当前用户 ID + 登录 token 持久化 — 非 Web 实现（Android/iOS/VM 测试）。
///
/// **token 落系统安全存储**（RFC 20260914 L2 登录体验批）：iOS 走 Keychain、
/// Android 走 EncryptedSharedPreferences。此前与 userId 一起放在 shared_preferences
/// （iOS 上就是**明文的 NSUserDefaults plist**）——那把「30 天滑动有效、能读写全部个人
/// 数据」的钥匙就那样明文躺在手机里。老值会在首次读取时**自动迁移**进钥匙串，
/// 迁移后旧明文立即清除。
///
/// 「记住我」关闭时（[saveToken] 的 `remember: false`）token **只留内存**：
/// 本次会话有效，重启需重新登录（适合临时用别人手机的场景）。
class UserStore {
  static const _keyUserId = 'current_user_id';
  static const _keyToken = 'auth_token';

  /// 钥匙串通道。iOS：`first_unlock_this_device`（设备首次解锁后可读、**不随备份迁到别的
  /// 设备**，兼顾后台推送与「换机不带走会话」）；Android：加密偏好存储。
  static const _secure = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
    iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device),
  );

  /// 未开启「记住我」时的内存副本（仅进程内有效）。
  static String? _memoryToken;

  /// 测试态（`flutter test` 的 VM 里没有钥匙串插件通道）：直接走内存，
  /// 既不挂起也不污染真实钥匙串。
  ///
  /// ⚠️ 用**环境变量**判定而不是 `bool.fromEnvironment('FLUTTER_TEST')`：后者在 `flutter test`
  /// 下并不总是 true，而通道调用一旦挂起，widget 测试里的 `await` 会**死等**
  /// （FakeAsync 不推进真实 Timer，`.timeout` 兜底也救不回来）。
  static final bool _testMode = Platform.environment['FLUTTER_TEST'] == 'true';

  /// 钥匙串调用的兜底超时：通道异常（系统卡住/插件未就绪）时最多等这么久，
  /// 超时按「读不到」处理 → 回登录页，绝不把整个启动流程吊死。
  static const Duration _secureTimeout = Duration(seconds: 3);

  /// 读取上次所选账号；无记录返回 null。
  static Future<String?> loadUserId() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(_keyUserId);
    } catch (_) {
      return null; // 持久化不可用 → 走登录
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

  /// 读取登录 token：内存优先（本次会话），其次钥匙串（记住过的设备）。
  /// 老版本存在 shared_preferences 里的明文 token 会在此**迁移**进钥匙串并清除旧值。
  static Future<String?> loadToken() async {
    if (_memoryToken != null) return _memoryToken;
    if (_testMode) return null;
    try {
      final stored = await _secure.read(key: _keyToken).timeout(_secureTimeout);
      if (stored != null && stored.isNotEmpty) return stored;
    } catch (_) {
      // 钥匙串不可用（系统异常/超时）→ 继续尝试老路径迁移
    }
    return _migrateLegacyToken();
  }

  /// 保存登录 token。
  ///
  /// [remember] = 登录页「记住这台设备」开关：
  /// - `true` → 写钥匙串，下次启动可免密（配合 Face ID 本地门禁）；
  /// - `false` → 只留内存，并清掉钥匙串与历史明文残留。
  static Future<void> saveToken(String token, {bool remember = true}) async {
    _memoryToken = token;
    if (_testMode) return;
    if (!remember) {
      try {
        await _secure.delete(key: _keyToken).timeout(_secureTimeout);
      } catch (_) {
        // 忽略：没写进去过，删不掉也无妨
      }
      await _clearLegacyPrefsToken();
      return;
    }
    try {
      await _secure.write(key: _keyToken, value: token).timeout(_secureTimeout);
      // 迁移完成 → 明文旧值不再保留（防「以为换了钥匙串、其实明文还在」）
      await _clearLegacyPrefsToken();
    } catch (_) {
      // 钥匙串失败不阻塞登录：本次会话仍有效（重启后需重新登录）
    }
  }

  /// 清除登录 token（登出 / 会话失效）。
  static Future<void> clearToken() async {
    _memoryToken = null;
    if (_testMode) return;
    try {
      await _secure.delete(key: _keyToken).timeout(_secureTimeout);
    } catch (_) {
      // 忽略
    }
    await _clearLegacyPrefsToken();
  }

  /// 老版本（shared_preferences 明文）token 迁移：读一次 → 写钥匙串 → 清旧值。
  static Future<String?> _migrateLegacyToken() async {
    if (_testMode) return null;
    try {
      final prefs = await SharedPreferences.getInstance();
      final legacy = prefs.getString(_keyToken);
      if (legacy == null || legacy.isEmpty) return null;
      await _secure.write(key: _keyToken, value: legacy).timeout(_secureTimeout);
      await prefs.remove(_keyToken);
      return legacy;
    } catch (_) {
      return null;
    }
  }

  static Future<void> _clearLegacyPrefsToken() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (prefs.containsKey(_keyToken)) {
        await prefs.remove(_keyToken);
      }
    } catch (_) {
      // 忽略
    }
  }

  /// 清除 URL 中的 `?userId=`（REVIEW #186，与 web 实现签名一致）。
  /// 原生无 URL 概念，空实现。
  static Future<void> clearUrlUserId() async {}
}
