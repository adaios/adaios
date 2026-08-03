import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:adai_web/models/feed_models.dart';
import 'package:adai_web/widgets/desktop_feed_card.dart';

void main() {
  Future<void> pumpCard(WidgetTester tester, FeedCardData data, {VoidCallback? onAsk, VoidCallback? onEnd}) {
    return tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: DesktopFeedCard(data: data, onAsk: onAsk, onEnd: onEnd),
        ),
      ),
    ));
  }

  testWidgets('idle log 卡渲染内容 + ask', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: '1', type: FeedCardType.record, time: '14:00', content: 'buy stock',
      intent: IntentType.log,
    ));
    expect(find.text('buy stock'), findsOneWidget);
    expect(find.text('ask'), findsOneWidget);
  });

  testWidgets('chatting 卡渲染对话 + end', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: '1', type: FeedCardType.record, time: '14:00', content: 'weather?',
      turns: [
        ConversationTurn(isUser: true, text: 'weather?', time: '14:00'),
        ConversationTurn(isUser: false, text: 'sunny', time: '14:01'),
      ],
      mode: CardMode.chatting, intent: IntentType.question,
    ));
    expect(find.text('weather?'), findsOneWidget);
    expect(find.text('sunny'), findsOneWidget);
    expect(find.text('end'), findsOneWidget);
  });

  testWidgets('ended 卡渲染总结 + 标签', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: '1', type: FeedCardType.record, time: '14:00', content: 'weather?',
      summary: 'chat about weather', tags: ['weather'],
      mode: CardMode.ended, intent: IntentType.question,
    ));
    expect(find.text('chat about weather'), findsOneWidget);
    expect(find.text('weather'), findsOneWidget);
  });

  testWidgets('ask 回调触发', (tester) async {
    bool asked = false;
    await pumpCard(tester,
        FeedCardData(id: '1', type: FeedCardType.record, time: '14:00', content: 'hi'),
        onAsk: () => asked = true);
    await tester.tap(find.text('ask'));
    expect(asked, true);
  });

  testWidgets('end 回调触发（chatting 态）', (tester) async {
    bool ended = false;
    await pumpCard(tester,
        FeedCardData(
          id: '1', type: FeedCardType.record, time: '14:00', content: 'hi',
          turns: [ConversationTurn(isUser: true, text: 'hi', time: '14:00')],
          mode: CardMode.chatting, intent: IntentType.question,
        ),
        onEnd: () => ended = true);
    await tester.tap(find.text('end'));
    expect(ended, true);
  });

  testWidgets('action 卡渲染待办 + 完成按钮', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: 'a1', type: FeedCardType.action, time: '14:00', content: '交房租',
    ));
    expect(find.text('待办'), findsOneWidget);
    expect(find.text('完成'), findsOneWidget);
  });

  testWidgets('market 卡渲染行情', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: 'm1', type: FeedCardType.market, time: '15:00', content: '上证指数 3200 +0.5%',
    ));
    expect(find.text('行情'), findsOneWidget);
    expect(find.text('上证指数 3200 +0.5%'), findsOneWidget);
  });

  testWidgets('普通卡时间竖列显示 date + time', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: '1', type: FeedCardType.record, time: '14:00', date: '08-03', content: 'buy stock',
    ));
    expect(find.text('08-03'), findsOneWidget);
    expect(find.text('14:00'), findsOneWidget);
  });

  testWidgets('action 卡显示 date + time', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: 'a1', type: FeedCardType.action, time: '09:05', date: '08-03', content: '交房租',
    ));
    expect(find.text('08-03  09:05'), findsOneWidget);
  });
}
