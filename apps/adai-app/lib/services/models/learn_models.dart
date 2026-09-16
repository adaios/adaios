/// 秒 → 人话时长（37 分钟 / 1 小时 12 分 / 10 小时；口径对齐后端 LearnTranscriptionService）。
String humanDuration(int seconds) {
  if (seconds <= 0) return '时长未知';
  final minutes = (seconds / 60).round();
  if (minutes < 60) return '${minutes < 1 ? 1 : minutes} 分钟';
  final h = minutes ~/ 60;
  final rest = minutes % 60;
  return rest == 0 ? '$h 小时' : '$h 小时 $rest 分';
}

int _asInt(dynamic v) => (v is num) ? v.toInt() : (int.tryParse('$v') ?? 0);
int? _asIntOrNull(dynamic v) => (v == null) ? null : _asInt(v);
double _asDouble(dynamic v) => (v is num) ? v.toDouble() : (double.tryParse('$v') ?? 0);
double? _asDoubleOrNull(dynamic v) => (v == null) ? null : _asDouble(v);

/// 日期归零（复习到期 / 本周都按「天」比，带时分秒会把边界算歪）。
DateTime _dateOnly(DateTime d) => DateTime(d.year, d.month, d.day);

/// 解析后端日期（`yyyy-MM-dd` 或 ISO datetime）；缺失/非法 → null（防御式，不抛）。
DateTime? _dateOrNull(dynamic v) {
  final s = (v is String) ? v.trim() : (v == null ? '' : '$v'.trim());
  if (s.isEmpty) return null;
  final d = DateTime.tryParse(s.length >= 10 ? s.substring(0, 10) : s);
  return d == null ? null : _dateOnly(d);
}

/// learn 消化任务状态（2026-09-10 提交式喂入配套，2026-09-12 抓取批补 stage/source/cost）：
/// status = idle（无任务）| pending（排队）| running（消化中）| needs_confirmation（转写要花钱，等用户点头）
///        | done（完成，type/title 定位新卡）| failed（失败，message 人话）| cancelled（用户选了先不转写）。
class LearnDigestJob {
  final String status;
  final String type;
  final String title;
  final String message;
  final String stage; // fetching | transcribing | structuring（可空 = ''）
  final LearnDigestSource? source; // 抓到的来源（B站视频/文章，可空）
  final LearnDigestCost? cost; // 转写费用预估与本月额度（needs_confirmation 时给，可空）

  LearnDigestJob({
    required this.status,
    this.type = '',
    this.title = '',
    this.message = '',
    this.stage = '',
    this.source,
    this.cost,
  });

  factory LearnDigestJob.fromJson(Map<String, dynamic> json) => LearnDigestJob(
        status: (json['status'] as String?) ?? 'idle',
        type: (json['type'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        message: (json['message'] as String?) ?? '',
        stage: (json['stage'] as String?) ?? '',
        source: LearnDigestSource.tryFrom(json['source']),
        cost: LearnDigestCost.tryFrom(json['cost']),
      );

  bool get isRunning => status == 'running';
  bool get isPending => status == 'pending';
  bool get isDone => status == 'done';
  bool get isFailed => status == 'failed';
  bool get isCancelled => status == 'cancelled';

  /// 转写要花钱，先等用户点头（RFC 20260912 §3.8 条 5）。
  bool get isAwaitingConfirm => status == 'needs_confirmation';

  /// 还要接着等（排队 + 消化中）。
  bool get isInProgress => isPending || isRunning;

  /// 进行中阶段人话（无阶段 = ''）。
  /// 2026-09-12 完整升级批新增 reading（图片喂入：正在读图）。
  String get stageText => switch (stage) {
        'fetching' => '正在抓取原文',
        'reading' => '正在读图',
        'transcribing' => '正在转写，可能要几分钟',
        'structuring' => '正在整理成卡片',
        _ => '',
      };

  /// 要花钱的提示人话：后端 message 优先（形如「这个视频没有字幕，需要转写：37 分钟，
  /// 预计约 0.18 元（本月剩余额度 10 小时）」）；缺了就用 cost 自己拼，再缺给兜底句。
  String get confirmText {
    if (message.isNotEmpty) return message;
    final c = cost;
    if (c == null) return '这个视频没有字幕，得先转成文字，会花一点钱。要我继续吗？';
    final parts = <String>['这个视频没有字幕，需要转写：${c.durationLabel}'];
    final yuan = c.estimatedYuanLabel;
    if (yuan.isNotEmpty) parts.add('预计约 $yuan 元');
    if (c.remainSeconds != null) parts.add('本月剩余额度 ${humanDuration(c.remainSeconds!)}');
    return '${parts.join('，')}。要我继续吗？';
  }

  /// 「先不转写」后的人话（后端 message 优先）。
  String get cancelledText =>
      message.isNotEmpty ? message : '先不转写了（这钱没花）。抓到的信息我留着，回头想整理再说一声。';
}

/// 抓到的来源（后端 DigestJobStatus.SourceView）：platform/title/author/durationSeconds 均可空。
class LearnDigestSource {
  final String platform;
  final String title;
  final String author;
  final int? durationSeconds;

  LearnDigestSource({
    this.platform = '',
    this.title = '',
    this.author = '',
    this.durationSeconds,
  });

  static LearnDigestSource? tryFrom(dynamic v) =>
      (v is Map) ? LearnDigestSource.fromJson(v.cast<String, dynamic>()) : null;

  factory LearnDigestSource.fromJson(Map<String, dynamic> json) => LearnDigestSource(
        platform: (json['platform'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        author: (json['author'] as String?) ?? '',
        durationSeconds: _asIntOrNull(json['durationSeconds']),
      );

  String get durationLabel =>
      (durationSeconds == null || durationSeconds! <= 0) ? '' : humanDuration(durationSeconds!);

  /// 一行来源人话：某视频 · 某UP · 37 分钟（都没抓到 = ''）。
  String get summaryLine {
    final parts = <String>[];
    if (title.isNotEmpty) parts.add('《$title》');
    if (author.isNotEmpty) parts.add(author);
    final d = durationLabel;
    if (d.isNotEmpty) parts.add(d);
    return parts.join(' · ');
  }
}

/// 转写费用预估与本月额度（后端 DigestJobStatus.CostView）。
class LearnDigestCost {
  final int? durationSeconds;
  final bool durationKnown;
  final double? estimatedYuan;
  final int? monthUsedSeconds;
  final int? quotaSeconds;
  final int? remainSeconds;

  LearnDigestCost({
    this.durationSeconds,
    this.durationKnown = false,
    this.estimatedYuan,
    this.monthUsedSeconds,
    this.quotaSeconds,
    this.remainSeconds,
  });

  static LearnDigestCost? tryFrom(dynamic v) =>
      (v is Map) ? LearnDigestCost.fromJson(v.cast<String, dynamic>()) : null;

  factory LearnDigestCost.fromJson(Map<String, dynamic> json) => LearnDigestCost(
        durationSeconds: _asIntOrNull(json['durationSeconds']),
        durationKnown: (json['durationKnown'] as bool?) ?? false,
        estimatedYuan: _asDoubleOrNull(json['estimatedYuan']),
        monthUsedSeconds: _asIntOrNull(json['monthUsedSeconds']),
        quotaSeconds: _asIntOrNull(json['quotaSeconds']),
        remainSeconds: _asIntOrNull(json['remainSeconds']),
      );

  String get durationLabel => (durationKnown && (durationSeconds ?? 0) > 0)
      ? humanDuration(durationSeconds!)
      : '时长未知';

  /// 费用人话（两位小数；没给钱数 = ''）。
  String get estimatedYuanLabel {
    final y = estimatedYuan;
    return y == null ? '' : y.toStringAsFixed(2);
  }
}

/// 本月转写用量与剩余额度（GET /learn/digest/quota）。
class LearnQuotaDto {
  final String month; // yyyy-MM
  final int usedSeconds;
  final double usedYuan;
  final int quotaSeconds;
  final int remainSeconds;
  final double yuanPerHour;
  final bool asrAvailable;
  final String unavailableReason;

  LearnQuotaDto({
    this.month = '',
    this.usedSeconds = 0,
    this.usedYuan = 0,
    this.quotaSeconds = 0,
    this.remainSeconds = 0,
    this.yuanPerHour = 0,
    this.asrAvailable = true,
    this.unavailableReason = '',
  });

  factory LearnQuotaDto.fromJson(Map<String, dynamic> json) => LearnQuotaDto(
        month: (json['month'] as String?) ?? '',
        usedSeconds: _asInt(json['usedSeconds']),
        usedYuan: _asDouble(json['usedYuan']),
        quotaSeconds: _asInt(json['quotaSeconds']),
        remainSeconds: _asInt(json['remainSeconds']),
        yuanPerHour: _asDouble(json['yuanPerHour']),
        asrAvailable: (json['asrAvailable'] as bool?) ?? true,
        unavailableReason: (json['unavailableReason'] as String?) ?? '',
      );

  /// 剩余额度人话（如「还剩 9 小时 40 分」）。
  String get remainLabel => '还剩 ${humanDuration(remainSeconds)}';

  /// 本月额度一行话（喂入页展示用，P2-learn15）：「本月还剩 9 小时 40 分，已用 0.10 元」。
  String get monthLabel => '本月${remainLabel}，已用 ${usedYuan.toStringAsFixed(2)} 元';

  /// 单价人话（「转写 0.29 元/小时」；后端没给单价 → ''）。
  String get priceLabel =>
      yuanPerHour <= 0 ? '' : '转写 ${yuanPerHour.toStringAsFixed(2)} 元/小时';

  /// 转写通道不可用时的人话（可用 = ''；不可用优先用后端给的原因）。
  String get unavailableLabel => asrAvailable
      ? ''
      : (unavailableReason.isNotEmpty ? unavailableReason : '这会还转不了文字，回头再说');
}

/// learn 学习卡片 DTO（RFC 20260829 learn 插件）。
/// 值复制自 adai-web learn_models + 后端 domain/learn/LearnCard（不跨工程 import）。
class LearnCardDto {
  final String type; // ai | trading | other
  final String title;
  final String platform;
  final String author;
  final String url;
  final String published;
  final String created; // yyyy-MM-dd
  final String status; // new | review | done
  final bool tradeRelated;
  final String tradeNote;
  final List<String> tags;
  final String coreView;
  final List<String> keyPoints;
  final List<String> questions;
  final String retell; // V2 复述段
  final String topic; // 主题目录名（2026-09-12 完整升级批；缺省 = 未归类）
  final bool writable; // false = Mac 侧技能整理的原始卡，只读（改/流转/反哺会被后端拒）
  // V2 S-learn1（2026-09-07）后端字段，tree 已在返回，前端此前丢掉了：
  final DateTime? reviewAt; // 进入 review 队列之日（复习提醒计时起点；非 review 卡/老数据 = null）
  final DateTime? remindedAt; // 最近一次复习提醒推送日（同卡 7 天节流用；可空）

  LearnCardDto({
    required this.type,
    required this.title,
    this.platform = '',
    this.author = '',
    this.url = '',
    this.published = '',
    required this.created,
    this.status = 'new',
    this.tradeRelated = false,
    this.tradeNote = '',
    this.tags = const [],
    this.coreView = '',
    this.keyPoints = const [],
    this.questions = const [],
    this.retell = '',
    this.topic = '',
    this.writable = true,
    this.reviewAt,
    this.remindedAt,
  });

  factory LearnCardDto.fromJson(Map<String, dynamic> json) => LearnCardDto(
        type: (json['type'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        platform: (json['platform'] as String?) ?? '',
        author: (json['author'] as String?) ?? '',
        url: (json['url'] as String?) ?? '',
        published: (json['published'] as String?) ?? '',
        created: (json['created'] as String?) ?? '',
        status: (json['status'] as String?) ?? 'new',
        tradeRelated: (json['tradeRelated'] as bool?) ?? false,
        tradeNote: (json['tradeNote'] as String?) ?? '',
        tags: _list(json['tags']),
        coreView: (json['coreView'] as String?) ?? '',
        keyPoints: _list(json['keyPoints']),
        questions: _list(json['questions']),
        retell: (json['retell'] as String?) ?? '',
        // 防御式：老后端/老卡没有这两个字段 → topic ''、writable true（当自己的卡处理）
        topic: (json['topic'] as String?) ?? '',
        writable: (json['writable'] as bool?) ?? true,
        // 防御式：缺失/非法格式 → null（老后端不返回这两个字段，复习口径里「reviewAt 缺失不计入」）
        reviewAt: _dateOrNull(json['reviewAt']),
        remindedAt: _dateOrNull(json['remindedAt']),
      );

  static List<String> _list(dynamic v) =>
      (v is List) ? v.map((e) => e.toString()).toList() : const [];

  /// 主题目录名（空 → 「未归类」，全 app 统一口径）。
  String get topicLabel => topic.isEmpty ? '未归类' : topic;

  /// 最近列表合并键（单篇阅读用 type+title）。
  String get id => '$type/$title';
}

/// 表格载荷。
class LearnPageTable {
  final List<String> headers;
  final List<List<String>> rows;
  const LearnPageTable({this.headers = const [], this.rows = const []});

  static LearnPageTable? fromJson(dynamic json) {
    if (json is! Map) return null;
    final headers = _strList(json['headers']);
    final rows = <List<String>>[];
    final raw = json['rows'];
    if (raw is List) {
      for (final r in raw) {
        if (r is List) {
          final cells = _strList(r);
          if (cells.isNotEmpty) rows.add(cells);
        }
      }
    }
    if (headers.isEmpty && rows.isEmpty) return null;
    return LearnPageTable(headers: headers, rows: rows);
  }
}

/// 数字卡载荷（v = 大号数字/词，l = 下方说明）。
class LearnPageNumber {
  final String v;
  final String l;
  const LearnPageNumber({this.v = '', this.l = ''});
}

/// 对照栏载荷（tone = good|bad|neutral）。
class LearnPageSide {
  final String title;
  final String tone;
  final List<String> items;
  const LearnPageSide({this.title = '', this.tone = 'neutral', this.items = const []});

  static LearnPageSide? fromJson(dynamic json) {
    if (json is! Map) return null;
    final title = (json['title'] as String?)?.trim() ?? '';
    final items = _strList(json['items']);
    if (title.isEmpty && items.isEmpty) return null;
    final tone = (json['tone'] as String?)?.trim().toLowerCase() ?? 'neutral';
    return LearnPageSide(
      title: title,
      tone: const ['good', 'bad', 'neutral'].contains(tone) ? tone : 'neutral',
      items: items,
    );
  }
}

/// 竖排图节点（手机端用 HTML/CSS 式排版画连线，不依赖 mermaid）。
class LearnPageNode {
  final String text;
  final String note;
  final String tone;
  const LearnPageNode({this.text = '', this.note = '', this.tone = 'neutral'});
}

/// 卡片页（2026-09-15 卡片流批）：一页只讲一件事——一句结论 + 一张图或一张表。
/// 后端把卡片 md 的 `## 卡片页` 段解析后给出；**老卡没有该段 → 空列表 → 前端按旧形态渲染**。
class LearnPageDto {
  final String kind; // points|table|numbers|compare|diagram|quote
  final String title;
  final String claim;
  final List<String> bullets;
  final LearnPageTable? table;
  final List<LearnPageNumber> numbers;
  final LearnPageSide? left;
  final LearnPageSide? right;
  final List<LearnPageNode> nodes;

  const LearnPageDto({
    this.kind = 'points',
    this.title = '',
    this.claim = '',
    this.bullets = const [],
    this.table,
    this.numbers = const [],
    this.left,
    this.right,
    this.nodes = const [],
  });

  factory LearnPageDto.fromJson(Map<String, dynamic> json) {
    final numbers = <LearnPageNumber>[];
    final rawNumbers = json['numbers'];
    if (rawNumbers is List) {
      for (final n in rawNumbers) {
        if (n is Map) {
          final v = (n['v'] as String?)?.trim() ?? '';
          final l = (n['l'] as String?)?.trim() ?? '';
          if (v.isNotEmpty || l.isNotEmpty) numbers.add(LearnPageNumber(v: v, l: l));
        }
      }
    }
    final nodes = <LearnPageNode>[];
    final rawNodes = json['nodes'];
    if (rawNodes is List) {
      for (final n in rawNodes) {
        if (n is Map) {
          final text = (n['text'] as String?)?.trim() ?? '';
          final note = (n['note'] as String?)?.trim() ?? '';
          if (text.isEmpty && note.isEmpty) continue;
          final tone = (n['tone'] as String?)?.trim().toLowerCase() ?? 'neutral';
          nodes.add(LearnPageNode(
            text: text,
            note: note,
            tone: const ['good', 'bad', 'neutral', 'info'].contains(tone) ? tone : 'neutral',
          ));
        }
      }
    }
    final kind = (json['kind'] as String?)?.trim().toLowerCase() ?? '';
    return LearnPageDto(
      kind: const ['points', 'table', 'numbers', 'compare', 'diagram', 'quote'].contains(kind)
          ? kind
          : 'points',
      title: (json['title'] as String?)?.trim() ?? '',
      claim: (json['claim'] as String?)?.trim() ?? '',
      bullets: _strList(json['bullets']),
      table: LearnPageTable.fromJson(json['table']),
      numbers: numbers,
      left: LearnPageSide.fromJson(json['left']),
      right: LearnPageSide.fromJson(json['right']),
      nodes: nodes,
    );
  }

  bool get isEmpty =>
      title.isEmpty &&
      claim.isEmpty &&
      bullets.isEmpty &&
      numbers.isEmpty &&
      nodes.isEmpty &&
      table == null &&
      left == null &&
      right == null;
}

List<String> _strList(dynamic v) {
  if (v is! List) return const [];
  final out = <String>[];
  for (final e in v) {
    if (e == null) continue;
    final s = e is String ? e.trim() : '$e'.trim();
    if (s.isNotEmpty) out.add(s);
  }
  return out;
}

List<LearnPageDto> _pageList(dynamic v) {
  if (v is! List) return const [];
  final out = <LearnPageDto>[];
  for (final e in v) {
    if (e is Map) {
      final p = LearnPageDto.fromJson(Map<String, dynamic>.from(e));
      if (!p.isEmpty) out.add(p);
    }
  }
  return out;
}

/// 卡片全文（GET /learn/content，2026-09-12 完整升级批）。
/// 列表接口只给产品建模的四个段；Mac 侧整理的卡还有「关键内容详解 / 金句 / 概念关系」等段——
/// 读全文必须走本 DTO（content = 该卡 md 原文，两种来源都完整）。
class LearnCardContentDto {
  final String type;
  final String title;
  final String topic;
  final bool writable;
  final String content; // md 原文（含 frontmatter；需要完整文件时用）
  final String body; // 展示用正文（后端已剥 frontmatter；老后端缺失 → 空串，前端自行兜底剥壳）
  final List<LearnPageDto> pages; // 卡片流（2026-09-15）；空 = 老卡，按旧形态渲染

  LearnCardContentDto({
    this.type = '',
    this.title = '',
    this.topic = '',
    this.writable = true,
    this.content = '',
    this.body = '',
    this.pages = const [],
  });

  factory LearnCardContentDto.fromJson(Map<String, dynamic> json) => LearnCardContentDto(
        type: (json['type'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        topic: (json['topic'] as String?) ?? '',
        writable: (json['writable'] as bool?) ?? true,
        content: (json['content'] as String?) ?? '',
        body: (json['body'] as String?) ?? '',
        pages: _pageList(json['pages']),
      );

  String get topicLabel => topic.isEmpty ? '未归类' : topic;
}

/// 删卡结果（DELETE /api/v1/learn/cards，2026-09-13 卡片管理动作批）。
/// 软删除：卡文件移入 learn/_trash/（不真丢，可人工找回），主题 README 索引摘行；
/// cascadedCandidates = 这张卡曾反哺过的交易候选，这次被一起清掉的标题——
/// **非空就必须如实告诉用户**（别悄悄替他清了东西）。
class LearnCardDeleteResult {
  final bool deleted;
  final String title;
  final String learnCardId; // 形如 learn/ai/量价关系/01-x.md（回收站里的身份）
  final List<String> cascadedCandidates;

  LearnCardDeleteResult({
    this.deleted = false,
    this.title = '',
    this.learnCardId = '',
    this.cascadedCandidates = const [],
  });

  factory LearnCardDeleteResult.fromJson(Map<String, dynamic> json) => LearnCardDeleteResult(
        deleted: (json['deleted'] as bool?) ?? false,
        title: (json['title'] as String?) ?? '',
        learnCardId: (json['learnCardId'] as String?) ?? '',
        cascadedCandidates: LearnCardDto._list(json['cascadedCandidates']),
      );
}

/// 产物反馈结果（RFC 20260917 §五 2b）：POST /learn/cards/feedback。
/// status = recorded（已沉淀为偏好）/ exists（这句已经记住过，不重复沉淀）；
/// canRepage = 这卡还有 _raw 素材、可按新偏好重排一版（**后端不自动重排**——重排调 LLM 花钱）。
class LearnFeedbackResult {
  final String status;
  final String message;
  final bool canRepage;

  LearnFeedbackResult({this.status = '', this.message = '', this.canRepage = false});

  factory LearnFeedbackResult.fromJson(Map<String, dynamic> json) => LearnFeedbackResult(
        status: (json['status'] as String?) ?? '',
        message: (json['message'] as String?) ?? '',
        canRepage: (json['canRepage'] as bool?) ?? false,
      );
}

/// learn 类型中文名（列表分组标题、对话流回话共用，单一口径）。
String learnTypeLabel(String type) => switch (type) {
      'ai' => 'AI / 技术',
      'trading' => '交易',
      'other' => '其他',
      _ => '其他',
    };

/// 最近学习的二级分组：type（ai/trading/other）→ topic → 卡片（created 倒序）。
class LearnTypeGroup {
  final String type;
  final List<LearnTopicGroup> topics;

  LearnTypeGroup({required this.type, required this.topics});

  String get label => learnTypeLabel(type);

  int get count => topics.fold(0, (sum, t) => sum + t.cards.length);
}

/// 主题层分组（topic 空 → 未归类）。
class LearnTopicGroup {
  final String topic;
  final List<LearnCardDto> cards;

  LearnTopicGroup({required this.topic, required this.cards});

  String get label => topic.isEmpty ? '未归类' : topic;
}

/// 学习进度汇总（2026-09-16 用户拍板口径，与「每晚 20:00 复习提醒」同一把尺子）。
///
/// **口径写死，不要另发明**：
/// - 待复习：`status == 'review'` 且 `writable == true` 且 `reviewAt != null` 且 `reviewAt ≤ 今天 − 7 天`
///   （只读卡不参与复习流转 → 排除；`reviewAt` 缺失 → 不计入，老数据宁可漏不算错）
/// - 学习中：`status == 'review'` 但不满足上面的到期条件
/// - 已掌握：`status == 'done'`
/// - 本周：`created` 落在本周（周一起算）
/// 全部从**已加载的 tree** 本地统计，不发任何网络请求。
class LearnProgress {
  final int due; // 待复习
  final int learning; // 学习中
  final int mastered; // 已掌握
  final int thisWeek; // 本周新增

  const LearnProgress({
    this.due = 0,
    this.learning = 0,
    this.mastered = 0,
    this.thisWeek = 0,
  });

  /// 复习到期窗口（天）：进入 review 队列满 7 天 → 该复习了（对齐后端 LearnReviewPushService）。
  static const int reviewWindowDays = 7;

  factory LearnProgress.of(Iterable<LearnCardDto> cards, {DateTime? now}) {
    final today = _dateOnly(now ?? DateTime.now());
    final dueBefore = today.subtract(const Duration(days: reviewWindowDays));
    final monday = today.subtract(Duration(days: today.weekday - 1)); // DateTime.weekday：周一=1
    var due = 0, learning = 0, mastered = 0, week = 0;
    for (final c in cards) {
      if (c.status == 'review') {
        final at = c.reviewAt;
        if (c.writable && at != null && !_dateOnly(at).isAfter(dueBefore)) {
          due++;
        } else {
          learning++;
        }
      } else if (c.status == 'done') {
        mastered++;
      }
      final created = _dateOrNull(c.created);
      if (created != null && !created.isBefore(monday) && !created.isAfter(today)) week++;
    }
    return LearnProgress(due: due, learning: learning, mastered: mastered, thisWeek: week);
  }

  /// 一行汇总人话：「待复习 3 · 学习中 5 · 已掌握 2 · 本周 +1」。
  String get label => '待复习 $due · 学习中 $learning · 已掌握 $mastered · 本周 +$thisWeek';
}

/// 卡片列表 → type → topic 二级分组（2026-09-12 完整升级批·最近学习按主题归置）。
/// 类型顺序固定 ai → trading → other；主题按各自最新卡 created 倒序；组内 created 倒序。
List<LearnTypeGroup> groupLearnCards(List<LearnCardDto> cards) {
  final byType = <String, Map<String, List<LearnCardDto>>>{};
  for (final card in cards) {
    final type = card.type.isEmpty ? 'other' : card.type;
    (byType[type] ??= <String, List<LearnCardDto>>{}).putIfAbsent(card.topic, () => []).add(card);
  }
  final groups = <LearnTypeGroup>[];
  for (final type in const ['ai', 'trading', 'other']) {
    final topics = byType[type];
    if (topics == null || topics.isEmpty) continue;
    final topicGroups = topics.entries.map((e) {
      final list = [...e.value]..sort((a, b) => b.created.compareTo(a.created));
      return LearnTopicGroup(topic: e.key, cards: list);
    }).toList()
      ..sort((a, b) => b.cards.first.created.compareTo(a.cards.first.created));
    groups.add(LearnTypeGroup(type: type, topics: topicGroups));
  }
  // 未知 type（后端将来加类型）兜底：排最后，不丢卡
  for (final entry in byType.entries) {
    if (const ['ai', 'trading', 'other'].contains(entry.key)) continue;
    final topicGroups = entry.value.entries.map((e) {
      final list = [...e.value]..sort((a, b) => b.created.compareTo(a.created));
      return LearnTopicGroup(topic: e.key, cards: list);
    }).toList();
    groups.add(LearnTypeGroup(type: entry.key, topics: topicGroups));
  }
  return groups;
}

/// learn 资产树响应：{ ai: [...], trading: [...], other: [...] }（只含非空组）。
class LearnTreeResponse {
  final List<LearnCardDto> ai;
  final List<LearnCardDto> trading;
  final List<LearnCardDto> other;

  LearnTreeResponse({required this.ai, required this.trading, required this.other});

  factory LearnTreeResponse.fromJson(Map<String, dynamic> json) => LearnTreeResponse(
        ai: _cards(json['ai']),
        trading: _cards(json['trading']),
        other: _cards(json['other']),
      );

  /// 全部卡片按 created 倒序合并（「最近学习」= 最新 N 篇）。
  List<LearnCardDto> get recentAll {
    final all = [...ai, ...trading, ...other];
    all.sort((a, b) => b.created.compareTo(a.created));
    return all;
  }

  bool get isEmpty => ai.isEmpty && trading.isEmpty && other.isEmpty;

  static List<LearnCardDto> _cards(dynamic v) =>
      (v is List)
          ? v.map((e) => LearnCardDto.fromJson(e as Map<String, dynamic>)).toList()
          : const [];
}
