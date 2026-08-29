import 'dart:convert';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'sse_client_common.dart';

/// Flutter Web 实现：`package:web` fetch streaming——dart http 的浏览器
/// 实现（XHR）不暴露渐进响应，只有 fetch 的 ReadableStream 能边到边读。
class SseClient extends SseClientBase {
  SseClient();

  @override
  Future<void> post(
    Uri url, {
    required Map<String, String> headers,
    required Object body,
    required void Function(String data) onData,
  }) async {
    final urlStr = url.toString();
    final init = web.RequestInit();
    init.method = 'POST';
    init.headers = headers.jsify() as web.HeadersInit;
    init.body = jsonEncode(body).toJS;
    final resp = await web.window.fetch(urlStr.toJS, init).toDart;
    if (resp.status >= 400) {
      // text() 的 toDart 得 JSString，再 toDart 转 Dart String
      final err = (await resp.text().toDart).toDart;
      throw SseHttpException(resp.status, err);
    }
    final stream = resp.body;
    if (stream == null) {
      throw SseHttpException(resp.status, '响应无 body（fetch streaming 不受支持）');
    }
    final reader = stream.getReader() as web.ReadableStreamDefaultReader;
    final parser = SseLineParser(onData);
    // 跨 chunk 的 UTF-8 多字节字符用流式解码器拼接，避免中文被 chunk 边界切碎
    final byteSink = utf8.decoder.startChunkedConversion(_StringSink(parser.add));
    try {
      while (true) {
        final result = await reader.read().toDart;
        if (result.done) break;
        final value = result.value;
        if (value != null) {
          byteSink.add((value as JSUint8Array).toDart);
        }
      }
    } finally {
      byteSink.close();
      parser.close();
    }
  }
}

/// utf8.decoder 流式解码的字符串出口。
class _StringSink implements Sink<String> {
  _StringSink(this._onString);

  final void Function(String) _onString;

  @override
  void add(String data) => _onString(data);

  @override
  void close() {}
}
