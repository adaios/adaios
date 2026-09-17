import 'dart:convert';
import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../theme/app_colors.dart';

/// 加载类错误的人话（无第三视角：不甩异常原文，给人话 + 出路）。
String _loadErrText(dynamic e) {
  final str = e.toString();
  if (str.contains('TimeoutException') || str.contains('timed out')) {
    return '等太久了，检查下网络再试';
  }
  if (str.contains('Connection refused') || str.contains('SocketException')) {
    return '暂时连不上，检查下网络再试';
  }
  return '没能取回你的待办，稍后再试一次';
}

/// 写动作失败的人话：**后端 `{"error":"人话"}` 优先透出**，取不到再按错误类型兜底。
String _apiErrText(dynamic e) {
  if (e is ApiException && e.body != null) {
    try {
      final json = jsonDecode(e.body!);
      if (json is Map && json['error'] is String) return json['error'] as String;
    } catch (_) {}
  }
  final s = e.toString();
  if (s.contains('TimeoutException') || s.contains('timed out')) {
    return '等太久了，检查下网络再试';
  }
  if (s.contains('SocketException') || s.contains('Connection refused')) {
    return '暂时连不上，检查下网络再试';
  }
  return '这次没成功，稍后再试一次';
}

/// 到期日人话（RFC 20260917）：今天 / 明天 / 已过期 / 具体日子。
/// 纯函数 + [now] 可注入，便于单测；[markOverdue]=false 用于已完成条目
/// ——做完的事不必再谈过期。
String? todoDueLabel(DateTime? due, {DateTime? now, bool markOverdue = true}) {
  if (due == null) return null;
  final current = now ?? DateTime.now();
  final today = DateTime(current.year, current.month, current.day);
  final d = DateTime(due.year, due.month, due.day);
  final diff = d.difference(today).inDays;
  final dateText = d.year == today.year
      ? '${d.month}月${d.day}日'
      : '${d.year}年${d.month}月${d.day}日';
  if (diff == 0) return '今天';
  if (diff == 1) return '明天';
  if (diff < 0) return markOverdue ? '$dateText · 已过期' : dateText;
  return dateText;
}

/// 到期日是否已经过去（决定是否用暗橙提醒）。
bool todoDueOverdue(DateTime? due, {DateTime? now}) {
  if (due == null) return false;
  final current = now ?? DateTime.now();
  final today = DateTime(current.year, current.month, current.day);
  return DateTime(due.year, due.month, due.day).isBefore(today);
}

/// TodoPage — 待办清单（RFC 20260917：Kernel builtin，两态 OPEN/DONE）。
///
/// 形态是**纯清单**：没做完的在上、做完的折起来；每条就一句话 + 一个可选的到期日。
/// 顶部一行就能加一条（标题 + 可选到期日 + 加上）。
class TodoPage extends StatefulWidget {
  final ApiService api;

  const TodoPage({super.key, required this.api});

  @override
  State<TodoPage> createState() => _TodoPageState();
}

class _TodoPageState extends State<TodoPage> {
  List<TodoItem> _open = [];
  List<TodoItem> _done = [];
  bool _loading = true;
  String? _error;
  bool _adding = false;
  bool _doneExpanded = false; // 已完成默认收起
  DateTime? _due; // 正在输入这条的到期日（可选）
  final Set<String> _busy = {}; // 正在提交的条目 id（防连点重复提交）
  final _titleCtrl = TextEditingController();

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

  /// 首次加载 / 重试：整页 loading。
  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    await _fetch();
  }

  /// 写操作成功后的静默刷新（不整页闪 loading）。
  Future<void> _refresh() async {
    if (!mounted) return;
    await _fetch();
  }

  Future<void> _fetch() async {
    try {
      final all = await widget.api.getTodos();
      if (!mounted) return;
      setState(() {
        _open = all.where((t) => !t.isDone).toList();
        _done = all.where((t) => t.isDone).toList();
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = _loadErrText(e);
      });
    }
  }

  /// 加一条。标题单行化（换行/连续空格压成一个空格），空标题不提交。
  Future<void> _add() async {
    final title = _titleCtrl.text.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (title.isEmpty) {
      _toast('写点什么吧，一句话就行');
      return;
    }
    if (_adding) return;
    setState(() => _adding = true);
    try {
      await widget.api.createTodo(title: title, due: _due);
      if (!mounted) return;
      _titleCtrl.clear();
      setState(() {
        _adding = false;
        _due = null;
      });
      await _refresh();
    } catch (e) {
      if (!mounted) return;
      setState(() => _adding = false);
      _toast('没记上：${_apiErrText(e)}');
    }
  }

  /// 勾选 = 完成；再点一下 = 重新打开。
  Future<void> _toggle(TodoItem t) async {
    if (_busy.contains(t.id)) return;
    setState(() => _busy.add(t.id));
    try {
      await widget.api.updateTodo(
        t.id,
        status: t.isDone ? TodoStatus.open : TodoStatus.done,
      );
      if (!mounted) return;
      setState(() => _busy.remove(t.id));
      await _refresh();
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy.remove(t.id));
      _toast('没勾上：${_apiErrText(e)}');
    }
  }

  /// 删掉一条（删前问一句，删错了没法回头）。
  Future<void> _delete(TodoItem t) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('删掉这条？',
            style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
        content: Text(t.title,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('先留着', style: TextStyle(color: AppColors.darkGrey4)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('删掉', style: TextStyle(color: AppColors.darkOrange)),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    if (_busy.contains(t.id)) return;
    setState(() => _busy.add(t.id));
    try {
      await widget.api.deleteTodo(t.id);
      if (!mounted) return;
      setState(() => _busy.remove(t.id));
      await _refresh();
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy.remove(t.id));
      _toast('没删掉：${_apiErrText(e)}');
    }
  }

  Future<void> _pickDue() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _due ?? now,
      firstDate: DateTime(now.year - 1),
      lastDate: DateTime(now.year + 5, 12, 31),
      helpText: '哪天要记得',
      cancelText: '算了',
      confirmText: '就这天',
    );
    if (picked == null || !mounted) return;
    setState(() => _due = DateTime(picked.year, picked.month, picked.day));
  }

  void _toast(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
    ));
  }

  /// 44pt 热区：小图标也要点得到（触控下限，别只画个 16px 的叉）。
  Widget _hitBox({
    Key? key,
    required Widget child,
    required VoidCallback? onTap,
    String? semantic,
  }) {
    return Semantics(
      key: key,
      button: true,
      label: semantic,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: SizedBox(width: 44, height: 44, child: Center(child: child)),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkBg,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: AppColors.darkGrey4),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text('待办',
            style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: AppColors.darkGrey1)),
      ),
      body: SafeArea(
        child: Column(children: [
          _buildComposer(),
          Expanded(child: _buildBody()),
        ]),
      ),
    );
  }

  Widget _buildComposer() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 12, 6),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: TextField(
              key: const Key('todo-input'),
              controller: _titleCtrl,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _add(),
              style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
              decoration: InputDecoration(
                hintText: '想到什么就写下来…',
                hintStyle: const TextStyle(fontSize: 14, color: AppColors.darkGrey6),
                isDense: true,
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                filled: true,
                fillColor: AppColors.darkSurface2,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide:
                      BorderSide(color: AppColors.darkGreen.withValues(alpha: 0.4)),
                ),
              ),
            ),
          ),
          const SizedBox(width: 4),
          _hitBox(
            key: const Key('todo-due-picker'),
            semantic: '选到期日',
            onTap: _pickDue,
            child: Icon(
              Icons.event_outlined,
              size: 20,
              color: _due != null ? AppColors.darkGreen : AppColors.darkGrey5,
            ),
          ),
          const SizedBox(width: 4),
          SizedBox(
            height: 44,
            child: ElevatedButton(
              key: const Key('todo-add'),
              onPressed: _adding ? null : _add,
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.darkGreen.withValues(alpha: 0.18),
                foregroundColor: AppColors.darkGreen,
                elevation: 0,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12)),
              ),
              child: _adding
                  ? const SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: AppColors.darkGreen))
                  : const Text('加上',
                      style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
            ),
          ),
        ]),
        if (_due != null)
          Row(children: [
            const SizedBox(width: 4),
            Text('到期：${todoDueLabel(_due, markOverdue: false)}',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
            GestureDetector(
              key: const Key('todo-due-clear'),
              onTap: () => setState(() => _due = null),
              behavior: HitTestBehavior.opaque,
              child: const Padding(
                padding: EdgeInsets.symmetric(horizontal: 8, vertical: 15),
                child: Text('不设了',
                    style: TextStyle(fontSize: 11, color: AppColors.darkGreen)),
              ),
            ),
          ]),
      ]),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) return _buildError();
    if (_open.isEmpty && _done.isEmpty) return _buildEmpty();
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 32),
      children: [
        if (_open.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 24),
            child: Center(
              child: Text('都做完了，歇会儿吧。',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
            ),
          )
        else
          ..._open.map(_buildRow),
        if (_done.isNotEmpty) ...[
          const SizedBox(height: 8),
          _buildDoneHeader(),
          if (_doneExpanded) ..._done.map(_buildRow),
        ],
      ],
    );
  }

  Widget _buildEmpty() {
    return const Center(
      child: Padding(
        padding: EdgeInsets.symmetric(horizontal: 32),
        child: Text(
          '还没有待办。想到什么就写下来，我替你记着。',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 14, color: AppColors.darkGrey4, height: 1.5),
        ),
      ),
    );
  }

  Widget _buildError() {
    return Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Icon(Icons.error_outline, size: 28, color: AppColors.darkOrange),
        const SizedBox(height: 10),
        Text(_error ?? '没能取回你的待办',
            style: const TextStyle(fontSize: 14, color: AppColors.darkGrey4)),
        const SizedBox(height: 14),
        SizedBox(
          height: 44,
          child: ElevatedButton(
            key: const Key('todo-retry'),
            onPressed: _load,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.darkSurface2,
              foregroundColor: AppColors.darkGreen,
              elevation: 0,
              padding: const EdgeInsets.symmetric(horizontal: 20),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
                side: BorderSide(color: AppColors.darkGreen.withValues(alpha: 0.3)),
              ),
            ),
            child: const Text('重试', style: TextStyle(fontSize: 13)),
          ),
        ),
      ]),
    );
  }

  Widget _buildDoneHeader() {
    return InkWell(
      key: const Key('todo-done-toggle'),
      onTap: () => setState(() => _doneExpanded = !_doneExpanded),
      borderRadius: BorderRadius.circular(10),
      child: SizedBox(
        height: 44,
        child: Row(children: [
          Icon(_doneExpanded ? Icons.expand_more : Icons.chevron_right,
              size: 18, color: AppColors.darkGrey5),
          const SizedBox(width: 4),
          Text('已完成 (${_done.length})',
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
        ]),
      ),
    );
  }

  Widget _buildRow(TodoItem t) {
    final due = todoDueLabel(t.due, markOverdue: !t.isDone);
    final overdue = !t.isDone && todoDueOverdue(t.due);
    final busy = _busy.contains(t.id);
    return Container(
      key: ValueKey('todo-row-${t.id}'),
      margin: const EdgeInsets.symmetric(vertical: 2),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(children: [
        _hitBox(
          key: Key('todo-check-${t.id}'),
          semantic: t.isDone ? '标记为没做完' : '标记为做完',
          onTap: busy ? null : () => _toggle(t),
          child: busy
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: AppColors.darkGreen))
              : Icon(
                  t.isDone
                      ? Icons.check_circle
                      : Icons.radio_button_unchecked,
                  size: 22,
                  color: t.isDone ? AppColors.darkGreen : AppColors.darkGrey5,
                ),
        ),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(
              t.title,
              style: TextStyle(
                fontSize: 14,
                color: t.isDone ? AppColors.darkGrey5 : AppColors.darkGrey1,
                decoration: t.isDone ? TextDecoration.lineThrough : null,
                decorationColor: AppColors.darkGrey5,
              ),
            ),
            if (due != null)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(due,
                    style: TextStyle(
                      fontSize: 11,
                      color: overdue ? AppColors.darkOrange : AppColors.darkGrey5,
                    )),
              ),
          ]),
        ),
        _hitBox(
          key: Key('todo-delete-${t.id}'),
          semantic: '删掉这条',
          onTap: busy ? null : () => _delete(t),
          child: const Icon(Icons.delete_outline,
              size: 18, color: AppColors.darkGrey5),
        ),
      ]),
    );
  }
}
