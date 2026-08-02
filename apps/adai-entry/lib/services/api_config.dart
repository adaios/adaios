/// AdaiOS 入口 — 目标地址配置。
///
/// 全部可用 `--dart-define` 覆盖（生产构建时指向服务器）：
/// ```bash
/// flutter build web \
///   --dart-define=API_BASE_URL=http://49.235.37.220:8080 \
///   --dart-define=APP_URL=http://49.235.37.220:8081 \
///   --dart-define=ADMIN_URL=http://49.235.37.220:8083
/// ```
class ApiConfig {
  ApiConfig._();

  /// 后端地址（GET /api/v1/accounts）。
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );

  /// adai-app 地址（普通用户跳转目标）。
  static const String appUrl = String.fromEnvironment(
    'APP_URL',
    defaultValue: 'http://localhost:8081',
  );

  /// adai-admin 地址（admin 账号跳转目标）。
  static const String adminUrl = String.fromEnvironment(
    'ADMIN_URL',
    defaultValue: 'http://localhost:8083',
  );
}
