// ReviewsTab 复盘页签测试（2026-09-06 审查 P1-B 回归）：
// 反哺入库 date 必须为 yyyy-MM-dd（此前 review.date.toString() 带空格毫秒，
// 后端 @PathVariable LocalDate 解析必败——反哺从未成功）。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/pages/system/reviews_tab.dart';

import 'fakes.dart';

void main() {
  testWidgets('反哺入库传 yyyy-MM-dd（P1-B 回归：不传 DateTime.toString）',
      (WidgetTester tester) async {
    final store = FakeSystemStore();
    await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: ReviewsTab(store: store))));
    await tester.pumpAndSettle();

    // fake 含两条复盘：2026-08-01 未生成（只有「生成」）、2026-07-31 已生成（有「反哺」）
    expect(find.text('反哺'), findsOneWidget);
    await tester.ensureVisible(find.text('反哺'));
    await tester.tap(find.text('反哺'));
    await tester.pumpAndSettle();

    // 确认弹窗 → 确认提升（不带备注）
    expect(find.text('反哺入库'), findsOneWidget);
    await tester.tap(find.text('确认提升'));
    await tester.pumpAndSettle();

    // 关键断言：date 是 yyyy-MM-dd，不含空格/毫秒（DateTime.toString 会产出
    // `2026-07-31 00:00:00.000`，后端 LocalDate 解析必败）
    expect(store.lastPromoteDate, '2026-07-31');
    expect(store.lastPromoteDate!.contains(' '), isFalse);
  });
}
