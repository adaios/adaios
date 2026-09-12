import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:image_picker/image_picker.dart';
import '../services/api_service.dart';
import '../services/models/learn_models.dart';
import '../theme/app_colors.dart';
import '../widgets/input_bar.dart' show PickedImage;

/// 加载/接口错误的人话（B1 无第三视角：不甩异常原文，给人话 + 出路）。
String _errText(dynamic e) {
  final str = e.toString();
  if (str.contains('TimeoutException') || str.contains('timed out')) return '等太久了，检查下网络再试';
  if (str.contains('Connection refused') || str.contains('SocketException')) return '暂时连不上，检查下网络再试';
  if (str.contains('403')) return '学习功能未启用（learn 插件）';
  return '加载失败，请重试';
}

/// 动作类错误的人话（写动作：挪主题 / 删卡 / 喂入确认，沿用本页既有口径）：
/// 优先取后端 error body（400/403 后端给的是人话），取不到就按错误类型给自然口吻兜底
/// （B1：不把「请求失败 / 接口」这类系统原话甩给用户）。
String _apiError(dynamic e) {
  if (e is ApiException && e.body != null) {
    try {
      final json = jsonDecode(e.body!);
      if (json is Map && json['error'] is String) return json['error'] as String;
    } catch (_) {}
  }
  final s = e.toString();
  if (s.contains('TimeoutException') || s.contains('timed out')) return '等太久了，检查下网络再试';
  if (s.contains('SocketException') || s.contains('Connection refused')) return '暂时连不上，检查下网络再试';
  if (s.contains('403')) return '学习功能未启用（learn 插件）';
  return '这次没成功，稍后再试一次';
}

/// 单篇页动作的结果（pop 回列表后由列表页刷新 + 交代人话）。
sealed class _CardActionResult {
  const _CardActionResult();
}

/// 换了主题：回列表刷新后把这张卡重新定位打开（它已经归到新主题分组下）。
class _CardMoved extends _CardActionResult {
  final String topic;
  const _CardMoved(this.topic);
}

/// 进了回收站：[cascaded] 是这次被一起清掉的交易候选标题（非空要如实说）。
class _CardDeleted extends _CardActionResult {
  final String title;
  final List<String> cascaded;
  const _CardDeleted(this.title, this.cascaded);
}

/// LearnPage —「最近学习」入口（RFC 20260829 learn 插件 L2 呈现·移动端）。
/// 移动端只做「最近学习 + 单篇全文」（完整资产浏览引导到 web 桌面端，双端分工见 RFC 3.7）。
/// 数据源：GET /learn/tree → 按 type → topic 二级归置（组内 created 倒序）；
/// 顶部搜索框（GET /learn/find，服务端按相关度排好）；进页顺带查一次任务态，
/// 有「等你拍板的转写」就在顶部亮提示条（P2-learn17 确认恢复入口）。
class LearnPage extends StatefulWidget {
  final ApiService api;

  /// 从对话流「整理好了」跳进来时直接打开的单篇（type + 精确标题）——
  /// 列表里找不到也照样打开（全文走 /learn/content，不依赖列表）。
  final ({String type, String title})? initialCard;

  /// 测试钩子：注入选图结果（等价 trading_page 的 debugPickImages，widget 测试不真调相册）。
  @visibleForTesting
  final Future<List<PickedImage>> Function()? debugPickImages;

  const LearnPage({super.key, required this.api, this.initialCard, this.debugPickImages});

  @override
  State<LearnPage> createState() => _LearnPageState();
}

class _LearnPageState extends State<LearnPage> {
  LearnTreeResponse? _tree;
  bool _loading = true;
  String? _error;

  /// 代际令牌：_load 重入/页面销毁后在途回包作废（沿用既有防护写法）。
  int _gen = 0;

  // 搜索（防抖 300ms）：_query 为空 = 没在搜，展示分组列表。
  final _searchCtl = TextEditingController();
  Timer? _searchDebounce;
  String _query = '';
  List<LearnCardDto>? _searchResults; // null = 没在搜（或搜挂了，走 _searchError）
  bool _searching = false;
  String? _searchError;
  int _searchGen = 0;

  // 确认恢复入口（P2-learn17）：进页查一次状态，needs_confirmation → 顶部提示条等用户拍板。
  LearnDigestJob? _pendingConfirm;
  bool _confirmBusy = false; // 连点守卫：不点头前只允许一次确认送达

  bool _initialOpened = false;

  @override
  void initState() {
    super.initState();
    _load();
    _checkPendingConfirm();
  }

  @override
  void dispose() {
    _gen++;
    _searchGen++;
    _searchDebounce?.cancel();
    _searchCtl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final gen = ++_gen;
    try {
      final tree = await widget.api.getLearnTree();
      if (!mounted || gen != _gen) return;
      setState(() {
        _tree = tree;
        _loading = false;
        _error = null;
      });
      _openInitialCardIfNeeded();
    } catch (e) {
      if (!mounted || gen != _gen) return;
      setState(() {
        _loading = false;
        _error = _errText(e);
      });
    }
  }

  /// 从对话流跳进来的单篇：列表加载后自动打开（只开一次）。
  void _openInitialCardIfNeeded() {
    final target = widget.initialCard;
    if (target == null || _initialOpened || !mounted) return;
    _initialOpened = true;
    final card = _tree?.recentAll
        .where((c) => c.type == target.type && c.title == target.title)
        .firstOrNull;
    _openCard(card ?? LearnCardDto(type: target.type, title: target.title, created: ''));
  }

  /// 确认恢复入口（P2-learn17）：进列表查一次任务态——有待拍板的转写就顶部提示，
  /// 用户不必回想刚才是在哪个入口触发的。查不到静默降级（不打扰人）。
  Future<void> _checkPendingConfirm() async {
    try {
      final job = await widget.api.getLearnDigestStatus();
      if (!mounted) return;
      if (job.isAwaitingConfirm) setState(() => _pendingConfirm = job);
    } catch (_) {
      // 静默：查不到就当作没有待确认的事
    }
  }

  /// 「继续转写 / 先不转写」（带连点守卫：不点头前的重复点击不再送达）。
  Future<void> _resolvePendingConfirm(bool yes) async {
    if (_confirmBusy) return;
    setState(() => _confirmBusy = true);
    final gen = ++_gen;
    try {
      final job = await widget.api.confirmLearnTranscription(yes);
      if (!mounted || gen != _gen) return;
      setState(() {
        _confirmBusy = false;
        _pendingConfirm = null;
      });
      if (!yes) {
        _snack(job.cancelledText);
        return;
      }
      _snack('好，我去转写了，弄好了列表里见（可能要几分钟）');
      await _load();
    } catch (e) {
      if (!mounted || gen != _gen) return;
      setState(() => _confirmBusy = false);
      _snack(_errText(e));
    }
  }

  void _snack(String text) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(text, style: const TextStyle(fontSize: 13)),
      backgroundColor: AppColors.darkSurface2,
    ));
  }

  /// 搜索输入：防抖 300ms（打字中间词不发请求），清空则回到分组列表。
  void _onSearchChanged(String raw) {
    _searchDebounce?.cancel();
    final q = raw.trim();
    if (q.isEmpty) {
      setState(() {
        _query = '';
        _searchResults = null;
        _searchError = null;
        _searching = false;
      });
      return;
    }
    setState(() {
      _query = q;
      _searching = true;
      _searchError = null;
    });
    _searchDebounce = Timer(const Duration(milliseconds: 300), () => _runSearch(q));
  }

  Future<void> _runSearch(String q) async {
    final gen = ++_searchGen;
    try {
      final list = await widget.api.searchLearnCards(q);
      if (!mounted || gen != _searchGen || _query != q) return;
      setState(() {
        _searchResults = list;
        _searching = false;
      });
    } catch (e) {
      if (!mounted || gen != _searchGen) return;
      setState(() {
        _searchResults = null;
        _searching = false;
        _searchError = _errText(e);
      });
    }
  }

  /// 打开单篇全文。单篇页里的卡片管理动作（挪主题 / 删卡）以结果 pop 回来，
  /// 由这里统一收尾：刷新列表 → 交代一句人话 → 挪过主题的卡按新分组重新定位打开。
  Future<void> _openCard(LearnCardDto card) async {
    final result = await Navigator.push<_CardActionResult>(context, MaterialPageRoute(
      builder: (_) => _LearnDetailPage(card: card, api: widget.api),
    ));
    if (result == null || !mounted) return;
    await _load();
    if (!mounted) return;
    switch (result) {
      case _CardMoved(:final topic):
        _snack('已挪到「$topic」');
        final tree = _tree;
        if (tree == null) return;
        final found = tree.recentAll
            .where((c) => c.type == card.type && c.title == card.title)
            .firstOrNull;
        // 换了主题分组 → 从新列表里重新定位这张卡打开
        if (found != null && mounted) _openCard(found);
      case _CardDeleted(:final title, :final cascaded):
        _snack(cascaded.isEmpty
            ? '已把《$title》移进回收站（回头想找还能翻出来）'
            : '已把《$title》移进回收站，同时清掉了 ${cascaded.length} 条交易候选');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(children: [
          _buildHeader(),
          Expanded(child: _buildBody()),
        ]),
      ),
    );
  }

  Widget _buildHeader() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      child: Row(children: [
        GestureDetector(
          onTap: () => Navigator.pop(context),
          child: const Padding(
            padding: EdgeInsets.only(right: 8),
            child: Icon(Icons.arrow_back, size: 20, color: AppColors.darkGrey4),
          ),
        ),
        const Icon(Icons.auto_stories_outlined, size: 20, color: AppColors.darkGrey3),
        const SizedBox(width: 8),
        const Text('最近学习',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const Spacer(),
        GestureDetector(
          onTap: _openDigest,
          child: const Icon(Icons.add_circle_outline, size: 22, color: AppColors.darkGreen),
        ),
        const SizedBox(width: 18),
        GestureDetector(
          onTap: _openReviewSetting,
          child: const Icon(Icons.notifications_outlined, size: 18, color: AppColors.darkGrey4),
        ),
        const SizedBox(width: 14),
        GestureDetector(
          onTap: _load,
          child: const Icon(Icons.refresh, size: 18, color: AppColors.darkGrey4),
        ),
      ]),
    );
  }

  /// 「整理新内容」喂入（2026-09-10 learn 喂入入口批·移动端）：push 喂入页提交消化，
  /// 完成后回来刷新列表并打开新卡详情。
  Future<void> _openDigest() async {
    final result = await Navigator.push<({String type, String title})>(
      context,
      MaterialPageRoute(
        builder: (_) => _DigestInputPage(api: widget.api, debugPickImages: widget.debugPickImages),
      ),
    );
    if (result == null || !mounted) return;
    _snack('已沉淀学习卡片《${result.title}》');
    await _load();
    if (!mounted || _tree == null) return;
    final found = _tree!.recentAll
        .where((c) => c.type == result.type && c.title == result.title)
        .firstOrNull;
    if (found != null && mounted) _openCard(found);
  }

  /// 复习提醒开关（S-learn2 2026-09-07）：纯 learn 用户（无交易页）也能自关。
  Future<void> _openReviewSetting() async {
    final bool enabled;
    try {
      enabled = await widget.api.getLearnReviewEnabled();
    } catch (e) {
      if (!mounted) return;
      _snack(_errText(e));
      return;
    }
    if (!mounted) return;
    var current = enabled;
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.darkSurface2,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setDlg) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('复习提醒',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
            const SizedBox(height: 6),
            Text(current ? '开启：进入复习队列满 7 天还没完成的卡片，每晚 20:00 汇总提醒（同卡 7 天内不重复推）。'
                         : '关闭：不再提醒复习。',
                style: const TextStyle(fontSize: 12.5, height: 1.6, color: AppColors.darkGrey5)),
            const SizedBox(height: 4),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(current ? '开启中' : '已关闭',
                  style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3)),
              value: current,
              onChanged: (v) async {
                try {
                  await widget.api.setLearnReviewEnabled(v);
                  if (!ctx.mounted) return;
                  setDlg(() => current = v);
                } catch (e) {
                  if (!ctx.mounted) return;
                  ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(
                    content: Text(_errText(e), style: const TextStyle(fontSize: 13)),
                    backgroundColor: AppColors.darkSurface2,
                  ));
                }
              },
            ),
          ]),
        ),
      )),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.error_outline, size: 28, color: AppColors.darkOrange),
          const SizedBox(height: 10),
          Text(_error!, style: const TextStyle(fontSize: 15, color: AppColors.darkGrey4)),
          const SizedBox(height: 14),
          GestureDetector(
            onTap: _load,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3)),
              ),
              child: const Text('重试', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
            ),
          ),
        ]),
      );
    }
    final tree = _tree;
    final cards = (tree == null ? <LearnCardDto>[] : tree.recentAll);
    return Column(children: [
      if (_pendingConfirm != null) _buildConfirmBanner(_pendingConfirm!),
      _buildSearchField(),
      Expanded(child: _buildListArea(cards)),
    ]);
  }

  /// 确认恢复入口提示条（P2-learn17）：有一件等你拍板的事（转写要花钱）。
  Widget _buildConfirmBanner(LearnDigestJob job) {
    return Container(
      key: const ValueKey('learn-confirm-banner'),
      margin: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.35)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Icon(Icons.record_voice_over_outlined, size: 17, color: AppColors.darkOrange),
          const SizedBox(width: 7),
          const Text('有一件事等你拍板',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        ]),
        const SizedBox(height: 8),
        Text(job.confirmText,
            style: const TextStyle(fontSize: 13, height: 1.7, color: AppColors.darkGrey2)),
        const SizedBox(height: 12),
        Row(children: [
          FilledButton(
            onPressed: _confirmBusy ? null : () => _resolvePendingConfirm(true),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.darkGreen,
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
            ),
            child: _confirmBusy
                ? const SizedBox(
                    width: 15, height: 15,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : const Text('继续转写', style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600)),
          ),
          const SizedBox(width: 10),
          OutlinedButton(
            onPressed: _confirmBusy ? null : () => _resolvePendingConfirm(false),
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey3,
              side: const BorderSide(color: AppColors.darkGrey6),
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
            ),
            child: const Text('先不转写', style: TextStyle(fontSize: 13.5)),
          ),
        ]),
      ]),
    );
  }

  Widget _buildSearchField() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 2),
      child: TextField(
        key: const ValueKey('learn-search'),
        controller: _searchCtl,
        onChanged: _onSearchChanged,
        style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey1),
        decoration: InputDecoration(
          isDense: true,
          hintText: '找找看：关键词 / 主题',
          hintStyle: const TextStyle(color: AppColors.darkGrey6, fontSize: 13),
          prefixIcon: const Icon(Icons.search, size: 17, color: AppColors.darkGrey5),
          suffixIcon: _query.isEmpty
              ? null
              : GestureDetector(
                  onTap: () {
                    _searchCtl.clear();
                    _onSearchChanged('');
                  },
                  child: const Icon(Icons.close, size: 16, color: AppColors.darkGrey5),
                ),
          border: const OutlineInputBorder(),
        ),
      ),
    );
  }

  Widget _buildListArea(List<LearnCardDto> cards) {
    if (_query.isNotEmpty) {
      if (_searching) return const Center(child: CircularProgressIndicator());
      if (_searchError != null) {
        return Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 40),
            child: Text(_searchError!,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 14, height: 1.8, color: AppColors.darkGrey4)),
          ),
        );
      }
      final hits = _searchResults ?? const <LearnCardDto>[];
      if (hits.isEmpty) {
        return Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 40),
            child: Text('没找到和「$_query」对得上的卡。换个说法试试，或者把链接丢给我整理一篇。',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 14, height: 1.8, color: AppColors.darkGrey4)),
          ),
        );
      }
      return ListView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
        children: hits.map(_buildCard).toList(),
      );
    }
    if (cards.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: 40),
          child: Text('还没有学习卡片\n点右上角「＋」丢个 B站 / 文章链接给我，阿呆抓来消化成卡片（没字幕的视频会先问你要不要转写）',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14, height: 1.8, color: AppColors.darkGrey4)),
        ),
      );
    }
    return _buildGroupedList(cards);
  }

  /// 最近学习按 type（中文名）→ topic 二级归置（组内 created 倒序）。
  Widget _buildGroupedList(List<LearnCardDto> cards) {
    final children = <Widget>[];
    for (final group in groupLearnCards(cards)) {
      children.add(Padding(
        padding: const EdgeInsets.only(top: 14, bottom: 6),
        child: Row(children: [
          Text(group.label,
              key: ValueKey('learn-type-${group.type}'),
              style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: _typeColor(group.type))),
          const SizedBox(width: 6),
          Text('${group.count} 篇', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
        ]),
      ));
      for (final topic in group.topics) {
        children.add(Padding(
          padding: const EdgeInsets.only(bottom: 6),
          child: Row(children: [
            const Icon(Icons.folder_outlined, size: 13, color: AppColors.darkGrey5),
            const SizedBox(width: 5),
            Text(topic.label,
                key: ValueKey('learn-topic-${group.type}-${topic.topic}'),
                style: const TextStyle(fontSize: 12.5, color: AppColors.darkGrey4)),
            const SizedBox(width: 6),
            Text('${topic.cards.length}', style: const TextStyle(fontSize: 10.5, color: AppColors.darkGrey6)),
          ]),
        ));
        children.addAll(topic.cards.map(_buildCard));
      }
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 10),
      children: children,
    );
  }

  Widget _buildCard(LearnCardDto card) {
    return GestureDetector(
      key: ValueKey('learn-${card.id}'),
      onTap: () => _openCard(card),
      child: Container(
        margin: const EdgeInsets.only(bottom: 10),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: _typeColor(card.type).withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(_typeLabel(card.type),
                style: TextStyle(fontSize: 10, color: _typeColor(card.type))),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(card.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1, height: 1.4)),
              if (card.coreView.isNotEmpty) ...[
                const SizedBox(height: 4),
                Text(card.coreView,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12.5, color: AppColors.darkGrey4, height: 1.5)),
              ],
              const SizedBox(height: 6),
              Text(_metaLine(card),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 10.5, color: AppColors.darkGrey5)),
            ]),
          ),
          const SizedBox(width: 4),
          if (!card.writable)
            const Padding(
              padding: EdgeInsets.only(top: 4, right: 4),
              child: Icon(Icons.lock_outline, size: 14, color: AppColors.darkGrey6),
            ),
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Icon(Icons.chevron_right, size: 18, color: AppColors.darkGrey6),
          ),
        ]),
      ),
    );
  }

  String _typeLabel(String type) => switch (type) {
        'ai' => 'AI',
        'trading' => '交易',
        _ => '其他',
      };

  Color _typeColor(String type) => switch (type) {
        'ai' => AppColors.darkGreen,
        'trading' => AppColors.darkRed,
        _ => AppColors.darkGrey4,
      };

  String _metaLine(LearnCardDto c) {
    final parts = <String>[];
    if (c.created.isNotEmpty) parts.add(_fmtDate(c.created));
    if (c.author.isNotEmpty) parts.add(c.author);
    if (c.tags.isNotEmpty) parts.add(c.tags.join(' · '));
    return parts.join('  ');
  }

  String _fmtDate(String date) {
    if (date.length < 10) return date;
    final now = DateTime.now();
    final y = int.tryParse(date.substring(0, 4));
    final mmdd = date.substring(5);
    return (y != null && y == now.year) ? mmdd : date;
  }
}

/// 单篇学习卡片全文页（2026-09-12 完整升级批：走 GET /learn/content 拿 md 原文渲染）。
/// 列表接口只给产品建模的四个段，Mac 侧技能整理的卡还有「关键内容详解 / 金句 / 与主题概念的关系」
/// 等段——**本页渲染 md 全文**，产品没建模的段也看得到；读不到给错误态 + 重试。
/// 只读卡（writable=false，Mac 上整理的原始卡）：编辑 / 复述 / 状态流转 / 反哺入口一律不出现，
/// 只留一行说明——后端也会拒绝写入，不把用户送到墙上撞。
class _LearnDetailPage extends StatefulWidget {
  final LearnCardDto card;
  final ApiService api;
  const _LearnDetailPage({required this.card, required this.api});

  @override
  State<_LearnDetailPage> createState() => _LearnDetailPageState();
}

class _LearnDetailPageState extends State<_LearnDetailPage> {
  bool _loading = true;
  String? _error;
  LearnCardContentDto? _content; // 拿到了就是全文权威（含 topic/writable）

  /// 动作级连点守卫（挪主题 / 删卡）：一次只送一个动作，回包前重复点击不再送出。
  bool _actionBusy = false;

  /// 代际令牌：重试后旧回包作废（沿用既有防护写法）。
  int _gen = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _gen++;
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final gen = ++_gen;
    try {
      final content = await widget.api.getLearnContent(
        type: widget.card.type,
        title: widget.card.title,
      );
      if (!mounted || gen != _gen) return;
      setState(() {
        _content = content;
        _loading = false;
      });
    } catch (e) {
      if (!mounted || gen != _gen) return;
      setState(() {
        _loading = false;
        _error = _errText(e);
      });
    }
  }

  LearnCardDto get _card => widget.card;

  /// 页面内一句人话（动作失败/成功交代用）。
  void _snack(String text) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(text, style: const TextStyle(fontSize: 13)),
      backgroundColor: AppColors.darkSurface2,
    ));
  }

  /// 「移动到主题」（只在能改的卡上出现）：填主题 → PATCH → 回列表刷新 + 重新定位打开。
  /// 只读卡不出现这个入口（后端也会拒写，不把人送到墙上撞）。
  Future<void> _moveTopic() async {
    if (_actionBusy) return;
    final current = (_content?.topic ?? _card.topic).trim();
    setState(() => _actionBusy = true);
    final next = await showDialog<String>(
      context: context,
      builder: (_) => _TopicInputDialog(initial: current),
    );
    if (!mounted) return;
    if (next == null) {
      setState(() => _actionBusy = false);
      return;
    }
    if (next.isEmpty) {
      setState(() => _actionBusy = false);
      _snack('主题名不能空着，比如「量价关系」');
      return;
    }
    final gen = ++_gen;
    try {
      await widget.api.moveLearnCardTopic(
        type: _card.type,
        title: _card.title,
        topic: next,
      );
      if (!mounted || gen != _gen) return;
      Navigator.pop(context, _CardMoved(next));
    } catch (e) {
      if (!mounted || gen != _gen) return;
      setState(() => _actionBusy = false);
      _snack(_apiError(e));
    }
  }

  /// 「删除」（只在能改的卡上出现）：二次确认 → DELETE（软删除进回收站）→ 回列表刷新。
  /// 确认框要说清两件事：① 不是彻底消失（移入回收站可找回）；② 反哺过的交易候选会一起清掉。
  Future<void> _deleteCard() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: const Text('删除这张卡？',
            style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
        content: Text(
            '《${_card.title}》会移进回收站（learn/_trash/），不是彻底没了，回头想找回我还能翻出来。\n\n'
            '要是这张卡之前反哺过交易候选，那些候选会跟着一起清掉——清掉几条我会在这边如实告诉你。',
            style: const TextStyle(fontSize: 13, height: 1.7, color: AppColors.darkGrey3)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消', style: TextStyle(color: AppColors.darkGrey5)),
          ),
          TextButton(
            key: const ValueKey('learn-delete-confirm'),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('删除', style: TextStyle(color: AppColors.darkOrange)),
          ),
        ],
      ),
    );
    if (!mounted) return;
    if (ok != true) {
      setState(() => _actionBusy = false);
      return;
    }
    final gen = ++_gen;
    try {
      final res = await widget.api.deleteLearnCard(type: _card.type, title: _card.title);
      if (!mounted || gen != _gen) return;
      Navigator.pop(
        context,
        _CardDeleted(res.title.isEmpty ? _card.title : res.title, res.cascadedCandidates),
      );
    } catch (e) {
      if (!mounted || gen != _gen) return;
      setState(() => _actionBusy = false);
      _snack(_apiError(e));
    }
  }

  /// 卡片管理动作行（2026-09-13 卡片管理动作批）：只对能改的卡出现。
  Widget _cardActions() {
    final disabled = _actionBusy || _loading;
    return Row(
      key: const ValueKey('learn-card-actions'),
      children: [
        OutlinedButton.icon(
          key: const ValueKey('learn-move-topic'),
          onPressed: disabled ? null : _moveTopic,
          style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.darkGrey3,
            side: const BorderSide(color: AppColors.darkGrey6),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          ),
          icon: const Icon(Icons.drive_file_move_outline, size: 16),
          label: const Text('移动到主题', style: TextStyle(fontSize: 13)),
        ),
        const SizedBox(width: 10),
        OutlinedButton.icon(
          key: const ValueKey('learn-delete-card'),
          onPressed: disabled ? null : _deleteCard,
          style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.darkOrange,
            side: BorderSide(color: AppColors.darkOrange.withValues(alpha: 0.4)),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          ),
          icon: const Icon(Icons.delete_outline, size: 16),
          label: const Text('删除', style: TextStyle(fontSize: 13)),
        ),
      ],
    );
  }

  /// 全文里的 writable 优先（列表可能来自旧缓存），缺省按卡上的值。
  bool get _writable => _content?.writable ?? _card.writable;

  String get _topicLabel =>
      (_content != null && _content!.topic.isNotEmpty) ? _content!.topic : _card.topicLabel;

  /// 展示用正文：优先后端的 body（已剥 frontmatter）；老后端没有该字段时自行剥壳兜底。
  String get _md {
    final body = (_content?.body ?? '').trim();
    if (body.isNotEmpty) return body;
    final raw = (_content?.content ?? '').trim();
    final m = RegExp(r'^---\r?\n.*?\r?\n---\r?\n', dotAll: true).firstMatch(raw);
    return m == null ? raw : raw.substring(m.end).trim();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
            child: Row(children: [
              GestureDetector(
                onTap: () => Navigator.pop(context),
                child: const Padding(
                  padding: EdgeInsets.only(right: 8),
                  child: Icon(Icons.arrow_back, size: 20, color: AppColors.darkGrey4),
                ),
              ),
              Expanded(
                child: Text(_card.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.darkGrey1)),
              ),
            ]),
          ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 14, 20, 40),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                _typeRow(),
                if (!_writable) ...[
                  const SizedBox(height: 14),
                  _readOnlyNote(),
                ] else ...[
                  // 卡片管理动作（挪主题 / 删卡）：只读卡不出现（后端也拒写）
                  const SizedBox(height: 14),
                  _cardActions(),
                ],
                const SizedBox(height: 16),
                ..._buildContent(),
              ]),
            ),
          ),
        ]),
      ),
    );
  }

  /// 正文：优先 md 全文（含产品未建模的段）；md 为空 → 拿列表里的结构化字段兜底。
  List<Widget> _buildContent() {
    if (_loading) {
      return const [
        Padding(
          padding: EdgeInsets.symmetric(vertical: 50),
          child: Center(child: CircularProgressIndicator()),
        ),
      ];
    }
    if (_error != null) {
      return [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 40),
          child: Column(children: [
            Icon(Icons.error_outline, size: 26, color: AppColors.darkOrange),
            const SizedBox(height: 10),
            Text(_error!, style: const TextStyle(fontSize: 14, color: AppColors.darkGrey4)),
            const SizedBox(height: 14),
            GestureDetector(
              key: const ValueKey('learn-content-retry'),
              onTap: _load,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3)),
                ),
                child: const Text('重试', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
              ),
            ),
          ]),
        ),
      ];
    }
    if (_md.isNotEmpty) {
      return [
        MarkdownBody(
          key: const ValueKey('learn-full-content'),
          data: _md,
          selectable: true,
          styleSheet: MarkdownStyleSheet.fromTheme(ThemeData(
            textTheme: const TextTheme(bodyMedium: TextStyle(fontSize: 14, height: 1.7, color: AppColors.darkGrey2)),
          )).copyWith(
            p: const TextStyle(fontSize: 14, height: 1.7, color: AppColors.darkGrey2),
            strong: const TextStyle(fontSize: 14, height: 1.7, color: AppColors.darkGrey1, fontWeight: FontWeight.w700),
            h1: const TextStyle(fontSize: 16, height: 1.5, color: AppColors.darkGreen, fontWeight: FontWeight.w700),
            h2: const TextStyle(fontSize: 15, height: 1.5, color: AppColors.darkGreen, fontWeight: FontWeight.w700),
            h3: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGreen, fontWeight: FontWeight.w600),
            code: const TextStyle(fontSize: 13, color: AppColors.darkGreen, backgroundColor: AppColors.darkBorder),
            listBullet: const TextStyle(fontSize: 14, height: 1.7, color: AppColors.darkGrey2),
            a: const TextStyle(fontSize: 14, color: AppColors.darkBlue),
          ),
        ),
        // 写入口（复述）：只读卡不出现（后端也拒写，不让人撞墙）
        if (_writable) ...[
          const SizedBox(height: 22),
          _retellEntry(),
        ],
      ];
    }
    // md 空（老卡/接口只给了元信息）→ 结构化兜底，别给人白屏
    return [
      if (_card.coreView.isNotEmpty) _section('核心观点', _card.coreView),
      if (_card.keyPoints.isNotEmpty) ...[
        const SizedBox(height: 18),
        _listSection('关键要点', _card.keyPoints),
      ],
      if (_card.questions.isNotEmpty) ...[
        const SizedBox(height: 18),
        _listSection('我的疑问', _card.questions),
      ],
      if (_writable) ...[
        const SizedBox(height: 18),
        _retellEntry(),
      ],
    ];
  }

  /// 只读卡说明（writable=false：Mac 上整理的原始卡，只当资料看）。
  Widget _readOnlyNote() {
    return Container(
      key: const ValueKey('learn-readonly-note'),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkGrey6.withValues(alpha: 0.5)),
      ),
      child: const Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Padding(
          padding: EdgeInsets.only(top: 2, right: 8),
          child: Icon(Icons.lock_outline, size: 15, color: AppColors.darkGrey5),
        ),
        Expanded(
          child: Text(
            '这张是在 Mac 上整理的原始卡，我在这里只当资料看、不改动它；想改的话我可以照它的内容另存一张能编辑的给你。',
            style: TextStyle(fontSize: 12.5, height: 1.7, color: AppColors.darkGrey4),
          ),
        ),
      ]),
    );
  }

  /// 复述写入口（只有能改的卡才出现）。
  Widget _retellEntry() {
    return Column(
      key: const ValueKey('learn-write-actions'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _section('复述',
            _card.retell.isNotEmpty ? _card.retell : '还没写复述。自己写 100-200 字才是真消化（编辑请到桌面端或让阿呆帮你）。'),
        const SizedBox(height: 10),
        const Text('这张卡能改：编辑、复习状态流转、反哺规则候选都在桌面端更顺手（手机端先当资料看）。',
            style: TextStyle(fontSize: 11.5, height: 1.6, color: AppColors.darkGrey5)),
      ],
    );
  }

  Widget _typeRow() {
    final meta = <String>[];
    if (_card.author.isNotEmpty) meta.add('作者：${_card.author}');
    if (_card.platform.isNotEmpty) meta.add(_card.platform);
    if (_card.created.isNotEmpty) meta.add(_card.created);
    return Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
      if (_writable) _statusBadge(_card.status),
      Text(learnTypeLabel(_card.type),
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: _typeColor(_card.type))),
      Row(mainAxisSize: MainAxisSize.min, children: [
        const Icon(Icons.folder_outlined, size: 12, color: AppColors.darkGrey5),
        const SizedBox(width: 4),
        Text(_topicLabel, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      ]),
      for (final m in meta)
        Text(m, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  /// 状态徽标：new=待复习（灰）/ review=复习中（橙）/ done=已完成（绿）。
  Widget _statusBadge(String status) {
    final (text, color) = switch (status) {
      'review' => ('复习中', AppColors.darkOrange),
      'done' => ('已完成', AppColors.darkGreen),
      _ => ('待复习', AppColors.darkGrey5),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(text,
          style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: color)),
    );
  }

  Widget _section(String title, String body) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.darkGreen)),
          const SizedBox(height: 8),
          Text(body, style: const TextStyle(fontSize: 14, color: AppColors.darkGrey2, height: 1.7)),
        ],
      );

  Widget _listSection(String title, List<String> items) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.darkGreen)),
          const SizedBox(height: 6),
          for (final item in items)
            Padding(
              padding: const EdgeInsets.only(bottom: 5),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('•  ', style: TextStyle(fontSize: 14, color: AppColors.darkGrey4)),
                Expanded(
                  child: Text(item,
                      style: const TextStyle(fontSize: 14, color: AppColors.darkGrey2, height: 1.6)),
                ),
              ]),
            ),
        ],
      );

  Color _typeColor(String type) => switch (type) {
        'ai' => AppColors.darkGreen,
        'trading' => AppColors.darkRed,
        _ => AppColors.darkGrey4,
      };
}

/// 「移动到主题」输入框（预填当前主题；自带 controller 生命周期，随弹窗一起销毁）。
class _TopicInputDialog extends StatefulWidget {
  final String initial;
  const _TopicInputDialog({required this.initial});

  @override
  State<_TopicInputDialog> createState() => _TopicInputDialogState();
}

class _TopicInputDialogState extends State<_TopicInputDialog> {
  late final TextEditingController _ctl = TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _ctl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface,
      title: const Text('移动到主题',
          style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        TextField(
          key: const ValueKey('learn-topic-input'),
          controller: _ctl,
          autofocus: true,
          style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
          decoration: const InputDecoration(
            hintText: '比如 量价关系',
            hintStyle: TextStyle(fontSize: 13, color: AppColors.darkGrey6),
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 10),
        const Text('换个主题名，这张卡就搬到那个主题下：内容不动，编号在新主题里接着排。',
            style: TextStyle(fontSize: 12, height: 1.6, color: AppColors.darkGrey5)),
      ]),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消', style: TextStyle(color: AppColors.darkGrey5)),
        ),
        TextButton(
          key: const ValueKey('learn-topic-confirm'),
          onPressed: () => Navigator.pop(context, _ctl.text.trim()),
          child: const Text('挪过去', style: TextStyle(color: AppColors.darkGreen)),
        ),
      ],
    );
  }
}

/// 「整理新内容」喂入页（2026-09-10 喂入入口批·移动端；2026-09-12 抓取批接链接 + 转写费用确认；
/// 2026-09-12 完整升级批接图片喂入 + 本月转写额度）。
/// 提交式：POST /learn/digest（只丢链接也行，抓取交给服务端）→ 轮询 GET /learn/digest/status（每 2s，上限 150s）
/// → done 以 (type,title) pop 回列表页打开新卡；failed 页内人话 + 可重试；
/// needs_confirmation（视频没字幕、转写要花钱）→ 停轮询、亮出费用预估与「继续转写 / 先不转写」，
/// 用户点头（POST /learn/digest/confirm）后才接着花钱；先不转写则到此为止（没花钱）；
/// 图片路径：顶部「选图整理（1~3 张）」→ POST /learn/digest/image（multipart）→ 同一套轮询（stage=reading 正在读图）；
/// 页内顺带显示本月转写额度（GET /learn/digest/quota，P2-learn15），查不到静默降级、不挡喂入；
/// 超时/返回 → 消化仍在后台继续，稍后刷新可见（素材已留存 learn/_raw/ 兜底）。
class _DigestInputPage extends StatefulWidget {
  final ApiService api;

  /// 测试钩子：注入选图结果（等价 trading_page 的 debugPickImages，widget 测试不真调相册）。
  final Future<List<PickedImage>> Function()? debugPickImages;

  const _DigestInputPage({required this.api, this.debugPickImages});

  @override
  State<_DigestInputPage> createState() => _DigestInputPageState();
}

class _DigestInputPageState extends State<_DigestInputPage> {
  final _linkCtl = TextEditingController();
  final _contentCtl = TextEditingController();
  final _platformCtl = TextEditingController();
  final _authorCtl = TextEditingController();
  String? _type; // null = 让阿呆自动判定
  bool _submitting = false;
  bool _polling = false;
  bool _awaiting = false; // 转写要花钱，等用户点头（已停轮询）
  bool _confirming = false; // 「继续 / 先不」正在送达
  String? _error;
  String? _confirmError;
  String? _cancelled; // 「先不转写」的结果人话（非 null = 这次到此为止）
  String _progress = '';
  LearnDigestJob? _job; // 最新一次状态（舞台/来源展示 + 费用提示来源）
  LearnQuotaDto? _quota; // 本月转写额度（P2-learn15；查不到 = null，不挡喂入）

  /// 代际令牌：重试/确认后旧轮询自行失效，防两条轮询并行（沿用既有防护写法）。
  int _gen = 0;

  static const _pollInterval = Duration(seconds: 2);
  static const _pollDeadline = Duration(seconds: 150);

  @override
  void initState() {
    super.initState();
    _loadQuota();
  }

  @override
  void dispose() {
    _gen++; // 页面销毁 → 在途轮询作废
    _linkCtl.dispose();
    _contentCtl.dispose();
    _platformCtl.dispose();
    _authorCtl.dispose();
    super.dispose();
  }

  /// 本月转写额度（P2-learn15）：进页查一次，查不到静默降级——不弹错、不挡喂入。
  Future<void> _loadQuota() async {
    try {
      final quota = await widget.api.getLearnQuota();
      if (!mounted) return;
      setState(() => _quota = quota);
    } catch (_) {
      // 静默：额度查不到不影响喂入
    }
  }

  Future<void> _submit() async {
    final link = _linkCtl.text.trim();
    final content = _contentCtl.text.trim();
    if (link.isEmpty && content.isEmpty) {
      setState(() => _error = '给我一个链接，或者把素材内容粘进来');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
      _cancelled = null;
      _confirmError = null;
    });
    final gen = ++_gen;
    try {
      await widget.api.submitLearnDigest(
        url: link.isEmpty ? null : link,
        content: content.isEmpty ? null : content,
        type: _type,
        platform: _trimOrNull(_platformCtl),
        author: _trimOrNull(_authorCtl),
      );
      if (!mounted || gen != _gen) return;
      setState(() {
        _submitting = false;
        _polling = true;
        _job = null;
        _progress = link.isNotEmpty ? '收到链接，我这就去看看…' : '已受理，阿呆开始消化…';
      });
      await _poll(gen);
    } catch (e) {
      if (!mounted || gen != _gen) return;
      setState(() {
        _submitting = false;
        _error = _apiError(e);
      });
    }
  }

  /// 选图整理（2026-09-12 完整升级批）：相册多选 1~3 张（书页/PPT/讲义/截图）→ 直接提交读图。
  /// 与截图入账同交互：选完就送，不让人再点一次；测试注入 debugPickImages 跳过相册。
  Future<void> _pickImages() async {
    if (_submitting || _polling || _awaiting) return;
    List<PickedImage> picked;
    try {
      if (widget.debugPickImages != null) {
        picked = await widget.debugPickImages!();
      } else {
        final files = await ImagePicker().pickMultiImage(
          maxWidth: 1920, // 限制长边（与 input_bar 同参）
          imageQuality: 85,
          limit: 3,
        );
        picked = [];
        for (final f in files) {
          final bytes = await f.readAsBytes();
          picked.add(PickedImage(
            bytes,
            f.name,
            f.name.contains('.') ? f.name.split('.').last : 'jpg',
          ));
        }
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '图片没选上来，再试一次');
      return;
    }
    if (picked.isEmpty || !mounted) return;
    if (picked.length > 3) picked = picked.take(3).toList();
    await _submitImages(picked);
  }

  /// 提交图片消化（1~3 张）→ 同一套轮询（stage=reading「正在读图」）。
  Future<void> _submitImages(List<PickedImage> images) async {
    setState(() {
      _submitting = true;
      _error = null;
      _cancelled = null;
      _confirmError = null;
    });
    final gen = ++_gen;
    try {
      await widget.api.submitLearnImages(
        bytesList: images.map((i) => i.bytes).toList(),
        filenames: images.map((i) => i.name).toList(),
        mimeTypes: images.map((i) => _mimeOf(i.extension ?? '')).toList(),
        type: _type,
      );
      if (!mounted || gen != _gen) return;
      setState(() {
        _submitting = false;
        _polling = true;
        _job = null;
        _progress = '收到 ${images.length} 张图，我这就看…';
      });
      await _poll(gen);
    } catch (e) {
      if (!mounted || gen != _gen) return;
      setState(() {
        _submitting = false;
        _error = _apiError(e);
      });
    }
  }

  /// 扩展名 → mime（与 main_page/trading_page 同口径）。
  String _mimeOf(String ext) {
    switch (ext.toLowerCase()) {
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'webp':
        return 'image/webp';
      case 'gif':
        return 'image/gif';
      case 'heic':
        return 'image/heic';
      case 'heif':
        return 'image/heif';
      default:
        return 'image/png';
    }
  }

  String? _trimOrNull(TextEditingController c) {
    final v = c.text.trim();
    return v.isEmpty ? null : v;
  }

  /// 「继续转写 / 先不转写」（2026-09-12 抓取批）：把用户的选择告诉阿呆。
  /// 继续 → 恢复轮询；先不 → 展示结果并结束（不产生费用）。
  Future<void> _confirm(bool yes) async {
    if (_confirming) return;
    setState(() {
      _confirming = true;
      _confirmError = null;
    });
    final gen = ++_gen;
    try {
      final job = await widget.api.confirmLearnTranscription(yes);
      if (!mounted || gen != _gen) return;
      if (!yes) {
        setState(() {
          _confirming = false;
          _awaiting = false;
          _job = job;
          _cancelled = job.cancelledText;
        });
        return;
      }
      if (job.isDone) {
        Navigator.pop(context, (type: job.type, title: job.title));
        return;
      }
      setState(() {
        _confirming = false;
        _awaiting = false;
        _job = job;
        _polling = true;
        _progress = job.stageText.isNotEmpty ? job.stageText : '正在转写，可能要几分钟';
      });
      await _poll(gen);
    } catch (e) {
      if (!mounted || gen != _gen) return;
      setState(() {
        _confirming = false;
        _confirmError = _apiError(e);
      });
    }
  }

  Future<void> _poll(int gen) async {
    final deadline = DateTime.now().add(_pollDeadline);
    while (mounted && gen == _gen && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(_pollInterval);
      if (!mounted || gen != _gen) return;
      final LearnDigestJob job;
      try {
        job = await widget.api.getLearnDigestStatus();
      } catch (e) {
        if (!mounted || gen != _gen) return;
        setState(() => _progress = '暂时没拿到进度（${_apiError(e)}），我接着等…');
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
          _job = job;
          _error = job.message.isEmpty ? '这次没整理成，素材我留着了，稍后可以再试一次' : job.message;
        });
        return;
      }
      if (job.isCancelled) {
        setState(() {
          _polling = false;
          _job = job;
          _cancelled = job.cancelledText;
        });
        return;
      }
      if (job.isAwaitingConfirm) {
        // 转写要花钱：停下轮询，等用户点头（不自动继续）。
        setState(() {
          _polling = false;
          _awaiting = true;
          _job = job;
          _progress = '';
        });
        return;
      }
      setState(() {
        _job = job;
        _progress = job.stageText.isNotEmpty ? job.stageText : '正在消化中，通常 1-3 分钟…';
      });
    }
    if (!mounted || gen != _gen) return;
    setState(() {
      _polling = false;
      _error = '消化仍在后台进行（AI 生成较慢）。素材已留存，稍后刷新列表即可看到新卡片；也可以再试一次。';
    });
  }

  String get _headerTitle {
    if (_cancelled != null) return '这次先不转写';
    if (_awaiting) return '转写确认';
    return _polling ? '消化中' : '整理新内容';
  }

  @override
  Widget build(BuildContext context) {
    final showSubmit = !_polling && !_awaiting && _cancelled == null;
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
            child: Row(children: [
              GestureDetector(
                onTap: () => Navigator.pop(context),
                child: const Padding(
                  padding: EdgeInsets.only(right: 8),
                  child: Icon(Icons.arrow_back, size: 20, color: AppColors.darkGrey4),
                ),
              ),
              const Icon(Icons.auto_stories_outlined, size: 20, color: AppColors.darkGrey3),
              const SizedBox(width: 8),
              Text(_headerTitle,
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
            ]),
          ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
              child: _buildScroller(),
            ),
          ),
          if (showSubmit)
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _submitting ? null : _submit,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.darkGreen,
                    padding: const EdgeInsets.symmetric(vertical: 13),
                  ),
                  child: _submitting
                      ? const SizedBox(
                          width: 18, height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Text('让阿呆消化',
                          style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                ),
              ),
            ),
        ]),
      ),
    );
  }

  Widget _buildScroller() {
    if (_cancelled != null) return _buildCancelled();
    if (_awaiting) return _buildConfirm();
    if (_polling) return _buildPolling();
    return _buildForm();
  }

  /// 进行中：舞台人话（抓取 / 转写 / 整理）+ 抓到的来源。
  Widget _buildPolling() {
    final src = _job?.source?.summaryLine ?? '';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 60),
      child: Column(children: [
        const SizedBox(width: 30, height: 30, child: CircularProgressIndicator(strokeWidth: 2.5)),
        const SizedBox(height: 16),
        Text(_progress,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey4, height: 1.6)),
        if (src.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(src,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5, height: 1.6)),
        ],
        const SizedBox(height: 8),
        const Text('可以返回「最近学习」，稍后下拉刷新查看',
            style: TextStyle(fontSize: 12, color: AppColors.darkGrey6)),
      ]),
    );
  }

  /// 转写要花钱：把费用摆出来，等用户点头（不点头不花钱）。
  Widget _buildConfirm() {
    final job = _job;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SizedBox(height: 20),
      Row(children: [
        const Icon(Icons.record_voice_over_outlined, size: 20, color: AppColors.darkOrange),
        const SizedBox(width: 8),
        Expanded(
          child: Text('转写要花钱，先问你一句',
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        ),
      ]),
      const SizedBox(height: 12),
      Text(job?.confirmText ?? '这个视频没有字幕，得先转成文字，会花一点钱。要我继续吗？',
          style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey2, height: 1.7)),
      if ((job?.source?.summaryLine ?? '').isNotEmpty) ...[
        const SizedBox(height: 8),
        Text(job!.source!.summaryLine,
            style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5, height: 1.6)),
      ],
      if (_confirmError != null) ...[
        const SizedBox(height: 10),
        Text(_confirmError!,
            style: const TextStyle(fontSize: 13, color: AppColors.darkRed, height: 1.6)),
      ],
      if (_quota != null) ...[
        const SizedBox(height: 12),
        Text('本月转写额度：${_quota!.monthLabel}',
            style: const TextStyle(fontSize: 11.5, height: 1.6, color: AppColors.darkGrey5)),
      ],
      const SizedBox(height: 18),
      Row(children: [
        Expanded(
          child: FilledButton(
            onPressed: _confirming ? null : () => _confirm(true),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.darkGreen,
              padding: const EdgeInsets.symmetric(vertical: 12),
            ),
            child: _confirming
                ? const SizedBox(
                    width: 16, height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : const Text('继续转写', style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600)),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: OutlinedButton(
            onPressed: _confirming ? null : () => _confirm(false),
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey3,
              side: const BorderSide(color: AppColors.darkGrey6),
              padding: const EdgeInsets.symmetric(vertical: 12),
            ),
            child: const Text('先不转写', style: TextStyle(fontSize: 14.5)),
          ),
        ),
      ]),
      const SizedBox(height: 10),
      const Text('继续 = 花这笔钱把语音转成文字，估算是参考、按实际时长算；先不转写就到此为止，这钱不花，抓到的信息我留着。',
          style: TextStyle(fontSize: 11.5, color: AppColors.darkGrey5, height: 1.6)),
    ]);
  }

  /// 「先不转写」结果：到此为止（没花钱），可以返回列表。
  Widget _buildCancelled() {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 50),
      child: Column(children: [
        const Icon(Icons.check_circle_outline, size: 30, color: AppColors.darkGrey4),
        const SizedBox(height: 14),
        Text(_cancelled!,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey3, height: 1.7)),
        const SizedBox(height: 18),
        OutlinedButton(
          onPressed: () => Navigator.pop(context),
          style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.darkGrey3,
            side: const BorderSide(color: AppColors.darkGrey6),
          ),
          child: const Text('返回最近学习', style: TextStyle(fontSize: 13.5)),
        ),
      ]),
    );
  }

  Widget _buildForm() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      // 选图整理（2026-09-12 完整升级批）：书页 / PPT / 讲义 / 截图 1~3 张 → 阿呆读图后整理成卡
      SizedBox(
        width: double.infinity,
        child: OutlinedButton.icon(
          key: const ValueKey('learn-pick-images'),
          onPressed: _submitting ? null : _pickImages,
          style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.darkGreen,
            side: BorderSide(color: AppColors.darkGreen.withValues(alpha: 0.4)),
            padding: const EdgeInsets.symmetric(vertical: 11),
          ),
          icon: const Icon(Icons.image_outlined, size: 17),
          label: const Text('选图整理（1~3 张）',
              style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600)),
        ),
      ),
      const SizedBox(height: 6),
      const Text('书页 / PPT / 讲义 / 截图都行，我逐张读成文字再整理（原图我留着，源不丢）。',
          style: TextStyle(fontSize: 11.5, color: AppColors.darkGrey5, height: 1.6)),
      const SizedBox(height: 14),
      _buildQuotaLine(),
      TextField(
        key: const ValueKey('learn-digest-link'),
        controller: _linkCtl,
        style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1, height: 1.6),
        decoration: const InputDecoration(
          hintText: '粘贴 B站 / 文章链接，我来整理',
          hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 13, height: 1.6),
          border: OutlineInputBorder(),
        ),
      ),
      const SizedBox(height: 6),
      const Text('链接给我就行，字幕 / 正文我自己去取（没有字幕的视频会先问你要不要花钱转写）。',
          style: TextStyle(fontSize: 11.5, color: AppColors.darkGrey5, height: 1.6)),
      const SizedBox(height: 14),
      TextField(
        key: const ValueKey('learn-digest-content'),
        controller: _contentCtl,
        maxLines: 8,
        maxLength: 50000,
        style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1, height: 1.6),
        decoration: const InputDecoration(
          hintText: '素材正文（可留空）：视频字幕 / 文章原文…\n（链接取不到内容时，把正文粘进来）',
          hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 13, height: 1.6),
          border: OutlineInputBorder(),
        ),
      ),
      const SizedBox(height: 12),
      DropdownButtonFormField<String>(
        initialValue: _type,
        isDense: true,
        decoration: const InputDecoration(
          labelText: '内容类型',
          labelStyle: TextStyle(fontSize: 12.5, color: AppColors.darkGrey5),
          border: OutlineInputBorder(),
          contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        ),
        style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
        items: const [
          DropdownMenuItem<String>(value: null, child: Text('让阿呆自动判定', style: TextStyle(fontSize: 14))),
          DropdownMenuItem<String>(value: 'ai', child: Text('AI / 技术', style: TextStyle(fontSize: 14))),
          DropdownMenuItem<String>(value: 'trading', child: Text('交易', style: TextStyle(fontSize: 14))),
          DropdownMenuItem<String>(value: 'other', child: Text('其他', style: TextStyle(fontSize: 14))),
        ],
        onChanged: _submitting ? null : (v) => setState(() => _type = v),
      ),
      const SizedBox(height: 12),
      Row(children: [
        Expanded(
          child: TextField(
            controller: _authorCtl,
            style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey1),
            decoration: const InputDecoration(
              hintText: '作者 / UP 主（可选）',
              hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 12.5),
              isDense: true,
              border: OutlineInputBorder(),
            ),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: TextField(
            controller: _platformCtl,
            style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey1),
            decoration: const InputDecoration(
              hintText: '来源平台（可选）',
              hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 12.5),
              isDense: true,
              border: OutlineInputBorder(),
            ),
          ),
        ),
      ]),
      if (_error != null) ...[
        const SizedBox(height: 12),
        Text(_error!,
            style: const TextStyle(fontSize: 13, color: AppColors.darkRed, height: 1.6)),
      ],
      const SizedBox(height: 8),
      const Text('消化后卡片按类型归档，可进入复习队列定期回看；交易类会提示是否反哺规则候选。',
          style: TextStyle(fontSize: 11.5, color: AppColors.darkGrey5, height: 1.6)),
    ]);
  }

  /// 本月转写额度人话（P2-learn15）：查到才显示；查不到或不可用给对应人话，绝不挡喂入。
  Widget _buildQuotaLine() {
    final quota = _quota;
    if (quota == null) return const SizedBox.shrink();
    final unavailable = quota.unavailableLabel;
    final text = unavailable.isNotEmpty
        ? unavailable
        : [quota.monthLabel, if (quota.priceLabel.isNotEmpty) quota.priceLabel].join(' · ');
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Container(
        key: const ValueKey('learn-quota'),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(children: [
          const Icon(Icons.hourglass_bottom_outlined, size: 14, color: AppColors.darkGrey5),
          const SizedBox(width: 7),
          Expanded(
            child: Text(text,
                style: const TextStyle(fontSize: 11.5, height: 1.6, color: AppColors.darkGrey4)),
          ),
        ]),
      ),
    );
  }
}
