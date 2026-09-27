import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_web/widgets/hoverable.dart';

/// task-log「125 剩余」（2026-09-26 夜间批六）：可点元素悬停**必须变手型**——
/// 桌面上「这能不能点」不该只能靠猜；纯高亮不可点的场景可显式 `defer`。
void main() {
  testWidgets('Hoverable 默认给手型光标（改前不变手型）', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Hoverable(builder: (context, hovered) => const Text('可点卡片')),
      ),
    ));

    final region = tester.widget<MouseRegion>(find.descendant(
      of: find.byType(Hoverable),
      matching: find.byType(MouseRegion),
    ));
    expect(region.cursor, SystemMouseCursors.click);
  });

  testWidgets('纯高亮不可点时可显式 defer', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Hoverable(
          cursor: MouseCursor.defer,
          builder: (context, hovered) => const Text('仅高亮'),
        ),
      ),
    ));

    final region = tester.widget<MouseRegion>(find.descendant(
      of: find.byType(Hoverable),
      matching: find.byType(MouseRegion),
    ));
    expect(region.cursor, MouseCursor.defer);
  });
}
