// 管理端登录 token 持久化（RFC 20260901-auth-login / REVIEW #178）。
//
// 注意：admin 与产品 web 同源部署（adaiadai.com 的 / 与 /admin/），localStorage 按
// origin 共享——token 键必须与管理端专用（admin_auth_token），不能与产品端 auth_token
// 混用，否则产品/控制台登录互相顶掉。
//
// 条件导出：Web（dart2wasm / js_interop）→ admin_session_store_web.dart（package:web
// localStorage）；VM/测试 → stub（内存态，不依赖浏览器）。
library;

export 'admin_session_store_stub.dart'
    if (dart.library.js_interop) 'admin_session_store_web.dart';
