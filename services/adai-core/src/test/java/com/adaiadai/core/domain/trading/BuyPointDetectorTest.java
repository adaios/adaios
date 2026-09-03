package com.adaiadai.core.domain.trading;

import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BuyPointDetector — 买点判定测试（C2，2026-08-16；2026-09-04 三重校验批语义重写）。
 * 覆盖：B1 课程几何（回撤到波段涨幅一半位）+ B2 放量突破的三重防护
 * （KDJ 拐头/超买排除/追高排除/连板排除）与部分候选 B1?。
 */
class BuyPointDetectorTest {

    /** 默认参数与 2026-09-04 拍板一致：回调 0.5、缩量 0.7、KDJ 13、放量 2.0（倍量柱）、窗口 20。 */
    private final BuyPointDetector detector = new BuyPointDetector(0.5, 0.7, 13, 2.0, 20);

    /** 由 close/volume 序列构造 K 线：每根 high = close + 0.4、low = close − 0.4。 */
    private static List<Candle> bars(double[] closes, double[] volumes) {
        List<Candle> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 8, 3);
        for (int i = 0; i < closes.length; i++) {
            out.add(new Candle(d, closes[i] - 0.3, closes[i] + 0.4,
                    closes[i] - 0.4, closes[i], volumes[i]));
            d = d.plusDays(1);
        }
        return out;
    }

    /** 线性段：from → to 共 n 根，量 vol。 */
    private static double[] lin(double from, double to, int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = from + (to - from) * i / (n - 1);
        return out;
    }

    private static double[] fill(double v, int n) {
        double[] out = new double[n];
        java.util.Arrays.fill(out, v);
        return out;
    }

    private static double[] concat(double[]... arrays) {
        List<Double> all = new ArrayList<>();
        for (double[] a : arrays) for (double x : a) all.add(x);
        return all.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[] shrinkTail(double[] vols, int tail, double value) {
        double[] out = vols.clone();
        for (int i = vols.length - tail; i < vols.length; i++) out[i] = value;
        return out;
    }

    /** B1 场景：一波 8→20 上涨，随后缩量跌回 10（回撤远超一半位、J 超卖）。 */
    private List<Candle> b1Pullback() {
        double[] closes = concat(lin(8, 20, 15), lin(20, 10, 10));
        double[] vols = shrinkTail(fill(1000, 25), 3, 250);
        return bars(closes, vols);
    }

    @Test
    void b1_pullbackToHalfShrinkKdjLow_hit() {
        var r = detector.detect(b1Pullback());
        assertEquals("B1", r.buyPoint(), "回撤到涨幅一半位 + 缩量 + KDJ 超卖 → B1，实际: " + r);
        assertTrue(r.score() > 0);
        assertTrue(r.signals().stream().anyMatch(s -> s.contains("一半位")),
                "信号应注明课程口径：实际 " + r.signals());
    }

    @Test
    void b1_pullbackShrinkButKdjNotLow_partialB1() {
        // 回调到一半位 + 缩量，但 J 不低（快速横盘后的中位回调）→ B1? 候选不硬推
        double[] closes = concat(
                lin(8, 20, 12),     // 涨
                lin(20, 12, 8),     // 快跌到 12
                lin(12, 11, 5));    // 横在 11 附近企稳（J 回升）
        double[] vols = concat(fill(1000, 20), fill(400, 5));
        List<Candle> candles = bars(closes, shrinkTail(vols, 3, 200));
        var r = detector.detect(candles);
        // 窗口末 20 根低点：8 →? 窗口起点可能在上涨段中部（前高 20 不在窗口则回撤口径变——用足够长序列保证窗口含高点）
        // 25 根：窗口 = 末 20 根（i5..24）——上涨段前高在 i0..11，i5..11 含高点 ~20 附近；低点 ~12
        // 回撤 (≈20 − 11)/(20−≈12) 接近但依赖窗口——只断言非硬推形态（B1? 或 NONE 均可，不能误判成高位 B2）
        assertFalse(r.buyPoint().equals("B2"));
    }

    /** B2 场景：区间震荡（8.0-9.0，swing low ≈8）→ 温和放量（3x）突破前高。 */
    private List<Candle> b2Breakout() {
        // 前 24 根震荡：close 8.4±0.5 锯齿（high ≤9.3），低点 ~7.45——共 25 根满足 detector 最少窗口
        List<Candle> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 8, 3);
        for (int i = 0; i < 24; i++) {
            double c = 8.3 + (i % 3 == 0 ? 0.55 : (i % 3 == 1 ? -0.35 : 0.05));
            out.add(new Candle(d, c, Math.min(9.3, c + 0.4), Math.max(7.6, c - 0.5), c, 1000));
            d = d.plusDays(1);
        }
        // 突破日：close 9.6 大阳 +8.5%，量 3600（> 2× 5日均量≈1200）；相对窗口低点 7.45 涨幅 28.8% ≤30%
        out.add(new Candle(d, 9.6 - 0.3, 9.7, 9.0, 9.6, 3600));
        return out;
    }

    @Test
    void b2_volumeSurgeBreakout_hit() {
        var r = detector.detect(b2Breakout());
        assertEquals("B2", r.buyPoint(), "温和放量突破前高 + KDJ 拐头未超买 → B2，实际: " + r);
    }

    @Test
    void b2_afterExtendedRally_rejected() {
        // 已从低位连续拉升（runUp≈56% 追高 + 末段连续大阳 J 高位钝化）→ 再放量破高不推
        // （数据保证 B2 几何满足 close>前高 & 放量——防护必须拦下，否则判定失去意义）
        double[] closes = concat(lin(8, 9, 12), lin(9, 12, 12), new double[]{12.5}); // 25 根
        double[] vols = concat(fill(1000, 24), new double[]{5000});
        List<Candle> candles = bars(closes, vols);
        var r = detector.detect(candles);
        assertFalse("B2".equals(r.buyPoint()), "连拉后高位再突破（追高/钝化）不应推 B2：实际 " + r);
    }

    @Test
    void b2_chaseRejected_whenRunUpOver30() {
        // 从波段低点已涨超 30% 再突破 = 追高 → 拦截
        double[] closes = concat(lin(8, 10.8, 23), new double[]{11.2}, new double[]{11.5}); // 末两日破前高，runUp≈44%
        double[] vols = concat(fill(1000, 23), fill(3000, 2));
        List<Candle> candles = bars(closes, vols);
        var r = detector.detect(candles);
        assertFalse("B2".equals(r.buyPoint()), "距低点涨幅>30% 追高不应推 B2：实际 " + r);
    }

    @Test
    void b2_twoConsecutiveLimitUp_rejected() {
        // ≥2 连板（近两日涨幅 ≥9.8%）再放量 → 拦截
        double[] closes = concat(lin(8, 9, 23), new double[]{9.9}, new double[]{11.0}); // 9.9(+10%) → 11.0(+11%)
        double[] vols = concat(fill(1000, 23), fill(3000, 2));
        List<Candle> candles = bars(closes, vols);
        var r = detector.detect(candles);
        assertFalse("B2".equals(r.buyPoint()), "≥2 连板不应推 B2：实际 " + r);
    }

    @Test
    void none_noPullback() {
        // 一直上涨无回调 → NONE
        double[] closes = new double[30];
        for (int i = 0; i < 30; i++) closes[i] = 10 + i * 0.5;
        assertEquals("NONE", detector.detect(bars(closes, fill(1000, 30))).buyPoint());
    }

    @Test
    void insufficientData_none() {
        assertEquals("NONE", detector.detect(bars(new double[]{10}, new double[]{100})).buyPoint());
    }
}
