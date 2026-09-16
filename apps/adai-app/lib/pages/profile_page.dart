import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../services/models/identity_models.dart';
import 'login_devices_page.dart';

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

  /// 「阿呆对你的了解」（2026-09-16「第一次见面」批）：拉失败静默降级，不影响档案页主体。
  MemoryInsightsResponse? _insights;

  /// 正在确认的观察内容（按钮 busy 态 + 防连点）。
  String? _confirmingInsight;

  /// 头像预设 id（2026-09-16「第一次见面」批；用户拍板「只做预设，不做上传」）。
  /// 存在 identity.preferences['avatar']，与既有档案同一条写路径，后端零改动。
  static const String _avatarKey = 'avatar';
  static const Map<String, String> _avatarPresets = {
    'none': '你',
    'sprout': '🌱',
    'moon': '🌙',
    'coffee': '☕',
    'music': '🎧',
    'book': '📚',
    'compass': '🧭',
    'whale': '🐳',
    'plant': '🪴',
  };
  String? _avatar;

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
        _avatar = profile.preferences[_avatarKey];
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '无法加载个人档案';
      });
    }
    _loadInsights(); // 不阻塞档案主体渲染（了解区块晚到就地补上）
  }

  /// 拉「阿呆对你的了解」。失败静默：旧后端没有该端点 / 网络抖动都不该让档案页报错。
  Future<void> _loadInsights() async {
    try {
      final insights = await widget.api.getMemoryInsights();
      if (!mounted) return;
      setState(() => _insights = insights);
    } catch (_) {
      // 静默降级：不显示该区块，档案页其余功能一字不动
    }
  }

  /// 确认一条观察 → 写回 identity.preferences（从此随档案进 prompt）。
  ///
  /// 「观察 → 确认 → 记住」闭环：AI 观察到的东西只有被用户点头，
  /// 才从「记忆里的猜测」升格成「档案里的共识」。
  Future<void> _confirmInsight(MemoryInsight ins) async {
    final profile = _profile;
    if (profile == null || _confirmingInsight != null) return;
    setState(() => _confirmingInsight = ins.content);
    try {
      final prefs = Map<String, String>.from(profile.preferences)
        ..[ins.content] = '已确认';
      final updated = await widget.api.updateIdentity(IdentityRequest(
        name: profile.name,
        preferences: prefs,
        rules: profile.rules,
        tags: profile.tags,
      ));
      if (!mounted) return;
      setState(() {
        _profile = updated;
        _confirmingInsight = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _confirmingInsight = null);
      _showError('没能记下，请重试');
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
    // 2026-09-16「第一次见面」批：不再拦截空昵称——新用户可能就是想先不填名字，
    // 后端 PUT /identity 已放宽 name 非空（此前两边都拦，零画像用户保存不了任何东西）
    setState(() => _saving = true);

    final request = IdentityRequest(
      name: _nameCtrl.text.trim(),
      preferences: {
        'language': _profile?.preferences['language'] ?? '中文',
        'style': _styleCtrl.text.trim().isNotEmpty ? _styleCtrl.text.trim() : '简洁、直接',
        'focus': _focusCtrl.text.trim(),
        // 头像预设走同一条写路径（'none' = 不写 → 全量覆盖顺带清掉旧选择）
        if (_avatar != null && _avatar != 'none') _avatarKey: _avatar!,
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

        // 头像（2026-09-16「第一次见面」批：预设，不做上传）
        Center(
          child: Container(
            width: 64,
            height: 64,
            decoration: BoxDecoration(
              color: AppColors.darkGreen.withAlpha(38),
              shape: BoxShape.circle,
              border: Border.all(color: AppColors.darkGreen.withAlpha(80)),
            ),
            child: Center(
              child: Text(_avatarGlyph(p),
                  style: const TextStyle(fontSize: 26, color: AppColors.darkGreen)),
            ),
          ),
        ),
        const SizedBox(height: 20),

        // 基本信息
        _sectionCard([
          // 2026-09-16「第一次见面」批：label 由「称呼」写清楚成「阿呆怎么称呼你」——
          // 这是 AI 对你的称呼（档案页不是账号页），空值时如实说「还没告诉我」
          _infoTile('阿呆怎么称呼你', p.name.isEmpty ? '还没告诉我' : p.name),
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
                style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        ]),
        const SizedBox(height: 12),

        // 阿呆对你的了解（2026-09-16「第一次见面」批）
        if (_insights != null) _buildInsightsCard(),
        if (_insights != null) const SizedBox(height: 12),

        // 登录设备（RFC 20260914 L2）：看得见「哪些设备登录着」，并能单独撤销一台
        _sectionCard([
          InkWell(
            onTap: () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => LoginDevicesPage(api: widget.api))),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  const Icon(Icons.devices_outlined, size: 16, color: AppColors.darkGrey4),
                  const SizedBox(width: 10),
                  const Expanded(
                    child: Text('登录设备',
                        style: TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
                  ),
                  const Text('查看 / 撤销',
                      style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
                  const Icon(Icons.chevron_right, size: 16, color: AppColors.darkGrey5),
                ],
              ),
            ),
          ),
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

  /// 头像字形：选了预设就用它，否则回落到名字首字（空名 → 「你」）。
  String _avatarGlyph(IdentityResponse p) {
    final key = p.preferences[_avatarKey];
    if (key != null && key != 'none' && _avatarPresets.containsKey(key)) {
      return _avatarPresets[key]!;
    }
    return p.name.isEmpty ? '你' : p.name.characters.first;
  }

  /// 预设头像可选一格（用户拍板：只做预设，不做上传）。
  Widget _avatarChip(String key, String glyph) {
    final selected = (_avatar ?? 'none') == key;
    return GestureDetector(
      key: ValueKey('avatar-$key'),
      onTap: () => setState(() => _avatar = key),
      child: Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: selected
              ? AppColors.darkGreen.withAlpha(46)
              : AppColors.darkSurface2,
          shape: BoxShape.circle,
          border: Border.all(
              color: selected
                  ? AppColors.darkGreen
                  : AppColors.darkBorder.withAlpha(150)),
        ),
        child: Center(child: Text(glyph, style: const TextStyle(fontSize: 18))),
      ),
    );
  }

  /// 「阿呆对你的了解」（2026-09-16「第一次见面」批）。
  ///
  /// 这些是 AI 从日常对话里自动沉淀的长期观察（memory 的 patterns / preferences），
  /// 此前**零出口**（REVIEW P2-认知3）：用户打开「档案」只看得到自己手填的表单，
  /// 于是觉得「它根本没有更懂我」——其实数据一直在长，只是没人把它端出来。
  /// 点「✓ 对」= 写回 identity.preferences，从此进 prompt。
  Widget _buildInsightsCard() {
    final data = _insights!;
    // 两类各取 3 条：合并按置信度排序时「行为模式」往往占满前几名，
    // 偏好一条都露不出来——而用户对「它还知道我什么喜好」同样在意
    final shown = [
      ...data.insights.where((i) => i.isPattern).take(3),
      ...data.insights.where((i) => !i.isPattern).take(3),
    ];
    return _sectionCard([
      const Row(children: [
        Icon(Icons.psychology_outlined, size: 15, color: AppColors.darkGreen),
        SizedBox(width: 6),
        Text('阿呆对你的了解',
            style: TextStyle(
                fontSize: 13, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
      ]),
      const SizedBox(height: 8),
      Text(
        data.total == 0
            ? '我还不认识你。多聊几句，这里会长出我对你的了解。'
            : '已经留意到 ${data.total} 件事'
                '${data.observedSince != null ? ' · 从 ${data.observedSince} 开始' : ''}',
        style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5, height: 1.5),
      ),
      if (shown.isNotEmpty) const SizedBox(height: 12),
      for (final ins in shown) _insightRow(ins),
    ]);
  }

  Widget _insightRow(MemoryInsight ins) {
    final confirmed = _profile?.preferences.containsKey(ins.content) ?? false;
    final busy = _confirmingInsight == ins.content;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(ins.content,
              style: const TextStyle(
                  fontSize: 13, color: AppColors.darkGrey1, height: 1.5)),
          const SizedBox(height: 4),
          Row(children: [
            Text(ins.isPattern ? '行为模式' : '偏好',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            const SizedBox(width: 6),
            Text('${(ins.confidence * 100).round()}%',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            const Spacer(),
            if (confirmed)
              const Text('✓ 已记进档案',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGreen))
            else
              GestureDetector(
                key: ValueKey('confirm-insight-${ins.content}'),
                onTap: busy ? null : () => _confirmInsight(ins),
                behavior: HitTestBehavior.opaque,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
                  child: Text(busy ? '记下中…' : '✓ 对',
                      style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                          color: busy ? AppColors.darkGrey5 : AppColors.darkGreen)),
                ),
              ),
          ]),
        ],
      ),
    );
  }

  Widget _sectionCard(List<Widget> children) {
    // 2026-09-16「第一次见面」批顺手修：背景色由 Container 的 decoration 改挂 Material——
    // 卡内「登录设备」是 ListTile，而 ListTile 的背景与水波纹画在**最近的 Material** 上，
    // 原写法会被中间这层带背景色的 DecoratedBox 盖住，Flutter 框架据此直接抛断言
    //（真机大屏把该区块纳入布局时就会打印）。视觉与原来一致，只是水波纹回来了。
    return Material(
      color: AppColors.darkSurface.withAlpha(200),
      borderRadius: BorderRadius.circular(16),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: AppColors.darkBorder.withAlpha(100)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: children,
        ),
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
            width: 100,
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

        // 头像（2026-09-16「第一次见面」批：预设，不做上传）
        Text('头像',
            style: TextStyle(
                fontSize: 14, fontWeight: FontWeight.w500, color: AppColors.darkGrey4)),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final e in _avatarPresets.entries) _avatarChip(e.key, e.value),
          ],
        ),
        const SizedBox(height: 24),

        // 2026-09-16「第一次见面」批：不再标 *（后端已放宽 name 非空——新用户没填昵称也能保存）
        _editField('阿呆怎么称呼你', _nameCtrl, hint: '比如：小明（改完下一句话就生效）'),
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
    // 2026-09-16「第一次见面」批顺手修：同 _sectionCard——背景改挂 Material，
    // 否则 SwitchListTile 的背景/水波纹被中间带背景色的 DecoratedBox 盖住（框架抛断言）
    return Container(
      margin: const EdgeInsets.only(bottom: 4),
      child: Material(
        color: AppColors.darkSurface2.withAlpha(128),
        borderRadius: BorderRadius.circular(10),
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
