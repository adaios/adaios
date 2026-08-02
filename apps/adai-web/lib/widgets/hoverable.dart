import 'package:flutter/material.dart';

/// 桌面端 hover 效果包装器。
///
/// 包裹任意 widget，在桌面端鼠标悬停时触发 builder 重建。
/// 触屏平台 MouseRegion 不触发，直接返回正常状态。
class Hoverable extends StatefulWidget {
  final Widget Function(BuildContext context, bool isHovered) builder;

  const Hoverable({super.key, required this.builder});

  @override
  State<Hoverable> createState() => _HoverableState();
}

class _HoverableState extends State<Hoverable> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      child: widget.builder(context, _isHovered),
    );
  }
}
