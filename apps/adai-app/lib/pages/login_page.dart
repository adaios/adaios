import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../services/user_store.dart';
import '../theme/app_colors.dart';

/// 登录页（移动端）— RFC 20260901-auth-login（根治 REVIEW #179 X-User-Id 零鉴权）。
///
/// 职责：
/// - 账号 + 密码登录（成功 → 存 token/userId → 回调进入主界面）
/// - 首访引导：系统未初始化时提供「设置密码」入口（POST /auth/setup，一次性）
/// - 401（密码错/未设密码/限流）人话提示
class LoginPage extends StatefulWidget {
  final ApiService api;
  final ValueChanged<AuthSession> onLoggedIn;

  const LoginPage({super.key, required this.api, required this.onLoggedIn});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

/// 登录成功后的会话信息。
class AuthSession {
  final String token;
  final String userId;
  final String role;

  const AuthSession({required this.token, required this.userId, required this.role});
}

class _LoginPageState extends State<LoginPage> {
  final _accountCtrl = TextEditingController(text: 'adai');
  final _passwordCtrl = TextEditingController();
  bool _loading = false;
  String? _error;
  bool _showSetup = false; // 首访设置密码模式

  @override
  void dispose() {
    _accountCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final account = _accountCtrl.text.trim();
    final password = _passwordCtrl.text;
    if (account.isEmpty) {
      setState(() => _error = '请输入账号');
      return;
    }
    if (password.isEmpty) {
      setState(() => _error = '请输入密码');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await widget.api.login(account, password);
      if (!mounted) return;
      final token = result['token'] as String;
      final userId = result['userId'] as String;
      final role = result['role'] as String? ?? 'user';
      await UserStore.saveToken(token);
      await UserStore.saveUserId(userId);
      if (!mounted) return;
      // 先关 loading（否则 spinner 永转，父组件切换前 pumpAndSettle 卡死）
      setState(() => _loading = false);
      widget.onLoggedIn(AuthSession(token: token, userId: userId, role: role));
    } catch (e) {
      if (!mounted) return;
      final msg = _errText(e);
      setState(() {
        _error = msg;
        _loading = false;
        // 未设密码 → 切换到设置密码引导
        if (msg.contains('尚未设置密码')) {
          _showSetup = true;
        }
      });
    }
  }

  Future<void> _submitSetup() async {
    final account = _accountCtrl.text.trim();
    final password = _passwordCtrl.text;
    if (account.isEmpty) {
      setState(() => _error = '请输入账号');
      return;
    }
    if (password.length < 8) {
      setState(() => _error = '密码长度至少 8 位');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await widget.api.setup(account, password);
      if (!mounted) return;
      setState(() {
        _showSetup = false;
        _loading = false;
        _error = '密码设置成功，请登录';
      });
    } catch (e) {
      if (!mounted) return;
      final msg = _errText(e);
      setState(() {
        _error = msg.contains('已完成初始化') ? '系统已有密码，请直接登录' : msg;
        _showSetup = false;
        _loading = false;
      });
    }
  }

  String _errText(dynamic e) {
    final str = e.toString();
    final body = e is ApiException ? e.body : null;
    if (body != null && body.contains('"error"')) {
      final start = body.indexOf('"error"') + 8;
      final end = body.indexOf('"', start + 1);
      if (end > start) {
        return body.substring(start + 1, end);
      }
    }
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器，请确认网络';
    if (str.contains('401')) return '账号或密码错误';
    return '操作失败，请重试';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 32),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text('阿呆阿呆',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        fontSize: 28,
                        fontWeight: FontWeight.w700,
                        color: AppColors.darkGrey1,
                        letterSpacing: 1.5)),
                const SizedBox(height: 6),
                const Text('Personal AI OS',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 12, color: AppColors.darkGrey5, letterSpacing: 1)),
                const SizedBox(height: 44),
                TextField(
                  controller: _accountCtrl,
                  enabled: !_loading,
                  style: const TextStyle(color: AppColors.darkGrey1),
                  decoration: _inputDecoration('账号', Icons.person_outline),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _passwordCtrl,
                  enabled: !_loading,
                  obscureText: true,
                  style: const TextStyle(color: AppColors.darkGrey1),
                  onSubmitted: (_) => _showSetup ? _submitSetup() : _submit(),
                  decoration: _inputDecoration('密码', Icons.lock_outline),
                ),
                if (_error != null) ...[
                  const SizedBox(height: 14),
                  Text(_error!,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                          fontSize: 13,
                          color: _error!.contains('成功') ? AppColors.darkGreen : AppColors.darkRed)),
                ],
                const SizedBox(height: 28),
                SizedBox(
                  height: 48,
                  child: FilledButton(
                    onPressed: _loading ? null : (_showSetup ? _submitSetup : _submit),
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.darkBlue,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    ),
                    child: _loading
                        ? const SizedBox(
                            width: 18, height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                        : Text(_showSetup ? '设置密码' : '登 录',
                            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                  ),
                ),
                const SizedBox(height: 20),
                if (_showSetup)
                  Text('首次使用：为账号「${_accountCtrl.text}」设置登录密码（一次性，此后直接登录）',
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5))
                else
                  TextButton(
                    onPressed: _loading
                        ? null
                        : () => setState(() {
                              _showSetup = !_showSetup;
                              _error = null;
                            }),
                    child: Text(_showSetup ? '返回登录' : '首次使用？设置密码',
                        style: const TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  InputDecoration _inputDecoration(String label, IconData icon) {
    return InputDecoration(
      labelText: label,
      labelStyle: const TextStyle(color: AppColors.darkGrey4),
      prefixIcon: Icon(icon, size: 18, color: AppColors.darkGrey4),
      filled: true,
      fillColor: AppColors.darkSurface,
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: AppColors.darkBorder),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: AppColors.darkBlue),
      ),
    );
  }
}
