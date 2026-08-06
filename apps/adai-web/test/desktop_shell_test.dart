import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:adai_web/desktop_shell.dart';
import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/pages/memory_page.dart';
import 'package:adai_web/pages/timeline_page.dart';
import 'package:adai_web/pages/trading_page.dart';

void main() {
  Future<void> pumpShell(WidgetTester tester) async {
    await tester.binding.setSurfaceSize(const Size(1200, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const MaterialApp(
      home: DesktopShell(userId: 'default'),
    ));
    await tester.pump();
  }

  testWidgets('渲染左导航 8 项 + 底部 @userId', (tester) async {
    await pumpShell(tester);

    final navRail = find.byKey(const ValueKey('nav-rail'));
    for (final label in ['对话流', '记忆', '时间线', '项目', '任务', '交易', '搜索', '档案']) {
      expect(
        find.descendant(of: navRail, matching: find.text(label)),
        findsOneWidget,
        reason: '导航项「$label」应存在',
      );
    }
    expect(find.descendant(of: navRail, matching: find.text('@default')), findsOneWidget);
  });

  testWidgets('默认 Feed 选中并渲染', (tester) async {
    await pumpShell(tester);

    expect(find.byType(FeedPage), findsOneWidget);
    // 其他页面未实例化（懒加载）
    expect(find.byType(MemoryPage), findsNothing);
  });

  testWidgets('懒加载：切到记忆后实例化，Feed 保活不销毁', (tester) async {
    await pumpShell(tester);

    await tester.tap(find.text('记忆'));
    await tester.pump();

    // 当前页（记忆）onstage 渲染
    expect(find.byType(MemoryPage), findsOneWidget);
    // Feed 仍在 IndexedStack 中（offstage 保活，未销毁）
    expect(find.byType(FeedPage, skipOffstage: false), findsOneWidget);
  });

  testWidgets('连续切换：记忆→交易，两页都在栈中', (tester) async {
    await pumpShell(tester);

    await tester.tap(find.text('记忆'));
    await tester.pump();
    await tester.tap(find.text('交易'));
    await tester.pump();

    // 当前页（交易）onstage 渲染
    expect(find.byType(TradingPage), findsOneWidget);
    // 已访问页面 offstage 保活
    expect(find.byType(MemoryPage, skipOffstage: false), findsOneWidget);
    expect(find.byType(FeedPage, skipOffstage: false), findsOneWidget);
  });

  testWidgets('时间线切换正常渲染', (tester) async {
    await pumpShell(tester);

    await tester.tap(find.text('时间线'));
    await tester.pump();

    expect(find.byType(TimelinePage), findsOneWidget);
  });
}
