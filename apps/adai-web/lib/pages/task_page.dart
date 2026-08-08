import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../widgets/page_header.dart';

/// 任务桌面形态 — 看板三列 TODO/DOING/DONE + quick-add。
class TaskPage extends StatefulWidget {
  final ApiService api;

  const TaskPage({super.key, required this.api});

  @override
  State<TaskPage> createState() => _TaskPageState();
}

class _TaskPageState extends State<TaskPage> {
  final _quickController = TextEditingController();
  List<TaskResponse> _tasks = [];
  bool _loading = true;

  // #112：补 CANCELLED——此前看板只列 TODO/DOING/DONE，已取消任务不可见
  static const _statusOrder = ['TODO', 'DOING', 'DONE', 'CANCELLED'];
  static const _statusColor = {
    'TODO': AppColors.darkOrange,
    'DOING': AppColors.darkBlue,
    'DONE': AppColors.darkGreen,
    'CANCELLED': AppColors.darkGrey5,
  };
  static const _statusLabel = {
    'TODO': '待做',
    'DOING': '进行中',
    'DONE': '已完成',
    'CANCELLED': '已取消',
  };

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _quickController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final tasks = await widget.api.getTasks();
      if (!mounted) return;
      setState(() {
        _tasks = tasks;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  Future<void> _quickAdd() async {
    final title = _quickController.text.trim();
    if (title.isEmpty) return;
    _quickController.clear();
    try {
      await widget.api.createTask(title: title);
      await _load();
    } catch (e) {
      if (mounted) _showError('创建失败: ${_extractApiError(e)}');
    }
  }

  Future<void> _moveTask(TaskResponse task, int delta) async {
    final idx = _statusOrder.indexOf(task.status);
    final next = idx + delta;
    if (idx < 0 || next < 0 || next >= _statusOrder.length) return;
    try {
      await widget.api.updateTask(task.id, status: _statusOrder[next]);
      await _load();
    } catch (e) {
      if (mounted) _showError('更新失败: ${_extractApiError(e)}');
    }
  }

  Future<void> _deleteTask(TaskResponse task) async {
    try {
      await widget.api.deleteTask(task.id);
      await _load();
    } catch (e) {
      if (mounted) _showError('删除失败: ${_extractApiError(e)}');
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
    ));
  }

  String _extractApiError(dynamic e) {
    final str = e.toString();
    if (str.contains('API 请求失败')) {
      final codeMatch = RegExp(r'HTTP (\d+)').firstMatch(str);
      final code = codeMatch?.group(1) ?? '?';
      return '请求失败 ($code)';
    }
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器';
    return '网络异常，请重试';
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      PageHeader(title: '任务', subtitle: '项目任务看板', actions: [_buildQuickAdd()]),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: _statusOrder.map((status) => Expanded(child: _buildColumn(status))).toList(),
              ),
      ),
    ]);
  }

  Widget _buildQuickAdd() {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: SizedBox(
        width: 260,
        child: TextField(
          controller: _quickController,
          onSubmitted: (_) => _quickAdd(),
          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
          decoration: InputDecoration(
            hintText: '快速添加任务…',
            hintStyle: const TextStyle(fontSize: 12, color: AppColors.darkGrey5),
            isDense: true,
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: const BorderSide(color: AppColors.darkBorder),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: const BorderSide(color: AppColors.darkBorder),
            ),
            suffixIcon: IconButton(
              onPressed: _quickAdd,
              icon: const Icon(Icons.add, size: 16),
              color: AppColors.darkGreen,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildColumn(String status) {
    final color = _statusColor[status]!;
    final columnTasks = _tasks.where((t) => t.status == status).toList();
    final idx = _statusOrder.indexOf(status);

    return Container(
      margin: const EdgeInsets.fromLTRB(12, 4, 12, 16),
      padding: const EdgeInsets.fromLTRB(10, 12, 10, 10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(children: [
            Container(width: 8, height: 8, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
            const SizedBox(width: 6),
            Text(_statusLabel[status]!,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
            const SizedBox(width: 6),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
              decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(8)),
              child: Text('${columnTasks.length}',
                  style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: color)),
            ),
          ]),
          const SizedBox(height: 8),
          const Divider(height: 1, color: AppColors.darkBorder),
          const SizedBox(height: 8),
          Expanded(
            child: columnTasks.isEmpty
                ? const Center(child: Text('空', style: TextStyle(fontSize: 11, color: AppColors.darkGrey6)))
                : ListView.builder(
                    itemCount: columnTasks.length,
                    itemBuilder: (_, i) => _buildTaskCard(columnTasks[i], idx),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildTaskCard(TaskResponse task, int statusIdx) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(task.title,
              style: TextStyle(
                fontSize: 13,
                color: task.status == 'DONE' ? AppColors.darkGrey5 : AppColors.darkGrey1,
                decoration: task.status == 'DONE' ? TextDecoration.lineThrough : null,
                decorationColor: AppColors.darkGrey5,
                fontWeight: FontWeight.w500,
              )),
          if (task.description.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(task.description,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
          ],
          if (task.priority.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(task.priority, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: _priorityColor(task.priority))),
          ],
          const SizedBox(height: 6),
          Row(children: [
            IconButton(
              onPressed: statusIdx > 0 ? () => _moveTask(task, -1) : null,
              icon: const Icon(Icons.chevron_left, size: 18),
              color: AppColors.darkGrey4,
              tooltip: '上一步',
            ),
            IconButton(
              onPressed: statusIdx < _statusOrder.length - 1 ? () => _moveTask(task, 1) : null,
              icon: const Icon(Icons.chevron_right, size: 18),
              color: AppColors.darkGreen,
              tooltip: '下一步',
            ),
            const Spacer(),
            IconButton(
              onPressed: () => _deleteTask(task),
              icon: const Icon(Icons.delete_outline, size: 16),
              color: AppColors.darkGrey5,
              tooltip: '删除',
            ),
          ]),
        ],
      ),
    );
  }

  Color _priorityColor(String p) {
    if (p.startsWith('P0')) return AppColors.darkRed;
    if (p.startsWith('P1')) return AppColors.darkOrange;
    if (p.startsWith('P3')) return AppColors.darkGrey5;
    return AppColors.darkBlue;
  }
}
