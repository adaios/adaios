import 'package:flutter/material.dart';
import '../../models/data_models.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';
import '../../widgets/dialogs.dart';
import '../../widgets/snack.dart';

/// 任务页签 — 列表 + 新建 + 状态切换 + 删除（真实后端 /project/tasks）。
class TasksTab extends StatefulWidget {
  const TasksTab({super.key, required this.store});

  final DataStore store;

  @override
  State<TasksTab> createState() => _TasksTabState();
}

class _TasksTabState extends State<TasksTab> {
  late final DataStore _store = widget.store;

  List<TaskItem>? _tasks;
  String? _error;
  bool _loading = true;

  bool _showCreate = false;
  final _titleCtrl = TextEditingController();
  String _priority = 'P2';

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    super.dispose();
  }

  Color get _priorityColor => AppColors.darkGreen;

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final tasks = await _store.loadTasks();
      if (!mounted) return;
      setState(() {
        _tasks = tasks;
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

  Future<void> _createTask() async {
    final title = _titleCtrl.text.trim();
    if (title.isEmpty) {
      showAppSnack(context, '任务标题不能为空', AppColors.darkOrange);
      return;
    }
    try {
      await _store.addTask(title, priority: _priority);
      if (!mounted) return;
      _titleCtrl.clear();
      setState(() => _showCreate = false);
      showAppSnack(context, '已创建任务', AppColors.darkGreen);
      await _load();
    } catch (e) {
      if (!mounted) return;
      showAppSnack(context, '创建失败：$e', AppColors.darkOrange);
    }
  }

  Future<void> _toggle(TaskItem task) async {
    await _store.toggleTask(task.id, task.done);
    if (!mounted) return;
    await _load();
  }

  Future<void> _delete(TaskItem task) async {
    final ok = await showConfirmDialog(
      context,
      title: '删除任务',
      message: '确定删除任务「${task.title}」？',
      confirmText: '删除',
    );
    if (!ok || !mounted) return;
    final success = await _store.deleteTask(task.id);
    if (!mounted) return;
    showAppSnack(context, success ? '已删除任务' : '删除失败', AppColors.darkOrange);
    await _load();
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
      return _buildError();
    }
    final tasks = _tasks ?? const <TaskItem>[];
    final doneCount = tasks.where((t) => t.done).length;

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        AppCard(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _statItem('全部', tasks.length, AppColors.darkGrey1),
              _statItem('待办', tasks.length - doneCount, AppColors.darkOrange),
              _statItem('已完成', doneCount, AppColors.darkGreen),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _buildCreateToggle(),
        if (_showCreate) ...[
          const SizedBox(height: 8),
          _buildCreateForm(),
        ],
        const SizedBox(height: 8),
        if (tasks.isEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 40),
            child: Center(
              child: Text('暂无任务',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
            ),
          )
        else
          for (final task in tasks) _buildTaskCard(task),
      ],
    );
  }

  Widget _buildError() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.cloud_off_outlined,
                size: 28, color: AppColors.darkOrange),
            const SizedBox(height: 10),
            Text('加载任务失败：$_error',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: _load,
              child: const Text('重试',
                  style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statItem(String label, int count, Color color) {
    return Column(children: [
      Text('$count',
          style: TextStyle(
              fontSize: 18, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _buildCreateToggle() {
    return Row(
      children: [
        const Icon(Icons.playlist_add, size: 16, color: AppColors.darkGreen),
        const SizedBox(width: 6),
        const Text('新建任务',
            style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: AppColors.darkGrey1)),
        const Spacer(),
        GestureDetector(
          onTap: () => setState(() => _showCreate = !_showCreate),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: _showCreate
                  ? AppColors.darkSurface2
                  : AppColors.darkGreen.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(
                color: _showCreate
                    ? AppColors.darkBorder
                    : AppColors.darkGreen.withValues(alpha: 0.3),
                width: 0.5,
              ),
            ),
            child: Text(
              _showCreate ? '收起' : '+ 新建',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w500,
                color: _showCreate ? AppColors.darkGrey5 : AppColors.darkGreen,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildCreateForm() {
    return AppCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        TextField(
          controller: _titleCtrl,
          onSubmitted: (_) => _createTask(),
          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
          decoration: InputDecoration(
            isDense: true,
            hintText: '任务标题（如：给复盘接真实数据）',
            hintStyle:
                const TextStyle(fontSize: 12, color: AppColors.darkGrey6),
            contentPadding:
                const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            filled: true,
            fillColor: AppColors.darkBg,
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(6),
              borderSide:
                  const BorderSide(color: AppColors.darkBorder, width: 0.5),
            ),
          ),
        ),
        const SizedBox(height: 10),
        Row(children: [
          const Text('优先级',
              style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          const SizedBox(width: 8),
          _priorityChip('P0', 'P0'),
          const SizedBox(width: 4),
          _priorityChip('P1', 'P1'),
          const SizedBox(width: 4),
          _priorityChip('P2', 'P2'),
          const SizedBox(width: 4),
          _priorityChip('P3', 'P3'),
        ]),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          height: 38,
          child: ElevatedButton(
            onPressed: _createTask,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.darkGreen.withValues(alpha: 0.2),
              foregroundColor: AppColors.darkGreen,
              shape:
                  RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('创建任务',
                style: TextStyle(fontWeight: FontWeight.w500)),
          ),
        ),
      ]),
    );
  }

  Widget _priorityChip(String value, String label) {
    final selected = _priority == value;
    return GestureDetector(
      onTap: () => setState(() => _priority = value),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: selected
              ? _priorityColor.withValues(alpha: 0.15)
              : AppColors.darkBg,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: selected
                ? _priorityColor.withValues(alpha: 0.3)
                : AppColors.darkBorder,
            width: 0.5,
          ),
        ),
        child: Text(label,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w500,
              color: selected ? _priorityColor : AppColors.darkGrey5,
            )),
      ),
    );
  }

  Widget _buildTaskCard(TaskItem task) {
    final priorityColor = switch (task.priority) {
      'P0' => AppColors.darkOrange,
      'P1' => AppColors.darkYellow,
      'P3' => AppColors.darkGrey5,
      'high' => AppColors.darkOrange,
      'low' => AppColors.darkGrey5,
      _ => AppColors.darkBlue,
    };

    return AppCard(
      margin: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Checkbox(
            value: task.done,
            activeColor: AppColors.darkGreen,
            checkColor: AppColors.darkBg,
            side: const BorderSide(color: AppColors.darkGrey5),
            onChanged: (_) => _toggle(task),
          ),
          Expanded(
            child: Text(
              task.title,
              style: TextStyle(
                fontSize: 13,
                color: task.done ? AppColors.darkGrey5 : AppColors.darkGrey2,
                decoration: task.done ? TextDecoration.lineThrough : null,
              ),
            ),
          ),
          const SizedBox(width: 6),
          AppBadge(
            label: task.priorityLabel,
            color: priorityColor,
            icon: task.priority == 'P0' || task.priority == 'high'
                ? Icons.priority_high
                : task.priority == 'P3' || task.priority == 'low'
                    ? Icons.low_priority
                    : null,
          ),
          const SizedBox(width: 6),
          AppBadge(
            label: task.statusLabel,
            color: task.done ? AppColors.darkGreen : AppColors.darkOrange,
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline,
                size: 16, color: AppColors.darkGrey5),
            onPressed: () => _delete(task),
            tooltip: '删除任务',
            visualDensity: VisualDensity.compact,
          ),
        ],
      ),
    );
  }
}
