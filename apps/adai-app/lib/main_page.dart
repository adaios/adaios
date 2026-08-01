import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'widgets/feed_card.dart';
import 'widgets/input_bar.dart';
import 'widgets/timeline_modal.dart';
import 'utils/text_cleaner.dart';

class MainPage extends StatefulWidget {
  final VoidCallback? onPullUp;
  final VoidCallback? onProfileTap;
  final String? filterTag;
  final VoidCallback? onClearFilter;

  const MainPage({super.key, this.onPullUp, this.onProfileTap, this.filterTag, this.onClearFilter});

  @override
  State<MainPage> createState() => _MainPageState();
}

class _MainPageState extends State<MainPage>
    with SingleTickerProviderStateMixin {
  final ScrollController _scrollController = ScrollController();
  final ApiService _api = ApiService();
  final GlobalKey<InputBarState> _inputBarKey = GlobalKey<InputBarState>();

  List<FeedCardData> _cards = [];
  int _totalToday = 0;
  int _currentPage = 0;
  String _brief = '';
  bool _loading = true;
  bool _loadingMore = false;     // load more 进度
  bool _scrollAtTop = true;
  bool _scrollAtBottom = true;
  static const int _pageSize = 5;

  String? _activeCardId;
  bool _hasActiveChat = false;
  int _chatEnterTurnCount = 0;

  late AnimationController _enterCtrl;
  late Animation<double> _contentAnim;

  @override
  void initState() {
    super.initState();
    _loadFeed();
    _scrollController.addListener(_onScroll);
    _enterCtrl = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 600),
    );
    _contentAnim = CurvedAnimation(parent: _enterCtrl, curve: Curves.easeOutCubic);
    _enterCtrl.forward();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    _enterCtrl.dispose();
    super.dispose();
  }

  Future<void> _refreshFeed() async {
    // 下拉刷新：不清 active 状态，只重新拉取数据
    _currentPage = 0;
    try {
      final brief = await _api.getBrief();
      final feed = await _api.getFeed(page: 0, size: _pageSize);
      if (!mounted) return;
      setState(() {
        _brief = brief;
        _totalToday = feed.totalToday;
        _cards = feed.entries
            .where((e) => e.type != FeedEntryType.aiNote)
            .map((e) => e.toFeedData())
            .toList();
      });
    } catch (e) {
      if (mounted) _showError('刷新失败');
    }
  }

  Future<void> _loadFeed({String? date}) async {
    try {
      final brief = await _api.getBrief();
      final feed = await _api.getFeed(page: 0, size: _pageSize);
      if (!mounted) return;
      final allCards = feed.entries
          .where((e) => e.type != FeedEntryType.aiNote)
          .map((e) => e.toFeedData())
          .toList();
      setState(() {
        _brief = brief;
        _totalToday = feed.totalToday;
        _currentPage = 0;
        _cards = allCards;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      _showError('加载失败');
      setState(() => _loading = false);
    }
  }

  @override
  void didUpdateWidget(MainPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.filterTag != oldWidget.filterTag) {
      setState(() {
        _cards = _cards.where((c) => c.tags?.contains(widget.filterTag) ?? false).toList();
      });
    }
  }

  void _onCardActivate(String cardId) {
    if (_activeCardId == cardId) {
      _closeChat(cardId);
      return;
    }
    final card = _cards.where((c) => c.id == cardId).firstOrNull;
    setState(() {
      _activeCardId = cardId;
      _hasActiveChat = true;
      _chatEnterTurnCount = card?.turns?.length ?? 0;
    });
    _scrollToBottom();
  }

  void _onAskCard(String cardId) {
    final card = _cards.where((c) => c.id == cardId).firstOrNull;
    if (card == null) return;

    // Card already has conversation — just reopen, don't re-ask
    if (card.turns != null && card.turns!.isNotEmpty) {
      setState(() {
        _activeCardId = cardId;
        _hasActiveChat = true;
        _chatEnterTurnCount = card.turns!.length;
      });
      _scrollToBottom();
      return;
    }

    final now = TimeOfDay.now();
    final timeStr = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';

    setState(() {
      _activeCardId = cardId;
      _hasActiveChat = true;
      _chatEnterTurnCount = 0;
      _updateCard(cardId, (c) => c.copyWith(mode: CardMode.waiting, loading: true, intent: IntentType.question));
    });
    _scrollToBottom();

    _doAskRequest(cardId, card.content, timeStr);
  }

  void _doAskRequest(String cardId, String content, String timeStr) async {
    try {
      final resp = await _api.createRecord(content, intent: 'question', cardId: cardId);
      if (!mounted) return;
      final aiTime = TimeOfDay.now();
      final aiTimeStr = '${aiTime.hour.toString().padLeft(2, '0')}:${aiTime.minute.toString().padLeft(2, '0')}';
      setState(() {
        _deactivateOtherCards(cardId);
        _updateCard(cardId, (c) => c.copyWith(mode: CardMode.chatting, loading: false,
            turns: [
              ConversationTurn(isUser: true, text: content, time: timeStr),
              if (resp.rawResponse != null || resp.summary != null)
                ConversationTurn(isUser: false, text: resp.rawResponse ?? resp.summary!, time: aiTimeStr),
            ],
            tags: resp.tags,
            domain: resp.domain,
        ));
      });
      _scrollToBottom();
    } catch (e) {
      if (mounted) {
        setState(() => _updateCard(cardId, (c) => c.copyWith(mode: CardMode.idle, loading: false)));
        _showError(_extractApiError(e));
      }
    }
  }

  void _closeChat(String cardId) async {
    final card = _cards.firstWhere((c) => c.id == cardId);
    final currentTurns = card.turns?.length ?? 0;
    final hasNewTurns = currentTurns > _chatEnterTurnCount;

    if (!hasNewTurns) {
      setState(() {
        _activeCardId = null;
        _hasActiveChat = false;
        if (card.turns != null && card.turns!.isNotEmpty) {
          _updateCard(cardId, (c) => c.copyWith(intent: IntentType.question));
        }
      });
      _scrollToBottom();
      return;
    }

    // Close active view, show card in feed with loading spinner at domain badge
    setState(() {
      _activeCardId = null;
      _hasActiveChat = false;
      _updateCard(cardId, (c) => c.copyWith(loading: true, mode: CardMode.idle, expanded: false));
    });

    try {
      final turns = card.turns?.map((t) => t.text).toList() ?? [];
      final resp = await _api.endConversation(turns, cardId: cardId);
      if (!mounted) return;
      setState(() {
        _updateCard(cardId, (c) => c.copyWith(
            summary: resp.summary, tags: resp.tags,
            loading: false, mode: CardMode.ended,
            intent: IntentType.question));
      });
      _scrollToBottom();
    } catch (e) {
      if (mounted) {
        _showError('生成总结失败: ${_extractApiError(e)}');
        setState(() {
          _updateCard(cardId, (c) => c.copyWith(
              loading: false, mode: CardMode.idle));
        });
      }
      _scrollToBottom();
    }
  }

  void _onSend(String text) async {
    final now = TimeOfDay.now();
    final timeStr = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';
    if (_activeCardId != null) {
      setState(() => _hasActiveChat = false);
      _appendToActiveCard(text, timeStr);
      return;
    }
    setState(() => _hasActiveChat = false);
    _createNewCard(text, timeStr, null);
  }

  void _createNewCard(String text, String timeStr, String? forcedIntent) async {
    final cardId = 'card_${DateTime.now().millisecondsSinceEpoch}';
    setState(() => _cards.add(FeedCardData(
      id: cardId, type: FeedCardType.record, time: timeStr, content: text, mode: CardMode.idle, loading: true,
    )));
    _scrollToBottom();

    try {
      final resp = await _api.createRecord(text, intent: forcedIntent, cardId: cardId);
      if (!mounted) return;
      if (IntentType.parse(resp.intent) == IntentType.question) {
        final aiTime = TimeOfDay.now();
        final aiTimeStr = '${aiTime.hour.toString().padLeft(2, '0')}:${aiTime.minute.toString().padLeft(2, '0')}';
        setState(() {
          _activeCardId = cardId;
          _deactivateOtherCards(cardId);
          _updateCard(cardId, (c) => c.copyWith(mode: CardMode.chatting, loading: false, intent: IntentType.question,
            turns: [ConversationTurn(isUser: true, text: text, time: timeStr),
              if (resp.rawResponse != null || resp.summary != null) ConversationTurn(isUser: false, text: resp.rawResponse ?? resp.summary!, time: aiTimeStr)],
            domain: resp.domain,
          ));
        });
      } else {
        setState(() {
          _updateCard(cardId, (c) => c.copyWith(summary: resp.summary ?? 'recorded', tags: resp.tags, loading: false, mode: CardMode.idle, intent: IntentType.log, domain: resp.domain));
        });
      }
    } catch (e) {
        if (mounted) {
          setState(() => _updateCard(cardId, (c) => c.copyWith(
              loading: false, error: _extractApiError(e))));
          _scrollToBottom();
        }
      }
  }

  /// 重试发送失败的新卡片：删除旧卡片，重新创建。
  void _onRetryCard(String cardId) {
    final idx = _cards.indexWhere((c) => c.id == cardId);
    if (idx < 0) return;
    final card = _cards[idx];
    setState(() => _cards.removeAt(idx));
    final now = TimeOfDay.now();
    final timeStr = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';
    _createNewCard(card.content, timeStr, null);
  }

  void _appendToActiveCard(String text, String timeStr) async {
    setState(() {
      _updateCard(_activeCardId!, (c) {
        final existing = c.turns ?? [];
        return c.copyWith(mode: CardMode.chatting, loading: true, turns: existing.isEmpty
            ? [ConversationTurn(isUser: true, text: c.content, time: c.time), ConversationTurn(isUser: true, text: text, time: timeStr)]
            : [...existing, ConversationTurn(isUser: true, text: text, time: timeStr)]);
      });
    });
    _scrollToBottom();
    try {
      final resp = await _api.createRecord(text, cardId: _activeCardId);
      if (!mounted) return;
      final aiTime = TimeOfDay.now();
      final aiTimeStr = '${aiTime.hour.toString().padLeft(2, '0')}:${aiTime.minute.toString().padLeft(2, '0')}';
      final aiReply = resp.rawResponse ?? resp.summary;
      if (aiReply != null) {
        setState(() {
          _updateCard(_activeCardId!, (c) {
            final existing = c.turns ?? [];
            return c.copyWith(mode: CardMode.chatting, loading: false, intent: IntentType.question,
              turns: [...existing, ConversationTurn(isUser: false, text: aiReply, time: aiTimeStr)]);
          });
        });
        _scrollToBottom();
      }
    } catch (e) { if (mounted) { setState(() => _updateCard(_activeCardId!, (c) => c.copyWith(loading: false))); _showError(_extractApiError(e)); } }
  }

  void _updateCard(String id, FeedCardData Function(FeedCardData) updater) {
    final idx = _cards.indexWhere((c) => c.id == id);
    if (idx >= 0) {
      _cards[idx] = updater(_cards[idx]);
    }
  }

  void _deleteCard(String id) async {
    try {
      await _api.deleteRecord(id);
      if (!mounted) return;
      setState(() {
        _cards.removeWhere((c) => c.id == id);
      });
    } catch (e) {
      if (mounted) _showError('删除失败');
    }
  }

  void _changeDomain(String id, String domain) async {
    setState(() {
      final idx = _cards.indexWhere((c) => c.id == id);
      if (idx >= 0) {
        _cards[idx] = _cards[idx].copyWith(domain: domain);
      }
    });
    try {
      await _api.updateRecordDomain(id, domain);
    } catch (e) {
      if (mounted) _showError('更新 OS 标记失败');
    }
  }

  void _deactivateOtherCards(String keepId) {
    for (int i = 0; i < _cards.length; i++) {
      if (_cards[i].id != keepId && (_cards[i].mode == CardMode.waiting || _cards[i].mode == CardMode.chatting)) {
        _cards[i] = _cards[i].copyWith(mode: CardMode.idle);
      }
    }
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2, behavior: SnackBarBehavior.floating,
      margin: const EdgeInsets.fromLTRB(20, 0, 20, 12), padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)), duration: const Duration(seconds: 3),
    ));
  }

  /// 从 API 异常中提取人类可读的错误消息。
  /// API service 抛出的格式：Exception: API 错误 {status}: {body}
  String _extractApiError(dynamic e) {
    final str = e.toString();
    if (str.contains('API 错误')) {
      // 提取状态码和 body 中的 error 字段
      final codeMatch = RegExp(r'API 错误 (\d+)').firstMatch(str);
      final code = codeMatch?.group(1) ?? '?';
      // 尝试从 JSON body 提取 error 字段
      try {
        final jsonStr = str.split(': ').skip(1).join(': ');
        final json = jsonDecode(jsonStr);
        if (json is Map && json['error'] != null) {
          return '请求失败 ($code): ${json['error']}';
        }
      } catch (_) {}
      return '请求失败 ($code)';
    }
    if (str.contains('TimeoutException') || str.contains('timed out')) {
      return '请求超时，请检查网络';
    }
    if (str.contains('Connection refused') || str.contains('SocketException')) {
      return '无法连接服务器，请确认后端已启动';
    }
    return 'network error';
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) {
        WidgetsBinding.instance.addPostFrameCallback((__) { if (_scrollController.hasClients) _doScroll(); });
        return;
      }
      _doScroll();
    });
  }

  void _doScroll() {
    if (_scrollController.hasClients) {
      _scrollController.animateTo(0, duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
    }
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final pos = _scrollController.position.pixels;
    final max = _scrollController.position.maxScrollExtent;
    // reverse: true → pos=0 是视觉最底部（最新内容）
    final atBottom = pos <= 20;
    final atTop = pos >= max - 20;
    if (atTop != _scrollAtTop || atBottom != _scrollAtBottom) {
      setState(() {
        _scrollAtTop = atTop;
        _scrollAtBottom = atBottom;
      });
    }
  }

  Future<void> _loadMore() async {
    if (_cards.length >= _totalToday) return;
    setState(() => _loadingMore = true);
    _currentPage++;
    try {
      final feed = await _api.getFeed(page: _currentPage, size: _pageSize);
      if (!mounted) return;
      final moreCards = feed.entries
          .where((e) => e.type != FeedEntryType.aiNote)
          .map((e) => e.toFeedData())
          .toList();
      setState(() {
        _cards = [...moreCards, ..._cards]; // 更早的条目插在前面，reverse 后出现在视觉顶部
        _loadingMore = false;
      });
    } catch (e) {
      if (!mounted) return;
      _showError('加载更多失败');
      setState(() => _loadingMore = false);
      _currentPage--;
    }
  }

  @override
  Widget build(BuildContext context) {
    final activeCard = _activeCardId != null ? _cards.where((c) => c.id == _activeCardId).firstOrNull : null;
    final hasMore = _cards.length < _totalToday;
    return SafeArea(
      child: Column(
        children: [
          GestureDetector(
            onVerticalDragEnd: (d) {
              if (d.primaryVelocity != null && d.primaryVelocity! > 200) {
                widget.onPullUp?.call();
              }
            },
            child: _TopBar(isActive: _activeCardId != null, onTimelineTap: () => TimelineModal.show(context, api: _api), onProfileTap: widget.onProfileTap),
          ),
          if (widget.filterTag != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
              child: GestureDetector(
                onTap: widget.onClearFilter,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: AppColors.darkGreen.withAlpha(20),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: AppColors.darkGreen.withAlpha(60)),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text('🏷️ ${widget.filterTag}', style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
                      const SizedBox(width: 6),
                      Icon(Icons.close, size: 14, color: AppColors.darkGreen.withAlpha(150)),
                    ],
                  ),
                ),
              ),
            ),
          if (_activeCardId == null && _brief.isNotEmpty && !_loading) _buildBriefCard(),
          if (_activeCardId == null && !_loading) _buildMoreBanner(hasMore: hasMore),
          Expanded(
            child: _loading ? const Center(child: CircularProgressIndicator())
                : _activeCardId == null ? _buildNormalLayout() : _buildActiveLayout(activeCard!),
          ),
          if (!_scrollAtBottom) _buildLatestBar(),
          if (_scrollAtBottom && _cards.isNotEmpty) _buildLastRecordBar(),
          GestureDetector(
            onVerticalDragEnd: (d) {
              if (d.primaryVelocity != null && d.primaryVelocity! < -200) {
                widget.onPullUp?.call();
              }
            },
            child: InputBar(key: _inputBarKey, onSend: _onSend, hasActiveChat: _hasActiveChat),
          ),
        ],
      ),
    );
  }

  Widget _buildNormalLayout() {
    return FadeTransition(
      opacity: _contentAnim,
      child: SlideTransition(
        position: Tween<Offset>(begin: const Offset(0, 0.04), end: Offset.zero).animate(_contentAnim),
        child: _cards.isEmpty ? _buildEmptyState() : _buildFeedList(_cards),
      ),
    );
  }

  Widget _buildFeedList(List<FeedCardData> cards) {
    return RefreshIndicator(
      color: AppColors.darkGreen,
      onRefresh: () async {
        await _refreshFeed();
      },
      child: ListView(
        reverse: true,
        controller: _scrollController,
        padding: const EdgeInsets.only(top: 0, bottom: 12),
        children: [
          ...cards.reversed.toList().asMap().entries.map((entry) {
            final card = entry.value;
            return TweenAnimationBuilder<double>(
              key: ValueKey('card_${card.id}'),
              tween: Tween(begin: 0.0, end: 1.0),
              duration: const Duration(milliseconds: 400),
              curve: Curves.easeOutCubic,
              builder: (context, value, child) => Opacity(
                opacity: value,
                child: Transform.translate(
                  offset: Offset(0, 20 * (1 - value)),
                  child: child,
                ),
              ),
              child: FeedCard(
                key: ValueKey(card.id),
                data: card,
                onActivate: () => _onCardActivate(card.id),
                onAsk: () => _onAskCard(card.id),
                onEnd: null,
                onDelete: () => _deleteCard(card.id),
                onToggleExpand: () {
                  final idx = _cards.indexWhere((c) => c.id == card.id);
                  if (idx >= 0) {
                    setState(() => _cards[idx] = _cards[idx].copyWith(expanded: !_cards[idx].expanded));
                  }
                },
                onDomainChanged: (domain) => _changeDomain(card.id, domain),
                onRetry: card.error != null ? () => _onRetryCard(card.id) : null,
              ),
            );
          }),
        ],
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('✦ ✦ ✦', style: TextStyle(fontSize: 24, color: AppColors.darkGrey6)),
            const SizedBox(height: 20),
            Text('还没有记录', style: TextStyle(fontSize: 18, color: AppColors.darkGrey4, fontWeight: FontWeight.w500)),
            const SizedBox(height: 8),
            Text('在下方输入你的第一条记录\n或语音、或文字，随你', textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14, color: AppColors.darkGrey6, height: 1.6)),
            const SizedBox(height: 32),
            Row(mainAxisSize: MainAxisSize.min, children: [
              _emptyChip('📝 记录心情', () => _inputBarKey.currentState?.prefillText('今天心情')),
              const SizedBox(width: 12),
              _emptyChip('🤔 问个问题', () => _inputBarKey.currentState?.prefillText('')),
            ]),
          ],
        ),
      ),
    );
  }

  Widget _emptyChip(String label, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          border: Border.all(color: AppColors.darkBorder.withAlpha(150)),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(label, style: TextStyle(fontSize: 13, color: AppColors.darkGrey3)),
      ),
    );
  }

  Widget _buildMoreBanner({required bool hasMore}) {
    final showLoadMore = _scrollAtTop && hasMore;
    final showTop = !_scrollAtTop;

    if (!showLoadMore && !showTop) return const SizedBox.shrink();

    final label = showLoadMore ? 'load more' : '↑ top';
    final color = showLoadMore ? AppColors.darkGrey4 : AppColors.darkGreen;

    return GestureDetector(
      onTap: showLoadMore
          ? (_loadingMore ? null : _loadMore)
          : () {
              _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
            },
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 6),
        margin: const EdgeInsets.symmetric(horizontal: 20),
        child: _loadingMore
            ? Center(child: Row(mainAxisSize: MainAxisSize.min, children: [
                SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGrey4)),
                const SizedBox(width: 6),
                Text('loading...', style: TextStyle(fontSize: 10, color: AppColors.darkGrey4)),
              ]))
            : Row(children: [
                Expanded(child: Container(
                  height: 1,
                  decoration: BoxDecoration(gradient: LinearGradient(
                    begin: Alignment.centerLeft, end: Alignment.centerRight,
                    colors: [Colors.transparent, color.withAlpha(150)]),
                  ),
                )),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  child: Text(label,
                    style: TextStyle(fontSize: 10, fontWeight: FontWeight.w500, color: color, letterSpacing: 0.5)),
                ),
                Expanded(child: Container(
                  height: 1,
                  decoration: BoxDecoration(gradient: LinearGradient(
                    begin: Alignment.centerLeft, end: Alignment.centerRight,
                    colors: [color.withAlpha(150), Colors.transparent]),
                  ),
                )),
              ]),
      ),
    );
  }

  Widget _buildLatestBar() {
    return GestureDetector(
      onTap: () {
        _scrollController.animateTo(0,
          duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
      },
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 6),
        margin: const EdgeInsets.symmetric(horizontal: 20),
        child: Row(children: [
          Expanded(child: Container(
            height: 1,
            decoration: BoxDecoration(gradient: LinearGradient(
              begin: Alignment.centerLeft, end: Alignment.centerRight,
              colors: [Colors.transparent, AppColors.darkGreen.withAlpha(150)]),
            ),
          )),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: Text('↓ latest',
              style: TextStyle(fontSize: 10, fontWeight: FontWeight.w500, color: AppColors.darkGreen, letterSpacing: 0.5)),
          ),
          Expanded(child: Container(
            height: 1,
            decoration: BoxDecoration(gradient: LinearGradient(
              begin: Alignment.centerLeft, end: Alignment.centerRight,
              colors: [AppColors.darkGreen.withAlpha(150), Colors.transparent]),
            ),
          )),
        ]),
      ),
    );
  }

  Widget _buildLastRecordBar() {
    if (_cards.isEmpty) return const SizedBox.shrink();
    final newest = _cards.last;
    final minutesAgo = DateTime.now().difference(newest.updatedAt).inMinutes;
    final label = minutesAgo < 1 ? 'just now'
        : minutesAgo < 60 ? '$minutesAgo min ago'
        : newest.time;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 4),
      margin: const EdgeInsets.symmetric(horizontal: 20),
      child: Row(children: [
        Expanded(child: Container(height: 1, color: AppColors.darkBorder.withAlpha(50))),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Text(label, style: TextStyle(fontSize: 9, color: AppColors.darkGrey6)),
        ),
        Expanded(child: Container(height: 1, color: AppColors.darkBorder.withAlpha(50))),
      ]),
    );
  }

  Widget _buildBriefCard() {
    final lines = _brief.split('\n').where((l) => l.trim().isNotEmpty).toList();
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 8),
      child: Container(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 20),
        decoration: BoxDecoration(
          gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [AppColors.darkSurface, AppColors.darkSurface2.withAlpha(153)]),
          borderRadius: BorderRadius.circular(20),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('今天', style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGrey3)),
            const SizedBox(height: 14),
            ...lines.asMap().entries.map((entry) {
              final i = entry.key; final line = entry.value.trim();
              if (line.isEmpty) return const SizedBox.shrink();
              if (i == 0) {
                return Padding(padding: const EdgeInsets.only(bottom: 6), child: Text(line, style: const TextStyle(fontSize: 16, height: 1.75, fontWeight: FontWeight.w400, color: AppColors.darkGrey1)));
              }
              return Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('•  ', style: TextStyle(fontSize: 16, color: AppColors.darkGreen.withAlpha(150))),
                  Expanded(child: Text(line, style: const TextStyle(fontSize: 14, height: 1.6, fontWeight: FontWeight.w400, color: AppColors.darkGrey1))),
                ]),
              );
            }),
          ],
        ),
      ),
    );
  }

  Widget _buildActiveLayout(FeedCardData activeCard) {
    return GestureDetector(
      onDoubleTap: () => _onCardActivate(activeCard.id),
      child: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [AppColors.darkSurface, AppColors.darkBg]),
        ),
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 4),
              child: Row(
                children: [
                  Container(width: 3, height: 14, decoration: BoxDecoration(color: AppColors.darkGreen, borderRadius: BorderRadius.circular(2))),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                    decoration: BoxDecoration(color: AppColors.darkGreen.withAlpha(50), borderRadius: BorderRadius.circular(4)),
                    child: Text('chat', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
                  ),
                  const Spacer(),
                  GestureDetector(
                    onTap: () => _onCardActivate(activeCard.id),
                    child: Container(width: 28, height: 28, decoration: BoxDecoration(color: AppColors.darkSurface2, borderRadius: BorderRadius.circular(8)),
                      child: Icon(Icons.close, size: 14, color: AppColors.darkGrey4)),
                  ),
                ],
              ),
            ),
            Divider(color: AppColors.darkBorder.withAlpha(50), height: 1),
            Expanded(
              child: ListView(
                reverse: true,
                controller: _scrollController,
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
                children: [
                  if (activeCard.loading)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Row(children: [
                        Container(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          decoration: BoxDecoration(color: AppColors.darkSurface2, borderRadius: BorderRadius.circular(16)),
                          child: SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen.withAlpha(150)))),
                      ]),
                    ),
                  if (activeCard.turns != null && activeCard.turns!.isNotEmpty)
                    ...activeCard.turns!.reversed.map((turn) => Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: _buildChatBubble(turn.text, turn.isUser, turn.time),
                    ))
                  else if (activeCard.content.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: _buildChatBubble(activeCard.content, true, activeCard.time),
                    ),
                ],
              ),
            ),
            if (activeCard.content.isNotEmpty)
              GestureDetector(
                onTap: () => _closeChat(activeCard.id),
                child: Container(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  child: Center(
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 6),
                      decoration: BoxDecoration(border: Border.all(color: AppColors.darkGreen.withAlpha(100)), borderRadius: BorderRadius.circular(20)),
                      child: Text('end conversation', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGreen, letterSpacing: 0.5)),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }


  Widget _buildChatBubble(String text, bool isUser, String time) {
    final displayText = isUser ? text : TextCleaner.stripDomainJson(text);
    return Column(
      crossAxisAlignment: isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: isUser ? AppColors.darkGreen.withAlpha(30) : AppColors.darkSurface2,
            borderRadius: BorderRadius.only(
              topLeft: Radius.circular(isUser ? 16 : 4), topRight: Radius.circular(isUser ? 4 : 16),
              bottomLeft: const Radius.circular(16), bottomRight: const Radius.circular(16),
            ),
          ),
          constraints: const BoxConstraints(maxWidth: 320),
          child: isUser
              ? Text(displayText, style: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGrey1))
              : MarkdownBody(
                  data: displayText,
                  selectable: true,
                  styleSheet: MarkdownStyleSheet.fromTheme(ThemeData(
                    textTheme: const TextTheme(bodyMedium: TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGrey1)),
                  )).copyWith(
                    strong: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w700),
                    h1: const TextStyle(fontSize: 16, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w700),
                    h2: const TextStyle(fontSize: 15, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w600),
                    h3: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w600),
                    h4: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w600),
                    code: TextStyle(fontSize: 13, color: AppColors.darkGreen, backgroundColor: const Color(0xFF2A2826)),
                    codeblockDecoration: BoxDecoration(
                      color: const Color(0xFF2A2826),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: AppColors.darkGreen.withAlpha(50)),
                    ),
                    p: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGrey1),
                    a: const TextStyle(fontSize: 14, color: AppColors.darkBlue),
                  )),
        ),
        const SizedBox(height: 4),
        Text(time, style: TextStyle(fontSize: 9, color: AppColors.darkGrey6)),
      ],
    );
  }
}

class _TopBar extends StatelessWidget {
  final bool isActive;
  final VoidCallback onTimelineTap;
  final VoidCallback? onProfileTap;
  const _TopBar({required this.isActive, required this.onTimelineTap, this.onProfileTap});

  String get _dateLabel {
    final now = DateTime.now();
    const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    return '${now.month}/${now.day}·${weekdays[now.weekday - 1]}';
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
      child: Row(
        children: [
          GestureDetector(
            onTap: onTimelineTap,
            child: Row(
              children: [
                Text(_dateLabel, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: AppColors.darkGrey5, letterSpacing: 0.3)),
                const SizedBox(width: 4),
                Icon(Icons.keyboard_arrow_down, size: 14, color: AppColors.darkGrey5.withAlpha(128)),
              ],
            ),
          ),
          if (isActive) ...[
            const SizedBox(width: 8),
            Container(width: 5, height: 5, decoration: BoxDecoration(color: AppColors.darkGreen, shape: BoxShape.circle)),
          ],
          const Spacer(),
          if (onProfileTap != null)
            GestureDetector(
              onTap: onProfileTap,
              child: const Padding(
                padding: EdgeInsets.only(right: 4),
                child: Icon(Icons.person_outline, size: 20, color: AppColors.darkGrey5),
              ),
            ),
        ],
      ),
    );
  }
}

extension FeedEntryResponseX on FeedEntryResponse {
  FeedCardData toFeedData() {
    List<ConversationTurn>? cardTurns;
    if (turns != null && turns!.isNotEmpty) {
      cardTurns = turns!.map((t) => ConversationTurn(
        isUser: t['isUser'] as bool? ?? true,
        text: t['text'] as String? ?? '',
        time: t['time'] as String? ?? '',
      )).toList();
    }
    return FeedCardData(
      id: id, type: FeedCardType.record, time: time, content: content,
      tags: tags.isNotEmpty ? tags : null, mode: CardMode.idle, intent: IntentType.parse(intent),
      summary: summary, turns: cardTurns, domain: domain,
    );
  }
}
