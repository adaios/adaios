package com.adaiadai.core.domain.trading.cases;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CaseConsensusTest — 完美买点共识画像（2026-08-30：数据驱动判定，核心价值）。
 * <p>
 * 覆盖：案例 &lt;5 → null、分位数区间、逐维命中、维度样本不足跳过、null 特征跳过、
 * 命中数统计。
 */
class CaseConsensusTest {

    private CaseRecord caseOf(double drawdown, double volRatio, Double kdjJ, double distMa60,
                              Double macdHist, int sideways) {
        return new CaseRecord("c", "000725", "京东方A", LocalDate.of(2026, 8, 3), "B1",
                null, List.of(), LocalDateTime.now(), new CaseRecord.CaseWindow(60, 30),
                new CaseRecord.CaseFeatures(drawdown, volRatio, kdjJ, true, macdHist, true,
                        "x", distMa60, "near", false, sideways, false),
                new CaseRecord.CaseVerify(null, null, null, false),
                CaseRecord.CaseAiInsight.empty());
    }

    @Test
    void belowMinCases_returnsNull() {
        List<CaseRecord> cases = List.of(
                caseOf(50, 0.6, 8.0, 1.5, -0.3, 5),
                caseOf(45, 0.7, 10.0, 2.0, -0.2, 4));
        assertNull(CaseConsensus.buildProfile(cases), "案例 <5 → 共识不可用");
    }

    @Test
    void profile_rangesFromPercentiles() {
        // 5 案例：回撤 40,45,50,55,60 → 25分位=45, 75分位=55
        List<CaseRecord> cases = new ArrayList<>();
        for (double d : new double[]{40, 45, 50, 55, 60}) {
            cases.add(caseOf(d, 0.6, 8.0, 1.5, -0.3, 5));
        }
        List<CaseConsensus.Range> profile = CaseConsensus.buildProfile(cases);
        assertNotNull(profile);
        CaseConsensus.Range drawdown = profile.stream()
                .filter(r -> r.feature().equals("drawdownFromHighPct")).findFirst().orElseThrow();
        assertEquals(45.0, drawdown.low(), 0.001, "25 分位");
        assertEquals(55.0, drawdown.high(), 0.001, "75 分位");
    }

    @Test
    void evaluate_hitsWithinRange() {
        List<CaseRecord> cases = new ArrayList<>();
        for (double d : new double[]{40, 45, 50, 55, 60}) {
            cases.add(caseOf(d, 0.6, 8.0, 1.5, -0.3, 5));
        }
        List<CaseConsensus.Range> profile = CaseConsensus.buildProfile(cases);
        // 当前形态回撤 50（区间内）→ 该维命中
        CaseRecord.CaseFeatures query = caseOf(50, 0.9, 30.0, 8.0, 1.5, 0).features();
        CaseConsensus.ConsensusResult result = CaseConsensus.evaluate(query, profile);
        assertNotNull(result);
        assertTrue(result.hitCount() >= 1, "回撤 50 在 45-55 内应命中");
        CaseConsensus.Hit drawdownHit = result.hits().stream()
                .filter(h -> h.feature().equals("drawdownFromHighPct")).findFirst().orElseThrow();
        assertTrue(drawdownHit.hit());
        assertEquals(50.0, drawdownHit.value(), 0.001);
    }

    @Test
    void evaluate_outsideRange_misses() {
        List<CaseRecord> cases = new ArrayList<>();
        for (double d : new double[]{40, 45, 50, 55, 60}) {
            cases.add(caseOf(d, 0.6, 8.0, 1.5, -0.3, 5));
        }
        List<CaseConsensus.Range> profile = CaseConsensus.buildProfile(cases);
        // 当前形态回撤 90（区间外）
        CaseRecord.CaseFeatures query = caseOf(90, 0.6, 8.0, 1.5, -0.3, 5).features();
        CaseConsensus.ConsensusResult result = CaseConsensus.evaluate(query, profile);
        CaseConsensus.Hit drawdownHit = result.hits().stream()
                .filter(h -> h.feature().equals("drawdownFromHighPct")).findFirst().orElseThrow();
        assertTrue(!drawdownHit.hit(), "回撤 90 超出 45-55 应不命中");
    }

    @Test
    void nullFeature_skipsDimension() {
        // 所有案例 KDJ null → kdjJ 维不参与画像
        List<CaseRecord> cases = new ArrayList<>();
        for (double d : new double[]{40, 45, 50, 55, 60}) {
            cases.add(caseOf(d, 0.6, null, 1.5, -0.3, 5));
        }
        List<CaseConsensus.Range> profile = CaseConsensus.buildProfile(cases);
        assertNotNull(profile);
        assertTrue(profile.stream().noneMatch(r -> r.feature().equals("kdjJ")),
                "样本不足维度应跳过");
    }

    @Test
    void percentile_linearInterpolation() {
        assertEquals(5.5, CaseConsensus.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), 0.5), 0.001);
        assertEquals(1.0, CaseConsensus.percentile(List.of(1.0), 0.5), 0.001);
        assertEquals(2.5, CaseConsensus.percentile(List.of(1.0, 2.0, 3.0, 4.0), 0.5), 0.001);
    }

    // ── 2026-08-31 双轨方案 ──

    /** 指定 buyType 的案例（回撤为主维，便于断言分组区间）。 */
    private CaseRecord caseOf(String type, double drawdown, double volRatio, Double kdjJ,
                              double distMa60, Double macdHist, int sideways) {
        return new CaseRecord("c-" + type + "-" + drawdown, "000725", "京东方A",
                LocalDate.of(2026, 8, 3), type,
                null, List.of(), LocalDateTime.now(), new CaseRecord.CaseWindow(60, 30),
                new CaseRecord.CaseFeatures(drawdown, volRatio, kdjJ, true, macdHist, true,
                        "x", distMa60, "near", false, sideways, false),
                new CaseRecord.CaseVerify(null, null, null, false),
                CaseRecord.CaseAiInsight.empty());
    }

    @Test
    void buildProfile_byType_rangesAreIndependent() {
        // B1：回撤 30-50；B2：回撤 5-10 —— 混合画像会被拉宽，分型画像各自收敛
        List<CaseRecord> cases = new ArrayList<>();
        for (double d : new double[]{30, 35, 40, 45, 50}) cases.add(caseOf("B1", d, 0.8, 5.0, 4.0, -0.2, 4));
        for (double d : new double[]{5, 6, 7, 8, 10}) cases.add(caseOf("B2", d, 1.1, 30.0, 14.0, 0.1, 2));

        List<CaseConsensus.Range> b1 = CaseConsensus.buildProfile(cases, "B1");
        List<CaseConsensus.Range> b2 = CaseConsensus.buildProfile(cases, "B2");
        assertNotNull(b1);
        assertNotNull(b2);
        double b1low = rangeOf(b1, "drawdownFromHighPct").low();
        double b1high = rangeOf(b1, "drawdownFromHighPct").high();
        double b2low = rangeOf(b2, "drawdownFromHighPct").low();
        double b2high = rangeOf(b2, "drawdownFromHighPct").high();
        assertEquals(35.0, b1low, 0.001);
        assertEquals(45.0, b1high, 0.001);
        assertEquals(6.0, b2low, 0.001);
        assertEquals(8.0, b2high, 0.001);
        // 分型区间不重叠（判别力恢复——混合画像 [5,50] 会被稀释）
        assertTrue(b1low > b2high, "B1/B2 回撤区间应分离");
    }

    @Test
    void buildProfile_allType_excludesFailed() {
        List<CaseRecord> cases = new ArrayList<>();
        for (double d : new double[]{30, 35, 40, 45, 50}) cases.add(caseOf("B1", d, 0.8, 5.0, 4.0, -0.2, 4));
        for (double d : new double[]{60, 61, 62, 63, 64}) cases.add(caseOf(CaseRecord.TYPE_FAILED, d, 0.9, 3.0, 2.0, -0.1, 3));
        // 全量口径排除负样本 → 画像只反映正样本
        List<CaseConsensus.Range> all = CaseConsensus.buildProfile(cases);
        assertNotNull(all);
        assertEquals(45.0, rangeOf(all, "drawdownFromHighPct").high(), 0.001,
                "负样本回撤 60-64 不应进入全量画像");
    }

    @Test
    void buildProfile_failedType_failedProfile() {
        List<CaseRecord> cases = new ArrayList<>();
        for (double d : new double[]{30, 35, 40, 45, 50}) cases.add(caseOf("B1", d, 0.8, 5.0, 4.0, -0.2, 4));
        for (double d : new double[]{60, 61, 62, 63, 64}) cases.add(caseOf(CaseRecord.TYPE_FAILED, d, 0.9, 3.0, 2.0, -0.1, 3));
        List<CaseConsensus.Range> failed = CaseConsensus.buildProfile(cases, CaseRecord.TYPE_FAILED);
        assertNotNull(failed);
        assertEquals(61.0, rangeOf(failed, "drawdownFromHighPct").low(), 0.001);
        assertEquals(63.0, rangeOf(failed, "drawdownFromHighPct").high(), 0.001);
    }

    private CaseConsensus.Range rangeOf(List<CaseConsensus.Range> profile, String feature) {
        return profile.stream().filter(r -> r.feature().equals(feature)).findFirst().orElseThrow();
    }
}
