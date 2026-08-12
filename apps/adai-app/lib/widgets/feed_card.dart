import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../theme/app_colors.dart';
import '../utils/text_cleaner.dart';
import 'full_image_dialog.dart';
import 'hoverable.dart';

/// 后端 record.type 映射。
enum FeedCardType { record, aiNote, push, dateSeparator, action, market }

/// 后端 intent：log → 记录，question → 提问。
enum IntentType { log, question;

  /// 从后端 API 返回的字符串解析（'log' / 'question'）。
  static IntentType? parse(String? value) {
    if (value == null) return null;
    return IntentType.values.firstWhere(
      (e) => e.name == value,
      orElse: () => IntentType.log,
    );
  }
}

class ConversationTurn {
  final bool isUser;
  final String text;
  final String time;
  ConversationTurn({required this.isUser, required this.text, required this.time});
}

enum CardMode {
  idle,     // record card（含 log / ask 风格）
  waiting,  // just clicked ask
  chatting, // in conversation
  ended,    // conversation ended
}

class FeedCardData {
  final String id;
  final FeedCardType type;
  final String time;
  final String date; // MM-dd，每张卡片都带（批2 每卡日期）
  final String content;
  final List<String>? tags;
  final String? summary;
  final List<ConversationTurn>? turns;
  final CardMode mode;
  final bool loading;
  final IntentType? intent;
  final bool expanded;
  final String domain;  // "life" | "trading" | "project"
  final String? error;  // API 调用失败时的错误信息，非 null 时卡片进入错误态
  final VoidCallback? onMarkDone; // action 卡"完成"按钮回调（调 PATCH /memory/{id}/done）
  final String? mediaUrl; // 图片记录原图 URL（批2 原图可见）
  final Map<String, String>? mediaHeaders; // 媒体请求鉴权头
  // REVIEW #235：上传占位卡保留原始图片字节/文件名/扩展名/共享 caption——
  // 失败重试重走 uploadImage 原路径（原实现把文件名当文本记录重发，字节永不重传）。
  final List<int>? mediaBytes;
  final String? mediaName;
  final String? mediaExt;
  final String? mediaCaption;
  final DateTime updatedAt;

  FeedCardData({
    required this.id, required this.type, required this.time, required this.content,
    this.date = '', this.tags, this.summary, this.turns, this.mode = CardMode.idle,
    this.loading = false, this.intent, this.expanded = false,
    this.domain = 'life', this.error, this.onMarkDone,
    this.mediaUrl, this.mediaHeaders,
    this.mediaBytes, this.mediaName, this.mediaExt, this.mediaCaption,
    DateTime? updatedAt,
  }) : updatedAt = updatedAt ?? DateTime.now();

  FeedCardData copyWith({
    String? id, FeedCardType? type, String? time, String? date, String? content,
    List<String>? tags, String? summary, List<ConversationTurn>? turns,
    CardMode? mode, bool? loading, IntentType? intent, bool? expanded,
    String? domain, String? error, bool clearError = false,
    String? mediaUrl, Map<String, String>? mediaHeaders,
    List<int>? mediaBytes, String? mediaName, String? mediaExt, String? mediaCaption,
    DateTime? updatedAt,
  }) {
    return FeedCardData(
      id: id ?? this.id, type: type ?? this.type, time: time ?? this.time,
      date: date ?? this.date, content: content ?? this.content, tags: tags ?? this.tags,
      summary: summary ?? this.summary, turns: turns ?? this.turns,
      mode: mode ?? this.mode, loading: loading ?? this.loading,
      intent: intent ?? this.intent, expanded: expanded ?? this.expanded,
      domain: domain ?? this.domain,
      error: clearError ? null : error ?? this.error,
      mediaUrl: mediaUrl ?? this.mediaUrl,
      mediaHeaders: mediaHeaders ?? this.mediaHeaders,
      mediaBytes: mediaBytes ?? this.mediaBytes,
      mediaName: mediaName ?? this.mediaName,
      mediaExt: mediaExt ?? this.mediaExt,
      mediaCaption: mediaCaption ?? this.mediaCaption,
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
  final VoidCallback? onDelete;
  final VoidCallback? onToggleExpand;
  final void Function(String domain)? onDomainChanged;
  final VoidCallback? onRetry;

  const FeedCard({
    super.key, required this.data,
    this.onAsk, this.onEnd, this.onActivate,
    this.onDelete, this.onToggleExpand,
    this.onDomainChanged, this.onRetry,
  });

  bool get _isWaiting => data.mode == CardMode.waiting;
  bool get _isChatting => data.mode == CardMode.chatting;
  bool get _isActive => _isWaiting || _isChatting;
  bool get _isEnded => data.mode == CardMode.ended;
  bool get _isIdle => data.mode == CardMode.idle;
  bool get _hasTurns => data.turns != null && data.turns!.isNotEmpty;
  // log: intent is 'log'; or no turns and no intent (feed-loaded records)
  bool get _isLogStyle => !_isActive && !_isEnded
      && (data.intent == IntentType.log || (data.intent == null && !_hasTurns));
  // ask: anything that's not log, not active, not ended
  bool get _isAskStyle => !_isActive && !_isEnded && !_isLogStyle;

  @override
  Widget build(BuildContext context) {
    // Date separator — no card styling
    if (data.type == FeedCardType.dateSeparator) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(data.content,
                style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGrey5)),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Container(height: 1, color: AppColors.darkBorder.withAlpha(80)),
            ),
          ],
        ),
      );
    }

    // Action todo card — 未完成行动提醒（记忆进化 Phase 3）
    if (data.type == FeedCardType.action) {
      return _buildSimpleCard(
        badgeText: '待办',
        badgeColor: AppColors.darkOrange,
        showDoneButton: true,
      );
    }
    // Market quote card — 大盘行情条（v0.2.0 L5）
    if (data.type == FeedCardType.market) {
      return _buildSimpleCard(
        badgeText: '行情',
        badgeColor: AppColors.darkBlue,
        showDoneButton: false,
      );
    }

    // Normal / expanded rendering (unchanged from current)
    final borderColor = _isEnded
        ? AppColors.darkGreen.withAlpha(180)
        : _isLogStyle
            ? AppColors.darkBorder.withAlpha(100)
            : AppColors.darkBorder.withAlpha(200);

    return Hoverable(
      builder: (context, isHovered) => Transform.translate(
        offset: isHovered ? const Offset(0, -2) : Offset.zero,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 5),
          child: Container(
            decoration: BoxDecoration(
              border: Border(
                left: _isActive ? BorderSide.none : BorderSide(color: borderColor, width: 1),
                top: BorderSide(color: borderColor, width: 1),
                right: BorderSide(color: borderColor, width: 1),
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
            child: Stack(
              children: [
                Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _buildHeader(),
                          const SizedBox(height: 6),
                          if (!_hasTurns) _buildBody(),
                          if (_hasTurns) ...[
                            const SizedBox(height: 3),
                            _buildTurns(),
                          ],
                          if (data.mediaUrl != null) ...[
                            const SizedBox(height: 8),
                            _buildMediaThumb(context),
                          ],
                          if (data.summary != null && !_isActive && !_isEnded) ...[
                            const SizedBox(height: 6),
                            _buildCleanSummary(),
                          ],
                          if (data.summary != null && _isEnded) ...[
                            const SizedBox(height: 6),
                            _buildSummaryBanner(),
                          ],
                          if (data.tags != null && data.tags!.isNotEmpty && !_isActive) ...[
                            const SizedBox(height: 6),
                            _buildTags(),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(height: 4),
                    _buildBottomLine(borderColor),
                  ],
                ),
                // Left accent indicator for active cards — overlay to avoid multi-color border issue
                if (_isActive)
                  PositionedDirectional(
                    start: 0, top: 0, bottom: 0,
                    child: Container(
                      width: 3,
                      decoration: BoxDecoration(
                        color: AppColors.darkGreen.withAlpha(150),
                        borderRadius: const BorderRadius.only(
                          topLeft: Radius.circular(16),
                          bottomLeft: Radius.circular(16),
                        ),
                      ),
                    ),
                  ),
              ],  // Stack children
            ),    // Stack
          ),      // Container(color)
        ),        // ClipRRect
      ),          // Container(decoration)
    ),            // Padding
      ),          // Transform.translate
    );            // Hoverable
  }

  /// 简单信息卡（待办提醒 / 大盘行情），无对话状态机。
  Widget _buildSimpleCard({required String badgeText, required Color badgeColor, required bool showDoneButton}) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 5),
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
        decoration: BoxDecoration(
          color: AppColors.darkSurface.withAlpha(200),
          border: Border.all(color: AppColors.darkBorder.withAlpha(120), width: 1),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 顶行：类型在前、日期+时间紧随其后，一起左上角
            Row(children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(color: badgeColor.withAlpha(40), borderRadius: BorderRadius.circular(6)),
                child: Text(badgeText,
                  style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: badgeColor)),
              ),
              const SizedBox(width: 8),
              Text(data.date.isEmpty ? data.time : '${data.date}  ${data.time}',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            ]),
            const SizedBox(height: 8),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: _buildSimpleContent()),
                if (showDoneButton) ...[
                  const SizedBox(width: 8),
                  InkWell(
                    onTap: data.onMarkDone,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                      decoration: BoxDecoration(
                        color: AppColors.darkGreen.withAlpha(30),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Text('完成',
                        style: TextStyle(fontSize: 12, color: AppColors.darkGreen, fontWeight: FontWeight.w600)),
                    ),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// 简单卡内容：行情卡红涨绿跌着色，其余普通文本。
  Widget _buildSimpleContent() {
    const style = TextStyle(fontSize: 14, color: AppColors.darkGrey1, height: 1.4);
    if (data.type != FeedCardType.market) {
      return Text(data.content, style: style);
    }
    return Text.rich(_buildMarketSpans(), style: style);
  }

  /// 行情内容「名称 指数 ±涨跌幅」段着色（A 股：红涨绿跌）。
  TextSpan _buildMarketSpans() {
    final children = <TextSpan>[];
    for (final seg in data.content.split(' · ')) {
      final trimmed = seg.trim();
      if (trimmed.isEmpty) continue;
      final pctMatch = RegExp(r'([+-]?\d+\.\d+)%$').firstMatch(trimmed);
      if (pctMatch == null) {
        children.add(TextSpan(text: trimmed));
      } else {
        final nameAndValue = trimmed.substring(0, pctMatch.start).trim();
        final pct = pctMatch.group(1)!;
        final value = double.tryParse(pct);
        final Color color;
        if (pct.startsWith('-')) {
          color = AppColors.darkGreen; // 跌 → 绿
        } else if (value == null || value == 0) {
          color = AppColors.darkGrey5; // 平 → 灰
        } else {
          color = AppColors.darkRed; // 涨 → 红
        }
        children.add(TextSpan(text: '$nameAndValue '));
        children.add(TextSpan(text: '$pct%', style: TextStyle(color: color, fontWeight: FontWeight.w600)));
      }
      children.add(const TextSpan(text: '  ·  '));
    }
    if (children.isNotEmpty && children.last.text == '  ·  ') children.removeLast();
    return TextSpan(children: children);
  }

  /// 图片记录缩略图（批2 原图可见）——点击弹全图。
  Widget _buildMediaThumb(BuildContext context) {
    final url = data.mediaUrl;
    if (url == null) return const SizedBox.shrink();
    return GestureDetector(
      onTap: () => _showFullImage(context),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: Image.network(
          url,
          headers: data.mediaHeaders,
          width: 96,
          height: 96,
          fit: BoxFit.cover,
          errorBuilder: (_, _, _) => Container(
            width: 96, height: 96,
            color: AppColors.darkSurface2,
            child: const Icon(Icons.broken_image_outlined, size: 20, color: AppColors.darkGrey5),
          ),
          loadingBuilder: (_, child, progress) => progress == null
              ? child
              : Container(
                  width: 96, height: 96,
                  color: AppColors.darkSurface2,
                  child: const Center(child: SizedBox(width: 16, height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen))),
                ),
        ),
      ),
    );
  }

  /// 点击缩略图 → 全图 Dialog（点任意处关闭）。
  /// REVIEW #244：复用公共全图 Dialog（带 errorBuilder，404 显示占位）。
  void _showFullImage(BuildContext context) {
    final url = data.mediaUrl;
    if (url == null) return;
    showFullImageDialog(context, url: url, headers: data.mediaHeaders);
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
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.edit_note, size: 11, color: AppColors.darkGrey5),
                const SizedBox(width: 2),
                Text('记录', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: AppColors.darkGrey5)),
              ],
            ),
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
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.help_outline, size: 11, color: AppColors.darkGreen),
                const SizedBox(width: 2),
                Text('提问', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
              ],
            ),
          ),
          const SizedBox(width: 6),
        ],
        Text(data.date.isEmpty ? data.time : '${data.date}  ${data.time}',
            style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGrey5)),
        if (_isChatting) ...[
          const SizedBox(width: 6),
          Container(width: 4, height: 4,
            decoration: BoxDecoration(color: AppColors.darkGreen, shape: BoxShape.circle),
          ),
        ],
        const Spacer(),
        // OS domain badge (or loading spinner during end-conversation / waiting / chatting processing)
        if (data.loading)
          SizedBox(
            width: 16, height: 16,
            child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen.withAlpha(150)),
          )
        else
          _buildDomainBadge(),
        const SizedBox(width: 4),
        // More menu
        _buildMoreMenu(),
      ],
    );
  }

  static const Map<String, String> _domainEmoji = {
    'life': '📝',
    'trading': '📈',
    'project': '📑',
  };

  Widget _buildDomainBadge() {
    final emoji = _domainEmoji[data.domain] ?? '📝';
    final name = data.domain == 'life' ? '生活'
        : data.domain == 'trading' ? '交易'
        : data.domain == 'project' ? '项目'
        : data.domain;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2.withAlpha(50),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: AppColors.darkBorder.withAlpha(80), width: 0.5),
      ),
      child: Text('$emoji $name',
        style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
    );
  }

  Widget _buildMoreMenu() {
    final selected = data.domain;
    final menuItems = <PopupMenuEntry<String>>[
      PopupMenuItem<String>(
        enabled: false,
        height: 22,
        padding: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.only(left: 10),
          child: Text('标记为', style: TextStyle(fontSize: 9, color: AppColors.darkGrey5, fontWeight: FontWeight.w500)),
        ),
      ),
      ...['life', 'trading', 'project'].map((d) => PopupMenuItem<String>(
        value: 'domain:$d',
        height: 24,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        child: Container(
          width: 90,
          decoration: d == selected
              ? BoxDecoration(
                  color: AppColors.darkGreen.withAlpha(25),
                  borderRadius: BorderRadius.circular(4),
                )
              : null,
          padding: EdgeInsets.symmetric(horizontal: 4, vertical: d == selected ? 2 : 0),
          child: Text('${_domainEmoji[d] ?? '📝'}  ${d == 'life' ? '生活' : d == 'trading' ? '交易' : '项目'}',
            style: TextStyle(
              fontSize: 11,
              fontWeight: d == selected ? FontWeight.w600 : FontWeight.w400,
              color: d == selected ? AppColors.darkGrey1 : AppColors.darkGrey3,
            ),
          ),
        ),
      )),
      const PopupMenuDivider(),
      PopupMenuItem<String>(
        value: 'delete',
        height: 24,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        child: SizedBox(
          width: 90,
          child: Text('删除', style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
        ),
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
        width: 20, height: 20,
        alignment: Alignment.center,
        child: Icon(Icons.more_vert_rounded, size: 14, color: AppColors.darkGrey4),
      ),
    );
  }

  Widget _buildBody() {
    return Text(data.content, style: TextStyle(fontSize: 15, height: 1.6, color: AppColors.darkGrey1));
  }

  /// 折叠时最大内容高度（超过此高度渐隐 + 展开按钮）。
  static const double _maxFoldHeight = 250;

  Widget _buildTurns() {
    final turns = data.turns!;
    // 用内容长度做快速预判（>200 字符才可能溢出 250px 高度）
    final totalChars = turns.fold<int>(0, (sum, t) => sum + t.text.length);
    final bool tooBig = totalChars > 200;
    // #15：active（chatting/waiting）时不折叠——用户正在继续聊天，必须看到完整上下文；
    // 折叠只用于浏览态（idle/ended）。
    final bool collapsed = !data.expanded && tooBig && !_isActive;
    // 折叠时截短 widget 数量减少布局压力
    final displayTurns = collapsed ? _truncateTurns(turns) : turns;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // ── 折叠态：ConstrainedBox 限高 + ClipRect + 渐隐 ──
        if (collapsed)
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: _maxFoldHeight),
            child: ClipRect(
              child: Stack(
                children: [
                  _buildTurnList(displayTurns),
                  // 底部渐隐
                  Positioned(bottom: 0, left: 0, right: 0,
                    child: IgnorePointer(
                      child: Container(
                        height: 48,
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [Colors.transparent, AppColors.darkSurface],
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          )
        else
          _buildTurnList(displayTurns),

        // ── 展开 / 收起按钮 ──（active 态不折叠，也无需按钮）
        if (tooBig && !_isActive)
          GestureDetector(
            onTap: onToggleExpand,
            child: Padding(
              padding: const EdgeInsets.only(top: 4, bottom: 5),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: AppColors.darkSurface2,
                      borderRadius: BorderRadius.circular(6),
                      border: Border.all(color: AppColors.darkBorder.withAlpha(76)),
                    ),
                    child: Text(collapsed ? '展开全部' : '收起',
                      style: TextStyle(fontSize: 10, color: AppColors.darkGrey4)),
                  ),
                  const SizedBox(width: 6),
                  Icon(collapsed ? Icons.expand_more : Icons.expand_less, size: 14, color: AppColors.darkGrey5),
                  const SizedBox(width: 6),
                  Text('${turns.length} 条',
                    style: TextStyle(fontSize: 9, color: AppColors.darkGrey6)),
                ],
              ),
            ),
          ),

        // ── Loading dots ──
        if (data.loading)
          const Padding(
            padding: EdgeInsets.only(left: 28, bottom: 5),
            child: _LoadingDots(),
          ),
      ],
    );
  }

  /// 渲染对话条目列表。
  Widget _buildTurnList(List<ConversationTurn> turns) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: turns.map((turn) => Padding(
        padding: const EdgeInsets.only(bottom: 5),
        child: turn.isUser
            ? Text(turn.text, style: TextStyle(fontSize: 15, height: 1.6,
                fontWeight: FontWeight.w500, color: AppColors.darkGrey1))
            : _buildAiMessage(turn.text),
      )).toList(),
    );
  }

  /// 折叠时截断：显示首条 + 末 2 条（减少实际渲染的 widget 数量）。
  List<ConversationTurn> _truncateTurns(List<ConversationTurn> turns) {
    if (turns.length <= 4) return turns;
    return [turns.first, turns[turns.length - 2], turns.last];
  }

  Widget _buildAiMessage(String text) {
    final cleanText = TextCleaner.stripDomainJson(text);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 2,
          margin: const EdgeInsets.only(top: 4, right: 10),
          decoration: BoxDecoration(
            color: AppColors.darkGreen.withAlpha(100),
            borderRadius: BorderRadius.circular(1),
          ),
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
              h2: const TextStyle(fontSize: 16, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w600),
              h3: const TextStyle(fontSize: 15, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w600),
              h4: const TextStyle(fontSize: 14, height: 1.5, color: AppColors.darkGrey1, fontWeight: FontWeight.w600),
              code: TextStyle(fontSize: 13, color: AppColors.darkGreen, backgroundColor: const Color(0xFF2A2826)),
              codeblockDecoration: BoxDecoration(
                color: const Color(0xFF2A2826),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppColors.darkGreen.withAlpha(50)),
              ),
              p: const TextStyle(fontSize: 15, height: 1.6, color: AppColors.darkGrey1),
              a: const TextStyle(fontSize: 15, color: AppColors.darkBlue),
            ),
          ),
        ),
      ],
    );
  }

  static const int _maxSummaryLen = 40;

  Widget _buildSummaryBanner() {
    final text = data.summary!;
    final bool long = text.length > _maxSummaryLen;
    final bool showFull = data.expanded || !long;
    final displayText = showFull ? text : '${text.substring(0, _maxSummaryLen)}...';

    return GestureDetector(
      onTap: long ? onToggleExpand : null,
      child: Container(
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
              child: Text(displayText,
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey4, height: 1.4)),
            ),
            if (!showFull && long)
              Padding(
                padding: const EdgeInsets.only(left: 4),
                child: Icon(Icons.expand_more, size: 14, color: AppColors.darkGrey5),
              ),
          ],
        ),
      ),
    );
  }

  /// 干净 summary 行 — 无图标无背景，纯文本。
  Widget _buildCleanSummary() {
    return Text(data.summary!,
      style: TextStyle(fontSize: 12, color: AppColors.darkGrey4, height: 1.4));
  }

  Widget _buildTags() {
    return Wrap(spacing: 4, runSpacing: 4,
      children: data.tags!.map((t) => _chip(t)).toList());
  }

  // ── Bottom line ──

  Widget _buildBottomLine(Color borderColor) {
    if (data.error != null) {
      return _lineRetry(borderColor);
    }
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
        height: 28,
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
                '结束',
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

  /// 错误态底部栏：显示错误信息 + 重试按钮。
  Widget _lineRetry(Color borderColor) {
    return GestureDetector(
      onTap: onRetry,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 28,
        decoration: BoxDecoration(
          border: Border(
            top: BorderSide(color: borderColor.withAlpha(128), width: 0.5),
          ),
        ),
        child: Row(
          children: [
            const SizedBox(width: 8),
            Icon(Icons.error_outline, size: 12, color: AppColors.darkOrange),
            const SizedBox(width: 4),
            Flexible(
              child: Text(
                data.error!,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 10, color: AppColors.darkOrange),
              ),
            ),
            const Spacer(),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                border: Border.all(color: AppColors.darkGreen.withAlpha(120)),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                '重试',
                style: TextStyle(
                  fontSize: 11, fontWeight: FontWeight.w500,
                  color: AppColors.darkGreen, letterSpacing: 0.5,
                ),
              ),
            ),
            const SizedBox(width: 8),
          ],
        ),
      ),
    );
  }

  Widget _lineCentered(Color borderColor) {
    final labelColor = _isEnded ? AppColors.darkGreen : AppColors.darkGreen.withAlpha(178);

    return GestureDetector(
      onTap: onAsk,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 28,
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
              child: Text('提问', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: labelColor, letterSpacing: 0.5)),
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
