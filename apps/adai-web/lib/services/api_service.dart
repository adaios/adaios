import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'api_config.dart';
import 'models/identity_models.dart';
import 'models/tag_models.dart';
import 'sse_client.dart';

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

  /// 当前用户 ID（入口 `?userId=` 传入，默认 'default'；登录后由会话决定，后端覆盖）。
  final String userId;

  /// 登录会话 token（RFC 20260901-auth-login；null = 未登录）。
  final String? token;

  /// 401（会话失效/未登录）全局回调：前端清 token 并跳登录页。
  final void Function()? onUnauthorized;

  /// 底层 HTTP 客户端（可注入 mock，测试用；默认真实 client）。
  final http.Client _client;

  /// AI 生成类请求专用客户端：DeepSeek 聊天/追问/总结实测 7~27s（2026-08-20 压测），
  /// 默认 15s 超时必误杀——聊天报错且重试仍报错的根因。AI 端点放宽到 120s
  /// （2026-08-26 对齐后端最坏 90.6s = 45s×2+0.6s，REVIEW S-9：原 90s < 后端 120.6s 导致
  /// 前端先超时断开 → 用户重发 → 卡片重复，S-9 关闭）。
  final http.Client _aiClient;

  /// SSE 流式客户端（ask-stream 专用，见 [askStream]）。
  final SseClient _sse;

  // 内存缓存：跨页面切换不丢；timeline/memory 按参数 key 区分（参数感知）
  TagsResponse? _tagsCache;
  final Map<String, List<TimelineEntryResponse>> _timelineCache = {};
  final Map<String, List<MemoryEntryResponse>> _memoryCache = {};

  ApiService({String? baseUrl, this.userId = 'default', this.token, this.onUnauthorized,
      http.Client? client, SseClient? sseClient})
      : baseUrl = baseUrl ?? ApiConfig.baseUrl,
        _client = client ?? _TimeoutClient(http.Client(), const Duration(seconds: 15)),
        _aiClient = client ?? _TimeoutClient(http.Client(), const Duration(seconds: 120)),
        _sse = sseClient ?? SseClient(httpClient: client);

  /// 获取今日 Brief（摘要），独立接口。
  /// AI 生成可能 7~27s（缓存命中时秒回）——走 _aiClient 防误杀。
  Future<String> getBrief() async {
    final resp = await _aiClient.get(
      Uri.parse('$baseUrl/api/v1/brief'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return data['content'] as String? ?? '';
  }

  /// 只取 5 分钟内的缓存 Brief（GET /api/v1/brief/cached），不触发 AI 生成。
  /// 主页首屏用它避免被 AI 生成阻塞；空串表示缓存过期，调用方再异步调 [getBrief] 补全。
  Future<String> getBriefCached() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/brief/cached'),
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
  /// 2026-08-20：聊天（intent=question / cardId 续聊）走 _aiClient——DeepSeek 回答 7~27s，
  /// 15s 默认超时必误杀（聊天报错根因）；纯 log 陈述走常规客户端。
  Future<RecordResponse> createRecord(String content, {String? type, List<String>? tags, String? intent, String? cardId}) async {
    final body = {
      'content': content,
      'type': ?type,
      if (tags != null && tags.isNotEmpty) 'tags': tags,
      'intent': ?intent,
      'cardId': ?cardId,
    };
    final aiHeavy = intent == 'question' || cardId != null;
    final resp = await (aiHeavy ? _aiClient : _client).post(
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

  /// 流式问答（ai-calling-governance 批 2，REVIEW P2-用户2）：POST /records/ask-stream。
  /// [onDelta] 逐段回调已剥离 JSON 回执的正文增量（流式草稿边到边显示）；完成后返回与
  /// [createRecord] 同构的 RecordResponse（rawResponse=最终正文，meta 定稿）。
  /// 降级（ai-calling-governance §⑤）：流开始前失败（HTTP 非 200 / 不支持流式）→
  /// 自动回退旧同步端点一次；已收到增量后的中途失败 → 原样抛出（前端保留草稿可重试）。
  Future<RecordResponse> askStream(
    String content, {
    String? cardId,
    String? intent,
    void Function(String partial)? onDelta,
  }) async {
    RecordResponse? meta;
    var receivedDelta = false;
    try {
      await _sse.post(
        Uri.parse('$baseUrl/api/v1/records/ask-stream'),
        headers: _headers,
        body: {'content': content, 'intent': ?intent, 'cardId': ?cardId},
        onData: (data) {
          if (data == '[DONE]') return;
          final event = jsonDecode(data) as Map<String, dynamic>;
          switch (event['type'] as String?) {
            case 'text':
              receivedDelta = true;
              onDelta?.call(event['content'] as String? ?? '');
            case 'meta':
              meta = RecordResponse(
                intent: 'question',
                recordId: event['recordId'] as String?,
                summary: event['summary'] as String?,
                tags: (event['tags'] as List?)?.cast<String>(),
                rawResponse: event['content'] as String?,
                domain: event['domain'] as String? ?? 'life',
              );
            case 'error':
              throw SseServerException(event['message'] as String? ?? '回答失败，请重试');
          }
        },
      );
      final result = meta;
      if (result != null) {
        // AI 回答落卡（tags/domain 可能变化）→ 缓存失效（与 createRecord 同口径）
        _tagsCache = null;
        _timelineCache.clear();
        _memoryCache.clear();
        return result;
      }
      throw Exception('流式回答未返回结果');
    } catch (e) {
      if (!receivedDelta) {
        // 降级保持与原调用同构：intent 由调用方透传（首问 question / 续问 auto-intent）
        return createRecord(content, intent: intent, cardId: cardId);
      }
      rethrow;
    }
  }

  /// 上传图片记录（多模态 L4）：multipart → VLM 理解 → 记录 + 记忆沉淀。
  Future<MediaRecordResponse> uploadImage({
    required List<int> bytes,
    required String filename,
    required String mimeType,
    String? caption,
  }) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/records/media'))
      ..headers.addAll(mediaHeaders) // RFC 20260901-auth-login：multipart 需显式带 Bearer
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
    final resp = await _aiClient.post(
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
    final resp = await _aiClient.post(
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
    final resp = await _aiClient.post(
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

  /// RFC 20260825：逐笔批次跟踪——批次明细（GET /api/v1/trading/lots）。
  /// 可选 query：state=open|closed|all（默认 open）、symbol=xxx（不传=全部）。
  /// 返回 {lots:[...], reconcile:[...]}；reconcile 的 note 含「≠」= 流水与持仓不一致。
  Future<LotsResponse> getLots({String? state, String? symbol}) async {
    final params = <String, String>{
      if (state != null && state.isNotEmpty) 'state': state,
      if (symbol != null && symbol.isNotEmpty) 'symbol': symbol,
    };
    final uri = Uri.parse('$baseUrl/api/v1/trading/lots')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    return LotsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  /// 标的搜索（2026-08-30 验收反馈：记不住代码只记得名字）——q 支持 代码/中文名/拼音首字母。
  /// GET /api/v1/trading/search?q= → [{"symbol":"600519","name":"贵州茅台"}, ...]；失败 → 空。
  Future<List<Map<String, dynamic>>> searchSymbols(String q) async {
    final text = q.trim();
    if (text.isEmpty) return const [];
    try {
      final uri = Uri.parse('$baseUrl/api/v1/trading/search')
          .replace(queryParameters: {'q': text});
      final resp = await _client.get(uri, headers: _headers);
      if (resp.statusCode != 200) return const [];
      final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List<dynamic>;
      return list.cast<Map<String, dynamic>>();
    } catch (_) {
      return const [];
    }
  }

  /// 持仓初始化导入（通达信导出 → 持仓快照，2026-08-16）。
  /// POST /api/v1/trading/positions/import?replace=true → {imported, missingStopLoss}.
  /// replace=true（2026-08-18 确认批次）= 全量覆盖：以文件为准，文件里没有的持仓移除（含 0 股残留）。
  Future<PositionImportResult> importPositions(List<Map<String, dynamic>> items, {bool replace = false}) async {
    final uri = Uri.parse('$baseUrl/api/v1/trading/positions/import')
        .replace(queryParameters: replace ? {'replace': 'true'} : null);
    final resp = await _client.post(
      uri,
      headers: _headers,
      body: jsonEncode(items),
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return PositionImportResult.fromJson(data);
  }

  /// 历史成交日志导入（第五份文件：通达信「历史成交查询」导出，2026-08-18）。
  /// POST /api/v1/trading/trades/import，body {"content": 转码后文本} →
  /// {imported, skipped, nonTrades, lines:[{symbol,name,count,netVolume,holdings,note}]}。
  /// 语义：只补逐笔流水（entryDate=成交日 / fee=券商实扣 / 成交编号幂等），不重算持仓与现金——以全量覆盖导入为准。
  Future<HistoricalTradeImportResult> importTradesHistory(String content) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/trades/import'),
      headers: _headers,
      body: jsonEncode({'content': content}),
    );
    _check(resp);
    return HistoricalTradeImportResult.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }
  /// 一键按流水重建持仓（2026-08-25：导入历史成交后持仓快照过期——已清仓残留自动移除）。
  /// POST /api/v1/trading/sync → {positionCount, removed:[...], keptInitial:[...]}
  Future<SyncResult> syncPositions() async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/sync'),
      headers: _headers,
    );
    _check(resp);
    return SyncResult.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 银证转账（POST /api/v1/trading/transfer：type IN/OUT + amount + note，净投入跟踪）。
  Future<void> recordTransfer({required String type, required double amount, String? note}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/transfer'),
      headers: _headers,
      body: jsonEncode({'type': type, 'amount': amount, 'note': note}));
    _check(resp);
  }

  /// 设置本金（PUT /api/v1/trading/principal，2026-08-18）。
  /// 只改 principal（累计净投入，总盈亏 = 资产 − 本金），不动现金/资产/市值。
  Future<void> setPrincipal(double amount) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/principal'),
      headers: _headers,
      body: jsonEncode({'amount': amount}));
    _check(resp);
  }

  /// 账户总体快照（GET /api/v1/trading/account：资产/可用/可取/参考市值/当日盈亏/盈亏）。
  Future<AccountSnapshotDto> getAccount() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/account'), headers: _headers);
    _check(resp);
    return AccountSnapshotDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// RFC 20260817：推送开关（类型 → 是否开启）。
  Future<Map<String, bool>> getPushSettings() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/push-settings'), headers: _headers);
    _check(resp);
    final map = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return map.map((k, v) => MapEntry(k, v as bool? ?? true));
  }

  /// RFC 20260817：更新推送开关（类型 + 开/关）。
  Future<void> updatePushSetting(String type, bool enabled) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/push-settings/$type'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({'enabled': enabled}),
    );
    _check(resp);
  }

  /// 第三阶段：交易规则参数（用户自己的交易系统参数，GET /trading/rules）。
  Future<Map<String, dynamic>> getTradingRules() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/rules'), headers: _headers);
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 第三阶段：更新交易规则参数（PUT /trading/rules，覆盖非空字段）。
  Future<void> updateTradingRules(Map<String, dynamic> params) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/rules'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({'params': params}),
    );
    _check(resp);
  }

  // ── 第四阶段（2026-08-30）：完美买点案例库（环 1-2）──

  /// 标注一个完美买点案例（POST /trading/cases，自动拉 60+30 日 K → 特征 + 后验）。
  Future<Map<String, dynamic>> annotateCase({
    required String symbol,
    required String buyDate,
    String? buyType,
    String? description,
    String? name,
  }) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/cases'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({
        'symbol': symbol,
        'buyDate': buyDate,
        if (buyType != null && buyType.isNotEmpty) 'buyType': buyType,
        if (description != null && description.isNotEmpty) 'description': description,
        if (name != null && name.isNotEmpty) 'name': name,
      }),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 案例列表（GET /trading/cases，buyDate 倒序）。
  Future<List<Map<String, dynamic>>> listCases() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/cases'), headers: _headers);
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List<dynamic>;
    return list.cast<Map<String, dynamic>>();
  }

  /// 案例详情（GET /trading/cases/{caseId}；kline=true 附 90 根窗口日 K 供画图；
  /// indicators=true 附指标全序列——2026-08-30 前后端一致：前端图不重算指标）。
  Future<Map<String, dynamic>> getCaseDetail(String caseId,
      {bool kline = false, bool indicators = false}) async {
    final params = <String>[];
    if (kline) params.add('kline=true');
    if (indicators) params.add('indicators=true');
    final qs = params.isEmpty ? '' : '?${params.join('&')}';
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/cases/$caseId$qs'),
      headers: _headers,
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 删除案例（DELETE /trading/cases/{caseId}）。
  Future<void> deleteCase(String caseId) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/trading/cases/$caseId'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 批量导入完美案例笔记（2026-08-31）：POST /trading/cases/import → 逐条结果。
  Future<List<dynamic>> importCases(String text) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/cases/import'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({'text': text}),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as List<dynamic>;
  }

  /// 环 3：生成案例 AI 理解（POST /trading/cases/{caseId}/insight → aiInsight 落盘）。
  Future<Map<String, dynamic>> generateCaseInsight(String caseId) async {
    final resp = await _aiClient.post(
      Uri.parse('$baseUrl/api/v1/trading/cases/$caseId/insight'),
      headers: _headers,
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 环 4：判定当下（POST /trading/cases/match）——当前形态 vs 案例库相似度 Top N。
  /// date 可空 = 最近交易日（核心价值：任意代码随查随用）。
  Future<Map<String, dynamic>> matchCases(String symbol, {String? date}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/cases/match'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({
        'symbol': symbol,
        if (date != null && date.isNotEmpty) 'date': date,
      }),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// RFC 20260817：确认交易日志落库（今日候选逐笔入账）。
  /// B11-4（2026-08-23，P1-交易18）：返回完整结果（含失败明细——失败候选保留，可丢弃）。
  Future<TradeLogConfirmResult> confirmTradeLog() async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/trade-log/confirm'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: '{}',
    );
    _check(resp);
    final map = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return TradeLogConfirmResult.fromJson(map);
  }

  /// B10-3（2026-08-23，P1-推送2）：删除单条推送（持久化——刷新不复活）。
  /// DELETE /api/v1/trading/pushes/{id}；404（已删/不存在）幂等成功。
  Future<void> dismissPush(String pushId) async {
    try {
      final resp = await _client.delete(
        Uri.parse('$baseUrl/api/v1/trading/pushes/$pushId'),
        headers: _headers,
      );
      _check(resp);
    } on ApiException catch (e) {
      if (e.statusCode != 404) rethrow;
    }
  }

  /// B11-4（2026-08-23，P1-交易18）：丢弃一条保留的交易日志候选（失败/不完整钉子户）。
  /// DELETE /api/v1/trading/trade-log?symbol=&direction=；404 幂等成功。
  Future<void> discardTradeLogCandidate({String? symbol, String? direction}) async {
    try {
      final uri = Uri.parse('$baseUrl/api/v1/trading/trade-log').replace(
        queryParameters: {
          if (symbol != null && symbol.isNotEmpty) 'symbol': symbol,
          if (direction != null && direction.isNotEmpty) 'direction': direction,
        },
      );
      final resp = await _client.delete(uri, headers: _headers);
      _check(resp);
    } on ApiException catch (e) {
      if (e.statusCode != 404) rethrow;
    }
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

  /// 自选股买点信号（GET /api/v1/trading/buy-points，C2：B1/B2 命中列表）。
  Future<List<BuyPointDto>> getBuyPoints() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/buy-points'), headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => BuyPointDto.fromJson(e)).toList();
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

  /// 清仓复盘三维打分（GET /api/v1/trading/sold/score，D3）。
  Future<List<SoldScoreDto>> getSoldScore() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/sold/score'), headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => SoldScoreDto.fromJson(e)).toList();
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

  /// RFC 20260822：当日交易复盘聚合（纯客观）——GET /trading/trades?date=today → daily 块。
  /// 后端旧版本无 daily 块 → 返回 null（前端不显示今日节奏行）。
  Future<DailyTradeSummaryDto?> getDailyTrades() async {
    final now = DateTime.now();
    final date = '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
    final uri = Uri.parse('$baseUrl/api/v1/trading/trades')
        .replace(queryParameters: {'date': date});
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    if (data is! Map<String, dynamic> || data['daily'] == null) return null;
    return DailyTradeSummaryDto.fromJson(data['daily']);
  }

  /// 生成交易复盘（POST /api/v1/trading/review，AI 生成 → 写入 data/trading/reviews/）。
  Future<ReviewResponse> generateReview({String? date}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/trading/review')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _aiClient.post(uri, headers: _headers);
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
    // 多账号：所有请求带当前用户（后端 FileStorage 按 userId 隔离）；
    // RFC 20260901-auth-login：后端 AuthFilter 校验 Bearer 并把 X-User-Id 覆盖为会话 userId
    'X-User-Id': userId,
    if (token != null) 'Authorization': 'Bearer $token',
  };

  /// 图片记录原图 URL（供 Image.network 渲染缩略图/点击看原图）。
  String mediaUrl(String recordId) => '$baseUrl/api/v1/records/media/$recordId';

  /// 媒体请求鉴权头（Image.network 需要显式传入）。
  Map<String, String> get mediaHeaders => {
    'X-User-Id': userId,
    if (token != null) 'Authorization': 'Bearer $token',
  };

  void _check(http.Response resp) {
    // RFC 20260901-auth-login：401 = 会话失效/未登录 → 全局回调跳登录页（先回调再抛异常）
    if (resp.statusCode == 401 && onUnauthorized != null) {
      onUnauthorized!();
    }
    if (resp.statusCode >= 400) {
      // #118：resp.body 按 latin1 解码中文会乱码，改 utf8 解码 bodyBytes
      throw ApiException(resp.statusCode, 'API 请求失败（HTTP ${resp.statusCode}）', utf8.decode(resp.bodyBytes));
    }
  }
}

/// 认证相关方法（RFC 20260901-auth-login）。
extension AuthApi on ApiService {
  /// 登录：成功返回 {token, userId, role, plugins}。
  /// 401（密码错/未设密码/限流）抛 ApiException，不触发 onUnauthorized（登录页场景）。
  Future<Map<String, dynamic>> login(String account, String password) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'account': account, 'password': password}),
    );
    _checkAuthOnly(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 首访一次性设密码：404 = 系统已初始化（引导登录）；200 = 设置成功。
  Future<void> setup(String account, String password) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/auth/setup'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'account': account, 'password': password}),
    );
    _checkAuthOnly(resp);
  }

  /// 当前会话信息：有效返回账号信息；401 = 会话失效。
  Future<Map<String, dynamic>> authMe() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/auth/me'),
      headers: _headers,
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 登出。
  Future<void> logout() async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/auth/logout'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 认证端点专用校验：>=400 抛 ApiException，但不触发 onUnauthorized 跳转。
  void _checkAuthOnly(http.Response resp) {
    if (resp.statusCode >= 400) {
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

/// 从异常中提取人话错误（后端 {"error":"人话"} 优先，其次状态码/超时/连接人话）。
/// B2-3（2026-08-23）：独立 State 类（历史成交导入 Dialog 等）无法访问页面私有
/// `_extractApiError`，抽为顶层函数复用——此前 `e.toString().contains('无法识别')`
/// 恒 false 把后端人话吞成「检查网络」。
/// B11-4（2026-08-23，P1-交易18）：确认交易日志落库结果（成功/失败/跳过 + 失败人话明细）。
class TradeLogConfirmResult {
  final int confirmed, failed, skipped;
  final List<String> failures;

  TradeLogConfirmResult({required this.confirmed, required this.failed,
      required this.skipped, required this.failures});

  factory TradeLogConfirmResult.fromJson(Map<String, dynamic> json) => TradeLogConfirmResult(
    confirmed: json['confirmed'] as int? ?? 0,
    failed: json['failed'] as int? ?? 0,
    skipped: json['skipped'] as int? ?? 0,
    failures: (json['failures'] as List?)?.map((e) => e.toString()).toList() ?? const [],
  );
}

String extractApiErrorMessage(dynamic e) {
  if (e is ApiException && e.body != null && e.body!.isNotEmpty) {
    final body = e.body!.trim();
    if (body.startsWith('{')) {
      try {
        final decoded = jsonDecode(body);
        if (decoded is Map && decoded['error'] is String && (decoded['error'] as String).isNotEmpty) {
          return decoded['error'] as String;
        }
      } catch (_) {
        // JSON 解析失败继续走下面分支
      }
    } else if (!body.startsWith('<')) {
      return body; // 非 HTML 的裸文本错误体直接展示
    }
  }
  final str = e.toString();
  // 普通业务异常（非网络/HTTP）：透出 message 人话（如「无法识别通达信持仓导出」），
  // 不要一律归为网络异常误导用户（2026-08-23：持仓导入本地校验失败曾被吞成「网络异常」）
  if (str.startsWith('Exception: ')) {
    final msg = str.substring('Exception: '.length).trim();
    if (msg.isNotEmpty && !msg.contains('TimeoutException') && !msg.contains('SocketException')) {
      return msg;
    }
  }
  if (str.contains('API 请求失败')) {
    final codeMatch = RegExp(r'HTTP (\d+)').firstMatch(str);
    final code = codeMatch?.group(1) ?? '?';
    return '请求失败 ($code)';
  }
  if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
  if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器';
  return '网络异常，请重试';
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
  final String updatedAt; // P1-5（2026-08-23 app 体感）：最后活跃 ISO 时间戳

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
    this.updatedAt = '',
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
    updatedAt: json['updatedAt'] as String? ?? '',
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
  final double? stopLossPrice; // 人工止损位（最近 BUY 值 / web 编辑；可空）
  final String? buyPoint; // 买点类型（B1/B2/...）
  final String? role; // 防守/前锋/中场/机动 + 主仓/副仓
  final double? targetPrice; // 目标价
  // 双止损位（trading-risk-plan）：系统计算止损（风险预算公式动态算）+ 生效止损 = max(人工, 计算)
  final double? computedStopLossPrice;
  final double? effectiveStopLoss;

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
    this.computedStopLossPrice,
    this.effectiveStopLoss,
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
    computedStopLossPrice: (json['computedStopLossPrice'] as num?)?.toDouble(),
    effectiveStopLoss: (json['effectiveStopLoss'] as num?)?.toDouble(),
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

/// RFC 20260825：逐笔批次跟踪——批次明细响应（GET /api/v1/trading/lots）。
/// lots=各批次明细；reconcile=流水净增减 vs 当前持仓的对账提示（note 含「≠」= 不一致，
/// 以持仓快照为准，差额按初始批次兜底）。
class LotsResponse {
  final List<LotItem> lots;
  final List<ReconcileLine> reconcile;

  LotsResponse({required this.lots, required this.reconcile});

  factory LotsResponse.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return LotsResponse(
      lots: ((m['lots'] as List?) ?? const [])
          .map((e) => LotItem.fromJson(e))
          .toList(),
      reconcile: ((m['reconcile'] as List?) ?? const [])
          .map((e) => ReconcileLine.fromJson(e))
          .toList(),
    );
  }
}

/// 批次明细（RFC 20260825）：一买一批，稳定 lotId；含剩余/成本/浮动盈亏/止损/买点/状态。
/// initial=true 初始底仓批次（无流水，lotId 以 _INIT 结尾）；
/// closed=true 该批已全部卖出（回合，realizedPnl=整批已实现盈亏）。
class LotItem {
  final String lotId;
  final String symbol;
  final String name;
  final String buyDate; // 批次买入日期 yyyy-MM-dd
  final int volume; // 买入数量
  final int remaining; // 剩余数量
  final double costPrice; // 批次加权成本（含费）
  final double currentPrice; // 现价（行情失败=成本价）
  final double marketValue; // 剩余部分市值
  final double pnl; // 剩余部分浮动盈亏
  final double pnlPct;
  final double? stopLossPrice; // 止损（未设时后端已按默认 −7% 兜底返回）
  final double? stopLossDistancePct; // 距止损%（正=安全，负=已破）
  final String? buyPoint;
  final String? role;
  final bool initial; // 初始底仓批次
  final bool closed; // 已全部卖出（回合）
  final double realizedPnl; // 整批已实现盈亏（closed 时有效）

  LotItem({
    required this.lotId,
    required this.symbol,
    required this.name,
    required this.buyDate,
    required this.volume,
    required this.remaining,
    required this.costPrice,
    required this.currentPrice,
    required this.marketValue,
    required this.pnl,
    required this.pnlPct,
    this.stopLossPrice,
    this.stopLossDistancePct,
    this.buyPoint,
    this.role,
    required this.initial,
    required this.closed,
    required this.realizedPnl,
  });

  factory LotItem.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return LotItem(
      lotId: m['lotId']?.toString() ?? '',
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      buyDate: m['buyDate']?.toString() ?? '',
      volume: (m['volume'] as num?)?.toInt() ?? 0,
      remaining: (m['remaining'] as num?)?.toInt() ?? 0,
      costPrice: (m['costPrice'] as num?)?.toDouble() ?? 0,
      currentPrice: (m['currentPrice'] as num?)?.toDouble() ?? 0,
      marketValue: (m['marketValue'] as num?)?.toDouble() ?? 0,
      pnl: (m['pnl'] as num?)?.toDouble() ?? 0,
      pnlPct: (m['pnlPct'] as num?)?.toDouble() ?? 0,
      stopLossPrice: (m['stopLossPrice'] as num?)?.toDouble(),
      stopLossDistancePct: (m['stopLossDistancePct'] as num?)?.toDouble(),
      buyPoint: m['buyPoint']?.toString(),
      role: m['role']?.toString(),
      initial: m['initial'] as bool? ?? false,
      closed: m['closed'] as bool? ?? false,
      realizedPnl: (m['realizedPnl'] as num?)?.toDouble() ?? 0,
    );
  }
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
  final String? tradeTime; // HH:mm:ss（RFC 20260822，可空——旧数据无）
  final double? stopLossPrice; // BUY 必填，SELL 可空
  final String? buyPoint;
  final double? targetPrice;
  final String? reason;
  final double? fee; // 手续费（券商实扣；2026-08-23 web 历史成交 Tab 显示）
  final String? orderId; // 券商成交编号（2026-08-23 web 历史成交 Tab 显示）

  TradeRecordItem({
    required this.id,
    required this.symbol,
    required this.name,
    required this.direction,
    required this.price,
    required this.volume,
    required this.amount,
    required this.entryDate,
    this.tradeTime,
    this.stopLossPrice,
    this.buyPoint,
    this.targetPrice,
    this.reason,
    this.fee,
    this.orderId,
  });

  bool get isBuy => direction.toUpperCase() == 'BUY';

  /// 股息类资金事件（2026-08-25 方案 A 落流水：volume=0 + reason=源文件备注）。
  /// 与后端 TradingImportParser.isDividendEvent 同口径——备注含 股息/红利/入账。
  /// P2-批次6（2026-08-29）：前端识别后显示「股息入账/红利税」类型标签，
  /// 不再误显示为「买入 0 股」。
  bool get isDividendEvent =>
      volume == 0 &&
      reason != null &&
      (reason!.contains('股息') || reason!.contains('红利') || reason!.contains('入账'));

  /// 股息事件类型标签：入账（BUY，现金流入）→ 股息入账；税（SELL，现金流出）→ 红利税。
  String get dividendLabel => isBuy ? '股息入账' : '红利税';

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
      tradeTime: map['tradeTime'] as String?,
      stopLossPrice: (map['stopLossPrice'] as num?)?.toDouble(),
      buyPoint: map['buyPoint'] as String?,
      targetPrice: (map['targetPrice'] as num?)?.toDouble(),
      reason: map['reason'] as String?,
      fee: (map['fee'] as num?)?.toDouble(),
      orderId: map['orderId'] as String?,
    );
  }
}

/// RFC 20260822：当日交易复盘聚合（纯客观）——今日 N 笔 · 买卖分布 · 时段分桶。
class DailyTradeSummaryDto {
  final String date;
  final int count, buyCount, sellCount;
  final List<DailySessionDto> sessions;
  final String? firstTradeTime, lastTradeTime;

  DailyTradeSummaryDto({required this.date, required this.count, required this.buyCount,
      required this.sellCount, required this.sessions,
      required this.firstTradeTime, required this.lastTradeTime});

  factory DailyTradeSummaryDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return DailyTradeSummaryDto(
      date: m['date']?.toString() ?? '',
      count: (m['count'] as num?)?.toInt() ?? 0,
      buyCount: (m['buyCount'] as num?)?.toInt() ?? 0,
      sellCount: (m['sellCount'] as num?)?.toInt() ?? 0,
      sessions: (m['sessions'] as List?)
          ?.map((e) => DailySessionDto.fromJson(e))
          .toList() ?? const [],
      firstTradeTime: m['firstTradeTime']?.toString(),
      lastTradeTime: m['lastTradeTime']?.toString(),
    );
  }
}

/// 时段桶：名称 / 时间范围 / 笔数。
class DailySessionDto {
  final String name, range;
  final int count;

  DailySessionDto({required this.name, required this.range, required this.count});

  factory DailySessionDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return DailySessionDto(
      name: m['name']?.toString() ?? '',
      range: m['range']?.toString() ?? '',
      count: (m['count'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 账户总体快照（资金股份查询导入，券商口径）。
class AccountSnapshotDto {
  final double assets, cash, available, withdrawable, marketValue, pnl, todayPnl;
  final double principal;
  final String snapshotDate;

  AccountSnapshotDto({required this.assets, required this.cash, required this.available,
      required this.withdrawable, required this.marketValue, required this.pnl,
      required this.todayPnl, required this.principal, required this.snapshotDate});

  /// 账户总盈亏 = 总资产 - 本金（本金 > 0 时有效）。
  /// P2-交易31（2026-08-29，U32）：本金未设（principal=0）→ null——不给误导数值
  /// （旧实现回落浮盈 pnl 漏已实现盈亏：清仓后浮盈≈0 却显示「0 盈亏」仍是误导）；
  /// UI 显示「—」+ 引导设置本金。
  double? get totalPnl => principal > 0 ? assets - principal : null;

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
      principal: (m['principal'] as num?)?.toDouble() ?? 0,
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

/// 案例相似参考（buyPoint="case" 时后端附 Top 3 相似案例；P2-案例2 2026-09-03 web 适配）。
class CaseMatchLiteDto {
  final String caseId, buyDate, buyType;
  final double similarityPercent;

  CaseMatchLiteDto({required this.caseId, required this.buyDate, required this.buyType,
      required this.similarityPercent});

  factory CaseMatchLiteDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return CaseMatchLiteDto(
      caseId: m['caseId']?.toString() ?? '',
      buyDate: m['buyDate']?.toString() ?? '',
      buyType: m['buyType']?.toString() ?? '',
      similarityPercent: (m['similarityPercent'] as num?)?.toDouble() ?? 0,
    );
  }
}

/// 自选股买点信号（C2 盯盘买点：B1 回调 / B2 突破；buyPoint="case"=规则未命中但形态接近完美买点）。
class BuyPointDto {
  final String symbol, name, buyPoint;
  final double score;
  final List<String> signals;
  final List<CaseMatchLiteDto> caseMatches;

  BuyPointDto({required this.symbol, required this.name, required this.buyPoint,
      required this.score, required this.signals, this.caseMatches = const []});

  factory BuyPointDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return BuyPointDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      buyPoint: m['buyPoint']?.toString() ?? '',
      score: (m['score'] as num?)?.toDouble() ?? 0,
      signals: (m['signals'] as List?)?.map((e) => e.toString()).toList() ?? const [],
      caseMatches: (m['caseMatches'] as List?)
              ?.map((e) => CaseMatchLiteDto.fromJson(e)).toList() ??
          const [],
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

/// 清仓复盘三维打分（D3：买点/执行/选股，分数是参考不是指令）。
class SoldScoreDto {
  final String symbol, name, buyPointSignal, buyPointExplain, executionExplain, verdict;
  final int? buyPointScore, executionScore;
  final double? totalScore;

  SoldScoreDto({required this.symbol, required this.name, required this.buyPointSignal,
      required this.buyPointExplain, required this.executionExplain, required this.verdict,
      required this.buyPointScore, required this.executionScore, required this.totalScore});

  factory SoldScoreDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return SoldScoreDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      buyPointSignal: m['buyPointSignal']?.toString() ?? '',
      buyPointExplain: m['buyPointExplain']?.toString() ?? '',
      executionExplain: m['executionExplain']?.toString() ?? '',
      verdict: m['verdict']?.toString() ?? '',
      buyPointScore: (m['buyPointScore'] as num?)?.toInt(),
      executionScore: (m['executionScore'] as num?)?.toInt(),
      totalScore: (m['totalScore'] as num?)?.toDouble(),
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
class BatchImportResponse {  final int success; // 成功条数
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

/// 一键同步持仓结果 DTO（POST /api/v1/trading/sync，2026-08-25）。
/// removed = 流水已清仓的快照残留（已从持仓移除）；keptInitial = 保留的初始底仓（快照早于流水的真底仓）。
class SyncResult {
  final int positionCount;
  final List<String> removed;
  final List<String> keptInitial;

  SyncResult({
    required this.positionCount,
    required this.removed,
    required this.keptInitial,
  });

  factory SyncResult.fromJson(Map<String, dynamic> json) => SyncResult(
        positionCount: (json['positionCount'] as num?)?.toInt() ?? 0,
        removed: (json['removed'] as List?)?.cast<String>() ?? const [],
        keptInitial: (json['keptInitial'] as List?)?.cast<String>() ?? const [],
      );
}

/// 历史成交导入结果 DTO（POST /api/v1/trading/trades/import，第五份文件，2026-08-18）。
/// 契约：{imported, updated, skipped, nonTrades, lines:[{symbol,name,count,netVolume,holdings,note}]}。
/// imported=落流水笔数 / updated=回填缺失成交时间笔数（2026-08-23）/ skipped=幂等去重跳过 /
/// nonTrades=非交易事件（股息红利税等）/ lines=对账提示。
/// RFC 20260825 扩展：syncMode（sync=当日成交同步，持仓已按成交同步更新 / append=历史补录，只补流水）
/// + summary（仅 sync 模式存在；append 无此字段，不报错）。
class HistoricalTradeImportResult {
  final int imported;
  final int updated;
  final int skipped;
  final int nonTrades;
  final List<ReconcileLine> lines;
  final String syncMode; // 'sync' | 'append'（后端旧版本无此字段 → 默认 append 兜底）
  final TradeImportSummary? summary; // 每日操作总结（仅 sync 模式）

  HistoricalTradeImportResult({
    required this.imported,
    required this.updated,
    required this.skipped,
    required this.nonTrades,
    required this.lines,
    this.syncMode = 'append',
    this.summary,
  });

  factory HistoricalTradeImportResult.fromJson(dynamic json) {
    if (json is! Map<String, dynamic>) {
      return HistoricalTradeImportResult(
          imported: 0, updated: 0, skipped: 0, nonTrades: 0, lines: []);
    }
    return HistoricalTradeImportResult(
      imported: (json['imported'] as num?)?.toInt() ?? 0,
      updated: (json['updated'] as num?)?.toInt() ?? 0,
      skipped: (json['skipped'] as num?)?.toInt() ?? 0,
      nonTrades: (json['nonTrades'] as num?)?.toInt() ?? 0,
      lines: ((json['lines'] as List?) ?? [])
          .map((e) => ReconcileLine.fromJson(e))
          .toList(),
      syncMode: json['syncMode'] as String? ?? 'append',
      summary: json['summary'] == null
          ? null
          : TradeImportSummary.fromJson(json['summary']),
    );
  }
}

/// RFC 20260825：每日操作总结（sync 模式导入后）——买卖分布 + 新增/扣减批次 + 行为标注。
class TradeImportSummary {
  final String date; // yyyy-MM-dd
  final int buyCount;
  final int sellCount;
  final double buyAmount; // 买入金额
  final double sellAmount; // 卖出金额
  final int newLots; // 新增批次
  final int deductedLots; // 扣减批次
  final List<TradeBehaviorDto> behaviors;

  TradeImportSummary({
    required this.date,
    required this.buyCount,
    required this.sellCount,
    required this.buyAmount,
    required this.sellAmount,
    required this.newLots,
    required this.deductedLots,
    required this.behaviors,
  });

  factory TradeImportSummary.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return TradeImportSummary(
      date: m['date']?.toString() ?? '',
      buyCount: (m['buyCount'] as num?)?.toInt() ?? 0,
      sellCount: (m['sellCount'] as num?)?.toInt() ?? 0,
      buyAmount: (m['buyAmount'] as num?)?.toDouble() ?? 0,
      sellAmount: (m['sellAmount'] as num?)?.toDouble() ?? 0,
      newLots: (m['newLots'] as num?)?.toInt() ?? 0,
      deductedLots: (m['deductedLots'] as num?)?.toInt() ?? 0,
      behaviors: ((m['behaviors'] as List?) ?? const [])
          .map((e) => TradeBehaviorDto.fromJson(e))
          .toList(),
    );
  }
}

/// RFC 20260825：行为标注——type 语义：
/// loss-avg-down 亏损加仓 / chase-high 追高 / short-new 短线新开 /
/// stop-loss-ignored 破止损未走 / giveback 浮盈回吐 / short-overdue 短线超期。
class TradeBehaviorDto {
  final String type;
  final String label; // 人话标签（亏损加仓/追高/…）
  final String symbol;
  final String name;
  final String date;
  final String message; // 人话解释

  TradeBehaviorDto({
    required this.type,
    required this.label,
    required this.symbol,
    required this.name,
    required this.date,
    required this.message,
  });

  factory TradeBehaviorDto.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return TradeBehaviorDto(
      type: m['type']?.toString() ?? '',
      label: m['label']?.toString() ?? '',
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      date: m['date']?.toString() ?? '',
      message: m['message']?.toString() ?? '',
    );
  }
}

/// 历史成交导入对账行：每标的 导入笔数 / 流水净增减 / 当前持仓 / 人话提示。
class ReconcileLine {
  final String symbol;
  final String name;
  final int count;
  final int netVolume;
  final int? holdings;
  final String note;

  ReconcileLine({
    required this.symbol,
    required this.name,
    required this.count,
    required this.netVolume,
    this.holdings,
    required this.note,
  });

  factory ReconcileLine.fromJson(dynamic json) {
    final map = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return ReconcileLine(
      symbol: map['symbol'] as String? ?? '',
      name: map['name'] as String? ?? '',
      count: (map['count'] as num?)?.toInt() ?? 0,
      netVolume: (map['netVolume'] as num?)?.toInt() ?? 0,
      holdings: (map['holdings'] as num?)?.toInt(),
      note: map['note'] as String? ?? '',
    );
  }
}

/// 反哺入库候选响应（POST /api/v1/trading/reviews/{date}/promote，#129）。
class PromoteResponse {  final String status;
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
