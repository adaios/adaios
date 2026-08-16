import 'package:flutter/material.dart';
import '../../models/system_models.dart';
import '../../services/system_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/snack.dart';

/// 维护操作页签 — 记忆重建 / 重补 / 清理（真实后端 /admin/memory/rebuild、/admin/records/retry、/admin/cards/cleanup）。
class MaintenanceTab extends StatefulWidget {
  const MaintenanceTab({super.key, required this.store});

  final SystemStore store;

  @override
  State<MaintenanceTab> createState() => _MaintenanceTabState();
}

class _MaintenanceTabState extends State<MaintenanceTab> {
  late final SystemStore _store = widget.store;

  /// 正在执行的操作 id；null = 空闲。
  String? _busy;

  Future<void> _run(
      String id, Future<MaintenanceResult> Function() op) async {
    setState(() => _busy = id);
    final result = await op();
    if (!mounted) return;
    setState(() => _busy = null);
    showAppSnack(
      context,
      result.message,
      result.success ? AppColors.darkGreen : AppColors.darkOrange,
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        const Text(
          '维护操作会触发后端异步任务，结果实时返回。',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey6),
        ),
        const SizedBox(height: 12),
        _buildAction(
          id: 'rebuild',
          icon: Icons.memory,
          title: '记忆重建',
          description: '合并同主题记忆、重新生成摘要（POST /admin/memory/rebuild）',
          color: AppColors.darkPurple,
          onTap: () => _run('rebuild', _store.rebuildMemory),
        ),
        const SizedBox(height: 10),
        _buildAction(
          id: 'refill',
          icon: Icons.autorenew,
          title: '记忆重补',
          description: 'AI 失败降级后，重新补齐缺失记忆条目（POST /admin/records/retry）',
          color: AppColors.darkBlue,
          onTap: () => _run('refill', _store.refillMemory),
        ),
        const SizedBox(height: 10),
        _buildAction(
          id: 'clean',
          icon: Icons.cleaning_services_outlined,
          title: '数据清理',
          description: '清理重复记录与失效索引（POST /admin/cards/cleanup）',
          color: AppColors.darkOrange,
          onTap: () => _run('clean', _store.cleanData),
        ),
      ],
    );
  }

  Widget _buildAction({
    required String id,
    required IconData icon,
    required String title,
    required String description,
    required Color color,
    required VoidCallback onTap,
  }) {
    final busy = _busy == id;
    return AppCard(
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(icon, size: 18, color: color),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: AppColors.darkGrey1)),
                const SizedBox(height: 3),
                Text(description,
                    style: const TextStyle(
                        fontSize: 12, color: AppColors.darkGrey4)),
              ],
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 96,
            height: 34,
            child: busy
                ? const Center(
                    child: SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: AppColors.darkGrey5,
                      ),
                    ),
                  )
                : ElevatedButton(
                    onPressed: onTap,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: color.withValues(alpha: 0.2),
                      foregroundColor: color,
                      padding: EdgeInsets.zero,
                      shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(8)),
                    ),
                    child: const Text('执行',
                        style:
                            TextStyle(fontSize: 12, fontWeight: FontWeight.w500)),
                  ),
          ),
        ],
      ),
    );
  }
}
