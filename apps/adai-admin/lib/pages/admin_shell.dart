import 'package:flutter/material.dart';
import '../models/account.dart';
import '../services/account_api_store.dart';
import '../services/data_api_store.dart';
import '../services/knowledge_api_store.dart';
import '../services/system_api_store.dart';
import '../theme/app_colors.dart';
import 'accounts/accounts_page.dart';
import 'data/data_page.dart';
import 'knowledge/knowledge_page.dart';
import 'system/system_page.dart';

/// 管理端骨架 — 顶栏（用户选择器）+ 侧边导航（宽屏）/ 底部导航（窄屏）。
/// 四个主区：账号 / 数据 / 系统 / 知识（数据、系统为 per-user，随用户切换）。
class AdminShell extends StatefulWidget {
  const AdminShell({
    super.key,
    this.accountStore,
    this.dataStore,
    this.systemStore,
    this.knowledgeStore,
  });

  /// 可注入账号 store（测试用 Fake）；默认加载真实账号列表用于用户选择器。
  final AccountStore? accountStore;

  /// 可注入数据 store（测试用 Fake）；默认按 userId 创建 [DataApiStore]。
  final DataStore? dataStore;

  /// 可注入系统 store（测试用 Fake）；默认按 userId 创建 [SystemApiStore]。
  final SystemStore? systemStore;

  /// 可注入知识 store（测试用 Fake）；默认真实 [KnowledgeApiStore]。
  final KnowledgeStore? knowledgeStore;

  @override
  State<AdminShell> createState() => _AdminShellState();
}

class _AdminShellState extends State<AdminShell> {
  late final AccountStore _accountStore = widget.accountStore ?? AccountApiStore();

  int _index = 0;

  /// 当前选中的用户 ID（per-user 请求的 X-User-Id）。
  String _userId = 'default';

  /// 可选账号列表（含 default）。
  List<Account> _accounts = [];
  bool _loadingAccounts = true;

  static const List<_NavItem> _items = [
    _NavItem('账号', Icons.manage_accounts_outlined, Icons.manage_accounts),
    _NavItem('数据', Icons.storage_outlined, Icons.storage),
    _NavItem('系统', Icons.settings_outlined, Icons.settings),
    _NavItem('知识', Icons.menu_book_outlined, Icons.menu_book),
  ];

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
      AccountsPage(store: widget.accountStore),
      DataPage(
        userId: _userId,
        store: widget.dataStore,
        key: ValueKey('data-$_userId'),
      ),
      SystemPage(
        userId: _userId,
        store: widget.systemStore,
        key: ValueKey('system-$_userId'),
      ),
      KnowledgePage(store: widget.knowledgeStore),
    ];

    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkSurface,
        elevation: 0,
        scrolledUnderElevation: 0,
        titleSpacing: 16,
        title: const Row(
          children: [
            Icon(Icons.adb, size: 20, color: AppColors.darkGreen),
            SizedBox(width: 8),
            Text('AdaiOS 管理端',
                style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: AppColors.darkGrey1)),
          ],
        ),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: _buildUserSelector(),
          ),
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
}

class _NavItem {
  const _NavItem(this.label, this.icon, this.selectedIcon);

  final String label;
  final IconData icon;
  final IconData selectedIcon;
}
