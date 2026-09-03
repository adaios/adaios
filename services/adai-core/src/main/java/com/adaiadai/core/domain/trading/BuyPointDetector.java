package com.adaiadai.core.domain.trading;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KdjIndicator;

import java.util.List;

/**
 * BuyPointDetector — 买点判定（C2，2026-08-16；2026-09-04 三重校验批 P1-交易20）。
 * <p>
 * 从课程提炼的可执行口径（参数来自用户规则 rules.yaml，无规则 → 默认值）：
 * <ul>
 *   <li><b>B1（低吸首选）</b>：回撤到波段**涨幅的一半位置**——窗口内最高 high 与最低 low 的
 *       中点 (high+low)/2，即回撤占波段 ≥50%（2026-09-04 按课程校准，P1-交易9 语义）+ 缩量
 *       （3日均量 &lt; 5日均量 × 缩量阈值）+ KDJ.J &lt; 低位阈值</li>
 *   <li><b>B2（突破右侧）</b>：放量（当日量 &gt; 5日均量 × 放量倍数，默认 2.0 = 课程「倍量柱」）
 *       且收盘破前高，并满足三重防护（2026-08-27 生产实锤楚天龙五连板高位仍推——加）：
 *       KDJ.J 拐头向上 + J &lt; 90 排除深度超买；距窗口低点涨幅 ≤30%（防追高）；非 ≥2 连板</li>
 * </ul>
 * 判定是提示不是指令——命中推送「到买点了」，买不买人决策。
 */
public class BuyPointDetector {

    /** B2 超买排除：KDJ.J ≥ 该值 = 深度超买不追（2026-09-04 三重校验）。 */
    static final double B2_OVERBOUGHT_J = 90.0;
    /** B2 追高防护：距窗口最低点累计涨幅 > 该比例不推（连板高位风险）。 */
    static final double CHASE_LIMIT_PCT = 0.30;
    /** B2 连板防护：近 2 日单日涨幅 ≥ 该比例视为涨停（≥2 连板不追）。 */
    static final double LIMIT_UP_PCT = 0.098;

    private final double pullbackPct;    // B1 回调幅度（回撤占波段比例，0-1，默认 0.5 = 涨一半位置）
    private final double shrinkRatio;    // 缩量阈值（默认 0.7）
    private final double kdjLow;         // KDJ.J 低位阈值（课程锚点 J<13，P2-6 2026-08-17）
    private final double volumeSurge;    // B2 放量倍数（默认 2.0 = 倍量柱，P2 2026-09-04 拍板）
    private final int priorHighDays;     // 前高/波段窗口（默认 20）

    public BuyPointDetector(double pullbackPct, double shrinkRatio, double kdjLow,
                            double volumeSurge, int priorHighDays) {
        this.pullbackPct = pullbackPct;
        this.shrinkRatio = shrinkRatio;
        this.kdjLow = kdjLow;
        this.volumeSurge = volumeSurge;
        this.priorHighDays = priorHighDays;
    }

    /** 判定结果。 */
    public record BuyPointResult(String buyPoint, double score, List<String> signals) {
        public boolean hit() {
            return !"NONE".equals(buyPoint);
        }
    }

    /** 判定最新买点信号。 */
    public BuyPointResult detect(List<Candle> candles) {
        if (candles == null || candles.size() < Math.max(priorHighDays + 5, 20)) {
            return new BuyPointResult("NONE", 0, List.of());
        }
        Candle last = candles.get(candles.size() - 1);

        // 波段窗口（priorHighDays 根，不含当日）：最高 high = 前高；最低 low = 波段起点（swing low）
        double priorHigh = 0;
        double swingLow = Double.MAX_VALUE;
        int start = Math.max(0, candles.size() - 1 - priorHighDays);
        for (int i = start; i < candles.size() - 1; i++) {
            priorHigh = Math.max(priorHigh, candles.get(i).high());
            swingLow = Math.min(swingLow, candles.get(i).low());
        }

        // 量均线
        double avg5 = avgVolume(candles, candles.size() - 5, candles.size());
        double avg3 = avgVolume(candles, candles.size() - 3, candles.size());
        boolean shrink = avg5 > 0 && avg3 < avg5 * shrinkRatio;
        boolean surge = avg5 > 0 && last.volume() > avg5 * volumeSurge;

        // B1 课程几何：回撤占波段涨幅比例 = (前高 − 现价) / (前高 − 波段低点)。
        // ≥50% ⇔ close ≤ (high+low)/2——「回调到涨幅一半位置」（P1-交易9 校准，替代旧的
        // 「距前高回撤≥50%」即 close≤high/2 的腰斩口径——课程是回撤到 (high+low)/2）。
        double range = priorHigh > 0 && swingLow < Double.MAX_VALUE ? priorHigh - swingLow : 0;
        double pullbackRatio = range > 1e-9 ? (priorHigh - last.close()) / range : 0;

        // KDJ（J 当前值 + 前一日值——B2 拐头判定用；无序列时回落中性 50 且拐头不拦截）
        List<KdjIndicator.Kdj> kdjSeries = KdjIndicator.series(candles);
        double jCurr = 50, jPrev = 50;
        if (!kdjSeries.isEmpty()) {
            jCurr = kdjSeries.get(kdjSeries.size() - 1).j();
            if (kdjSeries.size() >= 2) {
                jPrev = kdjSeries.get(kdjSeries.size() - 2).j();
            }
        }

        // B2：放量突破前高 + 三重防护（KDJ 拐头向上 & 非高位钝化 & 不追高 & 非连板）
        boolean jTurningUp = jCurr > jPrev;
        // 超买排除用「高位钝化」口径：J 连续两根 ≥90（J 已高位钝化，如连板/连拉后的再突破）→ 不追；
        // 若 J 刚从前低拉起（即使当日冲到 90+）→ 放行——首日突破 KDJ 必冲高，一刀切 <90 会误杀全部 B2
        boolean notStagnated = !(jCurr >= B2_OVERBOUGHT_J && jPrev >= B2_OVERBOUGHT_J);
        double low20 = swingLow < Double.MAX_VALUE ? swingLow : 0;
        double runUp = low20 > 0 ? (last.close() - low20) / low20 : 0;
        boolean notChasing = runUp <= CHASE_LIMIT_PCT;
        boolean notLimitRuns = !(isLimitUp(last, candles, candles.size() - 1)
                && candles.size() >= 2 && isLimitUp(candles.get(candles.size() - 2), candles, candles.size() - 2));
        if (surge && last.close() > priorHigh && jTurningUp && notStagnated && notChasing && notLimitRuns) {
            List<String> signals = new java.util.ArrayList<>();
            signals.add("放量突破前高");
            signals.add("量能 " + String.format("%.1f", last.volume() / avg5) + "x");
            signals.add("KDJ.J=" + String.format("%.0f", jCurr) + " 拐头向上");
            return new BuyPointResult("B2", Math.min(100, 60 + last.volume() / avg5 * 10), signals);
        }

        // B1：回撤到涨幅一半位 + 缩量 + KDJ 低位
        if (pullbackRatio >= pullbackPct && shrink && jCurr < kdjLow) {
            return new BuyPointResult("B1", Math.min(100,
                    40 + pullbackRatio * 40 + (1 - jCurr / 100) * 30),
                    List.of("回撤 " + String.format("%.0f", pullbackRatio * 100) + "%（到涨幅一半位）",
                            "缩量 " + String.format("%.1f", avg3 / avg5) + "x",
                            "KDJ.J=" + String.format("%.0f", jCurr)));
        }

        // 部分满足（提示候选，不硬推）
        if (pullbackRatio >= pullbackPct && shrink) {
            return new BuyPointResult("B1?", 50, List.of("回调到涨幅一半位且缩量，等 KDJ 低位"));
        }
        return new BuyPointResult("NONE", 0, List.of());
    }

    /** 单日是否近似涨停（涨幅 ≥ 涨停阈值）。 */
    private boolean isLimitUp(Candle c, List<Candle> candles, int index) {
        if (index <= 0) return false;
        double prevClose = candles.get(index - 1).close();
        return prevClose > 0 && (c.close() - prevClose) / prevClose >= LIMIT_UP_PCT;
    }

    private double avgVolume(List<Candle> candles, int from, int to) {
        double sum = 0;
        int n = 0;
        for (int i = Math.max(0, from); i < Math.min(to, candles.size()); i++) {
            sum += candles.get(i).volume();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }
}
