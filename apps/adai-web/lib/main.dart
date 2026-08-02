import 'package:flutter/material.dart';
import 'theme/app_theme.dart';
import 'desktop_shell.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(AdaiWebApp(userId: resolveUserId()));
}

/// 从入口跳转的 query 参数解析当前用户 ID（`?userId=xxx`）。
String resolveUserId() => resolveUserIdFrom(Uri.base);

/// 可测实现：[uri] 的 query 参数提取 userId。
/// 非法（含路径注入字符）/缺失 → 'default'。
@visibleForTesting
String resolveUserIdFrom(Uri uri) {
  const fallback = 'default';
  final q = uri.queryParameters['userId'];
  if (q == null) return fallback;
  return RegExp(r'^[a-zA-Z0-9_-]+$').hasMatch(q) ? q : fallback;
}

class AdaiWebApp extends StatelessWidget {
  /// 当前用户 ID（入口选择后通过 query 传入）。
  final String userId;

  const AdaiWebApp({super.key, required this.userId});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '阿呆阿呆',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.dark,
      home: DesktopShell(userId: userId),
    );
  }
}
