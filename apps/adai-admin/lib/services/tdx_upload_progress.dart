// 行情 zip 带进度上传（MD17 体验增强 2026-09-07）。
//
// 为什么需要条件导出：admin 部署为 Flutter Web，浏览器上传要拿「真实进度」只能走
// XMLHttpRequest 的 upload.onprogress（http 包的 MultipartRequest 封装不暴露它）；
// 而 VM（flutter test）/原生平台无该通道 → stub 返回 null，调用方回落普通 multipart。
//
// 条件导出语法：默认 URI（stub）在前，if 分支（web）覆盖——照 open_url.dart 惯例。
library;

export 'tdx_upload_progress_stub.dart'
    if (dart.library.js_interop) 'tdx_upload_progress_web.dart';
