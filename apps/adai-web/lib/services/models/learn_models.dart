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
  final LearnSourceDto? source;
  final LearnCostDto? cost;

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
  String get stageLabel => switch (stage) {
        'fetching' => '正在抓取原文',
        'transcribing' => '正在转写，可能要几分钟',
        'structuring' => '正在整理成卡片',
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
      );

  static List<String> _stringList(dynamic v) {
    if (v is List) return v.map((e) => e.toString()).toList();
    return const [];
  }
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

  static List<LearnCardDto> _cards(dynamic v) {
    if (v is List) {
      return v
          .map((e) => LearnCardDto.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    return const [];
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
