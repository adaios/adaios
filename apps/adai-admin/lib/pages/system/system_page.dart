import 'package:flutter/material.dart';
import '../../services/system_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/page_header.dart';
import 'feedback_tab.dart';
import 'feed_tab.dart';
import 'maintenance_tab.dart';
import 'market_tab.dart';
import 'reviews_tab.dart';

/// 系统操作台主区 — 五个子页签：Feed / 行情 / 复盘 / 反哺 / 维护。
/// 真实后端数据（per-user，带 X-User-Id）。
class SystemPage extends StatefulWidget {
  const SystemPage({super.key, this.store, this.userId = 'default'});

  /// 可注入 store（测试用 Fake）；默认真实 [SystemApiStore]。
  final SystemStore? store;

  /// 当前选中的用户 ID（per-user 请求的 X-User-Id）。
  final String userId;

  @override
  State<SystemPage> createState() => _SystemPageState();
}

class _SystemPageState extends State<SystemPage> {
  late final SystemStore _store =
      widget.store ?? SystemApiStore(userId: widget.userId);

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: DefaultTabController(
        length: 5,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
              child: PageHeader(
                icon: Icons.settings_outlined,
                title: '系统操作台',
                subtitle:
                    '用户「${widget.userId}」· Feed 预览 · 行情快照 · 复盘 · 知识反哺 · 维护操作',
              ),
            ),
            const SizedBox(height: 12),
            _buildTabBar(),
            Expanded(
              child: TabBarView(
                children: [
                  FeedTab(store: _store),
                  MarketTab(store: _store),
                  ReviewsTab(store: _store),
                  FeedbackTab(store: _store),
                  MaintenanceTab(store: _store),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTabBar() {
    return const TabBar(
      isScrollable: true,
      tabAlignment: TabAlignment.start,
      labelColor: AppColors.darkGreen,
      unselectedLabelColor: AppColors.darkGrey5,
      indicatorColor: AppColors.darkGreen,
      dividerColor: AppColors.darkBorder,
      indicatorSize: TabBarIndicatorSize.label,
      labelStyle: TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
      unselectedLabelStyle: TextStyle(fontSize: 13),
      tabs: [
        Tab(text: 'Feed'),
        Tab(text: '行情'),
        Tab(text: '复盘'),
        Tab(text: '反哺'),
        Tab(text: '维护'),
      ],
    );
  }
}
