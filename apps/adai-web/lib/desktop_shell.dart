import 'package:flutter/material.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'pages/feed_page.dart';
import 'pages/memory_page.dart';
import 'pages/timeline_page.dart';
import 'pages/project_page.dart';
import 'pages/task_page.dart';
import 'pages/trading_page.dart';
import 'pages/search_page.dart';
import 'pages/profile_page.dart';

/// 桌面壳 — 两栏（左导航 + 主内容区），参考元宝电脑端。
///
/// 主内容区用 **lazy IndexedStack**：页面保活（切换不重建，Feed 对话态跨页保留），
/// 且只实例化已访问页面（首次访问才构建，避免启动时 8 页并发 HTTP）。
class DesktopShell extends StatefulWidget {
  /// 当前用户 ID（透传给各页面 ApiService）。
  final String userId;

  const DesktopShell({super.key, required this.userId});

  @override
  State<DesktopShell> createState() => _DesktopShellState();
}

class _NavItem {
  final String label;
  final IconData icon;
  const _NavItem(this.label, this.icon);
}

class _DesktopShellState extends State<DesktopShell> {
  static const List<_NavItem> _items = [
    _NavItem('Feed', Icons.chat_bubble_outline),
    _NavItem('记忆', Icons.psychology_outlined),
    _NavItem('时间线', Icons.calendar_month_outlined),
    _NavItem('项目', Icons.dashboard_outlined),
    _NavItem('任务', Icons.checklist_outlined),
    _NavItem('交易', Icons.trending_up),
    _NavItem('搜索', Icons.search),
    _NavItem('档案', Icons.person_outline),
  ];

  int _current = 0;
  final Set<int> _visited = {0}; // Feed 默认已访问
  late final ApiService _api = ApiService(userId: widget.userId);

  void _select(int i) {
    setState(() {
      _visited.add(i);
      _current = i;
    });
  }

  Widget _buildPage(int i) {
    switch (i) {
      case 0:
        return FeedPage(api: _api);
      case 1:
        return MemoryPage(api: _api);
      case 2:
        return TimelinePage(api: _api);
      case 3:
        return ProjectPage(api: _api);
      case 4:
        return TaskPage(api: _api);
      case 5:
        return TradingPage(api: _api);
      case 6:
        return SearchPage(api: _api);
      default:
        return ProfilePage(api: _api);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: Row(
        children: [
          _buildNavRail(),
          const VerticalDivider(width: 1, color: AppColors.darkBorder),
          Expanded(
            child: IndexedStack(
              index: _current,
              children: List.generate(
                _items.length,
                (i) => _visited.contains(i) ? _buildPage(i) : const SizedBox.shrink(),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNavRail() {
    return Container(
      key: const ValueKey('nav-rail'),
      width: 200,
      color: AppColors.darkSurface,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Logo
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 20, 20, 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '阿呆阿呆',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                    color: AppColors.darkGrey1,
                    letterSpacing: 1.2,
                  ),
                ),
                SizedBox(height: 3),
                Text(
                  'Personal AI OS',
                  style: TextStyle(fontSize: 10, color: AppColors.darkGrey5, letterSpacing: 0.6),
                ),
              ],
            ),
          ),
          const Divider(height: 1, color: AppColors.darkBorder),
          const SizedBox(height: 8),
          // 导航项
          for (var i = 0; i < _items.length; i++) _buildNavItem(i),
          const Spacer(),
          // 底部 userId
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            decoration: const BoxDecoration(
              border: Border(top: BorderSide(color: AppColors.darkBorder, width: 0.5)),
            ),
            child: Text(
              '@${widget.userId}',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNavItem(int i) {
    final item = _items[i];
    final selected = _current == i;
    return GestureDetector(
      onTap: () => _select(i),
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 42,
        margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 1),
        decoration: BoxDecoration(
          color: selected ? AppColors.darkGreen.withValues(alpha: 0.12) : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          children: [
            // 选中态左侧 3px 竖线（对齐 adai-app 激活卡片风格）
            Container(
              width: 3,
              height: 18,
              decoration: BoxDecoration(
                color: selected ? AppColors.darkGreen : Colors.transparent,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(width: 10),
            Icon(item.icon, size: 18, color: selected ? AppColors.darkGreen : AppColors.darkGrey4),
            const SizedBox(width: 10),
            Text(
              item.label,
              style: TextStyle(
                fontSize: 14,
                color: selected ? AppColors.darkGrey1 : AppColors.darkGrey4,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
