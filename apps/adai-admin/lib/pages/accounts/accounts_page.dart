import 'package:flutter/material.dart';
import '../../data/mock_account_store.dart';
import '../../models/account.dart';
import '../../theme/app_colors.dart';
import '../../widgets/badge.dart';

/// 账号管理页 — 列表 + 建号表单 + 禁用/启用 + 删除（mock 数据）。
class AccountsPage extends StatefulWidget {
  const AccountsPage({super.key});

  @override
  State<AccountsPage> createState() => _AccountsPageState();
}

class _AccountsPageState extends State<AccountsPage> {
  final MockAccountStore _store = MockAccountStore();

  bool _showCreate = false;
  final _userIdCtrl = TextEditingController();
  String _role = 'user';

  @override
  void dispose() {
    _userIdCtrl.dispose();
    super.dispose();
  }

  // ── 操作 ──

  void _createAccount() {
    final error = _store.create(userId: _userIdCtrl.text, role: _role);
    if (!mounted) return;
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack(error, AppColors.darkOrange),
      );
      return;
    }
    _userIdCtrl.clear();
    setState(() => _showCreate = false);
    ScaffoldMessenger.of(context).showSnackBar(
      _snack('已创建账号', AppColors.darkGreen),
    );
  }

  void _toggleEnabled(Account account, bool enabled) {
    if (!_store.setEnabled(account.userId, enabled)) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack('内置管理员不可禁用', AppColors.darkOrange),
      );
      return;
    }
    setState(() {});
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

    if (!_store.delete(account.userId)) {
      ScaffoldMessenger.of(context).showSnackBar(
        _snack('内置管理员不可删除', AppColors.darkOrange),
      );
      return;
    }
    setState(() {});
    ScaffoldMessenger.of(context).showSnackBar(
      _snack('已删除账号 ${account.userId}', AppColors.darkGreen),
    );
  }

  SnackBar _snack(String text, Color color) {
    return SnackBar(
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      content: Text(text,
          style: TextStyle(color: color, fontSize: 13)),
    );
  }

  // ── 统计 ──

  int get _adminCount => _store.accounts.where((a) => a.isAdmin).length;
  int get _userCount => _store.accounts.where((a) => !a.isAdmin).length;
  int get _disabledCount =>
      _store.accounts.where((a) => !a.enabled).length;

  // ── 构建 ──

  @override
  Widget build(BuildContext context) {
    final accounts = _store.accounts;
    return SafeArea(
      child: ListView(
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
      ),
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
              Text('账号列表 · 建号（无口令）· 启用/禁用 · 删除',
                  style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            ],
          ),
        ),
        const AppBadge(label: 'MOCK', color: AppColors.darkYellow, icon: Icons.science_outlined),
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
          _statItem('全部', _store.accounts.length, AppColors.darkGrey1),
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
      {String? hint, void Function(String)? onSubmitted}) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 4),
      TextField(
        controller: ctrl,
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
    final isProtected = account.userId == MockAccountStore.protectedAdminId;
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
              value: account.enabled,
              activeTrackColor: AppColors.darkGreen.withValues(alpha: 0.4),
              activeThumbColor: AppColors.darkGreen,
              inactiveThumbColor: AppColors.darkGrey5,
              onChanged: (v) => _toggleEnabled(account, v),
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
      ]),
    );
  }

  String _formatDate(DateTime dt) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${dt.year}-${two(dt.month)}-${two(dt.day)}';
  }
}
