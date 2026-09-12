import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../services/api_service.dart';
import '../services/models/learn_models.dart';
import '../theme/app_colors.dart';
import '../widgets/page_header.dart';

/// learn 资产页（RFC 20260829 L2 呈现·桌面端）。
/// master-detail：左 = 卡片目录（type → topic 两级分组，点选），右 = 单篇卡片全文渲染。
/// 数据源：GET /learn/tree（分组目录）+ GET /learn/content（该卡 md 原文，2026-09-12 完整升级批：
/// 列表只回产品建模的四段，Mac 侧整理的卡另有「关键内容详解/金句/与主题概念的关系」等段，
/// 必须读原文才显示得全）。
/// 空态/加载失败降级（保活页 IndexedStack 下 initState 只拉一次 → 补刷新入口）。
class LearnPage extends StatefulWidget {
  final ApiService api;
  /// 是否启用 trading 插件（反哺候选入口二次门控，P2-learn5 2026-09-07：
  /// learn 开 + trading 关时旧 trading 卡仍可达候选创建 → 壳层把插件态传进来）。
  final bool tradingEnabled;

  /// 从对话流跳进来时要直接打开的卡（2026-09-12 完整升级批）：
  /// 壳层把 Feed 的「去学习页看看」请求传进来，本页加载完成后定位并打开该卡。
  /// id 递增——同一张卡再次被请求时也要重新定位（不能靠 type+title 去重）。
  final ({int id, String type, String title})? openCard;

  const LearnPage({super.key, required this.api, this.tradingEnabled = true, this.openCard});

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

  // ── 全文（md 原文）读取：列表给的四段不够，读全要走 /learn/content ──
  LearnCardContentDto? _content;
  bool _contentLoading = false;
  String? _contentError;
  int _contentGen = 0; // 代际令牌：换卡后旧响应不得覆盖新卡的全文

  // ── 搜索（顶部搜索框，防抖 300ms）──
  final TextEditingController _searchCtl = TextEditingController();
  Timer? _searchDebounce;
  List<LearnCardDto>? _searchHits; // null = 没在搜索
  bool _searching = false;
  String? _searchError;

  // ── 确认恢复入口（P2-learn17 2026-09-12）：进页面先看有没有等你拍板的转写 ──
  LearnDigestJob? _pendingConfirm;
  bool _confirmBusy = false;

  int _loadGen = 0; // 树加载代际（迟到响应作废）
  int _handledOpenId = -1; // 已处理过的跳转请求（避免重复定位/重复 setState）
  ({String type, String title})? _pendingOpen; // 首次构建就带跳转请求时，等树加载完再定位

  @override
  void initState() {
    super.initState();
    final req = widget.openCard;
    if (req != null) {
      _handledOpenId = req.id;
      _pendingOpen = (type: req.type, title: req.title);
    }
    _load();
    _checkPendingConfirm();
  }

  @override
  void didUpdateWidget(covariant LearnPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    final req = widget.openCard;
    if (req != null && req.id != _handledOpenId) {
      _handledOpenId = req.id;
      _openFromOutside(req.type, req.title);
    }
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchCtl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final gen = ++_loadGen;   // 代际令牌：刷新与跳转交错时，旧响应不得覆盖新树（对抗审查 P1）
    try {
      final tree = await widget.api.getLearnTree();
      if (!mounted || gen != _loadGen) return;
      // 卡片按 created 倒序（同 type 内），索引与详情取卡共用同一顺序
      tree.sortByCreatedDesc();
      setState(() {
        _tree = tree;
        _loading = false;
        _error = null;
        // 刷新保留选择（若仍在树内），否则回落第一组第一张卡
        final keep = _resolveCurrentCard(tree);
        _selectedGroup = keep?.$1;
        _selectedIndex = keep?.$2 ?? -1;
      });
      // 首次构建就带跳转请求（对话流跳进来）→ 树到位后定位该卡（它自己会读全文，不重复请求）
      final pending = _pendingOpen;
      if (pending != null) {
        _pendingOpen = null;
        _clearSearch();
        if (!_locateAndOpen(pending.type, pending.title)) {
          final fallback = _currentCard();
          if (fallback != null) _loadContent(fallback);
        }
      } else {
        final card = _currentCard();
        if (card != null) _loadContent(card);
      }
      _checkPendingConfirm();   // 每次刷新/重新进入都看一眼有没有等你拍板的转写
    } catch (_) {
      if (!mounted || gen != _loadGen) return;
      setState(() {
        _loading = false;
        _error = '学习卡片加载失败，请重试';
      });
    }
  }

  /// 进页面查一次：有任务在等用户拍板（无字幕视频要不要花钱转写）→ 顶部提示条恢复入口。
  /// P2-learn17：此前只在喂入弹窗里能点头，关了弹窗就再也点不到。
  Future<void> _checkPendingConfirm() async {
    try {
      final job = await widget.api.getLearnDigestStatus();
      if (!mounted || !job.isAwaitingConfirm) return;
      setState(() => _pendingConfirm = job);
    } catch (_) {
      // 查不到就当没有（不打扰）：真有待办，用户重新喂一次也能看到
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

  /// 当前选中的卡片（无选中/索引失效 → null）。
  LearnCardDto? _currentCard() {
    final tree = _tree;
    final group = _selectedGroup;
    if (tree == null || group == null || _selectedIndex < 0) return null;
    for (final g in tree.groups) {
      if (g.$1 == group) {
        if (_selectedIndex >= g.$2.length) return null;
        return g.$2[_selectedIndex];
      }
    }
    return null;
  }

  /// 读该卡 md 原文（换卡/刷新都重读；失败给错误态 + 重试，不假装读全了）。
  Future<void> _loadContent(LearnCardDto card) async {
    final gen = ++_contentGen;
    setState(() {
      _content = null; // 换卡先清空：旧卡的原文不得挂在新卡下面
      _contentLoading = true;
      _contentError = null;
    });
    try {
      final content = await widget.api.getLearnContent(type: card.type, title: card.title);
      if (!mounted || gen != _contentGen) return;
      setState(() {
        _content = content;
        _contentLoading = false;
      });
    } catch (_) {
      if (!mounted || gen != _contentGen) return;
      setState(() {
        _content = null;
        _contentLoading = false;
        _contentError = '这篇的原文我没读上来（可能是网络的事），我再试一次？';
      });
    }
  }

  void _select(String group, int index) {
    setState(() {
      _selectedGroup = group;
      _selectedIndex = index;
    });
    final card = _currentCard();
    if (card != null) _loadContent(card);
  }

  /// 「整理新内容」喂入弹窗（2026-09-10 learn 喂入入口批）。
  /// 提交式：POST 立即返回 → 后台消化（抓取 + 几十秒级 LLM）→ 弹窗内轮询 /learn/digest/status
  /// → done 关弹窗并定位打开新卡；needs_confirmation 弹窗内问你要不要花钱转写；
  /// failed 弹窗内人话可重试；超时提示稍后刷新（素材已留存 _raw/）。
  Future<void> _openDigestDialog({bool autoPoll = false}) async {
    final result = await showDialog<({String type, String title})>(
      context: context,
      builder: (_) => _DigestDialog(api: widget.api, autoPoll: autoPoll),
    );
    if (!mounted) return;
    _checkPendingConfirm(); // 弹窗里可能已经点过确认 → 顶部提示条跟着收掉
    if (result == null) return;
    _showSnack('已沉淀学习卡片《${result.title}》');
    await _refreshAndOpen(result.type, result.title);
  }

  /// 消化完成：整树刷新后定位并打开新卡（2026-09-10 喂入入口批）。
  Future<void> _refreshAndOpen(String type, String title) async {
    await _load();
    if (!mounted) return;
    if (!_locateAndOpen(type, title)) {
      final card = _currentCard();
      if (card != null) _loadContent(card);
    }
  }

  /// 在已加载的树里定位并打开该卡（找不到就保持当前选择，不假装打开成功）；
  /// 返回是否定位成功——没定位到时调用方仍要把当前选中卡的全文读出来。
  bool _locateAndOpen(String type, String title) {
    final tree = _tree;
    if (tree == null) return false;
    for (final g in tree.groups) {
      final group = g.$1.contains('AI') ? 'ai' : (g.$1.contains('交易') ? 'trading' : 'other');
      if (group != type) continue;
      for (var i = 0; i < g.$2.length; i++) {
        if (g.$2[i].title == title) {
          _select(g.$1, i);
          return true;
        }
      }
    }
    // 极端兜底：树里没找到新卡（同名被拒/组异常），回落整树默认选中即可
    return false;
  }

  /// 对话流跳进来要打开的那张卡（壳层请求）：清掉搜索态，定位并读全文。
  Future<void> _openFromOutside(String type, String title) async {
    _clearSearch();
    await _refreshAndOpen(type, title);
  }

  // ── 搜索（顶部搜索框；防抖 300ms）──

  void _onSearchChanged(String value) {
    _searchDebounce?.cancel();
    final q = value.trim();
    if (q.isEmpty) {
      setState(() {
        _searchHits = null;
        _searching = false;
        _searchError = null;
      });
      return;
    }
    _searchDebounce = Timer(const Duration(milliseconds: 300), () => _runSearch(q));
    setState(() {}); // 清空按钮跟着输入立刻出现（不等防抖那 300ms）
  }

  Future<void> _runSearch(String q) async {
    setState(() {
      _searching = true;
      _searchError = null;
    });
    try {
      final hits = await widget.api.searchLearnCards(q, limit: 8);
      if (!mounted || _searchCtl.text.trim() != q) return; // 期间又改了输入 → 这次结果作废
      setState(() {
        _searchHits = hits;
        _searching = false;
      });
    } catch (_) {
      if (!mounted || _searchCtl.text.trim() != q) return;
      setState(() {
        _searchHits = null;
        _searching = false;
        _searchError = '没搜成，等下再试一次';
      });
    }
  }

  void _clearSearch() {
    _searchDebounce?.cancel();
    _searchCtl.clear();
    setState(() {
      _searchHits = null;
      _searching = false;
      _searchError = null;
    });
  }

  /// 点搜索结果 → 先取消搜索（页面底部弹窗/输入框状态要收回），再定位并打开发全文。
  void _openSearchHit(LearnCardDto card) {
    _clearSearch();
    _refreshAndOpen(card.type, card.title);
  }

  // ── 转写确认恢复（P2-learn17）──

  Future<void> _answerPendingConfirm(bool confirm) async {
    if (_confirmBusy) return; // 连点守卫：一次只发一份
    setState(() => _confirmBusy = true);
    try {
      final job = await widget.api.confirmLearnTranscription(confirm);
      if (!mounted) return;
      setState(() {
        _confirmBusy = false;
        _pendingConfirm = null;
      });
      if (confirm) {
        _showSnack('好，我接着转写，弄好了在这儿告诉你');
        // 已经在转写了 → 弹窗直接进「消化中」（别装成刚要开始，也别让用户再提交一份）
        await _openDigestDialog(autoPoll: true);
      } else {
        _showSnack(job.message.isEmpty ? '好，这次先不转写（没花钱）' : job.message);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _confirmBusy = false);
      _showSnack(extractApiErrorMessage(e));
    }
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
            tooltip: '整理新内容（丢链接或粘素材，阿呆消化成卡片）',
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
      _buildSearchBar(),
      if (_pendingConfirm != null) _buildConfirmBanner(_pendingConfirm!),
      Expanded(
        child: _buildBody(),
      ),
    ]);
  }

  /// 顶部搜索框：找一张卡（关键词，服务端规则打分不烧 AI）。
  Widget _buildSearchBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 10, 24, 10),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.darkBorder, width: 0.5)),
      ),
      child: TextField(
        key: const ValueKey('learn-search'),
        controller: _searchCtl,
        onChanged: _onSearchChanged,
        style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
        decoration: InputDecoration(
          hintText: '找一张卡：输个关键词，比如「harness」',
          hintStyle: const TextStyle(color: AppColors.darkGrey6, fontSize: 12.5),
          isDense: true,
          border: const OutlineInputBorder(),
          prefixIcon: const Icon(Icons.search, size: 16, color: AppColors.darkGrey5),
          suffixIcon: _searchCtl.text.isEmpty
              ? null
              : IconButton(
                  icon: const Icon(Icons.close, size: 14, color: AppColors.darkGrey5),
                  tooltip: '清空',
                  onPressed: _clearSearch,
                ),
          contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
        ),
      ),
    );
  }

  /// 确认恢复提示条（P2-learn17）：有一件事等你拍板 + 继续转写 / 先不转写。
  Widget _buildConfirmBanner(LearnDigestJob job) {
    return Container(
      margin: const EdgeInsets.fromLTRB(24, 12, 24, 0),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.darkOrange.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.35)),
      ),
      child: Row(children: [
        const Icon(Icons.help_outline, size: 15, color: AppColors.darkOrange),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            '有一件事等你拍板：${job.message.isEmpty ? '上次那个视频要转写才能整理。' : job.message}',
            style: const TextStyle(fontSize: 12.5, color: AppColors.darkGrey2, height: 1.6),
          ),
        ),
        const SizedBox(width: 10),
        TextButton(
          onPressed: _confirmBusy ? null : () => _answerPendingConfirm(false),
          child: const Text('先不转写', style: TextStyle(fontSize: 12.5)),
        ),
        FilledButton(
          onPressed: _confirmBusy ? null : () => _answerPendingConfirm(true),
          style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
          child: const Text('继续转写', style: TextStyle(fontSize: 12.5)),
        ),
      ]),
    );
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
    if (_searchCtl.text.trim().isNotEmpty) return _buildSearchResults();
    final tree = _tree;
    if (tree == null || tree.isEmpty) {
      return Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const Text('还没有学习卡片\n点右上角「＋」丢一个 B站 / 文章链接给我，我抓原文、消化沉淀成卡片',
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
      Expanded(child: _buildDetail()),
    ]);
  }

  // ── 搜索结果（点开即读该卡全文）──

  Widget _buildSearchResults() {
    if (_searching) return const Center(child: CircularProgressIndicator());
    if (_searchError != null) {
      return Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(_searchError!, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: () => _runSearch(_searchCtl.text.trim()),
            child: const Text('重试'),
          ),
        ]),
      );
    }
    final hits = _searchHits;
    if (hits == null) return const SizedBox.shrink();
    if (hits.isEmpty) {
      return Center(
        child: Text('没找到名字或内容里有「${_searchCtl.text.trim()}」的卡。\n换个词试试，或者清空搜索翻左边的目录。',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 13, height: 1.8, color: AppColors.darkGrey5)),
      );
    }
    return ListView(
      padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 24),
      children: [
        Text('找到 ${hits.length} 张卡',
            style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
        const SizedBox(height: 8),
        for (final card in hits)
          InkWell(
            key: ValueKey('learn-search-hit-${card.type}-${card.title}'),
            onTap: () => _openSearchHit(card),
            borderRadius: BorderRadius.circular(8),
            child: Container(
              margin: const EdgeInsets.only(bottom: 6),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2.withValues(alpha: 0.5),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppColors.darkBorder),
              ),
              child: Row(children: [
                _typeTag(card.type),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(card.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
                ),
                const SizedBox(width: 8),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 180),
                  child: Text('${card.topicLabel} · ${card.created}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                ),
              ]),
            ),
          ),
      ],
    );
  }

  // ── 左：目录树（type → topic 两级分组 + 卡片列表）──

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
    // 卡片在 type 内的下标（详情取卡用同一个线性下标）——先建索引，避免 topic 分组后找不到
    final indexOf = <LearnCardDto, int>{};
    for (var i = 0; i < cards.length; i++) {
      indexOf[cards[i]] = i;
    }
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
        for (final (topic, topicCards) in groupCardsByTopic(cards)) ...[
          _topicHeader(topic, topicCards.length),
          for (final card in topicCards)
            _cardItem(label, card, indexOf[card] ?? 0, group),
        ],
      const SizedBox(height: 6),
    ];
  }

  /// 主题小标题（两级分组的第二级；空主题显示「未归类」）。
  Widget _topicHeader(String topic, int count) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 6, 16, 2),
      child: Text('$topic（$count）',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
    );
  }

  Widget _cardItem(String label, LearnCardDto card, int index, String group) {
    final isSelected = _selectedGroup == label && _selectedIndex == index;
    return GestureDetector(
      key: ValueKey('learn-card-$group-$index'),
      onTap: () => _select(label, index),
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
            // 只读卡（Mac 上整理的原始卡）：列表里先给个记号，点开还有一行说明
            if (card.readOnly) ...[
              const Icon(Icons.lock_outline, size: 11, color: AppColors.darkGrey5),
              const SizedBox(width: 4),
            ],
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

  Widget _buildDetail() {
    final card = _currentCard();
    if (card == null) return const SizedBox.shrink();
    final content = _content;
    // md 原文里产品没建模的段（关键内容详解/金句/与主题概念的关系…）——读全文的关键。
    // 已用结构化渲染的段（且 DTO 里确实有内容）才跳过，否则照 md 原文读出来（宁可重复不可漏读）。
    final modeledWithData = <String>{
      if (card.coreView.isNotEmpty) '核心观点',
      if (card.keyPoints.isNotEmpty) '关键要点',
      if (card.questions.isNotEmpty) '我的疑问',
      if (card.retell.isNotEmpty) '复述',
      if (card.tradeRelated) '交易相关',
    };
    final extraSections = content == null
        ? const <LearnMdSection>[]
        : parseLearnMarkdown(content.content)
            .where((s) => !(s.isModeled && modeledWithData.contains(s.normalizedTitle)))
            .toList();
    return SingleChildScrollView(
      key: ValueKey('learn-detail-${card.type}-${card.title}'),
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
            ..._actionButtons(card),
          ]),
        ]),
        const SizedBox(height: 8),
        Row(children: [
          _typeTag(card.type),
          const SizedBox(width: 6),
          _topicTag(card.topicLabel),
        ]),
        const SizedBox(height: 6),
        _sourceLine(card),
        if (card.readOnly) ...[
          const SizedBox(height: 12),
          _readOnlyNote(),
        ],
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
        // 原文里产品没建模的段：照原文读出来（否则 Mac 侧整理的卡在这里只剩标题）
        for (final section in extraSections) ...[
          _sectionTitle(section.normalizedTitle.isEmpty ? section.title : section.normalizedTitle),
          _markdownBody(section.body),
          const SizedBox(height: 16),
        ],
        if (_contentLoading) ...[
          const Text('正在读这篇的原文…',
              style: TextStyle(fontSize: 11.5, color: AppColors.darkGrey5)),
          const SizedBox(height: 12),
        ],
        if (_contentError != null) ...[
          Row(children: [
            const Icon(Icons.error_outline, size: 13, color: AppColors.darkOrange),
            const SizedBox(width: 6),
            Expanded(
              child: Text(_contentError!,
                  style: const TextStyle(fontSize: 11.5, color: AppColors.darkGrey5, height: 1.5)),
            ),
            TextButton(
              onPressed: () => _loadContent(card),
              child: const Text('重试', style: TextStyle(fontSize: 12)),
            ),
          ]),
          const SizedBox(height: 12),
        ],
        _sectionTitle('复述'),
        if (card.retell.isNotEmpty)
          _bodyText(card.retell)
        else if (card.readOnly)
          const Text('这张卡我没打算在这里写复述——它本来就是别处整理好的资料。',
              style: TextStyle(fontSize: 12.5, color: AppColors.darkGrey5, height: 1.6))
        else
          const Text('还没写复述。自己写 100-200 字才是真消化——点击右上「写复述」或让阿呆帮你改。',
              style: TextStyle(fontSize: 12.5, color: AppColors.darkGrey5, height: 1.6)),
      ]),
    );
  }

  /// 只读卡说明（Mac 上整理的原始卡）：为什么这里什么都不能改，说清楚。
  Widget _readOnlyNote() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Row(children: [
        const Icon(Icons.lock_outline, size: 13, color: AppColors.darkGrey4),
        const SizedBox(width: 8),
        Expanded(
          child: Text('这张是在 Mac 上整理的原始卡，我在这里只当资料看（改不了、也不进复习流转）。',
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4, height: 1.6)),
        ),
      ]),
    );
  }

  /// 详情操作（状态推进 / 写复述 / 反哺候选）——按卡片状态与类型呈现。
  /// 只读卡（writable=false）一律不给写入口：后端也会拒绝，不如这里就说清楚。
  List<Widget> _actionButtons(LearnCardDto card) {
    if (card.readOnly) return const [];
    final buttons = <Widget>[];
    if (card.status == 'new') {
      buttons.add(_actionChip('去复习', Icons.auto_stories, () => _changeStatus(card, 'review')));
    } else if (card.status == 'review') {
      buttons.add(_actionChip('标记完成', Icons.check_circle_outline, () => _changeStatus(card, 'done')));
    }
    buttons.add(_actionChip('写复述', Icons.edit_outlined, () => _openRetellDialog(card)));
    // 卡片管理（2026-09-13）：换主题归档 / 删卡（软删除进回收站）。
    // 只读卡在上面 readOnly 分支已整体挡住，这里不再出现（后端也会 400 拒绝）。
    buttons.add(_actionChip('移动到主题', Icons.drive_file_move_outlined,
        () => _openMoveTopicDialog(card), enabled: !_busy));
    buttons.add(_actionChip('删除', Icons.delete_outline, () => _confirmDeleteCard(card),
        enabled: !_busy, danger: true));
    if (card.type == 'trading' && widget.tradingEnabled) {
      if (card.tradeRelated && card.status != 'done') {
        buttons.add(_actionChip('反哺候选', Icons.rocket_launch_outlined, () => _createCandidate(card)));
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

  /// [enabled] = false 时按钮变灰且不可点（动作在途时挡住连点；守卫同时也在处理函数里兜一层）。
  /// [danger] = true 用红色（删除这类不可逆动作）。
  Widget _actionChip(String label, IconData icon, VoidCallback onTap,
      {bool enabled = true, bool danger = false}) {
    final textColor = danger ? AppColors.darkRed : AppColors.darkGrey3;
    final iconColor = danger ? AppColors.darkRed : AppColors.darkGrey4;
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Opacity(
        opacity: enabled ? 1 : 0.5,
        child: InkWell(
          onTap: enabled ? onTap : null,
          borderRadius: BorderRadius.circular(6),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
            decoration: BoxDecoration(
              border: Border.all(color: AppColors.darkBorder),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              Icon(icon, size: 12, color: iconColor),
              const SizedBox(width: 5),
              Text(label, style: TextStyle(fontSize: 11.5, color: textColor)),
            ]),
          ),
        ),
      ),
    );
  }

  // ── V2 操作 ──

  Future<void> _changeStatus(LearnCardDto card, String target) async {
    if (_busy) return; // P2-learn10 双击守卫
    setState(() => _busy = true);
    try {
      final updated = await widget.api.updateLearnStatus(
          type: card.type, title: card.title, status: target);
      if (!mounted) return;
      _replaceCard(updated);
      _showSnack('已标记「${_statusLabel(target)}」');
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e)); // P1-learn3：透出后端人话
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _createCandidate(LearnCardDto card) async {
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

  Future<void> _openRetellDialog(LearnCardDto card) async {
    if (card.readOnly) return; // 只读卡：入口本身不出现，这里再兜一层
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
      _replaceCard(updated);
      _showSnack(retell.isEmpty ? '复述已清空' : '复述已保存');
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e)); // P1-learn3
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 换主题归档（2026-09-13 卡片管理批）：预填当前主题 → PATCH /learn/cards/topic →
  /// **整树刷新**（卡片换了主题组，目录分组跟着变）并**重新定位回这张卡**（别丢选中）；
  /// 失败透出后端人话（400：卡片不存在 / 别处整理的只读卡 / 主题为空）。
  Future<void> _openMoveTopicDialog(LearnCardDto card) async {
    if (card.readOnly) return; // 兜底：只读卡入口本身不出现
    if (_busy) return;         // 连点守卫：一次只发一份
    final controller = TextEditingController(text: card.topic.trim());
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: Text('移动到主题 · ${card.title}',
            style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 420,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('换个主题，卡片就挪到那个主题目录里（左边目录的分组跟着变）。',
                style: TextStyle(fontSize: 12.5, height: 1.7, color: AppColors.darkGrey5)),
            const SizedBox(height: 10),
            TextField(
              key: const ValueKey('learn-topic-input'),
              controller: controller,
              autofocus: true,
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              onSubmitted: (_) => Navigator.pop(ctx, true),
              decoration: const InputDecoration(
                hintText: '比如 量价关系',
                hintStyle: TextStyle(color: AppColors.darkGrey6),
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
            child: const Text('确定'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final topic = controller.text.trim();
    if (topic.isEmpty) {
      // 后端 400 也会说「topic 为空」，但没必要多跑一趟：这里就说清楚
      _showSnack('主题名不能空着——给它起个名字（比如「量价关系」）');
      return;
    }
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final updated = await widget.api.moveLearnCardTopic(
          type: card.type, title: card.title, topic: topic);
      if (!mounted) return;
      // 主题变了 → 目录分组会换：整树刷新后按 type+title 重新定位（_refreshAndOpen = _load + 定位）
      await _refreshAndOpen(
        updated.type.isEmpty ? card.type : updated.type,
        updated.title.isEmpty ? card.title : updated.title,
      );
      if (!mounted) return;
      _showSnack('已挪到「${updated.topic.trim().isEmpty ? topic : updated.topicLabel}」');
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e)); // P1-learn3：透出后端人话
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 删卡（软删除，2026-09-13 卡片管理批）：二次确认说清「进回收站、不是消失」+「候选会级联清掉」
  /// → DELETE → 整树刷新 + 选中回落第一张（树空了就空态）；级联清掉的候选条数如实报出来。
  Future<void> _confirmDeleteCard(LearnCardDto card) async {
    if (card.readOnly) return; // 兜底：只读卡入口本身不出现
    if (_busy) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('删除这张卡？', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 440,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('《${card.title}》', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
            const SizedBox(height: 10),
            const Text('① 它不是彻底没了：会移进回收站（learn/_trash），哪天真想找回来跟我说一声。',
                style: TextStyle(fontSize: 12.5, height: 1.7, color: AppColors.darkGrey5)),
            const SizedBox(height: 6),
            const Text('② 如果它之前反哺过交易候选，那些候选会跟着一起清掉（源卡都不在了，建议也留不住）。',
                style: TextStyle(fontSize: 12.5, height: 1.7, color: AppColors.darkGrey5)),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkRed),
            child: const Text('确认删除'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await widget.api.deleteLearnCard(type: card.type, title: card.title);
      if (!mounted) return;
      // 选中回落第一张卡：先清掉选择，_load() 里的 _resolveCurrentCard 就会落到第一个非空组第一张。
      // （不能沿用 _selectedIndex：被删卡后面的卡片位移上来，同一个下标会指向另一张卡）
      setState(() {
        _selectedGroup = null;
        _selectedIndex = -1;
      });
      // 整树刷新（就地刷新：_load 不置 _loading，不整页闪）；树空了 → 走空态
      await _load();
      if (!mounted) return;
      final cascade = res.cascadedCandidates.isEmpty
          ? ''
          : '；同时清掉了 ${res.cascadedCandidates.length} 条交易候选';
      _showSnack('已删除《${res.title.isEmpty ? card.title : res.title}》'
          '，卡片进了回收站（learn/_trash），想找回跟我说一声$cascade');
    } catch (e) {
      if (!mounted) return;
      _showSnack(extractApiErrorMessage(e)); // P1-learn3：400（卡片不存在/只读卡/type 非法）人话透出
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 用后端返回的更新后卡片替换树中同 type+title 的卡片（就地刷新，不整树重拉）。
  /// P2-learn9（2026-09-07）：更新请求在途时用户改点了同组另一张卡 → 响应到达不得把
  /// 选中强制拉回刚更新的卡（浏览被打断）——仅当当前选中仍是「这张被更新的卡」才回写 index。
  void _replaceCard(LearnCardDto updated) {
    final tree = _tree;
    if (tree == null) return;
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

  /// 主题小标签（两级归档的第二级，详情里也标一下）。
  Widget _topicTag(String topic) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.darkBorder),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(topic, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
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

  /// 原文段落渲染（md 轻量渲染：列表/加粗/行内代码都认，不用把 md 标记当正文念）。
  Widget _markdownBody(String md) => MarkdownBody(
        data: md,
        selectable: true,
        styleSheet: MarkdownStyleSheet.fromTheme(ThemeData(
          textTheme: const TextTheme(
              bodyMedium: TextStyle(fontSize: 13.5, height: 1.7, color: AppColors.darkGrey2)),
        )).copyWith(
          p: const TextStyle(fontSize: 13.5, height: 1.7, color: AppColors.darkGrey2),
          strong: const TextStyle(
              fontSize: 13.5, height: 1.7, color: AppColors.darkGrey2, fontWeight: FontWeight.w700),
          listBullet: const TextStyle(fontSize: 13.5, height: 1.7, color: AppColors.darkGrey2),
          code: const TextStyle(fontSize: 12.5, color: AppColors.darkGreen),
          a: const TextStyle(fontSize: 13.5, color: AppColors.darkBlue),
        ),
      );

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

/// 「整理新内容」喂入弹窗（2026-09-10 喂入入口批，桌面端；2026-09-12 D 形态抓取批加链接入口与费用确认）。
/// 首选丢一个链接（B站/文章），服务端自己去抓元数据/字幕/正文；抓不到时用户把正文粘进来兜底；
/// 2026-09-12 完整升级批再加「选图整理」：书页/PPT/讲义拍照 1~3 张即可（原图落 _raw/）。
/// 三条路径**互斥**：选了图就锁住链接与素材框，不会被误当成两份素材提交。
/// 提交式：POST /learn/digest（或 /learn/digest/image）立即返回 → 轮询 GET /learn/digest/status
/// （每 2s，上限 150s）→ done 以 (type,title) pop 给 LearnPage 打开新卡；
/// needs_confirmation（视频没字幕、转写要花钱）→ **停下轮询**，把「多长、多少钱」摆出来，
///   点「继续转写」才恢复轮询，点「先不转写」就此结束（没花钱）；
/// failed 弹窗内人话 + 可重试；超时/用户中途关闭 → 整理仍在后台继续，稍后刷新列表可见。
class _DigestDialog extends StatefulWidget {
  final ApiService api;

  /// 进来就已经在转写了（页头提示条点了「继续转写」）→ 直接进轮询态，不摆空表单。
  final bool autoPoll;

  const _DigestDialog({required this.api, this.autoPoll = false});

  @override
  State<_DigestDialog> createState() => _DigestDialogState();
}

class _DigestDialogState extends State<_DigestDialog> {
  final _urlCtl = TextEditingController();     // 链接：首选入口
  final _contentCtl = TextEditingController(); // 素材：抓不到时用户粘正文兜底
  final _platformCtl = TextEditingController();
  final _authorCtl = TextEditingController();
  final List<LearnImageInput> _images = [];    // 选图整理（1~3 张，与链接/素材互斥）
  String? _type; // null = 让阿呆自动判定
  bool _submitting = false;
  bool _polling = false;
  bool _awaitingConfirm = false; // 转写要花钱，等你点头
  bool _answering = false;       // 确认按钮 in-flight 守卫（连点只发一次）
  String? _error;
  String _progress = '';
  String _sourceLine = '';  // 抓到的来源回显
  String _confirmMsg = '';  // 后端给的报价人话
  String? _outcome;         // 结束语（说了先不转写）
  LearnCostDto? _cost;
  LearnQuotaDto? _quota;
  bool _quotaFailed = false;
  int _gen = 0; // 代际令牌：旧轮询的迟到响应不得把弹窗拉回「消化中」

  static const _pollInterval = Duration(seconds: 2);
  static const _pollDeadline = Duration(seconds: 150);
  static const _maxImages = 3;

  @override
  void initState() {
    super.initState();
    _loadQuota();
    if (widget.autoPoll) {
      // 已经点头转写了：进度接着看（轮询等 2s 才发第一次，先把话说在前面）
      _polling = true;
      _progress = '正在接着转写，可能要几分钟';
      _poll();
    }
  }

  @override
  void dispose() {
    _gen++; // 关弹窗后旧轮询的迟到响应一律作废
    _urlCtl.dispose();
    _contentCtl.dispose();
    _platformCtl.dispose();
    _authorCtl.dispose();
    super.dispose();
  }

  /// 本月转写额度：先让你知道还剩多少。查不到也不拦着整理，只是把这句告诉你。
  Future<void> _loadQuota() async {
    final gen = _gen;
    try {
      final q = await widget.api.getLearnQuota();
      if (!mounted || gen != _gen) return;
      setState(() {
        _quota = q;
        _quotaFailed = false;
      });
    } catch (_) {
      if (!mounted || gen != _gen) return;
      setState(() => _quotaFailed = true);
    }
  }

  /// 链接与素材至少填一个（或选好图），「开始整理」才可点。
  bool get _canSubmit =>
      _images.isNotEmpty ||
      _urlCtl.text.trim().isNotEmpty ||
      _contentCtl.text.trim().isNotEmpty;

  bool get _imagesPicked => _images.isNotEmpty;

  /// 选图整理（1~3 张）：书页/PPT/讲义/截图，阿呆读图后照同一条流水线消化成卡。
  Future<void> _pickImages() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.image,
        withData: true,
        allowMultiple: true,
      );
      if (result == null || result.files.isEmpty) return;
      final picked = result.files
          .where((f) => f.bytes != null)
          .map((f) => LearnImageInput(
                bytes: f.bytes!,
                filename: f.name,
                mimeType: _imageMimeType(f.extension),
              ))
          .toList();
      if (picked.isEmpty) return;
      if (!mounted) return;
      final remaining = _maxImages - _images.length;
      if (remaining <= 0) {
        setState(() => _error = '最多 3 张图，先移除一张再选');
        return;
      }
      setState(() {
        _images.addAll(picked.take(remaining));
        _error = picked.length > remaining ? '一次最多 3 张图，我先收了前 $remaining 张' : null;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _error = '图片没选上，再来一次？');
    }
  }

  String _imageMimeType(String? ext) {
    switch (ext?.toLowerCase()) {
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'webp':
        return 'image/webp';
      case 'gif':
        return 'image/gif';
      default:
        return 'image/png';
    }
  }

  Future<void> _submit() async {
    final url = _urlCtl.text.trim();
    final content = _contentCtl.text.trim();
    if (_images.isEmpty && url.isEmpty && content.isEmpty) {
      setState(() => _error = '丢个链接给我，或者把字幕/原文粘进来，也可以直接选几张图，我才能开始');
      return;
    }
    if (_submitting) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      // 三条路径互斥：选了图就走图片入口，链接/素材这次不提交（也不静默丢弃——界面上已锁住）
      final String status;
      if (_images.isNotEmpty) {
        status = await widget.api.submitLearnImages(List.of(_images), type: _type);
      } else {
        status = await widget.api.submitLearnDigest(
          url: url.isEmpty ? null : url,
          content: content.isEmpty ? null : content,
          type: _type,
          platform: _trimOrNull(_platformCtl),
          author: _trimOrNull(_authorCtl),
        );
      }
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _polling = true;
        // 之前就有任务在等你点头 → 直接摆出来，不装作重新开始
        _progress = status == 'needs_confirmation'
            ? '有一件事还等你拍板，我看看…'
            : (_images.isNotEmpty ? '收到，我先看看这几张图…' : '收到，我去看看这个链接…');
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
    final gen = ++_gen;
    final deadline = DateTime.now().add(_pollDeadline);
    while (mounted && gen == _gen && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(_pollInterval);
      if (!mounted || gen != _gen) return;
      final LearnDigestJob job;
      try {
        job = await widget.api.getLearnDigestStatus();
      } catch (e) {
        if (!mounted || gen != _gen) return;
        setState(() => _progress = '进度我暂时看不到（${extractApiErrorMessage(e)}），接着等…');
        continue;
      }
      if (!mounted || gen != _gen) return;
      if (job.isDone) {
        Navigator.pop(context, (type: job.type, title: job.title));
        return;
      }
      if (job.isFailed) {
        setState(() {
          _polling = false;
          _error = job.message.isEmpty ? '整理失败，素材我已留存，稍后可以再试一次' : job.message;
        });
        return;
      }
      if (job.isCancelled) {
        // 任务已取消（比如另一端选了先不转写）→ 就此打住，不装作还在消化
        setState(() {
          _polling = false;
          _outcome = job.message.isEmpty ? '这次先不整理了，没花钱' : job.message;
        });
        return;
      }
      if (job.isAwaitingConfirm) {
        // 视频没字幕、转写要花钱 → 停下轮询，等你点头（不偷偷花钱）
        setState(() {
          _polling = false;
          _awaitingConfirm = true;
          _confirmMsg = job.message;
          _cost = job.cost;
          final src = job.source?.summaryLine ?? '';
          if (src.isNotEmpty) _sourceLine = src;
        });
        return;
      }
      setState(() {
        final stage = job.stageLabel;
        _progress = stage.isNotEmpty ? stage : '正在整理中，通常 1-3 分钟';
        final src = job.source?.summaryLine ?? '';
        if (src.isNotEmpty) _sourceLine = src;
      });
    }
    if (!mounted || gen != _gen) return;
    setState(() {
      _polling = false;
      _error = '整理还在后台继续（抓取和生成都慢）。素材我已留存，稍后刷新列表就能看到新卡片；也可以再试一次。';
    });
  }

  /// 转写费用确认（费用可控条 5：先报价，你点头我才花钱）。
  Future<void> _answerConfirm(bool confirm) async {
    if (_answering) return; // 连点守卫：只发一次
    setState(() {
      _answering = true;
      _error = null;
    });
    try {
      final job = await widget.api.confirmLearnTranscription(confirm);
      if (!mounted) return;
      if (!confirm) {
        _gen++; // 收尾：旧轮询的迟到响应一律作废
        setState(() {
          _answering = false;
          _awaitingConfirm = false;
          _outcome = job.message.isEmpty
              ? '好，这个先不转写（没花钱）。链接我留着，想整理再说一声'
              : job.message;
        });
        return;
      }
      setState(() {
        _answering = false;
        _awaitingConfirm = false;
        _polling = true;
        final stage = job.stageLabel;
        _progress = stage.isNotEmpty ? stage : '正在转写，可能要几分钟';
      });
      await _poll(); // 转写 + 结构化要几分钟，接着轮询
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _answering = false;
        _error = extractApiErrorMessage(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final String title;
    final Widget body;
    final List<Widget> actions;
    if (_polling) {
      title = '整理新内容 · 消化中';
      body = _buildPolling();
      actions = [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('后台继续，稍后刷新查看',
              style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
        ),
      ];
    } else if (_awaitingConfirm) {
      title = '整理新内容 · 转写要花钱，你说了算';
      body = _buildConfirm();
      actions = [
        TextButton(
          onPressed: _answering ? null : () => _answerConfirm(false),
          child: const Text('先不转写', style: TextStyle(fontSize: 13)),
        ),
        FilledButton(
          onPressed: _answering ? null : () => _answerConfirm(true),
          style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
          child: _answering
              ? const SizedBox(
                  width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('继续转写'),
        ),
      ];
    } else if (_outcome != null) {
      title = '整理新内容';
      body = _buildOutcome();
      actions = [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('关闭', style: TextStyle(fontSize: 13))),
      ];
    } else {
      title = '整理新内容';
      body = _buildForm();
      actions = [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消', style: TextStyle(fontSize: 13)),
        ),
        FilledButton(
          onPressed: _submitting || !_canSubmit ? null : _submit,
          style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
          child: _submitting
              ? const SizedBox(
                  width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : Text(_imagesPicked ? '开始读图' : '开始整理'),
        ),
      ];
    }
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: Text(title, style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
      content: SizedBox(width: 600, child: body),
      actions: actions,
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
        if (_sourceLine.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(_sourceLine,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 11.5, color: AppColors.darkGrey5, height: 1.6)),
        ],
      ]),
    );
  }

  /// 费用确认：把「多长、多少钱、还剩多少」摆出来，你点头我才花钱转写。
  Widget _buildConfirm() {
    final cost = _cost;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(_confirmLead(),
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, height: 1.7)),
        if (cost != null && _costLine(cost).isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(_costLine(cost),
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5, height: 1.6)),
        ],
        if (_sourceLine.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text('来源：$_sourceLine',
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5, height: 1.6)),
        ],
        if (_error != null) ...[
          const SizedBox(height: 8),
          Text(_error!, style: const TextStyle(fontSize: 12.5, color: AppColors.darkRed, height: 1.6)),
        ],
        const SizedBox(height: 12),
        const Text('转写是按音频时长花钱的（没字幕的视频才用得上）。不想花这次就先不转，链接我留着。',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey5, height: 1.6)),
      ]),
    );
  }

  /// 报价人话：后端给了就用后端的，没给就按已知的费用预估拼一句。
  String _confirmLead() {
    if (_confirmMsg.isNotEmpty) return _confirmMsg;
    final cost = _cost;
    if (cost == null) return '这个视频没有字幕，要转写才能整理成卡片。';
    final bits = [
      if (cost.durationText.isNotEmpty) cost.durationText,
      if (cost.estimateText.isNotEmpty) cost.estimateText,
      if (cost.remainText.isNotEmpty) cost.remainText,
    ];
    if (bits.isEmpty) return '这个视频没有字幕，要转写才能整理成卡片。';
    return '这个视频没有字幕，要转写才能整理成卡片：${bits.join(' · ')}。';
  }

  /// 费用明细（缺哪项省哪项，不编数字）。
  String _costLine(LearnCostDto cost) {
    final parts = <String>[];
    final d = cost.durationText;
    if (d.isNotEmpty) parts.add('时长 $d');
    if (cost.estimateText.isNotEmpty) parts.add('预计花 ${cost.estimateText}');
    if (cost.remainText.isNotEmpty) parts.add(cost.remainText);
    final q = _quota;
    if (q != null && q.yuanPerHour > 0) parts.add('单价 ${q.yuanPerHour.toStringAsFixed(2)} 元/小时');
    return parts.join(' · ');
  }

  Widget _buildOutcome() {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(_outcome!,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, height: 1.7)),
        const SizedBox(height: 10),
        const Text('想整理的话，把字幕或原文粘进素材框再来一次，这样就不用转写了。',
            style: TextStyle(fontSize: 11.5, color: AppColors.darkGrey5, height: 1.6)),
      ]),
    );
  }

  Widget _buildForm() {
    return SingleChildScrollView(
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        // 选图整理（1~3 张）：书页/PPT/讲义/截图；与下面链接/素材互斥
        OutlinedButton.icon(
          key: const ValueKey('learn-digest-pick-images'),
          onPressed: _submitting || _images.length >= _maxImages ? null : _pickImages,
          icon: const Icon(Icons.image_outlined, size: 15),
          label: const Text('选图整理（1~3 张）', style: TextStyle(fontSize: 12.5)),
          style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.darkGreen,
            side: const BorderSide(color: AppColors.darkBorder),
          ),
        ),
        if (_images.isNotEmpty) ...[
          const SizedBox(height: 8),
          for (var i = 0; i < _images.length; i++)
            Row(children: [
              const Icon(Icons.image_outlined, size: 13, color: AppColors.darkGrey5),
              const SizedBox(width: 6),
              Expanded(
                child: Text(_images[i].filename,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
              ),
              TextButton(
                onPressed: _submitting ? null : () => setState(() => _images.removeAt(i)),
                child: const Text('移除', style: TextStyle(fontSize: 12)),
              ),
            ]),
          const Text('这次用图整理：链接和素材框先锁住（把图都移除就解锁）。原图我会留着，读图后照常归到主题目录。',
              style: TextStyle(fontSize: 11, color: AppColors.darkGrey5, height: 1.6)),
        ],
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('learn-digest-url'),
          controller: _urlCtl,
          enabled: !_imagesPicked,
          onChanged: (_) => setState(() {}), // 两框至少一个 → 按钮可用态随输入变
          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
          decoration: const InputDecoration(
            hintText: '粘贴 B站 / 文章链接，我来整理',
            hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 12.5),
            border: OutlineInputBorder(),
            prefixIcon: Icon(Icons.link, size: 16, color: AppColors.darkGrey5),
            contentPadding: EdgeInsets.symmetric(horizontal: 10, vertical: 12),
          ),
        ),
        const SizedBox(height: 6),
        const Text('丢个链接就行：元数据、字幕、正文我自己去抓；没有字幕的视频，我会先把多长、要花多少钱说清楚，你点头我才转写。',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey5, height: 1.6)),
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('learn-digest-content'),
          controller: _contentCtl,
          enabled: !_imagesPicked,
          onChanged: (_) => setState(() {}),
          maxLines: 8,
          maxLength: 50000,
          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, height: 1.6),
          decoration: const InputDecoration(
            hintText: '我抓不到的话，把视频字幕 / 文章原文粘在这里（两个框至少填一个）',
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
              enabled: !_imagesPicked,
              style: const TextStyle(fontSize: 12.5, color: AppColors.darkGrey1),
              decoration: const InputDecoration(
                hintText: '作者 / UP 主（可选，我自己也能认出来）',
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
              enabled: !_imagesPicked,
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
        if (_quotaLine() != null) ...[
          const SizedBox(height: 10),
          Text(_quotaLine()!,
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5, height: 1.6)),
        ],
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

  /// 本月转写额度一句话（先把账说清楚；查不到也照实说，不装没事）。
  String? _quotaLine() {
    if (_quotaFailed) return '本月转写额度我没查到，不影响整理——真需要转写时我会先把钱说清楚再问你。';
    final q = _quota;
    if (q == null) return null;
    if (!q.asrAvailable) {
      final reason = q.unavailableReason.isEmpty ? '' : '：${q.unavailableReason}';
      return '这个月转写暂时用不了$reason。没有字幕的视频，可以先把字幕或正文粘进来。';
    }
    return '本月转写还剩 ${q.remainText}（${q.month} 用过 ${q.usedText}）——只有没字幕的视频才用得上，用之前我会先跟你算清楚。';
  }
}
