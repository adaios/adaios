// AdaiOS 管理端 — 账号管理 Widget 测试（注入 FakeAccountStore，不依赖后端）。

import 'dart:async';

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
    // 账号卡片含插件开关后整体变高，创建按钮可能滚出可视区 → 先滚动到可见
    await tester.ensureVisible(find.text('创建账号'));
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
    await tester.ensureVisible(find.text('创建账号'));
    await tester.tap(find.text('创建账号'));
    await tester.pumpAndSettle();

    expect(find.textContaining('账号已存在'), findsOneWidget);
  });

  testWidgets('禁用普通账号后状态更新为「禁用」', (WidgetTester tester) async {
    await tester.pumpWidget(_wrap(AccountsPage(store: FakeAccountStore())));
    await tester.pumpAndSettle();

    // 内置 adai 无启用开关（受保护）；alice / bob 各有 1 个启用开关 + 2 个插件开关
    expect(find.byKey(const ValueKey('enabled-adai')), findsNothing);
    expect(find.byKey(const ValueKey('enabled-alice')), findsOneWidget);
    expect(find.byKey(const ValueKey('enabled-bob')), findsOneWidget);
    expect(find.byKey(const ValueKey('plugin-alice-trading')), findsOneWidget);
    expect(find.byKey(const ValueKey('plugin-alice-project')), findsOneWidget);

    // 点击 alice 的启用开关
    await tester.tap(find.byKey(const ValueKey('enabled-alice')));
    await tester.pumpAndSettle();

    // 现在应有 2 个「禁用」标签（alice 禁用 + bob 本就禁用）
    expect(find.text('禁用'), findsNWidgets(2));
    expect(find.text('启用'), findsOneWidget); // adai 仍启用
  });

  testWidgets('插件开关：给 alice 开 trading → 状态反映（RFC 20260814）', (WidgetTester tester) async {
    await tester.pumpWidget(_wrap(AccountsPage(store: FakeAccountStore())));
    await tester.pumpAndSettle();

    // alice 初始无插件
    final switchWidget = tester.widget<Switch>(
        find.byKey(const ValueKey('plugin-alice-trading')));
    expect(switchWidget.value, isFalse);

    await tester.tap(find.byKey(const ValueKey('plugin-alice-trading')));
    await tester.pumpAndSettle();

    final after = tester.widget<Switch>(
        find.byKey(const ValueKey('plugin-alice-trading')));
    expect(after.value, isTrue, reason: '点开关后 alice 应启用 trading 插件');

    // project 开关不受影响
    expect(
      tester.widget<Switch>(find.byKey(const ValueKey('plugin-alice-project'))).value,
      isFalse,
    );
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

  testWidgets('P2-R1 双开关快速连点：串行队列保证两个都开（竞态修复）',
      (WidgetTester tester) async {
    // 可控延迟 store：第一个 setPlugins 挂起，模拟两个 PATCH 在飞的竞态窗口
    final gate = Completer<void>();
    final store = GatedAccountStore(gate: gate);
    await tester.pumpWidget(_wrap(AccountsPage(store: store)));
    await tester.pumpAndSettle();

    // 快速连点 alice 的 trading + project 两个开关
    await tester.tap(find.byKey(const ValueKey('plugin-alice-trading')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('plugin-alice-project')));
    await tester.pump();

    // 串行队列：第一个 merge 已发起（挂起中），第二个仍在队列等待
    expect(store.setPluginsCalls.length, 1, reason: '串行队列：第二个 toggle 等第一个完成');

    // 放行第一个 → 队列继续执行第二个（服务端合并语义：各自 add 单插件）
    gate.complete();
    await tester.pumpAndSettle();

    expect(store.setPluginsCalls.length, 2, reason: '两个 toggle 都应执行');
    expect(store.setPluginsCalls.first, contains('trading'), reason: '第一次 toggle 只 add trading');
    expect(store.setPluginsCalls.last, contains('project'), reason: '第二次 toggle 只 add project（服务端合并）');
    // UI 最终两个开关都开
    expect(
      tester.widget<Switch>(find.byKey(const ValueKey('plugin-alice-trading'))).value,
      isTrue,
    );
    expect(
      tester.widget<Switch>(find.byKey(const ValueKey('plugin-alice-project'))).value,
      isTrue,
    );
  });
}
