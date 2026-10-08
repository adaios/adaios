import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';

import '../services/api_service.dart';
import '../theme/app_colors.dart';

/// 复盘弹窗（2026-10-09 app 端体验重构批 4 抽出）——**当天复盘**与**复盘历史**共用一份。
///
/// 抽出来的原因：这个弹窗原本是交易页的私有构建器；「复盘历史」页要能点开历史某天，
/// 再抄一遍就会出现两份文案/样式。`onPromote` 由调用方给（交易页给它的 `_promote`，
/// 历史页同样给——反哺入库对历史复盘一样成立）。
class ReviewDialog extends StatelessWidget {
  final ReviewResponse review;

  /// 「反哺入库」回调；为 null 时按钮不渲染。
  final VoidCallback? onPromote;

  const ReviewDialog({super.key, required this.review, this.onPromote});

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: AppColors.darkSurface,
      insetPadding: const EdgeInsets.all(24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.article_outlined, size: 18, color: AppColors.darkGreen),
            const SizedBox(width: 8),
            Text('${review.date} 复盘',
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
            const Spacer(),
            GestureDetector(
              onTap: () => Navigator.pop(context),
              child: const Icon(Icons.close, size: 18, color: AppColors.darkGrey5),
            ),
          ]),
          const SizedBox(height: 12),
          Flexible(
            child: SingleChildScrollView(
              child: MarkdownBody(
                data: review.content.isEmpty ? '这天没有复盘内容' : review.content,
                selectable: true,
                styleSheet: MarkdownStyleSheet.fromTheme(ThemeData(
                  textTheme:
                      const TextTheme(bodyMedium: TextStyle(fontSize: 14, height: 1.6, color: AppColors.darkGrey1)),
                )).copyWith(
                  strong: const TextStyle(
                      fontSize: 14, height: 1.6, color: AppColors.darkGrey1, fontWeight: FontWeight.w700),
                  p: const TextStyle(fontSize: 14, height: 1.6, color: AppColors.darkGrey1),
                ),
              ),
            ),
          ),
          if (onPromote != null) ...[
            const SizedBox(height: 14),
            // #129：知识反哺闭环前端入口——复盘内容提升为入库候选
            Align(
              alignment: Alignment.centerRight,
              child: GestureDetector(
                onTap: onPromote,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
                  decoration: BoxDecoration(
                    color: AppColors.darkGreen.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.4)),
                  ),
                  child: const Row(mainAxisSize: MainAxisSize.min, children: [
                    Icon(Icons.inbox_outlined, size: 14, color: AppColors.darkGreen),
                    SizedBox(width: 6),
                    Text('反哺入库', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
                  ]),
                ),
              ),
            ),
          ],
        ]),
      ),
    );
  }
}
