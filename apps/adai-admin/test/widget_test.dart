// AdaiOS 管理端 — 账号管理 Widget 测试（注入 FakeAccountStore，不依赖后端）。

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/pages/accounts/accounts_page.dart';
import 'package:adai_admin/services/account_api_store.dart';

import 'fakes.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  /// 放大视口渲染全部账号卡（4 卡超出默认 600px 高 → ListView 懒构建找不到尾部卡），
  /// 并注入可选 store / 当前登录账号。
  Future<void> pumpAccounts(WidgetTester tester,
      {AccountStore? store,
      String currentUserId = '',
      ValueChanged<String>? onBrowseUser}) async {
    tester.view.physicalSize = const Size(1400, 2800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(_wrap(AccountsPage(
        store: store ?? FakeAccountStore(),
        currentUserId: currentUserId,
        onBrowseUser: onBrowseUser)));
    await tester.pumpAndSettle();
  }
  testWidgets('管理端渲染账号列表（预置账号 + 保护标记）',
      (WidgetTester tester) async {
    await pumpAccounts(tester);

    // 预置账号：admin / alice / adai / bob
    // admin 账号名与角色徽章文本同为 'admin'（徽章 'admin' + userId 行 'admin'）→ findsWidgets
    expect(find.text('admin'), findsWidgets);
    expect(find.text('alice'), findsOneWidget);
    expect(find.text('adai'), findsOneWidget);
    expect(find.text('bob'), findsOneWidget);

    // 内置管理员保护标记
    expect(find.text('内置'), findsOneWidget);
    expect(find.text('管理员'), findsWidgets);
  });

  testWidgets('新建账号后列表实时反映', (WidgetTester tester) async {
    await pumpAccounts(tester);

    // 展开新建表单
    await tester.tap(find.text('+ 新建'));
    await tester.pumpAndSettle();

    // 建号表单含「账号 ID + 初始密码」两个输入框 → 取第一个（账号 ID）
    await tester.enterText(find.byType(TextField).first, 'zhangsan');
    // 账号卡片含插件开关后整体变高，创建按钮可能滚出可视区 → 先滚动到可见
    await tester.ensureVisible(find.text('创建账号'));
    await tester.tap(find.text('创建账号'));
    await tester.pumpAndSettle();

    expect(find.text('zhangsan'), findsOneWidget);
    expect(find.text('已创建账号'), findsOneWidget);
  });

  testWidgets('重复 userId 建号被拒绝', (WidgetTester tester) async {
    await pumpAccounts(tester);

    await tester.tap(find.text('+ 新建'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField).first, 'alice');
    await tester.ensureVisible(find.text('创建账号'));
    await tester.tap(find.text('创建账号'));
    await tester.pumpAndSettle();

    expect(find.textContaining('账号已存在'), findsOneWidget);
  });

  testWidgets('禁用普通账号后状态更新为「禁用」', (WidgetTester tester) async {
    await pumpAccounts(tester);

    // 内置 admin 无启用开关（受保护）；adai / alice / bob 各有 1 个启用开关 + 2 个插件开关
    expect(find.byKey(const ValueKey('enabled-admin')), findsNothing);
    expect(find.byKey(const ValueKey('enabled-adai')), findsOneWidget);
    expect(find.byKey(const ValueKey('enabled-alice')), findsOneWidget);
    expect(find.byKey(const ValueKey('enabled-bob')), findsOneWidget);
    expect(find.byKey(const ValueKey('plugin-alice-trading')), findsOneWidget);
    expect(find.byKey(const ValueKey('plugin-alice-project')), findsOneWidget);

    // 点击 alice 的启用开关（P2-2：禁用需确认弹窗）
    await tester.tap(find.byKey(const ValueKey('enabled-alice')));
    await tester.pumpAndSettle();
    expect(find.text('禁用账号'), findsOneWidget, reason: 'P2-2：禁用前需确认');
    await tester.tap(find.text('确认禁用'));
    await tester.pumpAndSettle();

    // 现在应有 2 个「禁用」标签（alice 禁用 + bob 本就禁用）；admin / adai 仍启用
    expect(find.text('禁用'), findsNWidgets(2));
    expect(find.text('启用'), findsNWidgets(2));
  });

  testWidgets('插件开关：给 alice 开 trading → 状态反映（RFC 20260814）', (WidgetTester tester) async {
    await pumpAccounts(tester);

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
    await pumpAccounts(tester);

    // 删除 alice（第一个删除按钮）
    await tester.tap(find.byIcon(Icons.delete_outline).first);
    await tester.pumpAndSettle();

    // P2-5：确认文案明示会话失效与不可撤销
    expect(find.textContaining('会话将立即失效'), findsOneWidget);
    expect(find.textContaining('不可撤销'), findsOneWidget);

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
    await pumpAccounts(tester, store: store);

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

  testWidgets('REVIEW #178：账号卡提供重置密码，两次一致提交成功', (WidgetTester tester) async {
    await pumpAccounts(tester);

    // 每个账号卡都有重置密码按钮（admin / adai / alice）
    expect(find.byKey(const ValueKey('reset-pwd-admin')), findsOneWidget);
    expect(find.byKey(const ValueKey('reset-pwd-adai')), findsOneWidget);
    expect(find.byKey(const ValueKey('reset-pwd-alice')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('reset-pwd-alice')));
    await tester.pumpAndSettle();
    expect(find.textContaining('重置密码 · alice'), findsOneWidget);

    // 弹窗两个密码输入框：新密码 + 确认（不一致会被拦）
    await tester.enterText(find.byType(TextField).at(0), 'newpass123');
    await tester.enterText(find.byType(TextField).at(1), 'mismatch9');
    await tester.tap(find.text('重置密码'));
    await tester.pumpAndSettle();
    expect(find.textContaining('两次输入的密码不一致'), findsOneWidget);

    await tester.enterText(find.byType(TextField).at(1), 'newpass123');
    await tester.tap(find.text('重置密码'));
    await tester.pumpAndSettle();

    expect(find.textContaining('已重置 alice 的密码'), findsOneWidget);
  });

  testWidgets('REVIEW #178：当前登录账号自身隐藏重置密码（引导顶栏改密）', (WidgetTester tester) async {
    await pumpAccounts(tester, currentUserId: 'admin');

    // admin 是当前登录账号 → 无重置按钮；adai / alice 等他人账号仍可重置
    expect(find.byKey(const ValueKey('reset-pwd-admin')), findsNothing);
    expect(find.byKey(const ValueKey('reset-pwd-adai')), findsOneWidget);
    expect(find.byKey(const ValueKey('reset-pwd-alice')), findsOneWidget);
    expect(find.byKey(const ValueKey('reset-pwd-bob')), findsOneWidget);
  });

  testWidgets('P3-2：重置内置 admin 先警示确认；取消不进重置弹窗', (WidgetTester tester) async {
    await pumpAccounts(tester);

    await tester.tap(find.byKey(const ValueKey('reset-pwd-admin')));
    await tester.pumpAndSettle();
    // 警示确认对话框（内置管理员专用）
    expect(find.text('重置内置管理员'), findsOneWidget);
    expect(find.textContaining('系统内置管理员'), findsOneWidget);
    // 尚未进入实际重置弹窗
    expect(find.textContaining('重置密码 · admin'), findsNothing);

    // 取消 → 无重置弹窗
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(find.textContaining('重置密码 · admin'), findsNothing);

    // 再次进入并「继续重置」→ 才出现实际重置弹窗
    await tester.tap(find.byKey(const ValueKey('reset-pwd-admin')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('继续重置'));
    await tester.pumpAndSettle();
    expect(find.textContaining('重置密码 · admin'), findsOneWidget);
  });

  testWidgets('P2-6：账号卡「治理浏览」直达该用户；自身/禁用账号无入口', (WidgetTester tester) async {
    String? browsed;
    await pumpAccounts(tester,
        currentUserId: 'admin',
        store: FakeAccountStore(),
        onBrowseUser: (id) => browsed = id);

    // enabled 的 alice/adai 有治理浏览；内置 admin（自身）与禁用 bob 无
    expect(find.byKey(const ValueKey('browse-alice')), findsOneWidget);
    expect(find.byKey(const ValueKey('browse-adai')), findsOneWidget);
    expect(find.byKey(const ValueKey('browse-admin')), findsNothing,
        reason: '自身即当前登录账号，无需跳转');
    expect(find.byKey(const ValueKey('browse-bob')), findsNothing,
        reason: '禁用账号不可登录，无数据治理视图');

    await tester.ensureVisible(find.byKey(const ValueKey('browse-alice')));
    await tester.tap(find.byKey(const ValueKey('browse-alice')));
    expect(browsed, 'alice');
  });
}
