import 'package:flutter/material.dart';
import '../../models/tree_node.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/tree_view.dart';
import '../../widgets/file_preview.dart';

/// data/ 文件树页签 — 树形目录浏览（懒加载），点文件拉取内容（真实后端 /admin/files）。
class DataTreeTab extends StatefulWidget {
  const DataTreeTab({super.key, required this.store});

  final DataStore store;

  @override
  State<DataTreeTab> createState() => _DataTreeTabState();
}

class _DataTreeTabState extends State<DataTreeTab> {
  late final DataStore _store = widget.store;

  TreeNode? _root;
  TreeNode? _selected;
  bool _loading = true;
  bool _contentLoading = false;
  String? _error;
  String? _contentError; // 2026-09-06 审查 P1-C：内容加载失败不再静默吞（伪装「无内容」）

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final children = await _store.loadFiles('');
      if (!mounted) return;
      setState(() {
        _root = TreeNode(
          name: 'data/',
          path: '',
          isDir: true,
          children: children,
        );
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<List<TreeNode>> _loadChildren(TreeNode dir) {
    return _store.loadFiles(dir.path);
  }

  Future<void> _onFileTap(TreeNode node) async {
    setState(() {
      _selected = node;
      _contentLoading = true;
      _contentError = null;
    });
    try {
      final content = await _store.loadFileContent(node.path);
      if (!mounted) return;
      setState(() {
        _selected = content ?? node;
        _contentLoading = false;
      });
    } catch (e) {
      // P1-C：读文件失败不能静默回落「无内容预览」——管理员会误判文件为空；
      // 展示人话错误 + 重试，与「真空内容」区分。
      if (!mounted) return;
      setState(() {
        _contentLoading = false;
        _contentError = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(
        child: CircularProgressIndicator(
            strokeWidth: 2, color: AppColors.darkGreen),
      );
    }
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.cloud_off_outlined,
                  size: 28, color: AppColors.darkOrange),
              const SizedBox(height: 10),
              Text('加载文件树失败：$_error',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      fontSize: 12, color: AppColors.darkGrey4)),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _load,
                child: const Text('重试',
                    style:
                        TextStyle(fontSize: 12, color: AppColors.darkGreen)),
              ),
            ],
          ),
        ),
      );
    }
    final root = _root;
    if (root == null) return const SizedBox.shrink();

    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 700;
        final tree = AppCard(
          padding: const EdgeInsets.all(10),
          child: SingleChildScrollView(
            child: TreeView(
              root: root,
              expandedByDefault: true,
              onLoadChildren: _loadChildren,
              onFileTap: _onFileTap,
            ),
          ),
        );
        // P2-13（2026-09-06 拍板）：文件树是全局 data/（含全部账号目录）——
        // 与数据区其它「按用户」页签作用域不同，顶部显式标注防管理员误判为当前用户的数据
        final treeColumn = Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: AppColors.darkBorder, width: 0.5),
              ),
              child: const Row(
                children: [
                  Icon(Icons.public, size: 13, color: AppColors.darkOrange),
                  SizedBox(width: 6),
                  Expanded(
                    child: Text('全局 data/ 文件树（含全部账号目录，非当前用户视图）',
                        style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            tree,
          ],
        );

        if (wide) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(width: 300, child: treeColumn),
                const SizedBox(width: 12),
                Expanded(child: _contentPanel(_selected, wide)),
              ],
            ),
          );
        }

        return ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
          children: [
            treeColumn,
            const SizedBox(height: 12),
            _contentPanel(_selected, wide),
          ],
        );
      },
    );
  }

  Widget _contentPanel(TreeNode? node, bool wide) {
    return AppCard(
      child: node == null
          ? Padding(
              padding: EdgeInsets.symmetric(vertical: 32),
              child: Center(
                child: Column(
                  children: [
                    Icon(Icons.folder_open_outlined,
                        size: 30, color: AppColors.darkGrey6),
                    SizedBox(height: 10),
                    Text(wide ? '在左侧选择 data/ 下的文件查看内容' : '在上方选择 data/ 下的文件查看内容',
                        style: TextStyle(
                            fontSize: 12, color: AppColors.darkGrey5)),
                  ],
                ),
              ),
            )
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      node.isDir ? Icons.folder_outlined : Icons.description_outlined,
                      size: 15,
                      color: AppColors.darkGreen,
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        node.path,
                        style: const TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: AppColors.darkGrey1),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (node.meta != null)
                      Text(node.meta!,
                          style: const TextStyle(
                              fontSize: 11, color: AppColors.darkGrey6)),
                  ],
                ),
                const SizedBox(height: 10),
                if (_contentLoading)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: Center(
                      child: SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    ),
                  )
                else if (_contentError != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(Icons.error_outline,
                                size: 15, color: AppColors.darkOrange),
                            const SizedBox(width: 6),
                            Expanded(
                              child: Text('读取内容失败：$_contentError',
                                  style: const TextStyle(
                                      fontSize: 12,
                                      color: AppColors.darkGrey3)),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        OutlinedButton(
                          onPressed: () => _onFileTap(node),
                          child: const Text('重试',
                              style: TextStyle(
                                  fontSize: 12, color: AppColors.darkGreen)),
                        ),
                      ],
                    ),
                  )
                else if (node.isDir)
                  Text(
                    '目录 · ${node.children.length} 个条目',
                    style: const TextStyle(
                        fontSize: 13, color: AppColors.darkGrey4),
                  )
                else if (node.content != null)
                  // P3-17（2026-09-06）：超大文件预览截断（前 4000 字符），防全量渲染卡顿
                  FilePreview(text: node.content!)
                else
                  const Text('（无内容预览）',
                      style: TextStyle(
                          fontSize: 12, color: AppColors.darkGrey6)),
              ],
            ),
    );
  }
}
