import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

/// 文件/知识内容预览 — P3-17（2026-09-06）。
///
/// 超大文件（md/json 可达数千行）原以单个 [SelectableText] 全量渲染会卡顿；
/// 超过 [maxChars] 只渲染前段 + 「内容较长已截断」附注（仍可选中复制前段）。
class FilePreview extends StatelessWidget {
  const FilePreview({super.key, required this.text, this.maxChars = 4000});

  final String text;
  final int maxChars;

  @override
  Widget build(BuildContext context) {
    final truncated = text.length > maxChars;
    final preview = truncated ? text.substring(0, maxChars) : text;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SelectableText(
          preview,
          style: const TextStyle(
              fontSize: 12, height: 1.5, color: AppColors.darkGrey3),
        ),
        if (truncated)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text('内容较长（${text.length} 字符），仅预览前 $maxChars 字符',
                style: const TextStyle(
                    fontSize: 11, color: AppColors.darkGrey5)),
          ),
      ],
    );
  }
}
