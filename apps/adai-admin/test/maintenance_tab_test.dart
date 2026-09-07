// MaintenanceTab 行情数据导入进度条测试（2026-09-07 体验增强）：
// 上传中显示百分比 + LinearProgressIndicator + 已传/总量；完成显示摘要 snackbar。
// 注入 fake pickZipFile（内存 zip）与 FakeSystemStore.tdxProgressListener（模拟逐块进度）。

import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/pages/system/maintenance_tab.dart';
import 'package:adai_admin/widgets/app_card.dart';

import 'fakes.dart';

void main() {
  Widget wrap(MaintenanceTab tab) => MaterialApp(home: Scaffold(body: tab));

  testWidgets('行情导入上传中显示进度条与百分比，完成后出摘要（2026-09-07）',
      (WidgetTester tester) async {
    final store = FakeSystemStore();
    // fake 选文件：返回内存 zip（256KB，便于进度步进）。
    final zipBytes = Uint8List(256 * 1024);
    final tab = MaintenanceTab(
      store: store,
      userId: 'adai',
      pickZipFile: () async => FilePickerResult([
        PlatformFile(name: 'sh.zip', size: zipBytes.length, bytes: zipBytes),
      ]),
    );
    // 模拟上传进度：注册回调 → 25% → 50% → 75% → 完成。
    store.tdxProgressListener = (total, onProgress) async {
      onProgress(total ~/ 4, total);
      await Future<void>.delayed(const Duration(milliseconds: 10));
      onProgress(total ~/ 2, total);
      await Future<void>.delayed(const Duration(milliseconds: 10));
      onProgress((total * 3) ~/ 4, total);
      await Future<void>.delayed(const Duration(milliseconds: 10));
      onProgress(total, total);
    };

    await tester.pumpWidget(wrap(tab));
    await tester.pumpAndSettle();

    // 找到行情数据导入卡片的「执行」并点击
    final tdxCard = find.ancestor(
      of: find.text('行情数据导入'),
      matching: find.byType(AppCard),
    );
    expect(tdxCard, findsOneWidget);
    await tester.ensureVisible(find.descendant(
        of: tdxCard, matching: find.text('执行')));
    await tester.tap(find.descendant(of: tdxCard, matching: find.text('执行')));
    await tester.pump(); // picker future 微任务
    await tester.pump(); // 进入 busy + 首帧进度

    // 上传中：LinearProgressIndicator + 百分比（中途态应出现过 50% 或 75%）
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(find.textContaining('已上传'), findsOneWidget);
    expect(find.textContaining('%'), findsWidgets);

    // 等进度完成（4 步 * 10ms + setState 帧）
    await tester.pump(const Duration(milliseconds: 60));
    await tester.pumpAndSettle();

    // 完成后进度条消失，出现摘要 snackbar
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.textContaining('行情数据导入完成'), findsOneWidget);
  });

  testWidgets('行情导入失败（未选文件直接取消）不崩溃（2026-09-07）',
      (WidgetTester tester) async {
    final store = FakeSystemStore();
    final tab = MaintenanceTab(
      store: store,
      userId: 'adai',
      pickZipFile: () async => null, // 用户取消选择
    );

    await tester.pumpWidget(wrap(tab));
    await tester.pumpAndSettle();

    final tdxCard = find.ancestor(
      of: find.text('行情数据导入'),
      matching: find.byType(AppCard),
    );
    await tester.ensureVisible(find.descendant(
        of: tdxCard, matching: find.text('执行')));
    await tester.tap(find.descendant(of: tdxCard, matching: find.text('执行')));
    await tester.pumpAndSettle();

    // 无异常、无 busy、无进度残留
    expect(tester.takeException(), isNull);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.textContaining('已上传'), findsNothing);
  });
}
