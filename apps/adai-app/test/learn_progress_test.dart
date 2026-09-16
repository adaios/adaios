import 'package:adai_app/services/models/learn_models.dart';
import 'package:flutter_test/flutter_test.dart';

/// 学习进度口径（2026-09-16「学习卡片进度追踪」批）。
///
/// 这套数字**必须与每晚 20:00 的复习提醒同一把尺子**——口径一旦漂移，页面上说一套、
/// 推送里说另一套，就又是一个「会撒谎的指标」。所以这里把每条判据都钉死。
void main() {
  // 固定「今天」= 2026-09-16（周三），避免测试随真实日期漂移；本周一 = 2026-09-14。
  final today = DateTime(2026, 9, 16);

  LearnCardDto card({
    String status = 'review',
    bool writable = true,
    DateTime? reviewAt,
    String created = '2026-09-16',
  }) =>
      LearnCardDto(
        type: 'ai',
        title: '卡',
        created: created,
        status: status,
        writable: writable,
        reviewAt: reviewAt,
      );

  test('满 7 天才算「待复习」：第 7 天当天算到期，第 6 天不算', () {
    final progress = LearnProgress.of([
      card(reviewAt: DateTime(2026, 9, 9)), // 恰好 7 天前 → 到期
      card(reviewAt: DateTime(2026, 9, 10)), // 6 天前 → 还没到
    ], now: today);

    expect(progress.due, 1, reason: '满 7 天（含当天）才进待复习');
    expect(progress.learning, 1);
  });

  test('只读卡（Mac 侧整理的原始卡）不计入待复习，归学习中', () {
    final progress = LearnProgress.of([
      card(writable: false, reviewAt: DateTime(2026, 8, 1)), // 早就「该复习」了，但它只读
    ], now: today);

    expect(progress.due, 0,
        reason: '只读卡不参与复习流转——计进待复习会得到一个永远推不动的数字');
    expect(progress.learning, 1);
  });

  test('reviewAt 缺失（老数据/非 review 卡）不计入待复习——宁可漏不算错', () {
    final progress = LearnProgress.of([card()], now: today);

    expect(progress.due, 0);
    expect(progress.learning, 1);
  });

  test('已掌握只数 done；本周按「周一起算」', () {
    final progress = LearnProgress.of([
      card(status: 'done', created: '2026-09-14'), // 本周一
      card(status: 'done', created: '2026-09-13'), // 上周日
      card(status: 'new', created: '2026-09-16'),
    ], now: today);

    expect(progress.mastered, 2);
    expect(progress.thisWeek, 2, reason: '09-14（周一）与 09-16 在本周；09-13 属上周');
  });

  test('汇总文案格式（用户拍板，别改）', () {
    final progress = LearnProgress.of([
      card(reviewAt: DateTime(2026, 9, 1)),
    ], now: today);

    expect(progress.label, '待复习 1 · 学习中 0 · 已掌握 0 · 本周 +1');
  });
}
