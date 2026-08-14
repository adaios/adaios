import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_web/desktop_shell.dart';
import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/pages/memory_page.dart';
import 'package:adai_web/pages/timeline_page.dart';
import 'package:adai_web/pages/trading_page.dart';
import 'package:adai_web/services/api_service.dart';

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

/// 注入 MockClient 的 ApiService：默认给全插件（8 模块全显），可单独指定插件集测门控。
ApiService _api({List<String> plugins = const ['trading', 'project']}) {
  return ApiService(
    baseUrl: 'http://test',
    userId: 'default',
    client: MockClient((req) async {
      if (req.url.path.endsWith('/api/v1/me/plugins')) {
        return _json(plugins);
      }
      return _json({'error': 'not mocked'}, status: 404);
    }),
  );
}

void main() {
  Future<void> pumpShell(WidgetTester tester, {ApiService? api}) async {
    await tester.binding.setSurfaceSize(const Size(1200, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(
      home: DesktopShell(userId: 'default', api: api ?? _api()),
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

  // ── 插件门控（RFC 20260814 T2.9）──

  testWidgets('无插件用户：隐藏交易/项目，基础服务常驻', (tester) async {
    await pumpShell(tester, api: _api(plugins: []));

    final navRail = find.byKey(const ValueKey('nav-rail'));
    for (final label in ['对话流', '记忆', '时间线', '任务', '搜索', '档案']) {
      expect(
        find.descendant(of: navRail, matching: find.text(label)),
        findsOneWidget,
        reason: '基础服务「$label」应常驻',
      );
    }
    expect(find.descendant(of: navRail, matching: find.text('交易')), findsNothing,
        reason: '无 trading 插件 → 隐藏交易');
    expect(find.descendant(of: navRail, matching: find.text('项目')), findsNothing,
        reason: '无 project 插件 → 隐藏项目');
  });

  testWidgets('插件拉取失败：保守只显基础服务，壳不崩溃', (tester) async {
    await pumpShell(tester,
        api: ApiService(
          baseUrl: 'http://test',
          userId: 'default',
          client: MockClient((_) async => _json({'error': 'boom'}, status: 500)),
        ));

    expect(find.byType(DesktopShell), findsOneWidget);
    final navRail = find.byKey(const ValueKey('nav-rail'));
    expect(find.descendant(of: navRail, matching: find.text('交易')), findsNothing);
    expect(find.descendant(of: navRail, matching: find.text('对话流')), findsOneWidget);
  });
}
