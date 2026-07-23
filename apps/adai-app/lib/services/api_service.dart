import 'dart:convert';
import 'package:http/http.dart' as http;
import 'api_config.dart';
import 'models/identity_models.dart';
import 'models/tag_models.dart';

/// AdaiOS API 客户端。
/// 封装所有后端调用，App 其他部分不直接调 HTTP。
class ApiService {
  final String baseUrl;

  ApiService({String? baseUrl}) : baseUrl = baseUrl ?? ApiConfig.baseUrl;

  /// 获取 Feed 流。
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

  /// 获取时间线。
  Future<List<TimelineEntryResponse>> getTimeline({String? type, int limit = 50}) async {
    final params = <String, String>{};
    if (type != null) params['type'] = type;
    if (limit != 50) params['limit'] = limit.toString();

    final uri = Uri.parse('$baseUrl/api/v1/timeline').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await http.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(resp.body);
    return raw.map((e) => TimelineEntryResponse.fromJson(e)).toList();
  }

  /// 结束会话。
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

  /// 读取个人档案。
  Future<IdentityResponse> getIdentity() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/identity'),
      headers: _headers,
    );
    _check(resp);
    return IdentityResponse.fromJson(jsonDecode(resp.body));
  }

  /// 更新个人档案。
  Future<IdentityResponse> updateIdentity(IdentityRequest request) async {
    final resp = await http.put(
      Uri.parse('$baseUrl/api/v1/identity'),
      headers: _headers,
      body: jsonEncode(request.toJson()),
    );
    _check(resp);
    return IdentityResponse.fromJson(jsonDecode(resp.body));
  }

  /// 获取所有标签统计。
  Future<TagsResponse> getTags() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/tags'),
      headers: _headers,
    );
    _check(resp);
    return TagsResponse.fromJson(jsonDecode(resp.body));
  }

  /// 获取某日的记忆列表。
  Future<List<MemoryEntryResponse>> getMemory({String? date}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/memory').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await http.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(resp.body);
    return raw.map((e) => MemoryEntryResponse.fromJson(e)).toList();
  }

  /// 全文搜索。
  Future<SearchResponse> search(String query) async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/search?q=$query'),
      headers: _headers,
    );
    _check(resp);
    return SearchResponse.fromJson(jsonDecode(resp.body));
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

// ── Feed entry type constants ──

class FeedEntryType {
  static const String record = 'record';
  static const String aiNote = 'ai_note';
  static const String push = 'push';
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
  final String type;
  final String id;
  final String? sourceRecordId;
  final String title;
  final String content;
  final List<String> tags;
  final String time;
  final String? intent;
  final String? summary;

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
  final String intent;
  final String? recordId;
  final String? summary;
  final List<String>? tags;
  final String? content;

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

/// 记忆条目 DTO
class MemoryEntryResponse {
  final String id;
  final String recordId;
  final String summary;
  final List<String> tags;
  final String sentiment;
  final String createdAt;

  MemoryEntryResponse({
    required this.id,
    required this.recordId,
    required this.summary,
    required this.tags,
    required this.sentiment,
    required this.createdAt,
  });

  factory MemoryEntryResponse.fromJson(Map<String, dynamic> json) => MemoryEntryResponse(
    id: json['id'] as String? ?? '',
    recordId: json['recordId'] as String? ?? '',
    summary: json['summary'] as String? ?? '',
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    sentiment: json['sentiment'] as String? ?? 'neutral',
    createdAt: json['createdAt'] as String? ?? '',
  );
}

/// 搜索结果 DTO
class SearchResponse {
  final List<SearchResultItem> results;
  final int total;

  SearchResponse({required this.results, required this.total});

  factory SearchResponse.fromJson(Map<String, dynamic> json) => SearchResponse(
    results: (json['results'] as List?)?.map((e) => SearchResultItem.fromJson(e)).toList() ?? [],
    total: json['total'] as int? ?? 0,
  );
}

class SearchResultItem {
  final String id;
  final String type;
  final String title;
  final String content;
  final List<String> tags;
  final String dateTime;

  SearchResultItem({
    required this.id,
    required this.type,
    required this.title,
    required this.content,
    required this.tags,
    required this.dateTime,
  });

  factory SearchResultItem.fromJson(Map<String, dynamic> json) => SearchResultItem(
    id: json['id'] as String? ?? '',
    type: json['type'] as String? ?? 'note',
    title: json['title'] as String? ?? '',
    content: json['content'] as String? ?? '',
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    dateTime: json['dateTime'] as String? ?? '',
  );
}
