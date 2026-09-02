import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'root_keys.dart';
import 'theme/app_theme.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'services/user_store.dart';
import 'main_page.dart';
import 'pages/account_select_page.dart';
import 'pages/launcher_page.dart';
import 'pages/login_page.dart';
import 'pages/profile_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // RFC 20260901-auth-login：启动加载持久化会话（token + 上次账号），无 token → 登录页
  final token = await UserStore.loadToken();
  final savedUserId = await UserStore.loadUserId();
  runApp(RootApp(
    userId: savedUserId ?? 'default',
    initialToken: token,
    forceLogin: true,
  ));
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
    // T1.4（RFC 20260814）：无有效 userId 时返回空串（尚无活动用户），不再回退 'default'——
    // 防止选号前任何请求携带已迁移移除的 default 账号；needsSelect 强制首屏选号。
    userId: hasUrlUserId ? urlUserId : (effectiveSaved ?? ''),
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
  const RootApp({
    super.key,
    this.userId = 'default',
    this.needsSelect = false,
    this.apiFactory,
    this.initialToken,
    this.forceLogin = false,
  });

  /// 初始用户 ID（URL query / 持久化解析结果）。
  final String userId;

  /// 首次进入无账号记录 → 显示首屏选号页（旧多账号流程，测试用）。
  final bool needsSelect;

  /// 测试注入：按 userId 构造 ApiService（默认真实实现）。
  /// REVIEW #177：注入 factory 让「切换账号重建整树 / 双击防重入」链路可测
  /// （复用 MockClient 基建，RootApp/DualWorldShell 不再硬编码真实 ApiService）。
  final ApiService Function(String userId)? apiFactory;

  /// 持久化登录 token（RFC 20260901-auth-login；可能已失效，启动时校验）。
  final String? initialToken;

  /// 强制登录：无 token 时显示登录页（生产 main() 传 true；旧测试不传保持旧行为）。
  final bool forceLogin;

  @override
  State<RootApp> createState() => _RootAppState();
}

class _RootAppState extends State<RootApp> {
  late String _userId = widget.userId;
  late bool _needsSelect = widget.needsSelect;

  /// 当前会话 token；null = 未登录。
  String? _token;

  /// 启动校验持久化 token 期间显示 loading。
  bool _booting = false;

  /// MaterialApp 的 Navigator key：切换账号的 push/pop 必须走它。
  /// State 的 context 在 MaterialApp 之外，`Navigator.of(context)` 会返回 null 崩溃
  /// （v1.0.0 切换账号必现，`Null check operator used on a null value`）。
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  @override
  void initState() {
    super.initState();
    if (widget.initialToken != null) {
      _token = widget.initialToken;
      _booting = true;
      _validateStoredSession();
    }
  }

  /// 启动校验：持久化 token 调 /auth/me；失效（401）→ 清 token 回登录页。
  Future<void> _validateStoredSession() async {
    final api = _apiFor(_userId);
    try {
      final me = await api.authMe();
      if (!mounted) return;
      setState(() {
        _userId = me['userId'] as String? ?? _userId;
        _booting = false;
      });
    } catch (e) {
      if (!mounted) return;
      // 401 = 会话失效；其他错误（网络）先按未登录处理（安全默认，不静默进主界面）
      await UserStore.clearToken();
      if (!mounted) return;
      setState(() {
        _token = null;
        _booting = false;
      });
    }
  }

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

  /// 按 userId 构造 ApiService（测试 factory 优先；生产带 token 的真实实现）。
  ApiService _apiFor(String userId) {
    if (widget.apiFactory != null) {
      return widget.apiFactory!(userId);
    }
    return ApiService(userId: userId, token: _token, onUnauthorized: _handleUnauthorized);
  }

  /// 全局 401：清 token → 回登录页。
  Future<void> _handleUnauthorized() async {
    await UserStore.clearToken();
    if (!mounted) return;
    setState(() {
      _token = null;
      _userId = 'default';
    });
  }

  /// 登录成功：存会话 → 进主界面。
  Future<void> _handleLoggedIn(AuthSession session) async {
    if (!mounted) return;
    setState(() {
      _token = session.token;
      _userId = session.userId;
      _needsSelect = false;
    });
  }

  /// 登出（Launcher 底部「退出登录」）：调后端注销 + 清本地 → 回登录页。
  Future<void> _handleLogout() async {
    final api = _apiFor(_userId);
    try {
      await api.logout();
    } catch (_) {
      // 注销失败不阻塞登出（本地清 token 即生效）
    }
    await _handleUnauthorized();
  }

  /// 切换账号（旧多账号流程）：登录体系下已由「退出登录」替代，此处保留仅为首屏选号路径服务。
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '阿呆阿呆',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.dark,
      navigatorKey: _navigatorKey,
      scaffoldMessengerKey: rootScaffoldMessengerKey,
      // REVIEW #246：根 messenger 挂在 MaterialApp 层——MainPage 切 World B 被 dispose
      // 后，上传失败/成功提示仍能弹出，不静默丢。
      // 2026-08-17（iPhone 首次安装）：emoji fallback 字体按平台——'Noto Color Emoji'
      // 是 Android 字体，iOS 不存在 → iPhone 上 emoji 渲染成方框；iOS 用系统 Apple Color Emoji。
      builder: (context, child) => DefaultTextStyle(
        style: TextStyle(fontFamilyFallback: [
          if (defaultTargetPlatform == TargetPlatform.iOS ||
              defaultTargetPlatform == TargetPlatform.macOS)
            'Apple Color Emoji'
          else
            'Noto Color Emoji',
        ]),
        child: child!,
      ),
      home: _booting
          ? const _BootScreen()
          : widget.forceLogin && _token == null
              ? LoginPage(api: _apiFor(_userId), onLoggedIn: _handleLoggedIn)
              : _needsSelect
                  ? AccountSelectPage(
                      api: _apiFor(_userId),
                      onSelect: _selectAccount,
                    )
                  : DualWorldShell(
                      key: ValueKey(_userId),
                      userId: _userId,
                      // RFC 20260901-auth-login：传带 token 的 ApiService（2026-09-02 线上
                      // 实锤：生产无 apiFactory 时壳内自建丢 token → 主页全 401）
                      api: _apiFor(_userId),
                      apiFactory: widget.apiFactory,
                      onLogout: _handleLogout,
                    ),
    );
  }
}

/// 启动校验期间的 loading 屏。
class _BootScreen extends StatelessWidget {
  const _BootScreen();

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      backgroundColor: AppColors.darkBg,
      body: Center(
        child: SizedBox(
          width: 24,
          height: 24,
          child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkBlue),
        ),
      ),
    );
  }
}

/// 双主页壳 — World A (Feed) 与 World B (Launcher) 无缝切换。
class DualWorldShell extends StatefulWidget {
  const DualWorldShell(
      {super.key, this.userId = 'default', this.onLogout, this.apiFactory, this.api});

  /// 当前用户 ID（透传给 ApiService 与 MainPage）。
  final String userId;

  /// 登出回调（RFC 20260901-auth-login：Launcher「退出登录」→ 清会话回登录页）。
  final VoidCallback? onLogout;

  /// 测试注入：按 userId 构造 ApiService（默认真实实现）。
  final ApiService Function(String userId)? apiFactory;

  /// 直接注入 ApiService（RFC 20260901-auth-login：RootApp 传**带 token** 的实例——
  /// 生产无 apiFactory 时若壳内自建会丢 token 导致全 401，2026-09-02 线上实锤）。
  final ApiService? api;

  @override
  State<DualWorldShell> createState() => _DualWorldShellState();
}

class _DualWorldShellState extends State<DualWorldShell> {
  // REVIEW #177：切换账号重建整树（ValueKey 换 userId）→ 新 State 新建 ApiService，
  // 缓存（tags/timeline/memory）随之清空，不跨账号串数据。
  // 优先级：显式注入（带 token）> 测试 factory > 自建兜底
  late final ApiService _api = widget.api ??
      widget.apiFactory?.call(widget.userId) ??
      ApiService(userId: widget.userId);
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
          // 底部输入框区域（约 140px：输入栏 + 附件预览）仅在键盘弹起时排除切世界——
          // 打字上滑误触会切走 World 丢草稿（#16），但浏览时（键盘收起）保留输入栏
          // 上滑切 World 的旧入口（阿呆 08-13 反馈：无法按之前方式到背面主页）。
          final keyboardUp = MediaQuery.of(context).viewInsets.bottom > 0;
          if (keyboardUp && (_dragStartY ?? 0) >= (MediaQuery.of(context).size.height - 140)) return;
          // 全局空白快速拖拽：高速度阈值避免干扰正常滚动
          if (d.primaryVelocity != null) {
            if (d.primaryVelocity! < -400 && !_showWorldB) {
              _toggleWorld();
            } else if (d.primaryVelocity! > 400 && _showWorldB) {
              _toggleWorld();
            }
          }
        },
        // 点击空白处收起键盘（阿呆 08-13 反馈）：命中无子级 onTap 的区域时释放焦点；
        // 子级自带 onTap（FeedCard 按钮/卡片等）在手势竞技场中优先，不受影响。
        onTap: () => FocusScope.of(context).unfocus(),
        behavior: HitTestBehavior.translucent,
        // P1-2（2026-08-23 app 体感修复）：切 World 不再丢输入现场——
        // 原 AnimatedSwitcher 切换时 MainPage 出树 dispose（草稿/对话态/上传进度丢失）。
        // 改 IndexedStack 常驻保活：两页状态跨切换保留；_feedRefreshTick 仍负责切回 Feed 刷新。
        child: IndexedStack(
          index: _showWorldB ? 1 : 0,
          children: [
            MainPage(
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
            LauncherPage(
                key: const ValueKey('worldB'),
                api: _api,
                onNavigateBack: _toggleWorld,
                onLogout: widget.onLogout,
                refreshTick: _feedRefreshTick, // P1-2：切回 World B 时刷新（保活不重建）
              ),
          ],
        ),
      ),
    );
  }
}
