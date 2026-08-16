package com.adaiadai.core.domain.trading;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KdjIndicator;

import java.util.List;

/**
 * BuyPointDetector — 买点判定（C2，2026-08-16）。
 * <p>
 * 从课程提炼的可执行口径（参数为默认建议值，可配置调整，见 buy-point-rules.md）：
 * <ul>
 *   <li><b>B1（低吸首选）</b>：距前高回撤 ≥ 50% + 缩量（3日均量 &lt; 5日均量 × 0.7）+ KDJ.J &lt; 20</li>
 *   <li><b>B2（突破右侧）</b>：放量（当日量 &gt; 5日均量 × 1.5）且收盘突破前高</li>
 * </ul>
 * 判定是提示不是指令——命中推送「到买点了」，买不买人决策。
 */
public class BuyPointDetector {

    private final double pullbackPct;    // B1 回调幅度（0-1，默认 0.5）
    private final double shrinkRatio;    // 缩量阈值（默认 0.7）
    private final double kdjLow;         // KDJ.J 低位阈值（默认 20）
    private final double volumeSurge;    // B2 放量倍数（默认 1.5）
    private final int priorHighDays;     // 前高窗口（默认 20）

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

        // 前高（priorHighDays 窗口内，不含当日）
        double priorHigh = 0;
        int start = Math.max(0, candles.size() - 1 - priorHighDays);
        for (int i = start; i < candles.size() - 1; i++) {
            priorHigh = Math.max(priorHigh, candles.get(i).high());
        }

        // 量均线
        double avg5 = avgVolume(candles, candles.size() - 5, candles.size());
        double avg3 = avgVolume(candles, candles.size() - 3, candles.size());
        boolean shrink = avg5 > 0 && avg3 < avg5 * shrinkRatio;
        boolean surge = avg5 > 0 && last.volume() > avg5 * volumeSurge;

        // 回调幅度（距前高回撤）
        double pullback = priorHigh > 0
                ? (priorHigh - last.close()) / priorHigh : 0;

        KdjIndicator.Kdj kdj = KdjIndicator.latest(candles);
        double jValue = kdj != null ? kdj.j() : 50;

        // B2：放量突破前高
        if (surge && last.close() > priorHigh) {
            return new BuyPointResult("B2", Math.min(100, 60 + last.volume() / avg5 * 10),
                    List.of("放量突破前高", "量能 " + String.format("%.1f", last.volume() / avg5) + "x"));
        }

        // B1：回调 + 缩量 + KDJ 低位
        if (pullback >= pullbackPct && shrink && jValue < kdjLow) {
            return new BuyPointResult("B1", Math.min(100,
                    40 + pullback * 40 + (1 - jValue / 100) * 30),
                    List.of("回调 " + String.format("%.0f", pullback * 100) + "%",
                            "缩量 " + String.format("%.1f", avg3 / avg5) + "x",
                            "KDJ.J=" + String.format("%.0f", jValue)));
        }

        // 部分满足（提示候选，不硬推）
        if (pullback >= pullbackPct && shrink) {
            return new BuyPointResult("B1?", 50, List.of("回调到位且缩量，等 KDJ 低位"));
        }
        return new BuyPointResult("NONE", 0, List.of());
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
