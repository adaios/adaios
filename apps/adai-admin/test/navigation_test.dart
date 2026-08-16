// 管理端导航测试 — 数据 / 系统 / 知识 三个模块可进入并渲染（注入 Fake store）。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/pages/admin_shell.dart';

import 'fakes.dart';

void main() {
  Widget app() => MaterialApp(
        home: AdminShell(
          accountStore: FakeAccountStore(),
          dataStore: FakeDataStore(),
          systemStore: FakeSystemStore(),
          knowledgeStore: FakeKnowledgeStore(),
        ),
      );

  testWidgets('导航到「数据」模块：页头 + 记录页签', (WidgetTester tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.tap(find.text('数据'));
    await tester.pumpAndSettle();

    expect(find.text('数据管理'), findsOneWidget);
    expect(find.text('陈述'), findsWidgets); // RecordsTab 类型徽标
  });

  testWidgets('数据模块治理收敛：记录/任务页签无删除控件，档案无编辑按钮，记忆无修正入口',
      (WidgetTester tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.tap(find.text('数据'));
    await tester.pumpAndSettle();

    // 记录页签：只读查看，无删除按钮（P-role-03）
    expect(find.byIcon(Icons.delete_outline), findsNothing);

    // 任务页签：只读列表，无 Checkbox / 删除（P-role-04）
    await tester.tap(find.widgetWithText(Tab, '任务'));
    await tester.pumpAndSettle();
    expect(find.text('为 adai-admin 补数据/系统/知识三个模块的页面框架'), findsOneWidget);
    expect(find.byType(Checkbox), findsNothing);
    expect(find.byIcon(Icons.delete_outline), findsNothing);
    expect(find.text('新建任务'), findsNothing);

    // 档案页签：只读查看，无「编辑」按钮（P-role-01）
    await tester.tap(find.widgetWithText(Tab, '档案'));
    await tester.pumpAndSettle();
    expect(find.text('adai'), findsOneWidget);
    expect(find.text('编辑'), findsNothing);

    // 记忆页签：只读查看，无修正入口（P-role-02）
    await tester.tap(find.widgetWithText(Tab, '记忆'));
    await tester.pumpAndSettle();
    expect(
      find.text('Feed 中 type=market 的行情条需要独立渲染逻辑，与普通记录区分'),
      findsOneWidget,
    );
    expect(find.byIcon(Icons.edit_outlined), findsNothing);
  });

  testWidgets('数据模块内切换到「记忆」页签', (WidgetTester tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.tap(find.text('数据'));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(Tab, '记忆'));
    await tester.pumpAndSettle();

    // 断言列表顶部可见的记忆条目（m-01）
    expect(
      find.text('Feed 中 type=market 的行情条需要独立渲染逻辑，与普通记录区分'),
      findsOneWidget,
    );
    expect(find.text('已取代'), findsOneWidget); // m-03 superseded 徽标
  });

  testWidgets('导航到「系统」模块：页头 + Feed 页签', (WidgetTester tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.tap(find.text('系统'));
    await tester.pumpAndSettle();

    expect(find.text('系统操作台'), findsOneWidget);
    expect(find.text('行情 · 沪深300 指数'), findsOneWidget);
  });

  testWidgets('导航到「知识」模块：页头 + os/ 资产页签', (WidgetTester tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.tap(find.text('知识'));
    await tester.pumpAndSettle();

    expect(find.text('知识浏览'), findsOneWidget);
    expect(find.text('os/ 资产'), findsOneWidget);
  });

  testWidgets('系统模块维护操作：执行后成功 SnackBar', (WidgetTester tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();

    await tester.tap(find.text('系统'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('维护'));
    await tester.pumpAndSettle();

    // 触发「记忆重建」（第一个执行按钮）
    await tester.tap(find.text('执行').first);
    await tester.pumpAndSettle();

    expect(find.textContaining('记忆重建完成'), findsOneWidget);
  });
}
