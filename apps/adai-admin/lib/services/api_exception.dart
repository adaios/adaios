/// API 调用异常 — 统一携带给用户可读的错误信息。
///
/// 后端错误响应通常是 `{"error": "..."}` 或纯文本，
/// [ApiService] 解析后放入 [message]，页面可直接用 SnackBar 展示。
class ApiException implements Exception {
  const ApiException(this.message, {this.statusCode});

  /// 用户可读错误信息。
  final String message;

  /// HTTP 状态码（无状态码时为 null，如网络异常）。
  final int? statusCode;

  @override
  String toString() =>
      statusCode != null ? 'ApiException($statusCode): $message' : 'ApiException: $message';
}
