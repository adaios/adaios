import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../widgets/page_header.dart';

/// 项目桌面形态 — 仪表盘双列卡片 grid + RFC 表格。
class ProjectPage extends StatefulWidget {
  final ApiService api;

  const ProjectPage({super.key, required this.api});

  @override
  State<ProjectPage> createState() => _ProjectPageState();
}

class _ProjectPageState extends State<ProjectPage> {
  ProjectStatusResponse? _status;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final status = await widget.api.getProjectStatus();
      if (!mounted) return;
      setState(() {
        _status = status;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    return Column(children: [
      PageHeader(
        title: '项目',
        subtitle: s == null
            ? null
            : '${s.commitCount} 提交 · ${s.apiEndpoints?.toString() ?? '未知'} 个 API',
        actions: [
          IconButton(
            onPressed: _load,
            icon: const Icon(Icons.refresh, size: 18),
            color: AppColors.darkGrey4,
            tooltip: '刷新',
          ),
        ],
      ),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : s == null
                ? const Center(child: Text('加载失败', style: TextStyle(color: AppColors.darkGrey5)))
                : ListView(
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
                    children: [
                      _buildOverview(s),
                      const SizedBox(height: 16),
                      Row(children: [
                        Expanded(child: _buildComponentCard('Kernel 组件', s.kernelComponents, AppColors.darkBlue)),
                        const SizedBox(width: 12),
                        Expanded(child: _buildComponentCard('Domain OS', s.domainStatus, AppColors.darkGreen)),
                      ]),
                      const SizedBox(height: 16),
                      _buildRfcTable(s.rfcItems),
                    ],
                  ),
      ),
    ]);
  }

  Widget _buildOverview(ProjectStatusResponse s) {
    return Row(children: [
      _overviewCell('提交数', '${s.commitCount}', AppColors.darkBlue),
      const SizedBox(width: 12),
      _overviewCell('RFC 文档', '${s.rfcItems.length}', AppColors.darkPurple),
      const SizedBox(width: 12),
      _overviewCell('API 端点', s.apiEndpoints?.toString() ?? '未知', AppColors.darkGreen),
    ]);
  }

  Widget _overviewCell(String label, String value, Color color) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            const SizedBox(height: 8),
            Text(value, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: color)),
          ],
        ),
      ),
    );
  }

  Widget _buildComponentCard(String title, Map<String, String> items, Color accent) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Container(width: 8, height: 8, decoration: BoxDecoration(color: accent, shape: BoxShape.circle)),
            const SizedBox(width: 6),
            Text(title, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          ]),
          const SizedBox(height: 10),
          if (items.isEmpty)
            const Text('—', style: TextStyle(fontSize: 12, color: AppColors.darkGrey6))
          else
            ...items.entries.map((e) => Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(e.key, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
                  const Spacer(),
                  Flexible(
                    child: Text(e.value,
                        textAlign: TextAlign.right,
                        style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
                  ),
                ],
              ),
            )),
        ],
      ),
    );
  }

  Widget _buildRfcTable(List<RfcItemResponse> rfcItems) {
    if (rfcItems.isEmpty) return const SizedBox.shrink();
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('RFC 文档', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const SizedBox(height: 10),
          ...rfcItems.map((r) => Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Row(children: [
              Text(r.date, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              const SizedBox(width: 12),
              Expanded(
                child: Text(r.title, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: _rfcColor(r.status).withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(5),
                ),
                child: Text(r.status, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: _rfcColor(r.status))),
              ),
            ]),
          )),
        ],
      ),
    );
  }

  Color _rfcColor(String status) {
    if (status.contains('accepted') || status.contains('approved')) return AppColors.darkGreen;
    if (status.contains('draft')) return AppColors.darkOrange;
    return AppColors.darkGrey4;
  }
}
