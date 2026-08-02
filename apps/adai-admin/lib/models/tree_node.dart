/// 通用目录树节点 — 用于 data/ 与 os/ 资产的树形浏览（mock 阶段）。
/// 后端 API 补充后，可替换为真实的文件系统 / 知识库接口返回结构。
class TreeNode {
  const TreeNode({
    required this.name,
    required this.path,
    required this.isDir,
    this.children = const [],
    this.content,
    this.meta,
  });

  /// 节点显示名。
  final String name;

  /// 完整路径（如 `data/records/2026-08/`）。
  final String path;

  /// true=目录，false=文件。
  final bool isDir;

  /// 子节点（仅目录有意义）。
  final List<TreeNode> children;

  /// 文件内容预览（mock）；目录为空。
  final String? content;

  /// 附加信息（如文件大小 / 目录条目数），显示在名称右侧。
  final String? meta;
}
