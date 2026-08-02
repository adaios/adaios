/// 非 Web 平台（测试 / Dart VM）占位实现。
///
/// widget test 通过注入 `onNavigate` 捕获跳转目标，不会走到这里；
/// 真走到则视为误用。
void navigateTo(String url) {
  throw UnsupportedError('navigateTo 仅支持 Web 平台');
}
