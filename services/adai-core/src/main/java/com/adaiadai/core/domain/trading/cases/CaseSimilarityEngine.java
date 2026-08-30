package com.adaiadai.core.domain.trading.cases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CaseSimilarityEngine — 案例相似度引擎（2026-08-30 第四阶段环 4：判定当下）。
 * <p>
 * 度量：加权欧氏距离（权重表设计文档 §5.2，权重和为 1 → 距离 ∈ [0,1]）：
 * <pre>
 * weights = [回撤 0.25, 量比 0.20, KDJ.J 0.15, MACD 柱 0.10, 距 60 日线 0.15, 盘整 0.10, 信号 0.05]
 * distance = √(Σ wᵢ·(vᵢ−cᵢ)²)
 * similarity = (1 − distance) × 100%   （权重和=1 → 全异向量距离=1 → 相似度 0；全同=100%）
 * </pre>
 * 案例库为空 / 无特征 → 返回空（静默降级，不影响规则判定）。
 */
public final class CaseSimilarityEngine {

    /** 维度权重（和 = 1.0，距离上界 = 1.0）。 */
    public static final double[] WEIGHTS = {0.25, 0.20, 0.15, 0.10, 0.15, 0.10, 0.05};

    private CaseSimilarityEngine() {}

    /** 相似度（0-100%）：1 − 加权欧氏距离。向量维度必须 = DIM。 */
    public static double similarity(double[] query, double[] candidate) {
        if (query == null || candidate == null || query.length != WEIGHTS.length
                || candidate.length != WEIGHTS.length) {
            return 0.0;
        }
        double sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            double d = query[i] - candidate[i];
            sum += WEIGHTS[i] * d * d;
        }
        double distance = Math.sqrt(sum);
        return Math.max(0.0, Math.min(1.0, 1.0 - distance)) * 100.0;
    }

    /** 匹配结果（案例 + 相似度）。 */
    public record MatchResult(CaseRecord caseRecord, double similarityPercent) {}

    /** Top N 匹配（相似度降序）；空/无特征案例跳过；n ≤ 0 → 空。 */
    public static List<MatchResult> topN(List<CaseRecord> candidates, CaseRecord.CaseFeatures query, int n) {
        List<MatchResult> results = new ArrayList<>();
        if (candidates == null || query == null || n <= 0) return results;
        double[] q = CaseFeatureNormalizer.toVector(query);
        for (CaseRecord c : candidates) {
            if (c == null || c.features() == null) continue;
            double sim = similarity(q, CaseFeatureNormalizer.toVector(c.features()));
            results.add(new MatchResult(c, Math.round(sim * 100.0) / 100.0));
        }
        results.sort(Comparator.comparingDouble(MatchResult::similarityPercent).reversed());
        return results.size() > n ? results.subList(0, n) : results;
    }
}
