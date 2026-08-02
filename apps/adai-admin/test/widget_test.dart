// AdaiOS 管理端 — 账号管理 Widget 测试（注入 FakeAccountStore，不依赖后端）。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/pages/accounts/accounts_page.dart';

import 'fakes.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  testWidgets('管理端渲染账号列表（预置账号 + 保护标记）',
      (WidgetTester tester) async {
    await tester.pumpWidget(_wrap(AccountsPage(store: FakeAccountStore())));
    await tester.pumpAndSettle();

    // 预置账号：adai / alice / bob
    expect(find.text('adai'), findsOneWidget);
    expect(find.text('alice'), findsOneWidget);
    expect(find.text('bob'), findsOneWidget);

    // 内置管理员保护标记
    expect(find.text('内置'), findsOneWidget);
    expect(find.text('管理员'), findsWidgets);
  });

  testWidgets('新建账号后列表实时反映', (WidgetTester tester) async {
    await tester.pumpWidget(_wrap(AccountsPage(store: FakeAccountStore())));
    await tester.pumpAndSettle();

    // 展开新建表单
    await tester.tap(find.text('+ 新建'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'zhangsan');
    await tester.tap(find.text('创建账号'));
    await tester.pumpAndSettle();

    expect(find.text('zhangsan'), findsOneWidget);
    expect(find.text('已创建账号'), findsOneWidget);
  });

  testWidgets('重复 userId 建号被拒绝', (WidgetTester tester) async {
    await tester.pumpWidget(_wrap(AccountsPage(store: FakeAccountStore())));
    await tester.pumpAndSettle();

    await tester.tap(find.text('+ 新建'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'alice');
    await tester.tap(find.text('创建账号'));
    await tester.pumpAndSettle();

    expect(find.textContaining('账号已存在'), findsOneWidget);
  });

  testWidgets('禁用普通账号后状态更新为「禁用」', (WidgetTester tester) async {
    await tester.pumpWidget(_wrap(AccountsPage(store: FakeAccountStore())));
    await tester.pumpAndSettle();

    // 找到 alice 账号卡片的 Switch（内置 adai 无 Switch）
    final switches = tester.widgetList<Switch>(find.byType(Switch)).toList();
    expect(switches.length, 2); // alice / bob 各有 1 个 Switch

    // 点击第一个 Switch（alice）
    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();

    // 现在应有 2 个「禁用」标签（alice 禁用 + bob 本就禁用）
    expect(find.text('禁用'), findsNWidgets(2));
    expect(find.text('启用'), findsOneWidget); // adai 仍启用
  });

  testWidgets('删除账号需确认，取消则保留', (WidgetTester tester) async {
    await tester.pumpWidget(_wrap(AccountsPage(store: FakeAccountStore())));
    await tester.pumpAndSettle();

    // 删除 alice（第一个删除按钮）
    await tester.tap(find.byIcon(Icons.delete_outline).first);
    await tester.pumpAndSettle();

    // 取消
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(find.text('alice'), findsOneWidget);

    // 再删，确认
    await tester.tap(find.byIcon(Icons.delete_outline).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除'));
    await tester.pumpAndSettle();

    expect(find.text('alice'), findsNothing);
    expect(find.text('已删除账号 alice'), findsOneWidget);
  });
}
