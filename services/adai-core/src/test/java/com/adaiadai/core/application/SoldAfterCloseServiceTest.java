package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SoldAfterCloseServiceTest — 清仓「卖掉之后到现在」（交易插件 UI/UX 重做批）。
 * <p>
 * 口径权威在 SoldAfterCloseService 类注释：基准 = 卖出日（或其后第一根）收盘，最新 = 区间最后一根；
 * 拿不到一律 {@code pct=null + note}，绝不编。
 */
@ExtendWith(MockitoExtension.class)
class SoldAfterCloseServiceTest {

    @Mock
    private KlineService klineService;

    private SoldAfterCloseService service() {
        return new SoldAfterCloseService(klineService);
    }

    private static final LocalDate SELL = LocalDate.of(2026, 8, 19);
    private static final LocalDate LATEST = LocalDate.of(2026, 10, 7);

    /** 造一根 K 线（本服务只读 date/close）。 */
    private static Candle candle(LocalDate date, double close) {
        return new Candle(date, close, close, close, close, 10_000);
    }

    /** 一笔普通清仓（介入 = 卖出日往前 20 天）。 */
    private static SoldTrade trade(String symbol, LocalDate sellDate) {
        return new SoldTrade(symbol, "票" + symbol, SELL.minusDays(20), sellDate, 20,
                "1+1", 5.0, "盈利了结", "");
    }

    private void stubKline(String symbol, List<Candle> candles) {
        when(klineService.klineRange(eq(symbol), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(candles);
    }

    @Test
    void 卖后上涨_up_走早了() {
        stubKline("603993", List.of(
                candle(SELL, 12.34),
                candle(SELL.plusDays(10), 13.0),
                candle(LATEST, 14.61)));
        SoldAfterCloseService.AfterClose r =
                service().compute(List.of(trade("603993", SELL))).get(0);
        assertEquals("up", r.direction());
        assertEquals(18.4, r.pct());
        assertEquals("2026-08-19", r.baseDate());
        assertEquals(12.34, r.baseClose());
        assertEquals("2026-10-07", r.latestDate());
        assertEquals(14.61, r.latestClose());
        assertNull(r.note());
    }

    @Test
    void 卖后下跌_down_走对了() {
        stubKline("000725", List.of(
                candle(SELL, 10.0),
                candle(LATEST, 9.89)));
        SoldAfterCloseService.AfterClose r =
                service().compute(List.of(trade("000725", SELL))).get(0);
        assertEquals("down", r.direction());
        assertEquals(-1.1, r.pct());
        assertNull(r.note());
    }

    /** 关键自洽防线：pct 保留 2 位 = 0.04（微涨），但按 1 位判是 flat——不会「显示 0.0% 却标 ↑」。 */
    @Test
    void 微涨_一位后持平_flat_不误标涨() {
        stubKline("600000", List.of(
                candle(SELL, 10.00),
                candle(LATEST, 10.004)));
        SoldAfterCloseService.AfterClose r =
                service().compute(List.of(trade("600000", SELL))).get(0);
        assertEquals(0.04, r.pct());
        assertEquals("flat", r.direction());
    }

    @Test
    void K线空_行情取不到() {
        stubKline("600519", List.of());
        SoldAfterCloseService.AfterClose r =
                service().compute(List.of(trade("600519", SELL))).get(0);
        assertNull(r.pct());
        assertNull(r.direction());
        assertEquals("行情取不到", r.note());
    }

    /** 一年以上的老清仓：K 线源覆盖不到卖出日，第一根离得太远——不能拿它冒充基准。 */
    @Test
    void 行情覆盖不到卖出日_如实说() {
        stubKline("600157", List.of(
                candle(LocalDate.of(2026, 9, 1), 8.0),   // 距 SELL 13 天，超容差
                candle(LATEST, 8.5)));
        SoldAfterCloseService.AfterClose r =
                service().compute(List.of(trade("600157", SELL))).get(0);
        assertNull(r.pct());
        assertEquals("行情覆盖不到卖掉那天", r.note());
    }

    /** 边界：第一根距卖出日恰好 10 天（容差内，如长停牌后复牌首根）→ 仍可用，基准取该根。 */
    @Test
    void 容差边界_恰好十天仍可用() {
        stubKline("600157", List.of(
                candle(SELL.plusDays(10), 8.0),
                candle(LATEST, 9.6)));
        SoldAfterCloseService.AfterClose r =
                service().compute(List.of(trade("600157", SELL))).get(0);
        assertEquals("2026-08-29", r.baseDate());
        assertEquals(20.0, r.pct());
        assertNull(r.note());
    }

    @Test
    void 没记清仓日期_如实说() {
        SoldTrade t = new SoldTrade("000001", "平安银行", null, null, 0, "", 0, "", "");
        SoldAfterCloseService.AfterClose r = service().compute(List.of(t)).get(0);
        assertNull(r.pct());
        assertNull(r.sellDate());
        assertEquals("没记清仓日期", r.note());
    }

    /** 刚卖：区间只有卖出日当天一根 → 基准 = 最新 → 0.0% flat（不是 null，如实是「没动」）。 */
    @Test
    void 刚卖只有一根_flat零() {
        stubKline("601066", List.of(candle(SELL, 12.0)));
        SoldAfterCloseService.AfterClose r =
                service().compute(List.of(trade("601066", SELL))).get(0);
        assertEquals(0.0, r.pct());
        assertEquals("flat", r.direction());
        assertEquals(r.baseDate(), r.latestDate());
    }

    /** 单笔超时/异常不炸整批：该笔如实 null，其余照常，顺序保持。 */
    @Test
    void 单笔异常不炸整批_其余照常() {
        when(klineService.klineRange(eq("AAA111"), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("mock 网络炸了"));
        stubKline("BBB222", List.of(
                candle(SELL, 10.0),
                candle(LATEST, 11.5)));
        List<SoldAfterCloseService.AfterClose> out =
                service().compute(List.of(trade("AAA111", SELL), trade("BBB222", SELL)));
        assertEquals(2, out.size());
        assertEquals("AAA111", out.get(0).symbol());
        assertNull(out.get(0).pct());
        assertEquals("行情取不到", out.get(0).note());
        assertEquals("BBB222", out.get(1).symbol());
        assertEquals("up", out.get(1).direction());
        assertEquals(15.0, out.get(1).pct());
    }
}
