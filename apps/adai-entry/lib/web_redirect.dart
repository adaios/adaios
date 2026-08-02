/// 浏览器重定向 — 条件导入分发器。
///
/// - Web 构建 → [web_redirect_web.dart]（@JS 绑定 `window.location.assign`）
/// - 测试 / Dart VM → [web_redirect_stub.dart]（占位，测试注入 onNavigate 不走它）
///
/// 不用 `package:web`：其 `helpers/http.dart` 依赖的 `jsify` 扩展与当前
/// Dart SDK 的 `dart:js_interop` 不兼容（import 即编译错误）。
library;

export 'web_redirect_stub.dart'
    if (dart.library.js_interop) 'web_redirect_web.dart';
