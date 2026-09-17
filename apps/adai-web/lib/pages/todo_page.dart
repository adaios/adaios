import 'package:flutter/material.dart';
import 'dart:convert';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../widgets/page_header.dart';

/// 待办桌面形态（RFC 20260917）——**纯清单**：
/// 未完成在上 / 已完成折叠；每条 = 一句话 +（可选）到期日 + 完成 + 删除；顶部直接加一条。
///
/// 待办是 Kernel builtin：人人有、默认开、无插件门控。
/// 状态两态 `OPEN` / `DONE`（DOING/CANCELLED 已随 project 插件撤除）。
/// 第一原则「无第三视角」：页面上不出现系统口吻标签，全部按「我和阿呆」的口吻说话。
class TodoPage extends StatefulWidget {
  final ApiService api;

  const TodoPage({super.key, required this.api});

  @override
  State<TodoPage> createState() => _TodoPageState();
}

class _TodoPageState extends State<TodoPage> {
  final _addController = TextEditingController();

  List<TodoResponse> _todos = [];
  bool _loading = true;
  bool _loadFailed = false;

  /// 顶部「加一条」：正在提交守卫 + 失败提示（失败时保留已输入内容，原地可重试）。
  bool _adding = false;
  String _addError = '';

  /// 新建时选的到期日（null = 不设）。
  DateTime? _newDue;

  /// 已完成区是否展开（默认折叠）。
  bool _showDone = false;

  /// 正在提交的条目 id（防连点重复提交）。
  final Set<String> _busy = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _addController.dispose();
    super.dispose();
  }

  List<TodoResponse> get _open => _todos.where((t) => t.status != 'DONE').toList();

  List<TodoResponse> get _done => _todos.where((t) => t.status == 'DONE').toList();

  String get _subtitle {
    if (_loading) return '正在看…';
    if (_loadFailed) return '没读出来';
    if (_open.isEmpty) return _done.isEmpty ? '还没写过什么' : '手头的事都做完了';
    return '还有 ${_open.length} 件没做';
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _loadFailed = false;
    });
    try {
      final todos = await widget.api.getTodos();
      if (!mounted) return;
      setState(() {
        _todos = todos;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadFailed = true;
      });
    }
  }

  /// 顶部直接加一条：成功才清空输入；失败保留内容 + 人话原因，可直接再点一次。
  Future<void> _addTodo() async {
    final title = _addController.text.trim();
    if (title.isEmpty || _adding) return;
    final due = _newDue == null ? null : _fmtDate(_newDue!);
    setState(() {
      _adding = true;
      _addError = '';
    });
    try {
      await widget.api.createTodo(title: title, due: due);
      if (!mounted) return;
      _addController.clear();
      setState(() {
        _adding = false;
        _newDue = null;
      });
      await _load();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _adding = false;
        _addError = '没加上：${_extractApiError(e)}';
      });
    }
  }

  /// 完成 / 撤回完成（两态切换）。
  Future<void> _toggleDone(TodoResponse todo) async {
    if (_busy.contains(todo.id)) return;
    setState(() => _busy.add(todo.id));
    final next = todo.status == 'DONE' ? 'OPEN' : 'DONE';
    try {
      final updated = await widget.api.updateTodo(todo.id, status: next);
      if (!mounted) return;
      setState(() {
        _todos = _todos.map((t) => t.id == updated.id ? updated : t).toList();
        _busy.remove(todo.id);
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy.remove(todo.id));
      _showError('没改成：${_extractApiError(e)}', onRetry: () => _toggleDone(todo));
    }
  }

  /// 删除（取消即删除：不再有 CANCELLED 态）。
  Future<void> _deleteTodo(TodoResponse todo) async {
    if (_busy.contains(todo.id)) return;
    setState(() => _busy.add(todo.id));
    try {
      await widget.api.deleteTodo(todo.id);
      if (!mounted) return;
      setState(() {
        _todos = _todos.where((t) => t.id != todo.id).toList();
        _busy.remove(todo.id);
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy.remove(todo.id));
      _showError('没删掉：${_extractApiError(e)}', onRetry: () => _deleteTodo(todo));
    }
  }

  /// 改某条的到期日；[due] 传空串表示清除（后端约定空串 = 清除）。
  Future<void> _setDue(TodoResponse todo, String due) async {
    if (_busy.contains(todo.id)) return;
    setState(() => _busy.add(todo.id));
    try {
      final updated = await widget.api.updateTodo(todo.id, due: due);
      if (!mounted) return;
      setState(() {
        _todos = _todos.map((t) => t.id == updated.id ? updated : t).toList();
        _busy.remove(todo.id);
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy.remove(todo.id));
      _showError('日期没改上：${_extractApiError(e)}');
    }
  }

  /// 选日期（返回 yyyy-MM-dd；用户取消返回 null）。
  Future<String?> _pickDate({DateTime? initial}) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: initial ?? DateTime.now(),
      firstDate: DateTime(2020),
      lastDate: DateTime.now().add(const Duration(days: 3650)),
    );
    if (picked == null) return null;
    return _fmtDate(picked);
  }

  Future<void> _pickNewDue() async {
    final due = await _pickDate(initial: _newDue);
    if (due == null || !mounted) return;
    setState(() => _newDue = DateTime.tryParse(due));
  }

  Future<void> _pickTodoDue(TodoResponse todo) async {
    final due = await _pickDate(initial: DateTime.tryParse(todo.due ?? ''));
    if (due == null || !mounted) return;
    await _setDue(todo, due);
  }

  void _showError(String message, {VoidCallback? onRetry}) {
    final messenger = ScaffoldMessenger.of(context);
    messenger.clearSnackBars();
    messenger.showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
      action: onRetry == null
          ? null
          : SnackBarAction(label: '重试', textColor: AppColors.darkGreen, onPressed: onRetry),
    ));
  }

  String _extractApiError(dynamic e) {
    // 2026-08-17 走查：后端错误体 {"error":"人话"} 优先透出（与 feed/trading/profile 页同口径）
    if (e is ApiException && e.body != null && e.body!.isNotEmpty) {
      final body = e.body!.trim();
      if (body.startsWith('{')) {
        try {
          final decoded = jsonDecode(body);
          if (decoded is Map && decoded['error'] is String && (decoded['error'] as String).isNotEmpty) {
            return decoded['error'] as String;
          }
        } catch (_) {
          // JSON 解析失败继续走下面分支
        }
      } else if (!body.startsWith('<')) {
        return body; // 非 HTML 的裸文本错误体直接展示
      }
    }
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

  static String _fmtDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  /// 到期日人话：今天 / 明天 / 昨天 / 9-20（跨年带年份）。
  static String _dueLabel(String due) {
    final d = DateTime.tryParse(due);
    if (d == null) return due;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final target = DateTime(d.year, d.month, d.day);
    final diff = target.difference(today).inDays;
    if (diff == 0) return '今天';
    if (diff == 1) return '明天';
    if (diff == -1) return '昨天';
    final md = '${d.month}-${d.day}';
    return d.year == now.year ? md : '${d.year}-$md';
  }

  /// 到期状态色：过期红、今天橙、以后灰（不打断，只是让人一眼看到）。
  static Color _dueColor(String due) {
    final d = DateTime.tryParse(due);
    if (d == null) return AppColors.darkGrey5;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final target = DateTime(d.year, d.month, d.day);
    if (target.isBefore(today)) return AppColors.darkRed;
    if (target == today) return AppColors.darkOrange;
    return AppColors.darkGrey4;
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      PageHeader(
        title: '待办',
        subtitle: _subtitle,
        actions: [
          IconButton(
            onPressed: _load,
            icon: const Icon(Icons.refresh, size: 18),
            color: AppColors.darkGrey4,
            tooltip: '刷新',
          ),
        ],
      ),
      _buildAddRow(),
      Expanded(child: _buildBody()),
    ]);
  }

  /// 顶部直接加一条：一句话 +（可选）到期日 + 加。
  Widget _buildAddRow() {
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 12),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.darkBorder, width: 0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(
              child: TextField(
                key: const ValueKey('todo-input'),
                controller: _addController,
                enabled: !_adding,
                onSubmitted: (_) => _addTodo(),
                style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
                decoration: InputDecoration(
                  hintText: '加一件事…',
                  hintStyle: const TextStyle(fontSize: 13, color: AppColors.darkGrey5),
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: const BorderSide(color: AppColors.darkBorder),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: const BorderSide(color: AppColors.darkBorder),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 10),
            _buildNewDueChip(),
            const SizedBox(width: 10),
            FilledButton(
              key: const ValueKey('todo-add'),
              onPressed: _adding ? null : _addTodo,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.darkGreen,
                foregroundColor: AppColors.darkBg,
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
              child: Text(_adding ? '加上…' : '加一条',
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
            ),
          ]),
          if (_addError.isNotEmpty) ...[
            const SizedBox(height: 8),
            Row(children: [
              const Icon(Icons.error_outline, size: 14, color: AppColors.darkRed),
              const SizedBox(width: 6),
              Expanded(
                child: Text(_addError,
                    style: const TextStyle(fontSize: 12, color: AppColors.darkRed)),
              ),
              TextButton(
                onPressed: _adding ? null : _addTodo,
                child: const Text('再试一次', style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
              ),
            ]),
          ],
        ],
      ),
    );
  }

  /// 新建时的到期日选择：没选显示「到期日」，选了显示日期 + 可清除。
  Widget _buildNewDueChip() {
    final due = _newDue;
    return Row(mainAxisSize: MainAxisSize.min, children: [
      InkWell(
        key: const ValueKey('todo-new-due'),
        onTap: _adding ? null : _pickNewDue,
        borderRadius: BorderRadius.circular(8),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: due == null ? AppColors.darkBorder : AppColors.darkOrange.withValues(alpha: 0.6),
            ),
          ),
          child: Row(children: [
            Icon(Icons.event_outlined,
                size: 15, color: due == null ? AppColors.darkGrey5 : AppColors.darkOrange),
            const SizedBox(width: 6),
            Text(
              due == null ? '到期日' : _dueLabel(_fmtDate(due)),
              style: TextStyle(
                fontSize: 12,
                color: due == null ? AppColors.darkGrey5 : AppColors.darkOrange,
              ),
            ),
          ]),
        ),
      ),
      if (due != null)
        IconButton(
          key: const ValueKey('todo-new-due-clear'),
          onPressed: _adding ? null : () => setState(() => _newDue = null),
          icon: const Icon(Icons.close, size: 14),
          color: AppColors.darkGrey5,
          tooltip: '不要到期日',
          visualDensity: VisualDensity.compact,
        ),
    ]);
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_loadFailed) return _buildLoadFailed();
    final open = _open;
    final done = _done;
    if (open.isEmpty && done.isEmpty) return _buildEmpty();

    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
      children: [
        if (open.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text('手头的事都做完了。',
                style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
          )
        else
          ...open.map(_buildItem),
        if (done.isNotEmpty) ...[
          const SizedBox(height: 12),
          _buildDoneHeader(done.length),
          if (_showDone) ...[
            const SizedBox(height: 4),
            ...done.map((t) => _buildItem(t, isDone: true)),
          ],
        ],
      ],
    );
  }

  Widget _buildLoadFailed() {
    return Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Text('没读出来，再试一下？',
            style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        const SizedBox(height: 12),
        OutlinedButton(
          key: const ValueKey('todo-retry'),
          onPressed: _load,
          style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.darkGreen,
            side: const BorderSide(color: AppColors.darkBorder),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
          ),
          child: const Text('重试', style: TextStyle(fontSize: 13)),
        ),
      ]),
    );
  }

  Widget _buildEmpty() {
    return const Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.checklist_outlined, size: 32, color: AppColors.darkGrey6),
        SizedBox(height: 12),
        Text('还没有待办。想到什么，就在上面写一条吧。',
            style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
      ]),
    );
  }

  Widget _buildDoneHeader(int count) {
    return InkWell(
      key: const ValueKey('todo-done-toggle'),
      onTap: () => setState(() => _showDone = !_showDone),
      borderRadius: BorderRadius.circular(6),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
        child: Row(children: [
          Icon(_showDone ? Icons.expand_more : Icons.chevron_right,
              size: 16, color: AppColors.darkGrey5),
          const SizedBox(width: 6),
          Text('已完成 $count',
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
        ]),
      ),
    );
  }

  Widget _buildItem(TodoResponse todo, {bool isDone = false}) {
    final busy = _busy.contains(todo.id);
    return Container(
      key: ValueKey('todo-item-${todo.id}'),
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.fromLTRB(10, 8, 6, 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
      ),
      child: Row(children: [
        // 完成（两态：点一下完成，再点一下撤回）
        InkWell(
          key: ValueKey('todo-toggle-${todo.id}'),
          onTap: busy ? null : () => _toggleDone(todo),
          borderRadius: BorderRadius.circular(20),
          child: Padding(
            padding: const EdgeInsets.all(4),
            child: Icon(
              isDone ? Icons.check_circle : Icons.radio_button_unchecked,
              size: 20,
              color: isDone ? AppColors.darkGreen : AppColors.darkGrey4,
            ),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            todo.title,
            style: TextStyle(
              fontSize: 14,
              color: isDone ? AppColors.darkGrey5 : AppColors.darkGrey1,
              decoration: isDone ? TextDecoration.lineThrough : null,
              decorationColor: AppColors.darkGrey5,
            ),
          ),
        ),
        const SizedBox(width: 8),
        // 到期日（可选）：点一下改日期；有日期时旁边可清除
        if (todo.due != null && !isDone)
          InkWell(
            key: ValueKey('todo-due-${todo.id}'),
            onTap: busy ? null : () => _pickTodoDue(todo),
            borderRadius: BorderRadius.circular(6),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              child: Row(children: [
                Icon(Icons.event_outlined, size: 13, color: _dueColor(todo.due!)),
                const SizedBox(width: 4),
                Text(_dueLabel(todo.due!),
                    style: TextStyle(fontSize: 12, color: _dueColor(todo.due!))),
              ]),
            ),
          )
        else if (!isDone)
          InkWell(
            key: ValueKey('todo-due-${todo.id}'),
            onTap: busy ? null : () => _pickTodoDue(todo),
            borderRadius: BorderRadius.circular(6),
            child: const Padding(
              padding: EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              child: Text('加到期日', style: TextStyle(fontSize: 12, color: AppColors.darkGrey6)),
            ),
          ),
        if (todo.due != null && !isDone)
          IconButton(
            key: ValueKey('todo-due-clear-${todo.id}'),
            onPressed: busy ? null : () => _setDue(todo, ''),
            icon: const Icon(Icons.close, size: 13),
            color: AppColors.darkGrey6,
            tooltip: '不要到期日',
            visualDensity: VisualDensity.compact,
          ),
        IconButton(
          key: ValueKey('todo-delete-${todo.id}'),
          onPressed: busy ? null : () => _deleteTodo(todo),
          icon: const Icon(Icons.delete_outline, size: 16),
          color: AppColors.darkGrey5,
          tooltip: '删除',
          visualDensity: VisualDensity.compact,
        ),
      ]),
    );
  }
}
