// 行情 zip 带进度上传——公共类型（跨平台 stub/web 共享，MD17 体验增强 2026-09-07）。
library;

import 'dart:typed_data';

import 'package:http/http.dart' as http;

/// 上传进度回调：已发送字节 / 总字节。
typedef TdxProgressCallback = void Function(int sentBytes, int totalBytes);

/// 带进度上传执行函数签名：POST multipart（字段名 file）到 [url]，[headers] 需含
/// Authorization；按 [onProgress] 逐块上报进度。返回服务端原始响应（2xx/非 2xx 都由
/// 调用方经统一 _check 处理）。
typedef TdxProgressUploadFn = Future<http.Response> Function({
  required String url,
  required Map<String, String> headers,
  required Uint8List bytes,
  required String filename,
  required TdxProgressCallback onProgress,
});
