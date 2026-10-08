import 'package:flutter_test/flutter_test.dart';
import 'package:adai_app/utils/trading_verdict.dart';

// 2026-10-09（design-app §三 I-1 / I-2）：判断句与「与你的线的关系」的单测。
//
// 这两处是 app 端形态的第一步（先说结论），也是最容易「编话」的地方——
// 每个分支都必须钉死：**取不到就不说、不编成 0、也不写成「正常」**。

void main() {
  group('composeTradingVerdict · 判断句', () {
    test('常态：有持仓、没到线 → 一句最短结论', () {
      final v = composeTradingVerdict(const TradingVerdictInput(positionCount: 5));
      expect(v.headline, '持仓 5 只 · 不用动');
      expect(v.subline, isNull); // 无事不出小字
      expect(v.tone, TradingVerdictTone.ok);
    });

    test('破线：换句 + 小字点名', () {
      final v = composeTradingVerdict(const TradingVerdictInput(
        positionCount: 5,
        brokeStopCount: 1,
        brokeStopNames: ['云南锗业'],
      ));
      expect(v.headline, '持仓 5 只 · 1 只破了你的线');
      expect(v.subline, '破了你的线：云南锗业');
      expect(v.tone, TradingVerdictTone.attention);
    });

    test('到线（正向）：也可以是一句结论', () {
      final v = composeTradingVerdict(
          const TradingVerdictInput(positionCount: 3, reachedTargetCount: 2));
      expect(v.headline, '持仓 3 只 · 2 只到了你的线');
      expect(v.tone, TradingVerdictTone.attention);
    });

    test('账对不上：结论里明说，小字带后端原话', () {
      final v = composeTradingVerdict(const TradingVerdictInput(
        positionCount: 2,
        integrityIssue: true,
        integrityNote: '现金差 6,093.97',
      ));
      expect(v.headline, '持仓 2 只 · 不用动 · 账对不上');
      expect(v.subline, '账对不上：现金差 6,093.97');
    });

    test('账对不上但后端没给原话：说「还没算出来」，不编差额', () {
      final v = composeTradingVerdict(
          const TradingVerdictInput(positionCount: 2, integrityIssue: true));
      expect(v.subline, '账对不上，差多少我还没算出来');
    });

    test('账实「没取到」（null）→ 不显示、也不说没问题', () {
      final v = composeTradingVerdict(const TradingVerdictInput(positionCount: 2));
      expect(v.headline, '持仓 2 只 · 不用动');
      expect(v.subline, isNull);
    });

    test('多项异常并存：小字按 账 → 行情 → 待入账 → 点名 顺序拼', () {
      final v = composeTradingVerdict(const TradingVerdictInput(
        positionCount: 4,
        brokeStopCount: 1,
        brokeStopNames: ['紫金矿业'],
        integrityIssue: true,
        integrityNote: '落库持仓比派生多 200 股',
        marketIssue: true,
        pendingCandidates: 2,
      ));
      expect(v.subline,
          '账对不上：落库持仓比派生多 200 股 · 行情没取到，数字可能不是最新 · 2 笔等着你确认 · 破了你的线：紫金矿业');
    });

    test('持仓没取到（null）→ 换句，不当作 0 只', () {
      final v = composeTradingVerdict(const TradingVerdictInput(positionCount: null));
      expect(v.headline, '这次没取到你的持仓');
      expect(v.tone, TradingVerdictTone.unknown);
    });

    test('空账号（0 只）→ 说「还没有持仓」，不编结论', () {
      final v = composeTradingVerdict(const TradingVerdictInput(positionCount: 0));
      expect(v.headline, '还没有持仓');
      expect(v.tone, TradingVerdictTone.ok);
    });

    test('待入账笔数进小字（截图识别出的候选）', () {
      final v = composeTradingVerdict(
          const TradingVerdictInput(positionCount: 2, pendingCandidates: 3));
      expect(v.subline, '3 笔等着你确认');
      expect(v.tone, TradingVerdictTone.attention);
    });
  });

  group('readHoldLine · 与你的线的关系', () {
    test('没设止损 → 如实说「没设线」', () {
      final r = readHoldLine(currentPrice: 10, stopLossPrice: null);
      expect(r.state, HoldLineState.noLine);
      expect(r.text, '没设线');
    });

    test('止损为 0（未设）→ 同样「没设线」', () {
      expect(readHoldLine(currentPrice: 10, stopLossPrice: 0).text, '没设线');
    });

    test('现价取不到 → 不给假距离', () {
      final r = readHoldLine(currentPrice: null, stopLossPrice: 16.8);
      expect(r.state, HoldLineState.unknown);
      expect(r.text, '没取到现价');
    });

    test('破了止损', () {
      final r = readHoldLine(currentPrice: 16.0, stopLossPrice: 16.8);
      expect(r.state, HoldLineState.brokeStop);
      expect(r.text, '破了你的 16.80');
    });

    test('刚好等于止损也算破线（触线即提醒，不留缝）', () {
      expect(readHoldLine(currentPrice: 16.8, stopLossPrice: 16.8).state,
          HoldLineState.brokeStop);
    });

    test('在线上：给「离你的 X 还有 Y%」', () {
      final r = readHoldLine(currentPrice: 23.9, stopLossPrice: 23.0);
      expect(r.state, HoldLineState.aboveStop);
      expect(r.text, '离你的 23.00 还有 3.9%');
    });
  });
}
