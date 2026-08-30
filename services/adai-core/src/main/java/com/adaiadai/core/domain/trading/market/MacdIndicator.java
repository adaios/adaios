package com.adaiadai.core.domain.trading.market;

import java.util.ArrayList;
import java.util.List;

/**
 * MACD 指标（指数平滑异同移动平均线，2026-08-30：完美买点案例库特征——黄白线近似、
 * 趋势位置判断用）。
 * <p>
 * 口径：EMA12 - EMA26 = DIF；DIF 的 9 日 EMA = DEA（信号线）；柱 hist = DIF - DEA。
 * 金叉 = DIF 上穿 DEA（今日 DIF &gt; DEA 且昨日 DIF ≤ DEA）。
 */
public final class MacdIndicator {

    /** MACD 单点值。 */
    public record Macd(double dif, double dea, double hist) {}

    private MacdIndicator() {}

    /** 全序列 MACD（每根一个点，与输入等长，旧→新）；不足 26 根时前部用滚动近似（前值填充）。 */
    public static List<Macd> series(List<Candle> candles) {
        List<Macd> result = new ArrayList<>();
        if (candles == null || candles.isEmpty()) return result;
        double ema12 = candles.get(0).close();
        double ema26 = candles.get(0).close();
        double dea = 0;
        for (int i = 0; i < candles.size(); i++) {
            double c = candles.get(i).close();
            ema12 = i == 0 ? c : ema12 + (c - ema12) * (2.0 / 13.0);
            ema26 = i == 0 ? c : ema26 + (c - ema26) * (2.0 / 27.0);
            double dif = ema12 - ema26;
            dea = i == 0 ? dif : dea + (dif - dea) * (2.0 / 10.0);
            result.add(new Macd(dif, dea, dif - dea));
        }
        return result;
    }

    /** 最新 MACD 点；数据不足返回 null。 */
    public static Macd latest(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return null;
        List<Macd> series = series(candles);
        return series.isEmpty() ? null : series.get(series.size() - 1);
    }

    /** 是否金叉（最新两根：前一日 DIF ≤ DEA，今日 DIF &gt; DEA）；不足两根返回 false。 */
    public static boolean crossUp(List<Candle> candles) {
        List<Macd> series = series(candles);
        if (series.size() < 2) return false;
        Macd prev = series.get(series.size() - 2);
        Macd cur = series.get(series.size() - 1);
        return prev.dif() <= prev.dea() && cur.dif() > cur.dea();
    }
}
