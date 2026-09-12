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
  String get stageText => switch (stage) {
        'fetching' => '正在抓取原文',
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
