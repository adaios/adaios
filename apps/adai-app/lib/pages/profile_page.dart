import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../services/models/identity_models.dart';

/// 身份页 — 展示和编辑个人档案。
class ProfilePage extends StatefulWidget {
  final ApiService api;

  const ProfilePage({super.key, required this.api});

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  IdentityResponse? _profile;
  bool _loading = true;
  String? _error;

  // 编辑模式状态
  bool _editing = false;
  late TextEditingController _nameCtrl;
  late TextEditingController _styleCtrl;
  late TextEditingController _focusCtrl;
  late TextEditingController _tagCtrl;
  bool _ruleConfirmation = true;
  bool _ruleAuto = true;
  List<String> _editTags = [];
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController();
    _styleCtrl = TextEditingController();
    _focusCtrl = TextEditingController();
    _tagCtrl = TextEditingController();
    _loadIdentity();
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _styleCtrl.dispose();
    _focusCtrl.dispose();
    _tagCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadIdentity() async {
    setState(() => _loading = true);
    try {
      final profile = await widget.api.getIdentity();
      if (!mounted) return;
      setState(() {
        _profile = profile;
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '无法加载个人档案';
      });
    }
  }

  void _enterEdit() {
    if (_profile == null) return;
    _nameCtrl.text = _profile!.name;
    _styleCtrl.text = _profile!.preferences['style'] ?? '';
    _focusCtrl.text = _profile!.preferences['focus'] ?? '';
    _ruleConfirmation = _profile!.rules['confirmation']?.isNotEmpty ?? true;
    _ruleAuto = _profile!.rules['auto']?.isNotEmpty ?? true;
    _editTags = List.from(_profile!.tags);
    setState(() => _editing = true);
  }

  void _cancelEdit() {
    setState(() => _editing = false);
  }

  Future<void> _saveEdit() async {
    if (_nameCtrl.text.trim().isEmpty) return;
    setState(() => _saving = true);

    final request = IdentityRequest(
      name: _nameCtrl.text.trim(),
      preferences: {
        'language': _profile?.preferences['language'] ?? '中文',
        'style': _styleCtrl.text.trim().isNotEmpty ? _styleCtrl.text.trim() : '简洁、直接',
        'focus': _focusCtrl.text.trim(),
      },
      rules: {
        'confirmation': _ruleConfirmation ? '交易类操作需确认' : '',
        'auto': _ruleAuto ? '日常记录可自动处理' : '',
      },
      tags: _editTags,
    );

    try {
      final updated = await widget.api.updateIdentity(request);
      if (!mounted) return;
      setState(() {
        _profile = updated;
        _editing = false;
        _saving = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      _showError('保存失败，请重试');
    }
  }

  void _addTag() {
    final tag = _tagCtrl.text.trim();
    if (tag.isNotEmpty && !_editTags.contains(tag)) {
      setState(() => _editTags.add(tag));
      _tagCtrl.clear();
    }
  }

  void _removeTag(String tag) {
    setState(() => _editTags.remove(tag));
  }

  void _showError(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      margin: const EdgeInsets.fromLTRB(20, 0, 20, 12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      duration: const Duration(seconds: 3),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? _buildError()
                : _editing
                    ? _buildEditForm()
                    : _buildDisplay(),
      ),
    );
  }

  Widget _buildError() {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('⚠️ 无法加载个人档案',
              style: TextStyle(fontSize: 15, color: AppColors.darkGrey5)),
          const SizedBox(height: 12),
          GestureDetector(
            onTap: _loadIdentity,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              decoration: BoxDecoration(
                color: AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text('重试',
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
            ),
          ),
        ],
      ),
    );
  }

  // ── 展示模式 ──

  Widget _buildDisplay() {
    final p = _profile!;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 80),
      children: [
        Row(
          children: [
            GestureDetector(
              onTap: () => Navigator.pop(context),
              child: Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Icon(Icons.arrow_back, size: 20, color: AppColors.darkGrey4),
              ),
            ),
            Text('个人档案',
                style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                    color: AppColors.darkGrey1,
                    letterSpacing: -0.3)),
          ],
        ),
        const SizedBox(height: 20),

        // 基本信息
        _sectionCard([
          _infoTile('称呼', p.name),
          _infoTile('语言', p.preferences['language'] ?? '中文'),
          _infoTile('沟通风格', p.preferences['style'] ?? '—'),
          _infoTile('专注领域', p.preferences['focus'] ?? '—'),
        ]),
        const SizedBox(height: 12),

        // AI 协作规则
        _sectionCard([
          _ruleTile('交易类操作需确认', p.rules['confirmation']?.isNotEmpty ?? false),
          _ruleTile('日常记录可自动处理', p.rules['auto']?.isNotEmpty ?? false),
        ]),
        const SizedBox(height: 12),

        // 关注标签
        _sectionCard([
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Text('关注标签',
                style: TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
          ),
          if (p.tags.isNotEmpty)
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: p.tags.map((t) => _chip(t)).toList(),
            )
          else
            Text('暂无标签',
                style: TextStyle(fontSize: 13, color: AppColors.darkGrey6)),
        ]),
        const SizedBox(height: 28),

        // 编辑按钮
        Center(
          child: GestureDetector(
            onTap: _enterEdit,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 10),
              decoration: BoxDecoration(
                color: AppColors.darkGreen.withAlpha(30),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: AppColors.darkGreen.withAlpha(80)),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.edit_outlined, size: 14, color: AppColors.darkGreen),
                  const SizedBox(width: 6),
                  Text('编辑个人档案',
                      style: TextStyle(
                          fontSize: 13, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _sectionCard(List<Widget> children) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.darkSurface.withAlpha(200),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.darkBorder.withAlpha(100)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: children,
      ),
    );
  }

  Widget _infoTile(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 72,
            child: Text(label,
                style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
          ),
          Expanded(
            child: Text(value,
                style: TextStyle(
                    fontSize: 14, color: AppColors.darkGrey1, height: 1.4)),
          ),
        ],
      ),
    );
  }

  Widget _ruleTile(String label, bool enabled) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        children: [
          Icon(
            enabled ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded,
            size: 16,
            color: enabled ? AppColors.darkGreen : AppColors.darkGrey6,
          ),
          const SizedBox(width: 8),
          Text(label,
              style: TextStyle(fontSize: 14, color: AppColors.darkGrey1)),
        ],
      ),
    );
  }

  Widget _chip(String label) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withAlpha(76)),
      ),
      child: Text(label,
          style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
    );
  }

  // ── 编辑模式 ──

  Widget _buildEditForm() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 80),
      children: [
        Row(
          children: [
            GestureDetector(
              onTap: () => Navigator.pop(context),
              child: Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Icon(Icons.arrow_back, size: 20, color: AppColors.darkGrey4),
              ),
            ),
            Text('编辑个人档案',
                style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                    color: AppColors.darkGrey1,
                    letterSpacing: -0.3)),
          ],
        ),
        const SizedBox(height: 24),

        _editField('称呼 *', _nameCtrl, hint: '你的称呼'),
        const SizedBox(height: 16),

        _editField('沟通风格', _styleCtrl, hint: '简洁、直接'),
        const SizedBox(height: 16),

        _editField('专注领域', _focusCtrl, hint: '半导体、国产替代、成长股投资', maxLines: 2),
        const SizedBox(height: 20),

        // 协作规则
        Text('AI 协作规则',
            style: TextStyle(
                fontSize: 14, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
        const SizedBox(height: 8),
        _editSwitch('交易类操作需确认', _ruleConfirmation, (v) {
          setState(() => _ruleConfirmation = v);
        }),
        _editSwitch('日常记录可自动处理', _ruleAuto, (v) {
          setState(() => _ruleAuto = v);
        }),
        const SizedBox(height: 20),

        // 标签
        Text('关注标签',
            style: TextStyle(
                fontSize: 14, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
        const SizedBox(height: 8),
        if (_editTags.isNotEmpty)
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: _editTags
                .map((t) => _editChip(t, () => _removeTag(t)))
                .toList(),
          ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: Container(
                height: 36,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: TextField(
                  controller: _tagCtrl,
                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey1),
                  decoration: InputDecoration(
                    hintText: '添加标签',
                    hintStyle: TextStyle(fontSize: 13, color: AppColors.darkGrey6),
                    border: InputBorder.none,
                    contentPadding: EdgeInsets.zero,
                    isDense: true,
                  ),
                  onSubmitted: (_) => _addTag(),
                ),
              ),
            ),
            const SizedBox(width: 8),
            GestureDetector(
              onTap: _addTag,
              child: Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(Icons.add, size: 18, color: AppColors.darkGrey4),
              ),
            ),
          ],
        ),
        const SizedBox(height: 32),

        // 保存/取消
        Row(
          children: [
            Expanded(
              child: GestureDetector(
                onTap: _saving ? null : _cancelEdit,
                child: Container(
                  height: 44,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: AppColors.darkSurface2,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text('取消',
                      style:
                          TextStyle(fontSize: 14, color: AppColors.darkGrey5)),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: GestureDetector(
                onTap: (_nameCtrl.text.trim().isNotEmpty && !_saving)
                    ? _saveEdit
                    : null,
                child: Container(
                  height: 44,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: _nameCtrl.text.trim().isNotEmpty
                        ? AppColors.darkGreen
                        : AppColors.darkSurface2,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: _saving
                      ? SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: AppColors.darkBg))
                      : Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.save_outlined,
                                size: 16, color: AppColors.darkBg),
                            const SizedBox(width: 6),
                            Text('保存',
                                style: TextStyle(
                                    fontSize: 14,
                                    fontWeight: FontWeight.w500,
                                    color: AppColors.darkBg)),
                          ],
                        ),
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _editField(String label, TextEditingController ctrl,
      {String? hint, int maxLines = 1}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label,
            style: TextStyle(
                fontSize: 13, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
        const SizedBox(height: 6),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14),
          decoration: BoxDecoration(
            color: AppColors.darkSurface2,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: AppColors.darkBorder.withAlpha(76)),
          ),
          child: TextField(
            controller: ctrl,
            maxLines: maxLines,
            style: TextStyle(fontSize: 15, color: AppColors.darkGrey1),
            decoration: InputDecoration(
              hintText: hint,
              hintStyle: TextStyle(fontSize: 15, color: AppColors.darkGrey6),
              border: InputBorder.none,
              contentPadding:
                  EdgeInsets.symmetric(vertical: maxLines > 1 ? 12 : 10),
              isDense: true,
            ),
            onChanged: (_) => setState(() {}),
          ),
        ),
      ],
    );
  }

  Widget _editSwitch(String label, bool value, ValueChanged<bool> onChanged) {
    return Container(
      margin: const EdgeInsets.only(bottom: 4),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2.withAlpha(128),
        borderRadius: BorderRadius.circular(10),
      ),
      child: SwitchListTile(
        title: Text(label,
            style: TextStyle(fontSize: 14, color: AppColors.darkGrey1)),
        value: value,
        onChanged: onChanged,
        activeTrackColor: AppColors.darkGreen.withAlpha(128),
        activeThumbColor: AppColors.darkGreen,
        contentPadding: const EdgeInsets.symmetric(horizontal: 12),
        dense: true,
      ),
    );
  }

  Widget _editChip(String label, VoidCallback onRemove) {
    return Container(
      padding: const EdgeInsets.only(left: 10, right: 4, top: 4, bottom: 4),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withAlpha(76)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label,
              style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
          const SizedBox(width: 4),
          GestureDetector(
            onTap: onRemove,
            child: Icon(Icons.close, size: 14, color: AppColors.darkGrey6),
          ),
        ],
      ),
    );
  }
}
