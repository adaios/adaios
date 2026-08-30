package com.adaiadai.core.domain.trading.cases;

/**
 * CaseFeatureNormalizer — 案例特征归一化（2026-08-30 第四阶段环 4：判定当下）。
 * <p>
 * 特征全部映射到 0-1（min-max 边界固定表，设计文档 §4.3），保证跨标的可比：
 * <pre>
 * 1. drawdownFromHighPct / 100          回撤 %（0-100 → 0-1）
 * 2. volumeShrinkRatio / 3              量比（0-3 → 0-1）
 * 3. kdjJ / 100                         KDJ.J（0-100 → 0-1；null → 0.5 中性）
 * 4. (macdHist + 5) / 10                MACD 柱（±5 钳制 → 0-1；null → 0.5）
 * 5. distToMa60Pct / 20                 距 60 日线 %（0-20 → 0-1）
 * 6. sidewaysDays / 10                  盘整天数（0-10 → 0-1）
 * 7. signals / 4                        看多信号计数（KDJ金叉+MACD金叉+开门态+破前高）/4
 * </pre>
 */
public final class CaseFeatureNormalizer {

    /** 特征向量维度。 */
    public static final int DIM = 7;

    private CaseFeatureNormalizer() {}

    /** 特征 → 0-1 向量（维度对齐 {@link #DIM}）。 */
    public static double[] toVector(CaseRecord.CaseFeatures f) {
        double[] v = new double[DIM];
        v[0] = clamp(f.drawdownFromHighPct() / 100.0);
        v[1] = clamp(f.volumeShrinkRatio() / 3.0);
        v[2] = f.kdjJ() == null ? 0.5 : clamp(f.kdjJ() / 100.0);
        v[3] = f.macdHist() == null ? 0.5 : clamp((f.macdHist() + 5.0) / 10.0);
        v[4] = clamp(f.distToMa60Pct() / 20.0);
        v[5] = clamp(f.sidewaysDays() / 10.0);
        int signals = (f.kdjGoldenCross() ? 1 : 0) + (f.macdCrossUp() ? 1 : 0)
                + (f.whiteAboveYellow() ? 1 : 0) + (f.breakoutFromHigh() ? 1 : 0);
        v[6] = signals / 4.0;
        return v;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
