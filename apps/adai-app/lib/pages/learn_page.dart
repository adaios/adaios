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
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器，请确认后端已启动';
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
          child: Text('还没有学习卡片\n点右上角「＋」粘贴视频字幕或文章，阿呆帮你消化成卡片',
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

/// 「整理新内容」喂入页（2026-09-10 learn 喂入入口批·移动端）。
/// 提交式：POST /learn/cards 立即返回 → 轮询 GET /learn/digest/status（每 2s，上限 150s）→
/// done 以 (type,title) pop 回列表页打开新卡；failed 页内人话 + 可重试；
/// 超时/返回 → 消化仍在后台继续，稍后刷新可见（素材已留存 learn/_raw/ 兜底）。
class _DigestInputPage extends StatefulWidget {
  final ApiService api;
  const _DigestInputPage({required this.api});

  @override
  State<_DigestInputPage> createState() => _DigestInputPageState();
}

class _DigestInputPageState extends State<_DigestInputPage> {
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
        _error = _apiError(e);
      });
    }
  }

  String? _trimOrNull(TextEditingController c) {
    final v = c.text.trim();
    return v.isEmpty ? null : v;
  }

  /// 错误人话：优先取后端 error body（LearnException 400 人话，如「AI 消化失败，素材已留存…」）。
  String _apiError(dynamic e) {
    if (e is ApiException && e.body != null) {
      try {
        final json = jsonDecode(e.body!);
        if (json is Map && json['error'] is String) return json['error'] as String;
      } catch (_) {}
      return e.message;
    }
    final s = e.toString();
    if (s.contains('TimeoutException') || s.contains('timed out')) return '请求超时，请检查网络';
    if (s.contains('SocketException') || s.contains('Connection refused')) return '无法连接服务器，请确认后端已启动';
    return '操作失败，请重试';
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
        setState(() => _progress = '查询消化进度失败（${_apiError(e)}），继续等待…');
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
              Text(_polling ? '消化中' : '整理新内容',
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
            ]),
          ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
              child: _polling ? _buildPolling() : _buildForm(),
            ),
          ),
          if (!_polling)
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

  Widget _buildPolling() {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 60),
      child: Column(children: [
        const SizedBox(width: 30, height: 30, child: CircularProgressIndicator(strokeWidth: 2.5)),
        const SizedBox(height: 16),
        Text(_progress,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey4, height: 1.6)),
        const SizedBox(height: 8),
        const Text('可以返回「最近学习」，稍后下拉刷新查看',
            style: TextStyle(fontSize: 12, color: AppColors.darkGrey6)),
      ]),
    );
  }

  Widget _buildForm() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      TextField(
        controller: _contentCtl,
        maxLines: 10,
        maxLength: 50000,
        style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1, height: 1.6),
        decoration: const InputDecoration(
          hintText: '粘贴素材内容：视频字幕 / 文章原文 / 链接正文…\n（阿呆只做结构化整理，不代抓取外部链接；纯链接请先粘贴正文）',
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
      const SizedBox(height: 12),
      TextField(
        controller: _urlCtl,
        style: const TextStyle(fontSize: 13.5, color: AppColors.darkGrey1),
        decoration: const InputDecoration(
          hintText: '原文链接（可选）',
          hintStyle: TextStyle(color: AppColors.darkGrey6, fontSize: 12.5),
          isDense: true,
          border: OutlineInputBorder(),
        ),
      ),
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
