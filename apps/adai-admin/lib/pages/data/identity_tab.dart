import 'package:flutter/material.dart';
import '../../models/data_models.dart';
import '../../services/data_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';

/// 档案页签 — 治理视角查看个人档案（name / preferences / rules / tags，真实后端 /identity）。
/// 只读：档案编辑归用户端 app/web（P-role-01），admin 不提供编辑 UI。
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
