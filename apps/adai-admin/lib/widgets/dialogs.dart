import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// 通用确认对话框 — 返回 true 表示确认。
Future<bool> showConfirmDialog(
  BuildContext context, {
  required String title,
  required String message,
  String confirmText = '确认',
  String cancelText = '取消',
}) async {
  final result = await showDialog<bool>(
    context: context,
    builder: (_) => AlertDialog(
      backgroundColor: AppColors.darkSurface,
      title: Text(title,
          style: const TextStyle(color: AppColors.darkGrey1, fontSize: 16)),
      content: Text(message,
          style: const TextStyle(color: AppColors.darkGrey3, fontSize: 13)),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: Text(cancelText,
              style: const TextStyle(color: AppColors.darkGrey5)),
        ),
        TextButton(
          onPressed: () => Navigator.pop(context, true),
          child: Text(confirmText,
              style: const TextStyle(color: AppColors.darkOrange)),
        ),
      ],
    ),
  );
  return result == true;
}

/// 通用文本编辑对话框 — 返回保存后的文本；取消返回 null。
Future<String?> showEditDialog(
  BuildContext context, {
  required String title,
  required String initial,
  String hint = '',
  int maxLines = 1,
}) {
  final ctrl = TextEditingController(text: initial);
  return showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      backgroundColor: AppColors.darkSurface,
      title: Text(title,
          style: const TextStyle(color: AppColors.darkGrey1, fontSize: 16)),
      content: TextField(
        controller: ctrl,
        autofocus: true,
        maxLines: maxLines,
        style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: const TextStyle(fontSize: 12, color: AppColors.darkGrey6),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('取消',
              style: TextStyle(color: AppColors.darkGrey5)),
        ),
        TextButton(
          onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
          child: const Text('保存',
              style: TextStyle(color: AppColors.darkGreen)),
        ),
      ],
    ),
  );
}
