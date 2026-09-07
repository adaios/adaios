// Web 平台实现：XMLHttpRequest.upload.onprogress 拿真实上传进度（MD17 体验增强）。
//
// 为什么不直接用 http.MultipartRequest：它底层在浏览器走 XHR/fetch 但不暴露
// upload.onprogress；要进度只能裸 XHR。multipart body 手动拼装（boundary + 头 + 字节），
// Content-Type 由调用方头里的 boundary 决定（见 _buildMultipart）。
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:web/web.dart' as web;

import 'api_exception.dart';
import 'tdx_upload_types.dart';

/// 平台支持真实上传进度（Web）。
TdxProgressUploadFn? tdxProgressUpload() => _uploadWithProgress;

/// 带真实进度的 multipart POST（字段名 file）。进度回调按浏览器 upload 事件节流触发。
Future<http.Response> _uploadWithProgress({
  required String url,
  required Map<String, String> headers,
  required Uint8List bytes,
  required String filename,
  required TdxProgressCallback onProgress,
}) async {
  final boundary = 'AdaiTdx${DateTime.now().microsecondsSinceEpoch}';
  final body = _buildMultipart(bytes, filename, boundary);

  final xhr = web.XMLHttpRequest();
  final completer = Completer<http.Response>();
  xhr.open('POST', url);
  // 显式设 multipart Content-Type（含 boundary）；其余头（Authorization 等）原样带。
  for (final e in headers.entries) {
    if (e.key.toLowerCase() == 'content-type') continue;
    xhr.setRequestHeader(e.key, e.value);
  }
  xhr.setRequestHeader('Content-Type', 'multipart/form-data; boundary=$boundary');
  xhr.timeout = 10 * 60 * 1000; // 10 分钟，与原长超时 client 一致

  xhr.upload.onprogress = ((web.ProgressEvent e) {
    if (e.lengthComputable && e.total > 0) {
      onProgress(e.loaded, e.total);
    }
  }).toJS;

  xhr.onload = ((web.Event _) {
    final text = xhr.responseText;
    // _body 走 utf8.decode(bodyBytes)：用 Response.bytes 显式按 UTF-8 编码响应体，
    // 避免 Response(String) 按 content-type 缺 charset 时按 latin1 编码导致中文乱码。
    completer.complete(http.Response.bytes(
      utf8.encode(text),
      xhr.status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    ));
  }).toJS;
  xhr.onerror = ((web.Event _) {
    if (!completer.isCompleted) {
      completer.completeError(ApiException('网络错误：上传中断，请检查网络后重试'));
    }
  }).toJS;
  xhr.ontimeout = ((web.Event _) {
    if (!completer.isCompleted) {
      completer.completeError(ApiException('上传超时（10 分钟），请检查网络或减小数据包后重试'));
    }
  }).toJS;
  xhr.onabort = ((web.Event _) {
    if (!completer.isCompleted) {
      completer.completeError(ApiException('上传已取消'));
    }
  }).toJS;

  xhr.send(body.toJS);
  return completer.future;
}

/// 拼 multipart/form-data body：固定字段名 `file`（与后端 tdx-import 契约一致）。
Uint8List _buildMultipart(Uint8List fileBytes, String filename, String boundary) {
  final head = utf8.encode('--$boundary\r\n'
      'Content-Disposition: form-data; name="file"; filename="${_escape(filename)}"\r\n'
      'Content-Type: application/zip\r\n'
      '\r\n');
  final tail = utf8.encode('\r\n--$boundary--\r\n');
  final out = BytesBuilder(copy: false)
    ..add(head)
    ..add(fileBytes)
    ..add(tail);
  return out.takeBytes();
}

/// 文件名进入 Content-Disposition 头前转义引号与换行（防头注入）。
String _escape(String s) =>
    s.replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('\r', ' ').replaceAll('\n', ' ');
