/// learn 消化任务状态（2026-09-10 提交式喂入配套；2026-09-12 D 形态抓取批扩展）。
/// status = idle（没任务）| pending / running（整理中）| needs_confirmation（抓到没字幕的视频，
/// 我先告诉你多长、要花多少，等你点头）| done（完成，type/title 定位新卡）|
/// failed（失败，message 人话）| cancelled（你说先不转写，没花钱）。
/// stage = 进行中的阶段（fetching / transcribing / structuring，可能为空）；
/// source = 抓到的来源（可能为空）；cost = 转写费用预估与本月额度（通常只在上面的确认环节才有）。
class LearnDigestJob {
  final String status;
  final String type;
  final String title;
  final String message;
  final String stage;
  final String topic; // 完成后落到的主题目录（后端不一定带 → 空串，前端再问一次卡片）
  final LearnSourceDto? source;
  final LearnCostDto? cost;

  LearnDigestJob({
    required this.status,
    this.type = '',
    this.title = '',
    this.message = '',
    this.stage = '',
    this.topic = '',
    this.source,
    this.cost,
  });

  factory LearnDigestJob.fromJson(Map<String, dynamic> json) => LearnDigestJob(
        status: (json['status'] as String?) ?? 'idle',
        type: (json['type'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        message: (json['message'] as String?) ?? '',
        stage: (json['stage'] as String?) ?? '',
        topic: (json['topic'] as String?) ?? '',
        source: LearnSourceDto.fromJson(_asMap(json['source'])),
        cost: LearnCostDto.fromJson(_asMap(json['cost'])),
      );

  bool get isPending => status == 'pending';
  bool get isRunning => status == 'running';

  /// 还在忙（受理到出结果之间的所有中间态）——轮询据此决定继续等。
  bool get isActive => isPending || isRunning;
  bool get isAwaitingConfirm => status == 'needs_confirmation';
  bool get isDone => status == 'done';
  bool get isFailed => status == 'failed';
  bool get isCancelled => status == 'cancelled';

  /// 阶段的人话（B1：我跟你说「我在干嘛」，不报内部阶段名）。stage 为空返回空串，由页面兜底。
  /// reading = 图片喂入时正在读图（2026-09-12 完整升级批）。
  String get stageLabel => switch (stage) {
        'fetching' => '正在抓取原文',
        'transcribing' => '正在转写，可能要几分钟',
        'structuring' => '正在整理成卡片',
        'reading' => '正在读图',
        _ => '',
      };
}

/// 抓到的来源信息（服务端抓取回显；字段可能缺失 → 全部防御式解析）。
class LearnSourceDto {
  final String platform;
  final String title;
  final String author;
  final int? durationSeconds; // 未知 = null

  LearnSourceDto({
    this.platform = '',
    this.title = '',
    this.author = '',
    this.durationSeconds,
  });

  static LearnSourceDto? fromJson(Map<String, dynamic>? json) {
    if (json == null) return null;
    return LearnSourceDto(
      platform: (json['platform'] as String?) ?? '',
      title: (json['title'] as String?) ?? '',
      author: (json['author'] as String?) ?? '',
      durationSeconds: _toInt(json['durationSeconds']),
    );
  }

  bool get hasDuration => durationSeconds != null && durationSeconds! > 0;

  String get durationText => hasDuration ? humanDurationText(durationSeconds!) : '';

  /// 「某视频 · 某UP · 37 分钟」这种一行摘要（空字段自动省略）。
  String get summaryLine => [title, author, durationText]
      .where((e) => e.isNotEmpty)
      .join(' · ');
}

/// 转写费用预估与本月额度（费用可控条 4/5：可查、可预期）。
class LearnCostDto {
  final int? durationSeconds;
  final bool durationKnown;
  final double? estimatedYuan;
  final int? monthUsedSeconds;
  final int? quotaSeconds;
  final int? remainSeconds;

  LearnCostDto({
    this.durationSeconds,
    this.durationKnown = false,
    this.estimatedYuan,
    this.monthUsedSeconds,
    this.quotaSeconds,
    this.remainSeconds,
  });

  static LearnCostDto? fromJson(Map<String, dynamic>? json) {
    if (json == null) return null;
    return LearnCostDto(
      durationSeconds: _toInt(json['durationSeconds']),
      durationKnown: (json['durationKnown'] as bool?) ?? false,
      estimatedYuan: _toDouble(json['estimatedYuan']),
      monthUsedSeconds: _toInt(json['monthUsedSeconds']),
      quotaSeconds: _toInt(json['quotaSeconds']),
      remainSeconds: _toInt(json['remainSeconds']),
    );
  }

  /// 「37 分钟」——时长未知时给个大概，别让人以为马上就好。
  String get durationText {
    if (durationSeconds != null && durationSeconds! > 0) {
      return humanDurationText(durationSeconds!);
    }
    return durationKnown ? '' : '时长没查到';
  }

  /// 「约 0.18 元」——花钱前先把数说清。
  String get estimateText =>
      estimatedYuan == null ? '' : '约 ${estimatedYuan!.toStringAsFixed(2)} 元';

  /// 「本月还剩 9.7 小时」。
  String get remainText => remainSeconds == null ? '' : '本月还剩 ${humanHoursText(remainSeconds!)}';
}

/// 本月转写用量与额度（GET /learn/digest/quota；字段缺失一律兜底，不因解析炸掉）。
class LearnQuotaDto {
  final String month;
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
        usedSeconds: _toInt(json['usedSeconds']) ?? 0,
        usedYuan: _toDouble(json['usedYuan']) ?? 0,
        quotaSeconds: _toInt(json['quotaSeconds']) ?? 0,
        remainSeconds: _toInt(json['remainSeconds']) ?? 0,
        yuanPerHour: _toDouble(json['yuanPerHour']) ?? 0,
        asrAvailable: (json['asrAvailable'] as bool?) ?? true,
        unavailableReason: (json['unavailableReason'] as String?) ?? '',
      );

  String get usedText => humanHoursText(usedSeconds);
  String get remainText => humanHoursText(remainSeconds);
}

/// 「37 分钟」——秒数说成人话（后端 humanDuration 同口径，前端本地兜底）。
String humanDurationText(int seconds) {
  if (seconds <= 0) return '';
  final minutes = (seconds / 60).round();
  return '${minutes < 1 ? 1 : minutes} 分钟';
}

/// 「9.7 小时」/「40 分钟」——额度说成人话（后端 humanHours 同口径）。
String humanHoursText(int seconds) {
  if (seconds <= 0) return '0 分钟';
  final minutes = (seconds / 60).round();
  if (minutes < 60) return '$minutes 分钟';
  final hours = (minutes / 6).round() / 10;
  if (hours == hours.roundToDouble()) return '${hours.round()} 小时';
  return '$hours 小时';
}

int? _toInt(dynamic v) {
  if (v is int) return v;
  if (v is num) return v.toInt();
  if (v is String) return int.tryParse(v);
  return null;
}

double? _toDouble(dynamic v) {
  if (v is num) return v.toDouble();
  if (v is String) return double.tryParse(v);
  return null;
}

Map<String, dynamic>? _asMap(dynamic v) {
  if (v is Map<String, dynamic>) return v;
  if (v is Map) return Map<String, dynamic>.from(v);
  return null;
}

/// yyyy-MM-dd（或 ISO 时间戳）→ 当天零点；缺字段/空串/脏值一律 null（防御式，不炸）。
/// 只取日期部分：后端 LearnCard 的 created/reviewAt/remindedAt 都是 LocalDate。
DateTime? _toDate(dynamic v) {
  final s = v?.toString().trim() ?? '';
  if (s.isEmpty) return null;
  final m = RegExp(r'^(\d{4})-(\d{1,2})-(\d{1,2})').firstMatch(s);
  if (m == null) return null;
  final y = int.tryParse(m.group(1)!);
  final mo = int.tryParse(m.group(2)!);
  final d = int.tryParse(m.group(3)!);
  if (y == null || mo == null || d == null || mo < 1 || mo > 12 || d < 1 || d > 31) return null;
  return DateTime(y, mo, d);
}

/// 日期加减天数（用构造函数归一，避免 Duration 跨 DST 偏一小时导致差一天）。
DateTime _addDays(DateTime d, int days) => DateTime(d.year, d.month, d.day + days);

/// learn 学习卡片 DTO（RFC 20260829 learn 插件）。
/// 值复制自后端 domain/learn/LearnCard，桌面端独立解析（不跨工程 import）。
/// 2026-09-12 完整升级批：新增 topic（主题目录名）与 writable（是否本产品产出的卡）。
class LearnCardDto {
  final String type; // ai | trading | other
  final String title;
  final String platform;
  final String author;
  final String url;
  final String published;
  final String created; // yyyy-MM-dd
  final String status; // new | review | done（V2 复习流转）
  final bool tradeRelated;
  final String tradeNote;
  final List<String> tags;
  final String coreView;
  final List<String> keyPoints;
  final List<String> questions;
  final String retell; // V2 复述段（自己写的消化关键）
  final String topic; // 主题目录名（缺字段/空 → 展示为「未归类」）
  final bool writable; // false = 别处（Mac 技能）整理的原始卡，只读（缺字段按 true 兼容旧响应）
  // 2026-09-16 学习进度追踪批：后端 tree 一直返回这两个字段，前端此前没解析。
  // 进入 review 队列的日期（复习提醒按它计时，非 review 卡为空）/ 最近一次复习提醒日期。
  final DateTime? reviewAt;
  final DateTime? remindedAt;

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
        tags: _stringList(json['tags']),
        coreView: (json['coreView'] as String?) ?? '',
        keyPoints: _stringList(json['keyPoints']),
        questions: _stringList(json['questions']),
        retell: (json['retell'] as String?) ?? '',
        // 防御式：旧响应没有这两个字段也不能炸（topic 空串 → 展示兜「未归类」，writable 缺省 true 保持可写）
        topic: (json['topic'] as String?) ?? '',
        writable: (json['writable'] as bool?) ?? true,
        // 防御式：老数据可能是 null / 空串 / 脏字符串 → 一律当没有（不抛）
        reviewAt: _toDate(json['reviewAt']),
        remindedAt: _toDate(json['remindedAt']),
      );

  /// 目录里显示的主题名（空 → 未归类，与后端 DEFAULT_TOPIC 同口径）。
  String get topicLabel => topic.trim().isEmpty ? '未归类' : topic.trim();

  /// 只读卡（Mac 上整理的原始卡）：编辑/流转/反哺一律不可用。
  bool get readOnly => !writable;

  static List<String> _stringList(dynamic v) {
    if (v is List) return v.map((e) => e.toString()).toList();
    return const [];
  }
}

/// 删卡片响应（DELETE /learn/cards，2026-09-13 卡片管理批）。
/// 后端是**软删除**：文件移进 learn/_trash/（可人工找回）并从主题 README 索引里摘除；
/// 若该卡曾反哺过交易候选，那些候选会被级联清理——标题列在 cascadedCandidates 里，
/// 必须如实告诉用户（别让人以为只是少了一张卡）。
class LearnCardDeletedDto {
  final bool deleted;
  final String title;
  final String learnCardId; // 被删卡片的 id（如 learn/ai/量价关系/01-x.md）
  final List<String> cascadedCandidates; // 被级联清掉的交易候选标题（空 = 没有）

  LearnCardDeletedDto({
    this.deleted = false,
    this.title = '',
    this.learnCardId = '',
    this.cascadedCandidates = const [],
  });

  factory LearnCardDeletedDto.fromJson(Map<String, dynamic> json) => LearnCardDeletedDto(
        deleted: (json['deleted'] as bool?) ?? false,
        title: (json['title'] as String?) ?? '',
        learnCardId: (json['learnCardId'] as String?) ?? '',
        cascadedCandidates: _stringList(json['cascadedCandidates']),
      );

  /// 级联清掉的候选条数（0 = 只删了卡本身）。
  int get cascadedCount => cascadedCandidates.length;

  static List<String> _stringList(dynamic v) {
    if (v is List) return v.map((e) => e.toString()).toList();
    return const [];
  }
}

/// 产物反馈结果（RFC 20260917 §五 2b）：POST /learn/cards/feedback。
/// status = recorded（已沉淀为偏好）/ exists（这句已经记住过，不重复沉淀）；
/// canRepage = 这张卡还有 _raw 素材、可按新偏好重排一版——**后端不自动重排**（重排调 LLM 花钱），
/// 由前端据此再问用户。
class LearnFeedbackDto {
  final String status;
  final String message;
  final bool canRepage;

  LearnFeedbackDto({this.status = '', this.message = '', this.canRepage = false});

  factory LearnFeedbackDto.fromJson(Map<String, dynamic> json) => LearnFeedbackDto(
        status: (json['status'] as String?) ?? '',
        message: (json['message'] as String?) ?? '',
        canRepage: (json['canRepage'] as bool?) ?? false,
      );
}

/// 表格载荷。
class LearnPageTable {
  final List<String> headers;
  final List<List<String>> rows;
  const LearnPageTable({this.headers = const [], this.rows = const []});

  static LearnPageTable? fromJson(dynamic json) {
    if (json is! Map) return null;
    final headers = _pageStrList(json['headers']);
    final rows = <List<String>>[];
    final raw = json['rows'];
    if (raw is List) {
      for (final r in raw) {
        if (r is List) {
          final cells = _pageStrList(r);
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
    final items = _pageStrList(json['items']);
    if (title.isEmpty && items.isEmpty) return null;
    final tone = (json['tone'] as String?)?.trim().toLowerCase() ?? 'neutral';
    return LearnPageSide(
      title: title,
      tone: const ['good', 'bad', 'neutral'].contains(tone) ? tone : 'neutral',
      items: items,
    );
  }
}

/// 竖排图节点（不依赖 mermaid，宽屏窄屏都画得清楚）。
class LearnPageNode {
  final String text;
  final String note;
  final String tone;
  const LearnPageNode({this.text = '', this.note = '', this.tone = 'neutral'});
}

/// 卡片页（2026-09-15 卡片流批）：一页只讲一件事——一句结论 + 一张图或一张表。
/// **老卡没有该段 → 空列表 → 按旧形态渲染。**
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
      bullets: _pageStrList(json['bullets']),
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

List<String> _pageStrList(dynamic v) {
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

/// 单篇卡片全文（GET /learn/content）：列表接口只回产品建模的四段，md 原文才读得全。
class LearnCardContentDto {
  final String type;
  final String title;
  final String topic;
  final bool writable;
  final String content; // 该卡 md 全文（含 frontmatter；需要完整文件时用）
  final String body; // 展示用正文（后端已剥 frontmatter；老后端缺失 → 空串）
  final List<LearnPageDto> pages; // 卡片流（2026-09-15）；空 = 老卡

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

  bool get hasContent => displayText.trim().isNotEmpty;

  /// 展示优先用后端剥好的 body；老后端无 body → 回退 content（调用方还会做一次轻量清理）。
  String get displayText {
    final b = body.trim();
    return b.isNotEmpty ? b : content;
  }
}

/// 图片喂入的单张图片（POST /learn/digest/image 的 multipart 载荷）。
class LearnImageInput {
  final List<int> bytes;
  final String filename;
  final String mimeType; // image/png | image/jpeg | image/webp

  const LearnImageInput({
    required this.bytes,
    required this.filename,
    this.mimeType = 'image/png',
  });
}

/// 卡片按 created 倒序（yyyy-MM-dd 字符串可直接比较）；同日期按主题名稳定排序，避免列表抖动。
List<LearnCardDto> sortCardsByCreatedDesc(List<LearnCardDto> cards) {
  final sorted = List<LearnCardDto>.of(cards);
  sorted.sort((a, b) {
    final byDate = b.created.compareTo(a.created);
    if (byDate != 0) return byDate;
    return a.title.compareTo(b.title);
  });
  return sorted;
}

/// 一个 type 内按 topic 两级分组的目录（2026-09-12 完整升级批）：
/// 组间按「组内最新一张卡」倒序（最近动过的主题排前面），同刻按主题名排序（顺序稳定不随 Map 漂移）。
/// 卡片保持传入顺序（调用方先按 created 倒序排好）。
List<(String topic, List<LearnCardDto> cards)> groupCardsByTopic(List<LearnCardDto> cards) {
  final byTopic = <String, List<LearnCardDto>>{};
  for (final card in cards) {
    byTopic.putIfAbsent(card.topicLabel, () => <LearnCardDto>[]).add(card);
  }
  final topics = byTopic.keys.toList()
    ..sort((a, b) {
      final newestA = byTopic[a]!.first.created;
      final newestB = byTopic[b]!.first.created;
      final byDate = newestB.compareTo(newestA);
      return byDate != 0 ? byDate : a.compareTo(b);
    });
  return [for (final t in topics) (t, byTopic[t]!)];
}

/// md 卡片正文的一段（## 小标题 + 正文）——用于把列表接口没建模的段也读出来。
class LearnMdSection {
  final String title; // 段名（保留原文，含「二、核心观点」这类前缀）
  final String body; // 该段正文（md 原文，含列表/加粗等标记）

  const LearnMdSection({required this.title, required this.body});

  /// 归一化段名：去掉编号前缀（一、二、1. / 1、）与空白，用于跟产品建模的段名对齐。
  /// 例：「二、核心观点」→「核心观点」；「## 3. 金句」→「金句」。
  String get normalizedTitle {
    var t = title.trim();
    t = t.replaceFirst(RegExp(r'^[0-9]+[.、)）]\s*'), '');
    t = t.replaceFirst(RegExp(r'^[一二三四五六七八九十]+[、.)）]\s*'), '');
    t = t.replaceFirst(RegExp(r'^[（(][一二三四五六七八九十0-9]+[)）]\s*'), '');
    return t.trim();
  }

  /// 产品已建模的段（在页面上用结构化渲染，不再重复贴 md）。
  /// 注意「基本信息」不在此列：md 里的基本信息比列表字段更全（背景一句话等），照原文读出来。
  bool get isModeled =>
      const {'核心观点', '关键要点', '我的疑问', '复述', '交易相关'}.contains(normalizedTitle);
}

/// 把卡片 md 拆成「## 段」列表：跳过 frontmatter（--- 块）、H1 标题与空段；
/// 产品没建模的段（关键内容详解/金句/与主题概念的关系…）靠它读出来。
List<LearnMdSection> parseLearnMarkdown(String md) {
  final sections = <LearnMdSection>[];
  String? currentTitle;
  final buffer = StringBuffer();

  void flush() {
    final title = currentTitle;
    if (title == null) return;
    final body = buffer.toString().trim();
    if (body.isNotEmpty) sections.add(LearnMdSection(title: title, body: body));
    buffer.clear();
  }

  var inFrontmatter = false;
  var frontmatterDone = false;
  final lines = md.replaceAll('\r\n', '\n').split('\n');
  for (var i = 0; i < lines.length; i++) {
    final line = lines[i];
    final trimmed = line.trim();
    if (i == 0 && trimmed == '---') {
      inFrontmatter = true;
      continue;
    }
    if (inFrontmatter) {
      if (trimmed == '---') {
        inFrontmatter = false;
        frontmatterDone = true;
      }
      continue;
    }
    if (!frontmatterDone && trimmed == '---') continue; // 无 frontmatter 时的分隔线
    if (trimmed.startsWith('## ')) {
      flush();
      currentTitle = trimmed.substring(3).trim();
      continue;
    }
    if (trimmed.startsWith('# ')) continue; // H1 标题（页面已单独渲染卡名）
    if (currentTitle == null) continue; // 首个 ## 之前的引子内容不重复渲染
    buffer.writeln(line);
  }
  flush();
  return sections;
}


/// learn 资产树响应：{ ai: [LearnCard], trading: [...], other: [...] }（只含非空组）。
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

  /// 分组迭代顺序固定：ai → trading → other（资产页展示稳定，不随 Map 序漂移）。
  List<(String, List<LearnCardDto>)> get groups => [
        ('学习笔记 · AI', ai),
        ('学习笔记 · 交易', trading),
        ('学习笔记 · 其他', other),
      ];

  bool get isEmpty => ai.isEmpty && trading.isEmpty && other.isEmpty;

  /// 组内按 created 倒序（目录与详情取卡共用同一顺序，topic 分组后索引不会错位）。
  /// 空组跳过——空组可能是 const 列表，不硬改它。
  void sortByCreatedDesc() {
    for (final list in [ai, trading, other]) {
      if (list.isEmpty) continue;
      final sorted = sortCardsByCreatedDesc(list);
      list
        ..clear()
        ..addAll(sorted);
    }
  }

  static List<LearnCardDto> _cards(dynamic v) {
    if (v is List) {
      return v
          .map((e) => LearnCardDto.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    return <LearnCardDto>[]; // 可变列表：上层要按 created 就地排序
  }
}

/// 学习进度汇总（2026-09-16 学习卡片进度追踪批）。
///
/// **口径唯一**（与「每晚 20:00 复习提醒」对齐，别处不要另发明）：
/// - 待复习 = `status == 'review'` 且 `writable == true` 且 `reviewAt != null` 且 `reviewAt ≤ 今天 − 7 天`
///   （只读卡不参与复习流转，排除；`reviewAt` 缺失不计入）
/// - 学习中 = `status == 'review'` 但没到期待复习
/// - 已掌握 = `status == 'done'`
/// - 本周 = `created` 落在本周（周一起算，含今天）
///
/// 全部从**已加载的 tree** 本地统计，不发新请求（[fromTree] 是纯函数，便于单测钉死口径）。
class LearnProgressSummary {
  final int due; // 待复习（进队列满 7 天）
  final int learning; // 学习中（在复习队列里，还没到期）
  final int mastered; // 已掌握
  final int weekNew; // 本周新增（周一起算）

  const LearnProgressSummary({
    this.due = 0,
    this.learning = 0,
    this.mastered = 0,
    this.weekNew = 0,
  });

  /// 进 review 队列满几天才算「该回看」——与后端 LearnReviewPushService.REVIEW_STALE_DAYS 同口径。
  static const int reviewStaleDays = 7;

  factory LearnProgressSummary.fromTree(LearnTreeResponse tree, {DateTime? now}) {
    final today = _addDays(now ?? DateTime.now(), 0);
    final monday = _addDays(today, -(today.weekday - 1)); // 周一（weekday: 1=周一）
    final staleBefore = _addDays(today, -reviewStaleDays);
    var due = 0, learning = 0, mastered = 0, weekNew = 0;
    for (final card in [...tree.ai, ...tree.trading, ...tree.other]) {
      if (card.status == 'done') mastered++;
      if (card.status == 'review') {
        final ra = card.reviewAt;
        // 到期：可写 + 有进队列日期 + 已满 7 天（只读卡只读不流转 → 归「学习中」）
        if (card.writable && ra != null && !ra.isAfter(staleBefore)) {
          due++;
        } else {
          learning++;
        }
      }
      final created = _toDate(card.created);
      if (created != null && !created.isBefore(monday) && !created.isAfter(today)) weekNew++;
    }
    return LearnProgressSummary(due: due, learning: learning, mastered: mastered, weekNew: weekNew);
  }

  /// 页面头部那一行（用户拍板格式，别改）：待复习 N · 学习中 M · 已掌握 K · 本周 +J。
  String get line => '待复习 $due · 学习中 $learning · 已掌握 $mastered · 本周 +$weekNew';
}

/// learn → trading 反哺候选 DTO（RFC 20260829 V2 批 3）。
/// 值复制自后端 domain/learn/LearnTradingCandidate。
class LearnTradingCandidateDto {
  final String title;
  final String learnCardId; // 回链 learn 源卡（learn/{type}/{date}_{title}）
  final String sourceType;
  final String created;
  final String coreView;
  final List<String> keyPoints;
  final String tradeNote;
  final List<String> tags;

  LearnTradingCandidateDto({
    required this.title,
    required this.learnCardId,
    this.sourceType = 'trading',
    this.created = '',
    this.coreView = '',
    this.keyPoints = const [],
    this.tradeNote = '',
    this.tags = const [],
  });

  factory LearnTradingCandidateDto.fromJson(Map<String, dynamic> json) =>
      LearnTradingCandidateDto(
        title: (json['title'] as String?) ?? '',
        learnCardId: (json['learnCardId'] as String?) ?? '',
        sourceType: (json['sourceType'] as String?) ?? 'trading',
        created: (json['created'] as String?) ?? '',
        coreView: (json['coreView'] as String?) ?? '',
        keyPoints: _stringList(json['keyPoints']),
        tradeNote: (json['tradeNote'] as String?) ?? '',
        tags: _stringList(json['tags']),
      );

  static List<String> _stringList(dynamic v) {
    if (v is List) return v.map((e) => e.toString()).toList();
    return const [];
  }
}
