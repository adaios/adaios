import 'package:flutter/material.dart';
import 'theme/app_theme.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'main_page.dart';
import 'pages/launcher_page.dart';
import 'pages/profile_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(RootApp(userId: resolveUserId()));
}

/// 从入口跳转的 query 参数解析当前用户 ID（`?userId=xxx`）。
String resolveUserId() => resolveUserIdFrom(Uri.base);

/// 可测实现：[uri] 的 query 参数提取 userId。
/// 非法（含路径注入字符）/缺失 → 'default'（兼容直接打开 URL 与移动端）。
@visibleForTesting
String resolveUserIdFrom(Uri uri) {
  const fallback = 'default';
  final q = uri.queryParameters['userId'];
  if (q == null) return fallback;
  return RegExp(r'^[a-zA-Z0-9_-]+$').hasMatch(q) ? q : fallback;
}

class RootApp extends StatelessWidget {
  const RootApp({super.key, required this.userId});

  /// 当前用户 ID（入口选择后通过 query 传入）。
  final String userId;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '阿呆阿呆',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.dark,
      builder: (context, child) => DefaultTextStyle(
        style: const TextStyle(fontFamilyFallback: ['Noto Color Emoji']),
        child: child!,
      ),
      home: DualWorldShell(userId: userId),
    );
  }
}

/// 双主页壳 — World A (Feed) 与 World B (Launcher) 无缝切换。
class DualWorldShell extends StatefulWidget {
  const DualWorldShell({super.key, this.userId = 'default'});

  /// 当前用户 ID（透传给 ApiService 与 MainPage）。
  final String userId;

  @override
  State<DualWorldShell> createState() => _DualWorldShellState();
}

class _DualWorldShellState extends State<DualWorldShell> {
  late final ApiService _api = ApiService(userId: widget.userId);
  bool _showWorldB = false;
  String? _filterTag;

  /// Feed 刷新信号（MD1）：世界切回 Feed 时递增，MainPage 监听后重载。
  final ValueNotifier<int> _feedRefreshTick = ValueNotifier<int>(0);

  void _toggleWorld() {
    final wasWorldB = _showWorldB;
    setState(() => _showWorldB = !_showWorldB);
    // 返回 Feed（World A）时触发刷新——覆盖 adai-admin 记忆重建后 Feed 陈旧（MD1）
    if (wasWorldB) {
      _feedRefreshTick.value++;
    }
  }

  void _clearFilter() {
    setState(() => _filterTag = null);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: GestureDetector(
        onVerticalDragEnd: (d) {
          // 全局空白快速拖拽：高速度阈值避免干扰正常滚动
          if (d.primaryVelocity != null) {
            if (d.primaryVelocity! < -400 && !_showWorldB) {
              _toggleWorld();
            } else if (d.primaryVelocity! > 400 && _showWorldB) {
              _toggleWorld();
            }
          }
        },
        behavior: HitTestBehavior.translucent,
        child: AnimatedSwitcher(
        duration: const Duration(milliseconds: 250),
        switchInCurve: Curves.easeOut,
        switchOutCurve: Curves.easeIn,
        child: _showWorldB
            ? LauncherPage(
                key: const ValueKey('worldB'),
                api: _api,
                onNavigateBack: _toggleWorld,
              )
            : MainPage(
                key: ValueKey('worldA-${_filterTag ?? ''}'),
                userId: widget.userId,
                onPullUp: _toggleWorld,
                filterTag: _filterTag,
                refreshTick: _feedRefreshTick,
                onClearFilter: _clearFilter,
                onProfileTap: () {
                  Navigator.push(context, MaterialPageRoute(
                    builder: (_) => Scaffold(
                      backgroundColor: AppColors.darkBg,
                      body: ProfilePage(api: _api),
                    ),
                  ));
                },
              ),
        ),
      ),
    );
  }
}
