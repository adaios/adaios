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
  const AccountsPage(
      {super.key, this.store, this.currentUserId = '', this.onBrowseUser});

  /// 可注入 store（测试用 Fake）；默认真实 [AccountApiStore]。
  final AccountStore? store;

  /// 当前登录的 admin 账号（该账号隐藏「重置密码」，改密走顶栏会话菜单）。
  final String currentUserId;

  /// P2-6（2026-09-06）：账号卡「治理浏览」→ 跳转到该用户的数据区治理视图
  /// （由壳层把浏览用户切到目标 userId 并切页；null = 不显示入口，如独立测试）。
  final ValueChanged<String>? onBrowseUser;

  @override
  State<AccountsPage> createState() => _AccountsPageState();
}

class _AccountsPageState extends State<AccountsPage> {
  late final AccountStore _store = widget.store ?? AccountApiStore();

  List<Account>? _accounts;
  String? _error;
  bool _loading = true;

  bool _showCreate = false;
  bool _creating = false; // P3-9：建号提交中（防重复提交）
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
      if (silent && _accounts != null) {
        // P2-4（2026-09-06）：静默刷新失败保留已展示数据，非阻塞提示（U31）——
        // 此前与首载同路径整页替换为「加载账号失败」，操作后一次网络抖动即丢全部列表
        setState(() => _loading = false);
        ScaffoldMessenger.of(context).showSnackBar(
          _snack('刷新失败：列表为上次数据，请稍后重试', AppColors.darkOrange),
        );
        return;
      }
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  // ── 操作 ──

  Future<void> _createAccount() async {
    if (_creating) return; // P3-9：防双击重复提交
    final password = _passwordCtrl.text;
    if (password.isNotEmpty && password.length < 8) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack('初始密码长度至少 8 位', AppColors.darkOrange),
      );
      return;
    }
    setState(() => _creating = true);
    try {
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
    } finally {
      if (mounted) setState(() => _creating = false);
    }
  }

  /// 重置密码（REVIEW #178 改密入口放 admin）：设新密码 → 后端踢除该账号全部会话。
  /// P3-2（2026-09-06 拍板）：内置管理员被其它管理员重置 → 先警示确认——
  /// 保留重置逃生通道（防锁死），但明示「接管内置管理员」的后果。
  Future<void> _resetPassword(Account account) async {
    final isProtected = account.userId == AccountStore.protectedAdminId;
    if (isProtected) {
      final proceed = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          backgroundColor: AppColors.darkSurface,
          title: const Text('重置内置管理员',
              style: TextStyle(color: AppColors.darkOrange, fontSize: 16)),
          content: Text(
            '「${account.userId}」是系统内置管理员。\n\n重置后将用新密码接管该账号，其现有登录会话全部失效。\n\n若你就是该账号本人且忘了密码，请用右上角会话菜单「修改密码」——本列表对当前登录账号隐藏了重置按钮。',
            style: const TextStyle(
                fontSize: 13, height: 1.5, color: AppColors.darkGrey3),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消',
                  style: TextStyle(color: AppColors.darkGrey5)),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('继续重置',
                  style: TextStyle(color: AppColors.darkOrange)),
            ),
          ],
        ),
      );
      if (proceed != true || !mounted) return;
    }
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
    // P2-2（2026-09-06）：禁用是断用操作——先确认（后端 P1-账号1：禁用即踢该账号全部会话）
    if (!enabled) {
      final confirm = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          backgroundColor: AppColors.darkSurface,
          title: const Text('禁用账号',
              style: TextStyle(color: AppColors.darkOrange, fontSize: 16)),
          content: Text(
            '确定禁用账号「${account.userId}」？\n\n禁用后该账号无法登录，其当前登录会话将立即失效（该用户 app/web 将退出）。',
            style: const TextStyle(
                fontSize: 13, height: 1.5, color: AppColors.darkGrey3),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消',
                  style: TextStyle(color: AppColors.darkGrey5)),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('确认禁用',
                  style: TextStyle(color: AppColors.darkOrange)),
            ),
          ],
        ),
      );
      if (confirm != true || !mounted) return;
    }
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
    // P2-3（2026-09-06）：成功给明确反馈（保存即生效）——此前静默成功用户不确定是否已存
    const pluginLabels = {'trading': '交易', 'project': '项目'};
    final label = pluginLabels[plugin] ?? plugin;
    ScaffoldMessenger.of(context).showSnackBar(
      _snack('已${on ? '开启' : '关闭'} ${account.userId} 的「$label」模块',
          AppColors.darkGreen),
    );
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
          // P2-5（2026-09-06）：补充明确后果（会话失效/不可撤销）；数据目录清理
          // 口径后端待拍板（task-log #149），不臆断写「连数据一起删」
          '确定删除账号「${account.userId}」？\n\n其现有登录会话将立即失效，账号不可再登录；此操作不可撤销。',
          style: const TextStyle(
              fontSize: 13, height: 1.5, color: AppColors.darkGrey3),
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
            // P3-9（2026-09-06）：建号提交 busy——防双击重复提交（第二次报「已存在」覆盖成功反馈）
            onPressed: _creating ? null : _createAccount,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.darkGreen.withValues(alpha: 0.2),
              foregroundColor: AppColors.darkGreen,
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8)),
            ),
            child: Text(_creating ? '创建中…' : '创建账号',
                style: const TextStyle(fontWeight: FontWeight.w500)),
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
            // P2-22（2026-09-06）：保护锁提亮 grey6→grey4（原 1.58:1 几乎不可见）
            const Tooltip(
              message: '内置管理员受保护（不可删除/禁用/改插件）',
              child: Icon(Icons.lock_outline, size: 16, color: AppColors.darkGrey4),
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
            // P3-3（2026-09-06）：本人账号用 account_circle 而非 lock——锁易误读为「账号被锁」
            const Tooltip(
              message: '当前登录账号：请用右上角会话菜单「修改密码」',
              child: Icon(Icons.account_circle_outlined,
                  size: 17, color: AppColors.darkGrey4),
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
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
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
        // P2-6（2026-09-06）：账号卡直达「治理浏览」——切到该用户的数据/系统区看其
        // 记录/记忆/持仓（任务流「发现账号异常 → 去看它的数据」不再靠顶栏手动下拉）；
        // 仅 enabled 账号可浏览（disabled 无法登录也无数据视图），自身当前即在本人视图
        if (widget.onBrowseUser != null &&
            account.enabled &&
            account.userId != widget.currentUserId) ...[
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              key: ValueKey('browse-${account.userId}'),
              onPressed: () => widget.onBrowseUser!(account.userId),
              style: TextButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                minimumSize: Size.zero,
              ),
              icon: const Icon(Icons.travel_explore,
                  size: 14, color: AppColors.darkBlue),
              label: const Text('治理浏览',
                  style:
                      TextStyle(fontSize: 12, color: AppColors.darkBlue)),
            ),
          ),
        ],
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
        // P2-3（2026-09-06）：说明即时生效语义（保存即生效，影响该用户端显隐）
        message: isProtected
            ? '内置管理员插件受保护，不可修改'
            : '保存即生效：开启/关闭该用户 app/web 的「$label」模块',
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
