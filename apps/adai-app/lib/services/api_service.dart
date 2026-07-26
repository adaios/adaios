import 'dart:convert';
import 'package:http/http.dart' as http;
import 'api_config.dart';
import 'models/identity_models.dart';
import 'models/tag_models.dart';

/// AdaiOS API 客户端。
/// 封装所有后端调用，App 其他部分不直接调 HTTP。
class ApiService {
  final String baseUrl;

  // 内存缓存：跨页面切换不丢
  FeedResponse? _feedCache;
  TagsResponse? _tagsCache;
  List<TimelineEntryResponse>? _timelineCache;
  List<MemoryEntryResponse>? _memoryCache;

  ApiService({String? baseUrl}) : baseUrl = baseUrl ?? ApiConfig.baseUrl;

  /// 获取 Feed 流（当天自动缓存）。
  Future<FeedResponse> getFeed({String? date, String? since}) async {
    final key = date ?? 'today';
    // 当天命中缓存直接返回
    if (key == 'today' && _feedCache != null) {
      return _feedCache!;
    }

    final params = <String, String>{};
    if (date != null) params['date'] = date;
    if (since != null) params['since'] = since;

    final uri = Uri.parse('$baseUrl/api/v1/feed').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await http.get(uri, headers: _headers);
    _check(resp);
    final feed = FeedResponse.fromJson(jsonDecode(resp.body));

    // 缓存当天结果
    if (key == 'today') {
      _feedCache = feed;
    }
    return feed;
  }

  /// 删除记录。
  Future<void> deleteRecord(String id) async {
    final resp = await http.delete(
      Uri.parse('$baseUrl/api/v1/records/$id'),
      headers: _headers,
    );
    _check(resp);
    _feedCache = null;
  }

  /// 使缓存失效（发送新消息后调用）。
  void invalidateFeedCache() {
    _feedCache = null;
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
    // 发送内容后缓存失效
    _feedCache = null;
    _tagsCache = null;
    _timelineCache = null;
    _memoryCache = null;
    return RecordResponse.fromJson(jsonDecode(resp.body));
  }

  /// 获取时间线（自动缓存）。
  Future<List<TimelineEntryResponse>> getTimeline({String? type, int limit = 50}) async {
    if (_timelineCache != null) return _timelineCache!;
    final params = <String, String>{};
    if (type != null) params['type'] = type;
    if (limit != 50) params['limit'] = limit.toString();

    final uri = Uri.parse('$baseUrl/api/v1/timeline').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await http.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(resp.body);
    _timelineCache = raw.map((e) => TimelineEntryResponse.fromJson(e)).toList();
    return _timelineCache!;
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

  /// 获取所有标签统计（自动缓存）。
  Future<TagsResponse> getTags() async {
    if (_tagsCache != null) return _tagsCache!;
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/tags'),
      headers: _headers,
    );
    _check(resp);
    _tagsCache = TagsResponse.fromJson(jsonDecode(resp.body));
    return _tagsCache!;
  }

  /// 获取某日的记忆列表（当天自动缓存）。
  Future<List<MemoryEntryResponse>> getMemory({String? date}) async {
    if (date == null && _memoryCache != null) return _memoryCache!;
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/memory').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await http.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(resp.body);
    final result = raw.map((e) => MemoryEntryResponse.fromJson(e)).toList();
    if (date == null) _memoryCache = result;
    return result;
  }

  /// 获取记忆总条数。
  Future<int> getMemoryCount() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/memory/count'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(resp.body);
    return data['count'] as int;
  }

  /// 获取有记忆的所有日期。
  Future<List<String>> getMemoryDates() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/memory/dates'),
      headers: _headers,
    );
    _check(resp);
    final List raw = jsonDecode(resp.body);
    return raw.map((e) => e as String).toList();
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

  /// 获取项目状态。
  Future<ProjectStatusResponse> getProjectStatus() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/project/status'),
      headers: _headers,
    );
    _check(resp);
    return ProjectStatusResponse.fromJson(jsonDecode(resp.body));
  }

  // ── 交易 API ──

  /// 查询当前持仓。
  Future<PositionsResponse> getPositions() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/trading/positions'),
      headers: _headers,
    );
    _check(resp);
    return PositionsResponse.fromJson(jsonDecode(resp.body));
  }

  /// 查询投资组合快照。
  Future<PortfolioSnapshotResponse> getPortfolio() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/trading/portfolio'),
      headers: _headers,
    );
    _check(resp);
    return PortfolioSnapshotResponse.fromJson(jsonDecode(resp.body));
  }

  /// 记录一笔交易。
  Future<PositionsResponse> recordTrade({
    required String symbol,
    required String name,
    required String direction,
    required double price,
    required int volume,
  }) async {
    final body = {
      'symbol': symbol,
      'name': name,
      'direction': direction,
      'price': price,
      'volume': volume,
    };
    final resp = await http.post(
      Uri.parse('$baseUrl/api/v1/trading/trades'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return PositionsResponse.fromJson(jsonDecode(resp.body));
  }

  // ── 任务 API ──

  /// 获取任务列表。
  Future<List<TaskResponse>> getTasks({String? status, String? tag}) async {
    final params = <String, String>{};
    if (status != null) params['status'] = status;
    if (tag != null) params['tag'] = tag;
    final uri = Uri.parse('$baseUrl/api/v1/project/tasks')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await http.get(uri, headers: _headers);
    _check(resp);
    final list = jsonDecode(resp.body) as List;
    return list.map((e) => TaskResponse.fromJson(e)).toList();
  }

  /// 创建任务。
  Future<TaskResponse> createTask({
    required String title,
    String? description,
    String? priority,
    List<String>? tags,
    String? rfcRef,
  }) async {
    final body = <String, dynamic>{
      'title': title,
      if (description != null) 'description': description,
      if (priority != null) 'priority': priority,
      if (tags != null) 'tags': tags,
      if (rfcRef != null) 'rfcRef': rfcRef,
    };
    final resp = await http.post(
      Uri.parse('$baseUrl/api/v1/project/tasks'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return TaskResponse.fromJson(jsonDecode(resp.body));
  }

  /// 更新任务。
  Future<TaskResponse> updateTask(String id, {String? title, String? description, String? status, String? priority, List<String>? tags, String? rfcRef}) async {
    final body = <String, dynamic>{};
    if (title != null) body['title'] = title;
    if (description != null) body['description'] = description;
    if (status != null) body['status'] = status;
    if (priority != null) body['priority'] = priority;
    if (tags != null) body['tags'] = tags;
    if (rfcRef != null) body['rfcRef'] = rfcRef;
    final resp = await http.put(
      Uri.parse('$baseUrl/api/v1/project/tasks/$id'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return TaskResponse.fromJson(jsonDecode(resp.body));
  }

  /// 删除任务。
  Future<void> deleteTask(String id) async {
    final resp = await http.delete(
      Uri.parse('$baseUrl/api/v1/project/tasks/$id'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 获取任务统计。
  Future<TaskStatsResponse> getTaskStats() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/project/tasks/stats'),
      headers: _headers,
    );
    _check(resp);
    return TaskStatsResponse.fromJson(jsonDecode(resp.body));
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
  static const String card = 'card';
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
  final List<Map<String, dynamic>>? turns;
  final String domain;

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
    this.turns,
    this.domain = 'life',
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
    turns: (json['turns'] as List?)?.cast<Map<String, dynamic>>(),
    domain: json['domain'] as String? ?? 'life',
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

// ── Project Status DTO ──

class ProjectStatusResponse {
  final String project;
  final String architecture;
  final Map<String, String> kernelComponents;
  final Map<String, String> domainStatus;
  final List<RfcItemResponse> rfcItems;
  final int commitCount;
  final int apiEndpoints;

  ProjectStatusResponse({
    required this.project,
    required this.architecture,
    required this.kernelComponents,
    required this.domainStatus,
    required this.rfcItems,
    required this.commitCount,
    required this.apiEndpoints,
  });

  factory ProjectStatusResponse.fromJson(Map<String, dynamic> json) =>
      ProjectStatusResponse(
        project: json['project'] as String? ?? '',
        architecture: json['architecture'] as String? ?? '',
        kernelComponents:
            Map<String, String>.from(json['kernelComponents'] as Map? ?? {}),
        domainStatus:
            Map<String, String>.from(json['domainStatus'] as Map? ?? {}),
        rfcItems: (json['rfcItems'] as List?)
            ?.map((e) => RfcItemResponse.fromJson(e))
            .toList() ?? [],
        commitCount: json['commitCount'] as int? ?? 0,
        apiEndpoints: json['apiEndpoints'] as int? ?? 0,
      );
}

class RfcItemResponse {
  final String title;
  final String date;
  final String status;

  RfcItemResponse({required this.title, required this.date, required this.status});

  factory RfcItemResponse.fromJson(Map<String, dynamic> json) => RfcItemResponse(
    title: json['title'] as String? ?? '',
    date: json['date'] as String? ?? '',
    status: json['status'] as String? ?? '',
  );
}

// ── Trading DTO ──

class PositionsResponse {
  final List<PositionItem> positions;

  PositionsResponse({required this.positions});

  factory PositionsResponse.fromJson(dynamic json) {
    final list = (json is List) ? json : (json['positions'] as List?) ?? [];
    return PositionsResponse(
      positions: list.map((e) => PositionItem.fromJson(e)).toList(),
    );
  }
}

class PositionItem {
  final String symbol;
  final String name;
  final int quantity;
  final double avgCost;
  final double currentPrice;
  final double marketValue;
  final double pnl;
  final double pnlPercent;

  PositionItem({
    required this.symbol,
    required this.name,
    required this.quantity,
    required this.avgCost,
    required this.currentPrice,
    required this.marketValue,
    required this.pnl,
    required this.pnlPercent,
  });

  factory PositionItem.fromJson(Map<String, dynamic> json) => PositionItem(
    symbol: json['symbol'] as String? ?? '',
    name: json['name'] as String? ?? '',
    quantity: json['quantity'] as int? ?? 0,
    avgCost: (json['avgCost'] as num?)?.toDouble() ?? 0,
    currentPrice: (json['currentPrice'] as num?)?.toDouble() ?? 0,
    marketValue: (json['marketValue'] as num?)?.toDouble() ?? 0,
    pnl: (json['pnl'] as num?)?.toDouble() ?? 0,
    pnlPercent: (json['pnlPercent'] as num?)?.toDouble() ?? 0,
  );
}

class PortfolioSnapshotResponse {
  final double totalValue;
  final double totalPnl;
  final double cashBalance;
  final int positionCount;

  PortfolioSnapshotResponse({
    required this.totalValue,
    required this.totalPnl,
    required this.cashBalance,
    required this.positionCount,
  });

  factory PortfolioSnapshotResponse.fromJson(Map<String, dynamic> json) =>
      PortfolioSnapshotResponse(
        totalValue: (json['totalValue'] as num?)?.toDouble() ?? 0,
        totalPnl: (json['totalPnl'] as num?)?.toDouble() ?? 0,
        cashBalance: (json['cashBalance'] as num?)?.toDouble() ?? 0,
        positionCount: json['positionCount'] as int? ?? 0,
      );
}

// ── 任务 DTO ──

class TaskResponse {
  final String id;
  final String title;
  final String description;
  final String status;
  final String priority;
  final List<String> tags;
  final String? rfcRef;
  final String createdAt;
  final String updatedAt;

  TaskResponse({
    required this.id,
    required this.title,
    this.description = '',
    required this.status,
    this.priority = 'P2',
    this.tags = const [],
    this.rfcRef,
    required this.createdAt,
    required this.updatedAt,
  });

  factory TaskResponse.fromJson(Map<String, dynamic> json) => TaskResponse(
    id: json['id'] as String? ?? '',
    title: json['title'] as String? ?? '',
    description: json['description'] as String? ?? '',
    status: json['status'] as String? ?? 'TODO',
    priority: json['priority'] as String? ?? 'P2',
    tags: (json['tags'] as List?)?.map((e) => e as String).toList() ?? [],
    rfcRef: json['rfcRef'] as String?,
    createdAt: json['createdAt'] as String? ?? '',
    updatedAt: json['updatedAt'] as String? ?? '',
  );
}

class TaskStatsResponse {
  final int total;
  final int todo;
  final int doing;
  final int done;
  final int cancelled;

  TaskStatsResponse({
    required this.total,
    required this.todo,
    required this.doing,
    required this.done,
    required this.cancelled,
  });

  factory TaskStatsResponse.fromJson(Map<String, dynamic> json) => TaskStatsResponse(
    total: json['total'] as int? ?? 0,
    todo: json['todo'] as int? ?? 0,
    doing: json['doing'] as int? ?? 0,
    done: json['done'] as int? ?? 0,
    cancelled: json['cancelled'] as int? ?? 0,
  );
}
