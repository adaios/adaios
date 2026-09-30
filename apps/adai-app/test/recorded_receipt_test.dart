import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:adai_app/widgets/feed_card.dart';

/// D4「说出来」（2026-09-30 用户拍板：A「说出来」＋ 宁可它多说一句）。
///
/// 生产实据：带卡片的请求 **47%**（31/66）被判成 log → 不进对话、阿呆一个字都不回，
/// 用户体感就是「对话模式丢了上下文」（见 REVIEW P2-对话3）。
/// 本文件钉住两件事：
///   ① 刚提交且被判为「记录」的卡 → **给一行如实回执**（不再静默）；
///   ② 普通/历史记录卡 → **不显示**该行（回执只在「刚说过话」那一刻出现，不打扰翻历史）。
///
/// 反向可验证：批 1 之前 `justRecorded` 字段不存在，① 必然失败（卡片永远没有这行）。
void main() {
  FeedCardData card({required bool justRecorded}) => FeedCardData(
        id: 'rec_1',
        type: FeedCardType.record,
        time: '10:00',
        content: '今天很开心帮我记一下',
        summary: '记录今日好心情',
        mode: CardMode.idle,
        intent: IntentType.log,
        domain: 'life',
        justRecorded: justRecorded,
      );

  const receipt = '记下了 · 想接着说就点「提问」';

  testWidgets('刚提交的记录卡：显示如实回执（不再让用户觉得「它没接」）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(420, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: FeedCard(data: card(justRecorded: true))),
    ));

    expect(find.text(receipt), findsOneWidget);
  });

  testWidgets('普通/历史记录卡：不显示回执（刷新后自然消失，不打扰翻历史）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(420, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: FeedCard(data: card(justRecorded: false))),
    ));

    expect(find.text(receipt), findsNothing);
  });

  testWidgets('转成对话后：回执消失（copyWith 会保留标记，故不能只靠标记）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(420, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    // 模拟：记录卡被点「提问」→ mode=chatting + turns 非空，而 justRecorded 仍为 true
    // （copyWith 不传该字段即保留原值——这是自查发现的残留路径）
    final c = card(justRecorded: true).copyWith(
      mode: CardMode.chatting,
      turns: [ConversationTurn(isUser: true, text: '接着聊刚才那句', time: '10:01')],
    );

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: FeedCard(data: c)),
    ));

    expect(find.text(receipt), findsNothing);
  });

}
