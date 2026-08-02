import 'package:flutter/material.dart';
import '../models/tree_node.dart';
import '../theme/app_colors.dart';

/// 可展开/折叠的目录树。
///
/// 目录点击切换展开；文件点击通过 [onFileTap] 回调到父级展示内容。
/// 提供 [onLoadChildren] 支持懒加载：目录首次展开时通过回调拉取子节点。
class TreeView extends StatefulWidget {
  const TreeView({
    super.key,
    required this.root,
    this.onFileTap,
    this.onLoadChildren,
    this.depth = 0,
    this.expandedByDefault = false,
  });

  final TreeNode root;
  final ValueChanged<TreeNode>? onFileTap;

  /// 目录懒加载回调：传入待展开目录，返回子节点列表。
  final Future<List<TreeNode>> Function(TreeNode dir)? onLoadChildren;

  final int depth;
  final bool expandedByDefault;

  @override
  State<TreeView> createState() => _TreeViewState();
}

class _TreeViewState extends State<TreeView> {
  late bool _expanded;
  late List<TreeNode> _children;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _expanded = widget.expandedByDefault;
    _children = widget.root.children;
    if (_expanded) _ensureLoaded();
  }

  @override
  void didUpdateWidget(TreeView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.root.children != widget.root.children && !_loading) {
      _children = widget.root.children;
    }
  }

  Future<void> _ensureLoaded() async {
    final node = widget.root;
    if (!node.isDir) return;
    if (_children.isNotEmpty || _loading) return;
    final loader = widget.onLoadChildren;
    if (loader == null) return;
    setState(() => _loading = true);
    try {
      final children = await loader(node);
      if (!mounted) return;
      setState(() {
        _children = children;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  void _toggle() {
    setState(() => _expanded = !_expanded);
    if (_expanded) _ensureLoaded();
  }

  @override
  Widget build(BuildContext context) {
    final node = widget.root;
    final isDir = node.isDir;
    final indent = widget.depth * 18.0;
    final color = isDir ? AppColors.darkGreen : AppColors.darkGrey4;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        InkWell(
          onTap: isDir ? _toggle : () => widget.onFileTap?.call(node),
          borderRadius: BorderRadius.circular(6),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
            margin: EdgeInsets.only(left: indent),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(6),
              color: isDir
                  ? AppColors.darkGreen.withValues(alpha: 0.06)
                  : Colors.transparent,
            ),
            child: Row(
              children: [
                Icon(
                  isDir
                      ? (_expanded
                          ? Icons.folder_open_outlined
                          : Icons.folder_outlined)
                      : Icons.insert_drive_file_outlined,
                  size: 15,
                  color: color,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    node.name,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: isDir ? FontWeight.w600 : FontWeight.w400,
                      color: isDir ? AppColors.darkGrey1 : AppColors.darkGrey3,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (_loading)
                  const Padding(
                    padding: EdgeInsets.only(right: 6),
                    child: SizedBox(
                      width: 12,
                      height: 12,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  )
                else if (node.meta != null)
                  Padding(
                    padding: const EdgeInsets.only(left: 6),
                    child: Text(
                      node.meta!,
                      style: const TextStyle(
                          fontSize: 10, color: AppColors.darkGrey6),
                    ),
                  ),
                if (isDir)
                  Icon(
                    _expanded ? Icons.expand_less : Icons.expand_more,
                    size: 16,
                    color: AppColors.darkGrey5,
                  ),
              ],
            ),
          ),
        ),
        if (isDir && _expanded)
          for (final child in _children)
            TreeView(
              root: child,
              onFileTap: widget.onFileTap,
              onLoadChildren: widget.onLoadChildren,
              depth: widget.depth + 1,
              expandedByDefault: widget.depth < 1,
            ),
      ],
    );
  }
}
