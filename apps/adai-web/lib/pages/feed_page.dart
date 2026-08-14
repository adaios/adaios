import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../services/models/tag_models.dart';
import '../models/feed_models.dart';
import '../widgets/page_header.dart';
import '../widgets/desktop_feed_card.dart';

/// Feed 桌面形态 — 主对话流居中限宽 ~880 + 右上下文栏 300。
///
/// 桌面原生交互：卡片为时间竖列 + 内容形态，hover 提升；对话状态机
/// （idle/waiting/chatting/ended）与 adai-app 一致，UI 独立重绘。
class FeedPage extends StatefulWidget {
  final ApiService api;

  const FeedPage({super.key, required this.api});

  @override
  State<FeedPage> createState() => _FeedPageState();
}

class _FeedPageState extends State<FeedPage> {
  final ScrollController _scrollController = ScrollController();
  final GlobalKey<_DesktopInputBarState> _inputBarKey = GlobalKey();

  List<FeedCardData> _cards = [];
  int _totalToday = 0;
  String _brief = '';
  bool _loading = true;

  // #101 Feed 分页：固定页大小 + 当前页，列表底部「加载更早」追加
  static const int _pageSize = 20;
  int _currentPage = 0;
  bool _loadingMore = false;
  bool _hasMore = false;

  /// #234：已加载核心条目数（后端 type=record/card 都映射为 FeedCardType.record）。
  /// _totalToday 只计核心记录，附加条目（action/market/push）不计入分页终止判定。
  int get _coreCardCount => _cards.where((c) => c.type == FeedCardType.record).length;

  // 右上下文栏数据
  TagsResponse? _tags;
  TaskStatsResponse? _taskStats;
  bool _loadingSidebar = false; // #242：右栏加载去重守卫（getTaskStats 无缓存每次网络请求）

  // 对话状态
  String? _activeCardId;
  bool _hasActiveChat = false;
  int _chatEnterTurnCount = 0;

  @override
  void initState() {
    super.initState();
    _loadFeed(); // 内部联动 _loadSidebar（#115：右栏随 Feed 刷新更新）
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadFeed() async {
    try {
      final brief = await widget.api.getBrief();
      final feed = await widget.api.getFeed(page: 0, size: _pageSize);
      if (!mounted) return;
      final newCards = feed.entries
          .where((e) => e.type != FeedEntryType.aiNote)
          .map((e) => e.toFeedData(
            api: widget.api,
            onMarkDone: e.type == FeedEntryType.action ? () => _markActionDone(e.id) : null,
          ))
          .toList();
      setState(() {
        _brief = brief;
        _totalToday = feed.totalToday;
        _currentPage = 0;
        _cards = newCards;
        // F29（对齐 adai-app P0-1）：活动卡被刷新挤出 page0 → 静默退出对话态，防 hasActiveChat 与内容错乱
        _syncActiveCard(newCards);
        // #234：终止判定按核心条目数（record/card），附加条目不计入——否则 page 0 附加条目多时误判无更多
        _hasMore = _coreCardCount < _totalToday;
        _loading = false;
      });
      // #115：右栏（标签云/任务快照）随 Feed 刷新联动更新
      _loadSidebar();
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
      _showError('加载失败，请确认后端已启动');
    }
  }

  /// #101：Feed「加载更早」分页——更早记录（第 page+1 页）追加到列表底部。
  /// 后端 /feed 分页：page 从 0 起，核心记录新→旧切片。
  Future<void> _loadMore() async {
    // #234：终止判定按核心条目数（record/card），附加条目不计入
    if (_loadingMore || _coreCardCount >= _totalToday) return;
    setState(() => _loadingMore = true);
    try {
      final feed = await widget.api.getFeed(page: _currentPage + 1, size: _pageSize);
      if (!mounted) return;
      final moreCards = feed.entries
          .where((e) => e.type != FeedEntryType.aiNote)
          .map((e) => e.toFeedData(
            api: widget.api,
            onMarkDone: e.type == FeedEntryType.action ? () => _markActionDone(e.id) : null,
          ))
          .toList();
      setState(() {
        _currentPage += 1;
        _cards = [..._cards, ...moreCards];
        // #234：终止判定按已加载核心条目数；页面无更多数据（moreCards 空）同样终止
        _hasMore = moreCards.isNotEmpty && _coreCardCount < _totalToday;
        _loadingMore = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loadingMore = false);
      _showError('加载更早失败');
    }
  }

  Future<void> _loadSidebar() async {
    // #242：去重守卫——正在加载则跳过新请求，防连续聊天并发弱竞态（后到旧响应覆盖新）
    if (_loadingSidebar) return;
    _loadingSidebar = true;
    try {
      final tags = await widget.api.getTags();
      final stats = await widget.api.getTaskStats();
      if (!mounted) return;
      setState(() {
        _tags = tags;
        _taskStats = stats;
      });
    } catch (_) {
      // 右栏加载失败不阻塞主对话流
    } finally {
      _loadingSidebar = false;
    }
  }

  // ── 输入 / 对话逻辑（与 adai-app 状态机一致，UI 桌面化） ──

  void _onSend(String text) {
    final timeStr = _now();
    if (_activeCardId != null) {
      setState(() => _hasActiveChat = false);
      _appendToActiveCard(text, timeStr);
      return;
    }
    setState(() => _hasActiveChat = false);
    _createNewCard(text, timeStr);
  }

  /// 多模态 L4：多图逐张上传（每张一条记录+记忆，caption 共享）。
  /// REVIEW #255（对齐 #174）：逐张上传进度占位——每张图先插入 loading 占位卡（立即视觉反馈，
  /// 不再多图干等），单张完成后原位替换为真实记录卡，失败置 error 可重试。
  Future<void> _onSendMedia(List<PickedImage> images, String caption) async {
    if (images.isEmpty) return;
    final timeStr = _now();
    final placeholderIds = <String>[];
    setState(() {
      for (final image in images) {
        final pid = 'media_${DateTime.now().microsecondsSinceEpoch}_${placeholderIds.length}';
        placeholderIds.add(pid);
        _cards.add(FeedCardData(
          id: pid, type: FeedCardType.record, time: timeStr,
          content: caption.isEmpty ? image.name : caption,
          mode: CardMode.idle, loading: true,
        ));
      }
    });
    _scrollToBottom();

    var ok = 0;
    final uploadedIds = <String>[]; // S-1 带图 ask：成功上传的 recordId 集合（上传后统一问）
    String? firstErr;
    for (var i = 0; i < images.length; i++) {
      final image = images[i];
      try {
        final resp = await widget.api.uploadImage(
          bytes: image.bytes,
          filename: image.name,
          mimeType: _mimeTypeOf(image.extension),
          caption: caption.isEmpty ? null : caption,
        );
        ok++;
        if (resp.recordId.isNotEmpty) uploadedIds.add(resp.recordId);
        if (!mounted) return;
        // 单张完成 → 占位卡原位替换为真实记录卡（mediaUrl 指向原图，L4 可追问）
        setState(() {
          final idx = _cards.indexWhere((c) => c.id == placeholderIds[i]);
          if (idx >= 0) {
            final fallback = caption.isEmpty ? image.name : caption;
            _cards[idx] = FeedCardData(
              id: resp.recordId.isEmpty ? placeholderIds[i] : resp.recordId,
              type: FeedCardType.record,
              time: timeStr,
              content: resp.summary.isEmpty ? fallback : resp.summary,
              summary: resp.summary.isEmpty ? null : resp.summary,
              tags: resp.tags.isNotEmpty ? resp.tags : null,
              mode: CardMode.idle,
              intent: IntentType.log,
              domain: 'life',
              mediaUrl: resp.recordId.isEmpty ? null : widget.api.mediaUrl(resp.recordId),
              mediaHeaders: resp.recordId.isEmpty ? null : widget.api.mediaHeaders,
            );
          }
        });
        _scrollToBottom();
      } catch (e) {
        firstErr ??= _extractApiError(e);
        if (!mounted) return;
        // 单张失败 → 占位卡置 error（底部可重试）
        setState(() {
          final idx = _cards.indexWhere((c) => c.id == placeholderIds[i]);
          if (idx >= 0) {
            _cards[idx] = _cards[idx].copyWith(loading: false, error: _extractApiError(e));
          }
        });
      }
    }
    if (!mounted) return;
    if (ok > 0) {
      // S-1 带图 ask：附了文本 + 有成功图 → 后端按 intent 分流（问句 → VLM 多图回答；陈述 → 纯记录）
      final question = caption.trim();
      if (question.isNotEmpty && uploadedIds.isNotEmpty) {
        String feedback;
        try {
          final qa = await widget.api.askBatch(
              imageRecordIds: uploadedIds, question: question);
          feedback = qa.intent == 'question' && qa.answer.isNotEmpty
              ? '💬 ${_truncateForSnack(qa.answer)}'
              : '📷 已记录 $ok 张';
        } catch (e) {
          feedback = '📷 已记录 $ok 张（问答失败: ${_extractApiError(e)}）';
        }
        _showSnackBar(feedback);
        _totalToday += ok;
        _loadSidebar();
        if (!mounted) return;
        // 刷新 Feed：问句 → 首图卡显示 Q/A 气泡（后端已把 turns 合并到首图卡）
        await _loadFeed();
        return;
      }
      _showSnackBar(
        ok == images.length ? '📷 已记录 $ok 张' : '📷 已记录 $ok 张，${images.length - ok} 张失败',
      );
      // 占位卡已原位替换为真实记录，不再整体 _loadFeed；本地计数 + 右栏联动刷新（#115）
      _totalToday += ok;
      _loadSidebar();
    } else {
      _showError('图片上传失败: $firstErr');
    }
  }

  /// SnackBar 展示文本截断（多图问答回答较长，避免整条撑爆提示条）。
  String _truncateForSnack(String s, [int max = 60]) {
    if (s.length <= max) return s;
    return '${s.substring(0, max)}…';
  }

  String _mimeTypeOf(String? ext) {
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

  Future<void> _createNewCard(String text, String timeStr) async {
    final cardId = 'card_${DateTime.now().millisecondsSinceEpoch}';
    setState(() => _cards.add(FeedCardData(
      id: cardId, type: FeedCardType.record, time: timeStr, content: text,
      mode: CardMode.idle, loading: true,
    )));
    _scrollToBottom();

    try {
      final resp = await widget.api.createRecord(text, cardId: cardId);
      if (!mounted) return;
      if (IntentType.parse(resp.intent) == IntentType.question) {
        final aiTimeStr = _now();
        setState(() {
          _activeCardId = cardId;
          _deactivateOtherCards(cardId);
          _updateCard(cardId, (c) => c.copyWith(
            mode: CardMode.chatting, loading: false, intent: IntentType.question,
            turns: [
              ConversationTurn(isUser: true, text: text, time: timeStr),
              if (resp.rawResponse != null || resp.summary != null)
                ConversationTurn(isUser: false, text: resp.rawResponse ?? resp.summary!, time: aiTimeStr),
            ],
            tags: resp.tags,
            domain: resp.domain,
          ));
        });
      } else {
        setState(() {
          _totalToday += 1; // 本地计数跟随（#119）
          _updateCard(cardId, (c) => c.copyWith(
            summary: resp.summary ?? '已记录', tags: resp.tags,
            loading: false, mode: CardMode.idle, intent: IntentType.log, domain: resp.domain,
          ));
        });
      }
      // #115：新记录落盘 → 右栏标签云/任务快照联动刷新
      _loadSidebar();
      _scrollToBottom();
    } catch (e) {
      if (!mounted) return;
      setState(() => _updateCard(cardId, (c) => c.copyWith(loading: false, error: _extractApiError(e))));
      _scrollToBottom();
    }
  }

  Future<void> _appendToActiveCard(String text, String timeStr) async {
    // 先捕获 cardId 到局部变量：await 期间 _closeChat 可能置 _activeCardId=null，
    // 后续用 `!` 解引用会空值断言崩溃 + 回复丢失（#100 P0）。
    final cardId = _activeCardId;
    if (cardId == null) return;
    // #205：indexWhere 安全跳过（firstWhere 找不到同步抛 StateError）。
    // 卡片被替换/刷新后不在 _cards 时静默返回，与 _updateCard 语义一致。
    final activeIdx = _cards.indexWhere((c) => c.id == cardId);
    if (activeIdx < 0) return;
    final activeCard = _cards[activeIdx];
    final isImageAsk = activeCard.mediaUrl != null;

    setState(() {
      _updateCard(cardId, (c) {
        final existing = c.turns ?? [];
        return c.copyWith(mode: CardMode.chatting, loading: true, turns: existing.isEmpty
            // 图片追问：图即上下文（缩略图已在上方），不重复塞图片摘要作为首轮；
            // 文本卡保持原行为：首轮 = 卡片原内容 + 用户新消息（点卡激活后直接输入的场景）
            ? (isImageAsk
                ? [ConversationTurn(isUser: true, text: text, time: timeStr)]
                : [ConversationTurn(isUser: true, text: c.content, time: c.time),
                   ConversationTurn(isUser: true, text: text, time: timeStr)])
            : [...existing, ConversationTurn(isUser: true, text: text, time: timeStr)]);
      });
    });
    _scrollToBottom();
    try {
      if (isImageAsk) {
        // 图片追问：VLM 看图回答（L4 图片问答），沉淀为 image_qa 记录
        final resp = await widget.api.askMedia(imageRecordId: cardId, question: text);
        if (!mounted) return;
        setState(() {
          _updateCard(cardId, (c) {
            final existing = c.turns ?? [];
            return c.copyWith(mode: CardMode.chatting, loading: false, intent: IntentType.question,
                turns: [...existing, ConversationTurn(isUser: false, text: resp.answer, time: _now())]);
          });
        });
        // #115/#229：图片追问沉淀 image_qa 带 tags → 右栏标签云联动刷新
        _loadSidebar();
        _scrollToBottom();
        return;
      }
      final resp = await widget.api.createRecord(text, cardId: cardId);
      if (!mounted) return;
      final aiReply = resp.rawResponse ?? resp.summary;
      if (aiReply != null) {
        // await 后卡片可能已关闭/删除：_updateCard 按 id 定位，不存在则安全跳过
        setState(() {
          _updateCard(cardId, (c) {
            final existing = c.turns ?? [];
            return c.copyWith(mode: CardMode.chatting, loading: false, intent: IntentType.question,
                turns: [...existing, ConversationTurn(isUser: false, text: aiReply, time: _now())]);
          });
        });
        // #115：AI 回复带标签 → 右栏标签云联动刷新
        _loadSidebar();
        _scrollToBottom();
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _updateCard(cardId, (c) => c.copyWith(loading: false)));
      _showError(_extractApiError(e));
    }
  }

  void _onAskCard(String cardId) {
    final card = _cards.where((c) => c.id == cardId).firstOrNull;
    if (card == null) return;

    // 图片追问（L4 图片问答）：图即上下文，点提问后等用户输入问题，
    // 不走文本 _doAskRequest（那个会把图片摘要文本当问题发给文本 LLM）。
    if (card.mediaUrl != null) {
      final hasTurns = card.turns != null && card.turns!.isNotEmpty;
      setState(() {
        _activeCardId = cardId;
        _hasActiveChat = true;
        _chatEnterTurnCount = card.turns?.length ?? 0;
        _deactivateOtherCards(cardId);
        _updateCard(cardId, (c) => c.copyWith(
            mode: hasTurns ? CardMode.chatting : CardMode.waiting,
            loading: false,
            intent: IntentType.question));
      });
      _scrollToBottom();
      return;
    }

    if (card.turns != null && card.turns!.isNotEmpty) {
      setState(() {
        _activeCardId = cardId;
        _hasActiveChat = true;
        _chatEnterTurnCount = card.turns!.length;
        // 重开已有 turns 卡：其他卡置 idle 防互踩（#105）+ 本卡进入 chatting 与底部 end 按钮同步（#111）
        _deactivateOtherCards(cardId);
        _updateCard(cardId, (c) => c.copyWith(mode: CardMode.chatting));
      });
      _scrollToBottom();
      return;
    }

    setState(() {
      _activeCardId = cardId;
      _hasActiveChat = true;
      _chatEnterTurnCount = 0;
      _deactivateOtherCards(cardId);
      _updateCard(cardId, (c) => c.copyWith(mode: CardMode.waiting, loading: true, intent: IntentType.question));
    });
    _scrollToBottom();

    _doAskRequest(cardId, card.content);
  }

  Future<void> _doAskRequest(String cardId, String content) async {
    try {
      final resp = await widget.api.createRecord(content, intent: 'question', cardId: cardId);
      if (!mounted) return;
      setState(() {
        _deactivateOtherCards(cardId);
        _updateCard(cardId, (c) => c.copyWith(
          mode: CardMode.chatting, loading: false,
          turns: [
            ConversationTurn(isUser: true, text: content, time: _now()),
            if (resp.rawResponse != null || resp.summary != null)
              ConversationTurn(isUser: false, text: resp.rawResponse ?? resp.summary!, time: _now()),
          ],
          tags: resp.tags,
          domain: resp.domain,
        ));
      });
      _scrollToBottom();
    } catch (e) {
      if (!mounted) return;
      setState(() => _updateCard(cardId, (c) => c.copyWith(mode: CardMode.idle, loading: false)));
      _showError(_extractApiError(e));
    }
  }

  Future<void> _closeChat(String cardId) async {
    final card = _cards.firstWhere((c) => c.id == cardId);
    final currentTurns = card.turns?.length ?? 0;
    final hasNewTurns = currentTurns > _chatEnterTurnCount;
    final needsSummary = card.summary == null && (card.turns?.isNotEmpty ?? false);

    if (!hasNewTurns && !needsSummary) {
      setState(() {
        _activeCardId = null;
        _hasActiveChat = false;
        // #219：图片卡点「提问」后不输入直接关闭 → mode 仍 waiting 残留（且无法复位）。
        // 早退分支无条件复位 waiting 卡为 idle，与文本分支语义一致。
        _updateCard(cardId, (c) => c.copyWith(
            mode: c.mode == CardMode.waiting ? CardMode.idle : c.mode,
            intent: IntentType.question));
      });
      return;
    }

    setState(() {
      _activeCardId = null;
      _hasActiveChat = false;
      _updateCard(cardId, (c) => c.copyWith(loading: true, mode: CardMode.idle, expanded: false));
    });

    try {
      final turns = card.turns?.map((t) => t.text).toList() ?? [];
      final resp = await widget.api.endConversation(turns, cardId: cardId);
      if (!mounted) return;
      setState(() {
        _updateCard(cardId, (c) => c.copyWith(
          summary: resp.summary, tags: resp.tags,
          loading: false, mode: CardMode.ended, intent: IntentType.question,
        ));
      });
      // #115：结束对话生成总结/标签 → 右栏标签云联动刷新
      _loadSidebar();
    } catch (e) {
      if (!mounted) return;
      _showError('生成总结失败: ${_extractApiError(e)}');
      setState(() => _updateCard(cardId, (c) => c.copyWith(loading: false, mode: CardMode.idle)));
    }
  }

  Future<void> _deleteCard(String id) async {
    // 删除确认（#109）：DELETE 连带清理 record+card+memory，不可逆
    final confirmed = await _confirmDelete();
    if (!confirmed) return;
    try {
      await widget.api.deleteRecord(id);
      if (!mounted) return;
      setState(() {
        _cards.removeWhere((c) => c.id == id);
        // 删除 active 卡 → 清理全局引用，防后续输入 append 到已删卡（#104）
        if (_activeCardId == id) {
          _activeCardId = null;
          _hasActiveChat = false;
        }
        // 本地计数跟随（#119）
        if (_totalToday > 0) _totalToday -= 1;
      });
      // #115：删除记录 → 标签/任务统计变化，右栏联动刷新
      _loadSidebar();
    } catch (_) {
      if (mounted) _showError('删除失败');
    }
  }

  Future<bool> _confirmDelete() async {
    final result = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('删除记录', style: TextStyle(fontSize: 16)),
        content: const Text('将同时删除该记录及其对话、记忆，此操作不可恢复。确定删除？', style: TextStyle(fontSize: 13)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('删除', style: TextStyle(color: AppColors.darkRed)),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  /// 失败重试：复用原 cardId 重新 POST（后端按 cardId 幂等去重，避免半失败重复入账，#110）。
  Future<void> _retryCard(String id) async {
    final idx = _cards.indexWhere((c) => c.id == id);
    if (idx < 0) return;
    final content = _cards[idx].content;
    setState(() => _cards[idx] = _cards[idx].copyWith(loading: true, clearError: true));
    try {
      final resp = await widget.api.createRecord(content, cardId: id);
      if (!mounted) return;
      setState(() {
        _updateCard(id, (c) => c.copyWith(
          summary: resp.summary ?? '已记录', tags: resp.tags,
          loading: false, mode: CardMode.idle, intent: IntentType.log, domain: resp.domain,
        ));
      });
      // #115：重试落盘 → 右栏标签云联动刷新
      _loadSidebar();
      _scrollToBottom();
    } catch (e) {
      if (!mounted) return;
      setState(() => _updateCard(id, (c) => c.copyWith(loading: false, error: _extractApiError(e))));
      _scrollToBottom();
    }
  }

  Future<void> _markActionDone(String memoryId) async {
    try {
      await widget.api.markMemoryDone(memoryId);
      if (!mounted) return;
      setState(() => _cards.removeWhere((c) => c.id == memoryId));
      // #115：待办完成 → 任务快照统计变化，右栏联动刷新
      _loadSidebar();
    } catch (_) {
      if (mounted) _showError('标记完成失败');
    }
  }

  void _changeDomain(String id, String domain) {
    setState(() {
      final idx = _cards.indexWhere((c) => c.id == id);
      if (idx >= 0) _cards[idx] = _cards[idx].copyWith(domain: domain);
    });
    widget.api.updateRecordDomain(id, domain).catchError((_) {
      if (mounted) _showError('更新 OS 标记失败');
    });
  }

  void _updateCard(String id, FeedCardData Function(FeedCardData) updater) {
    final idx = _cards.indexWhere((c) => c.id == id);
    if (idx >= 0) _cards[idx] = updater(_cards[idx]);
  }

  /// F29（对齐 adai-app P0-1）：卡片列表重建后校验活动卡仍在列表中——被刷新挤出时
  /// 静默退出对话态，防输入栏 hasActiveChat 状态与 Feed 实际内容错乱。
  void _syncActiveCard(List<FeedCardData> cards) {
    if (_activeCardId != null && !cards.any((c) => c.id == _activeCardId)) {
      _activeCardId = null;
      _hasActiveChat = false;
    }
  }

  void _deactivateOtherCards(String keepId) {
    for (int i = 0; i < _cards.length; i++) {
      if (_cards[i].id != keepId && (_cards[i].mode == CardMode.waiting || _cards[i].mode == CardMode.chatting)) {
        _cards[i] = _cards[i].copyWith(mode: CardMode.idle);
      }
    }
  }

  String _now() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOut,
      );
    });
  }

  /// 通用 SnackBar 提示（对齐 adai-app _showSnackBar）。
  void _showSnackBar(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      margin: const EdgeInsets.fromLTRB(20, 0, 20, 12),
      duration: const Duration(seconds: 3),
    ));
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      margin: const EdgeInsets.fromLTRB(20, 0, 20, 12),
      duration: const Duration(seconds: 3),
    ));
  }

  String _extractApiError(dynamic e) {
    final str = e.toString();
    if (str.contains('API 请求失败')) {
      final codeMatch = RegExp(r'HTTP (\d+)').firstMatch(str);
      final code = codeMatch?.group(1) ?? '?';
      return '请求失败 ($code)';
    }
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器';
    return '网络异常，请重试';
  }

  // ── 布局 ──

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      PageHeader(
        title: '对话流',
        subtitle: _totalToday > 0 ? '今日 $_totalToday 条记录' : null,
        actions: [
          if (_cards.isNotEmpty)
            TextButton.icon(
              onPressed: _loadFeed,
              icon: const Icon(Icons.refresh, size: 16),
              label: const Text('刷新'),
              style: TextButton.styleFrom(foregroundColor: AppColors.darkGrey4),
            ),
        ],
      ),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // 主对话流（居中限宽 880）
                  Expanded(child: _buildMainFlow()),
                  // 右上下文栏
                  Container(width: 300, color: AppColors.darkSurface, child: _buildSidebar()),
                ],
              ),
      ),
    ]);
  }

  Widget _buildMainFlow() {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 880),
        child: Column(children: [
          Expanded(
            child: _cards.isEmpty ? _buildEmptyState() : _buildFeedList(),
          ),
          _buildInputBar(),
        ]),
      ),
    );
  }

  Widget _buildFeedList() {
    // #234：终止判定按核心条目数（record/card），附加条目不计入
    final hasMore = _hasMore && _coreCardCount < _totalToday;
    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.symmetric(vertical: 12),
      itemCount: _cards.length + (hasMore ? 1 : 0),
      itemBuilder: (_, i) {
        if (i >= _cards.length) return _buildLoadMoreBanner();
        final card = _cards[i];
        return DesktopFeedCard(
          key: ValueKey(card.id),
          data: card,
          onAsk: () => _onAskCard(card.id),
          onEnd: () => _closeChat(card.id),
          onDelete: () => _deleteCard(card.id),
          onRetry: card.error != null ? () => _retryCard(card.id) : null,
          onToggleExpand: () {
            final idx = _cards.indexWhere((c) => c.id == card.id);
            if (idx >= 0) {
              setState(() => _cards[idx] = _cards[idx].copyWith(expanded: !_cards[idx].expanded));
            }
          },
          onDomainChanged: (domain) => _changeDomain(card.id, domain),
        );
      },
    );
  }

  /// #101：Feed 底部「加载更早」入口（桌面端按钮形态，加载中转 spinner）。
  Widget _buildLoadMoreBanner() {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 16),
      child: Center(
        child: _loadingMore
            ? const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGrey4),
              )
            : TextButton.icon(
                onPressed: _loadMore,
                icon: const Icon(Icons.expand_more, size: 16),
                label: const Text('加载更早'),
                style: TextButton.styleFrom(foregroundColor: AppColors.darkGrey4),
              ),
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text('✦ ✦ ✦', style: TextStyle(fontSize: 24, color: AppColors.darkGrey6)),
          const SizedBox(height: 16),
          const Text('还没有记录',
              style: TextStyle(fontSize: 16, color: AppColors.darkGrey4, fontWeight: FontWeight.w500)),
          const SizedBox(height: 8),
          const Text('在下方输入你的第一条记录', style: TextStyle(fontSize: 13, color: AppColors.darkGrey6)),
          const SizedBox(height: 32),
          // #159 快速开始引导 chips：点击填入输入框并聚焦
          Row(mainAxisSize: MainAxisSize.min, children: [
            _emptyChip('📝 记录心情', () => _inputBarKey.currentState?.prefillText('今天心情')),
            const SizedBox(width: 12),
            _emptyChip('🤔 问个问题', () => _inputBarKey.currentState?.prefillText('')),
          ]),
        ],
      ),
    );
  }

  Widget _emptyChip(String label, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          border: Border.all(color: AppColors.darkBorder),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(label, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3)),
      ),
    );
  }

  Widget _buildInputBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 16),
      child: _DesktopInputBar(key: _inputBarKey, onSend: _onSend, onSendMedia: _onSendMedia, hasActiveChat: _hasActiveChat),
    );
  }

  Widget _buildSidebar() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
      children: [
        _buildBriefCard(),
        const SizedBox(height: 16),
        _buildTagCloud(),
        const SizedBox(height: 16),
        _buildTaskSnapshot(),
      ],
    );
  }

  Widget _buildBriefCard() {
    final lines = _brief.split('\n').where((l) => l.trim().isNotEmpty).toList();
    return _sidebarSection(
      title: '今日简报',
      child: lines.isEmpty
          ? const Text('暂无摘要', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              // 去绿点：AI 每行自带 emoji（prompt 要求）直接展示，双前缀冲突消除
              children: lines.map((line) => Padding(
                padding: const EdgeInsets.only(bottom: 5),
                child: Text(line, style: const TextStyle(fontSize: 12, height: 1.5, color: AppColors.darkGrey3)),
              )).toList(),
            ),
    );
  }

  Widget _buildTagCloud() {
    final tags = _tags?.tags ?? [];
    return _sidebarSection(
      title: '标签云',
      child: tags.isEmpty
          ? const Text('暂无标签', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
          : Wrap(
              spacing: 6,
              runSpacing: 6,
              children: tags.take(12).map((t) => Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
                ),
                child: Text('#${t.name}', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3)),
              )).toList(),
            ),
    );
  }

  Widget _buildTaskSnapshot() {
    final s = _taskStats;
    return _sidebarSection(
      title: '任务快照',
      child: s == null
          ? const Text('暂无任务', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
          : Row(children: [
              _statCell('待做', s.todo, AppColors.darkOrange),
              const SizedBox(width: 8),
              _statCell('进行中', s.doing, AppColors.darkBlue),
              const SizedBox(width: 8),
              _statCell('已完成', s.done, AppColors.darkGreen),
            ]),
    );
  }

  Widget _sidebarSection({required String title, required Widget child}) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGrey5)),
          const SizedBox(height: 8),
          child,
        ],
      ),
    );
  }

  Widget _statCell(String label, int value, Color color) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 8),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(children: [
          Text('$value', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: color)),
          Text(label, style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
        ]),
      ),
    );
  }
}

/// 用户选择的图片（多模态 L4，交给宿主上传）。
class PickedImage {
  final List<int> bytes;
  final String name;
  final String? extension;

  const PickedImage(this.bytes, this.name, this.extension);
}

/// 桌面输入栏 — 文本输入 + 图片上传 + 发送（无语音，桌面形态）。
class _DesktopInputBar extends StatefulWidget {
  final ValueChanged<String> onSend;
  final bool hasActiveChat;
  final void Function(List<PickedImage> images, String caption)? onSendMedia; // 多图 + 可选文字一起提交

  const _DesktopInputBar({super.key, required this.onSend, required this.hasActiveChat, this.onSendMedia});

  @override
  State<_DesktopInputBar> createState() => _DesktopInputBarState();
}

class _DesktopInputBarState extends State<_DesktopInputBar> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  final List<PickedImage> _pendingImages = []; // 输入栏内联附件：选图后待发送，非立即上传

  /// 从外部预设输入框文本并聚焦（空态快速开始 chips，#159）。
  void prefillText(String text) {
    _controller.text = text;
    _controller.selection = TextSelection.collapsed(offset: text.length);
    _focusNode.requestFocus();
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _send() {
    final text = _controller.text.trim();
    final images = List<PickedImage>.of(_pendingImages);
    if (images.isEmpty && text.isEmpty) return;
    _controller.clear();
    setState(() => _pendingImages.clear());
    if (images.isNotEmpty) {
      // 图 + 文字（可空，caption 共享）一起提交，逐张上传
      widget.onSendMedia?.call(images, text);
    } else {
      widget.onSend(text);
    }
  }

  Future<void> _pickImage() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.image,
        withData: true,
        allowMultiple: true, // 多选，逐张上传
      );
      if (result == null || result.files.isEmpty) return;
      final picked = result.files
          .where((f) => f.bytes != null)
          .map((f) => PickedImage(f.bytes!, f.name, f.extension))
          .toList();
      // S-1 带图 ask 图片上限 3（后端 ask-batch 强校验；前端选图即限，多余截断 + 提示）
      final remaining = 3 - _pendingImages.length;
      if (remaining <= 0) {
        _showSnackBar('最多 3 张图片');
        return;
      }
      if (picked.length > remaining) {
        setState(() => _pendingImages.addAll(picked.take(remaining)));
        _showSnackBar('最多 3 张图片，已保留前 $remaining 张');
      } else {
        // 选图后先挂到输入栏（内联预览），发送时才真正上传
        setState(() => _pendingImages.addAll(picked));
      }
    } catch (e) {
      if (!mounted) return;
      _showSnackBar('图片选择失败: $e');
    }
  }

  /// 桌面输入栏 SnackBar 提示（选图/上限反馈）。
  void _showSnackBar(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      margin: const EdgeInsets.fromLTRB(20, 0, 20, 12),
      duration: const Duration(seconds: 3),
    ));
  }

  /// 输入栏上方的图片附件预览（横向缩略图列表，每张可单独移除）。
  Widget _buildImagePreview() {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: SizedBox(
        height: 56,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: _pendingImages.length,
          separatorBuilder: (_, _) => const SizedBox(width: 8),
          itemBuilder: (_, i) => _buildThumb(_pendingImages[i], i),
        ),
      ),
    );
  }

  Widget _buildThumb(PickedImage image, int index) {
    return Stack(
      clipBehavior: Clip.none,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: Image.memory(
            Uint8List.fromList(image.bytes),
            width: 56,
            height: 56,
            fit: BoxFit.cover,
            errorBuilder: (_, _, _) => Container(
              width: 56,
              height: 56,
              color: AppColors.darkSurface,
              child: const Icon(Icons.broken_image_outlined, size: 20, color: AppColors.darkGrey5),
            ),
          ),
        ),
        Positioned(
          top: -6,
          right: -6,
          child: InkWell(
            onTap: () => setState(() => _pendingImages.removeAt(index)),
            child: Container(
              padding: const EdgeInsets.all(2),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.darkBorder),
              ),
              child: const Icon(Icons.close, size: 12, color: AppColors.darkGrey4),
            ),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        if (_pendingImages.isNotEmpty) _buildImagePreview(),
        Row(children: [
          Expanded(
            child: TextField(
              controller: _controller,
              focusNode: _focusNode,
              maxLines: 1,
              onSubmitted: (_) => _send(),
              style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
              decoration: InputDecoration(
                hintText: _pendingImages.isNotEmpty
                    ? '添加说明（可空）…'
                    : (widget.hasActiveChat ? '继续对话…' : '记录或提问…'),
                hintStyle: const TextStyle(fontSize: 13, color: AppColors.darkGrey5),
                border: InputBorder.none,
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
              ),
            ),
          ),
          const SizedBox(width: 4),
          IconButton(
            onPressed: _pickImage,
            icon: const Icon(Icons.image_outlined, size: 18),
            color: _pendingImages.isNotEmpty ? AppColors.darkGreen : AppColors.darkGrey4,
            tooltip: _pendingImages.isNotEmpty ? '更换图片' : '选择图片',
            style: IconButton.styleFrom(minimumSize: const Size(34, 34)),
          ),
          const SizedBox(width: 2),
          IconButton(
            onPressed: _send,
            icon: const Icon(Icons.arrow_upward, size: 18),
            color: AppColors.darkBg,
            style: IconButton.styleFrom(
              backgroundColor: _pendingImages.isNotEmpty ? AppColors.darkGreen : AppColors.darkGreen.withValues(alpha: 0.6),
              minimumSize: const Size(34, 34),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(17)),
            ),
          ),
        ]),
      ]),
    );
  }
}
