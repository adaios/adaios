import 'package:flutter/material.dart';
import '../../services/knowledge_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/page_header.dart';
import 'os_tree_tab.dart';
import 'terms_tab.dart';

/// 知识浏览主区 — 两个子页签：os/ 资产树 + 术语/规则。
/// 真实后端 /admin/knowledge（系统级，无 X-User-Id）。
class KnowledgePage extends StatefulWidget {
  const KnowledgePage({super.key, this.store});

  /// 可注入 store（测试用 Fake）；默认真实 [KnowledgeApiStore]。
  final KnowledgeStore? store;

  @override
  State<KnowledgePage> createState() => _KnowledgePageState();
}

class _KnowledgePageState extends State<KnowledgePage> {
  late final KnowledgeStore _store = widget.store ?? KnowledgeApiStore();

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: DefaultTabController(
        length: 2,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
              child: PageHeader(
                icon: Icons.menu_book_outlined,
                title: '知识浏览',
                subtitle: 'os/ 资产目录树 · 术语与规则',
              ),
            ),
            const SizedBox(height: 12),
            _buildTabBar(),
            Expanded(
              child: TabBarView(
                children: [
                  OsTreeTab(store: _store),
                  TermsTab(store: _store),
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
        Tab(text: 'os/ 资产'),
        Tab(text: '术语/规则'),
      ],
    );
  }
}
