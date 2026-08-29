/// SSE 客户端共享协议件（平台实现继承 [SseClientBase]，异常与行解析器两端共用）。
///
/// 协议（api-spec §ask-stream）：响应为 SSE，仅解析 `data:` 行（值已去前缀与空白）；
/// `data: [DONE]` 哨兵原样回调，由调用方判定。
library;

/// SSE 客户端抽象：POST 并逐条回调 `data:` 载荷。
abstract class SseClientBase {
  /// 非 200 → 抛 [SseHttpException]（带状态码与响应文本，调用方转人话/降级）。
  Future<void> post(
    Uri url, {
    required Map<String, String> headers,
    required Object body,
    required void Function(String data) onData,
  });
}

/// SSE HTTP 非 200 异常（保留原始响应体，调用方按需透出人话）。
class SseHttpException implements Exception {
  SseHttpException(this.statusCode, this.body);

  final int statusCode;
  final String body;

  @override
  String toString() => 'SSE HTTP $statusCode: $body';
}

/// 服务端 error 事件（message 已是人话，toString 直接透出避免 "Exception: " 前缀）。
class SseServerException implements Exception {
  SseServerException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// SSE 文本流 → data 行（跨 chunk 行拼接；仅取 `data:` 前缀行）。
class SseLineParser {
  SseLineParser(this.onData);

  final void Function(String data) onData;
  final StringBuffer _carry = StringBuffer();

  void add(String text) {
    _carry.write(text);
    var pending = _carry.toString();
    var nl = pending.indexOf('\n');
    while (nl >= 0) {
      _dispatch(pending.substring(0, nl));
      pending = pending.substring(nl + 1);
      nl = pending.indexOf('\n');
    }
    _carry
      ..clear()
      ..write(pending);
  }

  void close() {
    if (_carry.isNotEmpty) _dispatch(_carry.toString());
    _carry.clear();
  }

  void _dispatch(String line) {
    final t = line.trim();
    if (t.startsWith('data:')) onData(t.substring(5).trim());
  }
}
