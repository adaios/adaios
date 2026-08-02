import 'dart:js_interop';

@JS('window.location.assign')
external void jsLocationAssign(String url);

/// 重定向当前页面到 [url]（同窗口整页跳转）。
void navigateTo(String url) => jsLocationAssign(url);
