import 'package:flutter/material.dart';
import 'theme/app_theme.dart';
import 'services/api_service.dart';
import 'services/user_store.dart';
import 'pages/account_select_page.dart';
import 'desktop_shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // v1.0.0 多账号：URL ?userId= 优先 > 持久化上次账号 > 无记录（首屏选号）
  final urlUserId = resolveUserId();
  final savedUserId = await UserStore.loadUserId();
  final hasUrlUserId = urlUserId != 'default';
  final userId = hasUrlUserId ? urlUserId : (savedUserId ?? 'default');
  runApp(AdaiWebApp(userId: userId, needsSelect: !hasUrlUserId && savedUserId == null));
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

class AdaiWebApp extends StatefulWidget {
  const AdaiWebApp({super.key, this.userId = 'default', this.needsSelect = false});

  /// 初始用户 ID（URL query / 持久化解析结果）。
  final String userId;

  /// 首次进入无账号记录 → 显示首屏选号页。
  final bool needsSelect;

  @override
  State<AdaiWebApp> createState() => _AdaiWebAppState();
}

class _AdaiWebAppState extends State<AdaiWebApp> {
  late String _userId = widget.userId;
  late bool _needsSelect = widget.needsSelect;

  /// MaterialApp 的 Navigator key：切换账号的 push/pop 必须走它。
  /// State 的 context 在 MaterialApp 之外，`Navigator.of(context)` 会返回 null 崩溃
  /// （v1.0.0 切换账号必现，`Null check operator used on a null value`）。
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  /// 选定账号：持久化 + 重建整树（换 ValueKey → DesktopShell/ApiService 重建，缓存清空）。
  Future<void> _selectAccount(String userId) async {
    try {
      await UserStore.saveUserId(userId);
    } catch (_) {
      // 持久化失败不阻塞切换（记住功能降级）
    }
    if (!mounted) return;
    setState(() {
      _userId = userId;
      _needsSelect = false;
    });
  }

  /// 切换账号：push 选号页，选择后回传重建。
  void _openAccountSelect() {
    final nav = _navigatorKey.currentState;
    if (nav == null) return;
    nav.push(MaterialPageRoute(
      builder: (_) => AccountSelectPage(
        api: ApiService(userId: _userId),
        currentUserId: _userId,
        onSelect: (uid) {
          nav.pop();
          _selectAccount(uid);
        },
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '阿呆阿呆',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.dark,
      navigatorKey: _navigatorKey,
      home: _needsSelect
          ? AccountSelectPage(
              api: ApiService(userId: _userId),
              onSelect: _selectAccount,
            )
          : DesktopShell(
              key: ValueKey(_userId),
              userId: _userId,
              onSwitchAccount: _openAccountSelect,
            ),
    );
  }
}
