package com.adaiadai.core.domain.trading.market;

import java.util.List;

/**
 * KDJ 指标（随机指标，C2 买点判定用）。
 * <p>
 * 课程口径：B1 高质量买点需「KDJ 大负值」（R47）。标准 KDJ：RSV(9) → K=2/3K'+1/3RSV →
 * D=2/3D'+1/3K → J=3K-2D。J<20 视为低位（参数可配，默认建议值）。
 */
public final class KdjIndicator {

    /** KDJ 结果（最新值）。 */
    public record Kdj(double k, double d, double j) {}

    private KdjIndicator() {}

    /** 从 K 线算 KDJ（9,3,3），返回最新 K/D/J；数据不足返回 null。 */
    public static Kdj latest(List<Candle> candles) {
        if (candles == null || candles.size() < 10) return null;
        int period = 9;
        double k = 50, d = 50;
        Kdj result = null;
        for (int i = 0; i < candles.size(); i++) {
            if (i < period - 1) continue;
            double highest = Double.MIN_VALUE;
            double lowest = Double.MAX_VALUE;
            for (int j = i - period + 1; j <= i; j++) {
                highest = Math.max(highest, candles.get(j).high());
                lowest = Math.min(lowest, candles.get(j).low());
            }
            double c = candles.get(i).close();
            double rsv = (highest - lowest) == 0 ? 50
                    : (c - lowest) / (highest - lowest) * 100;
            k = 2.0 / 3.0 * k + 1.0 / 3.0 * rsv;
            d = 2.0 / 3.0 * d + 1.0 / 3.0 * k;
            double j = 3 * k - 2 * d;
            result = new Kdj(k, d, j);
        }
        return result;
    }
}
