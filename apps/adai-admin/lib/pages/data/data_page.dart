import 'package:flutter/material.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/page_header.dart';
import 'data_tree_tab.dart';
import 'identity_tab.dart';
import 'memory_tab.dart';
import 'positions_tab.dart';
import 'records_tab.dart';
import 'tasks_tab.dart';

/// 数据管理主区 — 六个子页签：记录 / 记忆 / 档案 / 任务 / 持仓 / 文件树。
/// 真实后端数据（per-user，带 X-User-Id）。
class DataPage extends StatefulWidget {
  const DataPage({super.key, this.store, this.userId = 'default'});

  /// 可注入 store（测试用 Fake）；默认真实 [DataApiStore]。
  final DataStore? store;

  /// 当前选中的用户 ID（per-user 请求的 X-User-Id）。
  final String userId;

  @override
  State<DataPage> createState() => _DataPageState();
}

class _DataPageState extends State<DataPage> {
  late final DataStore _store =
      widget.store ?? DataApiStore(userId: widget.userId);

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: DefaultTabController(
        length: 6,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
              child: PageHeader(
                icon: Icons.storage_outlined,
                title: '数据管理',
                subtitle:
                    '用户「${widget.userId}」· 记录 · 记忆 · 档案 · 任务 · 持仓 · data/ 文件树',
              ),
            ),
            const SizedBox(height: 12),
            _buildTabBar(),
            Expanded(
              child: TabBarView(
                children: [
                  RecordsTab(store: _store),
                  MemoryTab(store: _store),
                  IdentityTab(store: _store),
                  TasksTab(store: _store),
                  PositionsTab(store: _store),
                  DataTreeTab(store: _store),
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
        Tab(text: '记录'),
        Tab(text: '记忆'),
        Tab(text: '档案'),
        Tab(text: '任务'),
        Tab(text: '持仓'),
        Tab(text: '文件'),
      ],
    );
  }
}
