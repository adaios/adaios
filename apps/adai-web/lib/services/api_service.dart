import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'api_config.dart';
import 'models/identity_models.dart';
import 'models/tag_models.dart';

/// AdaiOS API 客户端。
/// 封装所有后端调用，App 其他部分不直接调 HTTP。
/// 带超时的 http.Client 包装（REVIEW P1-W6：请求无超时 → waiting/loading 无限转圈）。
class _TimeoutClient extends http.BaseClient {
  _TimeoutClient(this._inner, this._timeout);

  final http.Client _inner;
  final Duration _timeout;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) =>
      _inner.send(request).timeout(_timeout);
}

class ApiService {
  final String baseUrl;

  /// 当前用户 ID（入口 `?userId=` 传入，默认 'default'）。
  final String userId;

  /// 底层 HTTP 客户端（可注入 mock，测试用；默认真实 client）。
  final http.Client _client;

  // 内存缓存：跨页面切换不丢；timeline/memory 按参数 key 区分（参数感知）
  TagsResponse? _tagsCache;
  final Map<String, List<TimelineEntryResponse>> _timelineCache = {};
  final Map<String, List<MemoryEntryResponse>> _memoryCache = {};

  ApiService({String? baseUrl, this.userId = 'default', http.Client? client})
      : baseUrl = baseUrl ?? ApiConfig.baseUrl,
        _client = client ?? _TimeoutClient(http.Client(), const Duration(seconds: 15));

  /// 获取今日 Brief（摘要），独立接口。
  Future<String> getBrief() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/brief'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return data['content'] as String? ?? '';
  }

  /// 获取 Feed 流（分页，只返回今天的数据）。
  Future<FeedResponse> getFeed({String? date, int page = 0, int size = 5}) async {
    final params = <String, String>{
      'page': page.toString(),
      'size': size.toString(),
    };
    if (date != null) params['date'] = date;

    final uri = Uri.parse('$baseUrl/api/v1/feed').replace(queryParameters: params);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    return FeedResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 更新记录的 domain。
  Future<void> updateRecordDomain(String id, String domain) async {
    final resp = await _client.patch(
      Uri.parse('$baseUrl/api/v1/records/$id/domain'),
      headers: _headers,
      body: jsonEncode({'domain': domain}),
    );
    _check(resp);
  }

  /// 标记行动类记忆为已完成（PATCH /api/v1/memory/{id}/done）。
  Future<void> markMemoryDone(String memoryId) async {
    final resp = await _client.patch(
      Uri.parse('$baseUrl/api/v1/memory/$memoryId/done'),
      headers: _headers,
    );
    _check(resp);
    // doneAt 变化 → 记忆缓存失效，防记忆页「待办」陈旧（#107）
    _memoryCache.clear();
  }

  /// 删除记录。
  Future<void> deleteRecord(String id) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/records/$id'),
      headers: _headers,
    );
    _check(resp);
    // 删除影响 tags/timeline/memory，全清（#107）
    _tagsCache = null;
    _timelineCache.clear();
    _memoryCache.clear();
  }

  /// 提交记录。
  Future<RecordResponse> createRecord(String content, {String? type, List<String>? tags, String? intent, String? cardId}) async {
    final body = {
      'content': content,
      'type': ?type,
      if (tags != null && tags.isNotEmpty) 'tags': tags,
      'intent': ?intent,
      'cardId': ?cardId,
    };
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/records'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    // 发送内容后缓存失效
    _tagsCache = null;
    _timelineCache.clear();
    _memoryCache.clear();
    return RecordResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 上传图片记录（多模态 L4）：multipart → VLM 理解 → 记录 + 记忆沉淀。
  Future<MediaRecordResponse> uploadImage({
    required List<int> bytes,
    required String filename,
    required String mimeType,
    String? caption,
  }) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/records/media'))
      ..headers['X-User-Id'] = userId
      ..fields['caption'] = caption ?? ''
      ..files.add(http.MultipartFile.fromBytes(
        'file',
        bytes,
        filename: filename,
        contentType: MediaType('image', mimeType.split('/').last),
      ));
    final streamed = await _client.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    // 上传后缓存失效（Feed/Timeline/Memory 都会有新图片记录）
    _tagsCache = null;
    _timelineCache.clear();
    _memoryCache.clear();
    return MediaRecordResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 图片追问（L4 图片问答）：就一张图片提问，返回 VLM 自然语言回答。
  Future<AskMediaResponse> askMedia({
    required String imageRecordId,
    required String question,
  }) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/records/media/$imageRecordId/ask'),
      headers: _headers,
      body: jsonEncode({'question': question}),
    );
    _check(resp);
    // #229：image_qa 记录带 tags → 标签云缓存也需失效，否则右栏标签陈旧
    _tagsCache = null;
    _timelineCache.clear();
    _memoryCache.clear();
    return AskMediaResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 多图问答（Phase 1 带图 ask，S-1 桌面端同步）：1-3 张已上传图片一次提问，
  /// VLM 综合多图回答，沉淀 image_qa 记录（引用全部图片 id，Q/A 合并到首图卡）。
  /// intent=question → answer 为回答；intent=log → 陈述句纯记录，不烧 VLM。
  Future<AskBatchResponse> askBatch({
    required List<String> imageRecordIds,
    required String question,
  }) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/records/media/ask-batch'),
      headers: _headers,
      body: jsonEncode({'imageRecordIds': imageRecordIds, 'question': question}),
    );
    _check(resp);
    // image_qa 记录带 tags → 标签云缓存也需失效（对齐 askMedia #229）
    _tagsCache = null;
    _timelineCache.clear();
    _memoryCache.clear();
    return AskBatchResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 获取时间线（按参数 key 缓存；[force] 绕过缓存强制刷新，#103 保活页刷新用）。
  Future<List<TimelineEntryResponse>> getTimeline({String? type, int limit = 50, bool force = false}) async {
    final key = 'type=$type&limit=$limit';
    if (!force && _timelineCache.containsKey(key)) return _timelineCache[key]!;
    final params = <String, String>{};
    if (type != null) params['type'] = type;
    if (limit != 50) params['limit'] = limit.toString();

    final uri = Uri.parse('$baseUrl/api/v1/timeline').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(utf8.decode(resp.bodyBytes));
    final result = raw.map((e) => TimelineEntryResponse.fromJson(e)).toList();
    _timelineCache[key] = result;
    return result;
  }

  /// 结束会话。
  Future<EndConversationResponse> endConversation(List<String> turns, {String? cardId}) async {
    final body = {
      'turns': turns,
      'cardId': ?cardId,
    };
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/conversations/end'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    // 结束对话产出总结/标签 → 标签云/时间线/记忆缓存失效（#115 右栏联动刷新）
    _tagsCache = null;
    _timelineCache.clear();
    _memoryCache.clear();
    return EndConversationResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 读取个人档案。
  Future<IdentityResponse> getIdentity() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/identity'),
      headers: _headers,
    );
    _check(resp);
    return IdentityResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 更新个人档案。
  Future<IdentityResponse> updateIdentity(IdentityRequest request) async {
    // #243：走注入的 _client（MockClient 可拦截），不用全局 http.put（widget 测试真实 HTTP 恒 400）
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/identity'),
      headers: _headers,
      body: jsonEncode(request.toJson()),
    );
    _check(resp);
    return IdentityResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 获取所有标签统计（自动缓存）。
  Future<TagsResponse> getTags() async {
    if (_tagsCache != null) return _tagsCache!;
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/tags'),
      headers: _headers,
    );
    _check(resp);
    _tagsCache = TagsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
    return _tagsCache!;
  }

  /// 获取某日的记忆列表（按参数 key 缓存；[force] 绕过缓存强制刷新，#103 保活页刷新用）。
  Future<List<MemoryEntryResponse>> getMemory({String? date, bool force = false}) async {
    final key = 'date=${date ?? ''}';
    if (!force && _memoryCache.containsKey(key)) return _memoryCache[key]!;
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/memory').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(utf8.decode(resp.bodyBytes));
    final result = raw.map((e) => MemoryEntryResponse.fromJson(e)).toList();
    _memoryCache[key] = result;
    return result;
  }

  /// 获取记忆总条数。
  Future<int> getMemoryCount() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/memory/count'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return data['count'] as int;
  }

  /// 获取有记忆的所有日期。
  Future<List<String>> getMemoryDates() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/memory/dates'),
      headers: _headers,
    );
    _check(resp);
    final List raw = jsonDecode(utf8.decode(resp.bodyBytes));
    return raw.map((e) => e as String).toList();
  }

  /// 全文搜索。
  Future<SearchResponse> search(String query) async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/search').replace(queryParameters: {'q': query}),
      headers: _headers,
    );
    _check(resp);
    return SearchResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 获取项目状态。
  Future<ProjectStatusResponse> getProjectStatus() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/project/status'),
      headers: _headers,
    );
    _check(resp);
    return ProjectStatusResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  // ── 交易 API ──

  /// 查询当前持仓。
  Future<PositionsResponse> getPositions() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/positions'),
      headers: _headers,
    );
    _check(resp);
    return PositionsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 查询投资组合快照。
  Future<PortfolioSnapshotResponse> getPortfolio() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/portfolio'),
      headers: _headers,
    );
    _check(resp);
    return PortfolioSnapshotResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 记录一笔交易（RFC 20260816：BUY 必填止损位/买点，targetPrice/reason 可选）。
  Future<PositionsResponse> recordTrade({
    required String symbol,
    required String name,
    required String direction,
    required double price,
    required int volume,
    double? stopLossPrice,
    String? buyPoint,
    double? targetPrice,
    String? reason,
  }) async {
    final body = {
      'symbol': symbol,
      'name': name,
      'direction': direction,
      'price': price,
      'volume': volume,
      'stopLossPrice': ?stopLossPrice,
      'buyPoint': ?(buyPoint == null || buyPoint.isEmpty ? null : buyPoint),
      'targetPrice': ?targetPrice,
      'reason': ?(reason == null || reason.isEmpty ? null : reason),
    };
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/trades'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return PositionsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 更新持仓（web 独有详细管理，RFC 20260816 §4.2）：role/止损位/目标价。
  /// PUT /api/v1/trading/positions/{symbol}，body 只带非空字段 → 返回更新后持仓。
  Future<PositionItem> updatePosition(
    String symbol, {
    String? role,
    double? stopLossPrice,
    double? targetPrice,
  }) async {
    final body = <String, dynamic>{
      'role': ?(role == null || role.isEmpty ? null : role),
      'stopLossPrice': ?stopLossPrice,
      'targetPrice': ?targetPrice,
    };
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/positions/$symbol'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    // 契约：返回更新后持仓对象；兼容返回数组（取首条）的宽松解析。
    if (data is List) {
      return PositionItem.fromJson(data.first as Map<String, dynamic>);
    }
    return PositionItem.fromJson(data as Map<String, dynamic>);
  }

  /// 批量导入交易（web 独有，RFC 20260816 §4.2）。
  /// POST /api/v1/trading/trades/batch，body {"trades": [...]} → 逐条成功/失败结果。
  Future<BatchImportResponse> importTrades(List<Map<String, dynamic>> trades) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/trades/batch'),
      headers: _headers,
      body: jsonEncode({'trades': trades}),
    );
    _check(resp);
    return BatchImportResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 按代码查询名称（代码输入带出 + 二次确认，2026-08-16）。
  /// GET /api/v1/trading/lookup?symbol= → 名称；失败/空返回 null。
  Future<String?> lookupSymbol(String symbol) async {
    try {
      final uri = Uri.parse('$baseUrl/api/v1/trading/lookup')
          .replace(queryParameters: {'symbol': symbol});
      final resp = await _client.get(uri, headers: _headers);
      if (resp.statusCode != 200) return null;
      final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
      final name = data['name'] as String?;
      return (name != null && name.isNotEmpty) ? name : null;
    } catch (_) {
      return null;
    }
  }

  /// 持仓初始化导入（通达信导出 → 持仓快照，2026-08-16）。
  /// POST /api/v1/trading/positions/import → {imported, missingStopLoss}.
  Future<PositionImportResult> importPositions(List<Map<String, dynamic>> items) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/positions/import'),
      headers: _headers,
      body: jsonEncode(items),
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return PositionImportResult.fromJson(data);
  }
  /// 账户总体快照（GET /api/v1/trading/account：资产/可用/可取/参考市值/当日盈亏/盈亏）。
  Future<AccountSnapshotDto> getAccount() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/account'), headers: _headers);
    _check(resp);
    return AccountSnapshotDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 自选股列表（GET /api/v1/trading/watchlist，RFC 20260816）。
  Future<List<WatchlistItemDto>> getWatchlist() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/watchlist'), headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => WatchlistItemDto.fromJson(e)).toList();
  }

  /// 自选股导入（POST /api/v1/trading/watchlist/import，通达信导出文本）。
  Future<int> importWatchlist(String content) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/watchlist/import'),
      headers: _headers, body: jsonEncode({'content': content}));
    _check(resp);
    final d = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (d['imported'] as num?)?.toInt() ?? 0;
  }

  /// 删除自选股（DELETE /api/v1/trading/watchlist/{symbol}）。
  Future<void> removeWatchlist(String symbol) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/trading/watchlist/$symbol'), headers: _headers);
    _check(resp);
  }

  /// 清仓股列表（GET /api/v1/trading/sold，复盘闭环）。
  Future<List<SoldTradeDto>> getSold() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/sold'), headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => SoldTradeDto.fromJson(e)).toList();
  }

  /// 清仓股导入（POST /api/v1/trading/sold/import，通达信导出文本）。
  Future<int> importSold(String content) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/sold/import'),
      headers: _headers, body: jsonEncode({'content': content}));
    _check(resp);
    final d = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (d['imported'] as num?)?.toInt() ?? 0;
  }

  /// 清仓股心理标注（PUT /api/v1/trading/sold/{symbol}/psychology）。
  Future<void> updateSoldPsychology(String symbol, String psychology) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/sold/$symbol/psychology'),
      headers: _headers, body: jsonEncode({'psychology': psychology}));
    _check(resp);
  }

  /// 资金股份查询导入（POST /api/v1/trading/imports/cash：现金 + 精确成本）。
  Future<CashImportResult> importCash(String content) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/imports/cash'),
      headers: _headers, body: jsonEncode({'content': content}));
    _check(resp);
    final d = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return CashImportResult.fromJson(d);
  }

  /// 导入文件上传留存（通达信导出，2026-08-16）。
  /// POST /api/v1/trading/imports/save（multipart file）→ {path, content}（GBK 已转 UTF-8）。
  Future<ImportFileSaveResult> saveImportFile(String filename, List<int> bytes) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/trading/imports/save'))
      ..headers.addAll(_headers)
      ..files.add(http.MultipartFile.fromBytes('file', bytes, filename: filename));
    final streamed = await _client.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return ImportFileSaveResult.fromJson(data);
  }

  /// 交易历史逐笔流水（web 独有，RFC 20260816 §4.2）。
  /// GET /api/v1/trading/trades?from&to（yyyy-MM-dd，可选）→ TradeRecord 列表。
  Future<List<TradeRecordItem>> getTrades({String? from, String? to}) async {
    final params = <String, String>{};
    if (from != null) params['from'] = from;
    if (to != null) params['to'] = to;
    final uri = Uri.parse('$baseUrl/api/v1/trading/trades')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    final list = (data is List) ? data : (data['trades'] as List?) ?? [];
    return list.map((e) => TradeRecordItem.fromJson(e)).toList();
  }

  /// 生成交易复盘（POST /api/v1/trading/review，AI 生成 → 写入 data/trading/reviews/）。
  Future<ReviewResponse> generateReview({String? date}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/trading/review')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.post(uri, headers: _headers);
    _check(resp);
    return ReviewResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 获取指定日期复盘（无复盘返回 null，GET 404）。
  Future<ReviewResponse?> getReview({String? date}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/trading/review')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    if (resp.statusCode == 404) return null;
    _check(resp);
    return ReviewResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 列出所有复盘日期（GET /api/v1/trading/reviews）。
  Future<List<String>> getReviewDates() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/reviews'), headers: _headers);
    _check(resp);
    final raw = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
    return raw.map((e) => e.toString()).toList();
  }

  /// 复盘内容提升为入库候选（POST /api/v1/trading/reviews/{date}/promote，#129 前端入口）。
  /// 写入 os/trading-os/99-inbox/，返回带 message 提示（#178：不自动融入 AI context）。
  Future<PromoteResponse> promoteReview({required String date}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/reviews/$date/promote'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({}),
    );
    _check(resp);
    return PromoteResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  // ── 账号 API ──

  /// 可用账号列表（v1.0.0 多账号选号；无鉴权端点，仅返回 enabled 账号的 userId 最小集）。
  Future<List<String>> getAvailableAccounts() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/accounts/available'),
      headers: _headers,
    );
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
    return list.map((e) => e.toString()).toList();
  }

  /// 当前用户启用插件列表（RFC 20260814 Domain=插件模型；如 ["trading","project"]，
  /// 新用户为空 → 桌面壳按此显隐插件模块：交易/项目）。基础服务模块不依赖此列表。
  Future<List<String>> getMyPlugins() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/me/plugins'),
      headers: _headers,
    );
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
    return list.map((e) => e.toString()).toList();
  }

  // ── 任务 API ──

  /// 获取任务列表。
  Future<List<TaskResponse>> getTasks({String? status, String? tag}) async {
    final params = <String, String>{};
    if (status != null) params['status'] = status;
    if (tag != null) params['tag'] = tag;
    final uri = Uri.parse('$baseUrl/api/v1/project/tasks')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
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
      'description': ?description,
      'priority': ?priority,
      'tags': ?tags,
      'rfcRef': ?rfcRef,
    };
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/project/tasks'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return TaskResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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
    // #243：走注入的 _client（MockClient 可拦截），不用全局 http.put（widget 测试真实 HTTP 恒 400）
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/project/tasks/$id'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return TaskResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 删除任务。
  Future<void> deleteTask(String id) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/project/tasks/$id'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 获取任务统计。
  Future<TaskStatsResponse> getTaskStats() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/project/tasks/stats'),
      headers: _headers,
    );
    _check(resp);
    return TaskStatsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  Map<String, String> get _headers => {
    'Content-Type': 'application/json',
    // 多账号：所有请求带当前用户（后端 FileStorage 按 userId 隔离）
    'X-User-Id': userId,
  };

  /// 图片记录原图 URL（供 Image.network 渲染缩略图/点击看原图）。
  String mediaUrl(String recordId) => '$baseUrl/api/v1/records/media/$recordId';

  /// 媒体请求鉴权头（Image.network 需要显式传入）。
  Map<String, String> get mediaHeaders => {'X-User-Id': userId};

  void _check(http.Response resp) {
    if (resp.statusCode >= 400) {
      // #118：resp.body 按 latin1 解码中文会乱码，改 utf8 解码 bodyBytes
      throw ApiException(resp.statusCode, 'API 请求失败（HTTP ${resp.statusCode}）', utf8.decode(resp.bodyBytes));
    }
  }
}

/// API 自定义异常：携带 statusCode 与后端返回体，UI 层可按状态码区分处理。
class ApiException implements Exception {
  final int statusCode;
  final String message;
  final String? body;

  ApiException(this.statusCode, this.message, [this.body]);

  @override
  String toString() => 'ApiException($statusCode): $message';
}

// ── Feed entry type constants ──

class FeedEntryType {
  static const String record = 'record';
  static const String card = 'card';
  static const String aiNote = 'ai_note';
  static const String push = 'push';
  static const String action = 'action'; // 未完成行动提醒（记忆进化 Phase 3）
  static const String market = 'market'; // 大盘行情条（v0.2.0 L5）
}

// ── DTO ──

/// 图片记录响应 DTO（多模态 L4）。
class MediaRecordResponse {
  final String recordId;
  final String intent;
  final String summary;
  final List<String> tags;
  final String mediaPath;

  MediaRecordResponse({
    required this.recordId,
    required this.intent,
    required this.summary,
    required this.tags,
    required this.mediaPath,
  });

  factory MediaRecordResponse.fromJson(Map<String, dynamic> json) =>
      MediaRecordResponse(
        recordId: json['recordId'] as String? ?? '',
        intent: json['intent'] as String? ?? 'log',
        summary: json['summary'] as String? ?? '',
        tags: (json['tags'] as List?)?.cast<String>() ?? [],
        mediaPath: json['mediaPath'] as String? ?? '',
      );
}

/// 图片追问响应 DTO（L4 图片问答）。
class AskMediaResponse {
  final String recordId;
  final String answer;
  final String imageRecordId;

  AskMediaResponse({
    required this.recordId,
    required this.answer,
    required this.imageRecordId,
  });

  factory AskMediaResponse.fromJson(Map<String, dynamic> json) =>
      AskMediaResponse(
        recordId: json['recordId'] as String? ?? '',
        answer: json['answer'] as String? ?? '',
        imageRecordId: json['imageRecordId'] as String? ?? '',
      );
}

/// 多图问答响应 DTO（Phase 1 带图 ask，S-1 桌面端同步）。
/// intent=question → answer 为 VLM 综合回答；intent=log → 陈述句纯记录。
class AskBatchResponse {
  final String intent;
  final String answer;
  final String recordId;
  final List<String> imageRecordIds;

  AskBatchResponse({
    required this.intent,
    required this.answer,
    required this.recordId,
    required this.imageRecordIds,
  });

  factory AskBatchResponse.fromJson(Map<String, dynamic> json) =>
      AskBatchResponse(
        intent: json['intent'] as String? ?? 'log',
        answer: json['answer'] as String? ?? '',
        recordId: json['recordId'] as String? ?? '',
        imageRecordIds: (json['imageRecordIds'] as List?)?.cast<String>() ?? [],
      );
}

class FeedResponse {
  final List<FeedEntryResponse> entries;
  final int totalToday;

  FeedResponse({required this.entries, required this.totalToday});

  factory FeedResponse.fromJson(Map<String, dynamic> json) => FeedResponse(
    entries: (json['entries'] as List).map((e) => FeedEntryResponse.fromJson(e)).toList(),
    totalToday: json['totalToday'] as int? ?? 0,
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
  final String date; // MM-dd，每张卡片都带（批2 每卡日期）
  final String? mediaPath; // 图片记录才有（批2 原图可见）
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
    this.date = '',
    this.mediaPath,
    this.intent,
    this.summary,
    this.turns,
    this.domain = 'life',
  });

  factory FeedEntryResponse.fromJson(Map<String, dynamic> json) => FeedEntryResponse(
    type: json['type'] as String? ?? '',
    id: json['id'] as String,
    sourceRecordId: json['sourceRecordId'] as String?,
    title: json['title'] as String? ?? '',
    content: json['content'] as String,
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    time: json['time'] as String? ?? json['timeString'] as String? ?? '',
    date: json['date'] as String? ?? '',
    mediaPath: json['mediaPath'] as String?,
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
  final String? rawResponse;
  final String domain;

  RecordResponse({required this.intent, this.recordId, this.summary, this.tags, this.content, this.rawResponse, this.domain = 'life'});

  factory RecordResponse.fromJson(Map<String, dynamic> json) {
    final intent = json['intent'] as String? ?? 'log';
    return RecordResponse(
      intent: intent,
      recordId: json['recordId'] as String?,
      summary: json['summary'] as String?,
      tags: (json['tags'] as List?)?.cast<String>(),
      content: json['content'] as String?,
      rawResponse: json['rawResponse'] as String?,
      domain: json['domain'] as String? ?? 'life',
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
  final String? mediaPath; // 图片记录才有（批2 原图可见）

  TimelineEntryResponse({required this.id, required this.type, required this.title, required this.tags, required this.dateTime, this.mediaPath});

  factory TimelineEntryResponse.fromJson(Map<String, dynamic> json) => TimelineEntryResponse(
    id: json['id'] as String,
    type: json['type'] as String? ?? 'note',
    title: json['title'] as String? ?? '',
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    dateTime: json['dateTime'] as String? ?? '',
    mediaPath: json['mediaPath'] as String?,
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

/// 记忆条目 DTO（记忆进化 Phase 1-5 全字段）
class MemoryEntryResponse {
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

  MemoryEntryResponse({
    required this.id,
    required this.recordId,
    required this.summary,
    required this.tags,
    required this.sentiment,
    required this.createdAt,
    this.kind = 'insight',
    this.topic,
    this.superseded = false,
    this.evolvedTo,
    this.actionable = false,
    this.suggestion,
    this.doneAt,
  });

  factory MemoryEntryResponse.fromJson(Map<String, dynamic> json) => MemoryEntryResponse(
    id: json['id'] as String? ?? '',
    recordId: json['recordId'] as String? ?? '',
    kind: json['kind'] as String? ?? 'insight',
    summary: json['summary'] as String? ?? '',
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    sentiment: json['sentiment'] as String? ?? 'neutral',
    createdAt: json['createdAt'] as String? ?? '',
    topic: json['topic'] as String?,
    superseded: json['superseded'] as bool? ?? false,
    evolvedTo: json['evolvedTo'] as String?,
    actionable: json['actionable'] as bool? ?? false,
    suggestion: json['suggestion'] as String?,
    doneAt: json['doneAt'] as String?,
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
  // REVIEW #247：Integer 可空——endpoints.txt 资源缺失时后端返回 null，
  // 前端据此显示「未知」，不与「真 0 个端点」混淆。
  final int? apiEndpoints;

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
        apiEndpoints: json['apiEndpoints'] as int?,
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
  // RFC 20260816：持仓详细管理字段（后端 P0 落盘，web P1 编辑；旧数据缺失 → null 兜底）
  final String? entryDate; // 首买日 yyyy-MM-dd
  final double? stopLossPrice; // 止损位
  final String? buyPoint; // 买点类型（B1/B2/...）
  final String? role; // 防守/前锋/中场/机动 + 主仓/副仓
  final double? targetPrice; // 目标价

  PositionItem({
    required this.symbol,
    required this.name,
    required this.quantity,
    required this.avgCost,
    required this.currentPrice,
    required this.marketValue,
    required this.pnl,
    required this.pnlPercent,
    this.entryDate,
    this.stopLossPrice,
    this.buyPoint,
    this.role,
    this.targetPrice,
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
    entryDate: json['entryDate'] as String?,
    stopLossPrice: (json['stopLossPrice'] as num?)?.toDouble(),
    buyPoint: json['buyPoint'] as String?,
    role: json['role'] as String?,
    targetPrice: (json['targetPrice'] as num?)?.toDouble(),
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

/// 复盘响应 DTO（GET/POST /api/v1/trading/review）。
class ReviewResponse {
  final String date; // yyyy-MM-dd
  final String content; // markdown 复盘内容

  ReviewResponse({required this.date, required this.content});

  factory ReviewResponse.fromJson(Map<String, dynamic> json) => ReviewResponse(
    date: json['date'] as String? ?? '',
    content: json['content'] as String? ?? '',
  );
}

/// 交易历史逐笔流水 DTO（RFC 20260816 §2.1 TradeRecord，GET /api/v1/trading/trades）。
class TradeRecordItem {
  final String id;
  final String symbol;
  final String name;
  final String direction; // BUY/SELL
  final double price;
  final int volume;
  final double amount; // price × volume
  final String entryDate; // yyyy-MM-dd
  final double? stopLossPrice; // BUY 必填，SELL 可空
  final String? buyPoint;
  final double? targetPrice;
  final String? reason;

  TradeRecordItem({
    required this.id,
    required this.symbol,
    required this.name,
    required this.direction,
    required this.price,
    required this.volume,
    required this.amount,
    required this.entryDate,
    this.stopLossPrice,
    this.buyPoint,
    this.targetPrice,
    this.reason,
  });

  bool get isBuy => direction.toUpperCase() == 'BUY';

  factory TradeRecordItem.fromJson(dynamic json) {
    final map = json is Map<String, dynamic> ? json : <String, dynamic>{};
    // 日期字段宽松解析：entryDate / date / timestamp（timestamp 取日期部分）
    final rawDate = map['entryDate'] as String? ??
        map['date'] as String? ??
        map['timestamp'] as String? ??
        '';
    return TradeRecordItem(
      id: map['id'] as String? ?? '',
      symbol: map['symbol'] as String? ?? '',
      name: map['name'] as String? ?? '',
      direction: (map['direction'] as String? ?? '').toUpperCase(),
      price: (map['price'] as num?)?.toDouble() ?? 0,
      volume: map['volume'] as int? ?? 0,
      amount: (map['amount'] as num?)?.toDouble() ??
          ((map['price'] as num?)?.toDouble() ?? 0) * (map['volume'] as int? ?? 0),
      entryDate: rawDate.length >= 10 ? rawDate.substring(0, 10) : rawDate,
      stopLossPrice: (map['stopLossPrice'] as num?)?.toDouble(),
      buyPoint: map['buyPoint'] as String?,
      targetPrice: (map['targetPrice'] as num?)?.toDouble(),
      reason: map['reason'] as String?,
    );
  }
}

/// 账户总体快照（资金股份查询导入，券商口径）。
class AccountSnapshotDto {
  final double assets, cash, available, withdrawable, marketValue, pnl, todayPnl;
  final String snapshotDate;

  AccountSnapshotDto({required this.assets, required this.cash, required this.available,
      required this.withdrawable, required this.marketValue, required this.pnl,
      required this.todayPnl, required this.snapshotDate});

  factory AccountSnapshotDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return AccountSnapshotDto(
      assets: (m['assets'] as num?)?.toDouble() ?? 0,
      cash: (m['cash'] as num?)?.toDouble() ?? 0,
      available: (m['available'] as num?)?.toDouble() ?? 0,
      withdrawable: (m['withdrawable'] as num?)?.toDouble() ?? 0,
      marketValue: (m['marketValue'] as num?)?.toDouble() ?? 0,
      pnl: (m['pnl'] as num?)?.toDouble() ?? 0,
      todayPnl: (m['todayPnl'] as num?)?.toDouble() ?? 0,
      snapshotDate: m['snapshotDate']?.toString() ?? '',
    );
  }
}

/// 自选股条目（通达信形态/指标为买点判定原料）。
class WatchlistItemDto {
  final String symbol, name, industry, industry2, signal;
  final int longForm, midForm, shortForm;
  final String addedAt;

  WatchlistItemDto({required this.symbol, required this.name, required this.industry,
      required this.industry2, required this.longForm, required this.midForm,
      required this.shortForm, required this.signal, required this.addedAt});

  factory WatchlistItemDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return WatchlistItemDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      industry: m['industry']?.toString() ?? '',
      industry2: m['industry2']?.toString() ?? '',
      longForm: (m['longForm'] as num?)?.toInt() ?? 0,
      midForm: (m['midForm'] as num?)?.toInt() ?? 0,
      shortForm: (m['shortForm'] as num?)?.toInt() ?? 0,
      signal: m['signal']?.toString() ?? '',
      addedAt: m['addedAt']?.toString() ?? '',
    );
  }
}

/// 清仓股（B/S 复盘闭环）。
class SoldTradeDto {
  final String symbol, name, tradeCount, verdict, psychology;
  final String? buyDate, sellDate;
  final int holdDays;
  final double holdPnlPct;

  SoldTradeDto({required this.symbol, required this.name, required this.buyDate,
      required this.sellDate, required this.holdDays, required this.tradeCount,
      required this.holdPnlPct, required this.verdict, required this.psychology});

  factory SoldTradeDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return SoldTradeDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      buyDate: m['buyDate']?.toString(),
      sellDate: m['sellDate']?.toString(),
      holdDays: (m['holdDays'] as num?)?.toInt() ?? 0,
      tradeCount: m['tradeCount']?.toString() ?? '',
      holdPnlPct: (m['holdPnlPct'] as num?)?.toDouble() ?? 0,
      verdict: m['verdict']?.toString() ?? '',
      psychology: m['psychology']?.toString() ?? '',
    );
  }
}

/// 资金查询导入结果。
class CashImportResult {
  final double cash, assets;
  final int updatedCost;

  CashImportResult({required this.cash, required this.assets, required this.updatedCost});

  factory CashImportResult.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return CashImportResult(
      cash: (m['cash'] as num?)?.toDouble() ?? 0,
      assets: (m['assets'] as num?)?.toDouble() ?? 0,
      updatedCost: (m['updatedCost'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 导入文件留存结果（POST /api/v1/trading/imports/save）。
class ImportFileSaveResult {
  final String path;
  final String content;

  ImportFileSaveResult({required this.path, required this.content});

  factory ImportFileSaveResult.fromJson(dynamic json) {
    if (json is! Map<String, dynamic>) {
      return ImportFileSaveResult(path: '', content: '');
    }
    return ImportFileSaveResult(
      path: json['path']?.toString() ?? '',
      content: json['content']?.toString() ?? '',
    );
  }
}

/// 持仓初始化导入结果（POST /api/v1/trading/positions/import，通达信）。
class PositionImportResult {
  final int imported;
  final List<String> missingStopLoss;

  PositionImportResult({required this.imported, required this.missingStopLoss});

  factory PositionImportResult.fromJson(dynamic json) {
    if (json is! Map<String, dynamic>) {
      return PositionImportResult(imported: 0, missingStopLoss: []);
    }
    return PositionImportResult(
      imported: (json['imported'] as num?)?.toInt() ?? 0,
      missingStopLoss: ((json['missingStopLoss'] as List?) ?? [])
          .map((e) => e.toString())
          .toList(),
    );
  }
}

/// 批量导入结果 DTO（POST /api/v1/trading/trades/batch）。
/// 契约：逐条成功/失败结果；失败项带原始行号 + 人话原因。
class BatchImportResponse {
  final int success; // 成功条数
  final List<BatchImportFailure> failures;

  BatchImportResponse({required this.success, required this.failures});

  bool get hasFailures => failures.isNotEmpty;

  factory BatchImportResponse.fromJson(dynamic json) {
    if (json is! Map<String, dynamic>) {
      return BatchImportResponse(success: 0, failures: []);
    }
    // 宽松解析：success / successCount / ok 均可；失败项 failures / errors 均可。
    final success = (json['success'] as num?)?.toInt() ??
        (json['successCount'] as num?)?.toInt() ??
        (json['ok'] as num?)?.toInt() ??
        0;
    final rawFailures = (json['failures'] as List?) ?? (json['errors'] as List?) ?? [];
    return BatchImportResponse(
      success: success,
      failures: rawFailures
          .map((e) => BatchImportFailure.fromJson(e))
          .where((f) => f.message.isNotEmpty)
          .toList(),
    );
  }
}

/// 批量导入失败项：行号（从 1 起）+ 人话原因。
class BatchImportFailure {
  final int row; // 原始行号（1-based）；未知为 0
  final String symbol;
  final String message;

  BatchImportFailure({this.row = 0, this.symbol = '', required this.message});

  factory BatchImportFailure.fromJson(dynamic json) {
    final map = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return BatchImportFailure(
      row: (map['row'] as num?)?.toInt() ??
          (map['line'] as num?)?.toInt() ??
          (map['index'] as num?)?.toInt() ??
          0,
      symbol: map['symbol'] as String? ?? '',
      message: map['message'] as String? ??
          map['error'] as String? ??
          map['reason'] as String? ??
          '',
    );
  }
}

/// 反哺入库候选响应（POST /api/v1/trading/reviews/{date}/promote，#129）。
class PromoteResponse {
  final String status;
  final String path; // 99-inbox/ 候选文件路径
  final String message; // #178 融合提示

  PromoteResponse({required this.status, required this.path, required this.message});

  factory PromoteResponse.fromJson(Map<String, dynamic> json) => PromoteResponse(
    status: json['status'] as String? ?? '',
    path: json['path'] as String? ?? '',
    message: json['message'] as String? ?? '',
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
