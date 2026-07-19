import 'package:flutter/material.dart';
import 'package:characters/characters.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'widgets/feed_card.dart';
import 'widgets/input_bar.dart';
import 'widgets/timeline_modal.dart';

class MainPage extends StatefulWidget {
  const MainPage({super.key});

  @override
  State<MainPage> createState() => _MainPageState();
}

class _MainPageState extends State<MainPage>
    with SingleTickerProviderStateMixin {
  final ScrollController _scrollController = ScrollController();
  final ApiService _api = ApiService();

  List<FeedCardData> _cards = [];
  int _earlierCount = 0;
  String _brief = '';
  bool _loading = true;
  int _totalShown = 0;
  static const int _pageSize = 5;

  String? _activeCardId;
  bool _isAskMode = false;
  int _chatEnterTurnCount = 0;

  late AnimationController _enterCtrl;
  late Animation<double> _contentAnim;

  @override
  void initState() {
    super.initState();
    _loadFeed();
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

  Future<void> _loadFeed({String? date}) async {
    try {
      final feed = await _api.getFeed(date: date);
      setState(() {
        _brief = feed.brief;
        _earlierCount = feed.earlierCount;
        _cards = feed.entries
            .where((e) => e.type != 'ai_note')
            .map((e) => e.toFeedData())
            .toList();
        _loading = false;
        _totalShown = _pageSize;
      });
      _scrollToBottom();
    } catch (e) {
      setState(() => _loading = false);
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
      _isAskMode = true;
      _chatEnterTurnCount = card?.turns?.length ?? 0;
    });
    _scrollToBottom();
  }

  void _closeChat(String cardId) async {
    final card = _cards.firstWhere((c) => c.id == cardId);
    final currentTurns = card.turns?.length ?? 0;
    final hasNewTurns = currentTurns > _chatEnterTurnCount;

    if (!hasNewTurns) {
      setState(() {
        _activeCardId = null;
        _isAskMode = false;
        if (card.turns != null && card.turns!.isNotEmpty) {
          _updateCard(cardId, (c) => c.copyWith(intent: 'question'));
        }
      });
      _scrollToBottom();
      return;
    }

    _deactivateOtherCards(cardId);
    setState(() { _activeCardId = null; _isAskMode = false; });

    try {
      final turns = card.turns?.map((t) => t.text).toList() ?? [];
      final resp = await _api.endConversation(turns, cardId: cardId);
      if (!mounted) return;
      setState(() {
        _updateCard(cardId, (c) => c.copyWith(summary: resp.summary, tags: resp.tags, mode: CardMode.idle, intent: 'question'));
      });
      _scrollToBottom();
    } catch (_) {
      if (mounted) _showError('summary failed, saved locally');
      setState(() {
        _updateCard(cardId, (c) => c.copyWith(summary: 'conversation ended', tags: ['conversation'], mode: CardMode.idle, intent: 'question'));
      });
      _scrollToBottom();
    }
  }

  void _onSend(String text) async {
    final now = TimeOfDay.now();
    final timeStr = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';
    if (_activeCardId != null) {
      setState(() => _isAskMode = false);
      _appendToActiveCard(text, timeStr);
      return;
    }
    setState(() => _isAskMode = false);
    _createNewCard(text, timeStr, null);
  }

  void _createNewCard(String text, String timeStr, String? forcedIntent) async {
    final cardId = DateTime.now().millisecondsSinceEpoch.toString();
    setState(() => _cards.add(FeedCardData(
      id: cardId, type: FeedCardType.userEntry, time: timeStr, content: text, mode: CardMode.idle,
    )));
    _scrollToBottom();

    try {
      final resp = await _api.createRecord(text, intent: forcedIntent, cardId: cardId);
      if (!mounted) return;
      if (resp.intent == 'question') {
        final aiTime = TimeOfDay.now();
        final aiTimeStr = '${aiTime.hour.toString().padLeft(2, '0')}:${aiTime.minute.toString().padLeft(2, '0')}';
        setState(() {
          _activeCardId = cardId;
          _deactivateOtherCards(cardId);
          _updateCard(cardId, (c) => c.copyWith(mode: CardMode.chatting, loading: false, intent: 'question',
            turns: [ConversationTurn(isUser: true, text: text, time: timeStr),
              if (resp.summary != null) ConversationTurn(isUser: false, text: resp.summary!, time: aiTimeStr)],
          ));
        });
      } else {
        setState(() {
          _updateCard(cardId, (c) => c.copyWith(summary: resp.summary ?? 'recorded', tags: resp.tags, mode: CardMode.idle, intent: 'log'));
        });
      }
    } catch (_) { if (mounted) _showError('saved locally, waiting for network'); }
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
      if (resp.summary != null) {
        setState(() {
          _updateCard(_activeCardId!, (c) {
            final existing = c.turns ?? [];
            return c.copyWith(mode: CardMode.chatting, loading: false, intent: 'question',
              turns: [...existing, ConversationTurn(isUser: false, text: resp.summary!, time: aiTimeStr)]);
          });
        });
        _scrollToBottom();
      }
    } catch (_) { if (mounted) { setState(() => _updateCard(_activeCardId!, (c) => c.copyWith(loading: false))); _showError('network error, please try again'); } }
  }

  void _updateCard(String id, FeedCardData Function(FeedCardData) updater) {
    final idx = _cards.indexWhere((c) => c.id == id);
    if (idx >= 0) {
      _cards[idx] = updater(_cards[idx]);
      final now = TimeOfDay.now();
      _cards[idx] = _cards[idx].copyWith(time: '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}');
    }
    _cards.sort((a, b) => a.updatedAt.compareTo(b.updatedAt));
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
    if (_activeCardId != null && _scrollController.hasClients) {
      _scrollController.animateTo(0, duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
    } else if (_scrollController.hasClients) {
      _scrollController.animateTo(_scrollController.position.maxScrollExtent, duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
    }
  }

  void _loadMore() {
    if (_totalShown < _cards.length) {
      setState(() => _totalShown = (_totalShown + _pageSize).clamp(0, _cards.length));
    } else if (_earlierCount > 0) {
      _loadOlder();
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) _scrollController.animateTo(0, duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
    });
  }

  Future<void> _loadOlder() async {
    try {
      final today = DateTime.now();
      final prevDate = today.subtract(const Duration(days: 1));
      final dateStr = '${prevDate.year}-${prevDate.month.toString().padLeft(2, '0')}-${prevDate.day.toString().padLeft(2, '0')}';
      final feed = await _api.getFeed(date: dateStr);
      if (!mounted) return;
      setState(() {
        _cards.insertAll(0, feed.entries.where((e) => e.type != 'ai_note').map((e) => e.toFeedData()).toList());
        _earlierCount = feed.earlierCount;
        _totalShown = _cards.length;
      });
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final activeCard = _activeCardId != null ? _cards.where((c) => c.id == _activeCardId).firstOrNull : null;
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(
          children: [
            _TopBar(isActive: _activeCardId != null, onTimelineTap: () => TimelineModal.show(context, api: _api)),
            if (_activeCardId == null && _brief.isNotEmpty && !_loading) _buildBriefCard(),
            Expanded(
              child: _loading ? const Center(child: CircularProgressIndicator())
                  : _activeCardId == null ? _buildNormalLayout() : _buildActiveLayout(activeCard!),
            ),
            InputBar(onSend: _onSend, isAskMode: _isAskMode),
          ],
        ),
      ),
    );
  }

  Widget _buildNormalLayout() {
    final endIdx = _cards.length;
    final startIdx = (endIdx - _totalShown).clamp(0, endIdx);
    final visibleCards = _cards.sublist(startIdx, endIdx);
    final hasMore = startIdx > 0 || _earlierCount > 0;

    return FadeTransition(
      opacity: _contentAnim,
      child: SlideTransition(
        position: Tween<Offset>(begin: const Offset(0, 0.04), end: Offset.zero).animate(_contentAnim),
        child: ListView(
          controller: _scrollController,
          padding: const EdgeInsets.only(top: 20, bottom: 12),
          children: [
            if (hasMore) _buildMoreBanner(),
            ...visibleCards.map((card) => FeedCard(
              key: ValueKey(card.id),
              data: card.copyWith(mode: CardMode.idle),
              onActivate: () => _onCardActivate(card.id),
              onAsk: () => _onCardActivate(card.id),
              onEnd: null,
            )),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  Widget _buildMoreBanner() {
    return GestureDetector(
      onTap: _loadMore,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 20, vertical: 6),
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(color: AppColors.darkSurface2.withAlpha(128), borderRadius: BorderRadius.circular(12)),
        child: Center(child: Text('load more', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4))),
      ),
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
            Text('today', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w600, color: AppColors.darkGrey4, letterSpacing: 1.5)),
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
                  Text(_emojiForLine(line) + '  ', style: const TextStyle(fontSize: 16)),
                  Expanded(child: Text(_stripEmoji(line), style: const TextStyle(fontSize: 14, height: 1.6, fontWeight: FontWeight.w400, color: AppColors.darkGrey1))),
                ]),
              );
            }),
          ],
        ),
      ),
    );
  }

  String _emojiForLine(String line) {
    final trimmed = line.trim();
    if (trimmed.isEmpty) return '•';
    return trimmed.characters.first;
  }

  String _stripEmoji(String line) {
    final trimmed = line.trim();
    if (trimmed.isEmpty) return '';
    final first = trimmed.characters.first;
    if (first.codeUnitAt(0) > 0x2000) return trimmed.substring(first.length).trim();
    return trimmed;
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
                      padding: const EdgeInsets.only(bottom: 12),
                      child: _buildChatBubble(turn.text, turn.isUser, turn.time),
                    ))
                  else if (activeCard.content.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
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
          constraints: BoxConstraints(maxWidth: 320),
          child: Text(text, style: TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGrey1)),
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
  const _TopBar({required this.isActive, required this.onTimelineTap});

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
                Text(_dateLabel, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: AppColors.darkGrey5, letterSpacing: 0.3)),
                const SizedBox(width: 4),
                Icon(Icons.keyboard_arrow_down, size: 14, color: AppColors.darkGrey5.withValues(alpha: 0.5)),
              ],
            ),
          ),
          if (isActive) ...[
            const SizedBox(width: 8),
            Container(width: 5, height: 5, decoration: BoxDecoration(color: AppColors.darkGreen, shape: BoxShape.circle)),
          ],
          const Spacer(),
        ],
      ),
    );
  }
}

extension FeedEntryResponseX on FeedEntryResponse {
  FeedCardData toFeedData() {
    return FeedCardData(
      id: id, type: FeedCardType.userEntry, time: time, content: content,
      tags: tags.isNotEmpty ? tags : null, mode: CardMode.idle, intent: intent,
      summary: summary,
    );
  }
}
