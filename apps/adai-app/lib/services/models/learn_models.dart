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
        tags: _list(json['tags']),
        coreView: (json['coreView'] as String?) ?? '',
        keyPoints: _list(json['keyPoints']),
        questions: _list(json['questions']),
        retell: (json['retell'] as String?) ?? '',
      );

  static List<String> _list(dynamic v) =>
      (v is List) ? v.map((e) => e.toString()).toList() : const [];

  /// 最近列表合并键（单篇阅读用 type+title）。
  String get id => '$type/$title';
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
