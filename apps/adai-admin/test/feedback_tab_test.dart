// FeedbackTab 规则冲突页签测试（2026-09-06 P2-11 拍板回归）：
// 移除「标记已处理」本地假操作（无持久化、刷新即丢，会误导「已解决」）——
// 冲突为后端规则对照的只读结果；页内引导指向复盘 tab 的反哺入口。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/pages/system/feedback_tab.dart';

import 'fakes.dart';

void main() {
  testWidgets('冲突只读展示：无「标记已处理」按钮，徽章+引导存在（P2-11）',
      (WidgetTester tester) async {
    final store = FakeSystemStore();
    await tester.pumpWidget(
        MaterialApp(home: Scaffold(body: FeedbackTab(store: store))));
    await tester.pumpAndSettle();

    // 后端冲突（handled=false）展示「冲突」徽章 + 两侧内容
    expect(find.text('规则冲突'), findsOneWidget);
    expect(find.text('冲突'), findsWidgets);
    expect(find.text('R96 四不原则'), findsWidgets);

    // 本地假操作按钮已移除：无「标记已处理」tooltip、无 done 图标
    expect(find.byTooltip('标记已处理'), findsNothing);
    expect(find.byIcon(Icons.done_outline), findsNothing);
    expect(find.byIcon(Icons.undo), findsNothing);

    // 引导指向复盘 tab 的反哺入口
    expect(find.textContaining('反哺入库：在「复盘」页签'), findsOneWidget);
  });
}
