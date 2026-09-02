import 'package:flutter/material.dart';
import 'theme/app_theme.dart';
import 'services/api_service.dart';
import 'services/user_store.dart';
import 'pages/login_page.dart';
import 'desktop_shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // RFC 20260901-auth-login：启动加载持久化会话（token + 上次账号）
  final token = await UserStore.loadToken();
  final savedUserId = await UserStore.loadUserId();
  runApp(AdaiWebApp(initialToken: token, initialUserId: savedUserId));
}

/// 登录会话（RFC 20260901-auth-login）。
/// 无 token → 登录页；有 token → 启动校验 /auth/me 决定进主界面或回登录页。
class AdaiWebApp extends StatefulWidget {
  const AdaiWebApp({super.key, this.initialToken, this.initialUserId, this.apiFactory});

  /// 持久化的登录 token（可能已失效，启动时校验）。
  final String? initialToken;

  /// 持久化的上次账号（仅用于 ApiService userId 透传，后端覆盖）。
  final String? initialUserId;

  /// 测试注入：覆盖 ApiService 工厂（默认按 token/userId 创建）。
  final ApiService Function(String? token, String userId)? apiFactory;

  @override
  State<AdaiWebApp> createState() => _AdaiWebAppState();
}

class _AdaiWebAppState extends State<AdaiWebApp> {
  /// 当前会话 token；null = 未登录（显示登录页）。
  String? _token;

  /// 当前会话 userId（登录响应/me 返回；后端以会话为准）。
  String _userId = 'default';

  /// 启动状态：校验持久化 token 期间显示 loading。
  bool _booting = true;

  /// MaterialApp 的 Navigator key（登出跳转等）。
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  @override
  void initState() {
    super.initState();
    _token = widget.initialToken;
    _userId = widget.initialUserId ?? 'default';
    if (widget.initialToken != null) {
      _validateStoredSession();
    } else {
      _booting = false;
    }
  }

  /// 启动校验：持久化 token 调 /auth/me；失效（401）→ 清 token 回登录页。
  Future<void> _validateStoredSession() async {
    final api = _buildApi(_token!, _userId);
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

  ApiService _buildApi(String? token, String userId) {
    final factory = widget.apiFactory;
    if (factory != null) return factory(token, userId);
    return ApiService(
      userId: userId,
      token: token,
      onUnauthorized: _handleUnauthorized,
    );
  }

  /// 全局 401：清 token → 回登录页。
  Future<void> _handleUnauthorized() async {
    await UserStore.clearToken();
    await UserStore.clearUrlUserId();
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
    });
  }

  /// 登出（DesktopShell 底部账号点击）：调后端注销 + 清本地 → 回登录页。
  Future<void> _handleLogout() async {
    final api = _buildApi(_token, _userId);
    try {
      await api.logout();
    } catch (_) {
      // 注销失败不阻塞登出（本地清 token 即生效）
    }
    await _handleUnauthorized();
  }

  @override
  Widget build(BuildContext context) {
    final api = _buildApi(_token, _userId);
    return MaterialApp(
      title: '阿呆阿呆',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.dark,
      navigatorKey: _navigatorKey,
      home: _booting
          ? const _BootScreen()
          : _token == null
              ? LoginPage(api: api, onLoggedIn: _handleLoggedIn)
              : DesktopShell(
                  key: ValueKey('shell-$_userId'),
                  userId: _userId,
                  api: api,
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
      backgroundColor: Color(0xFF131211),
      body: Center(
        child: SizedBox(
          width: 24,
          height: 24,
          child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF5299FF)),
        ),
      ),
    );
  }
}
