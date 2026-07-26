import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import 'project_task_page.dart';

/// ProjectStatusPage — 项目仪表盘 + 任务快捷入口。
class ProjectStatusPage extends StatefulWidget {
  final ApiService api;

  const ProjectStatusPage({super.key, required this.api});

  @override
  State<ProjectStatusPage> createState() => _ProjectStatusPageState();
}

class _ProjectStatusPageState extends State<ProjectStatusPage> {
  ProjectStatusResponse? _status;
  TaskStatsResponse? _taskStats;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait([
        widget.api.getProjectStatus(),
        widget.api.getTaskStats(),
      ]);
      if (!mounted) return;
      setState(() {
        _status = results[0] as ProjectStatusResponse;
        _taskStats = results[1] as TaskStatsResponse;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = e.toString(); _loading = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkBg,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back, color: AppColors.darkGrey4),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text('阿呆系统', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        actions: [
          IconButton(
            icon: Icon(Icons.refresh, size: 18, color: AppColors.darkGrey5),
            onPressed: () { setState(() => _loading = true); _load(); },
          ),
        ],
      ),
      body: _loading ? const Center(child: CircularProgressIndicator())
          : _error != null ? Center(child: Text('加载失败\n$_error', style: TextStyle(color: AppColors.darkGrey5)))
          : ListView(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              children: [
                _sectionTitle('系统概览'),
                _card([
                  _kv('项目', _status!.project),
                  _kv('架构', _status!.architecture),
                ]),
                const SizedBox(height: 16),
                _sectionTitle('Kernel 组件'),
                _componentGrid(_status!.kernelComponents),
                const SizedBox(height: 16),
                _sectionTitle('Domain OS'),
                _domainList(_status!.domainStatus),
                const SizedBox(height: 16),
                _sectionTitle('统计数据'),
                _card([
                  _kv('Git 提交', '${_status!.commitCount}'),
                  _kv('RFC 文档', '${_status!.rfcItems.length}'),
                  _kv('API 端点', '${_status!.apiEndpoints}'),
                ]),
                const SizedBox(height: 16),
                _buildRfcSection(_status!.rfcItems),
                const SizedBox(height: 16),
                _buildTaskSection(),
              ],
            ),
    );
  }

  Widget _sectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10, top: 4),
      child: Text(title, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey5)),
    );
  }

  Widget _card(List<Widget> children) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: children),
    );
  }

  Widget _kv(String key, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: [
          SizedBox(width: 80, child: Text(key, style: TextStyle(fontSize: 13, color: AppColors.darkGrey5))),
          Expanded(child: Text(value, style: TextStyle(fontSize: 13, color: AppColors.darkGrey2))),
        ],
      ),
    );
  }

  Widget _componentGrid(Map<String, String> components) {
    final keys = ['identity', 'record', 'timeline', 'context', 'memory', 'knowledge'];
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Wrap(
        spacing: 12,
        runSpacing: 12,
        children: keys.map((k) {
          final done = components[k] == 'done';
          return Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: (done ? AppColors.darkGreen : AppColors.darkOrange).withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(
                color: (done ? AppColors.darkGreen : AppColors.darkOrange).withValues(alpha: 0.25),
                width: 0.5,
              ),
            ),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              Icon(done ? Icons.check_circle : Icons.radio_button_unchecked,
                  size: 13, color: done ? AppColors.darkGreen : AppColors.darkOrange),
              const SizedBox(width: 5),
              Text(k, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500,
                  color: done ? AppColors.darkGreen : AppColors.darkOrange)),
            ]),
          );
        }).toList(),
      ),
    );
  }

  Widget _domainList(Map<String, String> domains) {
    final entries = domains.entries.toList();
    final labels = {'trading': 'Trading OS', 'life': 'Life OS', 'project': 'Project OS'};
    final statusText = {'complete': '完整', 'skeleton': '骨架', 'none': '未开始'};
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        children: entries.map((e) {
          final isDone = e.value == 'complete';
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 3),
            child: Row(children: [
              SizedBox(width: 90, child: Text(labels[e.key] ?? e.key, style: TextStyle(fontSize: 13, color: AppColors.darkGrey2))),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: (isDone ? AppColors.darkGreen : AppColors.darkOrange).withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(statusText[e.value] ?? e.value,
                    style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500,
                        color: isDone ? AppColors.darkGreen : AppColors.darkOrange)),
              ),
            ]),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildTaskSection() {
    final s = _taskStats;
    if (s == null) return const SizedBox.shrink();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        _sectionTitle('任务'),
        const Spacer(),
        GestureDetector(
          onTap: () {
            Navigator.push(context, MaterialPageRoute(
              builder: (_) => ProjectTaskPage(api: widget.api),
            ));
          },
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: AppColors.darkGreen.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(children: [
              Text('管理', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
              const SizedBox(width: 2),
              Icon(Icons.chevron_right, size: 12, color: AppColors.darkGreen),
            ]),
          ),
        ),
      ]),
      const SizedBox(height: 8),
      Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceAround,
          children: [
            _statItem('待办', s.todo, AppColors.darkOrange),
            _statItem('进行', s.doing, AppColors.darkBlue),
            _statItem('完成', s.done, AppColors.darkGreen),
            _statItem('合计', s.total, AppColors.darkGrey1),
          ],
        ),
      ),
    ]);
  }

  Widget _statItem(String label, int count, Color color) {
    return Column(children: [
      Text('$count', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _buildRfcSection(List<RfcItemResponse> rfcs) {
    if (rfcs.isEmpty) return const SizedBox.shrink();
    final reversed = rfcs.reversed.toList(); // 旧到新
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      _sectionTitle('RFC 状态'),
      const SizedBox(height: 8),
      Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          children: reversed.map((r) {
            final statusColor = _rfcStatusColor(r.status);
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 3),
              child: Row(children: [
                SizedBox(width: 60, child: Text(r.date,
                    style: TextStyle(fontSize: 11, color: AppColors.darkGrey5))),
                Expanded(
                  child: Text(r.title,
                      style: TextStyle(fontSize: 12, color: AppColors.darkGrey2),
                      overflow: TextOverflow.ellipsis),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: statusColor.withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(_rfcStatusLabel(r.status),
                      style: TextStyle(fontSize: 10, fontWeight: FontWeight.w500, color: statusColor)),
                ),
              ]),
            );
          }).toList(),
        ),
      ),
    ]);
  }

  Color _rfcStatusColor(String status) {
    switch (status) {
      case 'implemented': return AppColors.darkGreen;
      case 'approved': return AppColors.darkBlue;
      case 'proposed': return AppColors.darkOrange;
      case 'deprecated': return AppColors.darkGrey5;
      default: return AppColors.darkGrey5;
    }
  }

  String _rfcStatusLabel(String status) {
    switch (status) {
      case 'implemented': return '完成';
      case 'approved': return '已批准';
      case 'proposed': return '提案';
      case 'deprecated': return '废弃';
      default: return status;
    }
  }
}
