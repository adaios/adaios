package com.adaiadai.core.domain.trading.market;

import java.util.List;

/**
 * KDJ 指标（随机指标，C2 买点判定用）。
 * <p>
 * 课程口径：B1 高质量买点需「KDJ 大负值」（R47）。标准 KDJ：RSV(9) → K=2/3K'+1/3RSV →
 * D=2/3D'+1/3K → J=3K-2D。J<13 视为低位（课程锚点，P2-6 2026-08-17：原建议 20 偏松，用户确认改 13）。
 */
public final class KdjIndicator {

    /** KDJ 结果（最新值）。 */
    public record Kdj(double k, double d, double j) {}

    private KdjIndicator() {}

    /** 从 K 线算 KDJ（9,3,3），返回最新 K/D/J；数据不足返回 null。 */
    public static Kdj latest(List<Candle> candles) {
        List<Kdj> series = series(candles);
        return series.isEmpty() ? null : series.get(series.size() - 1);
    }

    /** KDJ 全序列（与 latest 同一递推口径——2026-08-30 前后端一致，前端图直接用后端序列）。 */
    public static List<Kdj> series(List<Candle> candles) {
        List<Kdj> result = new java.util.ArrayList<>();
        if (candles == null || candles.isEmpty()) return result;
        int period = 9;
        double k = 50, d = 50;
        for (int i = 0; i < candles.size(); i++) {
            if (i >= period - 1) {
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
            }
            double j = 3 * k - 2 * d;
            result.add(new Kdj(k, d, j));
        }
        return result;
    }
}
