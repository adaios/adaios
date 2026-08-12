import 'package:flutter/material.dart';
import 'root_keys.dart';
import 'theme/app_theme.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'services/user_store.dart';
import 'main_page.dart';
import 'pages/account_select_page.dart';
import 'pages/launcher_page.dart';
import 'pages/profile_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // v1.0.0 多账号：URL ?userId= 优先 > 持久化上次账号 > 无记录（首屏选号）
  final urlUserId = resolveUserId();
  final savedUserId = await UserStore.loadUserId();
  final selection = resolveUserSelection(urlUserId: urlUserId, savedUserId: savedUserId);
  runApp(RootApp(userId: selection.userId, needsSelect: selection.needsSelect));
}

/// 解析最终生效 userId 与是否强制首屏选号（v1.0.0 多账号）。
/// 规则：URL `?userId=` 优先 > 持久化上次账号 > 无记录（首屏选号）。
/// REVIEW #182：持久化 'default' 视为无效（default 账号已随数据迁移移除），
/// 避免绕过选号流程的请求落到空 data/default/ 分支静默分裂数据。
/// REVIEW #177：抽成纯函数，让「持久化降级」可单测。
@visibleForTesting
({String userId, bool needsSelect}) resolveUserSelection({
  required String urlUserId,
  String? savedUserId,
}) {
  final hasUrlUserId = urlUserId != 'default';
  final effectiveSaved = (savedUserId != null && savedUserId != 'default') ? savedUserId : null;
  return (
    userId: hasUrlUserId ? urlUserId : (effectiveSaved ?? 'default'),
    needsSelect: !hasUrlUserId && effectiveSaved == null,
  );
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

class RootApp extends StatefulWidget {
  const RootApp({super.key, this.userId = 'default', this.needsSelect = false, this.apiFactory});

  /// 初始用户 ID（URL query / 持久化解析结果）。
  final String userId;

  /// 首次进入无账号记录 → 显示首屏选号页。
  final bool needsSelect;

  /// 测试注入：按 userId 构造 ApiService（默认真实实现）。
  /// REVIEW #177：注入 factory 让「切换账号重建整树 / 双击防重入」链路可测
  /// （复用 MockClient 基建，RootApp/DualWorldShell 不再硬编码真实 ApiService）。
  final ApiService Function(String userId)? apiFactory;

  @override
  State<RootApp> createState() => _RootAppState();
}

class _RootAppState extends State<RootApp> {
  late String _userId = widget.userId;
  late bool _needsSelect = widget.needsSelect;

  /// MaterialApp 的 Navigator key：切换账号的 push/pop 必须走它。
  /// State 的 context 在 MaterialApp 之外，`Navigator.of(context)` 会返回 null 崩溃
  /// （v1.0.0 切换账号必现，`Null check operator used on a null value`）。
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  /// 选定账号：持久化 + 清 URL userId + 重建整树（换 ValueKey → DualWorldShell/ApiService 重建，缓存清空）。
  Future<void> _selectAccount(String userId) async {
    if (_handlingSelect) return; // REVIEW #185：防快速双击重复 pop/push
    _handlingSelect = true;
    try {
      try {
        await UserStore.saveUserId(userId);
      } catch (_) {
        // 持久化失败不阻塞切换（记住功能降级）
      }
      // REVIEW #186：切换后清 URL ?userId=，刷新后持久化成为唯一决定源
      try {
        await UserStore.clearUrlUserId();
      } catch (_) {}
      if (!mounted) return;
      setState(() {
        _userId = userId;
        _needsSelect = false;
      });
    } finally {
      _handlingSelect = false;
    }
  }

  /// REVIEW #185：选号回调防重入（双击在 pop 动画期间重复触发）。
  bool _handlingSelect = false;

  /// 按 userId 构造 ApiService（优先测试注入的 factory）。
  ApiService _apiFor(String userId) =>
      widget.apiFactory?.call(userId) ?? ApiService(userId: userId);

  /// 切换账号：push 选号页，选择后回传重建。
  void _openAccountSelect() {
    final nav = _navigatorKey.currentState;
    if (nav == null) return;
    nav.push(MaterialPageRoute(
      builder: (_) => AccountSelectPage(
        api: _apiFor(_userId),
        currentUserId: _userId,
        onSelect: (uid) {
          // #204：守卫包住闭包整体（含 nav.pop()），快速双击不把 home 也 pop 掉。
          // 原 #185 守卫只在 _selectAccount 内，双击第二次短路后 pop 落到栈空黑屏。
          if (_handlingSelect) return;
          _selectAccount(uid);
          nav.pop();
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
      // REVIEW #246：根 messenger 挂在 MaterialApp 层——MainPage 切 World B 被 dispose
      // 后，上传失败/成功提示仍能弹出，不静默丢。
      scaffoldMessengerKey: rootScaffoldMessengerKey,
      builder: (context, child) => DefaultTextStyle(
        style: const TextStyle(fontFamilyFallback: ['Noto Color Emoji']),
        child: child!,
      ),
      home: _needsSelect
          ? AccountSelectPage(
              api: _apiFor(_userId),
              onSelect: _selectAccount,
            )
          : DualWorldShell(
              key: ValueKey(_userId),
              userId: _userId,
              apiFactory: widget.apiFactory,
              onSwitchAccount: _openAccountSelect,
            ),
    );
  }
}

/// 双主页壳 — World A (Feed) 与 World B (Launcher) 无缝切换。
class DualWorldShell extends StatefulWidget {
  const DualWorldShell({super.key, this.userId = 'default', this.onSwitchAccount, this.apiFactory});

  /// 当前用户 ID（透传给 ApiService 与 MainPage）。
  final String userId;

  /// 切换账号回调（RootApp 提供：push 选号页 → 选定后重建整树）。
  final VoidCallback? onSwitchAccount;

  /// 测试注入：按 userId 构造 ApiService（默认真实实现）。
  final ApiService Function(String userId)? apiFactory;

  @override
  State<DualWorldShell> createState() => _DualWorldShellState();
}

class _DualWorldShellState extends State<DualWorldShell> {
  // REVIEW #177：切换账号重建整树（ValueKey 换 userId）→ 新 State 新建 ApiService，
  // 缓存（tags/timeline/memory）随之清空，不跨账号串数据。
  late final ApiService _api = widget.apiFactory?.call(widget.userId) ?? ApiService(userId: widget.userId);
  bool _showWorldB = false;
  String? _filterTag;

  /// Feed 刷新信号（MD1）：世界切回 Feed 时递增，MainPage 监听后重载。
  final ValueNotifier<int> _feedRefreshTick = ValueNotifier<int>(0);

  /// 切世界拖拽的起点 Y（#16：用于排除底部输入框区域）。
  double? _dragStartY;

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
        onVerticalDragStart: (d) => _dragStartY = d.localPosition.dy,
        onVerticalDragEnd: (d) {
          // #16：输入框区域（屏幕底部约 140px，输入栏 + 附件预览）不响应切世界——
          // 否则打字上滑误触切走 World，MainPage 重建导致输入草稿丢失。
          if ((_dragStartY ?? 0) >= (MediaQuery.of(context).size.height - 140)) return;
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
                onSwitchAccount: widget.onSwitchAccount,
              )
            : MainPage(
                key: ValueKey('worldA-${_filterTag ?? ''}'),
                userId: widget.userId,
                // REVIEW #177：传入壳层共享 _api（测试注入 factory 时 MainPage 也走 MockClient；
                // 生产与 LauncherPage 共享同一 ApiService 实例与缓存，避免双实例缓存分裂）
                api: _api,
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
