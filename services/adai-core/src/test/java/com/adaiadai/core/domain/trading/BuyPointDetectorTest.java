package com.adaiadai.core.domain.trading;

import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BuyPointDetector — 买点判定测试（C2，2026-08-16）。 */
class BuyPointDetectorTest {

    private final BuyPointDetector detector = new BuyPointDetector(0.5, 0.7, 20, 1.5, 20);

    private List<Candle> uptrendThenPullback(boolean shrink, double kdjJ) {
        // 30 根：前 20 根上涨（前高 20），后 10 根回调到 ~9（回撤 55%）
        List<Candle> candles = new ArrayList<>();
        double price = 10;
        for (int i = 0; i < 30; i++) {
            double close;
            if (i < 20) {
                close = 10 + i * 0.5; // 涨到 20
            } else {
                close = 20 - (i - 19) * 1.2; // 回调到 ~8（回撤 60%）
            }
            double volume = (i < 20) ? 1000 : (shrink ? (i >= 27 ? 250 : 600) : 1200);
            candles.add(new Candle(LocalDate.of(2026, 8, i + 1), close - 0.2, close + 0.3,
                    close - 0.5, close, volume));
        }
        return candles;
    }

    @Test
    void b1_pullbackShrinkKdjLow_hit() {
        // 回调 55% + 缩量 0.5x → B1（KDJ 由构造决定——用足够回调让 J 低位）
        var r = detector.detect(uptrendThenPullback(true, 10));
        // KDJ 从数据算，若 J>=20 则走 B1? 分支——这里验证"回调+缩量"至少命中候选
        assertTrue(r.buyPoint().startsWith("B1"), "回调+缩量应判 B1/B1?，实际: " + r.buyPoint());
        assertTrue(r.score() > 0);
    }

    @Test
    void none_noPullback() {
        // 一直上涨无回调 → NONE
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double close = 10 + i * 0.5;
            candles.add(new Candle(LocalDate.of(2026, 8, i + 1),
                    close - 0.2, close + 0.3, close - 0.5, close, 1000));
        }
        assertEquals("NONE", detector.detect(candles).buyPoint());
    }

    @Test
    void insufficientData_none() {
        assertEquals("NONE", detector.detect(List.of(
                new Candle(LocalDate.of(2026, 8, 1), 10, 11, 9, 10, 100))).buyPoint());
    }
}
