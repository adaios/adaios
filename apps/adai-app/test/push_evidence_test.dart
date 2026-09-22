import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:adai_app/utils/push_evidence.dart';
import 'package:adai_app/widgets/feed_card.dart';

/// RFC 20260922 C 批 C3：决策推送卡的「一句话 + 可折叠四要素」。
///
/// 两件事必须钉住：
/// 1. **默认只露结论**（用户要的是「决策时点对我说一句话」，一屏堆满四要素又变回报表）；
/// 2. **依据一点就到**（「尤其给我铁证」），且**没有四要素的推送行为不变**（旧形态/行情/确认卡）。
void main() {
  const tailPush = '''
📉 尾盘卖点
按你 09-19 的账（持仓 2 只，1 只要看一眼）：
· 贵州茅台（600519） 现价 1370（今日 -2.1%） → 清仓参考（R66）
  ① 你的历史：你过去 8 次在「+5~10%」了结，7 次盈利、平均 +7.1%
  ② 证据：成本 1400 · 占比 96.5% · 止损 1380 · 持有 100 股
  ③ 规则：《止损位跌破即离场》R66 原文「现价跌破你设的止损位 → 按纪律离场，不扛单。」
  ④ 位置：这笔若现在了结，约 +6.2%（未扣手续费）
另外 1 只没有触发你的卖出条件，按计划拿着。''';

  group('splitPushContent（纯解析）', () {
    test('四要素正文：结论与依据分开，依据按原顺序', () {
      final parts = splitPushContent(tailPush);
      expect(parts.hasEvidence, isTrue);
      expect(parts.targetCount, 1);
      expect(parts.evidence.length, 4);
      expect(parts.evidence.first, startsWith('① 你的历史：'));
      expect(parts.evidence.last, startsWith('④ 位置：'));
      // 结论里不得残留任何依据行，但结论行（含逐票结论与收尾句）必须在
      expect(parts.head, isNot(contains('①')));
      expect(parts.head, contains('按你 09-19 的账'));
      expect(parts.head, contains('→ 清仓参考（R66）'));
      expect(parts.head, contains('另外 1 只没有触发你的卖出条件'));
    });

    test('两票时 targetCount 数对（按 ① 计数）', () {
      const two = '📋 早盘计划\n今天有 2 只进了你的条件——\n'
          '· 亨通光电（600487） 昨收 18.42（数据到 2026-09-19）\n  ① 你的历史：…\n  ② 证据：…\n'
          '· 中国稀土（000831） 昨收 53.30（数据到 2026-09-19）\n  ① 你的历史：…\n  ② 证据：…';
      final parts = splitPushContent(two);
      expect(parts.targetCount, 2);
      expect(parts.evidence.length, 4);
    });

    test('缩进不敏感（两个空格 / 四个空格 / 无缩进都认）', () {
      final parts = splitPushContent('结论行\n① 你的历史：…\n    ② 证据：…');
      expect(parts.hasEvidence, isTrue);
      expect(parts.evidence.length, 2);
      expect(parts.head, '结论行');
    });

    test('没有四要素 → 原样全文（旧形态推送行为不变）', () {
      const legacy = '⚠️ 贵州茅台现价 1370 已跌破你的止损位 1380——按纪律（R66）该清仓了。';
      final parts = splitPushContent(legacy);
      expect(parts.hasEvidence, isFalse);
      expect(parts.head, legacy);
    });

    test('空正文 / 只有依据没有结论 → 不炸、不渲染空白', () {
      expect(splitPushContent('').hasEvidence, isFalse);
      final onlyEvidence = splitPushContent('① 你的历史：…\n② 证据：…');
      expect(onlyEvidence.hasEvidence, isFalse, reason: '只有依据时应原样返回全文，而不是把卡片渲染成空白');
      expect(onlyEvidence.head, contains('① 你的历史'));
    });
  });

  group('推送卡渲染', () {
    Widget wrap(FeedCardData data) =>
        MaterialApp(home: Scaffold(body: SingleChildScrollView(child: FeedCard(data: data))));

    FeedCardData push(String content, {String title = '尾盘卖点'}) => FeedCardData(
          id: 'push_1', type: FeedCardType.push, time: '14:50', date: '09-22',
          content: content, pushTitle: title,
        );

    testWidgets('默认只露一句话：结论可见、四要素收起', (tester) async {
      await tester.pumpWidget(wrap(push(tailPush)));

      expect(find.textContaining('按你 09-19 的账'), findsOneWidget);
      expect(find.textContaining('→ 清仓参考（R66）'), findsOneWidget);
      expect(find.textContaining('① 你的历史'), findsNothing, reason: '默认不得铺开依据');
      expect(find.text('看依据（1 只 · 四要素）'), findsOneWidget);
    });

    testWidgets('点「看依据」展开四要素，再点收起', (tester) async {
      await tester.pumpWidget(wrap(push(tailPush)));

      await tester.tap(find.byKey(const ValueKey('push_evidence_toggle')));
      await tester.pumpAndSettle();

      expect(find.textContaining('① 你的历史'), findsOneWidget);
      expect(find.textContaining('③ 规则：《止损位跌破即离场》R66 原文'), findsOneWidget);
      expect(find.text('收起依据'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('push_evidence_toggle')));
      await tester.pumpAndSettle();
      expect(find.textContaining('① 你的历史'), findsNothing);
      expect(find.text('看依据（1 只 · 四要素）'), findsOneWidget);
    });

    testWidgets('没有四要素的推送（行情异动）全文照显、不出折叠入口', (tester) async {
      const alert = '⚠️ 京东方A 现价 4.80 已跌破你的止损位 4.90——按纪律（R66）该清仓了。';
      await tester.pumpWidget(wrap(push(alert, title: '止损预警')));

      expect(find.textContaining('已跌破你的止损位'), findsOneWidget);
      expect(find.text('看依据（1 只 · 四要素）'), findsNothing);
      expect(find.byKey(const ValueKey('push_evidence_toggle')), findsNothing);
    });
  });
}
