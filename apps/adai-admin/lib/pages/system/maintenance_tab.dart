import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../../models/system_models.dart';
import '../../services/system_api_store.dart';
import '../../theme/app_colors.dart';
import '../../widgets/app_card.dart';
import '../../widgets/snack.dart';

/// 维护操作页签 — 记忆重建 / 重补 / 清理 / 行情数据导入（真实后端 /admin/memory/rebuild、
/// /admin/records/retry、/admin/cards/cleanup、/admin/market/tdx-import）。
class MaintenanceTab extends StatefulWidget {
  const MaintenanceTab({
    super.key,
    required this.store,
    this.userId = 'default',
    this.pickZipFile,
  });

  final SystemStore store;

  /// 当前浏览用户（per-user 维护操作的作用对象；行情导入为全局数据、非 per-user）。
  final String userId;

  /// 行情导入选 .zip（可注入；默认 [FilePicker.platform.pickFiles]，测试注入内存 fake）。
  final Future<FilePickerResult?> Function()? pickZipFile;

  @override
  State<MaintenanceTab> createState() => _MaintenanceTabState();
}

class _MaintenanceTabState extends State<MaintenanceTab> {
  late final SystemStore _store = widget.store;

  /// 正在执行的操作 id；null = 空闲。
  String? _busy;

  // ── 行情导入上传进度（MD17 体验增强 2026-09-07）──
  /// 上传进度 0..1；null = 尚未开始 / 平台无逐块进度（UI 转圈兜底）。
  double? _tdxProgress;

  /// 已传 / 总字节（UI 文案用）。
  int _tdxSentBytes = 0;
  int _tdxTotalBytes = 0;

  /// 上传开始时刻（估算剩余时间）。
  DateTime? _tdxStartedAt;

  void _onTdxProgress(int sentBytes, int totalBytes) {
    if (!mounted) return;
    setState(() {
      _tdxSentBytes = sentBytes;
      _tdxTotalBytes = totalBytes;
      _tdxProgress =
          (totalBytes > 0) ? (sentBytes / totalBytes).clamp(0.0, 1.0) : null;
      _tdxStartedAt ??= DateTime.now();
    });
  }

  void _clearTdxProgress() {
    _tdxProgress = null;
    _tdxSentBytes = 0;
    _tdxTotalBytes = 0;
    _tdxStartedAt = null;
  }

  Future<void> _run(
      String id, String title, String description,
      Future<MaintenanceResult> Function() op) async {
    // P2-9（2026-09-06）：重建/重补/清理是昂贵且改变用户数据的操作——执行前确认，
    // 显式展示作用对象（当前浏览用户）与影响，防「对错的人执行」；行情导入走 _pickAndImport
    // （文件选择即确认，另属全局数据）。
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: Text(title,
            style: const TextStyle(color: AppColors.darkOrange, fontSize: 16)),
        content: Text(
          '$description\n\n目标用户：「${widget.userId}」\n\n确认执行？此操作可能改变该用户的记忆/记录数据。',
          style: const TextStyle(
              fontSize: 13, height: 1.5, color: AppColors.darkGrey3),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消',
                style: TextStyle(color: AppColors.darkGrey5)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认执行',
                style: TextStyle(color: AppColors.darkOrange)),
          ),
        ],
      ),
    );
    if (confirm != true || !mounted) return;

    setState(() => _busy = id);
    final result = await op();
    if (!mounted) return;
    setState(() => _busy = null);
    _showResult(result);
  }

  /// P2-10：结果展示——成功/无明细走 snackbar；失败带明细（行情导入 failed[]）弹对话框
  /// 完整复查（原失败清单仅截断前 3 条塞进瞬时 snackbar，无法复查）。
  void _showResult(MaintenanceResult result) {
    if (!result.success && result.failures.isNotEmpty) {
      showDialog<void>(
        context: context,
        builder: (_) => AlertDialog(
          backgroundColor: AppColors.darkSurface,
          title: const Text('导入完成（含失败项）',
              style:
                  TextStyle(color: AppColors.darkOrange, fontSize: 16)),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(result.message,
                    style: const TextStyle(
                        fontSize: 12, color: AppColors.darkGrey3)),
                const SizedBox(height: 10),
                const Text('失败清单：',
                    style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: AppColors.darkGrey4)),
                const SizedBox(height: 4),
                Flexible(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxHeight: 240),
                    child: SingleChildScrollView(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          for (final f in result.failures)
                            Padding(
                              padding: const EdgeInsets.only(bottom: 3),
                              child: Text('· $f',
                                  style: const TextStyle(
                                      fontSize: 12,
                                      height: 1.4,
                                      color: AppColors.darkOrange)),
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('关闭',
                  style: TextStyle(color: AppColors.darkGrey5)),
            ),
          ],
        ),
      );
      return;
    }
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
          '维护作用于当前浏览用户「X」（记忆重建/重补/数据清理）；行情导入为全局行情数据。',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey6),
        ),
        const SizedBox(height: 12),
        _buildAction(
          id: 'rebuild',
          icon: Icons.memory,
          title: '记忆重建',
          description: '合并同主题记忆、重新生成摘要（POST /admin/memory/rebuild）',
          color: AppColors.darkPurple,
          onTap: () => _run('rebuild', '记忆重建',
              '合并同主题记忆、重新生成摘要，作用于用户「${widget.userId}」。',
              _store.rebuildMemory),
        ),
        const SizedBox(height: 10),
        _buildAction(
          id: 'refill',
          icon: Icons.autorenew,
          title: '记忆重补',
          description: 'AI 失败降级后，重新补齐缺失记忆条目（POST /admin/records/retry）',
          color: AppColors.darkBlue,
          onTap: () => _run('refill', '记忆重补',
              '重新补齐该用户 AI 失败期间缺失的记忆条目，作用于用户「${widget.userId}」。',
              _store.refillMemory),
        ),
        const SizedBox(height: 10),
        _buildAction(
          id: 'clean',
          icon: Icons.cleaning_services_outlined,
          title: '数据清理',
          description: '清理重复记录与失效索引（POST /admin/cards/cleanup）',
          color: AppColors.darkOrange,
          onTap: () => _run('clean', '数据清理',
              '清理该用户重复记录与失效索引，作用于用户「${widget.userId}」。',
              _store.cleanData),
        ),
        const SizedBox(height: 10),
        _buildAction(
          id: 'tdx-import',
          icon: Icons.cloud_upload_outlined,
          title: '行情数据导入',
          description: '上传通达信盘后 .zip 数据包，更新本地 K 线（POST /admin/market/tdx-import）',
          color: AppColors.darkYellow,
          onTap: _pickAndImport,
        ),
      ],
    );
  }

  /// 选通达信数据包（.zip）→ 上传导入（MD17）。上传中显示真实进度条；结果 snackbar 摘要。
  Future<void> _pickAndImport() async {
    try {
      final picker = widget.pickZipFile ??
          () => FilePicker.platform.pickFiles(
                type: FileType.custom,
                allowedExtensions: ['zip'],
                withData: true,
              );
      final result = await picker();
      if (result == null || result.files.isEmpty) return;
      final f = result.files.single;
      final bytes = f.bytes;
      if (!mounted) return;
      if (bytes == null || bytes.isEmpty) {
        showAppSnack(context, '读取文件失败（未拿到内容）', AppColors.darkOrange);
        return;
      }
      setState(() {
        _busy = 'tdx-import';
        // 上传前即知总量：进度条分母立即可用（真实 sent 由 onProgress 驱动）。
        _tdxProgress = 0.0;
        _tdxSentBytes = 0;
        _tdxTotalBytes = bytes.length;
        _tdxStartedAt = DateTime.now();
      });
      final r = await _store.importTdxPackage(bytes, f.name,
          onProgress: _onTdxProgress);
      if (!mounted) return;
      setState(() {
        _busy = null;
        _clearTdxProgress();
      });
      _showResult(r);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = null;
        _clearTdxProgress();
      });
      showAppSnack(context, '行情数据导入失败：$e', AppColors.darkOrange);
    }
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
    // 行情导入：上传中显示进度（百分比 + 进度条 + 已传/总量），非 tdx-import 仍转圈。
    final uploading = busy && id == 'tdx-import';
    final percent = (uploading && _tdxProgress != null)
        ? (_tdxProgress! * 100).round()
        : null;
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
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
                height: 40, // P3-20（2026-09-06）：34→40 提升触达（触屏场景）
                child: busy
                    ? (percent != null
                        ? Center(
                            child: Text('$percent%',
                                style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: color)),
                          )
                        : const Center(
                            child: SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: AppColors.darkGrey5,
                              ),
                            ),
                          ))
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
                            style: TextStyle(
                                fontSize: 12, fontWeight: FontWeight.w500)),
                      ),
              ),
            ],
          ),
          if (uploading) ...[
            const SizedBox(height: 10),
            ClipRRect(
              borderRadius: BorderRadius.circular(3),
              child: LinearProgressIndicator(
                value: _tdxProgress ?? 0,
                minHeight: 5,
                backgroundColor: color.withValues(alpha: 0.15),
                valueColor: AlwaysStoppedAnimation(color),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              '已上传 ${_fmtBytes(_tdxSentBytes)} / ${_fmtBytes(_tdxTotalBytes)}'
              '${_remainingHint()}',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5),
            ),
          ],
        ],
      ),
    );
  }

  /// `123.4 MB` / `890 KB` 文件大小文案。
  static String _fmtBytes(int bytes) {
    if (bytes >= 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    return '$bytes B';
  }

  /// 按已传速度粗估剩余时间（上传刚起步 <2% 时不显示，防误导）。
  String _remainingHint() {
    final started = _tdxStartedAt;
    if (started == null || _tdxSentBytes <= 0 || _tdxTotalBytes <= 0) return '';
    final elapsedMs = DateTime.now().difference(started).inMilliseconds;
    if (elapsedMs < 300) return '';
    final remaining =
        (_tdxTotalBytes - _tdxSentBytes) * elapsedMs / _tdxSentBytes;
    final secs = remaining ~/ 1000;
    if (secs <= 0) return '';
    final mm = secs ~/ 60;
    final ss = secs % 60;
    return ' · 约剩 ${mm > 0 ? '$mm分' : ''}$ss秒';
  }
}
