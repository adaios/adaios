import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import 'accounts/accounts_page.dart';

/// 管理端骨架 — 侧边导航（宽屏）/ 底部导航（窄屏）。
/// 本期只有「账号」主区；数据 / 系统 / 知识为后续扩展占位。
class AdminShell extends StatefulWidget {
  const AdminShell({super.key});

  @override
  State<AdminShell> createState() => _AdminShellState();
}

class _AdminShellState extends State<AdminShell> {
  int _index = 0;

  static const List<_NavItem> _items = [
    _NavItem('账号', Icons.manage_accounts_outlined, Icons.manage_accounts),
    _NavItem('数据', Icons.storage_outlined, Icons.storage),
    _NavItem('系统', Icons.settings_outlined, Icons.settings),
    _NavItem('知识', Icons.menu_book_outlined, Icons.menu_book),
  ];

  @override
  Widget build(BuildContext context) {
    final pages = <Widget>[
      const AccountsPage(),
      const _PlaceholderPage(
        icon: Icons.storage_outlined,
        title: '数据',
        description: '记录 / 记忆 / 知识库 数据管理',
      ),
      const _PlaceholderPage(
        icon: Icons.settings_outlined,
        title: '系统',
        description: '系统配置与运行状态',
      ),
      const _PlaceholderPage(
        icon: Icons.menu_book_outlined,
        title: '知识',
        description: 'Domain OS 知识资产管理',
      ),
    ];

    return Scaffold(
      backgroundColor: AppColors.darkBg,
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

  Widget _buildRail() {
    return NavigationRail(
      selectedIndex: _index,
      onDestinationSelected: (i) => setState(() => _index = i),
      labelType: NavigationRailLabelType.all,
      minWidth: 76,
      leading: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Column(
          children: const [
            Icon(Icons.adb, size: 26, color: AppColors.darkGreen),
            SizedBox(height: 4),
            Text('AdaiOS',
                style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    color: AppColors.darkGrey4)),
            Text('管理端',
                style: TextStyle(fontSize: 9, color: AppColors.darkGrey6)),
          ],
        ),
      ),
      trailing: const SizedBox(height: 8),
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

/// 未开放区域的占位页。
class _PlaceholderPage extends StatelessWidget {
  const _PlaceholderPage({
    required this.icon,
    required this.title,
    required this.description,
  });

  final IconData icon;
  final String title;
  final String description;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.darkBorder, width: 0.5),
              ),
              child: Icon(icon, size: 34, color: AppColors.darkGrey5),
            ),
            const SizedBox(height: 16),
            Text(title,
                style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: AppColors.darkGrey1)),
            const SizedBox(height: 6),
            Text('$description（开发中）',
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            const SizedBox(height: 4),
            const Text('敬请期待',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
          ],
        ),
      ),
    );
  }
}
