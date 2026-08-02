import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// 桌面页统一页头：标题 + 副标题 + 右侧操作区。
class PageHeader extends StatelessWidget {
  final String title;
  final String? subtitle;
  final List<Widget>? actions;

  const PageHeader({super.key, required this.title, this.subtitle, this.actions});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 16),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.darkBorder, width: 0.5)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: _buildTitleColumn(),
            ),
          ),
          ...?actions,
        ],
      ),
    );
  }

  List<Widget> _buildTitleColumn() {
    return <Widget>[
      Text(
        title,
        style: const TextStyle(
          fontSize: 22,
          fontWeight: FontWeight.w700,
          color: AppColors.darkGrey1,
        ),
      ),
      if (subtitle != null) ...[
        const SizedBox(height: 4),
        Text(
          subtitle!,
          style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5),
        ),
      ],
    ];
  }
}
