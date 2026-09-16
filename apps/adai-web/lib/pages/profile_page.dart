import 'package:flutter/material.dart';
import 'dart:convert';
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

  /// 「阿呆对你的了解」（2026-09-16「第一次见面」批）：拉失败静默降级，不影响档案页主体。
  MemoryInsightsResponse? _insights;

  /// 正在确认的观察内容（按钮 busy 态 + 防连点）。
  String? _confirmingInsight;

  /// 头像预设 id（2026-09-16「第一次见面」批；用户拍板「只做预设，不做上传」）。
  /// 存在 identity.preferences['avatar']，与既有档案同一条写路径，后端零改动。
  static const String _avatarKey = 'avatar';
  String? _avatar;

  /// 预设头像：id → 字形。`none` = 回落到名字首字。
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
        _avatar = identity.preferences[_avatarKey];
        _prefRows
          ..clear()
          // avatar 有自己的选择器，不进「偏好」键值列表（否则会多出一行 avatar|sprout）
          ..addAll(identity.preferences.entries
              .where((e) => e.key != _avatarKey)
              .map((e) => _KvRow(e.key, e.value)));
        _ruleRows
          ..clear()
          ..addAll(identity.rules.entries.map((e) => _KvRow(e.key, e.value)));
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
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
  /// 这就是「观察 → 确认 → 记住」的闭环：AI 观察到的东西只有被用户点头，
  /// 才从「记忆里的猜测」升格成「档案里的共识」。
  Future<void> _confirmInsight(MemoryInsight ins) async {
    final identity = _identity;
    if (identity == null || _confirmingInsight != null) return;
    setState(() => _confirmingInsight = ins.content);
    try {
      final prefs = Map<String, String>.from(identity.preferences)
        ..[ins.content] = '已确认';
      final updated = await widget.api.updateIdentity(IdentityRequest(
        name: identity.name,
        preferences: prefs,
        rules: identity.rules,
        tags: identity.tags,
      ));
      if (!mounted) return;
      setState(() {
        _identity = updated;
        // 右侧编辑区的偏好行必须同步，否则下次「保存档案」会用旧快照覆盖回去
        _prefRows
          ..clear()
          ..addAll(updated.preferences.entries
              .where((e) => e.key != _avatarKey)
              .map((e) => _KvRow(e.key, e.value)));
        _confirmingInsight = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _confirmingInsight = null);
      _showError('没能记下: ${_extractApiError(e)}');
    }
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      final request = IdentityRequest(
        name: _nameCtrl.text.trim(),
        preferences: {
          for (final r in _prefRows)
            if (r.key.text.trim().isNotEmpty) r.key.text.trim(): r.value.text.trim(),
          // 头像预设走同一条写路径（'none' = 不写 → 全量覆盖顺带清掉旧选择）
          if (_avatar != null && _avatar != 'none') _avatarKey: _avatar!,
        },
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
      if (mounted) _showError('保存失败: ${_extractApiError(e)}');
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

  String _extractApiError(dynamic e) {
    // 2026-08-17 走查：后端错误体 {"error":"人话"} 优先透出（与 feed/trading/task 页同口径）
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
                      SizedBox(
                        width: 300,
                        child: SingleChildScrollView(
                          child: Column(children: [
                            _buildIdentityCard(),
                            _buildInsightsCard(),
                          ]),
                        ),
                      ),
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
                // 2026-09-16「第一次见面」批：① 支持预设头像；② 空名 fallback 由「阿呆」
                //（AI 自己的名）改「你」——这块是「你的档案」，用 AI 的名当占位会串线
                _avatarGlyph(i),
                style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w700, color: AppColors.darkGreen),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Text(i.name.isEmpty ? '还没告诉我怎么称呼' : i.name,
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

  /// 头像字形：选了预设就用它，否则回落到名字首字（空名 → 「你」）。
  String _avatarGlyph(IdentityResponse i) {
    final key = i.preferences[_avatarKey];
    if (key != null && key != 'none' && _avatarPresets.containsKey(key)) {
      return _avatarPresets[key]!;
    }
    return i.name.isEmpty ? '你' : i.name.characters.first;
  }

  /// 预设头像可选一格（用户拍板：只做预设，不做上传）。
  Widget _avatarChip(String key, String glyph) {
    final selected = (_avatar ?? 'none') == key;
    return GestureDetector(
      key: ValueKey('avatar-$key'),
      onTap: () => setState(() => _avatar = key),
      child: Tooltip(
        message: key == 'none' ? '用名字首字' : '用这个头像',
        child: Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            color: selected
                ? AppColors.darkGreen.withValues(alpha: 0.18)
                : AppColors.darkSurface2,
            shape: BoxShape.circle,
            border: Border.all(
                color: selected ? AppColors.darkGreen : AppColors.darkBorder),
          ),
          child: Center(
            child: Text(glyph, style: const TextStyle(fontSize: 18)),
          ),
        ),
      ),
    );
  }

  /// 「阿呆对你的了解」（2026-09-16「第一次见面」批）。
  ///
  /// 这些是 AI 从日常对话里自动沉淀的长期观察（memory 的 patterns / preferences），
  /// 此前**零出口**（REVIEW P2-认知3）：用户打开「档案」只看得到自己手填的表单，
  /// 于是觉得「它根本没有更懂我」——其实数据一直在长，只是没人把它端出来。
  /// 点「✓ 对」= 写回 identity.preferences，从此进 prompt，形成「观察 → 确认 → 记住」闭环。
  Widget _buildInsightsCard() {
    final data = _insights;
    if (data == null) return const SizedBox.shrink(); // 拉不到就不占位（旧后端/网络抖动）
    // 两类各取 3 条：合并按置信度排序时「行为模式」往往占满前几名，
    // 偏好一条都露不出来——而用户对「它还知道我什么喜好」同样在意
    final shown = [
      ...data.insights.where((i) => i.isPattern).take(3),
      ...data.insights.where((i) => !i.isPattern).take(3),
    ];
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.psychology_outlined, size: 15, color: AppColors.darkGreen),
            const SizedBox(width: 6),
            const Text('阿呆对你的了解',
                style: TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
          ]),
          const SizedBox(height: 6),
          Text(
            data.total == 0
                ? '我还不认识你。多聊几句，这里会长出我对你的了解。'
                : '已经留意到 ${data.total} 件事'
                    '${data.observedSince != null ? ' · 从 ${data.observedSince} 开始' : ''}',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5, height: 1.5),
          ),
          if (shown.isNotEmpty) ...[
            const SizedBox(height: 12),
            const Divider(color: AppColors.darkBorder, height: 1),
            const SizedBox(height: 12),
            for (final ins in shown) _insightRow(ins),
          ],
        ],
      ),
    );
  }

  Widget _insightRow(MemoryInsight ins) {
    final confirmed = _identity?.preferences.containsKey(ins.content) ?? false;
    final busy = _confirmingInsight == ins.content;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(ins.content,
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2, height: 1.5)),
          const SizedBox(height: 4),
          Row(children: [
            Text(ins.isPattern ? '行为模式' : '偏好',
                style: const TextStyle(fontSize: 10, color: AppColors.darkGrey6)),
            const SizedBox(width: 6),
            Text('${(ins.confidence * 100).round()}%',
                style: const TextStyle(fontSize: 10, color: AppColors.darkGrey6)),
            const Spacer(),
            if (confirmed)
              const Text('✓ 已记进档案',
                  style: TextStyle(fontSize: 10, color: AppColors.darkGreen))
            else
              GestureDetector(
                key: ValueKey('confirm-insight-${ins.content}'),
                onTap: busy ? null : () => _confirmInsight(ins),
                child: Text(busy ? '记下中…' : '✓ 对',
                    style: TextStyle(
                        fontSize: 11,
                        color: busy ? AppColors.darkGrey6 : AppColors.darkGreen)),
              ),
          ]),
        ],
      ),
    );
  }

  Widget _buildEditArea() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
      children: [
        _section('头像', [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final e in _avatarPresets.entries) _avatarChip(e.key, e.value),
            ],
          ),
        ]),
        const SizedBox(height: 16),
        _section('阿呆怎么称呼你', [
          TextField(
            controller: _nameCtrl,
            style: const TextStyle(fontSize: 14, color: AppColors.darkGrey1),
            decoration: _inputDecoration('比如：小明（改完下一句话就生效）'),
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
            decoration: _inputDecoration('键'),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          flex: 3,
          child: TextField(
            controller: row.value,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
            decoration: _inputDecoration('值'),
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
