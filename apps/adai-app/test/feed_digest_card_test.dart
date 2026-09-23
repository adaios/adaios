import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:adai_app/widgets/feed_card.dart';

/// 2026-09-23 分享追踪批：Feed 里那句「交给阿呆的东西」的回话。
///
/// 这一支最容易悄悄坏掉——前端按 type 字符串分派，后端改了 type 就没人渲染（卡片直接落回普通
/// 记录样式）。所以钉住三件事：digest 能渲染出内容、徽章说的是状态、别的类型不受影响。
void main() {
  Future<void> pump(WidgetTester tester, FeedCardData data) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(child: FeedCard(data: data))),
    ));
  }

  FeedCardData digestData(String content, List<String> tags) => FeedCardData(
        id: 'dtask_1',
        type: FeedCardType.digest,
        time: '23:06',
        date: '2026-09-23',
        content: content,
        tags: tags,
      );

  testWidgets('整理好的那条：正文说出卡片标题、徽章「已读好」', (tester) async {
    await pump(tester, digestData('《央行2025Q4货币政策报告要点》', ['学习', '已读好']));

    expect(find.text('《央行2025Q4货币政策报告要点》'), findsOneWidget);
    expect(find.text('已读好'), findsOneWidget, reason: '徽章随状态变，不是写死的「整理」');
  });

  testWidgets('正在读的那条：徽章「正在读」', (tester) async {
    await pump(tester, digestData('mp.weixin.qq.com上的一篇——先把原文抓下来', ['学习', '正在读']));

    expect(find.text('正在读'), findsOneWidget);
  });

  testWidgets('没读成的那条：原因如实显示', (tester) async {
    await pump(tester, digestData('这个链接我读不出来', ['学习', '没读成']));

    expect(find.text('没读成'), findsOneWidget);
    expect(find.text('这个链接我读不出来'), findsOneWidget);
  });

  testWidgets('行情条不受影响：仍走自己的徽章（digest 分支没有误伤同形的简单卡）', (tester) async {
    await pump(tester, FeedCardData(
      id: 'm1', type: FeedCardType.market, time: '10:00', content: '上证 3200 +0.5%'));

    expect(find.text('行情'), findsOneWidget);
    expect(find.text('上证 3200 +0.5%'), findsOneWidget);
  });
}
