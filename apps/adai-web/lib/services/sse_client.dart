// 条件导入：VM/移动端走 IO 实现（`http.Client.send` 流式），浏览器走
// fetch streaming（dart http 的 XHR 实现不暴露渐进响应，只有 fetch 的
// ReadableStream 能边到边读）。异常与 SSE 行解析器共享于 sse_client_common。
export 'sse_client_common.dart' show SseClientBase, SseHttpException, SseServerException;
export 'sse_client_io.dart' if (dart.library.js_interop) 'sse_client_web.dart';
