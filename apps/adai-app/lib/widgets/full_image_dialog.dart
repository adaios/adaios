import 'package:flutter/material.dart';
import '../theme/app_colors.dart';

/// 全图 Dialog（点任意处关闭）——供 feed_card / main_page 全图预览复用。
/// REVIEW #244：Image.network 带 errorBuilder/loadingBuilder（对齐 timeline_page #199 模式），
/// 图片 404 显示友好占位而非空白 Dialog。
void showFullImageDialog(
  BuildContext context, {
  required String url,
  Map<String, String>? headers,
}) {
  showDialog(
    context: context,
    builder: (_) => Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.all(20),
      child: GestureDetector(
        onTap: () => Navigator.pop(context),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: Image.network(
            url,
            headers: headers,
            fit: BoxFit.contain,
            errorBuilder: (_, _, _) => Container(
              constraints: const BoxConstraints(maxWidth: 300),
              padding: const EdgeInsets.all(28),
              color: AppColors.darkSurface2,
              child: const Column(mainAxisSize: MainAxisSize.min, children: [
                Icon(Icons.broken_image_outlined, size: 36, color: AppColors.darkGrey5),
                SizedBox(height: 10),
                Text('图片加载失败', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
              ]),
            ),
            loadingBuilder: (_, child, progress) => progress == null
                ? child
                : Container(
                    width: 140, height: 140,
                    color: AppColors.darkSurface2,
                    child: const Center(child: SizedBox(width: 24, height: 24,
                        child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen))),
                  ),
          ),
        ),
      ),
    ),
  );
}
