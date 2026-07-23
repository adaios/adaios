import 'dart:math' show cos, sin;
import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../services/models/tag_models.dart';
import 'profile_page.dart';
import 'memory_page.dart';
import 'timeline_page.dart';
import 'search_page.dart';

/// World B — Launcher。
class LauncherPage extends StatefulWidget {
  final ApiService api;
  final VoidCallback onNavigateBack;
  final void Function(String tag) onTagTap;

  const LauncherPage({
    super.key,
    required this.api,
    required this.onNavigateBack,
    required this.onTagTap,
  });

  @override
  State<LauncherPage> createState() => _LauncherPageState();
}

class _LauncherPageState extends State<LauncherPage>
    with SingleTickerProviderStateMixin {
  String? _myName;
  int _tagTotal = 0;
  int _memoryCount = 0;
  int _timelineCount = 0;
  String _ageStr = '';
  List<TagSummary> _allTags = [];
  bool _loading = true;
  bool _graphView = true;

  late AnimationController _graphAnim;
  late Animation<double> _graphAlpha;

  @override
  void initState() {
    super.initState();
    _graphAnim = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );
    _graphAlpha = CurvedAnimation(
      parent: _graphAnim,
      curve: Curves.easeOut,
    );
    _loadAll();
  }

  @override
  void dispose() {
    _graphAnim.dispose();
    super.dispose();
  }

  Future<void> _loadAll() async {
    try {
      final results = await Future.wait([
        widget.api.getIdentity(),
        widget.api.getTags(),
        widget.api.getTimeline(limit: 999),
        widget.api.getMemory(),
      ]);
      if (!mounted) return;

      final identity = results[0] as dynamic;
      final tagsResp = results[1] as TagsResponse;
      final timeline = results[2] as List;
      final memory = results[3] as List;

      setState(() {
        _myName = identity.name;
        _ageStr = '${identity.preferences['style'] ?? ''} · ${identity.preferences['focus'] ?? ''}';
        _tagTotal = tagsResp.total;
        _allTags = tagsResp.tags;
        _timelineCount = timeline.length;
        _memoryCount = memory.length;
        _loading = false;
      });
      _graphAnim.forward();
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _toggleView() {
    setState(() => _graphView = !_graphView);
    if (!_graphView) {
      _graphAnim.reset();
    } else {
      _graphAnim.forward();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 40),
      children: [
        _buildDragHandle(),
        _buildSearchBar(),
        const SizedBox(height: 28),

        _buildRow('👤', '关于我', '$_myName · $_ageStr', AppColors.darkGreen, () {
          Navigator.push(context, MaterialPageRoute(
            builder: (_) => Scaffold(
              backgroundColor: AppColors.darkBg,
              body: ProfilePage(api: widget.api),
            ),
          ));
        }),
        _divider(),
        _buildRow('🧠', '脑瓜子正在装...', '已存 $_memoryCount 条理解', AppColors.darkGreen, () {
          Navigator.push(context, MaterialPageRoute(
            builder: (_) => Scaffold(
              backgroundColor: AppColors.darkBg,
              body: MemoryPage(api: widget.api),
            ),
          ));
        }),
        _divider(),
        _buildRow('📅', '时间都去哪了', '已记 $_timelineCount 条记录', AppColors.darkGreen, () {
          Navigator.push(context, MaterialPageRoute(
            builder: (_) => Scaffold(
              backgroundColor: AppColors.darkBg,
              body: TimelinePage(api: widget.api),
            ),
          ));
        }),
        _divider(),
        const SizedBox(height: 28),

        // 标签宇宙 header + toggle
        Row(
          children: [
            Text('🏷️', style: TextStyle(fontSize: 16)),
            const SizedBox(width: 6),
            Text('标签宇宙', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
            const SizedBox(width: 6),
            Text('$_tagTotal个', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            const Spacer(),
            GestureDetector(
              onTap: _toggleView,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface2,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  _graphView ? '☰ 列表' : '✦ 图谱',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey5),
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),

        _graphView ? _buildGraphView() : _buildListView(),
      ],
    );
  }

  // ── 图谱视图 ──

  Widget _buildGraphView() {
    final sorted = List<TagSummary>.from(_allTags)
      ..sort((a, b) => b.count.compareTo(a.count));
    final tags = sorted.take(15).toList();
    if (tags.isEmpty) {
      return Text('暂无标签', style: TextStyle(fontSize: 13, color: AppColors.darkGrey6));
    }

    return AnimatedBuilder(
      animation: _graphAlpha,
      builder: (context, _) {
        return SizedBox(
          height: 260,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final cx = constraints.maxWidth / 2;
              final cy = constraints.maxHeight / 2;
              final maxR = (constraints.maxWidth < constraints.maxHeight
                      ? constraints.maxWidth
                      : constraints.maxHeight) *
                  0.38;
              final maxCount = tags.first.count.toDouble();

              // 中心标签
              final center = tags.first;

              // 外围标签：围绕中心散开
              final outer = tags.skip(1).toList();

              return Stack(
                children: [
                  // 中心到外围的连线
                  CustomPaint(
                    size: Size(constraints.maxWidth, constraints.maxHeight),
                    painter: _GraphLinePainter(
                      cx: cx,
                      cy: cy,
                      outerCount: outer.length,
                      outerR: maxR,
                      color: AppColors.darkGreen.withValues(alpha: 0.15 * _graphAlpha.value),
                    ),
                  ),
                  // 中心大标签
                  Positioned(
                    left: cx - 28,
                    top: cy - 28,
                    child: Opacity(
                      opacity: _graphAlpha.value,
                      child: _graphBubble(center, 56, 28, AppColors.darkGreen),
                    ),
                  ),
                  // 外围标签
                  ...List.generate(outer.length, (i) {
                    final angle = (2 * 3.14159 * i / outer.length) - 3.14159 / 2;
                    final r = maxR * (0.6 + 0.4 * (outer[i].count.toDouble() / maxCount));
                    final x = cx + r * _graphAlpha.value * _stableCos(angle) - 16;
                    final y = cy + r * _graphAlpha.value * _stableSin(angle) - 16;
                    final ratio = outer[i].count.toDouble() / maxCount;
                    final size = 28.0 + ratio * 16.0;
                    final color = outer[i].count > maxCount * 0.5
                        ? AppColors.darkGreen
                        : AppColors.darkGrey3;

                    return Positioned(
                      left: x,
                      top: y,
                      child: Opacity(
                        opacity: _graphAlpha.value * (0.6 + ratio * 0.4),
                        child: _graphBubble(
                          outer[i], size, size / 2 + 2 + ratio * 4, color),
                      ),
                    );
                  }),
                ],
              );
            },
          ),
        );
      },
    );
  }

  // 避免 dx 和 dy 在重绘时跳动
  double _stableCos(double a) => _cosTable[a.toString()] ??= _cos(a);
  double _stableSin(double a) => _sinTable[a.toString()] ??= _sin(a);
  final Map<String, double> _cosTable = {};
  final Map<String, double> _sinTable = {};
  double _cos(double a) => _round4(cos(a));
  double _sin(double a) => _round4(sin(a));
  double _round4(double v) => (v * 10000).roundToDouble() / 10000;

  Widget _graphBubble(TagSummary tag, double size, double fontSize, Color color) {
    return GestureDetector(
      onTap: () => widget.onTagTap(tag.name),
      child: Container(
        width: size,
        height: size,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.15),
          shape: BoxShape.circle,
          border: Border.all(color: color.withValues(alpha: 0.3), width: 0.5),
        ),
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: Padding(
            padding: const EdgeInsets.all(4),
            child: Text(
              tag.name,
              style: TextStyle(fontSize: fontSize * 0.28, color: color, fontWeight: FontWeight.w500),
            ),
          ),
        ),
      ),
    );
  }

  // ── 列表视图（原有 Wrap） ──

  Widget _buildListView() {
    final sorted = List<TagSummary>.from(_allTags)
      ..sort((a, b) => b.count.compareTo(a.count));
    final tags = sorted.take(20).toList();
    final maxCount = tags.isNotEmpty ? tags.first.count.toDouble() : 1.0;

    if (tags.isEmpty) {
      return Text('暂无标签', style: TextStyle(fontSize: 13, color: AppColors.darkGrey6));
    }

    return Wrap(
      spacing: 10,
      runSpacing: 14,
      children: tags.map((t) {
        final ratio = t.count.toDouble() / maxCount;
        final fontSize = 14.0 + ratio * 8.0;
        final opacity = 0.5 + ratio * 0.5;
        final color = t.count > maxCount * 0.6
            ? AppColors.darkGreen
            : t.count > maxCount * 0.3
                ? AppColors.darkGrey3
                : AppColors.darkGrey5;

        return GestureDetector(
          onTap: () => widget.onTagTap(t.name),
          child: Container(
            padding: EdgeInsets.symmetric(
              horizontal: 10.0 + ratio * 6.0,
              vertical: 6.0 + ratio * 3.0,
            ),
            decoration: BoxDecoration(
              color: AppColors.darkSurface2.withValues(alpha: opacity * 0.3),
              borderRadius: BorderRadius.circular(8.0 + ratio * 4.0),
              border: Border.all(
                color: color.withValues(alpha: opacity * 0.3),
                width: 0.5,
              ),
            ),
            child: Text(
              t.name,
              style: TextStyle(
                fontSize: fontSize,
                color: color.withValues(alpha: opacity),
                fontWeight: ratio > 0.5 ? FontWeight.w600 : FontWeight.w500,
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  // ── UI 组件 ──

  Widget _buildDragHandle() {
    return GestureDetector(
      onVerticalDragEnd: (d) {
        if (d.primaryVelocity != null && d.primaryVelocity! > 200) {
          widget.onNavigateBack();
        }
      },
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 30,
        alignment: Alignment.center,
        child: Container(
          width: 30,
          height: 3,
          decoration: BoxDecoration(
            color: AppColors.darkGrey5.withValues(alpha: 0.3),
            borderRadius: BorderRadius.circular(2),
          ),
        ),
      ),
    );
  }

  Widget _buildSearchBar() {
    return Container(
      height: 40,
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          GestureDetector(
            onTap: widget.onNavigateBack,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10),
              child: Icon(Icons.arrow_back, size: 18, color: AppColors.darkGrey4),
            ),
          ),
          Expanded(
            child: GestureDetector(
              onTap: () {
                Navigator.push(context, MaterialPageRoute(
                  builder: (_) => Scaffold(
                    backgroundColor: AppColors.darkBg,
                    body: SearchPage(api: widget.api),
                  ),
                ));
              },
              child: Container(
                height: 40,
                alignment: Alignment.centerLeft,
                child: Text('搜索记录、标签、记忆…',
                    style: TextStyle(fontSize: 15, color: AppColors.darkGrey6)),
              ),
            ),
          ),
          GestureDetector(
            onTap: () {
              Navigator.push(context, MaterialPageRoute(
                builder: (_) => Scaffold(
                  backgroundColor: AppColors.darkBg,
                  body: SearchPage(api: widget.api),
                ),
              ));
            },
            child: Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              child: Icon(Icons.arrow_forward_rounded, size: 18, color: AppColors.darkGrey5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRow(String emoji, String title, String preview, Color accentColor, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 14),
        child: Row(
          children: [
            Text(emoji, style: const TextStyle(fontSize: 20)),
            const SizedBox(width: 10),
            Text(title,
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w500, color: AppColors.darkGrey1)),
            const Spacer(),
            Text(preview, style: TextStyle(fontSize: 12, color: accentColor)),
            const SizedBox(width: 6),
            Icon(Icons.chevron_right, size: 16, color: AppColors.darkGrey6),
          ],
        ),
      ),
    );
  }

  Widget _divider() {
    return Divider(color: AppColors.darkBorder.withValues(alpha: 0.2), height: 1);
  }
}

/// 图谱连线绘制
class _GraphLinePainter extends CustomPainter {
  final double cx, cy;
  final int outerCount;
  final double outerR;
  final Color color;

  _GraphLinePainter({
    required this.cx,
    required this.cy,
    required this.outerCount,
    required this.outerR,
    required this.color,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (outerCount == 0) return;
    final paint = Paint()
      ..color = color
      ..strokeWidth = 0.5
      ..style = PaintingStyle.stroke;

    for (int i = 0; i < outerCount; i++) {
      final angle = (2 * 3.14159 * i / outerCount) - 3.14159 / 2;
      final x = cx + outerR * cos(angle);
      final y = cy + outerR * sin(angle);
      canvas.drawLine(Offset(cx, cy), Offset(x, y), paint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => true;
}
