package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R-04 通用 K 线（2026-10-07 晚）——「一张图四处共用」的原料组装：
 * 蜡烛 / 我的买卖点（B·T·S）/ 你定的止损线 / 峰值浮盈线 / 上下文。
 *
 * <p>锁两条硬口径：① **取不到行情不画假图**（空 candles + 人话 note）；
 * ② **没有买卖点/止损就不给**，不硬编。
 */
class TradingKlineAppServiceTest {

    private static final String SYM = "603993";
    private static final LocalDate D = LocalDate.of(2026, 8, 3);

    private record Mocks(TradingHistoryRepository history, PositionRepository positions,
                         SoldTradeRepository sold, TradingLotService lots) {}

    private Mocks mocks() {
        return new Mocks(mock(TradingHistoryRepository.class), mock(PositionRepository.class),
                mock(SoldTradeRepository.class), mock(TradingLotService.class));
    }

    private TradingKlineAppService service(KlineService kline, Mocks m) {
        when(m.history().findAll(anyString())).thenReturn(List.of());
        when(m.positions().findBySymbol(anyString(), anyString())).thenReturn(Optional.empty());
        when(m.sold().findAll(anyString())).thenReturn(List.of());
        return new TradingKlineAppService(kline, m.history(), m.positions(), m.sold(), m.lots());
    }

    private static List<Candle> candles(double... closes) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out.add(new Candle(D.plusDays(i), c - 0.1, c + 0.2, c - 0.3, c, 1000 + i));
        }
        return out;
    }

    private static TradeRecord trade(String id, TradeDirection dir, double price, int volume,
                                     LocalDate date, BigDecimal stopLoss) {
        return TradeRecord.of(id, SYM, "洛阳钼业", dir, BigDecimal.valueOf(price), volume, date,
                LocalTime.of(10, 30), stopLoss, "B1", null, null, BigDecimal.ZERO, date.atTime(10, 30),
                null, null);
    }

    // ── ① 取不到行情：不画假图 ──

    @Test
    void 取不到行情时返回空蜡烛和人话说明() {
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(List.of());
        Map<String, Object> r = service(kline, mocks()).kline("u", SYM, 90);

        assertEquals(List.of(), r.get("candles"));
        assertEquals(List.of(), r.get("marks"));
        assertNull(r.get("stopLine"));
        assertNull(r.get("peakLine"));
        assertNotNull(r.get("note"), "必须给一句人话，而不是空白图");
    }

    @Test
    void 代码不是六位时如实说代码不对() {
        KlineService kline = mock(KlineService.class);
        Map<String, Object> r = service(kline, mocks()).kline("u", "600519.SH", 90);

        assertEquals(List.of(), r.get("candles"));
        assertTrue(String.valueOf(r.get("note")).contains("6 位"));
    }

    // ── ② 买卖点推导：B 建仓 / T 加仓 / S 卖出 ──

    @Test
    void 同日同向的成交合并成一条买卖点_加权均价() {
        Mocks m = mocks();
        when(m.history().findAll("u")).thenReturn(List.of(
                trade("t1", TradeDirection.BUY, 15.00, 100, D, null),
                trade("t2", TradeDirection.BUY, 17.00, 300, D, null), // 同一天、同一方向
                trade("t3", TradeDirection.SELL, 18.00, 200, D.plusDays(5), null)));
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(candles(15.0, 16.0, 17.0, 18.0, 19.0,
                20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 24.0, 23.0, 22.0, 21.0, 20.0, 19.0, 18.0,
                17.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0));

        Map<String, Object> r = new TradingKlineAppService(kline, m.history(), m.positions(),
                m.sold(), m.lots()).kline("u", SYM, 30);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> marks = (List<Map<String, Object>>) r.get("marks");
        assertEquals(2, marks.size(), "同一天的两笔买入要合成一条，不能出两个点");
        assertEquals("B", marks.get(0).get("type"));
        assertEquals(400, marks.get(0).get("quantity"));
        assertEquals(16.50, (Double) marks.get(0).get("price"), 1e-9); // (15×100 + 17×300) / 400
        assertEquals("S", marks.get(1).get("type"));
    }

    @Test
    void 买卖点按成交先后推导成买加卖三种标记() {
        Mocks m = mocks();
        when(m.history().findAll("u")).thenReturn(List.of(
                trade("t1", TradeDirection.BUY, 15.30, 1000, D, new BigDecimal("14.80")),
                trade("t2", TradeDirection.SELL, 17.10, 500, D.plusDays(10), null),
                trade("t3", TradeDirection.BUY, 17.60, 700, D.plusDays(20), null),
                trade("t4", TradeDirection.BUY, 17.45, 300, D.plusDays(24), null)));
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(candles(15.0, 16.0, 17.0, 18.0, 19.0,
                20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 24.0, 23.0, 22.0, 21.0, 20.0, 19.5, 19.0,
                18.5, 18.0, 17.8, 17.6, 17.4, 17.2, 17.0, 16.8, 16.6, 16.4, 17.0, 18.0));

        Map<String, Object> r = new TradingKlineAppService(kline, m.history(), m.positions(), m.sold(), m.lots())
                .kline("u", SYM, 30);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> marks = (List<Map<String, Object>>) r.get("marks");
        assertEquals(List.of("B", "S", "T", "T"), marks.stream().map(x -> x.get("type")).toList());
    }

    // ── ③ 你定的止损：取最近一次买入时定的那条 ──

    @Test
    void 止损线取最近一次买入定下的价格() {
        Mocks m = mocks();
        when(m.history().findAll("u")).thenReturn(List.of(
                trade("t1", TradeDirection.BUY, 15.30, 1000, D, new BigDecimal("14.80")),
                trade("t2", TradeDirection.BUY, 17.60, 700, D.plusDays(3), new BigDecimal("16.20"))));
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(candles(15.0, 16.0, 17.0, 18.0, 19.0,
                20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 24.0, 23.0, 22.0, 21.0, 20.0, 19.0, 18.0,
                17.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0));

        Map<String, Object> r = new TradingKlineAppService(kline, m.history(), m.positions(), m.sold(), m.lots())
                .kline("u", SYM, 30);

        @SuppressWarnings("unchecked")
        Map<String, Object> stop = (Map<String, Object>) r.get("stopLine");
        assertNotNull(stop);
        assertEquals(16.20, (Double) stop.get("price"), 1e-9);
    }

    @Test
    void 没定过止损就不画这条线() {
        Mocks m = mocks();
        when(m.history().findAll("u")).thenReturn(List.of(
                trade("t1", TradeDirection.BUY, 15.30, 1000, D, null)));
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(candles(15.0, 16.0, 17.0, 18.0, 19.0,
                20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 24.0, 23.0, 22.0, 21.0, 20.0, 19.0, 18.0,
                17.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0));

        Map<String, Object> r = new TradingKlineAppService(kline, m.history(), m.positions(), m.sold(), m.lots())
                .kline("u", SYM, 30);
        assertNull(r.get("stopLine"), "没定过止损就不该凭空给一条线");
    }

    // ── ④ 峰值浮盈线：持有期内最高收盘 × (1 − 5%) ──

    @Test
    void 峰值浮盈线取持有期内最高收盘并回吐五个点() {
        Mocks m = mocks();
        when(m.history().findAll("u")).thenReturn(List.of(
                trade("t1", TradeDirection.BUY, 15.30, 1000, D, new BigDecimal("14.80"))));
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(candles(15.0, 16.0, 17.0, 18.0, 20.0,
                19.0, 18.0, 17.0, 16.0, 15.5, 15.4, 15.3, 15.2, 15.1, 15.0, 14.9, 14.8, 14.7,
                14.6, 14.5, 14.4, 14.3, 14.2, 14.1, 14.0, 13.9, 13.8, 13.7, 13.6, 13.5));

        Map<String, Object> r = new TradingKlineAppService(kline, m.history(), m.positions(), m.sold(), m.lots())
                .kline("u", SYM, 30);

        @SuppressWarnings("unchecked")
        Map<String, Object> peak = (Map<String, Object>) r.get("peakLine");
        assertNotNull(peak);
        assertEquals(19.00, (Double) peak.get("price"), 1e-9); // 20.0 × 0.95
        assertEquals(20.0, (Double) peak.get("peakPrice"), 1e-9);
    }

    @Test
    void 没有过持仓就没有峰值线() {
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(candles(15.0, 16.0, 17.0, 18.0, 19.0,
                20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 24.0, 23.0, 22.0, 21.0, 20.0, 19.0, 18.0,
                17.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0));
        Map<String, Object> r = service(kline, mocks()).kline("u", SYM, 30);
        assertNull(r.get("peakLine"));
    }

    // ── ⑤ 清仓股：带出「什么时候清的」上下文 ──

    @Test
    void 清仓股带出卖出日与这笔盈亏() {
        Mocks m = mocks();
        when(m.history().findAll("u")).thenReturn(List.of(
                trade("t1", TradeDirection.BUY, 15.30, 1000, D, new BigDecimal("14.80")),
                trade("t2", TradeDirection.SELL, 17.10, 1000, D.plusDays(20), null)));
        when(m.sold().findAll("u")).thenReturn(List.of(
                new SoldTrade(SYM, "洛阳钼业", D, D.plusDays(20), 25, "1", 9.6, "守纪律", null, "flow")));
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(candles(15.0, 16.0, 17.0, 18.0, 19.0,
                20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 24.0, 23.0, 22.0, 21.0, 20.0, 19.0, 18.0,
                17.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0));

        Map<String, Object> r = new TradingKlineAppService(kline, m.history(), m.positions(), m.sold(), m.lots())
                .kline("u", SYM, 30);

        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) r.get("context");
        assertEquals(false, ctx.get("held"));
        assertEquals(D.plusDays(20).toString(), ctx.get("closedAt"));
        assertEquals(9.6, (Double) ctx.get("holdPnlPct"), 1e-9);
    }

    @Test
    void 还拿着的时候上下文标记为持有() {
        Mocks m = mocks();
        when(m.positions().findBySymbol("u", SYM)).thenReturn(Optional.of(
                new Position(SYM, "洛阳钼业", 1500, new BigDecimal("17.32"), new BigDecimal("16.55"),
                        D.atTime(15, 0))));
        when(m.history().findAll("u")).thenReturn(List.of(
                trade("t1", TradeDirection.BUY, 15.30, 1500, D, new BigDecimal("14.80"))));
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(candles(15.0, 16.0, 17.0, 18.0, 19.0,
                20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 24.0, 23.0, 22.0, 21.0, 20.0, 19.0, 18.0,
                17.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0));

        Map<String, Object> r = new TradingKlineAppService(kline, m.history(), m.positions(), m.sold(), m.lots())
                .kline("u", SYM, 30);

        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) r.get("context");
        assertEquals(true, ctx.get("held"));
        assertNull(ctx.get("closedAt"));
    }
}
