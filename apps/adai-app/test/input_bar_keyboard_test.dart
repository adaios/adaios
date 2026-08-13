import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_app/widgets/input_bar.dart';

/// 输入栏键盘收起回归（阿呆 08-13 反馈）：
/// 1. 发送后主动 unfocus，键盘不再霸屏遮挡 Feed
/// 2. 点击空白区域（壳层 onTap unfocus）收起键盘
///
/// 判定用 `tester.testTextInput.hasAnyClients`——TextInput 客户端连接代表
/// 输入框持有焦点（键盘弹起）；unfocus 后焦点回到祖先 FocusScope，primaryFocus
/// 仍非 null，故不用 primaryFocus 判定。
void main() {
  group('InputBar 键盘收起', () {
    testWidgets('发送后收起键盘（焦点释放）', (tester) async {
      String? sent;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: InputBar(onSend: (text) => sent = text),
        ),
      ));

      // 聚焦输入框 → 键盘（文本输入连接）弹起
      await tester.tap(find.byType(TextField));
      await tester.pump();
      expect(tester.testTextInput.hasAnyClients, isTrue,
          reason: '点击输入框后应持有焦点（键盘弹起）');

      // 输入文字并发送
      await tester.enterText(find.byType(TextField), '测试记录');
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
      await tester.pump();

      expect(sent, '测试记录', reason: '发送回调应触发');
      expect(tester.testTextInput.hasAnyClients, isFalse,
          reason: '发送后应释放焦点收起键盘（阿呆 08-13）');
    });

    testWidgets('点击输入框外部空白区域收起键盘', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: GestureDetector(
            // 模拟壳层 onTap：子级无 onTap 的空白区域由外层 GestureDetector 释放焦点
            behavior: HitTestBehavior.translucent,
            onTap: () => FocusManager.instance.primaryFocus?.unfocus(),
            child: Column(
              children: [
                const Expanded(child: SizedBox()), // 空白区域（无子级 onTap）
                InputBar(onSend: (_) {}),
              ],
            ),
          ),
        ),
      ));

      // 聚焦输入框
      await tester.tap(find.byType(TextField));
      await tester.pump();
      expect(tester.testTextInput.hasAnyClients, isTrue);

      // 点击输入框外的空白区域
      await tester.tapAt(const Offset(200, 100));
      await tester.pump();
      expect(tester.testTextInput.hasAnyClients, isFalse,
          reason: '点击空白处应收起键盘（壳层 onTap unfocus）');
    });
  });
}
