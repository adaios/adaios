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
  /// 是否启用 trading 插件（反哺候选入口二次门控，P2-learn5 2026-09-07：
  /// learn 开 + trading 关时旧 trading 卡仍可达候选创建 → 壳层把插件态传进来）。
  final bool tradingEnabled;

  const LearnPage({super.key, required this.api, this.tradingEnabled = true});

  @override
  State<LearnPage> createState() => _LearnPageState();
}

class _LearnPageState extends State<LearnPage> {
  LearnTreeResponse? _tree;
  bool _loading = true;
  String? _error;
  String? _selectedGroup; // 'ai' | 'trading' | 'other'
  int _selectedIndex = -1;
  bool _busy = false; // 写操作 in-flight 守卫（P2-learn10：双击双提交/双 snack）

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

  /// 「整理新内容」喂入弹窗（2026-09-10 learn 喂入入口批）。
  /// 提交式：POST 立即返回 → 后台消化（几十秒级 LLM）→ 弹窗内轮询 /learn/digest/status
  /// → done 关弹窗并定位打开新卡；failed 弹窗内人话可重试；超时提示稍后刷新（素材已留存 _raw/）。
  Future<void> _openDigestDialog() async {
    final result = await showDialog<({String type, String title})>(
      context: context,
      builder: (_) => _DigestDialog(api: widget.api),
    );
    if (result == null || !mounted) return;
    _showSnack('已沉淀学习卡片《${result.title}》');
    await _refreshAndOpen(result.type, result.title);
  }

  /// 消化完成：整树刷新后定位并打开新卡（2026-09-10 喂入入口批）。
  Future<void> _refreshAndOpen(String type, String title) async {
    await _load();
    if (!mounted || _tree == null) return;
    for (final g in _tree!.groups) {
      final group = g.$1.contains('AI') ? 'ai' : (g.$1.contains('交易') ? 'trading' : 'other');
      if (group != type) continue;
      for (var i = 0; i < g.$2.length; i++) {
        if (g.$2[i].title == title) {
          setState(() {
            _selectedGroup = g.$1;
            _selectedIndex = i;
          });
          return;
        }
      }
    }
    // 极端兜底：树里没找到新卡（同名被拒/组异常），回落整树默认选中即可
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      PageHeader(
        title: '学习',
        subtitle: '消化沉淀的知识卡片',
        actions: [
          IconButton(
            onPressed: () => _openDigestDialog(),
            icon: const Icon(Icons.add, size: 16),
            color: AppColors.darkGreen,
            tooltip: '整理新内容（粘贴字幕/文章，阿呆消化成卡片）',
          ),
          IconButton(
            onPressed: () => _openCandidatesDialog(),
            icon: const Icon(Icons.inbox_outlined, size: 16),
            color: AppColors.darkGrey4,
            tooltip: '反哺候选（交易规则建议，审核后融合）',
          ),
          IconButton(
            onPressed: () => _openReviewSettingDialog(),
            icon: const Icon(Icons.notifications_outlined, size: 16),
            color: AppColors.darkGrey4,
            tooltip: '复习提醒开关',
          ),
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
      return Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const Text('还没有学习卡片\n点右上角「＋」粘贴字幕或文章原文，阿呆帮你消化沉淀成卡片',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 13, height: 1.8, color: AppColors.darkGrey5)),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: _openDigestDialog,
            icon: const Icon(Icons.add, size: 14),
            label: const Text('整理新内容'),
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGreen,
              side: const BorderSide(color: AppColors.darkBorder),
            ),
          ),
        ]),
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
          Row(children: [
            Expanded(
              child: Text(card.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: isSelected ? AppColors.darkGrey1 : AppColors.darkGrey3,
                    fontWeight: isSelected ? FontWeight.w600 : FontWeight.w400,
                  )),
            ),
            const SizedBox(width: 6),
            _statusBadge(card.status, small: true),
          ]),
          const SizedBox(height: 2),
          Text(_metaLine(card),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
        ]),
      ),
    );
  }

  /// 状态徽标：new=待复习（灰）/ review=复习中（橙）/ done=已完成（绿）。
  Widget _statusBadge(String status, {bool small = false}) {
    final (text, color) = switch (status) {
      'review' => ('复习中', AppColors.darkOrange),
      'done' => ('已完成', AppColors.darkGreen),
      _ => ('待复习', AppColors.darkGrey5),
    };
    return Container(
      padding: EdgeInsets.symmetric(horizontal: small ? 5 : 8, vertical: small ? 1 : 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(text,
          style: TextStyle(
              fontSize: small ? 9 : 11, fontWeight: FontWeight.w600, color: color)),
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
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: Text(card.title,
                style: const TextStyle(
                    fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.darkGrey1, height: 1.4)),
          ),
          const SizedBox(width: 12),
          Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            _statusBadge(card.status),
            const SizedBox(height: 10),
            ..._actionButtons(tree, card),
          ]),
        ]),
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
          const SizedBox(height: 16),
        ],
        _sectionTitle('复述'),
        if (card.retell.isNotEmpty)
          _bodyText(card.retell)
        else
          const Text('还没写复述。自己写 100-200 字才是真消化——点击右上「写复述」或让阿呆帮你改。',
              style: TextStyle(fontSize: 12.5, color: AppColors.darkGrey5, height: 1.6)),
      ]),
    );
  }

  /// 详情操作（状态推进 / 写复述 / 反哺候选）——按卡片状态与类型呈现。
  List<Widget> _actionButtons(LearnTreeResponse tree, LearnCardDto card) {
    final buttons = <Widget>[];
    if (card.status == 'new') {
      buttons.add(_actionChip('去复习', Icons.auto_stories, () => _changeStatus(tree, card, 'review')));
    } else if (card.status == 'review') {
      buttons.add(_actionChip('标记完成', Icons.check_circle_outline, () => _changeStatus(tree, card, 'done')));
    }
    buttons.add(_actionChip('写复述', Icons.edit_outlined, () => _openRetellDialog(tree, card)));
    if (card.type == 'trading' && widget.tradingEnabled) {
      if (card.tradeRelated && card.status != 'done') {
        buttons.add(_actionChip('反哺候选', Icons.rocket_launch_outlined, () => _createCandidate(tree, card)));
      } else {
        // P2-learn4：不可反哺要给原因，不能静默无按钮（用户不知道为何不能反哺）
        final why = card.status == 'done'
            ? '已完成消化，如需反哺请先「再看一遍」转回复习中'
            : '未标注涉及可执行交易规则，暂不能反哺候选';
        buttons.add(Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Tooltip(
            message: why,
            child: Opacity(
              opacity: 0.55,
              child: _actionChip('反哺候选（不可用）', Icons.rocket_launch_outlined, () {}),
            ),
          ),
        ));
      }
    }
    return buttons;
  }

  Widget _actionChip(String label, IconData icon, VoidCallback onTap) {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(6),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          decoration: BoxDecoration(
            border: Border.all(color: AppColors.darkBorder),
            borderRadius: BorderRadius.circular(6),
          ),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 12, color: AppColors.darkGrey4),
            const SizedBox(width: 5),
            Text(label,
                style: const TextStyle(fontSize: 11.5, color: AppColors.darkGrey3)),
          ]),
        ),
      ),
    );
  }

  // ── V2 操作 ──

  Future<void> _changeStatus(LearnTreeResponse tree, LearnCardDto card, String target) async {
    if (_busy) return; // P2-learn10 双击守卫
    setState(() => _busy = true);
    try {
      final updated = await widget.api.updateLearnStatus(
          type: card.type, title: card.title, status: target);
      if (!mounted) return;
      _replaceCard(tree, updated);
      _showSnack('已标记「${_statusLabel(target)}」');
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e)); // P1-learn3：透出后端人话
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _createCandidate(LearnTreeResponse tree, LearnCardDto card) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await widget.api.createLearnCandidate(type: card.type, title: card.title);
      if (!mounted) return;
      _showSnack('已生成规则候选——点页面右上「收件箱」图标可查看/删除，审核后融合进交易规则');
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e)); // P1-learn3
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openRetellDialog(LearnTreeResponse tree, LearnCardDto card) async {
    final controller = TextEditingController(text: card.retell);
    final saved = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: Text('写复述 · ${card.title}',
            style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 460,
          child: TextField(
            controller: controller,
            maxLines: 10,
            maxLength: 500,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, height: 1.6),
            decoration: const InputDecoration(
              hintText: '用自己的话写 100-200 字：这段内容关键是什么？和我知道的有什么关联？',
              hintStyle: TextStyle(color: AppColors.darkGrey6),
              border: OutlineInputBorder(),
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    if (saved != true || !mounted) return;
    final retell = controller.text.trim();
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final updated = await widget.api.editLearnCard(
          type: card.type, title: card.title, retell: retell);
      if (!mounted) return;
      _replaceCard(tree, updated);
      _showSnack(retell.isEmpty ? '复述已清空' : '复述已保存');
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e)); // P1-learn3
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 用后端返回的更新后卡片替换树中同 type+title 的卡片（就地刷新，不整树重拉）。
  /// P2-learn9（2026-09-07）：更新请求在途时用户改点了同组另一张卡 → 响应到达不得把
  /// 选中强制拉回刚更新的卡（浏览被打断）——仅当当前选中仍是「这张被更新的卡」才回写 index。
  void _replaceCard(LearnTreeResponse tree, LearnCardDto updated) {
    setState(() {
      for (var gi = 0; gi < tree.groups.length; gi++) {
        final (label, cards) = tree.groups[gi];
        if (cards.isEmpty) continue;
        for (var i = 0; i < cards.length; i++) {
          if (cards[i].title == updated.title && cards[i].type == updated.type) {
            final list = label.contains('AI') ? tree.ai : (label.contains('交易') ? tree.trading : tree.other);
            if (i < list.length) list[i] = updated;
            // 仅当用户此刻仍选中该卡才保持/回写选中位置；已改点别处则不动
            if (_selectedGroup == label && _selectedIndex >= 0 && _selectedIndex < cards.length) {
              final cur = cards[_selectedIndex];
              if (cur.title == updated.title && cur.type == updated.type) {
                _selectedIndex = i;
              }
            }
            return;
          }
        }
      }
      _load(); // 兜底：树结构变化（如新组）时整树刷新
    });
  }

  /// 反哺候选管理（P2-learn4 前端死路修复 2026-09-07）：列表 + 删除确认。
  /// 此前 getLearnCandidates/deleteLearnCandidate 零调用——误建候选无查看/删除入口。
  Future<void> _openCandidatesDialog() async {
    final List<LearnTradingCandidateDto> items;
    try {
      items = await widget.api.getLearnCandidates();
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e));
      return;
    }
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('反哺候选（待审核）',
            style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 520,
          height: 380,
          child: items.isEmpty
              ? const Center(
                  child: Text('还没有反哺候选\n在交易类学习卡片上点「反哺候选」生成建议卡',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 13, height: 1.8, color: AppColors.darkGrey5)))
              : ListView.separated(
                  itemCount: items.length,
                  separatorBuilder: (_, _) => const Divider(height: 1, color: AppColors.darkBorder),
                  itemBuilder: (_, i) {
                    final c = items[i];
                    return ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(c.title,
                          maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
                      subtitle: Text('${c.created} · ${c.coreView.isNotEmpty ? c.coreView : "（无核心观点）"}',
                          maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                      trailing: IconButton(
                        icon: const Icon(Icons.delete_outline, size: 16, color: AppColors.darkRed),
                        tooltip: '删除候选',
                        onPressed: () async {
                          final ok = await showDialog<bool>(
                            context: ctx,
                            builder: (c2) => AlertDialog(
                              backgroundColor: AppColors.darkSurface2,
                              title: Text('删除候选《${c.title}》？',
                                  style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1)),
                              content: const Text('仅删除这条建议卡，源学习卡片不受影响。',
                                  style: TextStyle(fontSize: 12.5, color: AppColors.darkGrey5)),
                              actions: [
                                TextButton(onPressed: () => Navigator.pop(c2, false), child: const Text('取消')),
                                FilledButton(
                                  style: FilledButton.styleFrom(backgroundColor: AppColors.darkRed),
                                  onPressed: () => Navigator.pop(c2, true),
                                  child: const Text('删除'),
                                ),
                              ],
                            ),
                          );
                          if (ok != true || !ctx.mounted) return;
                          try {
                            await widget.api.deleteLearnCandidate(c.title);
                            if (!ctx.mounted) return;
                            _showSnack('已删除候选《${c.title}》');
                            _openCandidatesDialog(); // 刷新列表
                          } catch (e) {
                            if (!ctx.mounted) return;
                            _showSnack(extractApiErrorMessage(e));
                          }
                        },
                      ),
                    );
                  },
                ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('关闭')),
        ],
      ),
    );
  }

  /// 复习提醒开关（S-learn2 2026-09-07）：纯 learn 用户可自关——不再只藏在交易设置页。
  Future<void> _openReviewSettingDialog() async {
    final bool enabled;
    try {
      enabled = await widget.api.getLearnReviewEnabled();
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e));
      return;
    }
    if (!mounted) return;
    var current = enabled;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setDlg) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('复习提醒',
            style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('进入复习队列满 7 天还没完成的卡片，阿呆每晚 20:00 汇总提醒你一次（同卡 7 天内不重复推）。',
              style: TextStyle(fontSize: 12.5, height: 1.7, color: AppColors.darkGrey5)),
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(current ? '开启（每晚 20:00 提醒）' : '关闭（不再提醒复习）',
                style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3)),
            value: current,
            onChanged: (v) async {
              try {
                await widget.api.setLearnReviewEnabled(v);
                setDlg(() => current = v);
              } catch (e) {
                if (!ctx.mounted) return;
                ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(
                  content: Text(extractApiErrorMessage(e), style: const TextStyle(fontSize: 13)),
                  backgroundColor: AppColors.darkSurface2,
                ));
              }
            },
          ),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('关闭')),
        ],
      )),
    );
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context).clearSnackBars(); // P2-learn10：连点不堆积
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg, style: const TextStyle(fontSize: 13)),
      duration: const Duration(seconds: 3),
      backgroundColor: AppColors.darkSurface2,
    ));
  }

  String _statusLabel(String s) => switch (s) {
        'review' => '复习中',
        'done' => '已完成',
        _ => '待复习',
      };


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

/// 「整理新内容」喂入弹窗（2026-09-10 learn 喂入入口批，桌面端）。
/// 提交式：POST /learn/cards 立即返回 → 轮询 GET /learn/digest/status（每 2s，上限 150s）→
/// done 以 (type,title) pop 给 LearnPage 打开新卡；failed 弹窗内人话 + 可重试；
/// 超时/用户中途关闭 → 消化仍在后台继续，稍后刷新列表可见（素材已留存 learn/_raw/ 兜底）。
class _DigestDialog extends StatefulWidget {
  final ApiService api;
  const _DigestDialog({required this.api});

  @override
  State<_DigestDialog> createState() => _DigestDialogState();
}

class _DigestDialogState extends State<_DigestDialog> {
  final _contentCtl = TextEditingController();
  final _platformCtl = TextEditingController();
  final _authorCtl = TextEditingController();
  final _urlCtl = TextEditingController();
  String? _type; // null = 让阿呆自动判定
  bool _submitting = false;
  bool _polling = false;
  String? _error;
  String _progress = '';

  static const _pollInterval = Duration(seconds: 2);
  static const _pollDeadline = Duration(seconds: 150);

  @override
  void dispose() {
    _contentCtl.dispose();
    _platformCtl.dispose();
    _authorCtl.dispose();
    _urlCtl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final content = _contentCtl.text.trim();
    if (content.isEmpty) {
      setState(() => _error = '请先粘贴素材内容：视频字幕 / 文章原文 / 链接正文');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await widget.api.submitLearnDigest(
        content: content,
        type: _type,
        platform: _trimOrNull(_platformCtl),
        author: _trimOrNull(_authorCtl),
        url: _trimOrNull(_urlCtl),
      );
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _polling = true;
        _progress = '已受理，阿呆开始消化…';
      });
      await _poll();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = extractApiErrorMessage(e);
      });
    }
  }

  String? _trimOrNull(TextEditingController c) {
    final v = c.text.trim();
    return v.isEmpty ? null : v;
  }

  Future<void> _poll() async {
    final deadline = DateTime.now().add(_pollDeadline);
    while (mounted && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(_pollInterval);
      if (!mounted) return;
      final LearnDigestJob job;
      try {
        job = await widget.api.getLearnDigestStatus();
      } catch (e) {
        if (!mounted) return;
        setState(() => _progress = '查询消化进度失败（${extractApiErrorMessage(e)}），继续等待…');
        continue;
      }
      if (!mounted) return;
      if (job.isDone) {
        Navigator.pop(context, (type: job.type, title: job.title));
        return;
      }
      if (job.isFailed) {
        setState(() {
          _polling = false;
          _error = job.message.isEmpty ? '消化失败，素材已留存（learn/_raw/），可稍后重试' : job.message;
        });
        return;
      }
      setState(() => _progress = '正在消化中，通常 1-3 分钟…');
    }
    if (!mounted) return;
    setState(() {
      _polling = false;
      _error = '消化仍在后台进行（AI 生成较慢）。素材已留存，稍后刷新列表即可看到新卡片；也可以再试一次。';
    });
  }

  @override
  Widget build(BuildContext context) {
    final polling = _polling;
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: Text(polling ? '整理新内容 · 消化中' : '整理新内容',
          style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
      content: SizedBox(width: 600, child: polling ? _buildPolling() : _buildForm()),
      actions: polling
          ? [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('后台继续，稍后刷新查看',
                    style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
              ),
            ]
          : [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('取消', style: TextStyle(fontSize: 13)),
              ),
              FilledButton(
                onPressed: _submitting ? null : _submit,
                style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
                child: _submitting
                    ? const SizedBox(
                        width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : const Text('让阿呆消化'),
              ),
            ],
    );
  }

  Widget _buildPolling() {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 20),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 2.5)),
        const SizedBox(height: 14),
        Text(_progress,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey4, height: 1.6)),
      ]),
    );
  }

  Widget _buildForm() {
    return SingleChildScrollView(
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        TextField(
          controller: _contentCtl,
          maxLines: 11,
          maxLength: 50000,
          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, height: 1.6),
          decoration: const InputDecoration(
            hintText: '粘贴素材内容：视频字幕 / 文章原文 / 链接正文…\n（阿呆只做结构化整理，不代抓取外部链接；纯链接请先粘贴正文）',
            hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 12.5, height: 1.6),
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 10),
        DropdownButtonFormField<String>(
          initialValue: _type,
          isDense: true,
          decoration: const InputDecoration(
            labelText: '内容类型',
            labelStyle: TextStyle(fontSize: 12, color: AppColors.darkGrey5),
            border: OutlineInputBorder(),
            contentPadding: EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          ),
          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
          items: const [
            DropdownMenuItem<String>(value: null, child: Text('让阿呆自动判定', style: TextStyle(fontSize: 13))),
            DropdownMenuItem<String>(value: 'ai', child: Text('AI / 技术', style: TextStyle(fontSize: 13))),
            DropdownMenuItem<String>(value: 'trading', child: Text('交易', style: TextStyle(fontSize: 13))),
            DropdownMenuItem<String>(value: 'other', child: Text('其他', style: TextStyle(fontSize: 13))),
          ],
          onChanged: _submitting ? null : (v) => setState(() => _type = v),
        ),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(
            child: TextField(
              controller: _authorCtl,
              style: const TextStyle(fontSize: 12.5, color: AppColors.darkGrey1),
              decoration: const InputDecoration(
                hintText: '作者 / UP 主（可选）',
                hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 12),
                isDense: true,
                border: OutlineInputBorder(),
              ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: TextField(
              controller: _platformCtl,
              style: const TextStyle(fontSize: 12.5, color: AppColors.darkGrey1),
              decoration: const InputDecoration(
                hintText: '来源平台（可选）',
                hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 12),
                isDense: true,
                border: OutlineInputBorder(),
              ),
            ),
          ),
        ]),
        const SizedBox(height: 10),
        TextField(
          controller: _urlCtl,
          style: const TextStyle(fontSize: 12.5, color: AppColors.darkGrey1),
          decoration: const InputDecoration(
            hintText: '原文链接（可选）',
            hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 12),
            isDense: true,
            border: OutlineInputBorder(),
          ),
        ),
        if (_error != null) ...[
          const SizedBox(height: 10),
          Text(_error!,
              style: const TextStyle(fontSize: 12.5, color: AppColors.darkRed, height: 1.6)),
        ],
        const SizedBox(height: 4),
        const Text('消化后卡片按类型归档，进入复习队列可定期回看；交易类会提示是否反哺规则候选。',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      ]),
    );
  }
}
