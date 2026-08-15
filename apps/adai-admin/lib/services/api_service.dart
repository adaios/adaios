import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/account.dart';
import '../models/api_dto.dart';
import 'api_config.dart';
import 'api_exception.dart';

/// AdaiOS 管理端 API 客户端 — 封装所有后端调用。
///
/// - 构造器可注入 [http.Client]（测试用 `MockClient`）与 [baseUrl]。
/// - [userId] 用于 per-user 请求的 `X-User-Id` header（默认 'default'）。
/// - [adminToken]（REVIEW #127）随系统级请求带 `X-Admin-Token`，默认取 [ApiConfig.adminToken]。
/// - 方法返回解析后的 DTO / 模型，失败抛 [ApiException]。
class ApiService {
  ApiService({
    http.Client? client,
    String? baseUrl,
    this.userId = 'default',
    this.adminToken = ApiConfig.adminToken,
  })  : _client = client ?? http.Client(),
        baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final http.Client _client;
  final String baseUrl;
  final String userId;
  final String adminToken;

  // ── 通用请求 ──

  /// 系统级请求头（无 X-User-Id；带管理令牌）。
  Map<String, String> get systemHeaders => {
        'Content-Type': 'application/json',
        if (adminToken.isNotEmpty) 'X-Admin-Token': adminToken,
      };

  /// per-user 请求头（带 X-User-Id）。
  Map<String, String> get userHeaders => {
        ...systemHeaders,
        'X-User-Id': userId,
      };

  Future<http.Response> _get(String path, {Map<String, String>? headers, Map<String, String>? query}) async {
    final uri = Uri.parse('$baseUrl$path')
        .replace(queryParameters: (query == null || query.isEmpty) ? null : query);
    final resp = await _client.get(uri, headers: headers ?? systemHeaders);
    return _check(resp);
  }

  Future<http.Response> _send(
    String method,
    String path, {
    Map<String, String>? headers,
    Object? body,
    Map<String, String>? query,
  }) async {
    final uri = Uri.parse('$baseUrl$path')
        .replace(queryParameters: (query == null || query.isEmpty) ? null : query);
    final encoded = body == null ? null : jsonEncode(body);
    final h = headers ?? systemHeaders;
    final http.Response resp;
    switch (method) {
      case 'POST':
        resp = await _client.post(uri, headers: h, body: encoded);
      case 'PATCH':
        resp = await _client.patch(uri, headers: h, body: encoded);
      case 'PUT':
        resp = await _client.put(uri, headers: h, body: encoded);
      case 'DELETE':
        resp = await _client.delete(uri, headers: h, body: encoded);
      default:
        throw ApiException('不支持的 HTTP 方法: $method');
    }
    return _check(resp);
  }

  /// 校验响应；非 2xx 解析 `{"error": "..."}` 后抛 [ApiException]。
  http.Response _check(http.Response resp) {
    if (resp.statusCode >= 200 && resp.statusCode < 300) return resp;
    final text = _body(resp);
    String message = 'HTTP ${resp.statusCode}';
    try {
      final body = jsonDecode(text);
      if (body is Map && body['error'] != null) {
        message = body['error'].toString();
      } else if (body is Map && body['message'] != null) {
        message = body['message'].toString();
      } else if (text.trim().isNotEmpty) {
        message = text.trim();
      }
    } catch (_) {
      if (text.trim().isNotEmpty) message = text.trim();
    }
    throw ApiException(message, statusCode: resp.statusCode);
  }

  /// 按 UTF-8 解码响应体（后端 JSON 为 UTF-8，避免 content-type 缺 charset 时按 latin1 解析导致中文乱码）。
  String _body(http.Response resp) => utf8.decode(resp.bodyBytes);

  // ── 账号（系统级，无 X-User-Id）──

  /// `GET /api/v1/accounts` → 账号列表。
  Future<List<Account>> getAccounts() async {
    final resp = await _get('/api/v1/accounts');
    final list = jsonDecode(_body(resp)) as List;
    return list.map((e) => Account.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// `POST /api/v1/accounts` body `{userId, role}` → 200 / 400。
  Future<Account> createAccount({required String userId, required String role}) async {
    final resp = await _send('POST', '/api/v1/accounts',
        body: {'userId': userId, 'role': role});
    return Account.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `PATCH /api/v1/accounts/{userId}` body `{enabled?, role?, plugins?}` → 200 / 400 / 404。
  Future<Account> updateAccount(String userId,
      {bool? enabled, String? role, List<String>? plugins}) async {
    final body = <String, dynamic>{
      'enabled': ?enabled,
      'role': ?role,
      'plugins': ?plugins,
    };
    final resp = await _send('PATCH', '/api/v1/accounts/$userId', body: body);
    return Account.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `PATCH /api/v1/accounts/{userId}/plugins` body `{add[], remove[]}` → 200 / 400 / 404（S-R2 合并语义）。
  Future<Account> mergeAccountPlugins(String userId,
      {required List<String> add, required List<String> remove}) async {
    final resp = await _send('PATCH', '/api/v1/accounts/$userId/plugins',
        body: {'add': add, 'remove': remove});
    return Account.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `DELETE /api/v1/accounts/{userId}` → 204 / 400 / 404。
  Future<void> deleteAccount(String userId) async {
    await _send('DELETE', '/api/v1/accounts/$userId');
  }

  // ── 记录（per-user）──

  /// `POST /api/v1/records` body `{content, type?, tags?, intent?}`。
  Future<void> createRecord(String content,
      {String? type, List<String>? tags, String? intent}) async {
    await _send('POST', '/api/v1/records',
        headers: userHeaders,
        body: {
          'content': content,
          'type': ?type,
          if (tags != null && tags.isNotEmpty) 'tags': tags,
          'intent': ?intent,
        });
  }

  /// `DELETE /api/v1/records/{id}` → 204。
  Future<void> deleteRecord(String id) async {
    await _send('DELETE', '/api/v1/records/$id', headers: userHeaders);
  }

  /// `POST /api/v1/records/retry` → 重补缺失记忆。
  Future<RetryResultDto> triggerRecordRetry() async {
    final resp = await _send('POST', '/api/v1/records/retry', headers: userHeaders);
    return RetryResultDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `POST /api/v1/cards/cleanup` → 清理重复记录（维护操作「数据清理」）。
  Future<Map<String, dynamic>> cleanupCards() async {
    final resp = await _send('POST', '/api/v1/cards/cleanup', headers: userHeaders);
    return jsonDecode(_body(resp)) as Map<String, dynamic>;
  }

  // ── Feed（per-user）──

  /// `GET /api/v1/feed?page=&size=`。
  Future<FeedResponseDto> getFeed({String? date, int page = 0, int size = 50}) async {
    final query = <String, String>{
      'page': page.toString(),
      'size': size.toString(),
      'date': ?date,
    };
    final resp = await _get('/api/v1/feed', headers: userHeaders, query: query);
    return FeedResponseDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  // ── Memory（per-user）──

  /// `GET /api/v1/memory?date=yyyy-MM-dd`。
  Future<List<MemoryDto>> getMemory({String? date}) async {
    final query = <String, String>{'date': ?date};
    final resp = await _get('/api/v1/memory', headers: userHeaders, query: query);
    final list = jsonDecode(_body(resp)) as List;
    return list.map((e) => MemoryDto.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// `GET /api/v1/memory/dates` → 有记忆的日期列表。
  Future<List<String>> getMemoryDates() async {
    final resp = await _get('/api/v1/memory/dates', headers: userHeaders);
    final list = jsonDecode(_body(resp)) as List;
    return list.map((e) => e.toString()).toList();
  }

  /// `PATCH /api/v1/memory/{id}` body `{kind?, summary?, tags?, actionable?, suggestion?}`。
  Future<void> updateMemory(String id,
      {String? kind, String? summary, List<String>? tags, bool? actionable, String? suggestion}) async {
    await _send('PATCH', '/api/v1/memory/$id',
        headers: userHeaders,
        body: {
          'kind': ?kind,
          'summary': ?summary,
          'tags': ?tags,
          'actionable': ?actionable,
          'suggestion': ?suggestion,
        });
  }

  /// `PATCH /api/v1/memory/{id}/done`。
  Future<void> markMemoryDone(String id) async {
    await _send('PATCH', '/api/v1/memory/$id/done', headers: userHeaders);
  }

  /// `POST /api/v1/memory/rebuild?date=`。
  Future<RebuildResultDto> rebuildMemory({String? date}) async {
    final query = <String, String>{'date': ?date};
    final resp = await _send('POST', '/api/v1/memory/rebuild',
        headers: userHeaders, query: query);
    return RebuildResultDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  // ── Identity（per-user）──

  /// `GET /api/v1/identity`。
  Future<IdentityDto> getIdentity() async {
    final resp = await _get('/api/v1/identity', headers: userHeaders);
    if (_body(resp).trim().isEmpty) return const IdentityDto();
    return IdentityDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `PUT /api/v1/identity` 全量覆盖。
  Future<IdentityDto> updateIdentity({
    required String name,
    required Map<String, String> preferences,
    required Map<String, String> rules,
    required List<String> tags,
  }) async {
    final resp = await _send('PUT', '/api/v1/identity',
        headers: userHeaders,
        body: {
          'name': name,
          'preferences': preferences,
          'rules': rules,
          'tags': tags,
        });
    if (_body(resp).trim().isEmpty) return const IdentityDto();
    return IdentityDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  // ── Trading（per-user）──

  /// `GET /api/v1/trading/positions`。
  Future<List<PositionDto>> getPositions() async {
    final resp = await _get('/api/v1/trading/positions', headers: userHeaders);
    final list = jsonDecode(_body(resp)) as List;
    return list.map((e) => PositionDto.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// `GET /api/v1/trading/reviews` → 复盘日期列表。
  Future<List<String>> getReviewDates() async {
    final resp = await _get('/api/v1/trading/reviews', headers: userHeaders);
    final list = jsonDecode(_body(resp)) as List;
    return list.map((e) => e.toString()).toList();
  }

  /// `GET /api/v1/trading/review?date=` → 内容；404 返回 null。
  Future<String?> getReview(String date) async {
    try {
      final resp = await _get('/api/v1/trading/review',
          headers: userHeaders, query: {'date': date});
      final dto =
          ReviewDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
      return dto.content;
    } on ApiException catch (e) {
      if (e.statusCode == 404) return null;
      rethrow;
    }
  }

  /// `POST /api/v1/trading/review?date=` → 生成复盘。
  Future<String> generateReview(String date) async {
    final resp = await _send('POST', '/api/v1/trading/review',
        headers: userHeaders, query: {'date': date});
    final dto = ReviewDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
    return dto.content;
  }

  /// `GET /api/v1/trading/has-activity?date=`。
  Future<ActivityCheckDto> hasTradingActivity(String date) async {
    final resp = await _get('/api/v1/trading/has-activity',
        headers: userHeaders, query: {'date': date});
    return ActivityCheckDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `GET /api/v1/trading/knowledge/conflicts`。
  Future<ConflictsResponseDto> getConflicts() async {
    final resp = await _get('/api/v1/trading/knowledge/conflicts', headers: userHeaders);
    return ConflictsResponseDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `POST /api/v1/trading/reviews/{date}/promote` body `{note, sections}`。
  Future<PromoteResultDto> promoteReview(String date,
      {String? note, List<String>? sections}) async {
    final resp = await _send('POST', '/api/v1/trading/reviews/$date/promote',
        headers: userHeaders,
        body: {
          'note': ?note,
          'sections': ?sections,
        });
    return PromoteResultDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  // ── Tasks（per-user）──

  /// `GET /api/v1/project/tasks?status=`。
  Future<List<TaskDto>> getTasks({String? status}) async {
    final query = <String, String>{'status': ?status};
    final resp = await _get('/api/v1/project/tasks', headers: userHeaders, query: query);
    final list = jsonDecode(_body(resp)) as List;
    return list.map((e) => TaskDto.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// `POST /api/v1/project/tasks`。
  Future<TaskDto> createTask({
    required String title,
    String? description,
    String? priority,
    List<String>? tags,
  }) async {
    final resp = await _send('POST', '/api/v1/project/tasks',
        headers: userHeaders,
        body: {
          'title': title,
          'description': ?description,
          'priority': ?priority,
          'tags': ?tags,
        });
    return TaskDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `PUT /api/v1/project/tasks/{id}`。
  Future<TaskDto> updateTask(String id,
      {String? title, String? description, String? status, String? priority, List<String>? tags}) async {
    final body = <String, dynamic>{
      'title': ?title,
      'description': ?description,
      'status': ?status,
      'priority': ?priority,
      'tags': ?tags,
    };
    final resp = await _send('PUT', '/api/v1/project/tasks/$id',
        headers: userHeaders, body: body);
    return TaskDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `DELETE /api/v1/project/tasks/{id}`。
  Future<void> deleteTask(String id) async {
    await _send('DELETE', '/api/v1/project/tasks/$id', headers: userHeaders);
  }

  /// `GET /api/v1/project/tasks/stats`。
  Future<TaskStatsDto> getTaskStats() async {
    final resp = await _get('/api/v1/project/tasks/stats', headers: userHeaders);
    return TaskStatsDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  // ── Admin 浏览（系统级，无 X-User-Id）──

  /// `GET /api/v1/admin/files?path=` → data/ 目录条目。
  Future<List<AdminFileDto>> listFiles({String path = ''}) async {
    final resp = await _get('/api/v1/admin/files',
        query: {if (path.isNotEmpty) 'path': path});
    final list = jsonDecode(_body(resp)) as List;
    return list.map((e) => AdminFileDto.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// `GET /api/v1/admin/files/content?path=` → 文件内容。
  Future<AdminFileContentDto> getFileContent(String path) async {
    final resp = await _get('/api/v1/admin/files/content',
        query: {'path': path});
    return AdminFileContentDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// `GET /api/v1/admin/knowledge?domain=&path=` → os/ 目录条目。
  Future<List<AdminFileDto>> listKnowledge(
      {String domain = 'trading-os', String path = ''}) async {
    final resp = await _get('/api/v1/admin/knowledge',
        query: {'domain': domain, if (path.isNotEmpty) 'path': path});
    final list = jsonDecode(_body(resp)) as List;
    return list.map((e) => AdminFileDto.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// `GET /api/v1/admin/knowledge/content?path=` → os/ 文件内容。
  Future<AdminFileContentDto> getKnowledgeContent(String path) async {
    final resp = await _get('/api/v1/admin/knowledge/content',
        query: {'path': path});
    return AdminFileContentDto.fromJson(jsonDecode(_body(resp)) as Map<String, dynamic>);
  }

  /// 关闭底层 HTTP client（测试/生命周期收尾）。
  void close() => _client.close();
}
