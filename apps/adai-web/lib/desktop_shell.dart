import 'package:flutter/material.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'widgets/hoverable.dart';
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

  /// 切换账号回调（AdaiWebApp 提供：push 选号页 → 选定后重建整树）。
  final VoidCallback? onSwitchAccount;

  /// 测试注入：覆盖内部 ApiService（默认按 userId 创建）。
  final ApiService? api;

  const DesktopShell({super.key, required this.userId, this.onSwitchAccount, this.api});

  @override
  State<DesktopShell> createState() => _DesktopShellState();
}

/// 导航项：plugin 为空 = 基础服务常驻；trading/project = 需启用对应插件才可见（RFC 20260814）。
class _NavEntry {
  final String label;
  final IconData icon;
  final String? plugin;
  final Widget Function(ApiService api) pageBuilder;
  const _NavEntry(this.label, this.icon, this.plugin, this.pageBuilder);
}

class _DesktopShellState extends State<DesktopShell> {
  /// 全量模块表：项目/交易为插件域，其余为 Kernel 基础服务（对话流/记忆/时间线/任务/搜索/档案）。
  static final List<_NavEntry> _allEntries = [
    _NavEntry('对话流', Icons.chat_bubble_outline, null, (api) => FeedPage(api: api)),
    _NavEntry('记忆', Icons.psychology_outlined, null, (api) => MemoryPage(api: api)),
    _NavEntry('时间线', Icons.calendar_month_outlined, null, (api) => TimelinePage(api: api)),
    _NavEntry('项目', Icons.dashboard_outlined, 'project', (api) => ProjectPage(api: api)),
    _NavEntry('任务', Icons.checklist_outlined, null, (api) => TaskPage(api: api)),
    _NavEntry('交易', Icons.trending_up, 'trading', (api) => TradingPage(api: api)),
    _NavEntry('搜索', Icons.search, null, (api) => SearchPage(api: api)),
    _NavEntry('档案', Icons.person_outline, null, (api) => ProfilePage(api: api)),
  ];

  /// 启用插件（RFC 20260814）：导航渲染 / IndexedStack / _buildPage 共用同一可见列表。
  Set<String> _plugins = {};

  List<_NavEntry> get _items =>
      _allEntries.where((e) => e.plugin == null || _plugins.contains(e.plugin)).toList();

  /// 当前页按稳定标识（label）跟踪（REVIEW P1-5）——插件异步加载后中部插入不会让位置索引错位。
  String _currentLabel = _allEntries.first.label;
  final Set<String> _visited = {_allEntries.first.label}; // Feed 默认已访问

  /// 当前页在可见列表中的索引（label 不在列表（理论上不发生的防御）→ 回落 0）。
  int get _currentIndex {
    final i = _items.indexWhere((e) => e.label == _currentLabel);
    return i >= 0 ? i : 0;
  }

  late final ApiService _api = widget.api ?? ApiService(userId: widget.userId);

  @override
  void initState() {
    super.initState();
    _loadPlugins();
  }

  /// 拉取当前用户启用插件；失败保守只显基础服务（无插件默认），核心功能仍可用。
  Future<void> _loadPlugins() async {
    try {
      final plugins = await _api.getMyPlugins();
      if (!mounted) return;
      // W-P3-2（2026-08-17）：重试成功后清除残留的失败 SnackBar
      ScaffoldMessenger.of(context).hideCurrentSnackBar();
      setState(() {
        _plugins = plugins.toSet();
        // P1-5：插件加载后当前页按 label 重解析；若当前页被隐藏（仅插件移除场景）回落首个可见项
        // deep 前端（2026-08-17）：_items.first 空列表防御（全门控化时 RangeError）
        if (_items.isNotEmpty && _items.indexWhere((e) => e.label == _currentLabel) < 0) {
          _currentLabel = _items.first.label;
          _visited.add(_currentLabel);
        }
      });
    } catch (_) {
      // REVIEW P2-5：失败不再静默吞错——给反馈 + 重试入口（仅显基础服务，不阻塞壳渲染）
      if (!mounted) return;
      // W-P3-2（2026-08-17）：连续失败不清空会队列堆积依次播放 → 先 clear 再 show
      final messenger = ScaffoldMessenger.of(context);
      messenger.clearSnackBars();
      messenger.showSnackBar(SnackBar(
        content: const Text('插件加载失败，仅显示基础服务'),
        action: SnackBarAction(label: '重试', onPressed: _loadPlugins),
        duration: const Duration(seconds: 4),
      ));
    }
  }

  void _select(String label) {
    setState(() {
      // deep 前端（2026-08-17）：tap 直接传 label，避免 build↔tap 间列表变更亚帧窗口取错条目
      _visited.add(label);
      _currentLabel = label;
    });
  }

  Widget _buildPage(int i) {
    final entry = _items[i];
    // 交易页：传入当前可见页 label → 每次切到交易页自动刷新（行情/盈亏实时）
    // P1-交易1 修复（2026-08-17）：label 是中文显示名（'交易'），内部标识在 plugin 字段（'trading'）
    // 之前判 entry.label=='trading' 恒 false → 切入自动刷新从未生效（死代码）
    if (entry.plugin == 'trading') {
      return TradingPage(api: _api, currentPage: _currentLabel);
    }
    return entry.pageBuilder(_api);
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
              index: _currentIndex,
              children: List.generate(
                _items.length,
                // W-P3-1 + deep 前端（2026-08-17）：ValueKey(label) 保活——插件中部插入导致
                // 已访问页 widget 槽位移时按 label 复用 state（此前 state 会被重置）
                (i) => _visited.contains(_items[i].label)
                    ? KeyedSubtree(
                        key: ValueKey(_items[i].label),
                        child: _buildPage(i),
                      )
                    : const SizedBox.shrink(),
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
          // 底部 userId（v1.0.0 多账号：点击切换账号）
          // #229：Tooltip 提示切换 + hover 高亮；#201：超长 userId Expanded+ellipsis 防横向溢出
          Tooltip(
            message: '切换账号（@${widget.userId}）',
            waitDuration: const Duration(milliseconds: 400),
            child: Hoverable(
              builder: (context, isHovered) => GestureDetector(
                onTap: widget.onSwitchAccount,
                behavior: HitTestBehavior.opaque,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                  decoration: BoxDecoration(
                    color: isHovered ? AppColors.darkSurface2.withValues(alpha: 0.5) : Colors.transparent,
                    border: const Border(top: BorderSide(color: AppColors.darkBorder, width: 0.5)),
                  ),
                  child: Row(children: [
                    Icon(Icons.swap_horiz, size: 13, color: AppColors.darkGrey5),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        '@${widget.userId}',
                        style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5),
                        overflow: TextOverflow.ellipsis,
                        maxLines: 1,
                      ),
                    ),
                  ]),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNavItem(int i) {
    final item = _items[i];
    final selected = item.label == _currentLabel;
    return GestureDetector(
      onTap: () => _select(item.label),
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
