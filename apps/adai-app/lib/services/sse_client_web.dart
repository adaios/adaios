import 'dart:convert';
import 'dart:js_interop';

import 'package:http/http.dart' as http;
import 'package:web/web.dart' as web;

import 'sse_client_common.dart';

/// Flutter Web 实现：`package:web` fetch streaming——dart http 的浏览器
/// 实现（XHR）不暴露渐进响应，只有 fetch 的 ReadableStream 能边到边读。
class SseClient extends SseClientBase {
  /// [httpClient] 只为与 `sse_client_io.dart` **保持构造签名一致**而存在
  /// （`sse_client.dart` 的条件导出要求两份实现 API 面完全相同，否则目标平台编译失败）。
  ///
  /// Web 上**不使用**它：浏览器 fetch 的渐进响应能力来自 ReadableStream，
  /// `package:http` 的浏览器实现（XHR）拿不到，注入也无效。测试注入口只在 IO/VM 侧有意义。
  ///
  /// 2026-09-13（PWA 装机批）修复：本文件原缺该参数 → 参数只在 io 侧存在 →
  /// `flutter build web` 编译失败（`No named parameter with the name 'httpClient'`）。
  /// 因为此前 web 目标从未构建过（PWA 是第一次），这个断链一直没被任何测试或门禁发现。
  SseClient({http.Client? httpClient});

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
