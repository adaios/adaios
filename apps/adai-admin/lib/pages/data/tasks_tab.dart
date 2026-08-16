import 'package:flutter/material.dart';
import '../../models/data_models.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';

/// 任务页签 — 治理视角查看任务列表 + 统计（真实后端 /project/tasks）。
/// 只读：任务 CRUD 归用户端 app/web（P-role-04），admin 不提供新建/状态切换/删除。
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
        ],
      ),
    );
  }
}
