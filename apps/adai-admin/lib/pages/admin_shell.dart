import 'package:flutter/material.dart';

import '../models/account.dart';
import '../services/account_api_store.dart';
import '../services/api_exception.dart';
import '../services/api_service.dart';
import '../services/data_api_store.dart';
import '../services/knowledge_api_store.dart';
import '../services/system_api_store.dart';
import '../theme/app_colors.dart';
import '../utils/open_url.dart';
import 'accounts/accounts_page.dart';
import 'data/data_page.dart';
import 'knowledge/knowledge_page.dart';
import 'system/system_page.dart';

/// 管理端骨架 — 顶栏（用户选择器 + 会话菜单）+ 侧边导航（宽屏）/ 底部导航（窄屏）。
/// 四个主区：账号 / 数据 / 系统 / 知识（数据、系统为 per-user，随用户切换）。
///
/// REVIEW #178：控制台并入统一登录——[token] 为 admin 会话，真实 store 均由带
/// Bearer 的 [ApiService] 构建；401 走 [onUnauthorized] 清会话回登录页；顶栏提供
/// 「修改密码 / 退出登录」会话菜单。
class AdminShell extends StatefulWidget {
  const AdminShell({
    super.key,
    this.accountStore,
    this.dataStore,
    this.systemStore,
    this.knowledgeStore,
    this.token,
    this.account = '',
    this.onLogout,
    this.onUnauthorized,
  });

  /// 可注入账号 store（测试用 Fake）；默认按会话 token 建真实 [AccountApiStore]。
  final AccountStore? accountStore;

  /// 可注入数据 store（测试用 Fake）；默认按 userId + 会话建真实 [DataApiStore]。
  final DataStore? dataStore;

  /// 可注入系统 store（测试用 Fake）；默认按 userId + 会话建真实 [SystemApiStore]。
  final SystemStore? systemStore;

  /// 可注入知识 store（测试用 Fake）；默认真实 [KnowledgeApiStore]。
  final KnowledgeStore? knowledgeStore;

  /// 登录会话 token（REVIEW #178；null = 测试/未登录，真实请求会 401）。
  final String? token;

  /// 当前登录的 admin 账号 userId（顶栏展示 + 改密对象）。
  final String account;

  /// 退出登录（根组件调后端注销 + 清 token 回登录页）。
  final VoidCallback? onLogout;

  /// 401 会话失效（根组件清 token 回登录页）。
  final VoidCallback? onUnauthorized;

  @override
  State<AdminShell> createState() => _AdminShellState();
}

/// 初始浏览用户 ID：`?userId=xxx`（前门 query）优先 → 回落登录账号（REVIEW #178：
/// 控制台登录后默认浏览自己数据）→ 'default'。
String _initialUserId(String account) {
  final q = Uri.base.queryParameters['userId'];
  if (q != null && RegExp(r'^[a-zA-Z0-9_-]+$').hasMatch(q)) return q;
  if (account.isNotEmpty) return account;
  return 'default';
}

class _AdminShellState extends State<AdminShell> {
  /// 会话 ApiService（按 userId 缓存；token 相同，onUnauthorized 直通根组件）。
  final Map<String, ApiService> _apiByUser = {};

  /// per-user 数据/系统 store 缓存（userId 切换时重建，与页面 ValueKey 对齐）。
  final Map<String, DataStore> _dataStores = {};
  final Map<String, SystemStore> _systemStores = {};

  late final AccountStore _accountStore =
      widget.accountStore ?? AccountApiStore(api: _apiFor('default'));

  late final KnowledgeStore _knowledgeStore =
      widget.knowledgeStore ?? KnowledgeApiStore(api: _apiFor('default'));

  int _index = 0;

  /// 当前选中的浏览用户 ID（per-user 请求的 X-User-Id；admin 会话后端透传，可跨账号治理浏览）。
  late String _userId = _initialUserId(widget.account);

  /// 可选账号列表（含 default）。
  List<Account> _accounts = [];
  bool _loadingAccounts = true;

  static const List<_NavItem> _items = [
    _NavItem('账号', Icons.manage_accounts_outlined, Icons.manage_accounts),
    _NavItem('数据', Icons.storage_outlined, Icons.storage),
    _NavItem('系统', Icons.settings_outlined, Icons.settings),
    _NavItem('知识', Icons.menu_book_outlined, Icons.menu_book),
  ];

  ApiService _apiFor(String userId) => _apiByUser.putIfAbsent(
      userId,
      () => ApiService(
            userId: userId,
            token: widget.token,
            onUnauthorized: widget.onUnauthorized,
          ));

  @override
  void initState() {
    super.initState();
    _loadAccounts();
  }

  Future<void> _loadAccounts() async {
    setState(() => _loadingAccounts = true);
    try {
      final accounts = await _accountStore.loadAccounts();
      if (!mounted) return;
      setState(() {
        _accounts = accounts;
        _loadingAccounts = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _accounts = const [];
        _loadingAccounts = false;
      });
    }
  }

  List<String> get _userOptions =>
      ['default', ..._accounts.where((a) => a.enabled).map((a) => a.userId)];

  @override
  Widget build(BuildContext context) {
    final pages = <Widget>[
      AccountsPage(
        store: widget.accountStore ?? _accountStore,
        currentUserId: widget.account,
      ),
      DataPage(
        userId: _userId,
        store: widget.dataStore ??
            _dataStores.putIfAbsent(
                _userId, () => DataApiStore(userId: _userId, api: _apiFor(_userId))),
        key: ValueKey('data-$_userId'),
      ),
      SystemPage(
        userId: _userId,
        store: widget.systemStore ??
            _systemStores.putIfAbsent(
                _userId, () => SystemApiStore(userId: _userId, api: _apiFor(_userId))),
        key: ValueKey('system-$_userId'),
      ),
      KnowledgePage(store: widget.knowledgeStore ?? _knowledgeStore),
    ];

    return Scaffold(
      backgroundColor: AppColors.darkBg,
      bottomNavigationBar: _buildIcpBar(),
      appBar: AppBar(
        backgroundColor: AppColors.darkSurface,
        elevation: 0,
        scrolledUnderElevation: 0,
        titleSpacing: 16,
        title: const Row(
          children: [
            Icon(Icons.adb, size: 20, color: AppColors.darkGreen),
            SizedBox(width: 8),
            Text('阿呆控制台',
                style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: AppColors.darkGrey1)),
          ],
        ),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 4),
            child: _buildUserSelector(),
          ),
          _buildSessionMenu(),
        ],
      ),
      body: LayoutBuilder(
        builder: (context, constraints) {
          final wide = constraints.maxWidth >= 720;

          if (wide) {
            return Row(
              children: [
                _buildRail(),
                const VerticalDivider(
                    width: 1, thickness: 1, color: AppColors.darkBorder),
                Expanded(
                  child: IndexedStack(index: _index, children: pages),
                ),
              ],
            );
          }

          return Column(
            children: [
              Expanded(
                child: IndexedStack(index: _index, children: pages),
              ),
              _buildBottomNav(),
            ],
          );
        },
      ),
    );
  }

  Widget _buildUserSelector() {
    Widget label() => const Text('用户',
        style: TextStyle(fontSize: 12, color: AppColors.darkGrey5));

    if (_loadingAccounts) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          label(),
          const SizedBox(width: 8),
          const SizedBox(
            width: 14,
            height: 14,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ],
      );
    }

    final options = _userOptions;
    final selected = options.contains(_userId) ? _userId : 'default';

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        label(),
        const SizedBox(width: 8),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
          decoration: BoxDecoration(
            color: AppColors.darkBg,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: AppColors.darkBorder, width: 0.5),
          ),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<String>(
              value: selected,
              dropdownColor: AppColors.darkSurface,
              icon: const Icon(Icons.person_outline,
                  size: 16, color: AppColors.darkGreen),
              style: const TextStyle(
                  fontSize: 13, color: AppColors.darkGrey1),
              items: [
                for (final id in options)
                  DropdownMenuItem(value: id, child: Text(id)),
              ],
              onChanged: (v) {
                if (v == null || v == _userId) return;
                setState(() => _userId = v);
              },
            ),
          ),
        ),
      ],
    );
  }

  /// 会话菜单：当前登录账号 + 修改密码 / 退出登录（REVIEW #178「改密入口放 admin」）。
  Widget _buildSessionMenu() {
    final displayAccount = widget.account.isEmpty ? 'admin' : widget.account;
    return PopupMenuButton<String>(
      key: const ValueKey('session-menu'),
      tooltip: '会话',
      offset: const Offset(0, 44),
      color: AppColors.darkSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      onSelected: (v) {
        switch (v) {
          case 'password':
            _showChangePasswordDialog();
          case 'logout':
            widget.onLogout?.call();
        }
      },
      itemBuilder: (_) => [
        PopupMenuItem(
          enabled: false,
          child: Text('登录：$displayAccount',
              style: const TextStyle(
                  fontSize: 12, color: AppColors.darkGrey5)),
        ),
        const PopupMenuDivider(height: 1),
        const PopupMenuItem(
          value: 'password',
          child: Row(children: [
            Icon(Icons.lock_reset_outlined,
                size: 16, color: AppColors.darkGrey3),
            SizedBox(width: 8),
            Text('修改密码', style: TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
          ]),
        ),
        const PopupMenuItem(
          value: 'logout',
          child: Row(children: [
            Icon(Icons.logout, size: 16, color: AppColors.darkOrange),
            SizedBox(width: 8),
            Text('退出登录', style: TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
          ]),
        ),
      ],
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6),
        child: Icon(Icons.account_circle_outlined,
            size: 22, color: AppColors.darkGrey3),
      ),
    );
  }

  /// 修改密码对话框（改本人密码，POST /auth/password——改密入口放 admin，#178）。
  Future<void> _showChangePasswordDialog() async {
    final kicked = await showDialog<int>(
      context: context,
      builder: (_) => _ChangePasswordDialog(
        onChangePassword: (oldPwd, newPwd) => _apiFor('default')
            .changePassword(oldPassword: oldPwd, newPassword: newPwd),
      ),
    );
    if (kicked == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      content: Text(
        kicked > 0 ? '密码已更新（已退出其他 $kicked 处登录）' : '密码已更新',
        style: const TextStyle(fontSize: 13, color: AppColors.darkGreen),
      ),
    ));
  }

  Widget _buildRail() {
    return NavigationRail(
      selectedIndex: _index,
      onDestinationSelected: (i) => setState(() => _index = i),
      labelType: NavigationRailLabelType.all,
      minWidth: 76,
      leading: const SizedBox.shrink(),
      destinations: [
        for (final item in _items)
          NavigationRailDestination(
            icon: Icon(item.icon),
            selectedIcon: Icon(item.selectedIcon),
            label: Text(item.label),
          ),
      ],
    );
  }

  Widget _buildBottomNav() {
    return NavigationBar(
      selectedIndex: _index,
      onDestinationSelected: (i) => setState(() => _index = i),
      destinations: [
        for (final item in _items)
          NavigationDestination(
            icon: Icon(item.icon),
            selectedIcon: Icon(item.selectedIcon),
            label: item.label,
          ),
      ],
    );
  }

  /// 底部备案号栏（管局强制要求：网站底部悬挂 ICP 备案号并链接到 beian.miit.gov.cn）。
  Widget _buildIcpBar() {
    return Container(
      height: 26,
      decoration: const BoxDecoration(
        color: AppColors.darkSurface,
        border: Border(top: BorderSide(color: AppColors.darkBorder, width: 1)),
      ),
      child: Center(
        child: InkWell(
          onTap: () => openUrl('https://beian.miit.gov.cn'),
          child: const Padding(
            padding: EdgeInsets.symmetric(horizontal: 12),
            child: Text(
              '京ICP备2026056893号',
              style: TextStyle(
                fontSize: 10,
                color: AppColors.darkGrey5,
                letterSpacing: 0.5,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _NavItem {
  const _NavItem(this.label, this.icon, this.selectedIcon);

  final String label;
  final IconData icon;
  final IconData selectedIcon;
}

/// 修改密码弹窗（独立 StatefulWidget：控制器随弹窗 State 释放）。
class _ChangePasswordDialog extends StatefulWidget {
  const _ChangePasswordDialog({required this.onChangePassword});

  /// 提交回调：成功返回被踢会话数；失败抛 [ApiException]（弹窗内展示人话）。
  final Future<int> Function(String oldPassword, String newPassword) onChangePassword;

  @override
  State<_ChangePasswordDialog> createState() => _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends State<_ChangePasswordDialog> {
  final _oldCtrl = TextEditingController();
  final _newCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();
  String? _error;
  bool _submitting = false;

  @override
  void dispose() {
    _oldCtrl.dispose();
    _newCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  InputDecoration _decoration(String label) {
    return InputDecoration(
      labelText: label,
      labelStyle: const TextStyle(fontSize: 12, color: AppColors.darkGrey4),
      isDense: true,
      filled: true,
      fillColor: AppColors.darkBg,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(6),
        borderSide: const BorderSide(color: AppColors.darkBorder, width: 0.5),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(6),
        borderSide: const BorderSide(color: AppColors.darkBorder, width: 0.5),
      ),
    );
  }

  Future<void> _submit() async {
    final newPwd = _newCtrl.text;
    if (newPwd.length < 8) {
      setState(() => _error = '新密码长度至少 8 位');
      return;
    }
    if (newPwd != _confirmCtrl.text) {
      setState(() => _error = '两次输入的新密码不一致');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final kicked = await widget.onChangePassword(_oldCtrl.text, newPwd);
      if (!mounted) return;
      Navigator.pop(context, kicked);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = e is ApiException ? e.message : '操作失败，请重试';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface,
      title: const Text('修改密码',
          style: TextStyle(color: AppColors.darkGrey1, fontSize: 16)),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _oldCtrl,
              obscureText: true,
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              decoration: _decoration('原密码'),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _newCtrl,
              obscureText: true,
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              decoration: _decoration('新密码（至少 8 位）'),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _confirmCtrl,
              obscureText: true,
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              decoration: _decoration('确认新密码'),
            ),
            if (_error != null) ...[
              const SizedBox(height: 10),
              Text(_error!,
                  style: const TextStyle(fontSize: 12, color: AppColors.darkRed)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消', style: TextStyle(color: AppColors.darkGrey5)),
        ),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.darkBlue,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
          ),
          child: Text(_submitting ? '提交中…' : '确认修改',
              style: const TextStyle(fontSize: 13)),
        ),
      ],
    );
  }
}
