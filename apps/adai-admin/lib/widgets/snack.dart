import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// 浮动 SnackBar — 深色表面 + 前景色区分成功/失败/提示。
void showAppSnack(BuildContext context, String text, Color color) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(
      backgroundColor: AppColors.darkSurface2,
      behavior: SnackBarBehavior.floating,
      content: Text(text, style: TextStyle(color: color, fontSize: 13)),
    ));
}
