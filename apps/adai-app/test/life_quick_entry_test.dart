import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_app/pages/life_quick_entry.dart';
import 'package:adai_app/widgets/input_bar.dart';

/// 生活快捷记录接线批（2026-09-13）。
///
/// 背景：`LifeQuickEntry`（四类模板弹窗）此前**定义了但全仓库无人引用**——是死代码；
/// 本轮把它接进输入栏，并把「随手记一笔」的门槛降到一下点击。
/// 本文件守住三件事：① 模板单一事实源不漂移；② 预选类型正确且非法值不崩；
/// ③ 「使用模板」后光标落在句尾（否则接着打字会插到句首，把句子打乱）。
void main() {
  group('LifeQuickEntry 弹窗', () {
    testWidgets('模板单一事实源：四类齐全且 key/emoji/label 稳定', (tester) async {
      expect(kLifeTemplates.map((t) => t.key).toList(), ['mood', 'sport', 'diet', 'sleep']);
      expect(kLifeTemplates.map((t) => t.label).toList(), ['心情', '运动', '饮食', '睡眠']);
      expect(kLifeTemplates.every((t) => t.emoji.isNotEmpty && t.prefix.isNotEmpty && t.hint.isNotEmpty), isTrue);
    });

    testWidgets('未知 key 回落心情（不崩）', (tester) async {
      expect(lifeTemplateOf('__nope__').key, 'mood');
      expect(lifeTemplateOf('').key, 'mood');
    });

    testWidgets('initialType 预选：打开即选中该类型并显示对应 hint', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => TextButton(
              onPressed: () => showLifeQuickEntry(ctx, (_) {}, initialType: 'sport'),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.text('今天运动了多久？感觉如何？'), findsOneWidget,
          reason: '预选运动 → hint 应为运动的引导问句');
    });

    testWidgets('非法 initialType 回落心情，不崩溃', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => TextButton(
              onPressed: () => showLifeQuickEntry(ctx, (_) {}, initialType: 'no_such_type'),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull, reason: '非法初始值不应抛异常');
      expect(find.text('今天心情怎么样？因为什么？'), findsOneWidget, reason: '应回落心情');
    });

    testWidgets('「使用模板」填入前缀且光标落句尾（接着打字不打乱句子）', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => TextButton(
              onPressed: () => showLifeQuickEntry(ctx, (_) {}, initialType: 'sport'),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('使用模板'));
      await tester.pumpAndSettle();

      final editable = tester.widget<EditableText>(
        find.descendant(of: find.byType(LifeQuickEntry), matching: find.byType(EditableText)),
      );
      expect(editable.controller.text, '今天运动了');
      expect(editable.controller.selection.baseOffset, '今天运动了'.length,
          reason: '光标必须在句尾——否则用户接着打字会插到「今天」前面');

      // 真实续写：验证文本顺序正确
      await tester.enterText(
        find.descendant(of: find.byType(LifeQuickEntry), matching: find.byType(TextField)),
        '今天运动了40分钟，状态不错',
      );
      await tester.pumpAndSettle();
      expect(editable.controller.text, '今天运动了40分钟，状态不错');
    });

    testWidgets('切换类型清空已输入内容并回到新类型 hint', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => TextButton(
              onPressed: () => showLifeQuickEntry(ctx, (_) {}),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.descendant(of: find.byType(LifeQuickEntry), matching: find.byType(TextField)),
        '临时内容',
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('睡眠'));
      await tester.pumpAndSettle();

      final editable = tester.widget<EditableText>(
        find.descendant(of: find.byType(LifeQuickEntry), matching: find.byType(EditableText)),
      );
      expect(editable.controller.text, isEmpty, reason: '切换类型应清空');
      expect(find.text('昨晚睡了几个小时？质量如何？'), findsOneWidget);
    });

    testWidgets('点「记录」→ onSend 收到文本并关闭弹窗', (tester) async {
      String? sent;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => TextButton(
              onPressed: () => showLifeQuickEntry(ctx, (t) => sent = t, initialType: 'diet'),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.descendant(of: find.byType(LifeQuickEntry), matching: find.byType(TextField)),
        '今天吃了牛肉面',
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('记录'));
      await tester.pumpAndSettle();

      expect(sent, '今天吃了牛肉面');
      expect(find.byType(LifeQuickEntry), findsNothing, reason: '发送后应关闭弹窗');
    });

    testWidgets('空输入不可发送（点「记录」无回调）', (tester) async {
      String? sent;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => TextButton(
              onPressed: () => showLifeQuickEntry(ctx, (t) => sent = t),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('记录'));
      await tester.pumpAndSettle();

      expect(sent, isNull, reason: '空输入不应触发回调');
      expect(find.byType(LifeQuickEntry), findsOneWidget, reason: '空输入不应关闭弹窗');
    });
  });

  group('InputBar 生活快捷条', () {
    testWidgets('无对话时渲染四类快捷入口（心情/运动/饮食/睡眠）', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: InputBar(onSend: (_) {})),
      ));
      await tester.pump();

      for (final label in ['心情', '运动', '饮食', '睡眠']) {
        expect(find.text(label), findsOneWidget, reason: '快捷条应有「$label」');
      }
    });

    testWidgets('对话进行中隐藏快捷条（此时意图是接着聊，不是开新记录）', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: InputBar(onSend: (_) {}, hasActiveChat: true)),
      ));
      await tester.pump();

      expect(find.text('心情'), findsNothing);
      expect(find.text('运动'), findsNothing);
    });

    testWidgets('点「运动」→ 弹窗预选运动 → 记录 → InputBar.onSend 收到文本（端到端接线）', (tester) async {
      String? sent;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: InputBar(onSend: (t) => sent = t)),
      ));
      await tester.pump();

      await tester.tap(find.text('运动'));
      await tester.pumpAndSettle();

      expect(find.byType(LifeQuickEntry), findsOneWidget, reason: '点快捷条应打开弹窗');
      expect(find.text('今天运动了多久？感觉如何？'), findsOneWidget, reason: '应预选「运动」');

      await tester.enterText(
        find.descendant(of: find.byType(LifeQuickEntry), matching: find.byType(TextField)),
        '今天运动了30分钟',
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('记录'));
      await tester.pumpAndSettle();

      expect(sent, '今天运动了30分钟', reason: '弹窗输出必须接回输入栏主发送流');
    });
  });
}
