import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../services/models/identity_models.dart';
import '../widgets/page_header.dart';

/// 档案桌面形态 — 左身份卡 + 右编辑区两栏。
class ProfilePage extends StatefulWidget {
  final ApiService api;

  const ProfilePage({super.key, required this.api});

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  IdentityResponse? _identity;
  bool _loading = true;
  bool _saving = false;

  final _nameCtrl = TextEditingController();
  final _tagsCtrl = TextEditingController();
  final List<_KvRow> _prefRows = [];
  final List<_KvRow> _ruleRows = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _tagsCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final identity = await widget.api.getIdentity();
      if (!mounted) return;
      setState(() {
        _identity = identity;
        _loading = false;
        _nameCtrl.text = identity.name;
        _tagsCtrl.text = identity.tags.join(', ');
        _prefRows
          ..clear()
          ..addAll(identity.preferences.entries.map((e) => _KvRow(e.key, e.value)));
        _ruleRows
          ..clear()
          ..addAll(identity.rules.entries.map((e) => _KvRow(e.key, e.value)));
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      final request = IdentityRequest(
        name: _nameCtrl.text.trim(),
        preferences: {for (final r in _prefRows) if (r.key.text.trim().isNotEmpty) r.key.text.trim(): r.value.text.trim()},
        rules: {for (final r in _ruleRows) if (r.key.text.trim().isNotEmpty) r.key.text.trim(): r.value.text.trim()},
        tags: _tagsCtrl.text.split(',').map((t) => t.trim()).where((t) => t.isNotEmpty).toList(),
      );
      await widget.api.updateIdentity(request);
      await _load();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('已保存', style: TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
        backgroundColor: AppColors.darkSurface2,
        duration: Duration(seconds: 2),
      ));
    } catch (e) {
      if (mounted) _showError('保存失败: $e');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      PageHeader(title: '档案', subtitle: '个人档案与 AI 协作规则'),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _identity == null
                ? const Center(child: Text('加载失败', style: TextStyle(color: AppColors.darkGrey5)))
                : Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SizedBox(width: 300, child: _buildIdentityCard()),
                      const VerticalDivider(width: 1, color: AppColors.darkBorder),
                      Expanded(child: _buildEditArea()),
                    ],
                  ),
      ),
    ]);
  }

  Widget _buildIdentityCard() {
    final i = _identity!;
    return Container(
      margin: const EdgeInsets.all(16),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Column(
        children: [
          const SizedBox(height: 8),
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              color: AppColors.darkGreen.withValues(alpha: 0.15),
              shape: BoxShape.circle,
            ),
            child: Center(
              child: Text(
                i.name.isEmpty ? '阿呆' : i.name.characters.first,
                style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w700, color: AppColors.darkGreen),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Text(i.name.isEmpty ? '未命名' : i.name,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const SizedBox(height: 4),
          const Text('Personal AI OS', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          const SizedBox(height: 16),
          const Divider(color: AppColors.darkBorder),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.centerLeft,
            child: Text('偏好 ${i.preferences.length} · 规则 ${i.rules.length} · 标签 ${i.tags.length}',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ),
          const SizedBox(height: 12),
          if (i.tags.isNotEmpty)
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: i.tags.map((t) => Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
                ),
                child: Text('#$t', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3)),
              )).toList(),
            ),
        ],
      ),
    );
  }

  Widget _buildEditArea() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
      children: [
        _section('姓名', [
          TextField(
            controller: _nameCtrl,
            style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
            decoration: _inputDecoration('你的名字'),
          ),
        ]),
        const SizedBox(height: 16),
        _section('偏好（AI 如何与你协作）', [
          ..._prefRows.map((r) => _kvRowWidget(r, () => setState(() => _prefRows.remove(r)))),
          _addRowButton('添加偏好', () => setState(() => _prefRows.add(_KvRow('', '')))),
        ]),
        const SizedBox(height: 16),
        _section('协作规则', [
          ..._ruleRows.map((r) => _kvRowWidget(r, () => setState(() => _ruleRows.remove(r)))),
          _addRowButton('添加规则', () => setState(() => _ruleRows.add(_KvRow('', '')))),
        ]),
        const SizedBox(height: 16),
        _section('标签（逗号分隔）', [
          TextField(
            controller: _tagsCtrl,
            style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
            decoration: _inputDecoration('如: trading, life, project'),
          ),
        ]),
        const SizedBox(height: 20),
        FilledButton.icon(
          onPressed: _saving ? null : _save,
          icon: _saving
              ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.save_outlined, size: 16),
          label: const Text('保存档案'),
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.darkGreen,
            foregroundColor: AppColors.darkBg,
            padding: const EdgeInsets.symmetric(vertical: 12),
            textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
          ),
        ),
      ],
    );
  }

  Widget _section(String title, List<Widget> children) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey4)),
        const SizedBox(height: 8),
        ...children,
      ],
    );
  }

  Widget _kvRowWidget(_KvRow row, VoidCallback onRemove) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(children: [
        Expanded(
          flex: 2,
          child: TextField(
            controller: row.key,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
            decoration: _inputDecoration('key'),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          flex: 3,
          child: TextField(
            controller: row.value,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
            decoration: _inputDecoration('value'),
          ),
        ),
        IconButton(
          onPressed: onRemove,
          icon: const Icon(Icons.close, size: 14),
          color: AppColors.darkGrey5,
          tooltip: '删除',
        ),
      ]),
    );
  }

  Widget _addRowButton(String label, VoidCallback onTap) {
    return Align(
      alignment: Alignment.centerLeft,
      child: TextButton.icon(
        onPressed: onTap,
        icon: const Icon(Icons.add, size: 14),
        label: Text(label, style: const TextStyle(fontSize: 12, color: AppColors.darkGreen)),
        style: TextButton.styleFrom(foregroundColor: AppColors.darkGreen),
      ),
    );
  }

  InputDecoration _inputDecoration(String hint) {
    return InputDecoration(
      hintText: hint,
      hintStyle: const TextStyle(fontSize: 12, color: AppColors.darkGrey6),
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppColors.darkBorder),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(8),
        borderSide: const BorderSide(color: AppColors.darkBorder),
      ),
    );
  }
}

class _KvRow {
  final TextEditingController key;
  final TextEditingController value;
  _KvRow(String k, String v)
      : key = TextEditingController(text: k),
        value = TextEditingController(text: v);
}
