/// API 环境配置。
///
/// 通过 --dart-define 在打包时切换：
/// ```bash
/// # 开发（默认 localhost）
/// flutter run -d chrome
///
/// # 手机测试（连电脑后端）
/// flutter run -d android --dart-define=API_BASE_URL=http://192.168.0.109:8080
/// ```
class ApiConfig {
  /// 后端 API 基础地址。
  /// 默认 localhost:8080，可通过 --dart-define 覆盖。
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );
}
