import 'package:flutter/material.dart';
import '../services/api_service.dart';

/// 卡片类型（桌面端与 adai-app 共享同一状态机模型，值复制不跨工程 import）。
enum FeedCardType { record, aiNote, push, dateSeparator, action, market }

/// 后端 intent：log → 记录，question → 提问。
enum IntentType {
  log,
  question;

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
  idle, // record card（含 log / ask 风格）
  waiting, // just clicked ask
  chatting, // in conversation
  ended, // conversation ended
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
  final String domain; // "life" | "trading" | "project"
  final String? error; // API 调用失败时的错误信息，非 null 时卡片进入错误态
  final VoidCallback? onMarkDone; // action 卡"完成"按钮回调（调 PATCH /memory/{id}/done）
  final String? mediaUrl; // 图片记录原图 URL（批2 原图可见）
  final Map<String, String>? mediaHeaders; // 媒体请求鉴权头
  // REVIEW F37（全维度走查 P1-W1）：图片占位卡保留原始字节，失败重试重走 uploadImage（防降级为文本记录）
  final List<int>? mediaBytes;
  final String? mediaName;
  final String? mediaExt;
  final String? mediaCaption;
  final DateTime updatedAt;

  FeedCardData({
    required this.id,
    required this.type,
    required this.time,
    required this.content,
    this.date = '',
    this.tags,
    this.summary,
    this.turns,
    this.mode = CardMode.idle,
    this.loading = false,
    this.intent,
    this.expanded = false,
    this.domain = 'life',
    this.error,
    this.onMarkDone,
    this.mediaUrl,
    this.mediaHeaders,
    this.mediaBytes,
    this.mediaName,
    this.mediaExt,
    this.mediaCaption,
    DateTime? updatedAt,
  }) : updatedAt = updatedAt ?? DateTime.now();

  FeedCardData copyWith({
    String? id,
    FeedCardType? type,
    String? time,
    String? date,
    String? content,
    List<String>? tags,
    String? summary,
    List<ConversationTurn>? turns,
    CardMode? mode,
    bool? loading,
    IntentType? intent,
    bool? expanded,
    String? domain,
    String? error,
    bool clearError = false,
    String? mediaUrl,
    Map<String, String>? mediaHeaders,
    List<int>? mediaBytes,
    String? mediaName,
    String? mediaExt,
    String? mediaCaption,
    DateTime? updatedAt,
  }) {
    return FeedCardData(
      id: id ?? this.id,
      type: type ?? this.type,
      time: time ?? this.time,
      date: date ?? this.date,
      content: content ?? this.content,
      tags: tags ?? this.tags,
      summary: summary ?? this.summary,
      turns: turns ?? this.turns,
      mode: mode ?? this.mode,
      loading: loading ?? this.loading,
      intent: intent ?? this.intent,
      expanded: expanded ?? this.expanded,
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

/// Feed 条目 → 卡片数据（值复制自 adai-app main_page）。
extension FeedEntryResponseX on FeedEntryResponse {
  FeedCardData toFeedData({required ApiService api, VoidCallback? onMarkDone}) {
    List<ConversationTurn>? cardTurns;
    if (turns != null && turns!.isNotEmpty) {
      cardTurns = turns!.map((t) => ConversationTurn(
        isUser: t['isUser'] as bool? ?? true,
        text: t['text'] as String? ?? '',
        time: t['time'] as String? ?? '',
      )).toList();
    }
    return FeedCardData(
      id: id,
      type: _toCardType(type),
      time: time,
      date: date,
      content: content,
      tags: tags.isNotEmpty ? tags : null,
      mode: CardMode.idle,
      intent: IntentType.parse(intent),
      summary: summary,
      turns: cardTurns,
      domain: domain,
      onMarkDone: onMarkDone,
      mediaUrl: mediaPath != null ? api.mediaUrl(id) : null,
      mediaHeaders: mediaPath != null ? api.mediaHeaders : null,
    );
  }

  /// 后端 Feed type → 前端卡片类型（action/market 有专属渲染，其余归 record）。
  FeedCardType _toCardType(String type) {
    switch (type) {
      case FeedEntryType.aiNote:
        return FeedCardType.aiNote;
      case FeedEntryType.action:
        return FeedCardType.action;
      case FeedEntryType.market:
        return FeedCardType.market;
      // #162：push 类型不再落默认 record（L5 推送上线时渲染成普通卡）
      case FeedEntryType.push:
        return FeedCardType.push;
      default:
        return FeedCardType.record;
    }
  }
}
