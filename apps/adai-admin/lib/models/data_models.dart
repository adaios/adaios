/// 数据管理模块模型（mock 阶段）。
/// 对应 adai-core Kernel：Record / Memory / Identity / Task / Position。
library;

/// 内容记录 — 最小个人事件单元（对应 Kernel Record）。
class ContentRecord {
  ContentRecord({
    required this.id,
    required this.type,
    required this.content,
    required this.tags,
    required this.createdAt,
  });

  /// 记录唯一标识。
  final String id;

  /// 类型：statement（陈述）/ question（提问）/ todo（待办）。
  final String type;

  /// 内容（列表显示摘要；mock 阶段整段可编辑）。
  String content;

  /// 标签。
  final List<String> tags;

  /// 记录时间。
  final DateTime createdAt;

  String get typeLabel => switch (type) {
        'statement' => '陈述',
        'question' => '提问',
        'todo' => '待办',
        _ => type,
      };
}

/// 记忆条目 — AI 理解沉淀（对应 Kernel Memory）。
class MemoryItem {
  MemoryItem({
    required this.id,
    required this.kind,
    required this.content,
    required this.superseded,
    required this.createdAt,
  });

  /// 记忆唯一标识。
  final String id;

  /// 类型：insight（洞察）/ preference（偏好）/ actionable（待办）/ summary（摘要）/ meta（元）。
  final String kind;

  /// 记忆内容（可手动修正）。
  String content;

  /// 是否已被新版本取代（被取代则淡化显示）。
  bool superseded;

  /// 沉淀时间。
  final DateTime createdAt;

  String get kindLabel => switch (kind) {
        'insight' => '洞察',
        'preference' => '偏好',
        'actionable' => '待办',
        'summary' => '摘要',
        'meta' => '元',
        _ => kind,
      };
}

/// 个人档案（对应 Kernel Identity）。
class IdentityProfile {
  IdentityProfile({
    required this.name,
    required this.preferences,
    required this.rules,
    required this.tags,
  });

  /// 姓名。
  String name;

  /// 偏好键值对（如 语言 → 中文）。
  Map<String, String> preferences;

  /// AI 协作规则（如「新功能先写 RFC」）。
  List<String> rules;

  /// 标签。
  List<String> tags;
}

/// 任务（对应 Kernel Record 的 todo 投影 / Project OS 任务）。
class TaskItem {
  TaskItem({
    required this.id,
    required this.title,
    required this.done,
    required this.priority,
    required this.createdAt,
  });

  /// 任务唯一标识。
  final String id;

  /// 标题。
  String title;

  /// 完成状态。
  bool done;

  /// 优先级：high / medium / low。
  final String priority;

  /// 创建时间。
  final DateTime createdAt;

  String get statusLabel => done ? '已完成' : '待办';

  String get priorityLabel => switch (priority) {
        'high' => '高',
        'medium' => '中',
        'low' => '低',
        _ => priority,
      };
}

/// 持仓（对应 trading Position）。
class Position {
  Position({
    required this.symbol,
    required this.name,
    required this.quantity,
    required this.avgCost,
    required this.currentPrice,
  });

  /// 代码（如 510300 / 600519）。
  final String symbol;

  /// 名称。
  final String name;

  /// 持仓数量。
  final double quantity;

  /// 平均成本。
  final double avgCost;

  /// 最新价。
  final double currentPrice;

  /// 浮动盈亏（按持仓量计算）。
  double get profit => (currentPrice - avgCost) * quantity;

  /// 盈亏率。
  double get profitPercent =>
      avgCost == 0 ? 0 : ((currentPrice - avgCost) / avgCost) * 100;

  /// 市值。
  double get marketValue => currentPrice * quantity;
}
