import 'package:flutter/material.dart';
import '../../models/knowledge_models.dart';
import '../../services/knowledge_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/badge.dart';

/// 术语/规则页签 — 从 os 资产读取（trading-os 规则从 11-context/rules.md 解析，
/// 术语用内置兜底；失败时展示兜底列表）。
class TermsTab extends StatefulWidget {
  const TermsTab({super.key, required this.store});

  final KnowledgeStore store;

  @override
  State<TermsTab> createState() => _TermsTabState();
}

class _TermsTabState extends State<TermsTab> {
  late final KnowledgeStore _store = widget.store;

  List<TermRule>? _terms;
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
      final terms = await _store.loadTerms();
      if (!mounted) return;
      setState(() {
        _terms = terms;
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
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.cloud_off_outlined,
                  size: 28, color: AppColors.darkOrange),
              const SizedBox(height: 10),
              Text('加载术语失败：$_error',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      fontSize: 12, color: AppColors.darkGrey4)),
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
    final all = _terms ?? const <TermRule>[];
    final terms = all.where((t) => t.category == '术语').toList();
    final rules = all.where((t) => t.category == '规则').toList();

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        _sectionHeader(Icons.menu_book_outlined, '术语'),
        const SizedBox(height: 8),
        AppCard(
          child: Column(
            children: [
              if (terms.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 16),
                  child: Text('暂无术语',
                      style: TextStyle(
                          fontSize: 12, color: AppColors.darkGrey5)),
                )
              else
                for (final t in terms) _buildRow(t, AppColors.darkBlue),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _sectionHeader(Icons.rule, '规则'),
        const SizedBox(height: 8),
        AppCard(
          child: Column(
            children: [
              if (rules.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 16),
                  child: Text('暂无规则',
                      style: TextStyle(
                          fontSize: 12, color: AppColors.darkGrey5)),
                )
              else
                for (final r in rules) _buildRow(r, AppColors.darkGreen),
            ],
          ),
        ),
      ],
    );
  }

  Widget _sectionHeader(IconData icon, String title) {
    return Row(
      children: [
        Icon(icon, size: 16, color: AppColors.darkGrey4),
        const SizedBox(width: 6),
        Text(title,
            style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: AppColors.darkGrey1)),
      ],
    );
  }

  Widget _buildRow(TermRule rule, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 9),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 64,
            child: AppBadge(label: rule.name, color: color),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(rule.definition,
                style: const TextStyle(
                    fontSize: 12, height: 1.4, color: AppColors.darkGrey2)),
          ),
          const SizedBox(width: 8),
          Text(rule.source,
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey6)),
        ],
      ),
    );
  }
}
