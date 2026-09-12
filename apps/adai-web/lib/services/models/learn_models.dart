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

/// 单篇卡片全文（GET /learn/content）：列表接口只回产品建模的四段，md 原文才读得全。
class LearnCardContentDto {
  final String type;
  final String title;
  final String topic;
  final bool writable;
  final String content; // 该卡 md 全文（含 frontmatter；需要完整文件时用）
  final String body; // 展示用正文（后端已剥 frontmatter；老后端缺失 → 空串）

  LearnCardContentDto({
    this.type = '',
    this.title = '',
    this.topic = '',
    this.writable = true,
    this.content = '',
    this.body = '',
  });

  factory LearnCardContentDto.fromJson(Map<String, dynamic> json) => LearnCardContentDto(
        type: (json['type'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        topic: (json['topic'] as String?) ?? '',
        writable: (json['writable'] as bool?) ?? true,
        content: (json['content'] as String?) ?? '',
        body: (json['body'] as String?) ?? '',
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
