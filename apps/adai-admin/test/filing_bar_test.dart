// 底部备案栏测试（2026-09-24 公安联网备案批）：
// 法定要求「网站底部悬挂备案号并链接官方查询页」——工信部 ICP + 公安部两套编号缺一不可，
// 且两处（壳 / 登录页）必须同源，否则改一处漏一处。
import 'package:adai_admin/widgets/filing_bar.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('备案栏同时渲染公安与 ICP 两套编号，图标在场', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: FilingBar())));

    expect(find.text(FilingLinks.gonganNo), findsOneWidget);
    expect(find.text(FilingLinks.icpNo), findsOneWidget);
    // 公安图标（测试环境可能加载不到 → errorBuilder 兜底，但 Image 节点仍在）
    expect(find.byType(Image), findsOneWidget);
  });

  testWidgets('两个备案号都可点（各自链接官方查询页），点击不抛异常', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: FilingBar())));

    expect(find.byType(InkWell), findsNWidgets(2));

    await tester.tap(find.text(FilingLinks.gonganNo));
    await tester.pump();
    await tester.tap(find.text(FilingLinks.icpNo));
    await tester.pump();

    expect(tester.takeException(), isNull);
    // 链接口径：公安用备案平台给的 code 查询页，ICP 用工信部备案系统
    expect(FilingLinks.gonganUrl, contains('beian.mps.gov.cn'));
    expect(FilingLinks.gonganUrl, contains('11011402057309'));
    expect(FilingLinks.icpUrl, contains('beian.miit.gov.cn'));
  });

  testWidgets('登录页复用的无容器版（FilingLinks）含同一份编号且可点', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: Center(child: FilingLinks()))),
    );

    expect(find.text(FilingLinks.gonganNo), findsOneWidget);
    expect(find.text(FilingLinks.icpNo), findsOneWidget);
    await tester.tap(find.text(FilingLinks.icpNo));
    await tester.pump();
    expect(tester.takeException(), isNull);
  });
}
