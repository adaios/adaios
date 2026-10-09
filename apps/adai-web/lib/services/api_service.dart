import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'api_config.dart';
import 'models/identity_models.dart';
import 'models/learn_models.dart';
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

  /// 普通请求超时（非 AI 端点）。
  static const Duration defaultTimeout = Duration(seconds: 15);

  /// AI 端点超时：DeepSeek 聊天/追问/总结/VLM 看图实测 7~28s，最坏 ~120s
  /// （2026-08-26 后端 45s×2+0.6s，2026-08-27 app 生产实测 GLM 单图最坏 28s）。
  static const Duration aiTimeout = Duration(seconds: 120);

  // 内存缓存：跨页面切换不丢；timeline/memory 按参数 key 区分（参数感知）
  TagsResponse? _tagsCache;
  final Map<String, List<TimelineEntryResponse>> _timelineCache = {};
  final Map<String, List<MemoryEntryResponse>> _memoryCache = {};

  ApiService({String? baseUrl, this.userId = 'default', this.token, this.onUnauthorized,
      http.Client? client, http.Client? aiClient, SseClient? sseClient})
      : baseUrl = baseUrl ?? ApiConfig.baseUrl,
        _client = client ?? _TimeoutClient(http.Client(), defaultTimeout),
        // P1-多图2（2026-09-22）：与 app 对齐——AI 类端点（含 VLM 看图）单独走长超时客户端。
        // aiClient 可单独注入（测试用）：不传时沿用 client（老测试注入单个 MockClient 的行为不变）。
        _aiClient = aiClient ?? client ?? _TimeoutClient(http.Client(), aiTimeout),
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
  ///
  /// P1-多图2（2026-09-22，对齐 app 2026-08-27 同款修复）：VLM 看图最坏 28s+，
  /// 原来走 15s 的 `_client` → 客户端先超时、服务端还在跑，用户重试即重复落盘
  /// （web 与 app 的超时口径必须一致，否则同一个后端在两端表现不同）。
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
    final streamed = await _aiClient.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    // 上传后缓存失效（Feed/Timeline/Memory 都会有新图片记录）
    _tagsCache = null;
    _timelineCache.clear();
    _memoryCache.clear();
    return MediaRecordResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 一次投递一次请求（P1-多图1/2）：multipart 多文件 `files`（1..3 张）+ 可选 `text`
  /// + 可选 `Idempotency-Key`（同键重发返回首次结果且 duplicated=true，不重复入库）。
  /// 后端对全部图做视觉识别、组成上下文：无提问 → `type=image`（summary 为综合总结）；
  /// 有提问 → `type=image_qa`（answer 有值）。AI 失败仍 200（summary 兜底）。
  ///
  /// 幂等键由调用方生成并在重试时**复用同一个 key**（见 FeedPage._retryMediaUpload），
  /// 这是「超时→重试→重复落盘」闭环的最后一环。
  Future<BatchMediaResponse> uploadImages({
    required List<MediaUploadFile> files,
    String? text,
    String? idempotencyKey,
  }) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/records/media/batch'))
      ..headers.addAll(mediaHeaders)
      ..fields['text'] = text ?? '';
    if (idempotencyKey != null && idempotencyKey.isNotEmpty) {
      req.headers['Idempotency-Key'] = idempotencyKey;
    }
    for (final f in files) {
      req.files.add(http.MultipartFile.fromBytes(
        'files', // 契约字段名（复数，与旧单图 file 区分）
        f.bytes,
        filename: f.filename,
        contentType: MediaType('image', f.mimeType.split('/').last),
      ));
    }
    final streamed = await _aiClient.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    // 一次投递落 1 条记录（+ N 张图资产）→ Feed/Timeline/Memory/Tags 全部失效
    _tagsCache = null;
    _timelineCache.clear();
    _memoryCache.clear();
    return BatchMediaResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  /// 「阿呆对你的了解」——聚合记忆里长期沉淀的观察（patterns/preferences）。
  ///
  /// 2026-09-16「第一次见面」批：这些数据一直在 memory 里自动生长，
  /// 此前没有任何出口（用户看到的「档案」只有自己手填的表单）。
  Future<MemoryInsightsResponse> getMemoryInsights() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/memory/insights'),
      headers: _headers,
    );
    _check(resp);
    return MemoryInsightsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  /// 查询当前持仓（当日口径）：GET /api/v1/trading/positions/daily。
  /// 与 getPositions() 同鉴权；`positions` 元素与原端点同形状（直接复用 PositionItem 解析），
  /// 额外给 daily（逐票当日盈亏/今日涨跌幅/仓位比例）、总仓位/现金比例与 notes（未计入项）。
  /// 每个数值字段都可能为 null＝「没算出来」（缺昨收 / 总资产为 0），**不是 0**。
  Future<PositionsDailyResponse> getPositionsDaily() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/positions/daily'),
      headers: _headers,
    );
    _check(resp);
    return PositionsDailyResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  /// 2026-09-04 按批次止损批：设/改某批次止损（事后可单独调，不污染流水）。
  /// PUT /api/v1/trading/lots/{lotId}/stop-loss，body {"stopLossPrice": 12.34}。
  Future<void> updateLotStopLoss(String lotId, double stopLossPrice) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/lots/$lotId/stop-loss'),
      headers: _headers,
      body: jsonEncode({'stopLossPrice': stopLossPrice}),
    );
    _check(resp);
  }

  /// 2026-09-04 按批次止损批：清除某批次止损覆盖（回退流水止损/默认 −7%）。
  /// DELETE /api/v1/trading/lots/{lotId}/stop-loss。
  Future<void> clearLotStopLoss(String lotId) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/trading/lots/$lotId/stop-loss'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 2026-09-04 资金曲线（决策方案 A）：GET /api/v1/trading/equity-curve。
  /// 返回每日收盘净资产 + 净值/回撤；无账户快照 → 空 points（不抛错）。
  Future<EquityCurveResponse> getEquityCurve() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/equity-curve'),
      headers: _headers,
    );
    _check(resp);
    return EquityCurveResponse.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
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
  /// [snapshotDate]（2026-09-12 账实一致性批）= 快照文件自身日期（从文件名解析，`yyyy-MM-dd`）——
  /// **优先于导入日**，后端拿它当锚定日；不传（null/空）则退回导入日。
  /// 补导几天前的快照时若把锚定日写成今天，锚定日之后、快照之前的成交会被误判成
  /// 「已含在快照口径内」而丢掉增量（见 RFC 20260912-trading-ledger-integrity）。
  /// [todayPnl]（2026-09-13）= 券商「持仓股」导出「当日盈亏」列之和（[TdxParseResult.todayPnl]）——
  /// **券商权威口径**，后端只在与账户快照同一天时写入；缺列/不可靠时传 null（保留账户旧值）。
  /// 持仓快照**对账**（POST /api/v1/trading/positions/import?dryRun=true，RFC 20261003 C4「持仓同理」）：
  /// **只读不落盘**——逐只摆出「文件 vs 系统」差多少、replace 会怎么改（新增/移除/改数量）。
  Future<Map<String, dynamic>> reconcilePositions(List<Map<String, dynamic>> items) async {
    final uri = Uri.parse('$baseUrl/api/v1/trading/positions/import')
        .replace(queryParameters: {'dryRun': 'true'});
    final resp = await _client.post(uri, headers: _headers, body: jsonEncode(items));
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  Future<PositionImportResult> importPositions(List<Map<String, dynamic>> items,
      {bool replace = false, String? snapshotDate, double? todayPnl, String? basedOn}) async {
    final params = <String, String>{
      if (replace) 'replace': 'true',
      if (snapshotDate != null && snapshotDate.isNotEmpty) 'snapshotDate': snapshotDate,
      // 2026-10-05（P2-交易84）：显式数据基准日——给了它就优先于「导入时刻」推断。
      if (basedOn != null && basedOn.isNotEmpty) 'basedOn': basedOn,
      if (todayPnl != null) 'todayPnl': todayPnl.toStringAsFixed(2),
    };
    final uri = Uri.parse('$baseUrl/api/v1/trading/positions/import')
        .replace(queryParameters: params.isEmpty ? null : params);
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
  ///
  /// 2026-09-12 账实一致性批：
  /// - [mode]：`auto`（默认）按券商快照锚定分派「≤锚定日只补流水 / 晚于锚定日才回放改账」；
  ///   `append` = 全部只补流水（锚定缺失时的安全模式，不动持仓/现金）
  /// - [dryRun]：只返回计划（新增/合并/跳过/非交易/无法归属 + 锚定状态），**不落任何盘**——
  ///   前端先预检让用户确认，再以 dryRun=false 真正导入
  /// - 锚定缺失且本次有需要改账的成交 → 后端 400 人话（用 [extractApiErrorMessage] 透出）
  Future<HistoricalTradeImportResult> importTradesHistory(String content,
      {String mode = 'auto', bool dryRun = false}) async {
    final uri = Uri.parse('$baseUrl/api/v1/trading/trades/import').replace(queryParameters: {
      'mode': mode,
      if (dryRun) 'dryRun': 'true',
    });
    final resp = await _client.post(
      uri,
      headers: _headers,
      body: jsonEncode({'content': content}),
    );
    _check(resp);
    return HistoricalTradeImportResult.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 账实一致性自检（GET /api/v1/trading/integrity，2026-09-12）：
  /// 应有持仓（快照基线 + 锚定日之后流水净增减）≠ 落地持仓 → drift；卖超/未持有 → gaps。
  /// 失败由调用方静默降级（这是可降级请求，不该打断页面加载）。
  Future<IntegrityReportDto> getTradingIntegrity() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/integrity'), headers: _headers);
    _check(resp);
    return IntegrityReportDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 行情（K 线）链路可用性（GET /api/v1/trading/market-data/health，RFC 20260923 D 批）：
  /// 三源同时失效时后端只在日志里知道，用户侧看到的是资金曲线平了/自选信号没了/案例匹配不上——
  /// 把「不知道」渲染成「不知道」。失败由调用方静默降级（旧后端 404 / 网络抖动不该打断页面加载）。
  Future<MarketDataHealthDto> getMarketDataHealth() async {
    final resp = await _client.get(
        Uri.parse('$baseUrl/api/v1/trading/market-data/health'), headers: _headers);
    _check(resp);
    return MarketDataHealthDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 锚定状态（GET /api/v1/trading/anchor，2026-09-12）：只读，不写任何数据。
  Future<AnchorStatusDto> getAnchorStatus() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/anchor'), headers: _headers);
    _check(resp);
    return AnchorStatusDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 锚点回填（PUT /api/v1/trading/anchor，2026-09-12）：存量环境（升级前导过快照但没写锚定文件）
  /// 的显式自愈手段——只改元信息，不动持仓/现金/流水；日期只前进不后退。
  /// [holdings] 形如 `[{'symbol':'600519','name':'贵州茅台','quantity':100}]`（快照持仓基线）。
  Future<AnchorStatusDto> backfillAnchor({
    String? positionsReplace,
    String? cashImport,
    List<Map<String, dynamic>>? holdings,
  }) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/anchor'),
      headers: _headers,
      body: jsonEncode(<String, dynamic>{
        if (positionsReplace != null && positionsReplace.isNotEmpty) 'positionsReplace': positionsReplace,
        if (cashImport != null && cashImport.isNotEmpty) 'cashImport': cashImport,
        'holdings': ?holdings,
      }),
    );
    _check(resp);
    return AnchorStatusDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  // ── 次日操作计划（RFC 20261003-trading-plan-and-review-loop，2026-10-03）──
  // 定位：系统只「记你的话 · 到点提醒 · 收盘对账」，**不生成计划、不给建议**。

  /// 有操作计划的日期（GET /api/v1/trading/plans）→ 倒序字符串列表。
  Future<List<String>> getPlanDates() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/plans'), headers: _headers);
    _check(resp);
    final m = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (m['dates'] as List? ?? const []).map((e) => e.toString()).toList();
  }

  /// 读某天的计划；**没写返回 null**（后端 404 人话——不返回空壳假计划）。
  Future<Map<String, dynamic>?> getPlan(String date) async {
    final resp = await _client.get(
        Uri.parse('$baseUrl/api/v1/trading/plans/$date'), headers: _headers);
    if (resp.statusCode == 404) return null;
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 写某天的计划（一句话一行；覆盖写）。空计划后端 400 人话。
  Future<Map<String, dynamic>> savePlan(String date, List<String> lines, String note) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/plans/$date'),
      headers: _headers,
      body: jsonEncode({'lines': lines, 'note': note}),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// P2-交易72（2026-10-05）：当天事后的状态回填——「今天没动」/「想动没动」。
  ///
  /// 落点与「今天买了/卖了」同属**这一天自己的记录**（`plans/{date}.json` 的 dayStatus），
  /// **不改动已有计划条目**（后端只改这一个字段）；同日同状态重复提交后端幂等，
  /// 返回 `recorded=false` —— 调用方据此如实说「已经记着了」，**不许假报落库**。
  /// 取值常量见 [dayStatusNoTrade] / [dayStatusWantedNotActed]（与后端 TradingPlan 逐字一致）。
  Future<Map<String, dynamic>> setPlanDayStatus(String date, String status) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/plans/$date/status'),
      headers: _headers,
      body: jsonEncode({'status': status}),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 今天没动（「没动」是完整信息，不是缺数据——R119「零仓位也是交易」）。
  static const String dayStatusNoTrade = 'NO_TRADE';

  /// 想动没动（同上；连续出现可作「手痒」的对照）。
  static const String dayStatusWantedNotActed = 'WANTED_NOT_ACTED';

  /// 收盘对账（计划 vs 实际 + ⚠️ 计划外成交）——**只陈述事实**。
  Future<Map<String, dynamic>> reviewPlan(String date) async {
    final resp = await _client.get(
        Uri.parse('$baseUrl/api/v1/trading/plans/$date/review'), headers: _headers);
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 「一轮完整交易」× 规则检查（GET /api/v1/trading/rounds，2026-10-03）——只陈述事实、不作建议。
  Future<Map<String, dynamic>> getRounds({String? symbol, int limit = 50}) async {
    final q = (symbol != null && symbol.isNotEmpty)
        ? '?symbol=$symbol&limit=$limit'
        : '?limit=$limit';
    final resp =
        await _client.get(Uri.parse('$baseUrl/api/v1/trading/rounds$q'), headers: _headers);
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 账户总体快照（GET /api/v1/trading/account：资产/可用/可取/参考市值/当日盈亏/盈亏）。
  Future<AccountSnapshotDto> getAccount() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/account'), headers: _headers);
    _check(resp);
    return AccountSnapshotDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 今日 / 本周 / 本月盈亏（GET /api/v1/trading/pnl-periods，2026-09-15 用户要求）：
  /// 金额 + 比例；口径与资金曲线同源（逐日总资产差分、剔除银证转账）。
  /// pct 可能为 null（区间起点前无曲线点 / 锚定日之前不可追溯）——**不渲染成 0%**。
  /// 增强项：调用方失败必须静默降级，不拖垮账户总览。
  Future<PnlPeriodsDto> getPnlPeriods() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/pnl-periods'), headers: _headers);
    _check(resp);
    return PnlPeriodsDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  // ── R-06 规则集三态（2026-10-07 差异决算批 5 · P3-7 补对齐）──
  // 与上面 GET/PUT /trading/rules（参数阈值 rules.yaml）**并存不冲突**：
  // 那是「你设的数」，这是「你的规则条文」（只存文本 + 参数）。

  /// 规则集全列表（GET /trading/rules/user）：{total, candidates, accepted, custom}——
  /// 候选（系统提、每条带据）→ 已认（你勾选/改）→ 自定义（你写）；一次拿全。
  Future<Map<String, dynamic>> getUserRules() async {
    final resp = await _client.get(
        Uri.parse('$baseUrl/api/v1/trading/rules/user'), headers: _headers);
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 从数据里照一遍候选（POST /trading/rules/candidates）：描述性统计 → 候选规则（每条带据），
  /// 返回刷新后全列表。红线：候选是「描述」（你实际在做什么）不是「建议」；样本不足宁可不出。
  Future<Map<String, dynamic>> generateRuleCandidates() async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/rules/candidates'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: '{}',
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 认下一条候选（POST /trading/rules/{id}/accept；text 非空 = 「改一改」认下时改）。
  Future<Map<String, dynamic>> acceptUserRule(String id, {String? text}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/rules/${Uri.encodeComponent(id)}/accept'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({
        if (text != null && text.isNotEmpty) 'text': text,
      }),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 改文本（PUT /trading/rules/{id}；仅已认/自定义——候选要先认下）。
  Future<Map<String, dynamic>> editUserRule(String id, String text) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/rules/${Uri.encodeComponent(id)}'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({'text': text}),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 弃掉一条（DELETE /trading/rules/{id}）：墓碑——不再出现、生成不复活；幂等。
  Future<void> dismissUserRule(String id) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/trading/rules/${Uri.encodeComponent(id)}'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 自己写一条（POST /trading/rules/custom，三态之自定义——三来源之「自建」）。
  Future<Map<String, dynamic>> createCustomRule(String text) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/rules/custom'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({'text': text}),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// v3.41（2026-09-04）：活跃市值区间（用户手动判定，GET /trading/market-stage）。
  /// 返回 {"exists":bool,"stage":"bull"|"bear"|null,"updatedAt":String|null}。
  Future<Map<String, dynamic>> getMarketStage() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/market-stage'), headers: _headers);
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// v3.41（2026-09-04）：设定活跃市值区间（PUT /trading/market-stage，两档 bull/bear）。
  Future<void> setMarketStage(String stage) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/market-stage'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({'stage': stage}),
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

  /// 案例候选（GET /trading/cases/candidates，2026-10-08 UI/UX 批 ③）：
  /// {pending: 等你认的候选（从你的记录里长出来）, accepted: 已经收下的}——
  /// 候选每次现算（数据变了候选跟着变），你认了才算；「不要」= 墓碑（下次刷新不复活）。
  Future<Map<String, List<Map<String, dynamic>>>> getCaseCandidates() async {
    final resp = await _client.get(
        Uri.parse('$baseUrl/api/v1/trading/cases/candidates'), headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    List<Map<String, dynamic>> rows(dynamic v) =>
        ((v as List?) ?? const []).cast<Map<String, dynamic>>();
    return {'pending': rows(data['pending']), 'accepted': rows(data['accepted'])};
  }

  /// 收下一条候选（POST /trading/cases/candidates/{id}/accept；title 非空 = 「改一改」改名）。
  /// 幂等：重复收下原样返回（第一次决定为准）。
  Future<Map<String, dynamic>> acceptCaseCandidate(String id, {String? title}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/cases/candidates/$id/accept'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({
        if (title != null && title.isNotEmpty) 'title': title,
      }),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 不要一条候选（POST /trading/cases/candidates/{id}/dismiss）：墓碑——下次刷新不复活；幂等。
  Future<void> dismissCaseCandidate(String id) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/cases/candidates/$id/dismiss'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: '{}',
    );
    _check(resp);
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
  /// DELETE /api/v1/trading/trade-log?id=&symbol=&direction=；404 幂等成功。
  ///
  /// C 批（2026-09-19，UI/UX 审查 P3-12「双端口径漂移」）：**补 `id` 行级定位**——app 侧
  /// 2026-09-17（P1-交易54）起已按 id 删，web 仍是旧的 symbol+direction 口径（同代码同方向的
  /// 多笔会被**一起删掉**）。web 交易页当前未调用此方法，属潜伏风险，一并补齐。
  Future<void> discardTradeLogCandidate({String? id, String? symbol, String? direction}) async {
    try {
      final uri = Uri.parse('$baseUrl/api/v1/trading/trade-log').replace(
        queryParameters: {
          if (id != null && id.isNotEmpty) 'id': id,
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

  /// 清仓股列表（GET /api/v1/trading/sold，复盘闭环；RFC 20260909 批1 双轨对象契约）。
  /// 新契约响应为对象：{"sold":[SoldTradeDto...], "pendingClearances":[{symbol,name,sellDate,reason}...]}——
  /// sold 每行新增 provenance=import|flow（老行缺省 import）；pendingClearances 为流水已清仓但
  /// 缺买入基线的待补清单（条件 B 只提示不写脏，空则省略）。
  /// 过渡兼容：后端仍返回裸数组（旧版）时按 sold=数组、pendingClearances 空处理。
  Future<SoldOverview> getSold() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/sold'), headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    if (data is List) {
      return SoldOverview(
        sold: data.map((e) => SoldTradeDto.fromJson(e)).toList(),
        pending: const [],
      );
    }
    final m = data is Map<String, dynamic> ? data : <String, dynamic>{};
    return SoldOverview(
      sold: (m['sold'] as List?)?.map((e) => SoldTradeDto.fromJson(e)).toList() ?? const [],
      pending: (m['pendingClearances'] as List?)
              ?.map((e) => PendingClearanceDto.fromJson(e)).toList() ??
          const [],
    );
  }

  /// 清仓股导入（POST /api/v1/trading/sold/import，通达信导出文本）。
  /// P2-交易83（2026-10-04，契约 v3.96）：返回值从「裸笔数 int」升级为 [SoldImportResult]——
  /// 后端自本批起把**没看懂的行**（行号 + 原文 + 原因）如实回传，原先只取 `imported` 把它全丢了。
  Future<SoldImportResult> importSold(String content) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/sold/import'),
      headers: _headers, body: jsonEncode({'content': content}));
    _check(resp);
    final d = jsonDecode(utf8.decode(resp.bodyBytes));
    return SoldImportResult.fromJson(d);
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

  /// 清仓「卖掉之后到现在」（GET /api/v1/trading/sold/after-close，2026-10-08 UI/UX 重做批）：
  /// 卖出日收盘 → 最新收盘的涨跌幅（回答「我卖飞了没」：涨=走早了 / 跌=走对了）。
  /// 按清仓列表顺序逐笔返回（前端按索引匹配，同 /sold/score）；拿不到的 pct=null + note。
  Future<List<SoldAfterCloseDto>> getSoldAfterClose() async {
    final resp = await _client.get(Uri.parse('$baseUrl/api/v1/trading/sold/after-close'), headers: _headers);
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes));
    return (data as List).map((e) => SoldAfterCloseDto.fromJson(e)).toList();
  }

  /// 资金快照**对账**（POST /api/v1/trading/imports/cash + dryRun，RFC 20261003 C4）：
  /// **只读不落盘**——返回 {brokerCash, systemCash, diff, since[], ledgerOnlyCount, note}，
  /// 让人先看见「券商现金 vs 系统推算」差多少、差在哪，再决定要不要覆盖。
  Future<Map<String, dynamic>> reconcileCash(String content, {String? snapshotDate, String? basedOn}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/imports/cash'),
      headers: _headers,
      body: jsonEncode(<String, dynamic>{
        'content': content,
        'dryRun': 'true',
        if (snapshotDate != null && snapshotDate.isNotEmpty) 'snapshotDate': snapshotDate,
        // 2026-10-05（P2-交易84）：对账口径与落盘锚定同判据——显式基准日优先
        if (basedOn != null && basedOn.isNotEmpty) 'basedOn': basedOn,
      }));
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 资金股份查询导入（POST /api/v1/trading/imports/cash：现金 + 精确成本）。
  /// [snapshotDate]（2026-09-12）= 快照文件自身日期（`yyyy-MM-dd`），优先于导入日——
  /// 后端用它做锚定日（cashImport）。不传则退回导入日。
  /// [basedOn]（2026-10-05，P2-交易84）= **显式数据基准日**（`yyyy-MM-dd`）——现金锚定日随之确定，
  /// 不再靠「导入时刻」推断；不传则退回既有归一化（后端回执标「无据」）。
  Future<CashImportResult> importCash(String content, {String? snapshotDate, String? basedOn}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/imports/cash'),
      headers: _headers,
      body: jsonEncode(<String, dynamic>{
        'content': content,
        if (snapshotDate != null && snapshotDate.isNotEmpty) 'snapshotDate': snapshotDate,
        if (basedOn != null && basedOn.isNotEmpty) 'basedOn': basedOn,
      }));
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

  /// 统一导入（`R-12`「一次把导出的文件交给它就行」· 2026-10-06）。
  /// POST /api/v1/trading/import（multipart，`files` 可一次多份）→
  /// {dryRun, okCount, failedCount, files:[{filename, savedPath?, kind, kindLabel, ok, error?, detail?}]}。
  /// 后端逐份识别（表头 fail-closed）、内部排序（快照先 / 流水后）、逐份回执——**一份失败不影响其他份**；
  /// [dryRun]=true 只识别 + 报「会做什么」，不落盘不留存。
  Future<BundleImportReceipt> importTradingBundle(List<BundleUploadFile> files,
      {bool dryRun = false}) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/trading/import'))
      ..headers.addAll(_headers)
      ..fields['dryRun'] = dryRun ? 'true' : 'false';
    for (final f in files) {
      req.files.add(http.MultipartFile.fromBytes('files', f.bytes, filename: f.name));
    }
    final streamed = await _client.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    return BundleImportReceipt.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 三粒度分析（`R-05`：全局 / 单标的 / 单笔）· 2026-10-06。
  /// GET /api/v1/trading/analysis/{scope}（scope=global|symbol|round；symbol、roundId 按 scope 二选一）。
  /// 契约（design §5）：缺数据 → value=null + 说明（**不出 0**）；每个数字带 trace（可回溯哪几笔 / 哪几天）；
  /// 只陈述、不评价、不建议；没有规则时 contrast.hasRules=false 且明说「判不了」。
  /// R-04（2026-10-07）：通用 K 线 —— 一张图四处共用（持仓 / 自选 / 清仓 / 案例）。
  /// 后端一次给齐：蜡烛 + 我的买卖点 + 你定的止损线 + 峰值浮盈线 + 上下文。
  /// 副图（量 / MACD / KDJ）不在这里 —— 与生产案例图同口径，前端从 OHLCV 重算。
  Future<TradingKlineDto> fetchTradingKline(String symbol, {int window = 90}) async {
    final uri = Uri.parse('$baseUrl/api/v1/trading/kline')
        .replace(queryParameters: {'symbol': symbol, 'window': '$window'});
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    return TradingKlineDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  Future<TradingAnalysisDto> fetchTradingAnalysis(String scope, {String? symbol, String? roundId}) async {
    final params = <String, String>{};
    if (symbol != null && symbol.isNotEmpty) params['symbol'] = symbol;
    if (roundId != null && roundId.isNotEmpty) params['id'] = roundId;
    final uri = Uri.parse('$baseUrl/api/v1/trading/analysis/$scope')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    return TradingAnalysisDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  /// 生成交易复盘（POST /api/v1/trading/review）。
  /// 2026-09-07 起 POST 改为「提交即返回」：后台生成（AI 实测 77~176s 波动大，旧同步等待
  /// 即使 120s AI 客户端也偶发超时 → 复盘实际生成却报失败）。返回 {date, status}：
  /// exists=已有复盘（直接 GET 展示）/ running=生成中（继续轮询）/ pending=已受理（轮询 GET 直到 200）。
  Future<ReviewSubmitResponse> submitReview({String? date}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/trading/review')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _aiClient.post(uri, headers: _headers);
    _check(resp);
    return ReviewSubmitResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  /// 当前用户启用插件列表（RFC 20260814 Domain=插件模型；RFC 20260917 后只剩
  /// ["trading","learn"]，project 已下线）。新用户为空 → 桌面壳按此显隐插件模块。
  /// 基础服务（含待办）不依赖此列表。
  Future<List<String>> getMyPlugins() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/me/plugins'),
      headers: _headers,
    );
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
    return list.map((e) => e.toString()).toList();
  }

  // ── 待办 API（RFC 20260917：Kernel builtin，无插件门控）──

  /// 获取待办列表（GET /todos?status=OPEN|DONE；status 可选）。
  Future<List<TodoResponse>> getTodos({String? status}) async {
    final params = <String, String>{};
    if (status != null) params['status'] = status;
    final uri = Uri.parse('$baseUrl/api/v1/todos')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
    return list.map((e) => TodoResponse.fromJson(e)).toList();
  }

  /// 新建待办（due 可省略/null 表示无到期日）。
  Future<TodoResponse> createTodo({required String title, String? due}) async {
    final body = <String, dynamic>{
      'title': title,
      'due': ?due,
    };
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/todos'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return TodoResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 更新待办（null = 保持原值；[due] 传空串 '' 表示清除到期日）。
  Future<TodoResponse> updateTodo(String id, {String? title, String? status, String? due}) async {
    final body = <String, dynamic>{};
    if (title != null) body['title'] = title;
    if (status != null) body['status'] = status;
    if (due != null) body['due'] = due;
    // #243：走注入的 _client（MockClient 可拦截），不用全局 http.put（widget 测试真实 HTTP 恒 400）
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/todos/$id'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return TodoResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 删除待办。
  Future<void> deleteTodo(String id) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/todos/$id'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 待办统计（GET /todos/stats → {total, open, done}）。
  Future<TodoStatsResponse> getTodoStats() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/todos/stats'),
      headers: _headers,
    );
    _check(resp);
    return TodoStatsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  // ── learn 学习插件（RFC 20260829）──

  /// 喂入链接或素材消化（RFC 20260912 D 形态抓取批）：POST /learn/digest →
  /// {status: pending|running|needs_confirmation}。**url 我（后端）自己去抓**（B站/文章），
  /// 抓不到时用户粘 content 兜底；两者至少给一个，url 优先（旧地址 /learn/cards 是同一逻辑的别名）。
  /// 素材框里只放一个裸链接时后端也当链接处理。
  /// 后台消化完成后轮询 [getLearnDigestStatus] 直到 done/failed/needs_confirmation。
  Future<String> submitLearnDigest({
    String? url,
    String? content,
    String? type,
    String? platform,
    String? author,
    String? published,
  }) async {
    final result = await submitLearnDigestDetailed(
      url: url, content: content, type: type,
      platform: platform, author: author, published: published,
    );
    return result.status;
  }

  /// 同上，但把**受理回执原样**带回来（P2-分享4，2026-10-05）：抢占任务位失败时后端回
  /// `status=not_queued`（这条**没排上**，不是「在跑了」）+ 一句人话，弹窗直接展示。
  Future<LearnDigestSubmitDto> submitLearnDigestDetailed({
    String? url,
    String? content,
    String? type,
    String? platform,
    String? author,
    String? published,
  }) async {
    assert((url != null && url.trim().isNotEmpty) ||
        (content != null && content.trim().isNotEmpty),
        '链接与素材至少给一个，否则我没法开始整理');
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/digest'),
      headers: _headers,
      body: jsonEncode({
        'url': ?url,
        'content': ?content,
        'type': ?type,
        'platform': ?platform,
        'author': ?author,
        'published': ?published,
      }),
    );
    _check(resp);
    return LearnDigestSubmitDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 整理任务账（GET /learn/digest/jobs，2026-10-05 桌面端补上）：「我分享过哪些、成了没有」。
  ///
  /// 与 [getLearnDigestStatus] 的分工：那个是**现在这一个**的执行态（内存、会过期），
  /// 这个是**落盘的账**（含 expired / not_queued 两个如实状态）。查询失败由调用方静默降级。
  Future<List<LearnDigestTaskDto>> getLearnDigestJobs({int limit = 20}) async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/digest/jobs?limit=$limit'),
      headers: _headers,
    );
    _check(resp);
    final json = jsonDecode(utf8.decode(resp.bodyBytes));
    if (json is Map && json['tasks'] is List) {
      return (json['tasks'] as List)
          .map((e) => LearnDigestTaskDto.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    return const [];
  }

  /// 展开状态清单（GET /learn/cards/expansions，P2-learn33）：桌面端「待展开」可见状态的真相源。
  Future<List<LearnExpansionDto>> getLearnExpansions() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/cards/expansions'),
      headers: _headers,
    );
    _check(resp);
    final json = jsonDecode(utf8.decode(resp.bodyBytes));
    if (json is Map && json['items'] is List) {
      return (json['items'] as List)
          .map((e) => LearnExpansionDto.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    return const [];
  }

  /// 展开一张索引卡（P2-learn33）：POST /learn/cards/expand → 衍生全文卡 + 三行要点。
  /// 同步跑一次 LLM（单次生成）→ 走 `_aiClient`（120s），15s 默认超时会误杀。
  Future<LearnExpansionResultDto> expandLearnCard({required String type, required String title}) async {
    final resp = await _aiClient.post(
      Uri.parse('$baseUrl/api/v1/learn/cards/expand'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title}),
    );
    _check(resp);
    return LearnExpansionResultDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 转写费用确认（RFC 20260912 §3.8 条 5）：抓到没字幕的视频 → 先把时长和钱说清 → 你点头我才花钱。
  /// POST /learn/digest/confirm，body {"confirm": true|false} → 任务状态对象。
  Future<LearnDigestJob> confirmLearnTranscription(bool confirm) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/digest/confirm'),
      headers: _headers,
      body: jsonEncode({'confirm': confirm}),
    );
    _check(resp);
    return LearnDigestJob.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 本月转写用量与额度（GET /learn/digest/quota）：还剩多少先说清楚，别默默花钱。
  Future<LearnQuotaDto> getLearnQuota() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/digest/quota'),
      headers: _headers,
    );
    _check(resp);
    return LearnQuotaDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 消化任务状态（2026-09-10；2026-09-12 抓取批加 stage/source/cost）：
  /// GET /learn/digest/status → {status, type?, title?, message?, stage?, source?, cost?}。
  Future<LearnDigestJob> getLearnDigestStatus() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/digest/status'),
      headers: _headers,
    );
    _check(resp);
    return LearnDigestJob.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 资产树：learn 卡片按 type 分组（GET /learn/tree）。
  Future<LearnTreeResponse> getLearnTree() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/tree'),
      headers: _headers,
    );
    _check(resp);
    return LearnTreeResponse.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 卡片列表（?type= 可选 ai/trading/other；缺省全部）。
  Future<List<LearnCardDto>> getLearnCards({String? type}) async {
    final uri = type == null || type.isEmpty
        ? Uri.parse('$baseUrl/api/v1/learn/cards')
        : Uri.parse('$baseUrl/api/v1/learn/cards').replace(queryParameters: {'type': type});
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final List raw = jsonDecode(utf8.decode(resp.bodyBytes));
    return raw.map((e) => LearnCardDto.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// 单篇卡片全文（GET /learn/content?type=&title=）：md 原文 + topic/writable。
  /// 2026-09-12 完整升级批：列表只回产品建模的四段，Mac 侧整理的卡（关键内容详解/金句/概念关系）
  /// 必须走这里才读得全。
  Future<LearnCardContentDto> getLearnContent(
      {required String type, required String title}) async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/content')
          .replace(queryParameters: {'type': type, 'title': title}),
      headers: _headers,
    );
    _check(resp);
    return LearnCardContentDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 找卡片（GET /learn/find?q=&limit=）：对话里「打开那篇」与学习页搜索共用。
  /// 服务端已按相关度排序（纯规则，不烧 AI）；没找到返回空列表。
  Future<List<LearnCardDto>> searchLearnCards(String q, {int limit = 5}) async {
    final query = q.trim();
    if (query.isEmpty) return const [];
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/find')
          .replace(queryParameters: {'q': query, 'limit': limit.toString()}),
      headers: _headers,
    );
    _check(resp);
    final List raw = jsonDecode(utf8.decode(resp.bodyBytes));
    return raw.map((e) => LearnCardDto.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// 图片喂入（POST /learn/digest/image，multipart files 1~3 张，可选 type/note）→
  /// {"status":"pending"}；之后照旧轮询 [getLearnDigestStatus]。原图落 learn/_raw/。
  Future<String> submitLearnImages(List<LearnImageInput> images,
      {String? type, String? note}) async {
    assert(images.isNotEmpty, '至少给我一张图');
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/learn/digest/image'))
      ..headers.addAll(mediaHeaders) // multipart 不走 _headers：需显式带 X-User-Id 与 Bearer
      ..fields.addAll({
        if (type != null && type.trim().isNotEmpty) 'type': type,
        if (note != null && note.trim().isNotEmpty) 'note': note,
      });
    for (final image in images) {
      final mime = image.mimeType.split('/');
      req.files.add(http.MultipartFile.fromBytes(
        'files',
        image.bytes,
        filename: image.filename,
        contentType: mime.length == 2 ? MediaType(mime[0], mime[1]) : MediaType('image', 'png'),
      ));
    }
    final streamed = await _client.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    final json = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (json['status'] as String?) ?? '';
  }

  /// 复习状态流转（V2）：new → review → done（PATCH /learn/cards/status）。
  Future<LearnCardDto> updateLearnStatus(
      {required String type, required String title, required String status}) async {
    final resp = await _client.patch(
      Uri.parse('$baseUrl/api/v1/learn/cards/status'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title, 'status': status}),
    );
    _check(resp);
    return LearnCardDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 编辑卡片正文（V2）：?type=&title= 定位，body 部分字段补丁（缺省 = 保留原值）。
  /// 传 null = 不改该字段；传空串/空列表 = 清空。
  Future<LearnCardDto> editLearnCard({
    required String type,
    required String title,
    String? coreView,
    List<String>? keyPoints,
    List<String>? questions,
    String? retell,
    bool? tradeRelated,
    String? tradeNote,
    List<String>? tags,
  }) async {
    final body = <String, dynamic>{
      'coreView': ?coreView,
      'keyPoints': ?keyPoints,
      'questions': ?questions,
      'retell': ?retell,
      'tradeRelated': ?tradeRelated,
      'tradeNote': ?tradeNote,
      'tags': ?tags,
    };
    final resp = await _client.patch(
      Uri.parse('$baseUrl/api/v1/learn/cards')
          .replace(queryParameters: {'type': type, 'title': title}),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return LearnCardDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 改主题（2026-09-13 卡片管理批）：PATCH /learn/cards/topic，body {type, title, topic}
  /// → 更新后的 LearnCard（topic 已是新值）。卡片被移到 {type}/{新主题}/NN-标题.md，新主题内续号。
  /// 幂等：新旧主题相同 → 原样返回。400：卡片不存在 / 只读卡 / topic 为空 / type 非法；403：learn 未启用。
  Future<LearnCardDto> moveLearnCardTopic(
      {required String type, required String title, required String topic}) async {    final resp = await _client.patch(
      Uri.parse('$baseUrl/api/v1/learn/cards/topic'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title, 'topic': topic}),
    );
    _check(resp);
    return LearnCardDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 删卡片（软删除，2026-09-13 卡片管理批）：DELETE /learn/cards?type=&title= →
  /// {deleted, title, learnCardId, cascadedCandidates}。
  /// 文件移进 learn/_trash/（可人工找回）并从主题 README 索引里摘除；曾反哺过的交易候选
  /// 会被级联清理（标题在 cascadedCandidates 里，调用方要如实展示）。
  /// 400：卡片不存在 / 别处整理的只读卡 / type 非法；403：learn 未启用。
  Future<LearnCardDeletedDto> deleteLearnCard(
      {required String type, required String title}) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/learn/cards')
          .replace(queryParameters: {'type': type, 'title': title}),
      headers: _headers,
    );
    _check(resp);
    return LearnCardDeletedDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 认回被抹掉的来源标记（P2-审查4，2026-09-17 B5 批）：
  /// `POST /learn/cards/restore-origin?type=&title=` → `{type, title, writable}`。
  /// 只对「本产品写的、`origin` 被别的工具抹掉」的卡有效（判据在**后端**：正文含 `## 卡片页`
  /// 或 frontmatter 带 `review_at`/`reminded_at`）；认不回来 400 人话——调用方原样显示，
  /// **不做本地猜测**（否则等于给别人的卡盖章，只读保护就废了）。
  Future<bool> restoreLearnOrigin(
      {required String type, required String title}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/cards/restore-origin')
          .replace(queryParameters: {'type': type, 'title': title}),
      headers: _headers,
    );
    _check(resp);
    final body = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return body['writable'] == true;
  }

  /// 产物反馈（RFC 20260917 §五 2b）：POST /learn/cards/feedback → {status, message, canRepage}。
  /// 把「太啰嗦 / 多举几个例子」沉淀为**长期偏好**，经画像回流作用于**下一次**生成。
  /// **本调用不烧钱**（只写偏好、不调 LLM）；canRepage=true 表示这张卡还有 _raw 素材、
  /// 可按新偏好重排一版（重排才花钱，由用户点头触发）。
  /// 400：卡片不存在 / 反馈为空或超长；403：learn 未启用。
  Future<LearnFeedbackDto> submitLearnFeedback(
      {required String type, required String title, required String feedback}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/cards/feedback'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title, 'feedback': feedback}),
    );
    _check(resp);
    return LearnFeedbackDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 重排页序列（2026-09-15 卡片流批；RFC 20260917 补前端入口）：
  /// POST /learn/cards/repages —— 拿这张卡留在 _raw/ 的原始素材补排「一页一单元」卡片流；
  /// **核心观点/要点/复述与手工编辑一字不动**（只补呈现层）。老卡没素材 → 400 人话。
  Future<void> repageLearnCard({required String type, required String title}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/cards/repages'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title}),
    );
    _check(resp);
  }

  /// trading 卡片反哺成规则候选（V2 批 3）：POST /learn/cards/candidate。
  Future<LearnTradingCandidateDto> createLearnCandidate(
      {required String type, required String title}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/cards/candidate'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title}),
    );
    _check(resp);
    return LearnTradingCandidateDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 反哺候选列表（V2 批 3）：GET /learn/cards/candidates。
  Future<List<LearnTradingCandidateDto>> getLearnCandidates() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/cards/candidates'),
      headers: _headers,
    );
    _check(resp);
    final List raw = jsonDecode(utf8.decode(resp.bodyBytes));
    return raw
        .map((e) => LearnTradingCandidateDto.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// 删除反哺候选（V2 批 3）：DELETE /learn/cards/candidates?title=（幂等）。
  Future<void> deleteLearnCandidate(String title) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/learn/cards/candidates')
          .replace(queryParameters: {'title': title}),
      headers: _headers,
    );
    _check(resp);
  }

  /// 复习提醒开关读（S-learn2 2026-09-07）：GET /learn/push-settings → {"learn-review": bool}。
  Future<bool> getLearnReviewEnabled() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/push-settings'),
      headers: _headers,
    );
    _check(resp);
    final json = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (json['learn-review'] as bool?) ?? true;
  }

  /// 复习提醒开关写（S-learn2）：PUT /learn/push-settings/learn-review，body {"enabled": bool}。
  Future<void> setLearnReviewEnabled(bool enabled) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/learn/push-settings/learn-review'),
      headers: _headers,
      body: jsonEncode({'enabled': enabled}),
    );
    _check(resp);
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

  /// mediaPath（`records/yyyy/MM/media/{recordId}.{ext}`）→ 记录 id。
  /// 后端 FeedEntry 的 mediaPaths 给的是存储相对路径，文件名即记录 id
  /// （`RecordFileRepository.mediaPath`），前端据此拼每张图的原图 URL。
  static String? recordIdOfMediaPath(String? mediaPath) {
    if (mediaPath == null || mediaPath.isEmpty) return null;
    final name = mediaPath.split('/').last;
    final dot = name.indexOf('.');
    final id = dot > 0 ? name.substring(0, dot) : name;
    return id.startsWith('rec_') ? id : null;
  }

  /// 一次投递的多张图路径 → 原图 URL 列表（按投递顺序）。
  /// 单图/历史条目（路径格式不符但 `mediaPath` 在）时回退 [fallbackRecordId]——
  /// 旧口径 `mediaUrl(entry.id)` 一字不变地保留，防后端路径格式变化导致图挂掉。
  List<String> mediaUrlsForPaths(List<String> paths, {String? fallbackRecordId}) {
    final urls = <String>[];
    for (final p in paths) {
      final id = recordIdOfMediaPath(p);
      if (id != null) urls.add(mediaUrl(id));
    }
    if (urls.isEmpty && fallbackRecordId != null && fallbackRecordId.isNotEmpty) {
      return [mediaUrl(fallbackRecordId)];
    }
    return urls;
  }

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

  /// 修改本人密码（2026-09-04 web 自助改密）：POST /api/v1/auth/password，
  /// body {oldPassword, newPassword} → 200 {message, kickedSessions}；返回被踢会话数
  /// （后端踢除该账号**其它**会话，保留当前）。
  /// 401 两种语义需区分：`原密码错误`（会话仍有效，弹窗内人话提示，**不**全局登出）
  /// vs `会话已失效`（AuthFilter 拦截，触发 [onUnauthorized] 回登录页）。
  Future<int> changePassword({
    required String oldPassword,
    required String newPassword,
  }) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/auth/password'),
      headers: _headers,
      body: jsonEncode({'oldPassword': oldPassword, 'newPassword': newPassword}),
    );
    if (resp.statusCode >= 400) {
      final text = utf8.decode(resp.bodyBytes);
      // 后端：AuthFilter 401 文案含「会话/未登录」；控制器 AuthException 401 为业务
      // 错误（如「原密码错误」）——后者不能误判会话失效把用户踢回登录页。
      final sessionInvalid = resp.statusCode == 401 &&
          (text.contains('会话') || text.contains('未登录'));
      if (sessionInvalid && onUnauthorized != null) onUnauthorized!();
      throw ApiException(resp.statusCode, 'API 请求失败（HTTP ${resp.statusCode}）', text);
    }
    final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (data['kickedSessions'] as num?)?.toInt() ?? 0;
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
  /// 2026-09-26（09-19 深审 P2-5，web 侧补消费）：`ledgerOnly` = 命中券商快照锚定、
  /// **只落流水不改账**的笔数（持仓/现金以快照为准）；`duplicated` = 之前已经记过的笔数。
  /// 两者都必须在回执里说清——否则用户会看到「点了确认、一笔都没入账、而且没有解释」，
  /// 那正是本条登记时的原话（app 侧早已消费，web 一直没解析）。
  final int ledgerOnly, duplicated;
  final List<String> failures;

  TradeLogConfirmResult({required this.confirmed, required this.failed,
      required this.skipped, required this.failures,
      this.ledgerOnly = 0, this.duplicated = 0});

  factory TradeLogConfirmResult.fromJson(Map<String, dynamic> json) => TradeLogConfirmResult(
    confirmed: json['confirmed'] as int? ?? 0,
    failed: json['failed'] as int? ?? 0,
    skipped: json['skipped'] as int? ?? 0,
    ledgerOnly: json['ledgerOnly'] as int? ?? 0,
    duplicated: json['duplicated'] as int? ?? 0,
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
  static const String action = 'action'; // RFC 20260917：待办卡已撤出 Feed；仅用于防御式丢弃历史条目
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

/// 一次投递里的一张图（[ApiService.uploadImages] 入参；由 UI 层 PickedImage 转换）。
class MediaUploadFile {
  final List<int> bytes;
  final String filename;
  final String mimeType; // image/png、image/jpeg…

  const MediaUploadFile({required this.bytes, required this.filename, required this.mimeType});
}

/// 一次投递的批量响应（POST /records/media/batch）。
///
/// [type] `image` = 无提问（[summary] 为多图综合总结）；`image_qa` = 有提问（[answer] 有值）。
/// [duplicated] true = 幂等键命中（本次未重复入库，返回首次结果）。
class BatchMediaResponse {
  final String recordId;
  final List<String> mediaIds;
  final String type;
  final String intent;
  final String summary;
  final String? answer;
  final List<String> tags;
  final String domain;
  final bool duplicated;

  BatchMediaResponse({
    required this.recordId,
    required this.mediaIds,
    required this.type,
    required this.intent,
    required this.summary,
    this.answer,
    required this.tags,
    required this.domain,
    required this.duplicated,
  });

  /// 本回合是否带提问（后端据图作答，answer 非空）。
  bool get isQa => type == 'image_qa' && (answer?.isNotEmpty ?? false);

  factory BatchMediaResponse.fromJson(Map<String, dynamic> json) => BatchMediaResponse(
        recordId: json['recordId'] as String? ?? '',
        // 防御式：mediaIds 缺失/脏数据兜底为空列表（旧后端未实现时前端不崩）
        mediaIds: json['mediaIds'] is List
            ? (json['mediaIds'] as List).map((e) => e.toString()).toList()
            : const [],
        type: json['type'] as String? ?? 'image',
        intent: json['intent'] as String? ?? 'log',
        summary: json['summary'] as String? ?? '',
        answer: json['answer'] as String?,
        tags: (json['tags'] as List?)?.cast<String>() ?? [],
        domain: json['domain'] as String? ?? 'life',
        duplicated: json['duplicated'] as bool? ?? false,
      );
}

class FeedResponse {
  final List<FeedEntryResponse> entries;
  final int totalToday;

  /// 2026-09-18 空态分流（对齐 adai-app 同名字段）：这个用户**有没有过历史记录**（不限当天）。
  /// 判据必须放服务端：本地标记重装/换设备即丢，而「跨日打开 Feed 为空」正是本 bug 的场景
  /// （老用户每天凌晨都会被本地标记当成新账号）。
  /// 默认 false = 真·新账号口径：旧后端不返回该字段时降级为当前行为（不崩、不报错）。
  final bool hasHistory;

  FeedResponse({required this.entries, required this.totalToday, this.hasHistory = false});

  factory FeedResponse.fromJson(Map<String, dynamic> json) => FeedResponse(
    entries: (json['entries'] as List).map((e) => FeedEntryResponse.fromJson(e)).toList(),
    totalToday: json['totalToday'] as int? ?? 0,
    // 旧后端无此字段 → ?? false（降级为新账号口径，即改动前的行为）
    hasHistory: json['hasHistory'] as bool? ?? false,
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
  /// P1-多图1（2026-09-22）：一次投递的多张图（按投递顺序）。
  /// 后端新契约；旧后端/无该字段时由 [mediaPath] 降级为单元素列表（单值兼容）。
  final List<String> mediaPaths;
  final String? intent;
  final String? summary;
  final List<Map<String, dynamic>>? turns;
  final String domain;
  final String updatedAt; // P1-5（2026-08-23 app 体感）：最后活跃 ISO 时间戳
  // P2-UI12（2026-09-16）：后端把「同一分钟同向成交」折叠成一条卡，这里是被折叠进本条的
  // 原始记录 id（删除时要删全，否则「删了又回来」）；未折叠/后端未返回 → 空列表。
  final List<String> mergedIds;

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
    this.mediaPaths = const [],
    this.intent,
    this.summary,
    this.turns,
    this.domain = 'life',
    this.updatedAt = '',
    this.mergedIds = const [],
  });

  factory FeedEntryResponse.fromJson(Map<String, dynamic> json) {
    // P1-多图1：mediaPaths（数组，按顺序）优先；旧后端只有单值 mediaPath → 降级单元素列表
    final rawPaths = json['mediaPaths'] is List
        ? (json['mediaPaths'] as List).map((e) => e.toString()).toList()
        : const <String>[];
    final singlePath = json['mediaPath'] as String?;
    return FeedEntryResponse(
      type: json['type'] as String? ?? '',
      id: json['id'] as String,
      sourceRecordId: json['sourceRecordId'] as String?,
      title: json['title'] as String? ?? '',
      content: json['content'] as String,
      tags: (json['tags'] as List?)?.cast<String>() ?? [],
      time: json['time'] as String? ?? json['timeString'] as String? ?? '',
      date: json['date'] as String? ?? '',
      mediaPath: singlePath,
      mediaPaths: rawPaths.isNotEmpty
          ? rawPaths
          : (singlePath != null && singlePath.isNotEmpty ? [singlePath] : const []),
      intent: json['intent'] as String?,
      summary: json['summary'] as String?,
      turns: (json['turns'] as List?)?.cast<Map<String, dynamic>>(),
      domain: json['domain'] as String? ?? 'life',
      updatedAt: json['updatedAt'] as String? ?? '',
      // 防御式：null / 不是 List / 脏元素都兜住（非 List → 空；元素统一转字符串）
      mergedIds: json['mergedIds'] is List
          ? (json['mergedIds'] as List).map((e) => e.toString()).toList()
          : const [],
    );
  }
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

/// 宽松取数值：number 原样，数字字符串（后端偶发字符串化）解析，其余（缺失/ null / 认不出）
/// → null。null 的语义是「没算出来」，前端必须渲染「—」，**绝不兜底成 0**。
double? _optDouble(dynamic v) {
  if (v is num) return v.toDouble();
  if (v is String) return double.tryParse(v.trim());
  return null;
}

/// 逐票当日口径（`GET /api/v1/trading/positions/daily` 的 `daily[symbol]`）。
/// 四个字段每个都可能 null（缺昨收 → todayPnl/dayChangePct 为 null；总资产为 0 → positionRatio 为 null）。
class PositionDailyItem {
  /// 当日盈亏（券商口径＝今天真实赚亏，不是累计浮盈金额）；null＝缺昨收没算出来。
  final double? todayPnl;
  /// 昨收价；null＝缺昨收。
  final double? yesterdayClose;
  /// 今日涨跌幅 %（(现价−昨收)/昨收）；null＝缺昨收。
  final double? dayChangePct;
  /// 该股市值占总资产（含现金）的百分比。
  final double? positionRatio;

  PositionDailyItem({
    this.todayPnl,
    this.yesterdayClose,
    this.dayChangePct,
    this.positionRatio,
  });

  factory PositionDailyItem.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return PositionDailyItem(
      todayPnl: _optDouble(m['todayPnl']),
      yesterdayClose: _optDouble(m['yesterdayClose']),
      dayChangePct: _optDouble(m['dayChangePct']),
      positionRatio: _optDouble(m['positionRatio']),
    );
  }
}

/// `GET /api/v1/trading/positions/daily` 响应：持仓主数据 + 当日口径。
/// 总仓位/现金比例 null＝总资产为 0（算不出比例，前端整段不显示）。
/// notes 非空＝有未计入项（当日盈亏偏小），必须如实提示，不能装作没事。
class PositionsDailyResponse {
  final List<PositionItem> positions;
  final Map<String, PositionDailyItem> daily;
  final double? totalAssets;
  final double? totalMarketValue;
  final double? cashBalance;
  /// 总仓位 %（持仓总市值/总资产）＝「几成仓」；null＝总资产为 0。
  final double? totalPositionRatio;
  /// 现金比例 %；null＝总资产为 0。
  final double? cashRatio;
  final List<String> notes;

  PositionsDailyResponse({
    required this.positions,
    required this.daily,
    this.totalAssets,
    this.totalMarketValue,
    this.cashBalance,
    this.totalPositionRatio,
    this.cashRatio,
    this.notes = const [],
  });

  factory PositionsDailyResponse.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    final rawPositions = m['positions'];
    final rawDaily = m['daily'];
    final daily = <String, PositionDailyItem>{};
    if (rawDaily is Map) {
      rawDaily.forEach((k, v) {
        final symbol = k?.toString() ?? '';
        if (symbol.isEmpty) return;
        daily[symbol] = PositionDailyItem.fromJson(v);
      });
    }
    return PositionsDailyResponse(
      positions: rawPositions is List
          ? rawPositions.map((e) => PositionItem.fromJson(e)).toList()
          : <PositionItem>[],
      daily: daily,
      totalAssets: _optDouble(m['totalAssets']),
      totalMarketValue: _optDouble(m['totalMarketValue']),
      cashBalance: _optDouble(m['cashBalance']),
      totalPositionRatio: _optDouble(m['totalPositionRatio']),
      cashRatio: _optDouble(m['cashRatio']),
      notes: (m['notes'] is List)
          ? (m['notes'] as List)
              .map((e) => e?.toString() ?? '')
              .where((s) => s.isNotEmpty)
              .toList()
          : <String>[],
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
  /// 浮动盈亏%：**负/零成本时为 null**（后端 Position.pnlPercent 语义，2026-09-13 负成本批）
  /// ——前端必须渲染成「—」而不是 0%：「0%」会被读成「没涨没跌」，与「成本已为负」是两回事。
  final double? pnlPercent;
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
    this.pnlPercent,
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
    pnlPercent: (json['pnlPercent'] as num?)?.toDouble(),
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
  /// 2026-09-16：各标的累计手续费（买入/卖出/合计）——批次弹窗展示。
  /// 卖出含印花税万 5（仅卖出收），费率约为买入 6 倍（用户实测 442 vs 2732）。
  final Map<String, SymbolFee> fees;

  LotsResponse({required this.lots, required this.reconcile, this.fees = const {}});

  factory LotsResponse.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    final feeMap = <String, SymbolFee>{};
    for (final e in (m['fees'] as List?) ?? const []) {
      final f = SymbolFee.fromJson(e);
      if (f.symbol.isNotEmpty) feeMap[f.symbol] = f;
    }
    return LotsResponse(
      lots: ((m['lots'] as List?) ?? const [])
          .map((e) => LotItem.fromJson(e))
          .toList(),
      reconcile: ((m['reconcile'] as List?) ?? const [])
          .map((e) => ReconcileLine.fromJson(e))
          .toList(),
      fees: feeMap,
    );
  }
}

/// 单标的累计手续费（2026-09-16）：买与卖差别大——卖出多一道印花税（万 5，仅卖出收）。
class SymbolFee {
  final String symbol;
  final double buy;
  final double sell;
  final double total;
  const SymbolFee({this.symbol = '', this.buy = 0, this.sell = 0, this.total = 0});

  factory SymbolFee.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return SymbolFee(
      symbol: m['symbol'] as String? ?? '',
      buy: (m['buy'] as num?)?.toDouble() ?? 0,
      sell: (m['sell'] as num?)?.toDouble() ?? 0,
      total: (m['total'] as num?)?.toDouble() ?? 0,
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
  /// 剩余部分浮动盈亏%：**负/零成本时为 null**（后端给「—」语义，2026-09-13 负成本批）
  final double? pnlPct;
  final double? stopLossPrice; // 止损（未设时后端已按默认 −7% 兜底返回）
  final double? stopLossDistancePct; // 距止损%（正=安全，负=已破）
  final String? buyPoint;
  final String? role;
  final bool initial; // 初始底仓批次
  final bool closed; // 已全部卖出（回合）
  final double realizedPnl; // 整批已实现盈亏（closed 时有效）
  final double buyFee; // 2026-09-16：该批次买入手续费合计（用户要求看到手续费体现）

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
    this.pnlPct,
    this.stopLossPrice,
    this.stopLossDistancePct,
    this.buyPoint,
    this.role,
    required this.initial,
    required this.closed,
    required this.realizedPnl,
    this.buyFee = 0,
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
      pnlPct: (m['pnlPct'] as num?)?.toDouble(),
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

/// 复盘响应 DTO（GET /api/v1/trading/review）。
class ReviewResponse {
  final String date; // yyyy-MM-dd
  final String content; // markdown 复盘内容

  ReviewResponse({required this.date, required this.content});

  factory ReviewResponse.fromJson(Map<String, dynamic> json) => ReviewResponse(
    date: json['date'] as String? ?? '',
    content: json['content'] as String? ?? '',
  );
}

/// 复盘提交响应 DTO（POST /api/v1/trading/review，2026-09-07 提交即返回）。
/// status：exists（已有复盘，GET 即取）/ running（同日在生成中）/ pending（已受理后台生成）。
class ReviewSubmitResponse {
  final String date; // yyyy-MM-dd
  final String status; // exists | running | pending

  ReviewSubmitResponse({required this.date, required this.status});

  factory ReviewSubmitResponse.fromJson(Map<String, dynamic> json) => ReviewSubmitResponse(
    date: json['date'] as String? ?? '',
    status: json['status'] as String? ?? '',
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

/// 单个区间的盈亏（今日 / 本周 / 本月）。
/// pnl 单位元；pct 为百分数（null = 区间起点前无曲线点 / 锚定日之前不可追溯）；
/// partial=true 表示该区间只有部分可追溯——UI 如实标注，不假装完整。
class PeriodPnlDto {
  final double? pnl;
  final double? pct;
  final bool partial;
  const PeriodPnlDto({this.pnl, this.pct, this.partial = false});

  static PeriodPnlDto? fromJson(dynamic j) {
    if (j is! Map) return null;
    return PeriodPnlDto(
      pnl: (j['pnl'] as num?)?.toDouble(),
      pct: (j['pct'] as num?)?.toDouble(),
      partial: j['partial'] == true,
    );
  }
}

/// 日 / 周 / 月盈亏（GET /api/v1/trading/pnl-periods）。
class PnlPeriodsDto {
  final PeriodPnlDto? today;
  final PeriodPnlDto? week;
  final PeriodPnlDto? month;
  final String asOf;
  final String? anchorDate;
  final String note;
  const PnlPeriodsDto({this.today, this.week, this.month, this.asOf = '',
      this.anchorDate, this.note = ''});

  factory PnlPeriodsDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return PnlPeriodsDto(
      today: PeriodPnlDto.fromJson(m['today']),
      week: PeriodPnlDto.fromJson(m['week']),
      month: PeriodPnlDto.fromJson(m['month']),
      asOf: m['asOf']?.toString() ?? '',
      anchorDate: m['anchorDate']?.toString(),
      note: m['note']?.toString() ?? '',
    );
  }
}

/// 账户总体快照（资金股份查询导入，券商口径）。
class AccountSnapshotDto {
  final double assets, cash, available, withdrawable, marketValue, pnl, todayPnl;
  final double principal;
  final String snapshotDate;
  // P2-交易48（2026-09-14）：当日盈亏来源（broker=券商「资金股份」文件该列求和 /
  // calc=系统按当日成交流水精算 / '' = 未知或旧后端缺字段）。UI 据此标注口径；
  // 未知一律不标（宁可不说，也不编造来源与日期）。
  final String todayPnlSource;
  // P2-交易69（2026-09-23）：现金这个数对应的**券商快照日期**（≠ snapshotDate）。
  // snapshotDate 是收盘更新的日期（每个交易日都被刷新），而现金只在导入「资金股份查询」时才更新
  // —— 两者混用会让人误以为手上这个现金数是今天的（生产上曾因此虚高 23,686.15、总盈亏少报 2.37 万）。
  final String cashDate;
  // 现金健康度人话（负现金 / 无券商来源 / 过期），'' = 无需提示——**文案由后端给**，前端只渲染。
  final String cashNote;
  // P2-交易66（2026-09-23）：本金置信度说明（手填本金 + 历史出入金零记录时的一句话），'' = 不提示。
  final String principalNote;

  AccountSnapshotDto({required this.assets, required this.cash, required this.available,
      required this.withdrawable, required this.marketValue, required this.pnl,
      required this.todayPnl, required this.principal, required this.snapshotDate,
      this.todayPnlSource = '', this.cashDate = '', this.cashNote = '',
      this.principalNote = ''});

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
      // 宽松：非字符串（数字/bool/对象）也安全转字符串；null/缺字段 → ''（不标来源）
      todayPnlSource: m['todayPnlSource']?.toString() ?? '',
      // P2-交易69：旧后端缺这两个字段 → ''（不提示、不崩）
      cashDate: m['cashDate']?.toString() ?? '',
      cashNote: m['cashNote']?.toString() ?? '',
      principalNote: m['principalNote']?.toString() ?? '',
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
/// RFC 20260909 批1：双轨后自动收录的行带 provenance=flow，券商导入/人工行为 import（老行缺省 import）。
class SoldTradeDto {
  final String symbol, name, tradeCount, verdict, psychology, provenance;
  final String? buyDate, sellDate;
  final int holdDays;
  final double holdPnlPct;

  SoldTradeDto({required this.symbol, required this.name, required this.buyDate,
      required this.sellDate, required this.holdDays, required this.tradeCount,
      required this.holdPnlPct, required this.verdict, required this.psychology,
      this.provenance = 'import'});

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
      provenance: m['provenance']?.toString() ?? 'import',
    );
  }
}

/// 待补清仓（RFC 20260909 批1 条件 B：流水已清仓但缺买入基线 → 只提示不写脏档案）。
class PendingClearanceDto {
  final String symbol, name;
  final String? sellDate, reason;

  PendingClearanceDto({required this.symbol, required this.name, this.sellDate, this.reason});

  factory PendingClearanceDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return PendingClearanceDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      sellDate: m['sellDate']?.toString(),
      reason: m['reason']?.toString(),
    );
  }
}

/// 清仓股列表响应（双轨对象契约：sold 复盘档案 + pendingClearances 待补清单）。
class SoldOverview {
  final List<SoldTradeDto> sold;
  final List<PendingClearanceDto> pending;

  SoldOverview({required this.sold, required this.pending});
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

/// 清仓「卖掉之后到现在」一行（GET /api/v1/trading/sold/after-close，2026-10-08 清仓列）。
/// 基准 = 卖出日（或其后第一根）K 线收盘，最新 = 区间最后一根；
/// pct/direction 为 null = 没算出来（note 说明原因人话，显示「—」不编）。
class SoldAfterCloseDto {
  final String symbol, name;
  final String? sellDate, baseDate, latestDate, direction, note;
  final double? baseClose, latestClose, pct;

  SoldAfterCloseDto({required this.symbol, required this.name, this.sellDate,
      this.baseDate, this.baseClose, this.latestDate, this.latestClose,
      this.pct, this.direction, this.note});

  factory SoldAfterCloseDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return SoldAfterCloseDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      sellDate: m['sellDate']?.toString(),
      baseDate: m['baseDate']?.toString(),
      baseClose: (m['baseClose'] as num?)?.toDouble(),
      latestDate: m['latestDate']?.toString(),
      latestClose: (m['latestClose'] as num?)?.toDouble(),
      pct: (m['pct'] as num?)?.toDouble(),
      direction: m['direction']?.toString(),
      note: m['note']?.toString(),
    );
  }
}

/// 丢行明细（`unparsed`）统一解析（P2-交易83，2026-10-04）：非列表/缺字段 → 空列表；
/// 元素安全转字符串（后端下发的是人话字符串，类型不符也不炸）。与历史成交导入同口径。
List<String> _unparsedLines(dynamic json) {
  if (json is! Map<String, dynamic>) return const [];
  return ((json['unparsed'] as List?) ?? const []).map((e) => e.toString()).toList();
}

/// 丢行计数（`unparsedCount`）统一解析：缺字段/非法 → 退回明细条数
/// （后端只在**有丢行时**才下发这两个字段，明细也空时结果自然是 0；
/// 宁可少报也不虚报——与 HistoricalTradeImportResult 同口径）。
int _unparsedCount(dynamic json, List<String> lines) {
  if (json is! Map<String, dynamic>) return 0;
  return (json['unparsedCount'] as num?)?.toInt() ?? lines.length;
}

/// 清仓股导入结果（POST /api/v1/trading/sold/import）。
/// 契约 v3.96（P2-交易83，2026-10-04）：有丢行时额外带 `unparsed`（逐条 = 行号 + 原文 + 原因）
/// 与 `unparsedCount`（= 明细条数），空则不带这两个字段 → 前端空列表 / 0。
/// 丢一行 = 该只清仓档案本次没进库（本导入按 symbol upsert、不删档案，故不拦导入，但必须可见）。
class SoldImportResult {
  final int imported;
  final List<String> unparsed;
  final int unparsedCount;

  SoldImportResult({required this.imported, this.unparsed = const [], this.unparsedCount = 0});

  factory SoldImportResult.fromJson(dynamic j) {
    final lines = _unparsedLines(j);
    return SoldImportResult(
      imported: j is Map<String, dynamic> ? (j['imported'] as num?)?.toInt() ?? 0 : 0,
      unparsed: lines,
      unparsedCount: _unparsedCount(j, lines),
    );
  }
}

/// 资金查询导入结果。
class CashImportResult {
  final double cash, assets;
  final int updatedCost;
  // P2-交易43（2026-09-14）：没认出来的明细行数（前端只认得出表头列的那几行）——
  // 丢一行 = 该只精确成本本次不更新。旧后端无此字段 → 0（行为与现在完全一致）。
  // ⚠️ 契约 v3.96 明确**这个字段保持 int 不变**（改类型会把导入打挂），升级走加字段式。
  final int unparsedRows;
  // P2-交易83（2026-10-04，契约 v3.96）：同一批丢行的**人话明细**（逐条 = 行号 + 原文 + 原因）——
  // 让用户知道「是**哪只票**的精确成本没更新」，不再只有一个数字。旧后端缺字段 → 空列表 / 0。
  final List<String> unparsed;
  final int unparsedCount;
  // 2026-10-05（P2-交易84）：锚定日的**依据**人话（有据/无据）——后端 additive 字段，
  // 旧后端缺字段 → null（不显示、不编造）。导入回执据此说明「这一天是怎么定下来的」。
  final String? anchorNote;
  final bool anchorWithEvidence;

  CashImportResult({required this.cash, required this.assets, required this.updatedCost,
      this.unparsedRows = 0, this.unparsed = const [], this.unparsedCount = 0,
      this.anchorNote, this.anchorWithEvidence = false});

  factory CashImportResult.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    final lines = _unparsedLines(j);
    return CashImportResult(
      cash: (m['cash'] as num?)?.toDouble() ?? 0,
      assets: (m['assets'] as num?)?.toDouble() ?? 0,
      updatedCost: (m['updatedCost'] as num?)?.toInt() ?? 0,
      unparsedRows: (m['unparsedRows'] as num?)?.toInt() ?? 0,
      unparsed: lines,
      unparsedCount: _unparsedCount(j, lines),
      anchorNote: _anchorNote(j),
      anchorWithEvidence: _anchorWithEvidence(j),
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

// ── 统一导入 / 三粒度分析（R-12 / R-05 · 2026-10-06）──

/// 统一导入的一份文件载荷（`R-12`）：文件名（只用于识别日期，类别靠内容认）+ 原始字节。
class BundleUploadFile {
  final String name;
  final List<int> bytes;
  const BundleUploadFile(this.name, this.bytes);
}

/// 统一导入回执（POST /api/v1/trading/import）：逐份结果 + 成功/失败计数。
/// 契约：{dryRun, okCount, failedCount, files:[{filename, savedPath?, kind, kindLabel, ok, error?, detail?}]}。
class BundleImportReceipt {
  final bool dryRun;
  final int okCount;
  final int failedCount;
  final List<BundleFileResultDto> files;

  BundleImportReceipt({required this.dryRun, required this.okCount,
      required this.failedCount, required this.files});

  factory BundleImportReceipt.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return BundleImportReceipt(
      dryRun: m['dryRun'] == true,
      okCount: (m['okCount'] as num?)?.toInt() ?? 0,
      failedCount: (m['failedCount'] as num?)?.toInt() ?? 0,
      files: ((m['files'] as List?) ?? [])
          .map((e) => BundleFileResultDto.fromJson(e))
          .toList(),
    );
  }
}

/// 统一导入的单份回执：ok=false 时 error 为后端人话原因；
/// detail = 该份具体结果（形状随 kind 不同，界面按常见键渲染摘要）。
class BundleFileResultDto {
  final String filename;
  final String? savedPath;
  final String? kind; // CASH / POSITIONS / TRADES / SOLD / WATCHLIST / UNKNOWN
  final String? kindLabel; // 资金股份 / 持仓股 / 历史成交 / 清仓股 / 自选股 / 无法识别
  final bool ok;
  final String? error;
  final Map<String, dynamic> detail;

  BundleFileResultDto({required this.filename, this.savedPath, this.kind,
      this.kindLabel, required this.ok, this.error, this.detail = const {}});

  factory BundleFileResultDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return BundleFileResultDto(
      filename: m['filename']?.toString() ?? '未命名文件',
      savedPath: m['savedPath']?.toString(),
      kind: m['kind']?.toString(),
      kindLabel: m['kindLabel']?.toString(),
      ok: m['ok'] == true,
      error: m['error']?.toString(),
      detail: m['detail'] is Map ? Map<String, dynamic>.from(m['detail'] as Map) : const {},
    );
  }
}

/// 三粒度分析视图（GET /api/v1/trading/analysis/{scope}，`R-05`）。
/// scope=global 全局 / symbol 单标的 / round 单笔；label=人话标题。
/// R-04 通用 K 线（2026-10-07）：一张图四处共用 —— 持仓 / 自选 / 清仓 / 案例。
class TradingKlineDto {
  TradingKlineDto({
    required this.symbol,
    required this.window,
    required this.candles,
    required this.marks,
    this.stopLine,
    this.peakLine,
    this.held = false,
    this.closedAt,
    this.holdPnlPct,
    this.verdict,
    this.note,
  });

  final String symbol;
  final int window;
  /// 每项 {date, open, high, low, close, volume}（旧→新）。
  final List<Map<String, dynamic>> candles;
  /// 我的买卖点：{date, type: B|T|S, price, quantity, note}。
  final List<Map<String, dynamic>> marks;
  final double? stopLine;
  final double? peakLine;
  final bool held;
  final String? closedAt;
  final double? holdPnlPct;
  final String? verdict;
  /// 后端如实说法（行情取不到 / 代码不对）—— 有值时前端只显示它，不画空图。
  final String? note;

  bool get hasData => candles.isNotEmpty;

  factory TradingKlineDto.fromJson(Map<String, dynamic> j) {
    double? d(Object? v) => v is num ? v.toDouble() : null;
    final candles = <Map<String, dynamic>>[];
    for (final e in (j['candles'] as List? ?? const [])) {
      final m = (e as Map).cast<String, dynamic>();
      final close = d(m['close']);
      if (close == null) continue; // 缺值的 K 线宁可少画，不补 0
      candles.add({
        'date': '${m['date']}',
        'open': d(m['open']) ?? close,
        'high': d(m['high']) ?? close,
        'low': d(m['low']) ?? close,
        'close': close,
        'volume': d(m['volume']) ?? 0,
      });
    }
    final marks = <Map<String, dynamic>>[];
    for (final e in (j['marks'] as List? ?? const [])) {
      marks.add((e as Map).cast<String, dynamic>());
    }
    final stop = j['stopLine'] is Map ? (j['stopLine'] as Map).cast<String, dynamic>() : null;
    final peak = j['peakLine'] is Map ? (j['peakLine'] as Map).cast<String, dynamic>() : null;
    final ctx = j['context'] is Map ? (j['context'] as Map).cast<String, dynamic>() : const {};
    return TradingKlineDto(
      symbol: '${j['symbol'] ?? ''}',
      window: (j['window'] as num?)?.toInt() ?? 90,
      candles: candles,
      marks: marks,
      stopLine: d(stop?['price']),
      peakLine: d(peak?['price']),
      held: ctx['held'] == true,
      closedAt: ctx['closedAt']?.toString(),
      holdPnlPct: d(ctx['holdPnlPct']),
      verdict: ctx['verdict']?.toString(),
      note: j['note']?.toString(),
    );
  }
}

class TradingAnalysisDto {
  final String scope;
  final String label;
  final List<AnalysisFactDto> description;
  final AnalysisContrastDto contrast;
  final AnalysisSummaryDto summary;

  TradingAnalysisDto({required this.scope, required this.label,
      required this.description, required this.contrast, required this.summary});

  factory TradingAnalysisDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return TradingAnalysisDto(
      scope: m['scope']?.toString() ?? '',
      label: m['label']?.toString() ?? '',
      description: ((m['description'] as List?) ?? [])
          .map((e) => AnalysisFactDto.fromJson(e))
          .toList(),
      contrast: AnalysisContrastDto.fromJson(m['contrast']),
      summary: AnalysisSummaryDto.fromJson(m['summary']),
    );
  }
}

/// 分析的一个数字（描述块）：value 可为 null（缺数据——界面显示「—」，**绝不渲染成 0**）、
/// 数字 / 字符串 / 列表（RoundBrief · Bucket · PeriodBucket · SizeBucket 形状的 Map）。
/// trace = 这个数字的出处（哪几笔 / 哪几天 / 说明）——「每个数字点得进去」的原料。
class AnalysisFactDto {
  final String key;
  final String label;
  final dynamic value;
  final String? unit;
  final AnalysisTraceDto trace;

  AnalysisFactDto({required this.key, required this.label, this.value, this.unit, required this.trace});

  factory AnalysisFactDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return AnalysisFactDto(
      key: m['key']?.toString() ?? '',
      label: m['label']?.toString() ?? '',
      value: m['value'],
      unit: m['unit']?.toString(),
      trace: AnalysisTraceDto.fromJson(m['trace']),
    );
  }
}

/// 数字的出处：roundIds / dates / note（全可空，空则不显示）。
class AnalysisTraceDto {
  final List<String> roundIds;
  final List<String> dates;
  final String? note;

  AnalysisTraceDto({this.roundIds = const [], this.dates = const [], this.note});

  factory AnalysisTraceDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return AnalysisTraceDto(
      roundIds: ((m['roundIds'] as List?) ?? []).map((e) => e.toString()).toList(),
      dates: ((m['dates'] as List?) ?? []).map((e) => e.toString()).toList(),
      note: m['note']?.toString(),
    );
  }

  bool get isEmpty => roundIds.isEmpty && dates.isEmpty && (note == null || note!.isEmpty);
}

/// 规则对照：hasRules=false → reason 明说「判不了」（不拿别人的规则替他判）。
class AnalysisContrastDto {
  final bool hasRules;
  final String? reason;
  final List<AnalysisRuleHitDto> ruleHits;

  AnalysisContrastDto({required this.hasRules, this.reason, this.ruleHits = const []});

  factory AnalysisContrastDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return AnalysisContrastDto(
      hasRules: m['hasRules'] == true,
      reason: m['reason']?.toString(),
      ruleHits: ((m['ruleHits'] as List?) ?? [])
          .map((e) => AnalysisRuleHitDto.fromJson(e))
          .toList(),
    );
  }
}

/// 一条规则的命中：rule（编号）+ text（规则原文）+ count（命中几笔）+ roundIds（哪几笔）。
class AnalysisRuleHitDto {
  final String rule;
  final String text;
  final int count;
  final List<String> roundIds;

  AnalysisRuleHitDto({required this.rule, required this.text, required this.count,
      this.roundIds = const []});

  factory AnalysisRuleHitDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return AnalysisRuleHitDto(
      rule: m['rule']?.toString() ?? '',
      text: m['text']?.toString() ?? '',
      count: (m['count'] as num?)?.toInt() ?? 0,
      roundIds: ((m['roundIds'] as List?) ?? []).map((e) => e.toString()).toList(),
    );
  }
}

/// 总结三句：fact 事实 / contrast 规则对照 / question 留给你的问题（全可空）。
class AnalysisSummaryDto {
  final String? fact;
  final String? contrast;
  final String? question;

  AnalysisSummaryDto({this.fact, this.contrast, this.question});

  factory AnalysisSummaryDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return AnalysisSummaryDto(
      fact: m['fact']?.toString(),
      contrast: m['contrast']?.toString(),
      question: m['question']?.toString(),
    );
  }
}

/// 持仓初始化导入结果（POST /api/v1/trading/positions/import，通达信）。
class PositionImportResult {
  final int imported;
  final List<String> missingStopLoss;
  // 2026-10-05（P2-交易84）：锚定日依据人话（有据/无据）；旧后端缺字段 → null。
  final String? anchorNote;
  final bool anchorWithEvidence;

  PositionImportResult({required this.imported, required this.missingStopLoss,
      this.anchorNote, this.anchorWithEvidence = false});

  factory PositionImportResult.fromJson(dynamic json) {
    if (json is! Map<String, dynamic>) {
      return PositionImportResult(imported: 0, missingStopLoss: []);
    }
    return PositionImportResult(
      imported: (json['imported'] as num?)?.toInt() ?? 0,
      missingStopLoss: ((json['missingStopLoss'] as List?) ?? [])
          .map((e) => e.toString())
          .toList(),
      anchorNote: _anchorNote(json),
      anchorWithEvidence: _anchorWithEvidence(json),
    );
  }
}

/// 锚定依据人话（P2-交易84）：后端 `anchor.note`；缺字段/旧后端 → null（不显示、不编造）。
String? _anchorNote(dynamic json) {
  if (json is! Map<String, dynamic>) return null;
  final a = json['anchor'];
  if (a is! Map<String, dynamic>) return null;
  final note = a['note']?.toString();
  return (note == null || note.isEmpty) ? null : note;
}

/// 锚定日是否**有据**（P2-交易84）：缺字段 → false（不冒充确定）。
bool _anchorWithEvidence(dynamic json) {
  if (json is! Map<String, dynamic>) return false;
  final a = json['anchor'];
  if (a is! Map<String, dynamic>) return false;
  return a['withEvidence'] == true;
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
  // ── 2026-09-12 账实一致性批 ──
  final List<RejectedLineDto> rejected; // 无法归属持仓的真实成交明细（已落流水，未动持仓/现金）
  final AnchorStatusDto? anchor; // 券商快照锚定状态（旧后端无此字段 → null）
  final bool dryRun; // true = 这份结果是预检计划，未落盘
  final ImportPlanDto? plan; // 仅 dryRun 时存在
  // ── P2-交易43（2026-09-14）──
  // 没看懂、**根本没导入**的行（人话逐条，如「第 3 行「2026080X …」：成交日期不是 yyyyMMdd 格式」）。
  // 旧后端无此字段 → 空列表/0（行为与现在完全一致，不报错、不显示）。
  final List<String> unparsed;
  // 后端计数：与 unparsed 条数**恒等**（明细是全量给的，不截断、不分页）——TradingController
  // 的 trades/import、sold/import、imports/cash 三段都是「非空才下发 unparsed + unparsedCount
  // = size()」；两者都空时后端不带这两个字段 → 前端空列表 + 0（缺字段时退回明细条数）。
  final int unparsedCount;

  HistoricalTradeImportResult({
    required this.imported,
    required this.updated,
    required this.skipped,
    required this.nonTrades,
    required this.lines,
    this.syncMode = 'append',
    this.summary,
    this.rejected = const [],
    this.anchor,
    this.dryRun = false,
    this.plan,
    this.unparsed = const [],
    this.unparsedCount = 0,
  });

  factory HistoricalTradeImportResult.fromJson(dynamic json) {
    if (json is! Map<String, dynamic>) {
      return HistoricalTradeImportResult(
          imported: 0, updated: 0, skipped: 0, nonTrades: 0, lines: []);
    }
    final unparsed = ((json['unparsed'] as List?) ?? const [])
        .map((e) => e.toString())
        .toList();
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
      rejected: ((json['rejected'] as List?) ?? const [])
          .map((e) => RejectedLineDto.fromJson(e))
          .toList(),
      anchor: json['anchor'] == null ? null : AnchorStatusDto.fromJson(json['anchor']),
      dryRun: json['dryRun'] == true,
      plan: json['plan'] == null ? null : ImportPlanDto.fromJson(json['plan']),
      unparsed: unparsed,
      // 计数缺失/非法 → 退回明细条数（宁可少报，也不虚报）
      unparsedCount: (json['unparsedCount'] as num?)?.toInt() ?? unparsed.length,
    );
  }
}

/// 无法归属持仓的成交行（POST /trading/trades/import 的 `rejected`，2026-09-12）。
/// 语义：该笔**已落逐笔流水**，但持仓/现金未变——快照基线缺口 / 漏导买入 / 重复流水污染。
/// 必须显眼展示：旧实现只写日志并计入「跳过 N 笔」，真实卖出就此消失（本次生产事故根因）。
class RejectedLineDto {
  final String symbol;
  final String name;
  final String direction; // 'BUY' | 'SELL'（后端 TradeDirection 枚举名）
  final int volume;
  final double? price; // 可空保持可空
  final String entryDate; // yyyy-MM-dd
  final String reason; // 中文人话原因

  RejectedLineDto({
    required this.symbol,
    required this.name,
    required this.direction,
    required this.volume,
    this.price,
    required this.entryDate,
    required this.reason,
  });

  /// 人话方向（BUY→买入 / SELL→卖出；缺失原样返回，不编造）。
  String get directionLabel {
    switch (direction.toUpperCase()) {
      case 'BUY':
        return '买入';
      case 'SELL':
        return '卖出';
      default:
        return direction;
    }
  }

  /// 明细行文案：`卖出 贵州茅台（600519）100 股 @ 1500.00 · 原因`。
  String get display {
    final qty = '${_thousandsNum(volume)} 股';
    final p = price == null ? '' : ' @ ${price!.toStringAsFixed(2)}';
    final who = name.isEmpty ? symbol : '$name（$symbol）';
    return '$directionLabel $who$qty$p${reason.isEmpty ? '' : ' · $reason'}';
  }

  factory RejectedLineDto.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return RejectedLineDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      direction: m['direction']?.toString() ?? '',
      volume: (m['volume'] as num?)?.toInt() ?? 0,
      price: (m['price'] as num?)?.toDouble(),
      entryDate: m['entryDate']?.toString() ?? '',
      reason: m['reason']?.toString() ?? '',
    );
  }
}

/// 整数千分位（明细文案用）：10000 → 10,000。
String _thousandsNum(int v) {
  final s = v.abs().toString();
  final buf = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    buf.write(s[i]);
    final remaining = s.length - 1 - i;
    if (remaining > 0 && remaining % 3 == 0) buf.write(',');
  }
  return '${v < 0 ? '-' : ''}$buf';
}

/// 券商快照锚定状态（GET/PUT /trading/anchor + 导入结果 `anchor`，2026-09-12）。
/// [known]=false → 无法判断哪些成交已包含在券商快照口径内（后端对需要改账的导入 fail-closed 400）；
/// [holdingsKnown]=false → 快照持仓基线未记录，对账（integrity）无法判定（诚实降级，不误报差异）。
class AnchorStatusDto {
  final String? positionsReplace; // yyyy-MM-dd（「持仓股」快照导入日）
  final String? cashImport; // yyyy-MM-dd（「资金股份查询」快照导入日）
  final bool known;
  final bool holdingsKnown;
  final String? anchorDate; // 生效锚定日（较晚者）；未知 → null

  AnchorStatusDto({
    this.positionsReplace,
    this.cashImport,
    required this.known,
    required this.holdingsKnown,
    this.anchorDate,
  });

  factory AnchorStatusDto.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return AnchorStatusDto(
      positionsReplace: m['positionsReplace']?.toString(),
      cashImport: m['cashImport']?.toString(),
      known: m['known'] == true,
      holdingsKnown: m['holdingsKnown'] == true,
      anchorDate: m['anchorDate']?.toString(),
    );
  }
}

/// 预检计划（dryRun=true 响应的 `plan`，2026-09-12）：这次导入会做什么——不落盘先给用户看。
/// 注意 JSON 键是 `new`（Dart 关键字）→ 字段名 [newCount]。
class ImportPlanDto {
  final int newCount;
  final int merged;
  final int skipped;
  final int nonTrades;
  final int wouldReject;
  final bool anchorKnown;
  final String syncMode; // 'sync'（要改持仓/现金）| 'append'（只补流水）

  ImportPlanDto({
    required this.newCount,
    required this.merged,
    required this.skipped,
    required this.nonTrades,
    required this.wouldReject,
    required this.anchorKnown,
    required this.syncMode,
  });

  factory ImportPlanDto.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return ImportPlanDto(
      newCount: (m['new'] as num?)?.toInt() ?? 0,
      merged: (m['merged'] as num?)?.toInt() ?? 0,
      skipped: (m['skipped'] as num?)?.toInt() ?? 0,
      nonTrades: (m['nonTrades'] as num?)?.toInt() ?? 0,
      wouldReject: (m['wouldReject'] as num?)?.toInt() ?? 0,
      anchorKnown: m['anchorKnown'] == true,
      syncMode: m['syncMode']?.toString() ?? 'append',
    );
  }
}

/// 账实对账差异行（GET /trading/integrity `drift`，2026-09-12）：
/// [derived] = 应有持仓（快照基线 [snapshotQty] + 锚点后流水净增减 [ledgerDelta]）。
/// [snapshotQty]/[holdings] 可空保持可空（基线未记录 / 标的不在持仓里）。
class DriftLineDto {
  final String symbol;
  final String name;
  final int? snapshotQty;
  final int ledgerDelta;
  final int derived;
  final int? holdings;
  final int diff;
  final String note;

  DriftLineDto({
    required this.symbol,
    required this.name,
    this.snapshotQty,
    required this.ledgerDelta,
    required this.derived,
    this.holdings,
    required this.diff,
    required this.note,
  });

  factory DriftLineDto.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    return DriftLineDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      snapshotQty: (m['snapshotQty'] as num?)?.toInt(),
      ledgerDelta: (m['ledgerDelta'] as num?)?.toInt() ?? 0,
      derived: (m['derived'] as num?)?.toInt() ?? 0,
      holdings: (m['holdings'] as num?)?.toInt(),
      diff: (m['diff'] as num?)?.toInt() ?? 0,
      note: m['note']?.toString() ?? '',
    );
  }
}

/// 账实一致性报告（GET /trading/integrity，2026-09-12）：锚定状态 + 差异 + 重放缺口 + 降级流水。
/// 降级诚实：锚定/基线缺失 → [note] 说明「无法判定」，drift/gaps 为空（不误报）。
class IntegrityReportDto {
  final AnchorStatusDto? anchor;
  final bool holdingsKnown;
  final List<DriftLineDto> drift;
  final List<RejectedLineDto> gaps; // 重放缺口（卖超/未持有）——与导入 rejected 同一件事
  /// 降级流水（2026-09-21，P1-交易61）：成交日 == 锚定日 → 只记流水、没进持仓。
  final List<DegradedLineDto> degraded;
  final String note;

  IntegrityReportDto({
    this.anchor,
    required this.holdingsKnown,
    required this.drift,
    required this.gaps,
    this.degraded = const [],
    required this.note,
  });

  /// 有需要用户看的东西吗（无差异 → 页面不显示任何横幅，不制造噪音）。
  /// 降级流水只在**锚定日是推断的**（inferred）时才算问题——锚定日明确时那些成交确实在快照里，
  /// 属于事实说明，天天挂横幅就是噪音。
  bool get hasIssue =>
      drift.isNotEmpty || gaps.isNotEmpty || degraded.any((d) => d.inferred);

  factory IntegrityReportDto.fromJson(dynamic json) {
    if (json is! Map<String, dynamic>) {
      return IntegrityReportDto(holdingsKnown: false, drift: const [], gaps: const [], note: '');
    }
    return IntegrityReportDto(
      anchor: json['anchor'] == null ? null : AnchorStatusDto.fromJson(json['anchor']),
      holdingsKnown: json['holdingsKnown'] == true,
      drift: ((json['drift'] as List?) ?? const [])
          .map((e) => DriftLineDto.fromJson(e))
          .toList(),
      gaps: ((json['gaps'] as List?) ?? const [])
          .map((e) => RejectedLineDto.fromJson(e))
          .toList(),
      degraded: ((json['degraded'] as List?) ?? const [])
          .map((e) => DegradedLineDto.fromJson(e))
          .toList(),
      note: json['note']?.toString() ?? '',
    );
  }
}

/// 降级成交流水（2026-09-21，P1-交易61）：成交日 == 锚定日，被按「已含在券商快照内」处理
/// （只记流水、未进持仓）。[inferred]=true 表示锚定日是按导入时刻**推断**的 →
/// 若快照实际基准日不是那天，这笔就不会体现在持仓里（对账据此把「假绿」变成可见）。
class DegradedLineDto {
  final String symbol;
  final String name;
  final String direction;
  final int volume;
  final double? price;
  final String? entryDate;
  final bool inferred;
  final String reason;

  DegradedLineDto({
    required this.symbol,
    required this.name,
    required this.direction,
    required this.volume,
    this.price,
    this.entryDate,
    required this.inferred,
    required this.reason,
  });

  factory DegradedLineDto.fromJson(dynamic json) {
    if (json is! Map<String, dynamic>) {
      return DegradedLineDto(
          symbol: '', name: '', direction: '', volume: 0, inferred: false, reason: '');
    }
    return DegradedLineDto(
      symbol: json['symbol']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      direction: json['direction']?.toString() ?? '',
      volume: (json['volume'] as num?)?.toInt() ?? 0,
      price: (json['price'] as num?)?.toDouble(),
      entryDate: json['entryDate']?.toString(),
      inferred: json['inferred'] == true,
      reason: json['reason']?.toString() ?? '',
    );
  }
}

/// 行情（K 线）链路可用性（GET /trading/market-data/health，RFC 20260923 D 批，2026-09-23）：
/// [ok]=false 表示「最近一次成功早于最近一次全失败」——三源全挂时后端只写日志，
/// 用户侧一切照旧（资金曲线平了、自选信号没了都长得像正常），这里把它变成页面上看得见的一句话。
///
/// **防御式解析的取舍**：所有字段都可能为 null（后端 record 里确实可空），且旧后端没有这个端点。
/// `ok` 缺失/非 bool 一律按 true（不报警）——漏报只是少一条提示，误报会让用户以为行情坏了而恐慌，
/// 后者消耗信任更快；解析绝不抛异常（半个残缺 JSON 不该把交易页拖成错误态）。
class MarketDataHealthDto {
  final bool ok;
  final String note; // 后端已写好的人话说明，直接透出（前端不再自造口径）
  final String? lastSuccessAt;
  final String? lastSuccessSource; // tdx / 腾讯 / 东财 / 新浪
  final String? lastFailureAt; // 「所有源都拿不到」的时刻
  final int consecutiveFailures; // 连续全失败次数（成功即清零）
  final String? lastFailedSymbol;
  final List<String> sources; // 当前启用的取数链（按序）
  // P2-交易58 前端侧（2026-10-04）：本地数据包（tdx）最后一根日期 `yyyy-MM-dd`。
  // 本地关掉 / 还没取过 → null（后端 record 里确实可空）；「本地止于哪天」决定资金曲线尾段
  // 与案例历史窗口有没有缺口，页面据此如实说出来（滞后才说，今天/null 零噪音）。
  final String? tdxLastDate;

  MarketDataHealthDto({
    required this.ok,
    required this.note,
    this.lastSuccessAt,
    this.lastSuccessSource,
    this.lastFailureAt,
    this.consecutiveFailures = 0,
    this.lastFailedSymbol,
    this.sources = const [],
    this.tdxLastDate,
  });

  /// 要不要给用户看横幅：**只有明确 ok=false 才报警**（ok=true 或拿不到信息 → 页面零显示）。
  bool get shouldWarn => !ok;

  factory MarketDataHealthDto.fromJson(dynamic json) {
    final m = json is Map<String, dynamic> ? json : <String, dynamic>{};
    // `sources`/`consecutiveFailures` 用 is 判断而非 `as List?`/`as num?`：
    // 类型错乱（如后端把次数写成字符串）时前者落安全默认，后者会抛——契约要求本 DTO 绝不抛。
    final rawSources = m['sources'];
    final rawFailures = m['consecutiveFailures'];
    return MarketDataHealthDto(
      ok: m['ok'] != false,
      note: m['note']?.toString() ?? '',
      lastSuccessAt: m['lastSuccessAt']?.toString(),
      lastSuccessSource: m['lastSuccessSource']?.toString(),
      lastFailureAt: m['lastFailureAt']?.toString(),
      consecutiveFailures: rawFailures is num ? rawFailures.toInt() : 0,
      lastFailedSymbol: m['lastFailedSymbol']?.toString(),
      sources: rawSources is List ? rawSources.map((e) => e.toString()).toList() : const [],
      tdxLastDate: m['tdxLastDate']?.toString(),
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

// ── 待办 DTO（RFC 20260917：纯清单两态）──

/// 待办条目：一句话 +（可选）到期日 + 两态状态。
class TodoResponse {
  final String id;
  final String title;

  /// 两态：OPEN / DONE（RFC 20260917；DOING/CANCELLED 已取消）。
  final String status;

  /// 到期日（YYYY-MM-DD）；null = 无到期日。
  final String? due;

  /// 来源记录 id（由记录自动进待办的溯源）；手动新建为 null。
  final String? sourceRecordId;
  final String createdAt;
  final String updatedAt;

  TodoResponse({
    required this.id,
    required this.title,
    required this.status,
    this.due,
    this.sourceRecordId,
    required this.createdAt,
    required this.updatedAt,
  });

  factory TodoResponse.fromJson(Map<String, dynamic> json) => TodoResponse(
    id: json['id'] as String? ?? '',
    title: json['title'] as String? ?? '',
    status: json['status'] as String? ?? 'OPEN',
    due: parseTodoDue(json['due']),
    sourceRecordId: json['sourceRecordId'] as String?,
    createdAt: json['createdAt'] as String? ?? '',
    updatedAt: json['updatedAt'] as String? ?? '',
  );

  /// 到期日防御式解析：只接受非空字符串（null / 空串 / 非字符串一律视为无到期日）。
  static String? parseTodoDue(dynamic raw) {
    if (raw is String) {
      final s = raw.trim();
      return s.isEmpty ? null : s;
    }
    return null;
  }
}

/// 待办统计（GET /todos/stats → {total, open, done}）。
class TodoStatsResponse {
  final int total;
  final int open;
  final int done;

  TodoStatsResponse({required this.total, required this.open, required this.done});

  factory TodoStatsResponse.fromJson(Map<String, dynamic> json) => TodoStatsResponse(
    total: json['total'] as int? ?? 0,
    open: json['open'] as int? ?? 0,
    done: json['done'] as int? ?? 0,
  );
}

// ── 资金曲线 DTO（2026-09-04 决策方案 A）──

/// 资金曲线点（date=交易日，YYYY-MM-DD；netValue/drawdown 可空）。
class EquityCurvePoint {
  final String date;
  final double totalAssets;
  final double cash;
  final double marketValue;
  final double invested;
  final double? netValue;
  final double? drawdown;

  EquityCurvePoint({
    required this.date,
    required this.totalAssets,
    required this.cash,
    required this.marketValue,
    required this.invested,
    this.netValue,
    this.drawdown,
  });

  factory EquityCurvePoint.fromJson(Map<String, dynamic> json) => EquityCurvePoint(
    date: json['date'] as String? ?? '',
    totalAssets: (json['totalAssets'] as num?)?.toDouble() ?? 0,
    cash: (json['cash'] as num?)?.toDouble() ?? 0,
    marketValue: (json['marketValue'] as num?)?.toDouble() ?? 0,
    invested: (json['invested'] as num?)?.toDouble() ?? 0,
    netValue: (json['netValue'] as num?)?.toDouble(),
    drawdown: (json['drawdown'] as num?)?.toDouble(),
  );
}

/// 资金曲线响应 {points, startDate, endDate}。
class EquityCurveResponse {
  final List<EquityCurvePoint> points;
  final String startDate;
  final String endDate;

  EquityCurveResponse({
    required this.points,
    required this.startDate,
    required this.endDate,
  });

  factory EquityCurveResponse.fromJson(Map<String, dynamic> json) =>
      EquityCurveResponse(
        points: ((json['points'] as List?) ?? const [])
            .map((e) => EquityCurvePoint.fromJson(e as Map<String, dynamic>))
            .toList(),
        startDate: json['startDate'] as String? ?? '',
        endDate: json['endDate'] as String? ?? '',
      );
}


/// 「阿呆对你的了解」响应（2026-09-16「第一次见面」批）。
///
/// 数据源是 memory 里长期沉淀的 patterns / preferences（后端已按时间衰减 × 置信度排序），
/// 本模型只做展示层的防御式解析。
class MemoryInsightsResponse {
  final int total;
  final int patternCount;
  final int preferenceCount;

  /// 最早一条记忆的日期（yyyy-MM-dd）；全新用户为 null。
  final String? observedSince;
  final List<MemoryInsight> insights;

  MemoryInsightsResponse({
    required this.total,
    required this.patternCount,
    required this.preferenceCount,
    required this.observedSince,
    required this.insights,
  });

  factory MemoryInsightsResponse.fromJson(Map<String, dynamic> json) =>
      MemoryInsightsResponse(
        total: json['total'] as int? ?? 0,
        patternCount: json['patternCount'] as int? ?? 0,
        preferenceCount: json['preferenceCount'] as int? ?? 0,
        observedSince: json['observedSince'] as String?,
        insights: ((json['insights'] as List?) ?? const [])
            .map((e) => MemoryInsight.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// 一条长期观察。[kind] 为 `pattern`（行为模式）或 `preference`（明确偏好）。
class MemoryInsight {
  final String kind;
  final String content;
  final double confidence;

  MemoryInsight({
    required this.kind,
    required this.content,
    required this.confidence,
  });

  bool get isPattern => kind == 'pattern';

  factory MemoryInsight.fromJson(Map<String, dynamic> json) => MemoryInsight(
        kind: json['kind'] as String? ?? 'pattern',
        content: json['content'] as String? ?? '',
        confidence: (json['confidence'] as num?)?.toDouble() ?? 0,
      );
}
