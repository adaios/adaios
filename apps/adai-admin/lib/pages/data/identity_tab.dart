import 'package:flutter/material.dart';
import '../../models/data_models.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';
import '../../widgets/snack.dart';

/// 档案页签 — 查看 / 编辑个人档案（name / preferences / rules / tags，真实后端 /identity）。
class IdentityTab extends StatefulWidget {
  const IdentityTab({super.key, required this.store});

  final DataStore store;

  @override
  State<IdentityTab> createState() => _IdentityTabState();
}

class _IdentityTabState extends State<IdentityTab> {
  late final DataStore _store = widget.store;

  IdentityProfile? _identity;
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
      final identity = await _store.loadIdentity();
      if (!mounted) return;
      setState(() {
        _identity = identity;
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

  Future<void> _edit() async {
    final identity = _identity;
    if (identity == null) return;
    final result = await _showEditDialog(identity);
    if (result == null || !mounted) return;

    try {
      await _store.saveIdentity(result);
      if (!mounted) return;
      setState(() => _identity = result);
      showAppSnack(context, '已保存档案', AppColors.darkGreen);
    } catch (e) {
      if (!mounted) return;
      showAppSnack(context, '保存失败：$e', AppColors.darkOrange);
    }
  }

  Future<IdentityProfile?> _showEditDialog(IdentityProfile current) async {
    final nameCtrl = TextEditingController(text: current.name);
    final prefsCtrl = TextEditingController(
        text: current.preferences.entries
            .map((e) => '${e.key}: ${e.value}')
            .join('\n'));
    final rulesCtrl = TextEditingController(text: current.rules.join('\n'));
    final tagsCtrl = TextEditingController(text: current.tags.join(', '));

    return showDialog<IdentityProfile>(
      context: context,
      builder: (ctx) {
        Future<IdentityProfile?> parseProfile() {
          final prefs = <String, String>{};
          for (final line in prefsCtrl.text.split('\n')) {
            final idx = line.indexOf(':');
            if (idx > 0) {
              prefs[line.substring(0, idx).trim()] =
                  line.substring(idx + 1).trim();
            }
          }
          final rules = rulesCtrl.text
              .split('\n')
              .map((s) => s.trim())
              .where((s) => s.isNotEmpty)
              .toList();
          final tags = tagsCtrl.text
              .split(RegExp(r'[,，]'))
              .map((s) => s.trim())
              .where((s) => s.isNotEmpty)
              .toList();
          return Future.value(IdentityProfile(
            name: nameCtrl.text.trim(),
            preferences: prefs,
            rules: rules,
            tags: tags,
          ));
        }

        return AlertDialog(
          backgroundColor: AppColors.darkSurface,
          title: const Text('编辑档案',
              style: TextStyle(color: AppColors.darkGrey1, fontSize: 16)),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _label('姓名'),
                _field(nameCtrl, hint: '如 adai'),
                const SizedBox(height: 10),
                _label('偏好（每行一条 key: value）'),
                _field(prefsCtrl, maxLines: 3),
                const SizedBox(height: 10),
                _label('规则（每行一条）'),
                _field(rulesCtrl, maxLines: 3),
                const SizedBox(height: 10),
                _label('标签（逗号分隔）'),
                _field(tagsCtrl),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消',
                  style: TextStyle(color: AppColors.darkGrey5)),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx, parseProfile()),
              child: const Text('保存',
                  style: TextStyle(color: AppColors.darkGreen)),
            ),
          ],
        );
      },
    );
  }

  Widget _label(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Text(text,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    );
  }

  Widget _field(TextEditingController ctrl, {String? hint, int maxLines = 1}) {
    return TextField(
      controller: ctrl,
      maxLines: maxLines,
      style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
      decoration: InputDecoration(
        isDense: true,
        hintText: hint,
        hintStyle: const TextStyle(fontSize: 12, color: AppColors.darkGrey6),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        filled: true,
        fillColor: AppColors.darkBg,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(6),
          borderSide: const BorderSide(color: AppColors.darkBorder, width: 0.5),
        ),
      ),
    );
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
    final identity = _identity ?? IdentityProfile(
        name: '', preferences: const {}, rules: const [], tags: const []);

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.person_outline,
                      size: 18, color: AppColors.darkGreen),
                  const SizedBox(width: 8),
                  Text(identity.name,
                      style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                          color: AppColors.darkGrey1)),
                  const Spacer(),
                  OutlinedButton.icon(
                    onPressed: _edit,
                    icon: const Icon(Icons.edit_outlined,
                        size: 14, color: AppColors.darkGreen),
                    label: const Text('编辑',
                        style:
                            TextStyle(fontSize: 12, color: AppColors.darkGreen)),
                    style: OutlinedButton.styleFrom(
                      side: const BorderSide(color: AppColors.darkBorder),
                      padding: const EdgeInsets.symmetric(
                          horizontal: 12, vertical: 6),
                      minimumSize: Size.zero,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Row(
                children: [
                  for (final tag in identity.tags)
                    Padding(
                      padding: const EdgeInsets.only(right: 6),
                      child: AppBadge(
                          label: tag, color: AppColors.darkPurple),
                    ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _sectionCard('偏好', Icons.tune, [
          for (final e in identity.preferences.entries) _kvRow(e.key, e.value),
        ]),
        const SizedBox(height: 10),
        _sectionCard('协作规则', Icons.rule, [
          for (final rule in identity.rules)
            _bulletRow(rule, Icons.chevron_right, AppColors.darkGreen),
        ]),
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
            Text('加载档案失败：$_error',
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

  Widget _sectionCard(String title, IconData icon, List<Widget> rows) {
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 15, color: AppColors.darkGrey4),
              const SizedBox(width: 6),
              Text(title,
                  style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: AppColors.darkGrey1)),
            ],
          ),
          const SizedBox(height: 10),
          ...rows,
        ],
      ),
    );
  }

  Widget _kvRow(String key, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          SizedBox(
            width: 72,
            child: Text(key,
                style:
                    const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
          ),
          Text(value,
              style:
                  const TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
        ],
      ),
    );
  }

  Widget _bulletRow(String text, IconData icon, Color color) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 6),
          Expanded(
            child: Text(text,
                style: const TextStyle(
                    fontSize: 13, height: 1.3, color: AppColors.darkGrey2)),
          ),
        ],
      ),
    );
  }
}
