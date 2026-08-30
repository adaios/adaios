package com.adaiadai.core.domain.trading.cases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CaseConsensus — 完美买点共识画像（2026-08-30：数据驱动判定深化，核心价值）。
 * <p>
 * 从案例库**统计学习**每个特征的「完美区间」（25-75 分位）——不硬编码 B1/B2 参数，
 * 而是从用户标注的真实完美买点归纳共识；匹配时逐维判定命中，输出「共识命中 N/M 维」。
 * <p>
 * 维度（6 维数值特征）：回撤 % / 量比 / KDJ.J / 距 60 日线 % / MACD 柱 / 盘整天数。
 * 案例 < {@link #MIN_CASES}（5）→ 统计无意义，返回 null（共识不可用）。
 */
public final class CaseConsensus {

    /** 最少案例数（不足则不生成共识画像——统计无意义）。 */
    public static final int MIN_CASES = 5;

    private CaseConsensus() {}

    /** 单维共识区间。 */
    public record Range(String feature, double low, double high) {}

    /** 逐维命中结果。 */
    public record Hit(String feature, boolean hit, double value, double low, double high) {}

    /** 共识画像 + 命中评估。 */
    public record ConsensusResult(List<Range> profile, List<Hit> hits,
                                  int hitCount, int total) {}

    /**
     * 从案例库构建共识画像（每维 25-75 分位）；案例 &lt; MIN_CASES → null。
     * 全量口径（不区分类型，向后兼容旧调用）。
     * 维度含 null 特征（KDJ/MACD 数据不足）的案例跳过该维。
     */
    public static List<Range> buildProfile(List<CaseRecord> cases) {
        return buildProfile(cases, null);
    }

    /**
     * 双轨画像（2026-08-31 方案第 1 层）：按类型过滤后构建 25-75 分位画像。
     * <ul>
     *   <li>type=null/空 → 全量正样本（排除负样本 {@link CaseRecord#TYPE_FAILED}）</li>
     *   <li>type=B1/B2 → 仅该类型正样本</li>
     *   <li>type=FAILED → 负样本画像（失败形态参照系，供 match 风险警示）</li>
     * </ul>
     * 案例 &lt; MIN_CASES → null（统计无意义）。
     */
    public static List<Range> buildProfile(List<CaseRecord> cases, String type) {
        if (cases == null) return null;
        List<CaseRecord> filtered = new ArrayList<>();
        for (CaseRecord c : cases) {
            if (c == null) continue;
            String t = c.buyType();
            boolean isFailed = CaseRecord.TYPE_FAILED.equals(t);
            if (type == null || type.isBlank()) {
                if (isFailed) continue; // 全量口径排除负样本，防稀释
            } else if (!type.equals(t)) {
                continue;
            }
            filtered.add(c);
        }
        if (filtered.size() < MIN_CASES) return null;
        String[] features = {"drawdownFromHighPct", "volumeShrinkRatio", "kdjJ",
                "distToMa60Pct", "macdHist", "sidewaysDays"};
        List<Range> ranges = new ArrayList<>();
        for (String f : features) {
            List<Double> values = new ArrayList<>();
            for (CaseRecord c : filtered) {
                if (c.features() == null) continue;
                Double v = valueOf(c.features(), f);
                if (v != null) values.add(v);
            }
            if (values.size() < MIN_CASES) continue; // 该维样本不足 → 不参与
            values.sort(Comparator.naturalOrder());
            double low = percentile(values, 0.25);
            double high = percentile(values, 0.75);
            ranges.add(new Range(f, low, high));
        }
        return ranges.isEmpty() ? null : ranges;
    }

    /** 评估当前形态对画像的逐维命中。 */
    public static ConsensusResult evaluate(CaseRecord.CaseFeatures features, List<Range> profile) {
        List<Hit> hits = new ArrayList<>();
        int hitCount = 0;
        for (Range r : profile) {
            Double v = valueOf(features, r.feature());
            if (v == null) continue;
            boolean hit = v >= r.low() && v <= r.high();
            if (hit) hitCount++;
            hits.add(new Hit(r.feature(), hit, v, r.low(), r.high()));
        }
        return new ConsensusResult(profile, hits, hitCount, hits.size());
    }

    private static Double valueOf(CaseRecord.CaseFeatures f, String feature) {
        return switch (feature) {
            case "drawdownFromHighPct" -> f.drawdownFromHighPct();
            case "volumeShrinkRatio" -> f.volumeShrinkRatio();
            case "kdjJ" -> f.kdjJ();
            case "distToMa60Pct" -> f.distToMa60Pct();
            case "macdHist" -> f.macdHist();
            case "sidewaysDays" -> (double) f.sidewaysDays();
            default -> null;
        };
    }

    /** 分位数（线性插值近似：排序后取位置 p*(n-1) 的相邻插值）。 */
    static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        if (sorted.size() == 1) return sorted.get(0);
        double pos = p * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted.get(lo);
        double frac = pos - lo;
        return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
    }
}
