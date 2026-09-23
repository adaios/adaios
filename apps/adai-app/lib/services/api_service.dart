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

  // 内存缓存：跨页面切换不丢
  TagsResponse? _tagsCache;
  List<TimelineEntryResponse>? _timelineCache;
  List<MemoryEntryResponse>? _memoryCache;

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
  /// 2026-08-27：VLM 识别走 _aiClient（120s）——生产实测 GLM 识别单图最坏 28s（
  /// 2026-08-26 晚截图入账日志实锤），15s 普通超时必误杀（首页发图同样结构性问题）。
  Future<MediaRecordResponse> uploadImage({
    required List<int> bytes,
    required String filename,
    required String mimeType,
    String? caption,
  }) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/records/media'))
      ..headers.addAll(_authHeaders)
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

  /// 一次投递一次请求（2026-09-22 多图批）：N 张图（1..3）+ 可选一句话 → 后端先对全部图
  /// 做视觉识别、组成上下文 → 无提问给一段综合总结 / 有提问据图作答。**一次投递 = 一条主记录
  /// = 一张卡**（此前前端逐张 POST 会落 N 条记录 / N 张卡，且多图串行易超时）。
  ///
  /// [idempotencyKey] 是**一次投递的唯一键**：同键重发后端返回首次结果（`duplicated=true`），
  /// **不会重复落盘**——失败重试必须复用同一个 key（生成点见 `MainPage._newIdempotencyKey`）。
  /// multipart 字段名固定 `files`（可重复）+ `text`。
  Future<MediaBatchResponse> uploadImages({
    required List<MediaUploadFile> files,
    required String idempotencyKey,
    String? text,
  }) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/records/media/batch'))
      ..headers.addAll(_authHeaders)
      ..headers['Idempotency-Key'] = idempotencyKey
      ..fields['text'] = text ?? '';
    for (final f in files) {
      req.files.add(http.MultipartFile.fromBytes(
        'files',
        f.bytes,
        filename: f.filename,
        contentType: MediaType('image', f.mimeType.split('/').last),
      ));
    }
    final streamed = await _aiClient.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    // 上传后缓存失效（Feed/Timeline/Memory 都会有新图片记录）
    _tagsCache = null;
    _timelineCache = null;
    _memoryCache = null;
    return MediaBatchResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 提交记录。
  /// 2026-08-20：聊天（intent=question / cardId 续聊）走 _aiClient——DeepSeek 回答 7~27s，
  /// 15s 默认超时必误杀（聊天报错根因）；纯 log 陈述走常规客户端。
  /// [source] 标记「这条记录从哪来」（P1-安全1 剩余项，2026-09-17 B4 批）：外部入口
  /// （Siri「记一笔」/ 快捷指令 / `adai://record`）传 `external_entry`；不传 = 后端按
  /// `user_input` 记（存量口径不变，用户拍板 D2「只给非手输入口加，不动存量」）。
  Future<RecordResponse> createRecord(String content, {String? type, List<String>? tags, String? intent, String? cardId, String? source}) async {
    final body = {
      'content': content,
      if (type != null) 'type': type,
      if (tags != null && tags.isNotEmpty) 'tags': tags,
      if (intent != null) 'intent': intent,
      if (cardId != null) 'cardId': cardId,
      if (source != null) 'source': source,
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
        body: {'content': content, 'intent': ?intent, if (cardId != null) 'cardId': cardId},
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
        _timelineCache = null;
        _memoryCache = null;
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

  /// 当日口径持仓（GET /api/v1/trading/positions/daily，2026-09-14 当日口径批）：
  /// 在原持仓之上多给每票「当日盈亏 / 今日涨跌幅 / 仓位比例」，外加总仓位/现金比例与
  /// 「哪些没算进去」的说明（[PositionsDailyResponse.notes]）。
  /// 每个数值字段都可能为 null（缺昨收 → 当日盈亏算不出；总资产为 0 → 仓位算不出）。
  /// 调用方（交易页）失败必须静默降级回 [getPositions]——增强项不能拖垮持仓主数据。
  Future<PositionsDailyResponse> getPositionsDaily() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/positions/daily'),
      headers: _headers,
    );
    _check(resp);
    return PositionsDailyResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 今日 / 本周 / 本月盈亏（GET /api/v1/trading/pnl-periods，2026-09-15 用户要求）：
  /// 金额 + 比例。口径与资金曲线同源（逐日总资产差分、剔除银证转账）。
  /// [PeriodPnlDto.pct] 可能为 null（区间起点前无曲线点 / 锚定日之前不可追溯）——
  /// **null 一律显示「—」，不得渲染成 0%**。调用方失败必须静默降级（不拖垮账户卡）。
  Future<PnlPeriodsDto> getPnlPeriods() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/pnl-periods'),
      headers: _headers,
    );
    _check(resp);
    return PnlPeriodsDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 银证转账（POST /api/v1/trading/transfer，P2-交易52）：转入/转出会同步更新净投入本金与现金
  /// （总盈亏 = 资产 − 本金 自动算）。金额必须 > 0 且有限；调用方预检，后端仍会校验。
  Future<void> recordTransfer({required String type, required double amount, String? note}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/trading/transfer'),
      headers: _headers,
      body: jsonEncode({'type': type, 'amount': amount, 'note': note}),
    );
    _check(resp);
  }

  /// 设置本金（PUT /api/v1/trading/principal，P2-交易52）：只写本金（累计净投入），
  /// **不动现金/持仓**——总盈亏 = 资产 − 本金。body 字段名是 `amount`（对齐后端契约）。
  Future<void> setPrincipal(double amount) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/principal'),
      headers: _headers,
      body: jsonEncode({'amount': amount}),
    );
    _check(resp);
  }

  /// 资金曲线 + **逐日盈亏**（GET /api/v1/trading/equity-curve，P2-交易52）。
  ///
  /// [EquityCurveDto.dailyPnl] 就是「券商口径的逐日盈亏」（与日/周/月三档同源回放），
  /// 收益日历直接用它，**前端不再自己做差分**——否则就是「卡片一个数、日历另一个数」
  /// （P2-交易50/51 的教训）。某天缺收盘价时后端不猜 0，前端据此显示「—」。
  Future<EquityCurveDto> getEquityCurve() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/equity-curve'),
      headers: _headers,
    );
    _check(resp);
    return EquityCurveDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  /// RFC 20260912 账实一致性自检（GET /api/v1/trading/integrity，P2-交易39 手机端补课）：
  /// 锚定状态 + drift（应有持仓 ≠ 落地持仓）+ gaps（重放缺口）。
  /// 降级诚实：锚定/基线缺失 → [IntegrityReportDto.note] 说明「无法判定」，drift/gaps 空（不误报差异）。
  /// 调用方（交易页）失败必须静默——本端点是增强项，不能拖垮持仓主数据。
  Future<IntegrityReportDto> getIntegrity() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/integrity'),
      headers: _headers,
    );
    _check(resp);
    return IntegrityReportDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// K 线/行情链路健康度（GET /api/v1/trading/market-data/health，2026-09-23）：
  /// 把「整段行情取不到数」从服务端日志搬到用户面前（P1-交易62：tdx 滞后 + 腾讯被 WAF 拦 +
  /// 东财被限 → K 线链路连续失败，而资金曲线/自选信号/案例匹配全建在它上面，用户此前只看到
  /// 「数据缺了」却不知道在重试）。
  /// 调用方（交易页）只有 `ok == false` 才显示横幅；本端点失败必须静默——
  /// 它本身是增强项，不能拖垮持仓主数据（与 [getIntegrity] 同一降级口径）。
  Future<MarketDataHealthDto> getMarketDataHealth() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/market-data/health'),
      headers: _headers,
    );
    _check(resp);
    return MarketDataHealthDto.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
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

  /// RFC 20260825：逐笔批次视图（GET /api/v1/trading/lots）。
  /// state ∈ open|closed|all（默认 all，一次拉全量按 symbol 前端过滤）；
  /// 返回 {"lots": [...], "reconcile": [...]}——每批含日期/数量/剩余/成本/现价/盈亏/距止损。
  Future<LotsResponse> getLots({String? state, String? symbol}) async {
    final params = <String, String>{
      if (state != null) 'state': state,
      if (symbol != null) 'symbol': symbol,
    };
    final uri = Uri.parse('$baseUrl/api/v1/trading/lots').replace(queryParameters: params);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    return LotsResponse.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 批次止损编辑（P2-交易59 C2，2026-09-22）：2026-09-04 只做了 web
  /// （`lot-stoploss.json` 覆盖层 + `PUT /trading/lots/{lotId}/stop-loss`）。
  /// 用户 2026-09-21 明确「**止损要能在 app 改**」→ 手机端补上同一端点、同一口径
  /// （不再把「改」整段留给电脑端；这条推翻了 RFC 20260918 里「app 不做止损编辑」的旧约定）。
  Future<void> updateLotStopLoss(String lotId, double stopLossPrice) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/lots/${Uri.encodeComponent(lotId)}/stop-loss'),
      headers: _headers,
      body: jsonEncode({'stopLossPrice': stopLossPrice}),
    );
    _check(resp);
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

  /// v3.41（2026-09-04）：活跃市值区间（用户手动判定，GET /trading/market-stage）。
  /// 返回 {"exists":bool,"stage":"bull"|"bear"|null,"updatedAt":String|null}。
  Future<Map<String, dynamic>> getMarketStage() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/market-stage'),
      headers: _headers,
    );
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

  /// RFC 20260817：交易日志当日候选（2026-08-26 类型化——交易页内嵌候选列表用）。
  Future<List<TradeLogCandidateDto>> getTradeLogCandidates() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/trade-log'),
      headers: _headers,
    );
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List<dynamic>;
    return list.map((e) => TradeLogCandidateDto.fromJson(e)).toList();
  }

  /// 2026-08-26 截图入账：券商「当日委托/历史成交」截图（1-3 张 multipart）→ VLM 归集为当日候选。
  /// POST /api/v1/trading/screenshots——不建记录、不落原图，候选确认落库后即权威数据。
  /// 2026-08-27：走 _aiClient（120s）——生产实测 VLM 单图识别最坏 28s（3 张近 90s），15s 必超时。
  Future<TradingScreenshotResult> uploadTradingScreenshots({
    required List<List<int>> bytesList,
    required List<String> filenames,
    required List<String> mimeTypes,
  }) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/trading/screenshots'))
      ..headers.addAll(_authHeaders);
    for (var i = 0; i < bytesList.length; i++) {
      req.files.add(http.MultipartFile.fromBytes(
        'files',
        bytesList[i],
        filename: filenames[i],
        contentType: MediaType('image', mimeTypes[i].split('/').last),
      ));
    }
    final streamed = await _aiClient.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    return TradingScreenshotResult.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// RFC 20260817：确认交易日志落库。
  /// B11-4（2026-08-23，P1-交易18）：返回完整结果（含失败明细——失败候选保留，可丢弃）。
  /// 2026-08-27 二修：截图候选缺成交日期会被拒（skipped+failures），补日期后再次确认。
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

  /// 2026-08-27 二修（用户拍板「截图缺日期禁止落库，补充日期后再确认」）：
  /// 给当日候选补写成交日期（PUT /api/v1/trading/trade-log/date）。
  /// 截图归集候选无日期列被 confirm 拒后，前端日期选择 → 补日期 → 再次确认。
  /// @return true=已更新；false=当日无此候选
  Future<bool> setTradeLogDate({
    String? id,
    String? symbol,
    String? direction,
    required String tradeDate,
  }) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/trade-log/date'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({
        // P1-交易54 收尾（2026-09-17）：**优先传 id 行级定位**——同代码同方向的多笔候选
        // （当日三笔亨通光电各 100 股）只有它能各自补日期；symbol/direction 是旧口径，
        // 会把那几笔一起补上同一日期，仅在旧后端（不返回 id）时使用。
        if (id != null && id.isNotEmpty) 'id': id,
        if (symbol != null && symbol.isNotEmpty) 'symbol': symbol,
        if (direction != null && direction.isNotEmpty) 'direction': direction,
        'tradeDate': tradeDate,
      }),
    );
    _check(resp);
    final map = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return map['updated'] as bool? ?? false;
  }

  /// 2026-09-18（RFC 20260918 A1-4）：**候选就地编辑**——改正截图识别错的价格 / 数量 / 方向 / 成交日期。
  ///
  /// 走 `PUT /api/v1/trading/trade-log/meta`（带 id 行级定位，同代码同方向的多笔必须逐条改）。
  /// 只传要改的字段；服务端只覆盖非空值并重算 `complete`。
  Future<bool> updateTradeLogFields({
    required String id,
    double? price,
    int? volume,
    String? direction,
    String? tradeDate,
  }) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/trading/trade-log/meta'),
      headers: {..._headers, 'content-type': 'application/json'},
      body: jsonEncode({
        'id': id,
        if (price != null) 'price': price,
        if (volume != null) 'volume': volume,
        if (direction != null && direction.isNotEmpty) 'direction': direction,
        if (tradeDate != null && tradeDate.isNotEmpty) 'tradeDate': tradeDate,
      }),
    );
    _check(resp);
    final map = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return map['updated'] as bool? ?? false;
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
  /// DELETE /api/v1/trading/trade-log?id=&symbol=&direction=；404 幂等成功。
  /// P1-交易54（2026-09-17）：**优先用 `id` 行级定位**——同标的同方向的多笔候选
  /// （当日三笔亨通光电各 100 股）只有它能精确删到一条；`symbol+direction` 是旧口径，
  /// 会把同代码同方向的多笔一起删掉（symbol 为空更会删光该方向），仅为兼容老后端保留。
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

  /// 清仓股列表（复盘闭环；RFC 20260909 批1 双轨对象契约，与 web 一致）。
  /// 新契约响应为对象：{"sold":[SoldTradeDto...], "pendingClearances":[{symbol,name,sellDate,reason}...]}——
  /// sold 每行新增 provenance=import|flow（老行缺省 import）；pendingClearances 为流水已清仓但
  /// 缺买入基线的待补清单（条件 B 只提示不写脏，空则省略）。
  /// 过渡兼容：后端仍返回裸数组（旧版）时按 sold=数组、pendingClearances 空处理。
  Future<SoldOverview> getSold() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/trading/sold'),
      headers: _headers,
    );
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

  /// 生成交易复盘（POST /api/v1/trading/review）。
  /// 2026-09-07 起 POST 改为「提交即返回」：后台生成（AI 实测 77~176s 远超客户端超时，
  /// 旧同步等待必被 15s 客户端掐断 → 复盘实际生成却「点击没反应」）。
  /// 返回 {date, status}：exists=已有复盘（直接 GET 展示）/ running=生成中（继续轮询）
  /// / pending=已受理（轮询 GET /trading/review 直到 200）。
  Future<ReviewSubmitResponse> submitReview({String? date}) async {
    final params = <String, String>{};
    if (date != null) params['date'] = date;
    final uri = Uri.parse('$baseUrl/api/v1/trading/review')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.post(uri, headers: _headers);
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

  /// 当前用户启用插件列表（RFC 20260814 Domain=插件模型；返回如 ["trading","learn"]，
  /// 新用户为空 → 前端按此显隐插件模块：交易/学习）。基础服务模块（含待办）不依赖此列表。
  /// RFC 20260917：project 插件已撤，账号里残留的 "project" 后端会自动过滤。
  Future<List<String>> getMyPlugins() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/me/plugins'),
      headers: _headers,
    );
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
    return list.map((e) => e.toString()).toList();
  }

  // ── learn 学习插件（RFC 20260829）──

  /// 喂入链接或素材消化（2026-09-12 抓取批）：POST /learn/digest → {status: pending|running|needs_confirmation}，
  /// 后台抓取（B站/文章，没字幕的视频走转写）+ AI 卡片化，完成后轮询 [getLearnDigestStatus] 直到 done/failed。
  /// [url] 与 [content] 至少给一个（url 优先：服务端自己抓，用户不必先搞字幕/正文；
  /// content 是抓不到时的降级路径）。旧地址 /learn/cards 仍是兼容别名，前端统一走 /digest。
  Future<String> submitLearnDigest({
    String? url,
    String? content,
    String? type,
    String? platform,
    String? author,
    String? published,
  }) async {
    final body = <String, dynamic>{
      if (url != null && url.isNotEmpty) 'url': url,
      if (content != null && content.isNotEmpty) 'content': content,
      if (type != null) 'type': type,
      if (platform != null) 'platform': platform,
      if (author != null) 'author': author,
      if (published != null) 'published': published,
    };
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/digest'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    final json = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (json['status'] as String?) ?? '';
  }

  /// 转写费用确认（2026-09-12）：POST /learn/digest/confirm，body {"confirm": true|false}。
  /// true = 花钱转写（恢复消化），false = 先不转写（不产生费用）；两者都返回最新任务状态。
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

  /// 本月转写用量与剩余额度（2026-09-12）：GET /learn/digest/quota。
  Future<LearnQuotaDto> getLearnQuota() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/digest/quota'),
      headers: _headers,
    );
    _check(resp);
    return LearnQuotaDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 消化任务状态（2026-09-12 抓取批补齐 stage/source/cost）：
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

  /// 单篇卡片全文（2026-09-12 完整升级批）：GET /learn/content?type=&title=（精确标题）→
  /// {type,title,topic,writable,content}，content 是该卡 md 原文。
  /// 列表接口只给产品建模的四个段——Mac 侧技能整理的卡还有「关键内容详解/金句/概念关系」等段，
  /// **读全文必须走这里**，否则那些段在界面上永远看不到。
  Future<LearnCardContentDto> getLearnContent({
    required String type,
    required String title,
  }) async {
    final uri = Uri.parse('$baseUrl/api/v1/learn/content')
        .replace(queryParameters: {'type': type, 'title': title});
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    return LearnCardContentDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 找卡片（学习页搜索 + 对话流「打开那篇」）：GET /learn/find?q=&limit=。
  /// 服务端已按相关度排好（纯规则打分，不烧 AI）；没命中 → 空列表（兜底话术交给调用方）。
  Future<List<LearnCardDto>> searchLearnCards(String q, {int limit = 5}) async {
    final uri = Uri.parse('$baseUrl/api/v1/learn/find')
        .replace(queryParameters: {'q': q, 'limit': '$limit'});
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List<dynamic>;
    return list
        .map((e) => LearnCardDto.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  // ── learn 卡片管理动作（2026-09-13 卡片管理动作批）──
  //
  // 只对「我在产品里写过的卡」（writable=true）成立；只读卡（Mac 侧技能整理的原始卡）
  // 后端一律 400 拒写，前端也不该把那两个入口亮出来（不把用户送到墙上撞）。

  /// 挪主题：PATCH /learn/cards/topic，body {type,title,topic} → 更新后的 LearnCard。
  /// 卡片文件会移到 `{type}/{新主题}/NN-标题.md`（新主题续号）；同主题幂等（照常 200）。
  /// 400（卡片不存在 / 只读卡 / type 非法）与 403（learn 插件未启用）抛 [ApiException]，
  /// body 里是后端人话（UI 层提取 error 展示，不甩技术原话）。
  Future<LearnCardDto> moveLearnCardTopic({
    required String type,
    required String title,
    required String topic,
  }) async {
    final resp = await _client.patch(
      Uri.parse('$baseUrl/api/v1/learn/cards/topic'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title, 'topic': topic}),
    );
    _check(resp);
    return LearnCardDto.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 产物反馈（RFC 20260917 §五 2b）：POST /learn/cards/feedback → {status,message,canRepage}。
  /// 把「太啰嗦 / 多举几个例子」沉淀为**长期偏好**，经画像回流作用于**下一次**生成。
  /// **不烧钱**（只写偏好、不调 LLM）；canRepage=true → 这卡还有 _raw 素材、可按新偏好重排
  /// （重排才花钱，由用户点头触发 [repageLearnCard]）。
  /// 400：卡片不存在 / 反馈为空或超长；403：learn 未启用。
  Future<LearnFeedbackResult> submitLearnFeedback({
    required String type,
    required String title,
    required String feedback,
  }) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/cards/feedback'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title, 'feedback': feedback}),
    );
    _check(resp);
    return LearnFeedbackResult.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 重排页序列（2026-09-15 卡片流批；RFC 20260917 补前端入口）：
  /// POST /learn/cards/repages —— 用 _raw 原始素材补排「一页一单元」卡片流；
  /// **正文/核心观点/复述一字不动**（只补呈现层）。老卡没素材 → 400 人话。
  Future<void> repageLearnCard({required String type, required String title}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/learn/cards/repages'),
      headers: _headers,
      body: jsonEncode({'type': type, 'title': title}),
    );
    _check(resp);
  }

  /// 删卡（软删除，不真丢）：DELETE /learn/cards?type=&title=（精确标题）→
  /// {deleted,title,learnCardId,cascadedCandidates}。卡文件移入 learn/_trash/（可人工找回），
  /// 主题 README 索引摘行；若该卡曾反哺过交易候选，候选会被级联清理，
  /// 标题在 cascadedCandidates 里——**调用方必须如实告诉用户**。
  /// 400 / 403 语义同 [moveLearnCardTopic]。
  Future<LearnCardDeleteResult> deleteLearnCard({
    required String type,
    required String title,
  }) async {
    final uri = Uri.parse('$baseUrl/api/v1/learn/cards')
        .replace(queryParameters: {'type': type, 'title': title});
    final resp = await _client.delete(uri, headers: _headers);
    _check(resp);
    return LearnCardDeleteResult.fromJson(
        jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>);
  }

  /// 认回被抹掉的来源标记（REVIEW P2-审查4，2026-09-17 B5 批）：
  /// `POST /api/v1/learn/cards/restore-origin?type=&title=`。
  /// <p>
  /// 只对「本该是本产品写的、`origin` 却被别的工具抹掉」的卡有意义——判据在**后端**
  /// （正文含 `## 卡片页` 或 frontmatter 带 `review_at`/`reminded_at`），所以前端不做本地
  /// 猜测：认不回来就把后端的人话原样显示（不给别人的卡盖章）。
  Future<bool> restoreLearnOrigin({
    required String type,
    required String title,
  }) async {
    final uri = Uri.parse('$baseUrl/api/v1/learn/cards/restore-origin')
        .replace(queryParameters: {'type': type, 'title': title});
    final resp = await _client.post(uri, headers: _headers);
    _check(resp);
    final body = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return body['writable'] == true;
  }

  /// 图片喂入（2026-09-12 完整升级批）：POST /learn/digest/image（multipart，字段名 files，1~3 张，
  /// 可选 type/note）→ {status}。原图先落 learn/_raw/（源必留痕），后台读图（stage=reading）后
  /// 与链接/素材走同一条消化流水线；提交式——拿到 status 后照旧轮询 [getLearnDigestStatus]。
  /// 走 _aiClient（120s）：多图上传 + 服务端读图排队，15s 默认超时会在弱网误杀（同截图入账口径）。
  /// multipart 必须显式带 [_authHeaders]（2026-09-02 线上实锤：漏了 Bearer → 401）。
  Future<String> submitLearnImages({
    required List<List<int>> bytesList,
    required List<String> filenames,
    required List<String> mimeTypes,
    String? type,
    String? note,
  }) async {
    final req = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/learn/digest/image'))
      ..headers.addAll(_authHeaders);
    if (type != null && type.isNotEmpty) req.fields['type'] = type;
    if (note != null && note.trim().isNotEmpty) req.fields['note'] = note.trim();
    for (var i = 0; i < bytesList.length; i++) {
      req.files.add(http.MultipartFile.fromBytes(
        'files',
        bytesList[i],
        filename: filenames[i],
        contentType: MediaType('image', mimeTypes[i].split('/').last),
      ));
    }
    final streamed = await _aiClient.send(req);
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    final json = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (json['status'] as String?) ?? '';
  }

  /// 复习提醒开关读（S-learn2 2026-09-07，双端一致）：GET /learn/push-settings。
  Future<bool> getLearnReviewEnabled() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/learn/push-settings'),
      headers: _headers,
    );
    _check(resp);
    final json = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (json['learn-review'] as bool?) ?? true;
  }

  /// 复习提醒开关写（S-learn2）：PUT /learn/push-settings/learn-review。
  Future<void> setLearnReviewEnabled(bool enabled) async {
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/learn/push-settings/learn-review'),
      headers: _headers,
      body: jsonEncode({'enabled': enabled}),
    );
    _check(resp);
  }
  // ── 待办 API（RFC 20260917：Kernel builtin，无插件门控、旧 project/tasks 已删除）──

  /// 获取待办列表。`status` 可选（OPEN / DONE），不传 = 两态都要。
  Future<List<TodoItem>> getTodos({String? status}) async {
    final params = <String, String>{};
    if (status != null) params['status'] = status;
    final uri = Uri.parse('$baseUrl/api/v1/todos')
        .replace(queryParameters: params.isNotEmpty ? params : null);
    final resp = await _client.get(uri, headers: _headers);
    _check(resp);
    final list = jsonDecode(utf8.decode(resp.bodyBytes)) as List;
    return list.map((e) => TodoItem.fromJson(e)).toList();
  }

  /// 加一条待办。`due` 省略 = 不设到期日（后端允许 due 缺省/null）。
  Future<TodoItem> createTodo({required String title, DateTime? due}) async {
    final body = <String, dynamic>{
      'title': title,
      if (due != null) 'due': _formatLocalDate(due),
    };
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/todos'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return TodoItem.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 改一条待办。契约（RFC 20260917）：**字段 null = 保持原值**——所以清除到期日
  /// 必须显式走 [clearDue] 发空串，`due: null` 表达的是「别动它」。
  Future<TodoItem> updateTodo(
    String id, {
    String? title,
    String? status,
    DateTime? due,
    bool clearDue = false,
  }) async {
    final body = <String, dynamic>{};
    if (title != null) body['title'] = title;
    if (status != null) body['status'] = status;
    if (clearDue) {
      body['due'] = '';
    } else if (due != null) {
      body['due'] = _formatLocalDate(due);
    }
    final resp = await _client.put(
      Uri.parse('$baseUrl/api/v1/todos/$id'),
      headers: _headers,
      body: jsonEncode(body),
    );
    _check(resp);
    return TodoItem.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// 删一条待办（后端 204）。
  Future<void> deleteTodo(String id) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/todos/$id'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 待办计数（总数 / 未完成 / 已完成）。
  Future<TodoStats> getTodoStats() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/todos/stats'),
      headers: _headers,
    );
    _check(resp);
    return TodoStats.fromJson(jsonDecode(utf8.decode(resp.bodyBytes)));
  }

  /// LocalDate 序列化：契约要 `YYYY-MM-DD`（不是带时区的 ISO8601）。
  static String _formatLocalDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-'
      '${d.month.toString().padLeft(2, '0')}-'
      '${d.day.toString().padLeft(2, '0')}';

  Map<String, String> get _headers => {
    'Content-Type': 'application/json',
    // 多账号：所有请求带当前用户（后端 FileStorage 按 userId 隔离）；
    // RFC 20260901-auth-login：后端 AuthFilter 校验 Bearer 并把 X-User-Id 覆盖为会话 userId
    'X-User-Id': userId,
    if (token != null) 'Authorization': 'Bearer $token',
  };

  /// 图片记录原图 URL（供 Image.network 渲染缩略图/点击看原图）。
  String mediaUrl(String recordId) => '$baseUrl/api/v1/records/media/$recordId';

  /// 媒体请求鉴权头（与 _headers 一致，Image.network 需要显式传入）。
  Map<String, String> get mediaHeaders => _authHeaders;

  /// multipart 请求鉴权头（RFC 20260901-auth-login：MultipartRequest 不自动带
  /// _headers——2026-09-02 线上实锤：截图/图片上传只发 X-User-Id 被后端 401）。
  Map<String, String> get _authHeaders => {
    'X-User-Id': userId,
    if (token != null) 'Authorization': 'Bearer $token',
  };

  void _check(http.Response resp) {
    // RFC 20260901-auth-login：401 = 会话失效/未登录 → 全局回调跳登录页（先回调再抛异常）
    if (resp.statusCode == 401 && onUnauthorized != null) {
      onUnauthorized!();
    }
    if (resp.statusCode >= 400) {
      throw ApiException(resp.statusCode, 'API 错误 ${resp.statusCode}',
          utf8.decode(resp.bodyBytes));
    }
  }
}

/// 认证相关方法（RFC 20260901-auth-login）。
extension AuthApi on ApiService {
  /// 登录：成功返回 {token, userId, role, plugins, sessionId, expiresAt}。
  /// 401（密码错/未设密码/限流）抛 ApiException，不触发 onUnauthorized（登录页场景）。
  ///
  /// [device]（RFC 20260914 L2）可选上报 `{name, platform, appVersion}`：写进会话供
  /// 「登录设备」列表辨认。**服务端不据此做任何安全判定**（可伪造），且缺失完全可用。
  Future<Map<String, dynamic>> login(String account, String password,
      {Map<String, dynamic>? device}) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({
        'account': account,
        'password': password,
        if (device != null) 'device': device,
      }),
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

  // ── 登录设备（RFC 20260914 L2：看得见 + 撤得掉）──

  /// 当前账号登录着的设备（GET /auth/sessions）。
  /// 每项：`{id, device: {name, platform, appVersion}?, createdAt, lastSeenAt, expiresAt, current}`。
  Future<List<Map<String, dynamic>>> listSessions() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/auth/sessions'),
      headers: _headers,
    );
    _check(resp);
    final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    final list = data['sessions'];
    if (list is! List) return const [];
    return list.whereType<Map<String, dynamic>>().toList();
  }

  /// 撤销一台设备的登录（DELETE /auth/sessions/{id}）。
  /// 400 = 标识对应多台 / 想撤销当前设备（后端给人话）；404 = 已经退出过了。
  Future<void> revokeSession(String id) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/auth/sessions/${Uri.encodeComponent(id)}'),
      headers: _headers,
    );
    _check(resp);
  }

  // ── 外部工具令牌（2026-09-13 外部入口批）──
  //
  // 快捷指令这类「我们控制不了凭据存放处」的工具用它：限权（scope 白名单）、可撤销、
  // 明文只在签发响应里出现一次。**绝不要把登录会话 token 交给快捷指令**——那是明文写在
  // plist 里、且 .shortcut 文件会被分享出去的东西。

  /// 签发一把外部令牌（POST /api/v1/auth/tokens）
  /// → `{token, prefix, label, scopes, createdAt, notice}`。
  /// **token 明文只在这一次响应里出现**，调用方必须立刻展示给用户复制走。
  Future<Map<String, dynamic>> issueExternalToken({
    required String label,
    List<String> scopes = const ['learn:digest'],
  }) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/auth/tokens'),
      headers: _headers,
      body: jsonEncode({'label': label, 'scopes': scopes}),
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 已签发的外部令牌 + 可选权限清单（GET /api/v1/auth/tokens）。
  /// 返回 `{tokens: [...], availableScopes: [...]}`；**不含任何明文**。
  Future<Map<String, dynamic>> listExternalTokens() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/auth/tokens'),
      headers: _headers,
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 撤销一把外部令牌（DELETE /api/v1/auth/tokens/{prefix}）——立即失效，
  /// 不影响登录会话与其它设备。404 表示这把已经不在（可能撤销过）。
  Future<void> revokeExternalToken(String prefix) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/auth/tokens/${Uri.encodeComponent(prefix)}'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 换一把新钥匙（POST /api/v1/auth/tokens/{idOrPrefix}/rotate，2026-09-17 B5 批）。
  /// <p>
  /// 后端语义：**先发新、再撤旧**（旧的撤不掉就把新的回滚）——所以拿到 200 就可以直接用返回的
  /// 明文替换本地副本，不存在「两把同时有效」的窗口（那比断链更糟：分不清哪把外泄）。
  /// 返回 `{token, id, prefix, label, scopes, createdAt, expiresAt, notice}`，其中 `token` 是
  /// **明文且只出现这一次**（后端只存哈希）——调用方必须立刻展示并让用户复制走。
  Future<Map<String, dynamic>> rotateExternalToken(String idOrPrefix) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/auth/tokens/${Uri.encodeComponent(idOrPrefix)}/rotate'),
      headers: _headers,
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  /// 修改本人密码（POST /api/v1/auth/password）→ 返回被踢除的**其他**会话数
  /// （当前会话保留，改密后无需重新登录；200 body `{message, kickedSessions}`）。
  /// 失败抛 [ApiException]（body 为后端 JSON，UI 提取 error 人话展示）。
  /// 401 双义（RFC 20260901-auth-login）：
  /// - 原密码错误 → 只抛异常（改密弹窗内人话提示，用户可重试，**不**触发全局登出）；
  /// - 会话已失效 → 抛异常前先走 [onUnauthorized]（与其它常规请求的 401 语义一致，
  ///   清 token 回登录页；弹窗按 error 文案提示后由用户关闭）。
  Future<int> changePassword({
    required String oldPassword,
    required String newPassword,
  }) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/auth/password'),
      headers: _headers,
      body: jsonEncode({'oldPassword': oldPassword, 'newPassword': newPassword}),
    );
    if (resp.statusCode == 401) {
      final body = utf8.decode(resp.bodyBytes);
      if (body.contains('会话') && onUnauthorized != null) {
        onUnauthorized!();
      }
      throw ApiException(resp.statusCode, 'API 错误 ${resp.statusCode}', body);
    }
    if (resp.statusCode >= 400) {
      throw ApiException(resp.statusCode, 'API 错误 ${resp.statusCode}',
          utf8.decode(resp.bodyBytes));
    }
    final data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    return (data['kickedSessions'] as num?)?.toInt() ?? 0;
  }

  /// 认证端点专用校验：>=400 抛 ApiException，但不触发 onUnauthorized 跳转。
  void _checkAuthOnly(http.Response resp) {
    if (resp.statusCode >= 400) {
      throw ApiException(resp.statusCode, 'API 错误 ${resp.statusCode}',
          utf8.decode(resp.bodyBytes));
    }
  }
}

/// 推送设备登记（RFC 20260913 APNs 批）。
extension PushApi on ApiService {
  /// 登记/刷新本机 APNs 设备（后端 `POST /api/v1/push/devices`，同 token 幂等）。
  ///
  /// [environment] 为 `sandbox` / `production`——由 iOS 侧读包内
  /// `embedded.mobileprovision` 的 `aps-environment` 得出（不是猜的 debug/release）；
  /// 后端按它选择 APNs 网关，送错会被 APNs 回 BadDeviceToken 丢弃。
  Future<void> registerPushDevice({
    required String token,
    required String environment,
    String? bundleId,
    String? label,
  }) async {
    final resp = await _client.post(
      Uri.parse('$baseUrl/api/v1/push/devices'),
      headers: _headers,
      body: jsonEncode({
        'token': token,
        'platform': 'ios',
        'environment': environment,
        if (bundleId != null) 'bundleId': bundleId,
        if (label != null) 'label': label,
      }),
    );
    _check(resp);
  }

  /// 注销本机设备（登出时调用；幂等，不存在也返回成功）。
  Future<void> unregisterPushDevice(String token) async {
    final resp = await _client.delete(
      Uri.parse('$baseUrl/api/v1/push/devices/$token'),
      headers: _headers,
    );
    _check(resp);
  }

  /// 推送链路自检：渠道就绪状态（apns enabled/configured/灰度白名单）+ 已登记设备数。
  Future<Map<String, dynamic>> getPushStatus() async {
    final resp = await _client.get(
      Uri.parse('$baseUrl/api/v1/push/status'),
      headers: _headers,
    );
    _check(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
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

/// 一次投递中的单个文件（多图 batch 上传，`uploadImages`）。
/// 不用裸 record：调用点（main_page 占位卡 / 重试）要跨方法传递，具名类更可读也便于测试。
class MediaUploadFile {
  final List<int> bytes;
  final String filename;
  final String mimeType;

  MediaUploadFile({required this.bytes, required this.filename, required this.mimeType});
}

/// 一次投递一次请求的响应（2026-09-22 多图批，`POST /records/media/batch`）。
class MediaBatchResponse {
  final String recordId; // 主记录 id（= 卡片 id）
  final List<String> mediaIds; // 附件记录 id，按上传顺序；原图 GET /records/media/{mediaId}
  final String type; // image（无提问，summary 已含综合总结）| image_qa（有提问，带 answer）
  final String intent; // log | question
  final String summary; // 无提问：一段综合总结；有提问：回答摘要
  final String? answer; // 有提问时的回答，否则 null
  final List<String> tags;
  final String domain;
  final bool duplicated; // 同 Idempotency-Key 重发 → true（首次结果原样返回，未重复入库）

  MediaBatchResponse({
    required this.recordId,
    this.mediaIds = const [],
    this.type = 'image',
    this.intent = 'log',
    this.summary = '',
    this.answer,
    this.tags = const [],
    this.domain = 'life',
    this.duplicated = false,
  });

  factory MediaBatchResponse.fromJson(Map<String, dynamic> json) => MediaBatchResponse(
        recordId: json['recordId'] as String? ?? '',
        mediaIds: (json['mediaIds'] as List?)?.map((e) => '$e').toList() ?? const [],
        type: json['type'] as String? ?? 'image',
        intent: json['intent'] as String? ?? 'log',
        summary: json['summary'] as String? ?? '',
        answer: json['answer'] as String?,
        tags: (json['tags'] as List?)?.cast<String>() ?? const [],
        domain: json['domain'] as String? ?? 'life',
        duplicated: json['duplicated'] as bool? ?? false,
      );

  /// 是否是一次「带提问」的投递（后端判定 intent，不在前端猜）：
  /// 有提问 → 进对话态（宿主卡 = recordId，图区 = mediaIds 全部图）。
  bool get isQuestion =>
      intent == 'question' || type == 'image_qa' || (answer?.trim().isNotEmpty ?? false);
}

/// Feed/Timeline 的 mediaPaths 元素 → 媒体记录 id。
///
/// 后端 `mediaPath` 形如 `records/{yyyy}/{MM}/media/{id}.{ext}`（存储相对路径），
/// 而原图入口是 `GET /records/media/{mediaId}`——这里统一取最后一段、去掉扩展名。
/// [fallbackId] 用于路径里认不出 `rec_` 前缀时回退条目的 id（旧后端/异常数据不炸）。
String mediaRecordIdOf(String rawPathOrId, {String fallbackId = ''}) {
  final s = rawPathOrId.split('?').first.trim();
  if (s.isEmpty) return '';
  final seg = s.split('/').last;
  final dot = seg.lastIndexOf('.');
  final candidate = dot > 0 ? seg.substring(0, dot) : seg;
  if (candidate.startsWith('rec_')) return candidate;
  return fallbackId.startsWith('rec_') ? fallbackId : candidate;
}

/// Feed/Timeline 的条目 JSON → 原图路径列表。契约 2026-09-22 新增 `mediaPaths`（数组），
/// 同时兼容旧后端的单个 `mediaPath`——两者都缺 → 空（无图条目）。
List<String> _mediaPathsOf(Map<String, dynamic> json) {
  final raw = json['mediaPaths'];
  if (raw is List) {
    final list = raw.map((e) => '$e').where((e) => e.isNotEmpty).toList();
    if (list.isNotEmpty) return list;
  }
  final single = json['mediaPath'] as String?;
  return (single != null && single.isNotEmpty) ? [single] : const [];
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
  static const String market = 'market'; // 大盘行情条（v0.2.0 L5）
  // RFC 20260917：待办卡（旧 'action'）撤出 Feed——待办有自己的页面，Feed 回归纯对话流。
}

// ── DTO ──

class FeedResponse {
  final List<FeedEntryResponse> entries;
  final int totalToday;
  // 2026-09-18 空态分流：这个用户**有没有过历史记录**（不限当天，服务端口径）。
  // 为什么要服务端判：Feed 按「当天」切数据，老用户当天没记录时 entries 也为空，
  // 前端拿不到任何「他是不是新账号」的本地证据——本地标记一重装/换设备就没了，
  // 而重装后首次打开恰恰就是本 bug 的现场（老用户被当成陌生人）。
  // 旧后端不返回该字段 → 降级 false（= 维持既有空态行为，不崩不报错）。
  final bool hasHistory;

  FeedResponse({
    required this.entries,
    required this.totalToday,
    this.hasHistory = false,
  });

  factory FeedResponse.fromJson(Map<String, dynamic> json) => FeedResponse(
    entries: (json['entries'] as List).map((e) => FeedEntryResponse.fromJson(e)).toList(),
    totalToday: json['totalToday'] as int? ?? 0,
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
  final String? mediaPath; // 兼容保留：单值=首图（图片记录才有）
  // 2026-09-22 多图批：一次投递一个回合的全部原图（按上传顺序；单图/旧记录 0 或 1 条）。
  // 前端据此在**同一张卡内并列展示全部图**（此前只有 mediaPath，多图只能看首图）。
  final List<String> mediaPaths;
  final String? intent;
  final String? summary;
  final List<Map<String, dynamic>>? turns;
  final String domain;
  final String updatedAt; // P1-5（2026-08-23 app 体感）：最后活跃 ISO 时间戳（「最近记录」相对时间）
  // P2-UI12（2026-09-16）：这张卡若是后端把「同一分钟同向成交」折叠出来的，
  // 这里是被折叠进本条的原始记录 id（含本卡 id）；删除时必须逐条删全，否则刷新后又一笔笔回来。
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
    mediaPaths: _mediaPathsOf(json),
    intent: json['intent'] as String?,
    summary: json['summary'] as String?,
    turns: (json['turns'] as List?)?.cast<Map<String, dynamic>>(),
    domain: json['domain'] as String? ?? 'life',
    updatedAt: json['updatedAt'] as String? ?? '',
    // 老后端/普通条目没有这个字段 → 空（按单条处理）
    mergedIds: (json['mergedIds'] as List?)?.map((e) => '$e').toList() ?? const [],
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
  final String? mediaPath; // 兼容保留：单值=首图
  final List<String> mediaPaths; // 2026-09-22 多图批：本回合全部原图（缺字段 → 回退 [mediaPath]）

  TimelineEntryResponse({required this.id, required this.type, required this.title, required this.tags, required this.dateTime, this.mediaPath, this.mediaPaths = const []});

  factory TimelineEntryResponse.fromJson(Map<String, dynamic> json) => TimelineEntryResponse(
    id: json['id'] as String,
    type: json['type'] as String? ?? 'note',
    title: json['title'] as String? ?? '',
    tags: (json['tags'] as List?)?.cast<String>() ?? [],
    dateTime: json['dateTime'] as String? ?? '',
    mediaPath: json['mediaPath'] as String?,
    mediaPaths: _mediaPathsOf(json),
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

/// 数值解析（当日口径专用）：缺失 / null / 类型不对 → null——
/// **绝不回落成 0**，否则「不知道」会被渲染成 0（仓位 0% / 当日盈亏 0）。
double? _asNumOrNull(Object? v) {
  if (v is num) return v.toDouble();
  if (v is String) return double.tryParse(v);
  return null;
}

/// 当日口径持仓（GET /api/v1/trading/positions/daily，2026-09-14 当日口径批）。
/// [positions] 与原 /trading/positions 元素同形状（直接复用 [PositionItem] 解析）；
/// [daily] 按 symbol → 当日指标；[totalPositionRatio] = 总仓位 %（几成仓）、[cashRatio] = 现金比例 %；
/// [notes] 非空 = 有未计入项（当日盈亏偏小），调用方必须如实提示。
/// 防御式解析：字段缺失 / null / 类型不对一律不崩，数值字段解析不出就是 null（绝不回落成 0）。
class PositionsDailyResponse {
  final List<PositionItem> positions;
  final Map<String, DailyPositionDto> daily;
  final double? totalAssets;
  final double? totalMarketValue;
  final double? cashBalance;
  final double? totalPositionRatio;
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
    final rawNotes = m['notes'];
    final daily = <String, DailyPositionDto>{};
    if (rawDaily is Map) {
      rawDaily.forEach((k, v) {
        if (v is Map) daily[k.toString()] = DailyPositionDto.fromJson(v.cast<String, dynamic>());
      });
    }
    return PositionsDailyResponse(
      positions: rawPositions is List
          ? rawPositions.whereType<Map>().map((e) => PositionItem.fromJson(e.cast<String, dynamic>())).toList()
          : <PositionItem>[],
      daily: daily,
      totalAssets: _asNumOrNull(m['totalAssets']),
      totalMarketValue: _asNumOrNull(m['totalMarketValue']),
      cashBalance: _asNumOrNull(m['cashBalance']),
      totalPositionRatio: _asNumOrNull(m['totalPositionRatio']),
      cashRatio: _asNumOrNull(m['cashRatio']),
      notes: rawNotes is List
          ? rawNotes.map((e) => e?.toString() ?? '').where((s) => s.isNotEmpty).toList()
          : const <String>[],
    );
  }

  /// 该票的当日指标；没有 / 解析不出 → null（调用方渲染「—」，不渲染 0）。
  DailyPositionDto? forSymbol(String symbol) => daily[symbol];
}

/// 单票当日指标（[PositionsDailyResponse.daily] 的值）。**每个字段都可能为 null**：
/// - [todayPnl]：当日盈亏（券商口径＝今天真实赚亏，不是累计浮盈）——缺昨收 → null；
/// - [yesterdayClose]：昨收；
/// - [dayChangePct]：今日涨跌幅 %（(现价−昨收)/昨收）——缺昨收 → null；
/// - [positionRatio]：该股市值占总资产（含现金）的百分比。
class DailyPositionDto {
  final double? todayPnl;
  final double? yesterdayClose;
  final double? dayChangePct;
  final double? positionRatio;

  DailyPositionDto({
    this.todayPnl,
    this.yesterdayClose,
    this.dayChangePct,
    this.positionRatio,
  });

  factory DailyPositionDto.fromJson(Map<String, dynamic> json) => DailyPositionDto(
    todayPnl: _asNumOrNull(json['todayPnl']),
    yesterdayClose: _asNumOrNull(json['yesterdayClose']),
    dayChangePct: _asNumOrNull(json['dayChangePct']),
    positionRatio: _asNumOrNull(json['positionRatio']),
  );
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
  /// ——前端必须渲染成「—」而不是 0%。
  final double? pnlPercent;
  final double? stopLossPrice; // 2026-08-17 对齐 web：止损位（持仓卡显示）

  PositionItem({
    required this.symbol,
    required this.name,
    required this.quantity,
    required this.avgCost,
    required this.currentPrice,
    required this.marketValue,
    required this.pnl,
    this.pnlPercent,
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
    pnlPercent: (json['pnlPercent'] as num?)?.toDouble(),
    stopLossPrice: (json['stopLossPrice'] as num?)?.toDouble(),
  );
}

/// 券商快照锚定状态（GET /trading/integrity `anchor`，2026-09-12；值复制自 adai-web）。
/// [known]=false → 无法判断哪些成交已含在券商快照口径内；[holdingsKnown]=false →
/// 快照持仓基线未记录，对账无法判定（诚实降级，不误报差异）。
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

/// 账实对账差异行（`drift`，2026-09-12）：
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

  /// 人话明细：`600206 有研新材：应有 900 股（快照基线 900 + 锚点后流水 0），落地 700 股，差 -200`。
  String get display =>
      '$symbol $name：应有 $derived 股'
      '（快照基线 ${snapshotQty ?? 0} + 锚点后流水 ${ledgerDelta > 0 ? '+' : ''}$ledgerDelta），'
      '落地 ${holdings ?? 0} 股，差 ${diff > 0 ? '+' : ''}$diff';

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

/// 重放缺口行（`gaps`，2026-09-12）：卖超/未持有的流水——与历史成交导入 rejected 同一件事。
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

/// 账实一致性报告（GET /api/v1/trading/integrity，2026-09-12）：锚定状态 + 差异 + 重放缺口 + 降级流水。
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

/// RFC 20260825：逐笔批次视图响应（GET /api/v1/trading/lots）。
/// {"lots": [...], "reconcile": [...]}——reconcile 为流水重放 vs 持仓快照对账提示（app 不展示）。
class LotsResponse {
  final List<LotItem> lots;
  /// 2026-09-16：各标的累计手续费（买入/卖出/合计）——批次弹窗底部展示。
  /// 卖出含印花税万 5（仅卖出收），费率约为买入 6 倍（用户实测 442 vs 2732）。
  final Map<String, SymbolFee> fees;

  LotsResponse({required this.lots, this.fees = const {}});

  factory LotsResponse.fromJson(Map<String, dynamic> json) {
    final feeMap = <String, SymbolFee>{};
    for (final e in (json['fees'] as List?) ?? const []) {
      if (e is Map<String, dynamic>) {
        final f = SymbolFee.fromJson(e);
        if (f.symbol.isNotEmpty) feeMap[f.symbol] = f;
      }
    }
    return LotsResponse(
      lots: ((json['lots'] as List?) ?? [])
          .map((e) => LotItem.fromJson(e as Map<String, dynamic>))
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

  factory SymbolFee.fromJson(Map<String, dynamic> json) => SymbolFee(
    symbol: json['symbol'] as String? ?? '',
    buy: (json['buy'] as num?)?.toDouble() ?? 0,
    sell: (json['sell'] as num?)?.toDouble() ?? 0,
    total: (json['total'] as num?)?.toDouble() ?? 0,
  );
}

/// 单批次视图：日期/数量/剩余/成本/现价/盈亏/距止损/角色/买点（RFC 20260825）。
/// 语义：buyDate 批次买入日期；costPrice 批次加权成本（含费）；
/// stopLossPrice 批次止损（未设后端按默认 −7% 兜底返回）；
/// stopLossDistancePct 距止损%（<0 = 已破止损未走）；initial=true 初始底仓（_INIT 结尾）；
/// closed=true 已清仓回合（realizedPnl 为该回合已实现盈亏）。
class LotItem {
  final String lotId;
  final String symbol;
  final String name;
  final String buyDate; // yyyy-MM-dd
  final int volume; // 买入量
  final int remaining; // 剩余量
  final double costPrice;
  final double currentPrice;
  final double marketValue;
  final double pnl;
  /// 剩余部分浮动盈亏%：**负/零成本时为 null**（2026-09-13 负成本批）
  final double? pnlPct;
  final double? stopLossPrice;
  final double? stopLossDistancePct;
  final String? buyPoint;
  final String? role;
  final bool initial;
  final bool closed;
  final double? realizedPnl;
  /// 2026-09-16（用户要求「我想看到手续费的体现」）：该批次买入手续费合计。
  final double buyFee;

  LotItem({
    this.lotId = '',
    this.symbol = '',
    this.name = '',
    this.buyDate = '',
    this.volume = 0,
    this.remaining = 0,
    this.costPrice = 0,
    this.currentPrice = 0,
    this.marketValue = 0,
    this.pnl = 0,
    this.pnlPct,
    this.stopLossPrice,
    this.stopLossDistancePct,
    this.buyPoint,
    this.role,
    this.initial = false,
    this.closed = false,
    this.realizedPnl,
    this.buyFee = 0,
  });

  factory LotItem.fromJson(Map<String, dynamic> json) => LotItem(
    lotId: json['lotId'] as String? ?? '',
    symbol: json['symbol'] as String? ?? '',
    name: json['name'] as String? ?? '',
    buyDate: json['buyDate'] as String? ?? '',
    volume: json['volume'] as int? ?? 0,
    remaining: json['remaining'] as int? ?? 0,
    costPrice: (json['costPrice'] as num?)?.toDouble() ?? 0,
    currentPrice: (json['currentPrice'] as num?)?.toDouble() ?? 0,
    marketValue: (json['marketValue'] as num?)?.toDouble() ?? 0,
    pnl: (json['pnl'] as num?)?.toDouble() ?? 0,
    pnlPct: (json['pnlPct'] as num?)?.toDouble(),
    stopLossPrice: (json['stopLossPrice'] as num?)?.toDouble(),
    stopLossDistancePct: (json['stopLossDistancePct'] as num?)?.toDouble(),
    buyPoint: json['buyPoint'] as String?,
    role: json['role'] as String?,
    initial: json['initial'] as bool? ?? false,
    closed: json['closed'] as bool? ?? false,
    realizedPnl: (json['realizedPnl'] as num?)?.toDouble(),
    buyFee: (json['buyFee'] as num?)?.toDouble() ?? 0,
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

// ── 待办 DTO（RFC 20260917：Kernel builtin，两态）──

/// 两态状态常量（旧看板的 DOING / CANCELLED 随 project 插件一起删了）。
class TodoStatus {
  static const String open = 'OPEN';
  static const String done = 'DONE';
}

/// 一条待办。`due` 是 LocalDate（可空）：后端可能给 `null`、空串或畸形串，
/// [fromJson] 一律 tryParse 成 null（缺字段不崩，也不假装有到期日）。
class TodoItem {
  final String id;
  final String title;
  final String status;
  final DateTime? due;
  final String? sourceRecordId;
  final String createdAt;
  final String updatedAt;

  TodoItem({
    required this.id,
    required this.title,
    this.status = TodoStatus.open,
    this.due,
    this.sourceRecordId,
    this.createdAt = '',
    this.updatedAt = '',
  });

  bool get isDone => status == TodoStatus.done;

  factory TodoItem.fromJson(Map<String, dynamic> json) => TodoItem(
    id: json['id'] as String? ?? '',
    title: json['title'] as String? ?? '',
    status: json['status'] as String? ?? TodoStatus.open,
    due: _parseLocalDate(json['due']),
    sourceRecordId: json['sourceRecordId'] as String?,
    createdAt: json['createdAt'] as String? ?? '',
    updatedAt: json['updatedAt'] as String? ?? '',
  );

  /// 防御式解析：非字符串 / 空串 / 非法日期 → null。
  static DateTime? _parseLocalDate(dynamic value) {
    if (value is! String || value.trim().isEmpty) return null;
    return DateTime.tryParse(value.trim());
  }
}

class TodoStats {
  final int total;
  final int open;
  final int done;

  TodoStats({this.total = 0, this.open = 0, this.done = 0});

  factory TodoStats.fromJson(Map<String, dynamic> json) => TodoStats(
    total: json['total'] as int? ?? 0,
    open: json['open'] as int? ?? 0,
    done: json['done'] as int? ?? 0,
  );
}


// ── 交易 DTO（对齐 web，2026-08-17）──

/// 单个区间的盈亏（今日 / 本周 / 本月）。
/// pnl 单位元；pct 为百分数（可为 null：区间起点前没有曲线点，或锚定日之前不可追溯）；
/// partial=true 表示「这个区间只有部分可追溯」——UI 如实标注，不假装完整。
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
  final String asOf;        // 曲线最后一个交易日
  final String? anchorDate; // 券商快照锚定日（此前不可追溯）
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

/// 资金曲线点（date = 交易日 YYYY-MM-DD；netValue/drawdown 可空）。P2-交易52。
class EquityCurvePointDto {
  final String date;
  final double totalAssets;
  final double? netValue;
  final double? drawdown;

  EquityCurvePointDto({
    required this.date,
    required this.totalAssets,
    this.netValue,
    this.drawdown,
  });

  factory EquityCurvePointDto.fromJson(Map<String, dynamic> json) => EquityCurvePointDto(
    date: json['date'] as String? ?? '',
    totalAssets: (json['totalAssets'] as num?)?.toDouble() ?? 0,
    netValue: (json['netValue'] as num?)?.toDouble(),
    drawdown: (json['drawdown'] as num?)?.toDouble(),
  );
}

/// 资金曲线响应（P2-交易52）：点序列 + **逐日盈亏**（date → 金额）。
///
/// `dailyPnl` 的值是 **nullable** 的：某天缺收盘价时后端如实留空（不猜 0），
/// 前端必须显示「—」而不是 ¥0.00——否则等于编造一个「当天不赚不亏」。
class EquityCurveDto {
  final List<EquityCurvePointDto> points;
  final Map<String, double?> dailyPnl;
  final String startDate;
  final String endDate;

  EquityCurveDto({
    required this.points,
    required this.dailyPnl,
    required this.startDate,
    required this.endDate,
  });

  factory EquityCurveDto.fromJson(Map<String, dynamic> json) => EquityCurveDto(
    points: ((json['points'] as List?) ?? const [])
        .map((e) => EquityCurvePointDto.fromJson(e as Map<String, dynamic>))
        .toList(),
    dailyPnl: ((json['dailyPnl'] as Map?) ?? const {}).map(
      (k, v) => MapEntry(k as String, (v as num?)?.toDouble()),
    ),
    startDate: json['startDate'] as String? ?? '',
    endDate: json['endDate'] as String? ?? '',
  );
}

/// 账户总体快照（券商口径：总资产/可用/可取/市值/当日盈亏/总盈亏=资产-本金）。
class AccountSnapshotDto {
  final double assets, cash, available, withdrawable, marketValue, pnl, todayPnl;
  final double principal;
  final String snapshotDate; // D9（2026-08-23 app 体感，P2-UX3）：快照日期（收盘陈旧感知）
  // P2-交易48（2026-09-14）：当日盈亏来源（broker=券商「资金股份」文件该列求和 /
  // calc=系统按当日成交流水精算 / '' = 未知或旧后端缺字段）。UI 据此标注口径与日期；
  // 未知一律不标（宁可不说，也不编造）。
  final String todayPnlSource;
  // P2-交易69（2026-09-23）：现金这个数对应的**券商快照日期**（≠ snapshotDate）。
  // snapshotDate 是收盘更新的日期（每个交易日都被刷新），而现金只在导入「资金股份查询」时才更新。
  final String cashDate;
  // 现金健康度人话（负现金 / 无券商来源 / 过期），'' = 无需提示——文案由后端给，前端只渲染。
  final String cashNote;
  // P2-交易66（2026-09-23）：本金置信度说明（手填本金 + 历史出入金零记录时的一句话），'' = 不提示。
  final String principalNote;

  AccountSnapshotDto({required this.assets, required this.cash, required this.available,
      required this.withdrawable, required this.marketValue, required this.pnl,
      required this.todayPnl, required this.principal, this.snapshotDate = '',
      this.todayPnlSource = '', this.cashDate = '', this.cashNote = '',
      this.principalNote = ''});

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

  /// 总盈亏 = 资产 - 本金（用户确认口径，2026-08-16）。
  /// P1-前端3（2026-08-17 走查）：principal>0 兜底与 web/后端对齐——本金未录时退回券商浮盈 pnl，
  /// 否则新账号显示「总盈亏=总资产」全当盈利
  /// 账户总盈亏 = 总资产 - 本金（本金 > 0 时有效）。
  /// P2-交易31（2026-08-29，U32）：本金未设（principal=0）→ null——不给误导数值
  /// （旧实现回落浮盈 pnl 漏已实现盈亏：清仓后浮盈≈0 却显示「0 盈亏」仍是误导）；
  /// UI 显示「—」+ 引导设置本金。
  double? get totalPnl => principal > 0 ? assets - principal : null;
}

/// B11-4（2026-08-23，P1-交易18）：确认交易日志落库结果（成功/失败/跳过 + 失败人话明细）。
class TradeLogConfirmResult {
  final int confirmed, failed, skipped;
  /// 2026-09-15：与已落库流水同笔而被跳过的候选数（截图反复确认不再重复入账）。
  final int duplicated;
  /// 2026-09-18（P0-交易59）：命中券商快照锚定 → 只落流水不改账的笔数
  /// （此前是硬拒「已包含在券商快照中」，用户当天成交永远入不了账）。
  /// 旧后端不返回该字段 → 0（行为与修复前一致）。
  final int ledgerOnly;
  final List<String> failures;
  final List<String> duplicates;

  TradeLogConfirmResult({required this.confirmed, required this.failed,
      required this.skipped, this.duplicated = 0, this.ledgerOnly = 0,
      required this.failures, this.duplicates = const []});

  factory TradeLogConfirmResult.fromJson(Map<String, dynamic> json) => TradeLogConfirmResult(
    confirmed: json['confirmed'] as int? ?? 0,
    failed: json['failed'] as int? ?? 0,
    skipped: json['skipped'] as int? ?? 0,
    duplicated: json['duplicated'] as int? ?? 0,
    ledgerOnly: json['ledgerOnly'] as int? ?? 0,
    failures: (json['failures'] as List?)?.map((e) => e.toString()).toList() ?? const [],
    duplicates: (json['duplicates'] as List?)?.map((e) => e.toString()).toList() ?? const [],
  );
}

/// 当日交易日志候选（GET /trading/trade-log 与截图入账共用，2026-08-26 类型化）。
/// complete=false = 缺 symbol/direction/price/volume 任一（宽松解析残件，确认会被拒）。
/// tradeDate（2026-08-27）：截图「日期」列提取的成交日期（yyyy-MM-dd）；无 → null（确认时按确认当天）。
class TradeLogCandidateDto {
  final String symbol, name, direction;
  final double? price;
  final int? volume;
  final String? tradeDate;
  /// 2026-09-18（P0-交易59）：截图「成交时间」列（HH:mm:ss）。
  /// 同代码/同方向/同价/同量的**分单**靠它区分（生产实据：000831 两笔各 200 股 @53.300，
  /// 10:03:44 与 10:04:09）；旧后端不返回 → null，界面退回不显示。
  final String? tradeTime;
  final bool complete;
  /// P1-交易54（2026-09-17）：候选**行标识**——丢弃按它定位（同标的同方向的多笔只有它能区分）。
  /// 旧后端不返回该字段 → 空串，此时退化为旧的 symbol+direction 口径（粗粒度，会一起删）。
  final String id;

  TradeLogCandidateDto({required this.symbol, required this.name, required this.direction,
      this.price, this.volume, this.tradeDate, this.tradeTime,
      required this.complete, this.id = ''});

  factory TradeLogCandidateDto.fromJson(dynamic j) {
    final m = j is Map<String, dynamic> ? j : <String, dynamic>{};
    return TradeLogCandidateDto(
      symbol: m['symbol']?.toString() ?? '',
      name: m['name']?.toString() ?? '',
      direction: m['direction']?.toString() ?? '',
      price: double.tryParse(m['price']?.toString() ?? ''),
      volume: int.tryParse(m['volume']?.toString() ?? ''),
      tradeDate: m['tradeDate']?.toString(),
      tradeTime: m['tradeTime']?.toString(),
      complete: m['complete'] as bool? ?? false,
      id: m['id']?.toString() ?? '',
    );
  }
}

/// 2026-08-26 截图入账结果（POST /trading/screenshots）：
/// total 提交张数 / processed 成功识别张数 / candidates 当日候选（去重后）/ errors 逐张失败原因。
/// P2-交易43（2026-09-14）：dropped = 截图里**没记**的行（状态不是已成/部成、认不出的行），
/// 人话逐条（如「第 1 张 · 第 2 行「…」：状态「已报」不是已成/部成（未成交的单子没有记）」）——
/// 旧实现静默丢，用户以为整张都记上了。旧后端缺该字段 → 空列表（行为与现在完全一致）。
class TradingScreenshotResult {
  final int total, processed;
  final List<TradeLogCandidateDto> candidates;
  final List<String> errors;
  final List<String> dropped;

  TradingScreenshotResult({required this.total, required this.processed,
      required this.candidates, required this.errors, this.dropped = const []});

  factory TradingScreenshotResult.fromJson(Map<String, dynamic> json) => TradingScreenshotResult(
    total: json['total'] as int? ?? 0,
    processed: json['processed'] as int? ?? 0,
    candidates: (json['candidates'] as List? ?? const [])
        .map((e) => TradeLogCandidateDto.fromJson(e)).toList(),
    errors: (json['errors'] as List?)?.map((e) => e.toString()).toList() ?? const [],
    dropped: (json['dropped'] as List?)?.map((e) => e.toString()).toList() ?? const [],
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
/// RFC 20260909 批1：双轨后自动收录的行带 provenance=flow，券商导入/人工行为 import（老行缺省 import）。
class SoldTradeDto {
  final String symbol, name, verdict, psychology, provenance;
  final String? buyDate, sellDate;
  final int holdDays;
  final double holdPnlPct;

  SoldTradeDto({required this.symbol, required this.name, required this.buyDate,
      required this.sellDate, required this.holdDays, required this.holdPnlPct,
      required this.verdict, required this.psychology, this.provenance = 'import'});

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

/// K 线/行情链路健康度（GET /api/v1/trading/market-data/health，2026-09-23，P1-交易62）。
///
/// 只读、纯展示：后端已把「连续失败几次、链路上有哪几家源、最近一次成功/失败是什么时候」
/// 算好（[note] 是人话文案），app 端**不自己推断行情好坏**——判定口径只能有一处。
///
/// 防御式解析的两条取舍（为什么这么写）：
/// 1. **`ok` 缺失 → 按 `true`（不报警）**：与「未加载/请求失败 → 不显示横幅」同一口径——
///    「不知道」绝不能渲染成「有问题」（那会制造假警报，用户会来找不存在的毛病）；
///    也绝不能渲染成「没问题」（所以交易页是「拿不到就零显示」，而不是显示一句「行情正常」）。
/// 2. 旧后端没有这个端点 → 调用方 404 静默，本 DTO 也要能接受任意残缺 JSON 而不抛异常，
///    否则一条增强信息足以让整个交易页崩掉。
class MarketDataHealthDto {
  /// 行情链路是否正常。[false] 才需要用户看见。
  final bool ok;

  /// 后端拟好的整句人话（可能为 null：无记录时后端不出文案）。
  final String? note;
  final String? lastSuccessAt;
  final String? lastSuccessSource;
  final String? lastFailureAt;
  final int consecutiveFailures;
  final String? lastFailedSymbol;

  /// 取数链（如 tdx / 腾讯 / 东财 / 新浪），仅用于展开明细；无记录时空列表。
  final List<String> sources;

  MarketDataHealthDto({
    this.ok = true,
    this.note,
    this.lastSuccessAt,
    this.lastSuccessSource,
    this.lastFailureAt,
    this.consecutiveFailures = 0,
    this.lastFailedSymbol,
    this.sources = const [],
  });

  /// 是否需要给用户看（交易页据此决定渲不渲染横幅，缺信息=不出声）。
  bool get hasIssue => !ok;

  factory MarketDataHealthDto.fromJson(dynamic json) {
    // 非 Map（后端返回裸数组/裸字符串等异常形态）→ 当成「拿不到信息」，不抛异常
    if (json is! Map<String, dynamic>) return MarketDataHealthDto();
    return MarketDataHealthDto(
      // 只有明确的 false 才算「行情挂了」：字段缺失/null/类型异常一律不报警
      ok: json['ok'] != false,
      note: json['note']?.toString(),
      lastSuccessAt: json['lastSuccessAt']?.toString(),
      lastSuccessSource: json['lastSuccessSource']?.toString(),
      lastFailureAt: json['lastFailureAt']?.toString(),
      // 不能用 `as num?`：后端某天把次数写成字符串就会在这里抛，整条增强信息变崩溃源。
      // 类型不符 = 这个数不可信 → 0（宁少说，不编数）
      consecutiveFailures: json['consecutiveFailures'] is num
          ? (json['consecutiveFailures'] as num).toInt()
          : 0,
      lastFailedSymbol: json['lastFailedSymbol']?.toString(),
      // sources 元素也逐个 toString：后端某天多塞了非字符串（数字源名）也不该让整页崩
      sources: ((json['sources'] as List?) ?? const [])
          .map((e) => e?.toString() ?? '')
          .where((e) => e.isNotEmpty)
          .toList(),
    );
  }
}
