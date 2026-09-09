/// learn 消化任务状态（2026-09-10 提交式喂入配套）：
/// status = idle（无任务）| running（消化中）| done（完成，type/title 定位新卡）| failed（失败，message 人话）。
class LearnDigestJob {
  final String status;
  final String type;
  final String title;
  final String message;

  LearnDigestJob({
    required this.status,
    this.type = '',
    this.title = '',
    this.message = '',
  });

  factory LearnDigestJob.fromJson(Map<String, dynamic> json) => LearnDigestJob(
        status: (json['status'] as String?) ?? 'idle',
        type: (json['type'] as String?) ?? '',
        title: (json['title'] as String?) ?? '',
        message: (json['message'] as String?) ?? '',
      );

  bool get isRunning => status == 'running';
  bool get isDone => status == 'done';
  bool get isFailed => status == 'failed';
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
