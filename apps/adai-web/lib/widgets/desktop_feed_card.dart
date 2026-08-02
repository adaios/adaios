import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../theme/app_colors.dart';
import '../models/feed_models.dart';
import '../utils/text_cleaner.dart';
import 'hoverable.dart';

/// 桌面 FeedCard — 4 态状态机（idle/waiting/chatting/ended）。
///
/// 桌面形态重绘：左侧时间竖列 + 主内容区，hover 提升背景，非移动端放大。
class DesktopFeedCard extends StatelessWidget {
  final FeedCardData data;
  final VoidCallback? onAsk;
  final VoidCallback? onEnd;
  final VoidCallback? onDelete;
  final VoidCallback? onToggleExpand;
  final VoidCallback? onRetry;
  final void Function(String domain)? onDomainChanged;

  const DesktopFeedCard({
    super.key,
    required this.data,
    this.onAsk,
    this.onEnd,
    this.onDelete,
    this.onToggleExpand,
    this.onRetry,
    this.onDomainChanged,
  });

  bool get _isWaiting => data.mode == CardMode.waiting;
  bool get _isChatting => data.mode == CardMode.chatting;
  bool get _isActive => _isWaiting || _isChatting;
  bool get _isEnded => data.mode == CardMode.ended;
  bool get _hasTurns => data.turns != null && data.turns!.isNotEmpty;
  bool get _isLogStyle =>
      !_isActive && !_isEnded &&
      (data.intent == IntentType.log || (data.intent == null && !_hasTurns));
  bool get _isAskStyle => !_isActive && !_isEnded && !_isLogStyle;

  @override
  Widget build(BuildContext context) {
    if (data.type == FeedCardType.action) {
      return _buildSimpleCard(badgeText: '待办', badgeColor: AppColors.darkOrange, showDoneButton: true);
    }
    if (data.type == FeedCardType.market) {
      return _buildSimpleCard(badgeText: '行情', badgeColor: AppColors.darkBlue, showDoneButton: false);
    }

    return Hoverable(
      builder: (context, isHovered) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
        child: Container(
          decoration: BoxDecoration(
            color: isHovered
                ? AppColors.darkSurface2
                : _isActive
                    ? AppColors.darkSurface
                    : AppColors.darkSurface.withValues(alpha: 0.6),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: _isEnded
                  ? AppColors.darkGreen.withValues(alpha: 0.35)
                  : AppColors.darkBorder.withValues(alpha: 0.6),
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // ── 时间竖列 ──
              Container(
                width: 64,
                padding: const EdgeInsets.only(top: 14),
                decoration: BoxDecoration(
                  color: AppColors.darkGreen.withValues(alpha: 0.04),
                  borderRadius: const BorderRadius.horizontal(left: Radius.circular(11)),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(data.time,
                        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGrey4)),
                    const SizedBox(height: 6),
                    if (_isChatting)
                      Container(width: 6, height: 6,
                          decoration: const BoxDecoration(color: AppColors.darkGreen, shape: BoxShape.circle))
                    else if (_isEnded)
                      Container(width: 6, height: 6,
                          decoration: BoxDecoration(color: AppColors.darkGreen.withValues(alpha: 0.5), shape: BoxShape.circle)),
                  ],
                ),
              ),
              const VerticalDivider(width: 1, color: AppColors.darkBorder),
              // ── 主内容区 ──
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 10, 8, 4),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _buildHeader(),
                      const SizedBox(height: 6),
                      if (!_hasTurns) _buildBody(),
                      if (_hasTurns) _buildTurns(),
                      if (data.summary != null && _isEnded) ...[
                        const SizedBox(height: 6),
                        _buildSummaryBanner(),
                      ],
                      if (data.tags != null && data.tags!.isNotEmpty && !_isActive) ...[
                        const SizedBox(height: 6),
                        _buildTags(),
                      ],
                      const SizedBox(height: 4),
                      _buildBottomLine(),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSimpleCard({required String badgeText, required Color badgeColor, required bool showDoneButton}) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
      child: Container(
        padding: const EdgeInsets.fromLTRB(16, 12, 12, 12),
        decoration: BoxDecoration(
          color: AppColors.darkSurface.withValues(alpha: 0.7),
          border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(color: badgeColor.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(6)),
              child: Text(badgeText,
                  style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: badgeColor)),
            ),
            const SizedBox(width: 10),
            Expanded(child: Text(data.content, style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1))),
            if (showDoneButton) ...[
              const SizedBox(width: 8),
              InkWell(
                onTap: data.onMarkDone,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                  decoration: BoxDecoration(
                    color: AppColors.darkGreen.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Text('完成',
                      style: TextStyle(fontSize: 12, color: AppColors.darkGreen, fontWeight: FontWeight.w600)),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  static const Map<String, String> _domainEmoji = {'life': '📝', 'trading': '📈', 'project': '📑'};

  Widget _buildHeader() {
    return Row(
      children: [
        if (_isLogStyle) ...[
          _badge('log', Icons.edit_note, AppColors.darkGrey5),
          const SizedBox(width: 6),
        ],
        if (_isAskStyle) ...[
          _badge('ask', Icons.help_outline, AppColors.darkGreen),
          const SizedBox(width: 6),
        ],
        if (data.loading && data.mode == CardMode.idle)
          const SizedBox(width: 14, height: 14,
              child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen)),
        const Spacer(),
        _buildDomainBadge(),
        const SizedBox(width: 4),
        _buildMoreMenu(),
      ],
    );
  }

  Widget _badge(String label, IconData icon, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(4)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 11, color: color),
        const SizedBox(width: 2),
        Text(label, style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: color)),
      ]),
    );
  }

  Widget _buildDomainBadge() {
    final emoji = _domainEmoji[data.domain] ?? '📝';
    final name = data.domain == 'life'
        ? '生活'
        : data.domain == 'trading'
            ? '交易'
            : data.domain == 'project'
                ? '项目'
                : data.domain;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Text('$emoji $name', style: const TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
    );
  }

  Widget _buildMoreMenu() {
    final menuItems = <PopupMenuEntry<String>>[
      ...['life', 'trading', 'project'].map((d) => PopupMenuItem<String>(
        value: 'domain:$d',
        height: 28,
        child: Text('${_domainEmoji[d]} ${d == 'life' ? '生活' : d == 'trading' ? '交易' : '项目'}',
            style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
      )),
      const PopupMenuDivider(),
      PopupMenuItem<String>(
        value: 'delete',
        height: 28,
        child: const Text('删除', style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
      ),
    ];
    return PopupMenuButton<String>(
      onSelected: (value) {
        if (value.startsWith('domain:')) {
          onDomainChanged?.call(value.substring(7));
        } else if (value == 'delete') {
          onDelete?.call();
        }
      },
      itemBuilder: (_) => menuItems,
      offset: const Offset(-16, 16),
      color: AppColors.darkSurface2,
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      child: Container(
        width: 20,
        height: 20,
        alignment: Alignment.center,
        child: const Icon(Icons.more_vert_rounded, size: 14, color: AppColors.darkGrey4),
      ),
    );
  }

  Widget _buildBody() {
    return Text(data.content, style: const TextStyle(fontSize: 15, height: 1.6, color: AppColors.darkGrey1));
  }

  Widget _buildTurns() {
    final turns = data.turns!;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: turns.map((turn) => Padding(
        padding: const EdgeInsets.only(bottom: 5),
        child: turn.isUser
            ? Text(turn.text,
                style: const TextStyle(fontSize: 15, height: 1.6, fontWeight: FontWeight.w500, color: AppColors.darkGrey1))
            : _buildAiMessage(turn.text),
      )).toList(),
    );
  }

  Widget _buildAiMessage(String text) {
    final cleanText = TextCleaner.stripDomainJson(text);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 2,
          margin: const EdgeInsets.only(top: 4, right: 10),
          decoration: BoxDecoration(color: AppColors.darkGreen.withValues(alpha: 0.4), borderRadius: BorderRadius.circular(1)),
        ),
        Expanded(
          child: MarkdownBody(
            data: cleanText,
            selectable: true,
            styleSheet: MarkdownStyleSheet.fromTheme(ThemeData(
              textTheme: const TextTheme(bodyMedium: TextStyle(fontSize: 15, height: 1.6, color: AppColors.darkGrey1)),
            )).copyWith(
              strong: const TextStyle(fontSize: 15, height: 1.6, color: AppColors.darkGrey1, fontWeight: FontWeight.w700),
              h1: const TextStyle(fontSize: 17, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w700),
              code: TextStyle(fontSize: 13, color: AppColors.darkGreen, backgroundColor: const Color(0xFF2A2826)),
              codeblockDecoration: BoxDecoration(
                color: const Color(0xFF2A2826),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3)),
              ),
              p: const TextStyle(fontSize: 15, height: 1.6, color: AppColors.darkGrey1),
              a: const TextStyle(fontSize: 15, color: AppColors.darkBlue),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSummaryBanner() {
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.darkGreen.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.check_circle_rounded, size: 14, color: AppColors.darkGreen),
          const SizedBox(width: 6),
          Expanded(
            child: Text(data.summary!,
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4, height: 1.4)),
          ),
        ],
      ),
    );
  }

  Widget _buildTags() {
    return Wrap(spacing: 4, runSpacing: 4, children: data.tags!.map(_chip).toList());
  }

  Widget _chip(String label) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Text(label, style: const TextStyle(fontSize: 10, color: AppColors.darkGrey4)),
    );
  }

  Widget _buildBottomLine() {
    if (data.error != null) return _lineRetry();
    if (_isActive) return _lineEnd();
    return _lineAsk();
  }

  Widget _lineEnd() {
    return GestureDetector(
      onTap: onEnd,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 26,
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 8),
        child: Text('end',
            style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGreen, letterSpacing: 0.5)),
      ),
    );
  }

  Widget _lineRetry() {
    return GestureDetector(
      onTap: onRetry,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 26,
        alignment: Alignment.centerLeft,
        child: Row(children: [
          const Icon(Icons.error_outline, size: 12, color: AppColors.darkOrange),
          const SizedBox(width: 4),
          Flexible(
            child: Text(data.error!,
                overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 10, color: AppColors.darkOrange)),
          ),
          const Spacer(),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.7)),
              borderRadius: BorderRadius.circular(6),
            ),
            child: const Text('重试',
                style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
          ),
          const SizedBox(width: 8),
        ]),
      ),
    );
  }

  Widget _lineAsk() {
    final color = _isEnded ? AppColors.darkGreen : AppColors.darkGreen.withValues(alpha: 0.8);
    return GestureDetector(
      onTap: onAsk,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 26,
        alignment: Alignment.center,
        child: Row(children: [
          Expanded(child: Container(height: 1, color: color.withValues(alpha: 0.25))),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Text('ask',
                style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: color, letterSpacing: 0.5)),
          ),
          Expanded(child: Container(height: 1, color: color.withValues(alpha: 0.25))),
        ]),
      ),
    );
  }
}
