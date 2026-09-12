import 'dart:convert';
import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../services/models/learn_models.dart';
import '../theme/app_colors.dart';

/// LearnPage —「最近学习」入口（RFC 20260829 learn 插件 L2 呈现·移动端）。
/// 移动端只做「最近学习 + 单篇全文」（完整资产浏览引导到 web 桌面端，双端分工见 RFC 3.7）。
/// 数据源：GET /learn/tree → 合并全部卡片按 created 倒序 → 顶部最新。
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
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = _errText(e);
      });
    }
  }

  String _errText(dynamic e) {
    final str = e.toString();
    if (str.contains('TimeoutException') || str.contains('timed out')) return '等太久了，检查下网络再试';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '暂时连不上，检查下网络再试';
    if (str.contains('403')) return '学习功能未启用（learn 插件）';
    return '加载失败，请重试';
  }

  void _openCard(LearnCardDto card) {
    Navigator.push(context, MaterialPageRoute(
      builder: (_) => _LearnDetailPage(card: card),
    ));
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
      MaterialPageRoute(builder: (_) => _DigestInputPage(api: widget.api)),
    );
    if (result == null || !mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('已沉淀学习卡片《${result.title}》', style: const TextStyle(fontSize: 13)),
      backgroundColor: AppColors.darkSurface2,
    ));
    await _load();
    if (!mounted || _tree == null) return;
    LearnCardDto? found;
    for (final c in _tree!.recentAll) {
      if (c.type == result.type && c.title == result.title) {
        found = c;
        break;
      }
    }
    if (found != null && mounted) _openCard(found);
  }

  /// 复习提醒开关（S-learn2 2026-09-07）：纯 learn 用户（无交易页）也能自关。
  Future<void> _openReviewSetting() async {
    final bool enabled;
    try {
      enabled = await widget.api.getLearnReviewEnabled();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(_errText(e), style: const TextStyle(fontSize: 13)),
        backgroundColor: AppColors.darkSurface2,
      ));
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
    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      itemCount: cards.length,
      itemBuilder: (_, i) => _buildCard(cards[i]),
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

/// 单篇学习卡片全文页（核心观点 / 关键要点 / 我的疑问 / 交易标注）。
class _LearnDetailPage extends StatelessWidget {
  final LearnCardDto card;
  const _LearnDetailPage({required this.card});

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
                child: Text(card.title,
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
                const SizedBox(height: 16),
                if (card.coreView.isNotEmpty) _section('核心观点', card.coreView),
                if (card.keyPoints.isNotEmpty) ...[
                  const SizedBox(height: 18),
                  _listSection('关键要点', card.keyPoints),
                ],
                if (card.questions.isNotEmpty) ...[
                  const SizedBox(height: 18),
                  _listSection('我的疑问', card.questions),
                ],
                if (card.tradeRelated) ...[
                  const SizedBox(height: 18),
                  _section('交易相关',
                      card.tradeNote.isNotEmpty ? '涉及可执行交易规则（备注：${card.tradeNote}），规则变更须你拍板' : '涉及可执行交易规则，规则变更须你拍板'),
                ],
                const SizedBox(height: 18),
                _section('复述',
                    card.retell.isNotEmpty ? card.retell : '还没写复述。自己写 100-200 字才是真消化（编辑请到桌面端或让阿呆帮你）。'),
              ]),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _typeRow() {
    final meta = <String>[];
    if (card.author.isNotEmpty) meta.add('作者：${card.author}');
    if (card.platform.isNotEmpty) meta.add(card.platform);
    if (card.created.isNotEmpty) meta.add(card.created);
    return Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
      _statusBadge(card.status),
      Text(_typeLabel(card.type),
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: _typeColor(card.type))),
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

  String _typeLabel(String type) => switch (type) {
        'ai' => 'AI / 技术',
        'trading' => '交易',
        _ => '其他',
      };

  Color _typeColor(String type) => switch (type) {
        'ai' => AppColors.darkGreen,
        'trading' => AppColors.darkRed,
        _ => AppColors.darkGrey4,
      };
}

/// 「整理新内容」喂入页（2026-09-10 喂入入口批·移动端；2026-09-12 抓取批接链接 + 转写费用确认）。
/// 提交式：POST /learn/digest（只丢链接也行，抓取交给服务端）→ 轮询 GET /learn/digest/status（每 2s，上限 150s）
/// → done 以 (type,title) pop 回列表页打开新卡；failed 页内人话 + 可重试；
/// needs_confirmation（视频没字幕、转写要花钱）→ 停轮询、亮出费用预估与「继续转写 / 先不转写」，
/// 用户点头（POST /learn/digest/confirm）后才接着花钱；先不转写则到此为止（没花钱）；
/// 超时/返回 → 消化仍在后台继续，稍后刷新可见（素材已留存 learn/_raw/ 兜底）。
class _DigestInputPage extends StatefulWidget {
  final ApiService api;
  const _DigestInputPage({required this.api});

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

  /// 代际令牌：重试/确认后旧轮询自行失效，防两条轮询并行（沿用既有防护写法）。
  int _gen = 0;

  static const _pollInterval = Duration(seconds: 2);
  static const _pollDeadline = Duration(seconds: 150);

  @override
  void dispose() {
    _gen++; // 页面销毁 → 在途轮询作废
    _linkCtl.dispose();
    _contentCtl.dispose();
    _platformCtl.dispose();
    _authorCtl.dispose();
    super.dispose();
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

  String? _trimOrNull(TextEditingController c) {
    final v = c.text.trim();
    return v.isEmpty ? null : v;
  }

  /// 错误人话：优先取后端 error body（400 人话，如「请给我一个链接，或者把素材内容粘进来」），
  /// 取不到就按错误类型给自然口吻兜底（不把技术原话甩给用户）。
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
    return '这次没成功，稍后再试一次';
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
}
