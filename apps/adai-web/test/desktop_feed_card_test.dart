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
    expect(find.text('提问'), findsOneWidget);
  });

  testWidgets('image 卡渲染内容 + 提问按钮（L4 图片可追问）', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: 'img1', type: FeedCardType.record, time: '14:00', content: '持仓截图',
      summary: '持仓截图：浦发银行', intent: IntentType.log,
      mediaUrl: 'http://test/api/v1/records/media/img1',
    ));
    // 图片卡（log）显示内容 + 提问按钮（追问入口，与普通记录一致）
    expect(find.text('持仓截图'), findsOneWidget);
    expect(find.text('提问'), findsOneWidget);
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
    expect(find.text('结束'), findsOneWidget);
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
    await tester.tap(find.text('提问'));
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
    await tester.tap(find.text('结束'));
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

  // ── B9-3/4 + B9-5（2026-08-23，P1-推送1 根因修复后回归）：push 卡标题/徽章/确认按钮 ──

  testWidgets('push 卡「今日操作确认」渲染确认按钮 + 尾盘橙徽章', (tester) async {
    bool confirmed = false;
    await pumpCard(tester, FeedCardData(
      id: 'p1', type: FeedCardType.push, time: '15:15', date: '08-23',
      content: '📋 今日操作汇总\n· 京东方A 卖出 5300 股 @6.10',
      pushTitle: '今日操作确认', // 后端标题透传后真实到达
      onConfirmTradeLog: () => confirmed = true,
    ));
    // B9-3：确认按钮渲染（原后端丢标题 → 判定落空永不渲染）
    expect(find.text('确认并入账'), findsOneWidget);
    // B9-5：今日操作确认 → 尾盘建议橙徽章（不再落 default 灰）
    expect(find.text('尾盘建议'), findsOneWidget);
    await tester.tap(find.text('确认并入账'));
    expect(confirmed, true);
  });

  testWidgets('push 卡「放飞提示」（gain）专属徽章不落灰', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: 'p2', type: FeedCardType.push, time: '14:35', date: '08-23',
      content: '📈 贵州茅台 今日涨 5.2%，关注放飞条件',
      pushTitle: '放飞提示',
    ));
    // B9-5：gain 专属徽章（原 default 灰「行情」）
    expect(find.text('放飞提示'), findsOneWidget);
    expect(find.text('行情'), findsNothing);
  });

  testWidgets('push 卡未知标题兜底「行情」灰徽章', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: 'p3', type: FeedCardType.push, time: '14:00', date: '08-23',
      content: '大盘异动',
      pushTitle: '未知类型', // 未匹配任何分支 → default 灰
    ));
    expect(find.text('行情'), findsOneWidget);
  });

  testWidgets('push 卡「忽略」按钮触发 onDismiss（B10-3）', (tester) async {
    bool dismissed = false;
    await pumpCard(tester, FeedCardData(
      id: 'p4', type: FeedCardType.push, time: '14:00', date: '08-23',
      content: '止损预警',
      pushTitle: '止损预警',
      onDismiss: () => dismissed = true,
    ));
    expect(find.text('忽略'), findsOneWidget);
    await tester.tap(find.text('忽略'));
    expect(dismissed, true);
  });

  testWidgets('push 卡确认+忽略按钮并存（今日操作确认）', (tester) async {
    await pumpCard(tester, FeedCardData(
      id: 'p5', type: FeedCardType.push, time: '15:15', date: '08-23',
      content: '📋 今日操作汇总',
      pushTitle: '今日操作确认',
      onConfirmTradeLog: () {},
      onDismiss: () {},
    ));
    expect(find.text('确认并入账'), findsOneWidget);
    expect(find.text('忽略'), findsOneWidget);
  });
}
