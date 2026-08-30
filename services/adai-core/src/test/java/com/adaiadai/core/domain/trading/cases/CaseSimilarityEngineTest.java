package com.adaiadai.core.domain.trading.cases;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CaseSimilarityEngineTest — 相似度引擎（2026-08-30 第四阶段环 4：判定当下）。
 * <p>
 * 验证：同形态案例相似度高、不同形态低、归一化钳制、Top N 排序、空库/无特征跳过。
 */
class CaseSimilarityEngineTest {

    private CaseRecord.CaseFeatures features(double drawdown, double volRatio, Double kdjJ,
                                             boolean gold, boolean macdGold, double distMa60,
                                             int sideways, boolean breakout, boolean whiteAbove) {
        return new CaseRecord.CaseFeatures(drawdown, volRatio, kdjJ, gold,
                -0.3, macdGold, "close_above_ma20_below_ma60", distMa60,
                distMa60 < 2 ? "near" : "below", whiteAbove, sideways, breakout);
    }

    private CaseRecord record(String id, CaseRecord.CaseFeatures f) {
        return new CaseRecord(id, "000725", "京东方A", LocalDate.of(2026, 8, 3), "B1",
                null, List.of(), LocalDateTime.now(), new CaseRecord.CaseWindow(60, 30),
                f, new CaseRecord.CaseVerify(18.2, 24.5, -2.1, false),
                CaseRecord.CaseAiInsight.empty());
    }

    @Test
    void sameFeatures_similarityIs100() {
        CaseRecord.CaseFeatures f = features(52.3, 0.62, 8.4, true, true, 1.8, 5, false, false);
        double sim = CaseSimilarityEngine.similarity(
                CaseFeatureNormalizer.toVector(f), CaseFeatureNormalizer.toVector(f));
        assertEquals(100.0, sim, 0.001);
    }

    @Test
    void oppositeFeatures_similarityLow() {
        // 完全相反：回撤 0 vs 100、量比 3 vs 0、KDJ 100 vs 0、距 60 日线 0 vs 20、盘整 10 vs 0、信号 0 vs 4
        CaseRecord.CaseFeatures a = features(0, 3.0, 100.0, false, false, 0.0, 0, false, false);
        CaseRecord.CaseFeatures b = features(100, 0.0, 0.0, true, true, 20.0, 10, true, true);        double sim = CaseSimilarityEngine.similarity(
                CaseFeatureNormalizer.toVector(a), CaseFeatureNormalizer.toVector(b));
        assertTrue(sim < 30, "完全相反形态相似度应很低，实际 " + sim);
    }

    @Test
    void nullKdjAndMacd_neutralValue() {
        CaseRecord.CaseFeatures f = new CaseRecord.CaseFeatures(50, 1.5, null, false,
                null, false, "close_above_ma20_below_ma60", 10, "near", false, 5, false);
        double[] v = CaseFeatureNormalizer.toVector(f);
        assertEquals(0.5, v[2], 0.001, "KDJ null → 中性 0.5");
        assertEquals(0.5, v[3], 0.001, "MACD null → 中性 0.5");
    }

    @Test
    void normalization_clampedTo01() {
        CaseRecord.CaseFeatures f = features(999, 9.9, 300.0, true, true, 99, 99, true, true);
        double[] v = CaseFeatureNormalizer.toVector(f);
        for (double x : v) {
            assertTrue(x >= 0 && x <= 1, "归一化应钳制在 0-1：" + x);
        }
    }

    @Test
    void topN_sortedBySimilarityDesc_skipsNullFeatures() {
        CaseRecord.CaseFeatures query = features(52.3, 0.62, 8.4, true, true, 1.8, 5, false, false);
        CaseRecord near = record("near", features(55.0, 0.60, 10.0, true, true, 2.0, 4, false, false));
        CaseRecord far = record("far", features(10.0, 2.5, 80.0, false, false, 15.0, 0, true, true));
        CaseRecord noFeature = new CaseRecord("none", "600519", "茅台", LocalDate.of(2026, 7, 1),
                "B2", null, List.of(), LocalDateTime.now(), new CaseRecord.CaseWindow(60, 30),
                null, null, CaseRecord.CaseAiInsight.empty());

        List<CaseSimilarityEngine.MatchResult> top =
                CaseSimilarityEngine.topN(List.of(far, noFeature, near), query, 5);

        assertEquals(2, top.size(), "无特征案例应被跳过");
        assertEquals("near", top.get(0).caseRecord().id(), "相似度降序第一应为 near");
        assertTrue(top.get(0).similarityPercent() > top.get(1).similarityPercent());
    }

    @Test
    void topN_emptyLibrary_returnsEmpty() {
        assertEquals(0, CaseSimilarityEngine.topN(List.of(),
                features(50, 1, 20.0, false, false, 5, 2, false, false), 5).size());
    }

    @Test
    void topN_limitRespected() {
        List<CaseRecord> cases = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> record("c" + i, features(50 + i, 1.0, 20.0, false, false, 5, 2, false, false)))
                .toList();
        CaseRecord.CaseFeatures query = features(55, 1.0, 20.0, false, false, 5, 2, false, false);
        assertEquals(5, CaseSimilarityEngine.topN(cases, query, 5).size());
    }
}
