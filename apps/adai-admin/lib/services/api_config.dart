/// API 环境配置。
///
/// 通过 --dart-define 在打包时切换：
/// ```bash
/// # 开发（默认 localhost）
/// flutter run -d chrome
///
/// # 生产/其他后端
/// flutter run -d chrome --dart-define=API_BASE_URL=https://api.adaiadai.com
/// ```
class ApiConfig {
  /// 后端 API 基础地址。
  /// 默认 localhost:8080，可通过 --dart-define 覆盖。
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );
}
