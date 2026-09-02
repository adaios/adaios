import 'package:web/web.dart' as web;

/// Web 端：新标签页打开外部链接（管局备案号链接）。
void openUrl(String url) {
  web.window.open(url, '_blank');
}
