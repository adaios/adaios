package com.adaiadai.core.domain.trading.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IndicatorSeriesCalculatorTest — 指标全序列（2026-08-30：前后端指标一致）。
 * <p>
 * 验证：序列长度与输入一致、MA/KDJ/MACD 与既有单点口径一致（latest/series 同源）、
 * MA 末值 = 近 N 根均值。
 */
class IndicatorSeriesCalculatorTest {

    private List<Candle> candles(int count) {
        List<Candle> list = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < count; i++) {
            double close = 10 + i * 0.1;
            list.add(new Candle(start.plusDays(i), close - 0.05, close + 0.2, close - 0.2, close, 1000));
        }
        return list;
    }

    @Test
    void series_lengthMatchesInput() {
        List<Candle> c = candles(90);
        IndicatorSeriesCalculator.IndicatorSeries s = IndicatorSeriesCalculator.series(c);
        assertEquals(90, s.ma5().size());
        assertEquals(90, s.ma10().size());
        assertEquals(90, s.ma20().size());
        assertEquals(90, s.ma60().size());
        assertEquals(90, s.kdjK().size());
        assertEquals(90, s.kdjD().size());
        assertEquals(90, s.kdjJ().size());
        assertEquals(90, s.macdDif().size());
        assertEquals(90, s.macdDea().size());
        assertEquals(90, s.macdHist().size());
    }

    @Test
    void kdjSeries_matchesLatest() {
        List<Candle> c = candles(90);
        IndicatorSeriesCalculator.IndicatorSeries s = IndicatorSeriesCalculator.series(c);
        KdjIndicator.Kdj latest = KdjIndicator.latest(c);
        assertEquals(latest.k(), s.kdjK().get(89), 0.0001, "序列末值 = latest（同口径）");
        assertEquals(latest.d(), s.kdjD().get(89), 0.0001);
        assertEquals(latest.j(), s.kdjJ().get(89), 0.0001);
    }

    @Test
    void macdSeries_matchesLatestAndCrossUp() {
        List<Candle> c = candles(90);
        IndicatorSeriesCalculator.IndicatorSeries s = IndicatorSeriesCalculator.series(c);
        MacdIndicator.Macd latest = MacdIndicator.latest(c);
        assertEquals(latest.dif(), s.macdDif().get(89), 0.0001);
        assertEquals(latest.dea(), s.macdDea().get(89), 0.0001);
        assertEquals(latest.hist(), s.macdHist().get(89), 0.0001);
        assertEquals(MacdIndicator.crossUp(c),
                s.macdDif().get(88) <= s.macdDea().get(88) && s.macdDif().get(89) > s.macdDea().get(89),
                "crossUp 口径一致");
    }

    @Test
    void ma_lastEqualsRecentNMean() {
        List<Candle> c = candles(90);
        IndicatorSeriesCalculator.IndicatorSeries s = IndicatorSeriesCalculator.series(c);
        double sum10 = 0;
        for (int i = 80; i < 90; i++) sum10 += c.get(i).close();
        assertEquals(sum10 / 10, s.ma10().get(89), 0.0001);
        double sum5 = 0;
        for (int i = 85; i < 90; i++) sum5 += c.get(i).close();
        assertEquals(sum5 / 5, s.ma5().get(89), 0.0001);
        // 前 N 根不足时用可用根数（ma60 第 30 根 = 前 31 根均值）
        double sum31 = 0;
        for (int i = 0; i <= 30; i++) sum31 += c.get(i).close();
        assertEquals(sum31 / 31, s.ma60().get(30), 0.0001);
    }

    @Test
    void kdjSeries_beforePeriod_holdsInitialValues() {
        List<Candle> c = candles(5); // 不足 9 根
        IndicatorSeriesCalculator.IndicatorSeries s = IndicatorSeriesCalculator.series(c);
        for (int i = 0; i < 5; i++) {
            assertEquals(50.0, s.kdjK().get(i), 0.0001, "前 8 根 K/D 保持初值 50");
            assertEquals(50.0, s.kdjJ().get(i), 0.0001, "J = 3K-2D = 50");
        }
    }
}
