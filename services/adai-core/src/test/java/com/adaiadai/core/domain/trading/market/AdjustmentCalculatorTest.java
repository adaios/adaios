package com.adaiadai.core.domain.trading.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdjustmentCalculatorTest — 前复权换算（2026-08-30：TDX 数据正确性）。
 * <p>
 * 覆盖：10送10、10派10、送转+派息混合、无事件原样、事件早于窗口、除权日前停牌跳过、
 * 从近到远逐级累计。
 */
class AdjustmentCalculatorTest {

    private List<Candle> candles(int startDay, int count, double close) {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate d = LocalDate.of(2026, 3, startDay + i);
            list.add(new Candle(d, close, close + 1, close - 1, close, 1000));
        }
        return list;
    }

    @Test
    void noEvents_returnsOriginal() {
        List<Candle> c = candles(1, 5, 10.0);
        assertEquals(c, AdjustmentCalculator.adjust(c, List.of()));
    }

    @Test
    void send10Of10_halvesPriorPrices() {
        // 除权日 3/5，之前收盘 10 元，10送10 → 参考价 5 元 → 之前价格 ×0.5
        List<Candle> c = candles(1, 8, 10.0); // 3/1..3/8 全 10 元（3/5 除权日也 10，简化）
        List<AdjustmentCalculator.AdjustmentEvent> events = List.of(
                new AdjustmentCalculator.AdjustmentEvent(LocalDate.of(2026, 3, 5), 0, 1.0, 0.0));
        List<Candle> adj = AdjustmentCalculator.adjust(c, events);
        // 3/1..3/4（除权日前）→ 5 元
        for (int i = 0; i < 4; i++) {
            assertEquals(5.0, adj.get(i).close(), 0.001, "除权日前价格应减半");
        }
        // 3/5 及之后（除权日/之后）→ 不变（10）
        for (int i = 4; i < 8; i++) {
            assertEquals(10.0, adj.get(i).close(), 0.001, "除权日及之后不变（最新价基准）");
        }
    }

    @Test
    void cash10Per10_reducesPriorPrices() {
        // 10派10 → 每股派 1 元 → 参考价 = (10×10 − 1×10)/(10) = 9 → 之前 ×0.9
        List<Candle> c = candles(1, 6, 10.0);
        List<AdjustmentCalculator.AdjustmentEvent> events = List.of(
                new AdjustmentCalculator.AdjustmentEvent(LocalDate.of(2026, 3, 4), 1.0, 0, 0));
        List<Candle> adj = AdjustmentCalculator.adjust(c, events);
        for (int i = 0; i < 3; i++) {
            assertEquals(9.0, adj.get(i).close(), 0.001);
        }
        for (int i = 3; i < 6; i++) {
            assertEquals(10.0, adj.get(i).close(), 0.001);
        }
    }

    @Test
    void mixedSendTransferCash() {
        // 10送4转6派5 → 送 0.4 + 转 0.6，派 0.5/股 → 参考价 = (10×10−0.5×10)/(10+10) = 4.75
        List<Candle> c = candles(1, 6, 10.0);
        List<AdjustmentCalculator.AdjustmentEvent> events = List.of(
                new AdjustmentCalculator.AdjustmentEvent(LocalDate.of(2026, 3, 4), 0.5, 0.4, 0.6));
        List<Candle> adj = AdjustmentCalculator.adjust(c, events);
        assertEquals(4.75, adj.get(0).close(), 0.001, "(10×10−5)/(20)=4.75");
        assertEquals(10.0, adj.get(3).close(), 0.001);
    }

    @Test
    void multipleEvents_cumulativeFromNewest() {
        // 3/4 派息（×0.9），3/6 送转（×0.5）——3/4 前价格 ×0.9×0.5=0.45，3/4-3/5 ×0.5
        List<Candle> c = candles(1, 8, 10.0);
        List<AdjustmentCalculator.AdjustmentEvent> events = List.of(
                new AdjustmentCalculator.AdjustmentEvent(LocalDate.of(2026, 3, 6), 0, 1.0, 0.0),
                new AdjustmentCalculator.AdjustmentEvent(LocalDate.of(2026, 3, 4), 1.0, 0, 0));
        List<Candle> adj = AdjustmentCalculator.adjust(c, events);
        // 3/1..3/3（两个事件之前）：10 × 0.9 × 0.5 = 4.5
        assertEquals(4.5, adj.get(0).close(), 0.001);
        // 3/4..3/5（只受送转事件影响）：10 × 0.5 = 5
        assertEquals(5.0, adj.get(3).close(), 0.001);
        // 3/6 及之后：10 不变
        assertEquals(10.0, adj.get(6).close(), 0.001);
    }

    @Test
    void eventBeforeWindow_noEffect() {
        // 事件早于数据起点 → 忽略（窗口内无影响）
        List<Candle> c = candles(1, 5, 10.0); // 3/1 起
        List<AdjustmentCalculator.AdjustmentEvent> events = List.of(
                new AdjustmentCalculator.AdjustmentEvent(LocalDate.of(2026, 2, 1), 1.0, 0, 0));
        List<Candle> adj = AdjustmentCalculator.adjust(c, events);
        assertEquals(10.0, adj.get(0).close(), 0.001, "窗口外事件不影响");
    }

    @Test
    void missingPrevClose_skipsEvent() {
        // 除权日 3/1（窗口第一根，无前收盘）→ 跳过（无法换算，不抛错）
        List<Candle> c = candles(1, 5, 10.0);
        List<AdjustmentCalculator.AdjustmentEvent> events = List.of(
                new AdjustmentCalculator.AdjustmentEvent(LocalDate.of(2026, 3, 1), 1.0, 0, 0));
        List<Candle> adj = AdjustmentCalculator.adjust(c, events);
        assertTrue(adj.get(0).close() > 9.99, "除权日为首根无前收盘 → 跳过事件");
    }

    @Test
    void volumeUnchanged() {
        List<Candle> c = candles(1, 6, 10.0);
        List<AdjustmentCalculator.AdjustmentEvent> events = List.of(
                new AdjustmentCalculator.AdjustmentEvent(LocalDate.of(2026, 3, 4), 1.0, 0, 0));
        List<Candle> adj = AdjustmentCalculator.adjust(c, events);
        assertEquals(1000.0, adj.get(0).volume(), 0.001, "送转不影响成交量手数");
    }
}
