import 'package:flutter/material.dart';

import 'pages/admin_shell.dart';
import 'pages/login_page.dart';
import 'services/admin_session_store.dart';
import 'services/api_service.dart';
import 'theme/app_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // REVIEW #178：启动加载持久化登录会话（admin 并入统一登录）
  final token = await AdminSessionStore.loadToken();
  runApp(AdminApp(initialToken: token));
}

/// 控制台入口 — 登录门（RFC 20260901-auth-login / REVIEW #178）。
/// 无 token → 登录页；有 token → 启动校验 /auth/me（有效期 + role=admin）决定进控制台或回登录页。
class AdminApp extends StatefulWidget {
  const AdminApp({super.key, this.initialToken, this.apiFactory});

  /// 持久化的登录 token（可能已失效，启动时校验）。
  final String? initialToken;

  /// 测试注入：覆盖 ApiService 工厂（默认按 token 创建）。
  final ApiService Function(String? token)? apiFactory;

  @override
  State<AdminApp> createState() => _AdminAppState();
}

class _AdminAppState extends State<AdminApp> {
  /// 当前会话 token；null = 未登录（显示登录页）。
  String? _token;

  /// 当前登录账号（admin 会话的 userId，用于展示与改密）。
  String _account = '';

  /// 启动状态：校验持久化 token 期间显示 loading。
  bool _booting = true;

  @override
  void initState() {
    super.initState();
    _token = widget.initialToken;
    if (widget.initialToken != null) {
      _validateStoredSession();
    } else {
      _booting = false;
    }
  }

  /// 启动校验：持久化 token 调 /auth/me；失效（401）或非 admin → 清 token 回登录页。
  Future<void> _validateStoredSession() async {
    final api = _buildApi(_token!);
    try {
      final me = await api.authMe();
      if (!mounted) return;
      final role = me['role'] as String? ?? 'user';
      if (role != 'admin') {
        await _clearSession();
        return;
      }
      setState(() {
        _account = me['userId'] as String? ?? '';
        _booting = false;
      });
    } catch (e) {
      // 401 = 会话失效；其他错误（网络）先按未登录处理（安全默认，不静默进主界面）
      await _clearSession();
    }
  }

  ApiService _buildApi(String? token) {
    final factory = widget.apiFactory;
    if (factory != null) return factory(token);
    return ApiService(token: token, onUnauthorized: _handleUnauthorized);
  }

  /// 全局 401（会话失效）：清 token 回登录页。
  Future<void> _handleUnauthorized() async {
    await _clearSession();
  }

  Future<void> _clearSession() async {
    await AdminSessionStore.clearToken();
    if (!mounted) return;
    setState(() {
      _token = null;
      _account = '';
      _booting = false;
    });
  }

  /// 登录成功（role=admin）：存会话 → 进控制台。
  Future<void> _handleLoggedIn(AdminSession session) async {
    await AdminSessionStore.saveToken(session.token);
    if (!mounted) return;
    setState(() {
      _token = session.token;
      _account = session.userId;
    });
  }

  /// 退出登录：调后端注销 + 清本地 → 回登录页。
  Future<void> _handleLogout() async {
    final api = _buildApi(_token);
    try {
      await api.logout();
    } catch (_) {
      // 注销失败不阻塞登出（本地清 token 即生效）
    }
    await _clearSession();
  }

  @override
  Widget build(BuildContext context) {
    final api = _buildApi(_token);
    return MaterialApp(
      title: '阿呆控制台',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.dark,
      home: _booting
          ? const _BootScreen()
          : _token == null
              ? LoginPage(api: api, onLoggedIn: _handleLoggedIn)
              : AdminShell(
                  key: ValueKey('shell-$_token'),
                  token: _token,
                  account: _account,
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
