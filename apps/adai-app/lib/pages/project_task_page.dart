import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// ProjectTaskPage — 任务列表 + 创建/编辑。
class ProjectTaskPage extends StatefulWidget {
  final ApiService api;

  const ProjectTaskPage({super.key, required this.api});

  @override
  State<ProjectTaskPage> createState() => _ProjectTaskPageState();
}

class _ProjectTaskPageState extends State<ProjectTaskPage> {
  List<TaskResponse> _tasks = [];
  TaskStatsResponse? _stats;
  bool _loading = true;
  String? _error;
  String? _filterStatus;
  bool _showCreate = false;
  bool _submitting = false;

  // 创建表单
  final _titleCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  final _tagCtrl = TextEditingController();
  String _priority = 'P2';

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    _descCtrl.dispose();
    _tagCtrl.dispose();
    super.dispose();
  }

  /// 首次加载 / 下拉刷新：显示整页加载指示。
  Future<void> _loadAll() async {
    setState(() { _loading = true; _error = null; });
    await _loadData();
  }

  /// 操作成功后静默刷新：不置 _loading，避免每次操作整页闪 Spinner。
  Future<void> _refresh() async {
    if (!mounted) return;
    await _loadData();
  }

  Future<void> _loadData() async {
    try {
      final results = await Future.wait([
        widget.api.getTasks(status: _filterStatus),
        widget.api.getTaskStats(),
      ]);
      if (!mounted) return;
      setState(() {
        _tasks = results[0] as List<TaskResponse>;
        _stats = results[1] as TaskStatsResponse;
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = _errText(e); _loading = false; });
    }
  }

  /// 后端故障人话（#108 + #113 错误态不再暴露技术串）。
  String _errText(dynamic e) {
    final str = e.toString();
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器，请确认后端已启动';
    return '加载失败，请重试';
  }

  Widget _buildError() {
    return Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.error_outline, size: 28, color: AppColors.darkOrange),
        const SizedBox(height: 10),
        Text(_error ?? '加载失败', style: const TextStyle(fontSize: 15, color: AppColors.darkGrey4)),
        const SizedBox(height: 14),
        GestureDetector(
          onTap: _loadAll,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
            decoration: BoxDecoration(
              color: AppColors.darkSurface2,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3)),
            ),
            child: const Text('重试', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
          ),
        ),
      ]),
    );
  }

  Future<void> _createTask() async {
    // 标题单行化：换行/tab/连续空格压成单个空格，防止多行标题破坏后端条目格式
    final title = _titleCtrl.text.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (title.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('请输入任务标题', style: TextStyle(color: AppColors.darkGrey1)),
            backgroundColor: AppColors.darkSurface2),
      );
      return;
    }
    setState(() => _submitting = true);
    try {
      final desc = _descCtrl.text.trim();
      final tagText = _tagCtrl.text.trim();
      final tags = tagText.isNotEmpty ? tagText.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).toList() : null;
      await widget.api.createTask(
        title: title,
        description: desc.isNotEmpty ? desc : null,
        priority: _priority,
        tags: tags,
      );
      if (!mounted) return;
      _titleCtrl.clear(); _descCtrl.clear(); _tagCtrl.clear();
      setState(() { _showCreate = false; _submitting = false; });
      _refresh();
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('创建失败: ${_extractApiError(e)}', style: TextStyle(color: AppColors.darkOrange)),
            backgroundColor: AppColors.darkSurface2),
      );
    }
  }

  Future<void> _updateStatus(String id, String newStatus) async {
    try {
      await widget.api.updateTask(id, status: newStatus);
      _refresh();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('更新失败: ${_extractApiError(e)}', style: TextStyle(color: AppColors.darkOrange)),
            backgroundColor: AppColors.darkSurface2),
      );
    }
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
    return 'network error';
  }

  Future<void> _deleteTask(String id) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: Text('删除任务', style: TextStyle(color: AppColors.darkGrey1, fontSize: 16)),
        content: Text('确定删除？', style: TextStyle(color: AppColors.darkGrey3)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text('取消', style: TextStyle(color: AppColors.darkGrey5))),
          TextButton(onPressed: () => Navigator.pop(context, true), child: Text('删除', style: TextStyle(color: AppColors.darkOrange))),
        ],
      ),
    );
    if (confirm == true) {
      try {
        await widget.api.deleteTask(id);
        _refresh();
      } catch (e) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('删除失败', style: TextStyle(color: AppColors.darkOrange)),
              backgroundColor: AppColors.darkSurface2),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkBg,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back, color: AppColors.darkGrey4),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text('任务', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        actions: [
          IconButton(
            icon: Icon(Icons.refresh, size: 18, color: AppColors.darkGrey5),
            onPressed: _loadAll,
          ),
          IconButton(
            icon: Icon(Icons.add_rounded, size: 18, color: AppColors.darkGreen),
            onPressed: () => setState(() => _showCreate = !_showCreate),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? _buildError()
              : ListView(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                  children: [
                    _buildStatsRow(),
                    const SizedBox(height: 12),
                    _buildFilterRow(),
                    const SizedBox(height: 8),
                    if (_showCreate) _buildCreateForm(),
                    const SizedBox(height: 8),
                    ..._tasks.map((t) => _buildTaskCard(t)),
                    if (_tasks.isEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 40),
                        child: Center(child: Text('暂无任务', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5))),
                      ),
                  ],
                ),
    );
  }

  Widget _buildStatsRow() {
    if (_stats == null) return const SizedBox.shrink();
    final s = _stats!;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _statItem('全部', s.total, AppColors.darkGrey1),
          _statItem('待办', s.todo, AppColors.darkOrange),
          _statItem('进行', s.doing, AppColors.darkBlue),
          _statItem('完成', s.done, AppColors.darkGreen),
        ],
      ),
    );
  }

  Widget _statItem(String label, int count, Color color) {
    return Column(children: [
      Text('$count', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _buildFilterRow() {
    final statuses = [null, 'TODO', 'DOING', 'DONE', 'CANCELLED'];
    final labels = ['全部', '待办', '进行', '完成', '取消'];
    final colors = [AppColors.darkGrey5, AppColors.darkOrange, AppColors.darkBlue, AppColors.darkGreen, AppColors.darkGrey5];

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: List.generate(statuses.length, (i) {
          final selected = _filterStatus == statuses[i];
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: GestureDetector(
              onTap: () {
                setState(() => _filterStatus = statuses[i]);
                _loadAll();
              },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: selected ? colors[i].withValues(alpha: 0.15) : AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: selected ? colors[i].withValues(alpha: 0.3) : AppColors.darkBorder,
                    width: 0.5,
                  ),
                ),
                child: Text(labels[i], style: TextStyle(
                  fontSize: 12, fontWeight: FontWeight.w500,
                  color: selected ? colors[i] : AppColors.darkGrey5,
                )),
              ),
            ),
          );
        }),
      ),
    );
  }

  Widget _buildCreateForm() {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _field('标题', _titleCtrl),
        const SizedBox(height: 8),
        _field('描述（可选）', _descCtrl, maxLines: 2),
        const SizedBox(height: 8),
        _field('标签（逗号分隔，可选）', _tagCtrl),
        const SizedBox(height: 8),
        Row(children: [
          _sectionTitle('优先级'),
          const SizedBox(width: 8),
          _prioChip('P0'),
          const SizedBox(width: 4),
          _prioChip('P1'),
          const SizedBox(width: 4),
          _prioChip('P2'),
          const SizedBox(width: 4),
          _prioChip('P3'),
        ]),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          height: 36,
          child: ElevatedButton(
            onPressed: _submitting ? null : _createTask,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.darkGreen.withValues(alpha: 0.2),
              foregroundColor: AppColors.darkGreen,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: Text(_submitting ? '创建中...' : '创建任务', style: const TextStyle(fontWeight: FontWeight.w500)),
          ),
        ),
      ]),
    );
  }

  Widget _field(String label, TextEditingController ctrl, {int maxLines = 1}) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 4),
      TextField(
        controller: ctrl,
        maxLines: maxLines,
        style: TextStyle(fontSize: 13, color: AppColors.darkGrey2),
        decoration: InputDecoration(
          isDense: true,
          contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          filled: true,
          fillColor: AppColors.darkBg,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(6),
            borderSide: BorderSide(color: AppColors.darkBorder, width: 0.5),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(6),
            borderSide: BorderSide(color: AppColors.darkBorder, width: 0.5),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(6),
            borderSide: BorderSide(color: AppColors.darkGreen, width: 0.5),
          ),
        ),
      ),
    ]);
  }

  Widget _prioChip(String value) {
    final selected = _priority == value;
    return GestureDetector(
      onTap: () => setState(() => _priority = value),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: selected ? AppColors.darkGreen.withValues(alpha: 0.15) : AppColors.darkBg,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: selected ? AppColors.darkGreen.withValues(alpha: 0.3) : AppColors.darkBorder,
            width: 0.5,
          ),
        ),
        child: Text(value, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500,
            color: selected ? AppColors.darkGreen : AppColors.darkGrey5)),
      ),
    );
  }

  Widget _sectionTitle(String title) {
    return Text(title, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGrey5));
  }

  Widget _buildTaskCard(TaskResponse task) {
    final statusColors = {
      'TODO': AppColors.darkOrange,
      'DOING': AppColors.darkBlue,
      'DONE': AppColors.darkGreen,
      'CANCELLED': AppColors.darkGrey5,
    };
    final statusLabels = {'TODO': '待办', 'DOING': '进行', 'DONE': '完成', 'CANCELLED': '取消'};
    final color = statusColors[task.status] ?? AppColors.darkGrey5;

    final nextStatus = task.status == 'TODO' ? 'DOING' : task.status == 'DOING' ? 'DONE' : null;

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
        border: task.status == 'DOING'
            ? Border.all(color: AppColors.darkBlue.withValues(alpha: 0.3), width: 0.5)
            : null,
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(statusLabels[task.status] ?? task.status,
                style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: color)),
          ),
          const SizedBox(width: 6),
          if (task.priority != 'P2')
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: AppColors.darkOrange.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(task.priority, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: AppColors.darkOrange)),
            ),
          const Spacer(),
          PopupMenuButton<String>(
            icon: Icon(Icons.more_horiz, size: 16, color: AppColors.darkGrey5),
            color: AppColors.darkSurface,
            onSelected: (action) {
              if (action == 'delete') _deleteTask(task.id);
              if (action == 'edit') _editTask(task);
              if (action == 'DOING' || action == 'DONE') _updateStatus(task.id, action);
            },
            itemBuilder: (_) => [
              if (nextStatus != null) PopupMenuItem(value: nextStatus, child: Text('推进 → ${statusLabels[nextStatus]}', style: TextStyle(fontSize: 13, color: AppColors.darkGrey2))),
              const PopupMenuItem(value: 'edit', child: Text('编辑', style: TextStyle(fontSize: 13, color: AppColors.darkGrey2))),
              const PopupMenuItem(value: 'delete', child: Text('删除', style: TextStyle(fontSize: 13, color: AppColors.darkOrange))),
            ],
          ),
        ]),
        const SizedBox(height: 8),
        Text(task.title, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        if (task.description.isNotEmpty) ...[
          const SizedBox(height: 4),
          Text(task.description, style: TextStyle(fontSize: 12, color: AppColors.darkGrey5), maxLines: 2, overflow: TextOverflow.ellipsis),
        ],
        if (task.tags.isNotEmpty) ...[
          const SizedBox(height: 8),
          Wrap(spacing: 6, runSpacing: 4, children: task.tags.map((t) => Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: AppColors.darkBg,
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(t, style: TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
          )).toList()),
        ],
      ]),
    );
  }

  void _editTask(TaskResponse task) {
    _titleCtrl.text = task.title;
    _descCtrl.text = task.description;
    _tagCtrl.text = task.tags.join(', ');
    _priority = task.priority;
    setState(() => _showCreate = true);
    // Scroll to form
    Scrollable.ensureVisible(context, alignment: 0.1);
  }
}
