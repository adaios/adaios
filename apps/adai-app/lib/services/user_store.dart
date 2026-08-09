// 当前用户 ID 持久化（v1.0.0 多账号选号：记住上次账号，重启直达）。
//
// 条件导出：Web（dart2wasm）→ user_store_web.dart（package:web localStorage），
// 其余平台 → user_store_io.dart（shared_preferences 原生插件）。
// 背景：dart2wasm 下 shared_preferences 的 web 插件不自动注册，
// 走 MethodChannel 抛 MissingPluginException 导致启动白屏（v1.0.0 wasm 修复）。
export 'user_store_io.dart' if (dart.library.js_interop) 'user_store_web.dart';
