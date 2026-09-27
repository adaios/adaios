import 'package:flutter/material.dart';

/// 桌面端 hover 效果包装器。
///
/// 包裹任意 widget，在桌面端鼠标悬停时触发 builder 重建。
/// 触屏平台 MouseRegion 不触发，直接返回正常状态。
class Hoverable extends StatefulWidget {
  final Widget Function(BuildContext context, bool isHovered) builder;

  /// 悬停光标（task-log「125 剩余」，2026-09-26）：可点元素此前悬停**不变手型**，
  /// 桌面上「这能不能点」只能靠猜。默认手型——本组件目前只用于可点元素
  /// （导航项 / Feed 卡）；纯高亮不可点的场景显式传 [MouseCursor.defer]。
  final MouseCursor cursor;

  const Hoverable({super.key, required this.builder, this.cursor = SystemMouseCursors.click});

  @override
  State<Hoverable> createState() => _HoverableState();
}

class _HoverableState extends State<Hoverable> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: widget.cursor,
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      child: widget.builder(context, _isHovered),
    );
  }
}
