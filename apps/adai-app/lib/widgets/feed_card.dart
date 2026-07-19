import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

enum FeedCardType { userEntry, aiNote, dailyBrief, news, weather, image, link, file }

class ConversationTurn {
  final bool isUser;
  final String text;
  final String time;
  ConversationTurn({required this.isUser, required this.text, required this.time});
}

enum CardMode {
  idle,     // record card
  waiting,  // just clicked ask
  chatting, // in conversation
}

class FeedCardData {
  final String id;
  final FeedCardType type;
  final String time;
  final String content;
  final List<String>? tags;
  final String? summary;
  final List<ConversationTurn>? turns;
  final CardMode mode;
  final bool ended;
  final bool loading;
  final String? intent;
  final bool expanded;
  final DateTime updatedAt;

  FeedCardData({
    required this.id, required this.type, required this.time, required this.content,
    this.tags, this.summary, this.turns, this.mode = CardMode.idle,
    this.ended = false, this.loading = false, this.intent, this.expanded = false,
    DateTime? updatedAt,
  }) : updatedAt = updatedAt ?? DateTime.now();

  FeedCardData copyWith({
    String? id, FeedCardType? type, String? time, String? content,
    List<String>? tags, String? summary, List<ConversationTurn>? turns,
    CardMode? mode, bool? ended, bool? loading, String? intent, bool? expanded,
    DateTime? updatedAt,
  }) {
    return FeedCardData(
      id: id ?? this.id, type: type ?? this.type, time: time ?? this.time,
      content: content ?? this.content, tags: tags ?? this.tags,
      summary: summary ?? this.summary, turns: turns ?? this.turns,
      mode: mode ?? this.mode, ended: ended ?? this.ended, loading: loading ?? this.loading,
      intent: intent ?? this.intent, expanded: expanded ?? this.expanded,
      updatedAt: updatedAt ?? DateTime.now(),
    );
  }
}

// ── Three-dot loading widget ──

class _LoadingDots extends StatefulWidget {
  const _LoadingDots();
  @override
  State<_LoadingDots> createState() => _LoadingDotsState();
}
class _LoadingDotsState extends State<_LoadingDots> with SingleTickerProviderStateMixin {
  late AnimationController _ctrl;
  @override void initState() { super.initState(); _ctrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))..repeat(); }
  @override void dispose() { _ctrl.dispose(); super.dispose(); }
  @override Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (_, __) => Row(
        mainAxisSize: MainAxisSize.min,
        children: List.generate(3, (i) {
          final delay = i * 0.2;
          final opacity = ((_ctrl.value - delay) % 1.0).abs() < 0.3 ? 1.0 : 0.3;
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2),
            child: Container(
              width: 4, height: 4,
              decoration: BoxDecoration(
                color: AppColors.darkGreen.withAlpha((opacity * 178).round()),
                shape: BoxShape.circle,
              ),
            ),
          );
        }),
      ),
    );
  }
}

// ── FeedCard ──

class FeedCard extends StatelessWidget {
  final FeedCardData data;
  final VoidCallback? onAsk;
  final VoidCallback? onEnd;
  final VoidCallback? onActivate;

  const FeedCard({
    super.key, required this.data, this.onAsk, this.onEnd, this.onActivate,
  });

  bool get _isWaiting => data.mode == CardMode.waiting;
  bool get _isChatting => data.mode == CardMode.chatting;
  bool get _isActive => _isWaiting || _isChatting;
  bool get _isEnded => data.mode == CardMode.idle && data.ended;
  bool get _isIdle => data.mode == CardMode.idle && !data.ended;
  bool get _hasTurns => data.turns != null && data.turns!.isNotEmpty;
  // log: intent is 'log', or null and no turns (feed-loaded records)
  bool get _isLogStyle => !_isActive && !_isEnded
      && (data.intent == 'log' || (data.intent == null && !_hasTurns));
  // ask: anything that's not log, not active, not ended
  bool get _isAskStyle => !_isActive && !_isEnded && !_isLogStyle;

  @override
  Widget build(BuildContext context) {
    // Normal / expanded rendering (unchanged from current)
    final borderColor = _isEnded
        ? AppColors.darkGreen.withAlpha(180)
        : _isLogStyle
            ? AppColors.darkBorder.withAlpha(100)
            : AppColors.darkBorder.withAlpha(200);

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 6),
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Left accent indicator (active cards only)
            if (_isActive)
              Container(
                width: 3,
                margin: const EdgeInsets.only(right: 0),
                decoration: BoxDecoration(
                  color: AppColors.darkGreen.withAlpha(150),
                  borderRadius: const BorderRadius.only(
                    topLeft: Radius.circular(16),
                    bottomLeft: Radius.circular(16),
                  ),
                ),
              ),
            // Card body
            Expanded(
              child: Container(
                decoration: BoxDecoration(
                  border: Border(
                    top: BorderSide(color: borderColor, width: 1),
                    right: BorderSide(color: borderColor, width: 1),
                    left: _isActive ? BorderSide.none : BorderSide(color: borderColor, width: 1),
                    bottom: _isActive
                        ? BorderSide.none
                        : BorderSide(color: borderColor, width: 1),
                  ),
                  borderRadius: _isActive
                      ? const BorderRadius.only(
                          topRight: Radius.circular(16),
                          bottomRight: Radius.circular(16),
                        )
                      : BorderRadius.circular(16),
                ),
                child: ClipRRect(
                  borderRadius: _isActive
                      ? const BorderRadius.only(
                          topRight: Radius.circular(15),
                          bottomRight: Radius.circular(15),
                        )
                      : BorderRadius.circular(15),
                  child: Container(
                    color: _isActive || _isEnded
                        ? AppColors.darkSurface
                        : AppColors.darkSurface.withAlpha(200),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(16, 14, 16, 0),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildHeader(),
                              const SizedBox(height: 10),
                              if (!_hasTurns) _buildBody(),
                              if (_hasTurns) ...[
                                const SizedBox(height: 4),
                                _buildTurns(),
                              ],
                              if (data.summary != null && _isEnded) ...[
                                const SizedBox(height: 8),
                                _buildSummaryBanner(),
                              ],
                              if (_isIdle && data.summary != null) ...[
                                const SizedBox(height: 8),
                                _buildSummaryBanner(),
                              ],
                              if (data.tags != null && data.tags!.isNotEmpty && !_isActive) ...[
                                const SizedBox(height: 8),
                                _buildTags(),
                              ],
                            ],
                          ),
                        ),
                        const SizedBox(height: 6),
                        _buildBottomLine(borderColor),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Row(
      children: [
        if (_isLogStyle) ...[
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
            decoration: BoxDecoration(
              color: AppColors.darkGrey5.withAlpha(50),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text('log', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: AppColors.darkGrey5)),
          ),
          const SizedBox(width: 6),
        ],
        if (_isAskStyle) ...[
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
            decoration: BoxDecoration(
              color: AppColors.darkGreen.withAlpha(50),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text('ask', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
          ),
          const SizedBox(width: 6),
        ],
        Text(data.time, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGrey5)),
        if (_isChatting) ...[
          const SizedBox(width: 6),
          Container(width: 4, height: 4,
            decoration: BoxDecoration(color: AppColors.darkGreen, shape: BoxShape.circle),
          ),
        ],
      ],
    );
  }

  Widget _buildBody() {
    return Text(data.content, style: TextStyle(fontSize: 15, height: 1.6, color: AppColors.darkGrey1));
  }

  Widget _buildTurns() {
    final turns = data.turns!;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...turns.map((turn) => Padding(
          padding: const EdgeInsets.only(bottom: 5),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                margin: const EdgeInsets.only(top: 2, right: 8),
                child: Text(
                  turn.isUser ? 'you' : 'ai',
                  style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600,
                    color: turn.isUser ? AppColors.darkGrey5 : AppColors.darkGreen.withAlpha(178)),
                ),
              ),
              Expanded(
                child: Text(turn.text, style: TextStyle(fontSize: 14, height: 1.5,
                  fontWeight: turn.isUser ? FontWeight.w500 : FontWeight.w400,
                  color: AppColors.darkGrey1)),
              ),
            ],
          ),
        )),
        // Loading dots while waiting for AI
        if (data.loading)
          const Padding(
            padding: EdgeInsets.only(left: 28, bottom: 5),
            child: _LoadingDots(),
          ),
      ],
    );
  }

  Widget _buildSummaryBanner() {
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2.withAlpha(128),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.check_circle_rounded, size: 14, color: AppColors.darkGreen.withAlpha(178)),
          const SizedBox(width: 6),
          Expanded(
            child: Text(data.summary!,
              style: TextStyle(fontSize: 12, color: AppColors.darkGrey4, height: 1.4)),
          ),
        ],
      ),
    );
  }

  Widget _buildTags() {
    return Wrap(spacing: 4, runSpacing: 4,
      children: data.tags!.map((t) => _chip(t)).toList());
  }

  // ── Bottom line ──

  Widget _buildBottomLine(Color borderColor) {
    if (_isActive) {
      return _lineEnd(borderColor);
    }
    return _lineCentered(borderColor);
  }

  Widget _lineEnd(Color borderColor) {
    return GestureDetector(
      onTap: onEnd,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 36,
        decoration: BoxDecoration(
          border: Border(
            top: BorderSide(color: borderColor.withAlpha(128), width: 0.5),
          ),
        ),
        child: Row(
          children: [
            const Spacer(),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              child: Text(
                'end',
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w500,
                  color: AppColors.darkGreen,
                  letterSpacing: 0.5,
                ),
              ),
            ),
            const SizedBox(width: 48),
          ],
        ),
      ),
    );
  }

  Widget _lineCentered(Color borderColor) {
    final labelColor = _isEnded ? AppColors.darkGreen : AppColors.darkGrey5;

    return GestureDetector(
      onTap: onAsk,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 36,
        decoration: BoxDecoration(
          border: Border(
            top: BorderSide(color: borderColor.withAlpha(128), width: 0.5),
          ),
        ),
        child: Row(
          children: [
            Expanded(child: Container(
              height: 1,
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.centerLeft, end: Alignment.centerRight,
                  colors: [Colors.transparent, labelColor.withAlpha(100)],
                ),
              ),
            )),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text('ask', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: labelColor, letterSpacing: 0.5)),
            ),
            Expanded(child: Container(
              height: 1,
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.centerLeft, end: Alignment.centerRight,
                  colors: [labelColor.withAlpha(100), Colors.transparent],
                ),
              ),
            )),
          ],
        ),
      ),
    );
  }

  Widget _chip(String label) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: AppColors.darkBorder.withAlpha(76)),
      ),
      child: Text(label, style: TextStyle(fontSize: 10, color: AppColors.darkGrey4)),
    );
  }
}
