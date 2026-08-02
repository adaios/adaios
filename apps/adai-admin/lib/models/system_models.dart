/// 系统操作台模块模型（mock 阶段）。
/// 对应 adai-core：Feed / Market / TradingReview / 知识反哺 / 维护操作。
library;

/// Feed 条目 — 时间线中的一条（record / card / action / market）。
class FeedItem {
  FeedItem({
    required this.id,
    required this.type,
    required this.title,
    required this.subtitle,
    required this.time,
  });

  /// 条目唯一标识。
  final String id;

  /// 类型：record（记录）/ card（卡片）/ action（动作/待办）/ market（行情）。
  final String type;

  /// 主标题。
  final String title;

  /// 副标题 / 摘要。
  final String subtitle;

  /// 时间。
  final DateTime time;

  String get typeLabel => switch (type) {
        'record' => '记录',
        'card' => '卡片',
        'action' => '动作',
        'market' => '行情',
        _ => type,
      };
}

/// 大盘指数快照。
class MarketIndex {
  MarketIndex({
    required this.code,
    required this.name,
    required this.price,
    required this.changePercent,
  });

  final String code;
  final String name;
  final double price;
  final double changePercent;
}

/// 持仓实时价快照。
class PositionQuote {
  PositionQuote({
    required this.symbol,
    required this.name,
    required this.price,
    required this.changePercent,
  });

  final String symbol;
  final String name;
  final double price;
  final double changePercent;
}

/// 复盘条目。
class TradingReview {
  TradingReview({
    required this.id,
    required this.date,
    required this.title,
    required this.generated,
  });

  final String id;
  final DateTime date;
  final String title;

  /// 是否已生成复盘内容。
  bool generated;
}

/// 知识反哺 — promote 候选（可提升为设计原则 / 规则）。
class PromoteCandidate {
  PromoteCandidate({
    required this.id,
    required this.content,
    required this.source,
    required this.handled,
  });

  final String id;
  final String content;
  final String source;
  bool handled;
}

/// 知识反哺 — 冲突项（两条规则 / 方案存在冲突）。
class ConflictItem {
  ConflictItem({
    required this.id,
    required this.sideA,
    required this.sideB,
    required this.handled,
  });

  final String id;
  final String sideA;
  final String sideB;
  bool handled;
}

/// 维护操作结果（mock 模拟异步操作返回）。
class MaintenanceResult {
  const MaintenanceResult({required this.success, required this.message});

  final bool success;
  final String message;
}
