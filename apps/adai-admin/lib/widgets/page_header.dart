import 'package:flutter/material.dart';
import '../theme/app_colors.dart';
import 'badge.dart';

/// 模块页统一页头 — 图标 + 标题 + 副标题 + MOCK 徽标。
class PageHeader extends StatelessWidget {
  const PageHeader({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Icon(icon, size: 26, color: AppColors.darkGreen),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title,
                  style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                    letterSpacing: -0.3,
                    color: AppColors.darkGrey1,
                  )),
              const SizedBox(height: 2),
              Text(subtitle,
                  style: const TextStyle(
                      fontSize: 12, color: AppColors.darkGrey5)),
            ],
          ),
        ),
        const AppBadge(
          label: 'MOCK',
          color: AppColors.darkYellow,
          icon: Icons.science_outlined,
        ),
      ],
    );
  }
}
