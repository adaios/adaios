package com.adaiadai.core.domain.trading.cases;

import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CaseFeatureExtractorTest — 案例特征提取（2026-08-30 第四阶段环 2）。
 * <p>
 * 构造确定性 K 线序列验证：回撤/量比/均线关系/黄白线态/盘整/破前高 + 后验窗口。
 */
class CaseFeatureExtractorTest {

    /** 构造 70 根日 K：前 20 根上涨至 10 → 回撤至 8（-20%）→ 缩量横盘 → buyDate 后小跌再大涨。 */
    private List<Candle> buildCandles() {
        List<Candle> list = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 5);
        // 前 30 根：逐步上涨 5 → 10（峰值在 idx 29）
        for (int i = 0; i < 30; i++) {
            double close = 5 + 5.0 * i / 29;
            list.add(candle(start.plusDays(i), close, 1000));
        }
        // idx 30-39：回撤 10 → 8（-20%）
        for (int i = 0; i < 10; i++) {
            double close = 10 - 2.0 * i / 9;
            list.add(candle(start.plusDays(30 + i), close, 900));
        }
        // idx 40-49：缩量横盘（振幅 2% < 3% 盘整阈值；idx 44-46 放量 900、47-49 缩量 300
        // → 3 日均量 300 / 5 日均量 540 ≈ 0.56 缩量）；buyDate = idx 49
        for (int i = 0; i < 10; i++) {
            LocalDate d = start.plusDays(40 + i);
            double close = 8.0 + (i % 3) * 0.05;
            list.add(new Candle(d, close * 0.995, close * 1.01, close * 0.99, close,
                    i >= 4 && i <= 6 ? 900 : 300));
        }
        // idx 50-69：小跌至 7.8 再大涨至 10（后验 +5/+10 收益、最大回撤、破止损判定）
        for (int i = 0; i < 5; i++) {
            list.add(candle(start.plusDays(50 + i), 8.0 - 0.04 * i, 400));
        }
        for (int i = 0; i < 15; i++) {
            double close = 7.8 + 2.2 * (i + 1) / 15.0;
            list.add(candle(start.plusDays(55 + i), close, 1500));
        }
        return list;
    }

    private Candle candle(LocalDate date, double close, double volume) {
        double open = close * 0.99, high = close * 1.02, low = close * 0.98;
        return new Candle(date, open, high, low, close, volume);
    }

    @Test
    void extract_typicalPullback_hasAllFeatures() {
        List<Candle> candles = buildCandles();
        LocalDate buyDate = candles.get(49).date();
        CaseRecord.CaseFeatures f = CaseFeatureExtractor.extract(candles, buyDate);

        assertNotNull(f, "特征不应为 null");
        // 回撤：前 20 根最高收盘 = idx 29 的 10.0，buy 收盘 ≈ 8.05 → ≈ 19.5%
        assertTrue(f.drawdownFromHighPct() > 15 && f.drawdownFromHighPct() < 22,
                "回撤应 ≈20%，实际 " + f.drawdownFromHighPct());
        // 量比：近 3 日均量 300 / 近 5 日均量 (300*3+900+900)/5=540 → ≈0.56（缩量）
        assertTrue(f.volumeShrinkRatio() < 0.9, "应为缩量，实际 " + f.volumeShrinkRatio());
        // 指标非空
        assertNotNull(f.kdjJ(), "KDJ.J 不应为 null");
        assertNotNull(f.macdHist(), "MACD 柱不应为 null");
        // 均线关系/黄白线态为合法枚举
        assertTrue(f.maRelation() != null && !f.maRelation().isBlank());
        assertTrue(List.of("touch", "near", "above", "below").contains(f.yellowLineState()));
        // 盘整天数 ≥ 1（缩量横盘段）
        assertTrue(f.sidewaysDays() >= 5, "横盘段应计数，实际 " + f.sidewaysDays());
        // 未破前高（close 8 < peak 10）
        assertEquals(false, f.breakoutFromHigh());
    }

    @Test
    void verify_afterWindow_returnsReturnsAndDrawdown() {
        List<Candle> candles = buildCandles();
        LocalDate buyDate = candles.get(49).date();
        CaseRecord.CaseVerify v = CaseFeatureExtractor.verify(candles, buyDate);

        assertNotNull(v, "后验不应为 null");
        // +5 日：7.8/8.05 - 1 ≈ -3.1%；+10 日：≈ +8%
        assertTrue(v.plus5dReturnPct() != null && v.plus5dReturnPct() < 0,
                "+5 应小幅为负，实际 " + v.plus5dReturnPct());
        assertTrue(v.plus10dReturnPct() != null && v.plus10dReturnPct() > 0,
                "+10 应转正，实际 " + v.plus10dReturnPct());
        // 最大回撤：最低 low ≈ 7.8×0.98 → ≈ -4.5%（未破 -7% 止损）
        assertTrue(v.maxDrawdownAfterBuyPct() != null && v.maxDrawdownAfterBuyPct() > -5.0,
                "最大回撤应 > -5%，实际 " + v.maxDrawdownAfterBuyPct());
        assertEquals(false, v.stopLossHit());
    }

    @Test
    void verify_windowTooShort_returnsNulls() {
        // 仅 2 根（buyDate 在最后一根）：+5/+10/最大回撤均无后续数据 → null，标注仍可成功
        List<Candle> candles = buildCandles().subList(48, 50);
        LocalDate buyDate = candles.get(1).date();
        CaseRecord.CaseVerify v = CaseFeatureExtractor.verify(candles, buyDate);
        assertNotNull(v);
        assertNull(v.plus5dReturnPct());
        assertNull(v.plus10dReturnPct());
        assertNull(v.maxDrawdownAfterBuyPct());
    }

    @Test
    void extract_buyDateNotInCandles_returnsNull() {
        List<Candle> candles = buildCandles();
        assertNull(CaseFeatureExtractor.extract(candles, LocalDate.of(2020, 1, 1)),
                "buyDate 不在窗口 → null（调用方报「无交易数据」）");
        assertNull(CaseFeatureExtractor.verify(candles, LocalDate.of(2020, 1, 1)));
    }
}
