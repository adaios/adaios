import 'package:flutter/material.dart';
import '../../models/account.dart';
import '../../services/account_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/badge.dart';

/// 账号管理页 — 列表 + 建号表单 + 禁用/启用 + 重置密码 + 删除（真实后端 /api/v1/accounts）。
///
/// REVIEW #178：改密入口放 admin——本人改密走顶栏会话菜单（/auth/password），
/// 本页为每个账号提供「重置密码」（PATCH /accounts/{id} password，踢除该账号会话）；
/// 当前登录账号自身隐藏重置（引导用顶栏改密，避免踢掉当前会话）。
class AccountsPage extends StatefulWidget {
  const AccountsPage({super.key, this.store, this.currentUserId = ''});

  /// 可注入 store（测试用 Fake）；默认真实 [AccountApiStore]。
  final AccountStore? store;

  /// 当前登录的 admin 账号（该账号隐藏「重置密码」，改密走顶栏会话菜单）。
  final String currentUserId;

  @override
  State<AccountsPage> createState() => _AccountsPageState();
}

class _AccountsPageState extends State<AccountsPage> {
  late final AccountStore _store = widget.store ?? AccountApiStore();

  List<Account>? _accounts;
  String? _error;
  bool _loading = true;

  bool _showCreate = false;
  final _userIdCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  String _role = 'user';

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _userIdCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _load({bool silent = false}) async {
    // deep 前端（2026-08-17）：操作后静默刷新不闪全页 spinner（已有数据时 silent）
    setState(() {
      _loading = !silent;
      _error = null;
    });
    try {
      final accounts = await _store.loadAccounts();
      if (!mounted) return;
      setState(() {
        _accounts = accounts;
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

  // ── 操作 ──

  Future<void> _createAccount() async {
    final password = _passwordCtrl.text;
    if (password.isNotEmpty && password.length < 8) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack('初始密码长度至少 8 位', AppColors.darkOrange),
      );
      return;
    }
    final error = await _store.create(
        userId: _userIdCtrl.text, role: _role, password: password);
    if (!mounted) return;
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack(error, AppColors.darkOrange),
      );
      return;
    }
    _userIdCtrl.clear();
    _passwordCtrl.clear();
    setState(() => _showCreate = false);
    ScaffoldMessenger.of(context).showSnackBar(
      _snack('已创建账号', AppColors.darkGreen),
    );
    await _load(silent: true);
  }

  /// 重置密码（REVIEW #178 改密入口放 admin）：设新密码 → 后端踢除该账号全部会话。
  Future<void> _resetPassword(Account account) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => _ResetPasswordDialog(
        accountId: account.userId,
        onReset: (pwd) => _store.resetPassword(account.userId, pwd),
      ),
    );
    if (ok != true || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      _snack('已重置 ${account.userId} 的密码', AppColors.darkGreen),
    );
  }

  Future<void> _toggleEnabled(Account account, bool enabled) async {
    final error = await _store.setEnabled(account.userId, enabled);
    if (!mounted) return;
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack(error, AppColors.darkOrange),
      );
      return;
    }
    await _load(silent: true);
  }

  /// 插件 toggle 串行队列（REVIEW P2-R1）：PATCH 全量替换（read-modify-write）并发互覆——
  /// 快速连点两个开关若同时从旧快照出发，后完成的全量覆盖先完成的 → 丢一个开关。
  /// 串行化后，后一个 toggle 等前一个完成（其 _load 已刷新 _accounts），重取到最新快照。
  Future<void> _toggleQueue = Future.value();

  /// 插件开关（RFC 20260814）：trading/project 勾选 → PATCH 全量 plugins。
  Future<void> _togglePlugin(Account account, String plugin, bool on) {
    // W-P2-3（2026-08-17）：catchError 恢复——非 ApiException 异常（网络等）不会让队列永久 error，
    // 否则后续 toggle 全部拒绝（串行队列单点故障，F4 同类）
    _toggleQueue = _toggleQueue
        .catchError((_) {}) // 前序失败不阻断后续（已反馈，忽略）
        .then((_) => _doTogglePlugin(account, plugin, on));
    return _toggleQueue;
  }

  Future<void> _doTogglePlugin(Account account, String plugin, bool on) async {
    // REVIEW S-R2：改走服务端合并语义（add/remove）——不再本地拼全量 PATCH，
    // 服务端账号级锁保证并发 toggle 顺序合并，快速连点不再互覆丢开关
    final error = await _store.mergePlugins(account.userId,
        add: on ? [plugin] : [], remove: on ? [] : [plugin]);
    if (!mounted) return;
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack(error, AppColors.darkOrange),
      );
      return;
    }
    await _load(silent: true);
  }

  Future<void> _deleteAccount(Account account) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: const Text('删除账号',
            style: TextStyle(color: AppColors.darkGrey1, fontSize: 16)),
        content: Text(
          '确定删除账号「${account.userId}」？此操作不可撤销。',
          style: const TextStyle(color: AppColors.darkGrey3),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消',
                style: TextStyle(color: AppColors.darkGrey5)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除',
                style: TextStyle(color: AppColors.darkOrange)),
          ),
        ],
      ),
    );
    if (confirm != true || !mounted) return;

    final error = await _store.delete(account.userId);
    if (!mounted) return;
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack(error, AppColors.darkOrange),
      );
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      _snack('已删除账号 ${account.userId}', AppColors.darkGreen),
    );
    await _load(silent: true);
  }

  SnackBar _snack(String text, Color color) {
    return SnackBar(
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      content: Text(text, style: TextStyle(color: color, fontSize: 13)),
    );
  }

  // ── 统计 ──

  List<Account> get _accountsOrEmpty => _accounts ?? const [];
  int get _adminCount => _accountsOrEmpty.where((a) => a.isAdmin).length;
  int get _userCount => _accountsOrEmpty.where((a) => !a.isAdmin).length;
  int get _disabledCount => _accountsOrEmpty.where((a) => !a.enabled).length;

  // ── 构建 ──

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: _loading
          ? const Center(
              child: CircularProgressIndicator(
                  strokeWidth: 2, color: AppColors.darkGreen),
            )
          : _error != null
              ? _buildError()
              : _buildList(),
    );
  }

  Widget _buildError() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.cloud_off_outlined,
                size: 30, color: AppColors.darkOrange),
            const SizedBox(height: 10),
            Text('加载账号失败：$_error',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: _load,
              child: const Text('重试',
                  style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildList() {
    final accounts = _accountsOrEmpty;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
      children: [
        _buildHeader(),
        const SizedBox(height: 16),
        _buildStatsRow(),
        const SizedBox(height: 12),
        _buildCreateToggle(),
        if (_showCreate) ...[
          const SizedBox(height: 8),
          _buildCreateForm(),
        ],
        const SizedBox(height: 12),
        if (accounts.isEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 40),
            child: Center(
              child: Text('暂无账号',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
            ),
          )
        else
          ...accounts.map(_buildAccountCard),
      ],
    );
  }

  Widget _buildHeader() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        const Icon(Icons.manage_accounts_outlined,
            size: 26, color: AppColors.darkGreen),
        const SizedBox(width: 10),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('账号管理',
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                    letterSpacing: -0.3,
                    color: AppColors.darkGrey1,
                  )),
              SizedBox(height: 2),
              Text('账号列表 · 建号（可设初始密码）· 启用/禁用 · 重置密码 · 插件开关 · 删除',
                  style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildStatsRow() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _statItem('全部', _accountsOrEmpty.length, AppColors.darkGrey1),
          _statItem('管理员', _adminCount, AppColors.darkPurple),
          _statItem('普通用户', _userCount, AppColors.darkBlue),
          _statItem('已禁用', _disabledCount, AppColors.darkOrange),
        ],
      ),
    );
  }

  Widget _statItem(String label, int count, Color color) {
    return Column(children: [
      Text('$count',
          style: TextStyle(
              fontSize: 20, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _buildCreateToggle() {
    return Row(
      children: [
        const Icon(Icons.person_add_alt_1, size: 16, color: AppColors.darkGreen),
        const SizedBox(width: 6),
        const Text('新建账号',
            style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: AppColors.darkGrey1)),
        const Spacer(),
        GestureDetector(
          onTap: () => setState(() => _showCreate = !_showCreate),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: _showCreate
                  ? AppColors.darkSurface2
                  : AppColors.darkGreen.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(
                color: _showCreate
                    ? AppColors.darkBorder
                    : AppColors.darkGreen.withValues(alpha: 0.3),
                width: 0.5,
              ),
            ),
            child: Text(
              _showCreate ? '收起' : '+ 新建',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w500,
                color: _showCreate ? AppColors.darkGrey5 : AppColors.darkGreen,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildCreateForm() {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _field('账号 ID', _userIdCtrl,
            hint: '登录名（如 zhangsan）', onSubmitted: (_) => _createAccount()),
        const SizedBox(height: 10),
        _field('初始密码', _passwordCtrl,
            hint: '至少 8 位（留空 = 登录前需先重置密码）', obscure: true,
            onSubmitted: (_) => _createAccount()),
        const SizedBox(height: 10),
        Row(children: [
          _sectionTitle('角色'),
          const SizedBox(width: 8),
          _roleChip('user', '普通用户'),
          const SizedBox(width: 4),
          _roleChip('admin', '管理员'),
        ]),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          height: 38,
          child: ElevatedButton(
            onPressed: _createAccount,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.darkGreen.withValues(alpha: 0.2),
              foregroundColor: AppColors.darkGreen,
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('创建账号',
                style: TextStyle(fontWeight: FontWeight.w500)),
          ),
        ),
      ]),
    );
  }

  Widget _field(String label, TextEditingController ctrl,
      {String? hint, bool obscure = false, void Function(String)? onSubmitted}) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 4),
      TextField(
        controller: ctrl,
        obscureText: obscure,
        onSubmitted: onSubmitted,
        style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
        decoration: InputDecoration(
          isDense: true,
          hintText: hint,
          hintStyle: const TextStyle(fontSize: 12, color: AppColors.darkGrey6),
          contentPadding:
              const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
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
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(6),
            borderSide: const BorderSide(color: AppColors.darkGreen, width: 0.5),
          ),
        ),
      ),
    ]);
  }

  Widget _roleChip(String value, String label) {
    final selected = _role == value;
    return GestureDetector(
      onTap: () => setState(() => _role = value),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: selected
              ? AppColors.darkGreen.withValues(alpha: 0.15)
              : AppColors.darkBg,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: selected
                ? AppColors.darkGreen.withValues(alpha: 0.3)
                : AppColors.darkBorder,
            width: 0.5,
          ),
        ),
        child: Text(label,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w500,
              color: selected ? AppColors.darkGreen : AppColors.darkGrey5,
            )),
      ),
    );
  }

  Widget _sectionTitle(String title) {
    return Text(title,
        style: const TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w600,
            color: AppColors.darkGrey5));
  }

  // ── 账号卡片 ──

  Widget _buildAccountCard(Account account) {
    final isProtected = account.userId == AccountStore.protectedAdminId;
    final statusColor = account.enabled ? AppColors.darkGreen : AppColors.darkOrange;
    final roleColor = account.isAdmin ? AppColors.darkPurple : AppColors.darkBlue;

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
        border: account.enabled
            ? null
            : Border.all(color: AppColors.darkBorder, width: 0.5),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          AppBadge(
            label: account.isAdmin ? 'admin' : 'user',
            color: roleColor,
          ),
          const SizedBox(width: 6),
          AppBadge(
            label: account.statusLabel,
            color: statusColor,
            icon: account.enabled ? Icons.check_circle_outline : Icons.pause_circle_outline,
          ),
          if (isProtected) ...[
            const SizedBox(width: 6),
            const AppBadge(
              label: '内置',
              color: AppColors.darkYellow,
              icon: Icons.lock_outline,
            ),
          ],
          const Spacer(),
          if (isProtected)
            const Tooltip(
              message: '内置管理员受保护',
              child: Icon(Icons.lock_outline, size: 16, color: AppColors.darkGrey6),
            ),
          if (!isProtected)
            Switch(
              key: ValueKey('enabled-${account.userId}'),
              value: account.enabled,
              activeTrackColor: AppColors.darkGreen.withValues(alpha: 0.4),
              activeThumbColor: AppColors.darkGreen,
              inactiveThumbColor: AppColors.darkGrey5,
              onChanged: (v) => _toggleEnabled(account, v),
            ),
          // REVIEW #178：改密入口放 admin——重置密码（当前登录账号自身引导走顶栏改密）
          if (account.userId == widget.currentUserId && widget.currentUserId.isNotEmpty)
            const Tooltip(
              message: '当前登录账号：请用右上角会话菜单「修改密码」',
              child: Icon(Icons.lock_outline, size: 16, color: AppColors.darkGrey6),
            )
          else
            IconButton(
              key: ValueKey('reset-pwd-${account.userId}'),
              icon: const Icon(Icons.password_outlined,
                  size: 18, color: AppColors.darkGrey5),
              onPressed: () => _resetPassword(account),
              tooltip: '重置密码',
            ),
          if (!isProtected)
            IconButton(
              icon: const Icon(Icons.delete_outline,
                  size: 18, color: AppColors.darkGrey5),
              onPressed: () => _deleteAccount(account),
              tooltip: '删除账号',
            ),
        ]),
        const SizedBox(height: 8),
        Row(children: [
          Icon(Icons.person_outline, size: 15, color: AppColors.darkGrey4),
          const SizedBox(width: 6),
          Text(
            account.userId,
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: AppColors.darkGrey1,
            ),
          ),
          const Spacer(),
          Text(
            '创建于 ${_formatDate(account.createdAt)}',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey6),
          ),
        ]),
        // 插件开关（RFC 20260814 Domain=插件模型）：控制该用户启用 trading/project
        // 08-15 前端×2（2026-08-17）：内置管理员插件受保护（enabled/删除有保护、插件开关此前无）
        const SizedBox(height: 10),
        Row(children: [
          const Text('插件',
              style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          const SizedBox(width: 8),
          _pluginSwitch(account, 'trading', '交易'),
          const SizedBox(width: 12),
          _pluginSwitch(account, 'project', '项目'),
        ]),
      ]),
    );
  }

  Widget _pluginSwitch(Account account, String plugin, String label) {
    final enabled = account.plugins.contains(plugin);
    // REVIEW P2-R3：内置 admin（owner）插件开关受 isProtected 保护——禁用 + Tooltip，
    // 与 enabled/删除按钮同保护口径（防误关 owner 插件）
    final isProtected = account.userId == AccountStore.protectedAdminId;
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Text(label,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
      const SizedBox(width: 4),
      Tooltip(
        message: isProtected ? '内置管理员插件受保护，不可修改' : '切换插件',
        waitDuration: const Duration(milliseconds: 400),
        child: Switch(
          key: ValueKey('plugin-${account.userId}-$plugin'),
          value: enabled,
          activeTrackColor: AppColors.darkOrange.withValues(alpha: 0.4),
          activeThumbColor: AppColors.darkOrange,
          inactiveThumbColor: AppColors.darkGrey5,
          onChanged: isProtected ? null : (v) => _togglePlugin(account, plugin, v),
        ),
      ),
    ]);
  }

  String _formatDate(DateTime dt) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${dt.year}-${two(dt.month)}-${two(dt.day)}';
  }
}

/// 重置密码弹窗（独立 StatefulWidget：控制器随弹窗 State 释放，避免退出动画期
/// 使用已 dispose 的控制器）。
class _ResetPasswordDialog extends StatefulWidget {
  const _ResetPasswordDialog({required this.accountId, required this.onReset});

  final String accountId;

  /// 提交回调：返回 null = 成功；返回字符串 = 失败原因（弹窗内展示）。
  final Future<String?> Function(String newPassword) onReset;

  @override
  State<_ResetPasswordDialog> createState() => _ResetPasswordDialogState();
}

class _ResetPasswordDialogState extends State<_ResetPasswordDialog> {
  final _pwdCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();
  String? _error;
  bool _submitting = false;

  @override
  void dispose() {
    _pwdCtrl.dispose();
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
    final pwd = _pwdCtrl.text;
    if (pwd.length < 8) {
      setState(() => _error = '密码长度至少 8 位');
      return;
    }
    if (pwd != _confirmCtrl.text) {
      setState(() => _error = '两次输入的密码不一致');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    final fail = await widget.onReset(pwd);
    if (!mounted) return;
    if (fail != null) {
      setState(() {
        _submitting = false;
        _error = fail;
      });
      return;
    }
    Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface,
      title: Text('重置密码 · ${widget.accountId}',
          style: const TextStyle(color: AppColors.darkGrey1, fontSize: 15)),
      content: SizedBox(
        width: 320,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _pwdCtrl,
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
                  style: const TextStyle(fontSize: 12, color: AppColors.darkOrange)),
            ],
            const SizedBox(height: 6),
            const Text('重置后该账号所有登录将失效，需用新密码重新登录',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('取消', style: TextStyle(color: AppColors.darkGrey5)),
        ),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.darkBlue,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
          ),
          child: Text(_submitting ? '提交中…' : '重置密码',
              style: const TextStyle(fontSize: 13)),
        ),
      ],
    );
  }
}
