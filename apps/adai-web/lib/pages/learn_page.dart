import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../services/models/learn_models.dart';
import '../theme/app_colors.dart';
import '../widgets/page_header.dart';

/// learn 资产页（RFC 20260829 L2 呈现·桌面端）。
/// master-detail：左 = 卡片目录（按 ai/trading/other 分组，点选），右 = 单篇卡片全文渲染。
/// 数据源：GET /learn/tree（分组）+ 组内卡片即含全文字段（无需二次请求）。
/// 空态/加载失败降级（保活页 IndexedStack 下 initState 只拉一次 → 补刷新入口）。
class LearnPage extends StatefulWidget {
  final ApiService api;

  const LearnPage({super.key, required this.api});

  @override
  State<LearnPage> createState() => _LearnPageState();
}

class _LearnPageState extends State<LearnPage> {
  LearnTreeResponse? _tree;
  bool _loading = true;
  String? _error;
  String? _selectedGroup; // 'ai' | 'trading' | 'other'
  int _selectedIndex = -1;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final tree = await widget.api.getLearnTree();
      if (!mounted) return;
      setState(() {
        _tree = tree;
        _loading = false;
        _error = null;
        // 刷新保留选择（若仍在树内），否则回落第一组第一张卡
        final keep = _resolveCurrentCard(tree);
        _selectedGroup = keep?.$1;
        _selectedIndex = keep?.$2 ?? -1;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '学习卡片加载失败，请重试';
      });
    }
  }

  (String, int)? _resolveCurrentCard(LearnTreeResponse tree) {
    final groups = tree.groups;
    if (groups.isEmpty) return null;
    if (_selectedGroup != null) {
      for (var g = 0; g < groups.length; g++) {
        if (groups[g].$1.contains(_selectedGroup!) && _selectedIndex >= 0 &&
            _selectedIndex < groups[g].$2.length) {
          return (groups[g].$1, _selectedIndex);
        }
      }
    }
    // 回落：第一非空组第一张
    for (final g in groups) {
      if (g.$2.isNotEmpty) return (g.$1, 0);
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      PageHeader(
        title: '学习',
        subtitle: '消化沉淀的知识卡片',
        actions: [
          IconButton(
            onPressed: _load,
            icon: const Icon(Icons.refresh, size: 16),
            color: AppColors.darkGrey4,
            tooltip: '刷新',
          ),
        ],
      ),
      Expanded(
        child: _buildBody(),
      ),
    ]);
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(_error!, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
          const SizedBox(height: 10),
          OutlinedButton(onPressed: _load, child: const Text('重试')),
        ]),
      );
    }
    final tree = _tree;
    if (tree == null || tree.isEmpty) {
      return const Center(
        child: Text('还没有学习卡片\n在对话里说「整理这个视频/文章」，阿呆帮你沉淀成卡片',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 13, height: 1.8, color: AppColors.darkGrey5)),
      );
    }
    return Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      SizedBox(width: 260, child: _buildTree(tree)),
      const VerticalDivider(width: 1, color: AppColors.darkBorder),
      Expanded(child: _buildDetail(tree)),
    ]);
  }

  // ── 左：目录树（分组 + 卡片列表）──

  Widget _buildTree(LearnTreeResponse tree) {
    return ListView(
      padding: const EdgeInsets.symmetric(vertical: 8),
      children: [
        for (final (label, cards) in tree.groups) ..._groupSection(label, cards),
      ],
    );
  }

  List<Widget> _groupSection(String label, List<LearnCardDto> cards) {
    final group = label.contains('AI') ? 'ai' : (label.contains('交易') ? 'trading' : 'other');
    final selected = _selectedGroup == label;
    return [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 4),
        child: Text(label,
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: selected ? AppColors.darkGreen : AppColors.darkGrey5)),
      ),
      if (cards.isEmpty)
        const Padding(
          padding: EdgeInsets.fromLTRB(16, 2, 16, 6),
          child: Text('（空）', style: TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
        )
      else
        for (var i = 0; i < cards.length; i++)
          _cardItem(label, cards[i], i, group),
      const SizedBox(height: 6),
    ];
  }

  Widget _cardItem(String label, LearnCardDto card, int index, String group) {
    final isSelected = _selectedGroup == label && _selectedIndex == index;
    return GestureDetector(
      key: ValueKey('learn-card-$group-$index'),
      onTap: () => setState(() {
        _selectedGroup = label;
        _selectedIndex = index;
      }),
      behavior: HitTestBehavior.opaque,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 1),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
        decoration: BoxDecoration(
          color: isSelected ? AppColors.darkGreen.withValues(alpha: 0.12) : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(card.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 13,
                color: isSelected ? AppColors.darkGrey1 : AppColors.darkGrey3,
                fontWeight: isSelected ? FontWeight.w600 : FontWeight.w400,
              )),
          const SizedBox(height: 2),
          Text(_metaLine(card),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
        ]),
      ),
    );
  }

  String _metaLine(LearnCardDto c) {
    final parts = <String>[];
    if (c.created.isNotEmpty) parts.add(c.created);
    if (c.author.isNotEmpty) parts.add(c.author);
    if (c.tags.isNotEmpty) parts.add(c.tags.join(' · '));
    return parts.join('  ');
  }

  // ── 右：单篇卡片全文渲染 ──

  Widget _buildDetail(LearnTreeResponse tree) {
    if (_selectedGroup == null || _selectedIndex < 0) return const SizedBox.shrink();
    final groupCards = tree.groups.firstWhere((g) => g.$1 == _selectedGroup).$2;
    if (_selectedIndex >= groupCards.length) return const SizedBox.shrink();
    final card = groupCards[_selectedIndex];
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(28, 20, 28, 40),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(card.title,
            style: const TextStyle(
                fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.darkGrey1, height: 1.4)),
        const SizedBox(height: 8),
        _typeTag(card.type),
        const SizedBox(height: 6),
        _sourceLine(card),
        const SizedBox(height: 18),
        if (card.coreView.isNotEmpty) ...[
          _sectionTitle('核心观点'),
          _bodyText(card.coreView),
          const SizedBox(height: 16),
        ],
        if (card.keyPoints.isNotEmpty) ...[
          _sectionTitle('关键要点'),
          for (final kp in card.keyPoints) _bullet(kp),
          const SizedBox(height: 16),
        ],
        if (card.questions.isNotEmpty) ...[
          _sectionTitle('我的疑问'),
          for (final q in card.questions) _bullet(q),
          const SizedBox(height: 16),
        ],
        if (card.tradeRelated) ...[
          _sectionTitle('交易相关'),
          _bodyText(card.tradeNote.isNotEmpty
              ? '涉及可执行交易规则（备注：${card.tradeNote}），规则变更须用户拍板'
              : '涉及可执行交易规则，规则变更须用户拍板'),
        ],
      ]),
    );
  }

  Widget _typeTag(String type) {
    final (text, color) = switch (type) {
      'ai' => ('AI / 技术', AppColors.darkGreen),
      'trading' => ('交易', AppColors.darkRed),
      _ => ('其他', AppColors.darkGrey4),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(text,
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: color)),
    );
  }

  Widget _sourceLine(LearnCardDto c) {
    final parts = <String>[];
    if (c.author.isNotEmpty) parts.add('作者：${c.author}');
    if (c.platform.isNotEmpty) parts.add('来源：${c.platform}');
    if (c.url.isNotEmpty) parts.add(c.url);
    if (parts.isEmpty) return const SizedBox.shrink();
    return Text(parts.join(' · '),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5));
  }

  Widget _sectionTitle(String t) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Text(t,
            style: const TextStyle(
                fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.darkGrey2)),
      );

  Widget _bodyText(String t) => Text(t,
      style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey2, height: 1.7));

  Widget _bullet(String t) => Padding(
        padding: const EdgeInsets.only(bottom: 5),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('•  ', style: TextStyle(fontSize: 13.5, color: AppColors.darkGrey4)),
          Expanded(
            child: Text(t,
                style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey2, height: 1.6)),
          ),
        ]),
      );
}
