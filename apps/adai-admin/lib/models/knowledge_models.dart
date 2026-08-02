/// 知识浏览模块模型（mock 阶段）。
/// 对应 os/ 知识资产：目录树（复用 TreeNode）+ 术语/规则列表。
library;

/// 术语 / 规则条目（如 trading-os 的 terms 与 rules）。
class TermRule {
  const TermRule({
    required this.name,
    required this.definition,
    required this.category,
    required this.source,
  });

  /// 术语名 / 规则编号（如「持仓」/「R096」）。
  final String name;

  /// 定义 / 规则描述。
  final String definition;

  /// 分类：术语 / 规则。
  final String category;

  /// 来源 os（trading-os / life-os / project-os / 全局）。
  final String source;
}
