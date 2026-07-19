import 'dart:convert';
import 'package:http/http.dart' as http;
import 'api_config.dart';

/// AdaiOS API 客户端。
/// 封装所有后端调用，App 其他部分不直接调 HTTP。
class ApiService {
  final String baseUrl;

  ApiService({String? baseUrl}) : baseUrl = baseUrl ?? ApiConfig.baseUrl;

  /// 获取 Feed 流。
  /// [since] 可选，此时间之后的条目为"当前会话"。
  Future<FeedResponse> getFeed({String? date, String? since}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    if (since != null) params['since'] = since;

    final uri = Uri.parse('$baseUrl/api/v1/feed').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await http.get(uri, headers: _headers);
    _check(resp);
    return FeedResponse.fromJson(jsonDecode(resp.body));
  }

  /// 提交记录。
  /// [intent] 可选，手动指定意图：log | question，不指定则由后端自动识别。
  /// [cardId] 可选，会话卡片 ID，用于跟踪对话上下文。
  Future<RecordResponse> createRecord(String content, {String? type, List<String>? tags, String? intent, String? cardId}) async {
    final body = {
      'content': content,
      if (type != null) 'type': type,
      if (tags != null && tags.isNotEmpty) 'tags': tags,
      if (intent != null) 'intent': intent,
      if (cardId != null) 'cardId': cardId,
    };
    final resp = await http.post(
      Uri.parse('$baseUrl/api/v1/records'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return RecordResponse.fromJson(jsonDecode(resp.body));
  }

  /// 获取时间线（按天分组）。
  Future<List<TimelineEntryResponse>> getTimeline({String? date}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;

    final uri = Uri.parse('$baseUrl/api/v1/timeline').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await http.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(resp.body);
    return raw.map((e) => TimelineEntryResponse.fromJson(e)).toList();
  }

  /// 结束会话（总结对话，存为记录）。
  Future<EndConversationResponse> endConversation(List<String> turns, {String? cardId}) async {
    final body = {
      'turns': turns,
      if (cardId != null) 'cardId': cardId,
    };
    final resp = await http.post(
      Uri.parse('$baseUrl/api/v1/conversations/end'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return EndConversationResponse.fromJson(jsonDecode(resp.body));
  }

  Map<String, String> get _headers => {
    'Content-Type': 'application/json',
  };

  void _check(http.Response resp) {
    if (resp.statusCode >= 400) {
      throw Exception('API 错误 ${resp.statusCode}: ${resp.body}');
    }
  }
}

// ── DTO ──

class FeedResponse {
  final String brief;
  final List<FeedEntryResponse> entries;
  final int earlierCount;

  FeedResponse({required this.brief, required this.entries, required this.earlierCount});

  factory FeedResponse.fromJson(Map<String, dynamic> json) => FeedResponse(
    brief: json['brief'] as String,
    entries: (json['entries'] as List).map((e) => FeedEntryResponse.fromJson(e)).toList(),
    earlierCount: json['earlierCount'] as int? ?? 0,
  );
}

class FeedEntryResponse {
  final String type;  // record | ai_note | push
  final String id;
  final String? sourceRecordId;
  final String title;
  final String content;
  final List<String> tags;
  final String time;  // HH:mm
  final String? intent; // "question" | "log" | null
  final String? summary; // AI-generated summary

  FeedEntryResponse({
    required this.type,
    required this.id,
    this.sourceRecordId,
    required this.title,
    required this.content,
    required this.tags,
    required this.time,
    this.intent,
    this.summary,
  });

  factory FeedEntryResponse.fromJson(Map<String, dynamic> json) => FeedEntryResponse(
    type: json['type'] as String,
    id: json['id'] as String,
    sourceRecordId: json['sourceRecordId'] as String?,
    title: json['title'] as String? ?? '',
    content: json['content'] as String,
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    time: json['time'] as String? ?? json['timeString'] as String? ?? '',
    intent: json['intent'] as String?,
    summary: json['summary'] as String?,
  );
}

class RecordResponse {
  final String intent;  // log | question
  final String? recordId;
  final String? summary;  // question: AI answer; log: brief confirmation
  final List<String>? tags;
  final String? content;  // log: original content echoed back

  RecordResponse({required this.intent, this.recordId, this.summary, this.tags, this.content});

  factory RecordResponse.fromJson(Map<String, dynamic> json) {
    final intent = json['intent'] as String? ?? 'log';
    return RecordResponse(
      intent: intent,
      recordId: json['recordId'] as String?,
      summary: json['summary'] as String?,
      tags: (json['tags'] as List?)?.cast<String>(),
      content: json['content'] as String?,
    );
  }
}

class UnderstandingResponse {
  final String summary;
  final List<String> tags;
  final String sentiment;
  final bool actionable;
  final String? actionSuggestion;

  UnderstandingResponse({
    required this.summary,
    required this.tags,
    required this.sentiment,
    required this.actionable,
    this.actionSuggestion,
  });

  factory UnderstandingResponse.fromJson(Map<String, dynamic> json) => UnderstandingResponse(
    summary: json['summary'] as String? ?? '',
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    sentiment: json['sentiment'] as String? ?? 'neutral',
    actionable: json['actionable'] as bool? ?? false,
    actionSuggestion: json['actionSuggestion'] as String?,
  );
}

class TimelineEntryResponse {
  final String id;
  final String type;
  final String title;
  final List<String> tags;
  final String dateTime;

  TimelineEntryResponse({required this.id, required this.type, required this.title, required this.tags, required this.dateTime});

  factory TimelineEntryResponse.fromJson(Map<String, dynamic> json) => TimelineEntryResponse(
    id: json['id'] as String,
    type: json['type'] as String? ?? 'note',
    title: json['title'] as String? ?? '',
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    dateTime: json['dateTime'] as String? ?? '',
  );
}

class EndConversationResponse {
  final String recordId;
  final String summary;
  final List<String> tags;

  EndConversationResponse({required this.recordId, required this.summary, required this.tags});

  factory EndConversationResponse.fromJson(Map<String, dynamic> json) => EndConversationResponse(
    recordId: json['recordId'] as String? ?? '',
    summary: json['summary'] as String? ?? '',
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
  );
}
