package com.adaiadai.core.domain.trading.cases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CaseSimilarityEngine — 案例相似度引擎（2026-08-30 第四阶段环 4：判定当下）。
 * <p>
 * 度量：加权欧氏距离（权重和 = 1 → 距离 ∈ [0,1]）：
 * <pre>
 * weights = [回撤 0.25, 量比 0.20, KDJ.J 0.15, MACD 柱 0.10, 距 60 日线 0.15, 盘整 0.10, 信号 0.05]
 * distance = √(Σ wᵢ·(vᵢ−cᵢ)²)
 * similarity = (1 − distance) × 100%   （权重和=1 → 全异向量距离=1 → 相似度 0；全同=100%）
 * </pre>
 * 2026-08-31 双轨方案：topN 支持按类型过滤（B1/B2 各自匹配，负样本不参与正样本匹配），
 * 权重可配置（{@code adai.trading.case.sim-weights}，逗号分隔 7 个 double，默认下述）。
 * 案例库为空 / 无特征 → 返回空（静默降级，不影响规则判定）。
 */
public final class CaseSimilarityEngine {

    /** 默认维度权重（和 = 1.0，距离上界 = 1.0）；可配置覆盖（方案 §6）。 */
    public static final double[] DEFAULT_WEIGHTS = {0.25, 0.20, 0.15, 0.10, 0.15, 0.10, 0.05};

    private CaseSimilarityEngine() {}

    /** 解析配置权重字符串（"0.25,0.20,..."）；非法 → 默认权重（fail-safe，不阻塞判定）。 */
    public static double[] parseWeights(String csv) {
        if (csv == null || csv.isBlank()) return DEFAULT_WEIGHTS.clone();
        try {
            String[] parts = csv.trim().split("\\s*,\\s*");
            if (parts.length != DEFAULT_WEIGHTS.length) return DEFAULT_WEIGHTS.clone();
            double[] w = new double[parts.length];
            double sum = 0;
            for (int i = 0; i < parts.length; i++) {
                double v = Double.parseDouble(parts[i]);
                if (v < 0 || v > 1) return DEFAULT_WEIGHTS.clone();
                w[i] = v;
                sum += v;
            }
            // 权重和须为 1（距离上界语义）；容忍浮点误差
            if (Math.abs(sum - 1.0) > 1e-6) return DEFAULT_WEIGHTS.clone();
            return w;
        } catch (NumberFormatException e) {
            return DEFAULT_WEIGHTS.clone();
        }
    }

    /** 相似度（0-100%）：1 − 加权欧氏距离。向量维度必须 = DIM。默认权重。 */
    public static double similarity(double[] query, double[] candidate) {
        return similarity(query, candidate, DEFAULT_WEIGHTS);
    }

    /** 相似度（可配置权重）。 */
    public static double similarity(double[] query, double[] candidate, double[] weights) {
        if (query == null || candidate == null || query.length != DEFAULT_WEIGHTS.length
                || candidate.length != DEFAULT_WEIGHTS.length
                || weights == null || weights.length != DEFAULT_WEIGHTS.length) {
            return 0.0;
        }
        double sum = 0;
        for (int i = 0; i < DEFAULT_WEIGHTS.length; i++) {
            double d = query[i] - candidate[i];
            sum += weights[i] * d * d;
        }
        double distance = Math.sqrt(sum);
        return Math.max(0.0, Math.min(1.0, 1.0 - distance)) * 100.0;
    }

    /** 匹配结果（案例 + 相似度）。 */
    public record MatchResult(CaseRecord caseRecord, double similarityPercent) {}

    /** Top N 匹配（相似度降序）；空/无特征案例跳过；n ≤ 0 → 空。全量（不含负样本，向后兼容）。 */
    public static List<MatchResult> topN(List<CaseRecord> candidates, CaseRecord.CaseFeatures query, int n) {
        return topN(candidates, query, n, null, DEFAULT_WEIGHTS);
    }

    /** Top N（按类型过滤：type=null → 全量正样本；type=B1/B2 → 仅该类型；负样本恒不参与）。 */
    public static List<MatchResult> topN(List<CaseRecord> candidates, CaseRecord.CaseFeatures query,
                                         int n, String type) {
        return topN(candidates, query, n, type, DEFAULT_WEIGHTS);
    }

    /** Top N（类型过滤 + 可配置权重，2026-08-31 双轨方案完整形态）。 */
    public static List<MatchResult> topN(List<CaseRecord> candidates, CaseRecord.CaseFeatures query,
                                         int n, String type, double[] weights) {
        List<MatchResult> results = new ArrayList<>();
        if (candidates == null || query == null || n <= 0) return results;
        boolean queryFailed = CaseRecord.TYPE_FAILED.equals(type);
        double[] q = CaseFeatureNormalizer.toVector(query);
        for (CaseRecord c : candidates) {
            if (c == null || c.features() == null) continue;
            boolean isFailed = CaseRecord.TYPE_FAILED.equals(c.buyType());
            if (queryFailed) {
                // 失败画像查询：只匹配负样本（供 match 风险警示）
                if (!isFailed) continue;
            } else {
                // 正样本匹配：负样本恒不参与（防污染相似度参照系）
                if (isFailed) continue;
                if (type != null && !type.isBlank() && !type.equals(c.buyType())) continue;
            }
            double sim = similarity(q, CaseFeatureNormalizer.toVector(c.features()), weights);
            results.add(new MatchResult(c, Math.round(sim * 100.0) / 100.0));
        }
        results.sort(Comparator.comparingDouble(MatchResult::similarityPercent).reversed());
        return results.size() > n ? results.subList(0, n) : results;
    }
}
