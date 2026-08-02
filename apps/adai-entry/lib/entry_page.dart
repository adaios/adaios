import 'package:flutter/material.dart';

import 'services/api_config.dart';
import 'web_redirect.dart';
import 'services/api_service.dart';
import 'theme/app_colors.dart';

/// 账号选择入口页 — 产品「前门」。
///
/// 拉取账号列表（前端过滤 enabled），点选后按角色分流：
/// - admin 账号 → adai-admin（`{adminUrl}/?userId=xxx`）
/// - 普通用户 → adai-app（`{appUrl}/?userId=xxx`）
///
/// 跨端口 localStorage 不共享，故用 query 参数传递所选 userId。
class EntryPage extends StatefulWidget {
  const EntryPage({super.key, this.api, this.onNavigate});

  /// 可注入 API（测试用）；默认真实 [EntryApiService]。
  final EntryApiService? api;

  /// 可注入跳转回调（测试用 capture）；默认 [navigateTo]（整页跳转）。
  final void Function(String url)? onNavigate;

  @override
  State<EntryPage> createState() => _EntryPageState();
}

class _EntryPageState extends State<EntryPage> {
  late final EntryApiService _api = widget.api ?? EntryApiService();

  List<Account>? _accounts;
  String? _error;
  bool _loading = true;

  void _navigate(String url) {
    final nav = widget.onNavigate ?? navigateTo;
    nav(url);
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final all = await _api.getAccounts();
      if (!mounted) return;
      setState(() {
        // 后端无 enabled 过滤参数，前端过滤（与 adai-admin 一致）
        _accounts = all.where((a) => a.enabled).toList();
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  void _select(Account account) {
    final id = account.userId;
    final url = account.isAdmin
        ? '${ApiConfig.adminUrl}/?userId=$id'
        : '${ApiConfig.appUrl}/?userId=$id';
    _navigate(url);
  }

  // ── 构建 ──

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 360),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _buildHeader(),
                  const SizedBox(height: 28),
                  _buildBody(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Column(children: [
      Container(
        width: 64,
        height: 64,
        decoration: BoxDecoration(
          color: AppColors.darkGreen.withValues(alpha: 0.15),
          shape: BoxShape.circle,
          border: Border.all(
            color: AppColors.darkGreen.withValues(alpha: 0.3),
            width: 0.5,
          ),
        ),
        child: const Icon(Icons.auto_awesome,
            size: 30, color: AppColors.darkGreen),
      ),
      const SizedBox(height: 16),
      const Text(
        'AdaiOS',
        style: TextStyle(
          fontSize: 26,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.5,
          color: AppColors.darkGrey1,
        ),
      ),
      const SizedBox(height: 6),
      const Text(
        '选择你的账号进入',
        style: TextStyle(fontSize: 13, color: AppColors.darkGrey5),
      ),
    ]);
  }

  Widget _buildBody() {
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.only(top: 40),
        child: Center(
          child: CircularProgressIndicator(
              strokeWidth: 2, color: AppColors.darkGreen),
        ),
      );
    }
    if (_error != null) return _buildError();
    final accounts = _accounts ?? const [];
    if (accounts.isEmpty) return _buildEmpty();
    return Column(children: accounts.map(_buildAccountCard).toList());
  }

  Widget _buildError() {
    return Column(children: [
      const Icon(Icons.cloud_off_outlined, size: 30, color: AppColors.darkOrange),
      const SizedBox(height: 10),
      Text(
        '加载账号失败：$_error',
        textAlign: TextAlign.center,
        style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4),
      ),
      const SizedBox(height: 14),
      OutlinedButton(
        onPressed: _load,
        child: const Text('重试',
            style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
      ),
    ]);
  }

  Widget _buildEmpty() {
    return const Padding(
      padding: EdgeInsets.only(top: 24),
      child: Column(children: [
        Icon(Icons.hourglass_empty, size: 30, color: AppColors.darkGrey6),
        SizedBox(height: 10),
        Text('暂无可用账号',
            style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
      ]),
    );
  }

  Widget _buildAccountCard(Account account) {
    final roleColor = account.isAdmin ? AppColors.darkPurple : AppColors.darkBlue;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: () => _select(account),
          borderRadius: BorderRadius.circular(12),
          child: Ink(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            decoration: BoxDecoration(
              color: AppColors.darkSurface2,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.darkBorder, width: 0.5),
            ),
            child: Row(children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: roleColor.withValues(alpha: 0.18),
                  shape: BoxShape.circle,
                ),
                child: Icon(Icons.person_outline, size: 22, color: roleColor),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      account.userId,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: AppColors.darkGrey1,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      account.isAdmin ? '管理员' : '普通用户',
                      style:
                          const TextStyle(fontSize: 12, color: AppColors.darkGrey5),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, size: 20, color: AppColors.darkGrey6),
            ]),
          ),
        ),
      ),
    );
  }
}
