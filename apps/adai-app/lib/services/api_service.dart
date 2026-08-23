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

  /// AI 生成类请求专用客户端：DeepSeek 聊天/追问/总结实测 7~27s（2026-08-20 压测），
  /// 默认 15s 超时必误杀——聊天报错且重试仍报错的根因。AI 端点放宽到 90s。
  final http.Client _aiClient;

  // 内存缓存：跨页面切换不丢
  TagsResponse? _tagsCache;
  List<TimelineEntryResponse>? _timelineCache;
  List<MemoryEntryResponse>? _memoryCache;

  ApiService({String? baseUrl, this.userId = 'default', http.Client? client})
      : baseUrl = baseUrl ?? ApiConfig.baseUrl,
        _client = client ?? _TimeoutClient(http.Client(), const Duration(seconds: 15)),
        _aiClient = client ?? _TimeoutClient(http.Client(), const Duration(seconds: 90));

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
    // doneAt 变化 → 记忆缓存失效（#107）
    _memoryCache = null;
  }

  /// 修正记忆（PATCH /api/v1/memory/{id}，P-role-02 用户端记忆修正）。
  /// 只传需要改的字段，未传字段后端保持原值。
  Future<void> updateMemory(
    String memoryId, {
    String? kind,
    String? summary,
    List<String>? tags,
    bool? actionable,
  }) async {
    final body = <String, dynamic>{
      if (kind != null) 'kind': kind,
      if (summary != null) 'summary': summary,
      if (tags != null) 'tags': tags,
      if (actionable != null) 'actionable': actionable,
    };
    final resp = await _client.patch(
      Uri.parse('$baseUrl/api/v1/memory/$memoryId'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    // 修正影响记忆内容 → 缓存失效（#107）
    _memoryCache = null;
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
    _timelineCache = null;
    _memoryCache = null;
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
    _timelineCache = null;
    _memoryCache = null;
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
    _timelineCache = null;
    _memoryCache = null;
    return AskMediaResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// Phase 1 带图 ask（多图问答）：对已上传的 1-3 张图片一次提问。
  /// 后端按文本 intent 分流——问句 → VLM 多图回答（intent=question）；陈述 → 纯记录（intent=log）。
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
    _timelineCache = null;
    _memoryCache = null;
    return AskBatchResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 提交记录。
  /// 2026-08-20：聊天（intent=question / cardId 续聊）走 _aiClient——DeepSeek 回答 7~27s，
  /// 15s 默认超时必误杀（聊天报错根因）；纯 log 陈述走常规客户端。
  Future<RecordResponse> createRecord(String content, {String? type, List<String>? tags, String? intent, String? cardId}) async {
    final body = {
      'content': content,
      if (type != null) 'type': type,
      if (tags != null && tags.isNotEmpty) 'tags': tags,
      if (intent != null) 'intent': intent,
      if (cardId != null) 'cardId': cardId,
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
    _timelineCache = null;
    _memoryCache = null;
    return RecordResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 获取时间线（自动缓存）。
  Future<List<TimelineEntryResponse>> getTimeline({String? type, int limit = 50}) async {
    if (_timelineCache != null) return _timelineCache!;
    final params = <String, String>{};
    if (type != null) params['type'] = type;
    if (limit != 50) params['limit'] = limit.toString();

    final uri = Uri.parse('$baseUrl/api/v1/timeline').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(utf8.decode(resp.bodyBytes));
    _timelineCache = raw.map((e) => TimelineEntryResponse.fromJson(e)).toList();
    return _timelineCache!;
  }

  /// 结束会话。
  Future<EndConversationResponse> endConversation(List<String> turns, {String? cardId}) async {
    final body = {
      'turns': turns,
      if (cardId != null) 'cardId': cardId,
    };
    final resp = await _aiClient.post(
      Uri.parse('$baseUrl/api/v1/conversations/end'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
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

  /// 获取某日的记忆列表（当天自动缓存）。
  Future<List<MemoryEntryResponse>> getMemory({String? date}) async {
    if (date == null && _memoryCache != null) return _memoryCache!;
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/memory').replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(utf8.decode(resp.bodyBytes));
    final result = raw.map((e) => MemoryEntryResponse.fromJson(e)).toList();
    if (date == null) _memoryCache = result;
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

  /// 账户总体快照（券商口径：总资产/可用/可取/市值/当日盈亏/总盈亏=资产-本金）。
  Future<AccountSnapshotDto> getAccount() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/account'),
      headers: _headers,
    );
    _check(resp);
    return AccountSnapshotDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// RFC 20260822：当日交易复盘聚合（纯客观）——GET /trading/trades?date=today → {trades, daily}。
  Future<DailyTradesResponse> getDailyTrades() async {
    final today = DateTime.now();
    final date = '${today.year}-${today.month.toString().padLeft(2, '0')}-${today.day.toString().padLeft(2, '0')}';
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/trades?date=$date'),
      headers: _headers,
    );
    _check(resp);
    return DailyTradesResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// RFC 20260817：推送开关（类型 → 是否开启）。
  Future<Map<String, bool>> getPushSettings() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/push-settings'),
      headers: _headers,
    );
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

  /// RFC 20260817：交易日志当日候选。
  Future<List<dynamic>> getTradeLogCandidates() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/trade-log'),
      headers: _headers,
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as List<dynamic>;
  }

  /// RFC 20260817：确认交易日志落库。
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

  /// B10-2/B11-4（2026-08-23）：删除单条推送（持久化）+ 丢弃保留候选（钉子户）。
  /// DELETE /api/v1/trading/pushes/{id}；404（已删/不存在）静默成功（幂等）。
  Future<void> dismissPush(String pushId) async {
    try {
      final resp = await _client.delete(
        Uri.parse('$baseUrl/api/v1/trading/pushes/$pushId'),
        headers: _headers,
      );
      _check(resp);
    } catch (e) {
      // app 端异常为泛 Exception('API 错误 {code}: {body}')——404 幂等成功
      if (!_isNotFound(e)) rethrow;
    }
  }

  /// B11-4：丢弃一条保留的交易日志候选（失败/不完整钉子户）。
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
    } catch (e) {
      if (!_isNotFound(e)) rethrow;
    }
  }

  /// 从 app 泛 Exception 消息判断是否 404（幂等场景）。
  bool _isNotFound(Object e) => e.toString().contains('API 错误 404');

  /// 自选股列表。
  Future<List<WatchlistItemDto>> getWatchlist() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/watchlist'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => WatchlistItemDto.fromJson(e)).toList();
  }

  /// 自选股买点信号（B1/B2 命中）。
  Future<List<BuyPointDto>> getBuyPoints() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/buy-points'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => BuyPointDto.fromJson(e)).toList();
  }

  /// 清仓股列表（复盘闭环）。
  Future<List<SoldTradeDto>> getSold() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/sold'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => SoldTradeDto.fromJson(e)).toList();
  }

  /// 清仓复盘三维打分（买点/执行/总分）。
  Future<List<SoldScoreDto>> getSoldScore() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/sold/score'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => SoldScoreDto.fromJson(e)).toList();
  }

  /// 记录一笔交易。
  /// name 可空（RFC 20260815：代码即标的，名称由后端补全/以代码兜底）。
  /// 2026-08-18 简化：app 只记录买卖（标的/价格/数量/方向），止损/买点归 web 端设置——
  /// stopLossPrice/buyPoint 可选透传（app 不再传，web 记录对话框/CSV 导入传）。
  Future<PositionsResponse> recordTrade({
    required String symbol,
    String? name,
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
      if (name != null && name.trim().isNotEmpty) 'name': name.trim(),
      'direction': direction,
      'price': price,
      'volume': volume,
      if (stopLossPrice != null) 'stopLossPrice': stopLossPrice,
      if (buyPoint != null && buyPoint.trim().isNotEmpty) 'buyPoint': buyPoint.trim(),
      if (targetPrice != null) 'targetPrice': targetPrice,
      if (reason != null && reason.trim().isNotEmpty) 'reason': reason.trim(),
    };
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/trades'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return PositionsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 解析一句话交易（RFC 20260815 通道 A）：POST /api/v1/trading/trades/parse。
  /// 后端 LLM 结构化 + 正则兜底，返回 matched 与结构化字段；matched=false 前端落精确表单。
  Future<ParseTradeResponse> parseTrade(String text) async {
    final resp = await _aiClient.post(
      Uri.parse('$baseUrl/api/v1/trading/trades/parse'),
      headers: _headers,
      body: jsonEncode({'text': text}),
    );
    _check(resp);
    return ParseTradeResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 持仓建议（RFC 20260815）：POST /api/v1/trading/advice。
  /// 后端读 os/trading-os/11-context/rules.md（R66/R68/R71/R81-R95）+ 持仓 + 行情，
  /// 输出逐票建议（买入/持有/减仓/清仓 + 阿呆自然对话理由 + 依据规则号）。
  Future<AdviceResponse> getAdvice() async {
    final resp = await _aiClient.post(
      Uri.parse('$baseUrl/api/v1/trading/advice'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({}),
    );
    _check(resp);
    return AdviceResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 检测某日是否有交易活动（GET /api/v1/trading/has-activity）。
  Future<bool> hasTradingActivity({String? date}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/trading/has-activity')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return data['hasActivity'] as bool? ?? false;
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

  /// 当前用户启用插件列表（RFC 20260814 Domain=插件模型；返回如 ["trading","project"]，
  /// 新用户为空 → 前端按此显隐插件模块：交易/阿呆系统）。基础服务模块不依赖此列表。
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
      if (description != null) 'description': description,
      if (priority != null) 'priority': priority,
      if (tags != null) 'tags': tags,
      if (rfcRef != null) 'rfcRef': rfcRef,
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

  /// 媒体请求鉴权头（与 _headers 一致，Image.network 需要显式传入）。
  Map<String, String> get mediaHeaders => {'X-User-Id': userId};

  void _check(http.Response resp) {
    if (resp.statusCode >= 400) {
      throw Exception('API 错误 ${resp.statusCode}: ${utf8.decode(resp.bodyBytes)}');
    }
  }
}

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

/// 多图问答响应 DTO（Phase 1 带图 ask）。
class AskBatchResponse {
  final String intent; // question（VLM 回答） | log（纯记录，无需问答）
  final String answer; // intent=question 时非空
  final String recordId; // intent=question 时非空（image_qa 记录 id）
  final List<String> imageRecordIds;

  AskBatchResponse({
    this.intent = 'log',
    this.answer = '',
    this.recordId = '',
    this.imageRecordIds = const [],
  });

  factory AskBatchResponse.fromJson(Map<String, dynamic> json) =>
      AskBatchResponse(
        intent: json['intent'] as String? ?? 'log',
        answer: json['answer'] as String? ?? '',
        recordId: json['recordId'] as String? ?? '',
        imageRecordIds: (json['imageRecordIds'] as List?)?.cast<String>() ?? [],
      );
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
  final String updatedAt; // P1-5（2026-08-23 app 体感）：最后活跃 ISO 时间戳（「最近记录」相对时间）

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
    type: json['type'] as String,
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
  final double? stopLossPrice; // 2026-08-17 对齐 web：止损位（持仓卡显示）

  PositionItem({
    required this.symbol,
    required this.name,
    required this.quantity,
    required this.avgCost,
    required this.currentPrice,
    required this.marketValue,
    required this.pnl,
    required this.pnlPercent,
    this.stopLossPrice,
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
    stopLossPrice: (json['stopLossPrice'] as num?)?.toDouble(),
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

/// 一句话解析结果 DTO（POST /api/v1/trading/trades/parse，RFC 20260815）。
/// 宽容解析：matched=false 时其余字段可缺省（前端落精确表单）。
/// RFC 20260816：新增 stopLossPrice/buyPoint/targetPrice/reason（可空，宽松解析）。
class ParseTradeResponse {
  final bool matched;
  final String symbol;
  final String name;
  final String direction; // BUY / SELL
  final double? price;
  final int? volume;
  final double? stopLossPrice; // 止损位（BUY 通常必填，后端 parse 可带回）
  final String? buyPoint; // 买点类型（B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他）
  final double? targetPrice; // 目标价（可空）
  final String? reason; // 交易原因/预期（可空）

  ParseTradeResponse({
    this.matched = false,
    this.symbol = '',
    this.name = '',
    this.direction = 'BUY',
    this.price,
    this.volume,
    this.stopLossPrice,
    this.buyPoint,
    this.targetPrice,
    this.reason,
  });

  factory ParseTradeResponse.fromJson(Map<String, dynamic> json) =>
      ParseTradeResponse(
        matched: json['matched'] as bool? ?? false,
        symbol: json['symbol'] as String? ?? '',
        name: json['name'] as String? ?? '',
        direction: (json['direction'] as String? ?? 'BUY').toUpperCase(),
        price: (json['price'] as num?)?.toDouble(),
        volume: (json['volume'] as num?)?.toInt(),
        stopLossPrice: (json['stopLossPrice'] as num?)?.toDouble(),
        buyPoint: json['buyPoint'] as String?,
        targetPrice: (json['targetPrice'] as num?)?.toDouble(),
        reason: json['reason'] as String?,
      );
}

/// 单票建议 DTO（POST /api/v1/trading/advice，RFC 20260815）。
/// action ∈ 买入/持有/减仓/清仓；advice 为阿呆自然对话；rules 为依据规则号（R81…）。
class AdviceItem {
  final String symbol;
  final String name;
  final String action;
  final String advice;
  final List<String> rules;

  AdviceItem({
    this.symbol = '',
    this.name = '',
    this.action = '',
    this.advice = '',
    this.rules = const [],
  });

  factory AdviceItem.fromJson(Map<String, dynamic> json) {
    // rules 字段宽容：List<String> | String（逗号分隔）| 单条 rule 字段
    List<String> rules = [];
    final rawRules = json['rules'];
    if (rawRules is List) {
      rules = rawRules.map((e) => e.toString()).toList();
    } else if (rawRules is String && rawRules.trim().isNotEmpty) {
      rules = rawRules.split(RegExp(r'[,，\s]+')).where((e) => e.isNotEmpty).toList();
    } else if (json['rule'] is String) {
      rules = [json['rule'] as String];
    }
    return AdviceItem(
      symbol: json['symbol'] as String? ?? '',
      name: json['name'] as String? ?? '',
      action: json['action'] as String? ?? '',
      advice: (json['advice'] as String? ??
              json['reason'] as String? ??
              json['content'] as String? ??
              '')
          .toString(),
      rules: rules,
    );
  }
}

/// 持仓建议响应 DTO（POST /api/v1/trading/advice）。
/// 兼容 {items:[...]} 或裸数组两种形态。
class AdviceResponse {
  final List<AdviceItem> items;
  final String summary; // 可选：阿呆整体口径

  AdviceResponse({this.items = const [], this.summary = ''});

  factory AdviceResponse.fromJson(dynamic json) {
    if (json is List) {
      return AdviceResponse(items: json.map((e) => AdviceItem.fromJson(e)).toList());
    }
    final map = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return AdviceResponse(
      items: (map['items'] as List?)
              ?.map((e) => AdviceItem.fromJson(e as Map<String, dynamic>))
              .toList() ??
          [],
      summary: map['summary'] as String? ?? '',
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


// ── 交易 DTO（对齐 web，2026-08-17）──

/// 账户总体快照（券商口径：总资产/可用/可取/市值/当日盈亏/总盈亏=资产-本金）。
class AccountSnapshotDto {
  final double assets, cash, available, withdrawable, marketValue, pnl, todayPnl;
  final double principal;
  final String snapshotDate; // D9（2026-08-23 app 体感，P2-UX3）：快照日期（收盘陈旧感知）

  AccountSnapshotDto({required this.assets, required this.cash, required this.available,
      required this.withdrawable, required this.marketValue, required this.pnl,
      required this.todayPnl, required this.principal, this.snapshotDate = ''});

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

  /// 总盈亏 = 资产 - 本金（用户确认口径，2026-08-16）。
  /// P1-前端3（2026-08-17 走查）：principal>0 兜底与 web/后端对齐——本金未录时退回券商浮盈 pnl，
  /// 否则新账号显示「总盈亏=总资产」全当盈利
  double get totalPnl => principal > 0 ? assets - principal : pnl;
}

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

/// 自选股条目（盯盘买点原料）。
class WatchlistItemDto {
  final String symbol, name, industry, signal;
  final String longForm, midForm, shortForm;

  WatchlistItemDto({required this.symbol, required this.name, required this.industry,
      required this.longForm, required this.midForm, required this.shortForm, required this.signal});

  factory WatchlistItemDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return WatchlistItemDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      industry: m['industry']?.toString() ?? '',
      longForm: m['longForm']?.toString() ?? '',
      midForm: m['midForm']?.toString() ?? '',
      shortForm: m['shortForm']?.toString() ?? '',
      signal: m['signal']?.toString() ?? '',
    );
  }
}

/// 自选股买点信号（B1 回调 / B2 突破，判定是提示不是指令）。
class BuyPointDto {
  final String symbol, name, buyPoint;
  final double score;
  final List<String> signals;

  BuyPointDto({required this.symbol, required this.name, required this.buyPoint,
      required this.score, required this.signals});

  factory BuyPointDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return BuyPointDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      buyPoint: m['buyPoint']?.toString() ?? '',
      score: (m['score'] as num?)?.toDouble() ?? 0,
      signals: (m['signals'] as List?)?.map((e) => e.toString()).toList() ?? const [],
    );
  }
}

/// 清仓股（B/S 复盘闭环）。
class SoldTradeDto {
  final String symbol, name, verdict, psychology;
  final String? buyDate, sellDate;
  final int holdDays;
  final double holdPnlPct;

  SoldTradeDto({required this.symbol, required this.name, required this.buyDate,
      required this.sellDate, required this.holdDays, required this.holdPnlPct,
      required this.verdict, required this.psychology});

  factory SoldTradeDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return SoldTradeDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      buyDate: m['buyDate']?.toString(),
      sellDate: m['sellDate']?.toString(),
      holdDays: (m['holdDays'] as num?)?.toInt() ?? 0,
      holdPnlPct: (m['holdPnlPct'] as num?)?.toDouble() ?? 0,
      verdict: m['verdict']?.toString() ?? '',
      psychology: m['psychology']?.toString() ?? '',
    );
  }
}

/// 清仓复盘三维打分（D3：买点/执行/总分，分数是参考不是指令）。
class SoldScoreDto {
  final String symbol, name;
  final int? buyPointScore, executionScore;
  final double? totalScore;

  SoldScoreDto({required this.symbol, required this.name,
      required this.buyPointScore, required this.executionScore, required this.totalScore});

  factory SoldScoreDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return SoldScoreDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      buyPointScore: (m['buyPointScore'] as num?)?.toInt(),
      executionScore: (m['executionScore'] as num?)?.toInt(),
      totalScore: (m['totalScore'] as num?)?.toDouble(),
    );
  }
}

/// RFC 20260822：当日交易复盘聚合（纯客观）——GET /trading/trades?date=today 的 {daily} 块。
class DailyTradesResponse {
  final DailyTradeSummaryDto daily;

  DailyTradesResponse({required this.daily});

  factory DailyTradesResponse.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return DailyTradesResponse(
      daily: DailyTradeSummaryDto.fromJson(m['daily']),
    );
  }
}

/// 当日复盘聚合：总笔数/买卖分布/时段分桶/首末笔时间。
class DailyTradeSummaryDto {
  final String date;
  final int count, buyCount, sellCount;
  final double buyAmount, sellAmount;
  final List<DailySessionDto> sessions;
  final String? firstTradeTime, lastTradeTime;

  DailyTradeSummaryDto({required this.date, required this.count, required this.buyCount,
      required this.sellCount, required this.buyAmount, required this.sellAmount,
      required this.sessions, required this.firstTradeTime, required this.lastTradeTime});

  factory DailyTradeSummaryDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return DailyTradeSummaryDto(
      date: m['date']?.toString() ?? '',
      count: (m['count'] as num?)?.toInt() ?? 0,
      buyCount: (m['buyCount'] as num?)?.toInt() ?? 0,
      sellCount: (m['sellCount'] as num?)?.toInt() ?? 0,
      buyAmount: (m['buyAmount'] as num?)?.toDouble() ?? 0,
      sellAmount: (m['sellAmount'] as num?)?.toDouble() ?? 0,
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
