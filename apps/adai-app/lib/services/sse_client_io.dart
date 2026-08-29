import 'dart:convert';

import 'package:http/http.dart' as http;

import 'sse_client_common.dart';

/// IO 平台（VM 测试 / iOS / Android）实现：`http.Client.send` 流式读取。
class SseClient extends SseClientBase {
  SseClient({http.Client? httpClient}) : _client = httpClient;

  final http.Client? _client;

  @override
  Future<void> post(
    Uri url, {
    required Map<String, String> headers,
    required Object body,
    required void Function(String data) onData,
  }) async {
    final req = http.Request('POST', url)
      ..headers.addAll(headers)
      ..body = jsonEncode(body);
    final resp = await (_client ?? http.Client()).send(req);
    if (resp.statusCode >= 400) {
      final err = await resp.stream.bytesToString();
      throw SseHttpException(resp.statusCode, err);
    }
    final parser = SseLineParser(onData);
    try {
      await for (final text in resp.stream.transform(utf8.decoder)) {
        parser.add(text);
      }
    } finally {
      parser.close();
    }
  }
}
