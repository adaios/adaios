// 治理浏览数据源标注测试（2026-09-06 P2-7 拍板回归）：
// 记录/Feed 预览都是「当天 + 最多前 50 条」的截断视图——须显式标注来源与上限，
// 防管理员把截断误读为「该用户当天只有这些数据」（历史浏览待规划）。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/pages/data/records_tab.dart';
import 'package:adai_admin/pages/system/feed_tab.dart';

import 'fakes.dart';

void main() {
  testWidgets('记录页标注「当天 · 最多 50 条」来源说明（P2-7）',
      (WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: RecordsTab(store: FakeDataStore()))));
    await tester.pumpAndSettle();

    expect(find.text('数据源：当天记录，最多展示前 50 条'), findsOneWidget);
  });

  testWidgets('Feed 预览标注「当天 · 最多 50 条」来源说明（P2-7）',
      (WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: FeedTab(store: FakeSystemStore()))));
    await tester.pumpAndSettle();

    expect(find.text('数据源：当天 Feed，最多展示前 50 条'), findsOneWidget);
  });
}
