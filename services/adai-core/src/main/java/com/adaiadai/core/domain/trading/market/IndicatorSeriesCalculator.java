package com.adaiadai.core.domain.trading.market;

import java.util.ArrayList;
import java.util.List;

/**
 * IndicatorSeriesCalculator — 指标全序列计算（2026-08-30：前后端指标口径一致）。
 * <p>
 * 前端 K 线图（hover 单日指标查看）**不再自行重算指标**——本类在服务端统一计算全序列
 * 随详情返回（单一事实源：hover 看到的 KDJ/MACD/MA 值 = 案例特征同源，消除双算不一致）。
 * <p>
 * 口径：MA 简单均值 / KDJ 9,3,3（K/D 初值 50，复用 {@link KdjIndicator}）/
 * MACD 12,26,9（EMA 初值=首根收盘、DEA 初值=首根 DIF，复用 {@link MacdIndicator}）——
 * 与 {@code CaseFeatureExtractor} 特征同口径。
 */
public final class IndicatorSeriesCalculator {

    private IndicatorSeriesCalculator() {}

    /** 指标全序列（与输入等长，旧→新）。 */
    public record IndicatorSeries(
            List<Double> ma5, List<Double> ma10, List<Double> ma20, List<Double> ma60,
            List<Double> kdjK, List<Double> kdjD, List<Double> kdjJ,
            List<Double> macdDif, List<Double> macdDea, List<Double> macdHist) {}

    public static IndicatorSeries series(List<Candle> candles) {
        List<Double> ma5 = new ArrayList<>();
        List<Double> ma10 = new ArrayList<>();
        List<Double> ma20 = new ArrayList<>();
        List<Double> ma60 = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            ma5.add(ma(candles, i, 5));
            ma10.add(ma(candles, i, 10));
            ma20.add(ma(candles, i, 20));
            ma60.add(ma(candles, i, 60));
        }
        List<Double> kdjK = new ArrayList<>();
        List<Double> kdjD = new ArrayList<>();
        List<Double> kdjJ = new ArrayList<>();
        for (KdjIndicator.Kdj k : KdjIndicator.series(candles)) {
            kdjK.add(k.k());
            kdjD.add(k.d());
            kdjJ.add(k.j());
        }
        List<Double> macdDif = new ArrayList<>();
        List<Double> macdDea = new ArrayList<>();
        List<Double> macdHist = new ArrayList<>();
        for (MacdIndicator.Macd m : MacdIndicator.series(candles)) {
            macdDif.add(m.dif());
            macdDea.add(m.dea());
            macdHist.add(m.hist());
        }
        return new IndicatorSeries(ma5, ma10, ma20, ma60, kdjK, kdjD, kdjJ, macdDif, macdDea, macdHist);
    }

    /** 均线：截至 idx 的最近 n 根收盘均值（不足 n 根用可用根数，对齐 CaseFeatureExtractor）。 */
    private static double ma(List<Candle> candles, int idx, int n) {
        int from = Math.max(0, idx - n + 1);
        double sum = 0;
        int count = 0;
        for (int i = from; i <= idx; i++) {
            sum += candles.get(i).close();
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }
}
