/// 打开外部链接（跨平台条件导入）。
///
/// Web 端（dart.library.js_interop 可用）用 package:web 的 window.open；
/// VM/测试环境走 stub（no-op），避免 flutter test 报错。
/// 条件导出语法：默认 URI 在前，if 分支覆盖。
library;

export 'open_url_stub.dart'
    if (dart.library.js_interop) 'open_url_web.dart';
