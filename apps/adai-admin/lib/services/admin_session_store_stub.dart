/// 管理端登录 token 持久化 — VM / 测试环境实现（内存态，不依赖浏览器 localStorage）。
///
/// flutter test 在 Dart VM 运行（无 dart.library.js_interop）→ 走本文件；
/// 测试用 [resetForTest] 清理内存态，避免用例间串 token。
class AdminSessionStore {
  static String? _token;

  static Future<String?> loadToken() async => _token;

  static Future<void> saveToken(String token) async {
    _token = token;
  }

  static Future<void> clearToken() async {
    _token = null;
  }

  /// 测试专用：清空内存态。
  static void resetForTest() {
    _token = null;
  }
}
