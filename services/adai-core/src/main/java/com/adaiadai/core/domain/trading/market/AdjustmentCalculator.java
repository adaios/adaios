package com.adaiadai.core.domain.trading.market;

import java.time.LocalDate;
import java.util.List;

/**
 * AdjustmentCalculator — 前复权换算（2026-08-30：TDX 本地数据正确性）。
 * <p>
 * TDX `.day` 是不复权原始价——除权日（送转/分红）价格跳空 → 回撤/特征失真、画面与通达信
 * （前复权）不一致。本类把不复权 K 线换算为**前复权**（口径对齐腾讯 qfq / 通达信）。
 * <p>
 * 算法（标准前复权，设计文档 trading-data-adjustment.md §五）：
 * <pre>
 * 1. 每个除权事件 e：除权参考价 refPrice = (除权前收盘×10 − 派息×10) / (10 + (送+转)×10)
 *    （除权前收盘 = e.exDate 前一交易日收盘，从 candles 取；缺失 → 跳过事件）
 * 2. 按 exDate 从近到远：对 e.exDate 之前的 K 线价格 ×= cumulative（cumulative 累乘
 *    factor = refPrice/除权前收盘）；除权日及之后因子 = 1（最新价不变）
 * </pre>
 * 纯算法无 IO，可单测。
 */
public final class AdjustmentCalculator {

    private AdjustmentCalculator() {}

    /** 除权事件（每股比例）。 */
    public record AdjustmentEvent(LocalDate exDate, double cashPerShare,
                                  double sendPerShare, double transferPerShare) {}

    /**
     * 前复权换算；无事件 → 原样返回。原始列表不变（返回新列表）。
     * <p>
     * 算法：每个除权事件用**原始不复权价**算 factor = 除权参考价/除权前收盘；
     * 每根 K 线的前复权因子 = 其日期之前所有除权事件的 factor 乘积
     * （该 K 线之后发生的除权都会压低它——从近到远逐级，等价于标准前复权）。
     */
    public static List<Candle> adjust(List<Candle> candles, List<AdjustmentEvent> events) {
        if (candles == null || candles.isEmpty() || events == null || events.isEmpty()) {
            return candles;
        }
        // 每事件的有效 factor（用原始价；事件须在窗口内且除权日前收盘存在）
        java.util.Map<LocalDate, Double> factorByDate = new java.util.HashMap<>();
        for (AdjustmentEvent e : events) {
            int exIdx = indexOfDate(candles, e.exDate());
            if (exIdx <= 0) continue; // 事件不在窗口内 → 无影响
            double prevClose = candles.get(exIdx - 1).close();
            if (prevClose <= 0) continue; // 除权日前收盘缺失 → 跳过
            double ref = refPrice(prevClose, e);
            if (ref <= 0) continue;
            factorByDate.put(e.exDate(), ref / prevClose);
        }
        if (factorByDate.isEmpty()) return candles;

        List<Candle> result = new java.util.ArrayList<>(candles.size());
        for (Candle c : candles) {
            double f = 1.0;
            for (java.util.Map.Entry<LocalDate, Double> entry : factorByDate.entrySet()) {
                if (c.date().isBefore(entry.getKey())) f *= entry.getValue();
            }
            result.add(new Candle(c.date(), c.open() * f, c.high() * f,
                    c.low() * f, c.close() * f, c.volume()));
        }
        return result;
    }

    /** 除权参考价（含税口径，对齐通达信/腾讯）：(除权前收盘×10 − 派息×10) / (10 + (送+转)×10)。 */
    static double refPrice(double prevClose, AdjustmentEvent e) {
        double denominator = 10 + (e.sendPerShare() + e.transferPerShare()) * 10;
        if (denominator <= 0) return 0;
        return (prevClose * 10 - e.cashPerShare() * 10) / denominator;
    }

    private static int indexOfDate(List<Candle> candles, LocalDate date) {
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).date().equals(date)) return i;
        }
        return -1;
    }
}
