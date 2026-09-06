// DataTreeTab 文件树页签测试（2026-09-06 P2-13 拍板回归）：
// 文件树是全局 data/（含全部账号目录），需显式标注，避免管理员
// 在「用户 X」per-user 视图里误判文件树范围就是该用户的数据。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/pages/data/data_tree_tab.dart';

import 'fakes.dart';

void main() {
  testWidgets('文件树标注全局 data/ 范围（P2-13）', (WidgetTester tester) async {
    final store = FakeDataStore();
    await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: DataTreeTab(store: store))));
    await tester.pumpAndSettle();

    expect(find.text('全局 data/ 文件树（含全部账号目录，非当前用户视图）'),
        findsOneWidget);
    // 树根 data/ 渲染
    expect(find.text('data/'), findsOneWidget);
  });
}
