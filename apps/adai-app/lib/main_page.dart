import 'dart:convert';
import 'package:flutter/material.dart';
import 'theme/app_colors.dart';
import 'services/api_service.dart';
import 'widgets/feed_card.dart';
import 'widgets/input_bar.dart';
import 'widgets/timeline_modal.dart';

/// Single page for ADAI.
/// Card-based conversation: each user input creates or mutates a card.
/// Card lifecycle: idle (record/summary) -> [ask] -> waiting -> chatting -> [end] -> idle
class MainPage extends StatefulWidget {
  const MainPage({super.key});

  @override
  State<MainPage> createState() => _MainPageState();
}

class _MainPageState extends State<MainPage>
    with SingleTickerProviderStateMixin {
  final ScrollController _scrollController = ScrollController();
  final ApiService _api = ApiService();
  final DateTime _openTime = DateTime.now();

  List<FeedCardData> _cards = [];
  int _earlierCount = 0;
  String _brief = '';
  bool _loading = true;

  // Active card tracking
  String? _activeCardId;
  bool _isAskMode = false;

  late AnimationController _enterCtrl;
  late Animation<double> _contentAnim;

  @override
  void initState() {
    super.initState();
    _loadFeed();

    _enterCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 600),
    );
    _contentAnim = CurvedAnimation(
      parent: _enterCtrl,
      curve: Curves.easeOutCubic,
    );
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
      final feed = await _api.getFeed(
        date: date,
        since: _openTime.toIso8601String(),
      );
      setState(() {
        _brief = feed.brief;
        _earlierCount = feed.earlierCount;
        _cards = feed.entries.map((e) => e.toFeedData()).toList();
        _loading = false;
      });
    } catch (e) {
      setState(() => _loading = false);
    }
  }

  // ── Send new input ──

  void _onSend(String text) async {
    final now = TimeOfDay.now();
    final timeStr =
        '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';

    // If there is an active card, append to it
    if (_activeCardId != null) {
      setState(() => _isAskMode = false);
      _appendToActiveCard(text, timeStr);
      return;
    }

    // No active card: create new card
    setState(() => _isAskMode = false);
    _createNewCard(text, timeStr, null);
  }

  // ── Create a brand new card (no active session) ──

  void _createNewCard(String text, String timeStr, String? forcedIntent) async {
    final cardId = DateTime.now().millisecondsSinceEpoch.toString();

    setState(() {
      _cards.add(FeedCardData(
        id: cardId,
        type: FeedCardType.userEntry,
        time: timeStr,
        content: text,
        mode: CardMode.idle,
      ));
    });
    _scrollToBottom();

    try {
      final resp = await _api.createRecord(text, intent: forcedIntent);
      if (!mounted) return;

      if (resp.intent == 'question') {
        final aiTime = TimeOfDay.now();
        final aiTimeStr =
            '${aiTime.hour.toString().padLeft(2, '0')}:${aiTime.minute.toString().padLeft(2, '0')}';

        setState(() {
          _activeCardId = cardId;
          _deactivateOtherCards(cardId);
          _updateCard(cardId, (c) => c.copyWith(
            mode: CardMode.chatting,
            loading: false,
            turns: [
              ConversationTurn(isUser: true, text: text, time: timeStr),
              if (resp.summary != null)
                ConversationTurn(isUser: false, text: resp.summary!, time: aiTimeStr),
            ],
          ));
        });
      } else {
        // Record: show summary + tags, but NOT the turns (single log entry)
        setState(() {
          _updateCard(cardId, (c) => c.copyWith(
            summary: resp.summary ?? 'recorded',
            tags: resp.tags,
            mode: CardMode.idle,
          ));
        });
      }
    } catch (_) {
      if (mounted) _showError('saved locally, waiting for network');
    }
  }

  // ── Append user input to active card (chatting mode) ──

  void _appendToActiveCard(String text, String timeStr) async {
    // Optimistically add user turn + show loading
    setState(() {
      _updateCard(_activeCardId!, (c) {
        final existing = c.turns ?? [];
        return c.copyWith(
          mode: CardMode.chatting,
          loading: true,
          turns: [...existing, ConversationTurn(isUser: true, text: text, time: timeStr)],
        );
      });
    });
    _scrollToBottom();

    try {
      final resp = await _api.createRecord(text, intent: 'question');
      if (!mounted) return;

      final aiTime = TimeOfDay.now();
      final aiTimeStr =
          '${aiTime.hour.toString().padLeft(2, '0')}:${aiTime.minute.toString().padLeft(2, '0')}';

      if (resp.summary != null) {
        setState(() {
          _updateCard(_activeCardId!, (c) {
            final existing = c.turns ?? [];
            return c.copyWith(
              mode: CardMode.chatting,
              loading: false,
              turns: [...existing, ConversationTurn(isUser: false, text: resp.summary!, time: aiTimeStr)],
            );
          });
        });
        _scrollToBottom();
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _updateCard(_activeCardId!, (c) => c.copyWith(loading: false));
        });
        _showError('network error, please try again');
      }
    }
  }

  // ── [ask] handler: transfer card from idle -> waiting -> chatting ──

  void _onCardAsk(String cardId) {
    if (_activeCardId != null && _activeCardId != cardId) {
      _deactivateOtherCards(cardId);
    }

    setState(() {
      _isAskMode = true;
      _activeCardId = cardId;
      final idx = _cards.indexWhere((c) => c.id == cardId);
      if (idx >= 0) {
        final card = _cards.removeAt(idx);
        _cards.add(card.copyWith(mode: CardMode.waiting, ended: false));
      }
    });
    _scrollToBottom();
  }

  // ── [end] handler: end conversation, get summary from backend ──

  void _onCardEnd(String cardId) async {
    _deactivateOtherCards(cardId);
    setState(() {
      _activeCardId = null;
      _isAskMode = false;
    });

    try {
      final card = _cards.firstWhere((c) => c.id == cardId);
      final turns = card.turns?.map((t) => t.text).toList() ?? [];
      final resp = await _api.endConversation(turns);
      if (!mounted) return;

      setState(() {
        _updateCard(cardId, (c) => c.copyWith(
          summary: resp.summary,
          tags: resp.tags,
          mode: CardMode.idle,
          ended: true,
        ));
      });
    } catch (_) {
      if (mounted) _showError('saved locally, summary may be incomplete');
      setState(() {
        _updateCard(cardId, (c) => c.copyWith(
          summary: 'conversation ended',
          tags: ['conversation'],
          mode: CardMode.idle,
          ended: true,
        ));
      });
    }
  }

  // ── Helpers ──

  void _updateCard(String id, FeedCardData Function(FeedCardData) updater) {
    final idx = _cards.indexWhere((c) => c.id == id);
    if (idx >= 0) {
      _cards[idx] = updater(_cards[idx]);
    }
  }

  void _deactivateOtherCards(String keepId) {
    // Reset any other card in waiting/chatting mode back to idle
    for (int i = 0; i < _cards.length; i++) {
      if (_cards[i].id != keepId &&
          (_cards[i].mode == CardMode.waiting || _cards[i].mode == CardMode.chatting)) {
        _cards[i] = _cards[i].copyWith(mode: CardMode.idle);
      }
    }
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      margin: const EdgeInsets.fromLTRB(20, 0, 20, 12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      duration: const Duration(seconds: 3),
    ));
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: Column(
          children: [
            _TopBar(
              isActive: _activeCardId != null,
              onTimelineTap: () => TimelineModal.show(context, api: _api),
            ),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : FadeTransition(
                      opacity: _contentAnim,
                      child: SlideTransition(
                        position: Tween<Offset>(
                          begin: const Offset(0, 0.04),
                          end: Offset.zero,
                        ).animate(_contentAnim),
                        child: ListView(
                          controller: _scrollController,
                          padding: const EdgeInsets.only(top: 20, bottom: 12),
                          children: [
                            _brief.isNotEmpty
                                ? _buildBriefCard()
                                : const SizedBox.shrink(),
                            if (_earlierCount > 0)
                              _buildEarlierBanner(),
                            ..._cards.map((card) => FeedCard(
                              key: ValueKey(card.id),
                              data: card,
                              onAsk: card.mode == CardMode.idle
                                  ? () => _onCardAsk(card.id)
                                  : null,
                              onEnd: card.mode != CardMode.idle
                                  ? () => _onCardEnd(card.id)
                                  : null,
                            )),
                            const SizedBox(height: 24),
                          ],
                        ),
                      ),
                    ),
            ),
            InputBar(onSend: _onSend, isAskMode: _isAskMode),
          ],
        ),
      ),
    );
  }

  Widget _buildBriefCard() {
    final time = _openTime.hour.toString().padLeft(2, '0') + ':' +
        _openTime.minute.toString().padLeft(2, '0');
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 8),
      child: Container(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 20),
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [AppColors.darkSurface, AppColors.darkSurface2.withAlpha(153)],
          ),
          borderRadius: BorderRadius.circular(20),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'today',
              style: TextStyle(fontSize: 9, fontWeight: FontWeight.w600,
                  color: AppColors.darkGrey4, letterSpacing: 1.5),
            ),
            const SizedBox(height: 14),
            Text(_brief,
                style: const TextStyle(fontSize: 16, height: 1.75,
                    fontWeight: FontWeight.w400, color: AppColors.darkGrey1)),
          ],
        ),
      ),
    );
  }

  Widget _buildEarlierBanner() {
    return GestureDetector(
      onTap: () => _loadFeed(),
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 20, vertical: 6),
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2.withAlpha(128),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Center(
          child: Text(
            'expand earlier ($_earlierCount)',
            style: TextStyle(fontSize: 13, color: AppColors.darkGrey4),
          ),
        ),
      ),
    );
  }
}

class _TopBar extends StatelessWidget {
  final bool isActive;
  final VoidCallback onTimelineTap;

  const _TopBar({required this.isActive, required this.onTimelineTap});

  String get _shortDate {
    final now = DateTime.now();
    return '${now.month}/${now.day}';
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 12, 0),
      child: Row(
        children: [
          Text(
            _shortDate,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w500,
              color: AppColors.darkGrey5,
              letterSpacing: 0.3,
            ),
          ),
          if (isActive) ...[
            const SizedBox(width: 8),
            Container(
              width: 5, height: 5,
              decoration: BoxDecoration(
                color: AppColors.darkGreen,
                shape: BoxShape.circle,
              ),
            ),
          ],
          const Spacer(),
          GestureDetector(
            onTap: onTimelineTap,
            child: Container(
              width: 36, height: 36,
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(
                Icons.timeline_outlined,
                size: 18,
                color: AppColors.darkGrey4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── FeedEntryResponse to FeedCardData conversion extension ──

extension FeedEntryResponseX on FeedEntryResponse {
  FeedCardData toFeedData() {
    return FeedCardData(
      id: id,
      type: FeedCardType.userEntry,
      time: time,
      content: content,
      tags: tags.isNotEmpty ? tags : null,
      mode: CardMode.idle,
    );
  }
}
