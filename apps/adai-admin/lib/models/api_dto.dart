/// 后端（adai-core Spring Boot）响应 DTO。
///
/// 后端响应为 snake_case JSON（如 `createdAt`、`isDir`、`avgCost`）。
/// 这些 DTO 只负责从 JSON 解析，页面仍使用 `data_models.dart` /
/// `system_models.dart` / `knowledge_models.dart` 中的领域模型，
/// 由各 ApiStore 做 DTO → 页面模型映射。
library;

// ── Feed ──

/// `GET /api/v1/feed` 响应：`{entries: [...], totalToday: n}`。
class FeedResponseDto {
  const FeedResponseDto({required this.entries, required this.totalToday});

  final List<FeedEntryDto> entries;
  final int totalToday;

  factory FeedResponseDto.fromJson(Map<String, dynamic> json) => FeedResponseDto(
        entries: (json['entries'] as List? ?? [])
            .map((e) => FeedEntryDto.fromJson(e as Map<String, dynamic>))
            .toList(),
        totalToday: json['totalToday'] as int? ?? 0,
      );
}

/// Feed 单条目。`time` 为 "HH:mm" 字符串。
class FeedEntryDto {
  const FeedEntryDto({
    required this.type,
    required this.id,
    required this.title,
    required this.content,
    required this.tags,
    required this.time,
    this.intent,
    this.summary,
    this.domain,
  });

  final String type;
  final String id;
  final String title;
  final String content;
  final List<String> tags;
  final String time;
  final String? intent;
  final String? summary;
  final String? domain;

  factory FeedEntryDto.fromJson(Map<String, dynamic> json) => FeedEntryDto(
        type: json['type'] as String? ?? 'record',
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? '',
        content: json['content'] as String? ?? '',
        tags: (json['tags'] as List?)?.cast<String>() ?? const [],
        time: json['time'] as String? ?? '',
        intent: json['intent'] as String?,
        summary: json['summary'] as String?,
        domain: json['domain'] as String?,
      );
}

// ── Memory ──

/// `GET /api/v1/memory` 单条目（记忆进化 Phase 1-5 全字段）。
class MemoryDto {
  const MemoryDto({
    required this.id,
    this.recordId = '',
    this.kind = 'insight',
    this.summary = '',
    this.tags = const [],
    this.sentiment = 'neutral',
    this.createdAt = '',
    this.topic,
    this.superseded = false,
    this.evolvedTo,
    this.actionable = false,
    this.suggestion,
    this.doneAt,
    this.lastConfirmed,
  });

  final String id;
  final String recordId;
  final String kind;
  final String summary;
  final List<String> tags;
  final String sentiment;
  final String createdAt;
  final String? topic;
  final bool superseded;
  final String? evolvedTo;
  final bool actionable;
  final String? suggestion;
  final String? doneAt;
  final String? lastConfirmed;

  factory MemoryDto.fromJson(Map<String, dynamic> json) => MemoryDto(
        id: json['id'] as String? ?? '',
        recordId: json['recordId'] as String? ?? '',
        kind: json['kind'] as String? ?? 'insight',
        summary: json['summary'] as String? ?? '',
        tags: (json['tags'] as List?)?.cast<String>() ?? const [],
        sentiment: json['sentiment'] as String? ?? 'neutral',
        createdAt: json['createdAt'] as String? ?? '',
        topic: json['topic'] as String?,
        superseded: json['superseded'] as bool? ?? false,
        evolvedTo: json['evolvedTo'] as String?,
        actionable: json['actionable'] as bool? ?? false,
        suggestion: json['suggestion'] as String?,
        doneAt: json['doneAt'] as String?,
        lastConfirmed: json['lastConfirmed'] as String?,
      );
}

// ── Identity ──

/// `GET /api/v1/identity` 响应：`{name, preferences, rules, tags}`。
/// 后端 `rules` 为 `Map<String,String>`，映射到页面 `List<String>` 时取 values。
class IdentityDto {
  const IdentityDto({
    this.name = '',
    this.preferences = const {},
    this.rules = const {},
    this.tags = const [],
  });

  final String name;
  final Map<String, String> preferences;
  final Map<String, String> rules;
  final List<String> tags;

  factory IdentityDto.fromJson(Map<String, dynamic> json) => IdentityDto(
        name: json['name'] as String? ?? '',
        preferences: (json['preferences'] as Map?)?.map(
              (k, v) => MapEntry(k.toString(), v.toString()),
            ) ??
            const {},
        rules: (json['rules'] as Map?)?.map(
              (k, v) => MapEntry(k.toString(), v.toString()),
            ) ??
            const {},
        tags: (json['tags'] as List?)?.cast<String>() ?? const [],
      );
}

// ── Task ──

/// `GET /api/v1/project/tasks` 单条目。
class TaskDto {
  const TaskDto({
    required this.id,
    required this.title,
    this.description = '',
    this.status = 'TODO',
    this.priority = 'P2',
    this.tags = const [],
    this.rfcRef,
    this.createdAt = '',
    this.updatedAt = '',
  });

  final String id;
  final String title;
  final String description;
  final String status; // TODO / DOING / DONE / CANCELLED
  final String priority; // P0 / P1 / P2 / P3
  final List<String> tags;
  final String? rfcRef;
  final String createdAt;
  final String updatedAt;

  bool get isDone => status == 'DONE';

  factory TaskDto.fromJson(Map<String, dynamic> json) => TaskDto(
        id: json['id'] as String? ?? '',
        title: json['title'] as String? ?? '',
        description: json['description'] as String? ?? '',
        status: json['status'] as String? ?? 'TODO',
        priority: json['priority'] as String? ?? 'P2',
        tags: (json['tags'] as List?)?.cast<String>() ?? const [],
        rfcRef: json['rfcRef'] as String?,
        createdAt: json['createdAt'] as String? ?? '',
        updatedAt: json['updatedAt'] as String? ?? '',
      );
}

/// `GET /api/v1/project/tasks/stats` 响应。
class TaskStatsDto {
  const TaskStatsDto({
    this.total = 0,
    this.todo = 0,
    this.doing = 0,
    this.done = 0,
    this.cancelled = 0,
  });

  final int total;
  final int todo;
  final int doing;
  final int done;
  final int cancelled;

  factory TaskStatsDto.fromJson(Map<String, dynamic> json) => TaskStatsDto(
        total: json['total'] as int? ?? 0,
        todo: json['todo'] as int? ?? 0,
        doing: json['doing'] as int? ?? 0,
        done: json['done'] as int? ?? 0,
        cancelled: json['cancelled'] as int? ?? 0,
      );
}

// ── Trading ──

/// `GET /api/v1/trading/positions` 单条目（含后端计算的市值/盈亏字段）。
class PositionDto {
  const PositionDto({
    required this.symbol,
    this.name = '',
    this.quantity = 0,
    this.avgCost = 0,
    this.currentPrice = 0,
    this.lastUpdated,
    this.marketValue = 0,
    this.pnl = 0,
    this.pnlPercent = 0,
  });

  final String symbol;
  final String name;
  final int quantity;
  final double avgCost;
  final double currentPrice;
  final String? lastUpdated;
  final double marketValue;
  final double pnl;
  final double pnlPercent;

  factory PositionDto.fromJson(Map<String, dynamic> json) => PositionDto(
        symbol: json['symbol'] as String? ?? '',
        name: json['name'] as String? ?? '',
        quantity: json['quantity'] as int? ?? 0,
        avgCost: (json['avgCost'] as num?)?.toDouble() ?? 0,
        currentPrice: (json['currentPrice'] as num?)?.toDouble() ?? 0,
        lastUpdated: json['lastUpdated'] as String?,
        marketValue: (json['marketValue'] as num?)?.toDouble() ?? 0,
        pnl: (json['pnl'] as num?)?.toDouble() ?? 0,
        pnlPercent: (json['pnlPercent'] as num?)?.toDouble() ?? 0,
      );
}

/// `GET /api/v1/trading/review?date=` 响应：`{date, content}`。
class ReviewDto {
  const ReviewDto({required this.date, required this.content});

  final String date;
  final String content;

  factory ReviewDto.fromJson(Map<String, dynamic> json) => ReviewDto(
        date: json['date'] as String? ?? '',
        content: json['content'] as String? ?? '',
      );
}

/// `GET /api/v1/trading/has-activity?date=` 响应：`{date, hasActivity}`。
class ActivityCheckDto {
  const ActivityCheckDto({required this.date, this.hasActivity = false});

  final String date;
  final bool hasActivity;

  factory ActivityCheckDto.fromJson(Map<String, dynamic> json) =>
      ActivityCheckDto(
        date: json['date'] as String? ?? '',
        hasActivity: json['hasActivity'] as bool? ?? false,
      );
}

/// `GET /api/v1/trading/knowledge/conflicts` 响应。
class ConflictsResponseDto {
  const ConflictsResponseDto({this.conflicts = const []});

  final List<ConflictDto> conflicts;

  factory ConflictsResponseDto.fromJson(Map<String, dynamic> json) =>
      ConflictsResponseDto(
        conflicts: (json['conflicts'] as List? ?? [])
            .map((e) => ConflictDto.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class ConflictDto {
  const ConflictDto({
    this.rule = '',
    this.description = '',
    this.category = '',
  });

  final String rule;
  final String description;
  final String category;

  factory ConflictDto.fromJson(Map<String, dynamic> json) => ConflictDto(
        rule: json['rule'] as String? ?? '',
        description: json['description'] as String? ?? '',
        category: json['category'] as String? ?? '',
      );
}

/// `POST /api/v1/trading/reviews/{date}/promote` 响应：`{status, path}`。
class PromoteResultDto {
  const PromoteResultDto({this.status = '', this.path = '', this.message = ''});

  final String status;
  final String path;
  final String message;

  factory PromoteResultDto.fromJson(Map<String, dynamic> json) =>
      PromoteResultDto(
        status: json['status'] as String? ?? '',
        path: json['path'] as String? ?? '',
        message: json['message'] as String? ?? '',
      );
}

// ── Admin 文件 / 知识 ──

/// `GET /api/v1/admin/files` 与 `GET /api/v1/admin/knowledge` 目录条目。
class AdminFileDto {
  const AdminFileDto({
    required this.name,
    required this.path,
    required this.isDir,
    this.size,
  });

  final String name;
  final String path;
  final bool isDir;
  final int? size;

  factory AdminFileDto.fromJson(Map<String, dynamic> json) => AdminFileDto(
        name: json['name'] as String? ?? '',
        path: json['path'] as String? ?? '',
        isDir: json['isDir'] as bool? ?? false,
        size: json['size'] as int?,
      );
}

/// `GET /api/v1/admin/files/content` 与 `knowledge/content` 响应。
class AdminFileContentDto {
  const AdminFileContentDto({
    this.path = '',
    this.size = 0,
    this.content = '',
  });

  final String path;
  final int size;
  final String content;

  factory AdminFileContentDto.fromJson(Map<String, dynamic> json) =>
      AdminFileContentDto(
        path: json['path'] as String? ?? '',
        size: json['size'] as int? ?? 0,
        content: json['content'] as String? ?? '',
      );
}

// ── 维护操作结果 ──

/// `POST /api/v1/memory/rebuild` 响应。
class RebuildResultDto {
  const RebuildResultDto({
    this.success = 0,
    this.failed = 0,
    this.total = 0,
    this.errors = const [],
  });

  final int success;
  final int failed;
  final int total;
  final List<String> errors;

  factory RebuildResultDto.fromJson(Map<String, dynamic> json) =>
      RebuildResultDto(
        success: json['success'] as int? ?? 0,
        failed: json['failed'] as int? ?? 0,
        total: json['total'] as int? ?? 0,
        errors: (json['errors'] as List?)?.cast<String>() ?? const [],
      );
}

/// `POST /api/v1/records/retry` 响应。
class RetryResultDto {
  const RetryResultDto({
    this.status = '',
    this.memoriesBefore = 0,
    this.memoriesAfter = 0,
    this.newMemories = 0,
  });

  final String status;
  final int memoriesBefore;
  final int memoriesAfter;
  final int newMemories;

  factory RetryResultDto.fromJson(Map<String, dynamic> json) => RetryResultDto(
        status: json['status'] as String? ?? '',
        memoriesBefore: json['memoriesBefore'] as int? ?? 0,
        memoriesAfter: json['memoriesAfter'] as int? ?? 0,
        newMemories: json['newMemories'] as int? ?? 0,
      );
}
