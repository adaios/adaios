package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AdviceEntry;
import com.adaiadai.core.domain.trading.AdviceHistoryRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.market.Candle;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdviceOutcomeServiceTest — 建议结果回填（RFC 20260922 A 批 A3）。
 *
 * <p>核心不是"算得对"，而是**不编**：数据不全（K 线缺 / 还没走满 N 个交易日 / 分母为 0）时
 * 宁可**不写** outcome（下次再试），也绝不写一个半截的"结果"；已有 outcome 不覆盖（幂等）。
 */
class AdviceOutcomeServiceTest {

    private static final String USER = "adai";
    private static final int N = 5;

    private Candle candle(LocalDate d, double close) {
        return new Candle(d, close, close, close, close, 1000);
    }

    /** 连续 N+2 个交易日的 K 线（日期连续即可，服务只按顺序取第 N 根）。 */
    private List<Candle> candles(LocalDate from, int count, double first, double step) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(candle(from.plusDays(i), first + step * i));
        return out;
    }

    private AdviceEntry entry(LocalDate date, String symbol, String outcome) {
        return new AdviceEntry(null, date, symbol, symbol + "名", "reduce", "按 R66", List.of("R66"),
                false, new BigDecimal("20.0"), "manual-advice", LocalDateTime.now(),
                "{\"price\":\"10.0\"}", outcome);
    }

    private AdviceOutcomeService service(AdviceHistoryRepository history,
                                         TradingHistoryRepository trades, KlineService kline) {
        return new AdviceOutcomeService(history, trades, kline, N);
    }

    /**
     * 按月返回（贴近真实仓储）：{@code findByMonth(month)} **只返回该月**的条目——
     * 服务会扫近 3 个月，若 mock 不区分月份，同一条会被跨月重复回填（测试假象，不是线上行为）。
     */
    private void stubMonth(AdviceHistoryRepository history, AdviceEntry e) {
        when(history.findByMonth(anyString(), any())).thenAnswer(inv -> {
            LocalDate month = inv.getArgument(1);
            return e.date() != null && month != null
                    && month.getYear() == e.date().getYear()
                    && month.getMonth() == e.date().getMonth()
                    ? List.of(e) : List.of();
        });
    }

    @Test
    void backfill_writesOutcomeWithPricesAndPct() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        LocalDate adviceDate = today.minusDays(12);
        AdviceEntry e = entry(adviceDate, "600206", null);
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        stubMonth(history, e);
        when(history.updateOutcome(anyString(), any(), anyString(), anyString())).thenReturn(true);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(eq("600206"), any(), any()))
                .thenReturn(candles(adviceDate, 10, 10.0, 1.0)); // 第 5 根 = 15.0
        TradingHistoryRepository trades = mock(TradingHistoryRepository.class);
        when(trades.findAll(anyString())).thenReturn(List.of());

        int written = service(history, trades, kline).backfill(USER, today);

        assertEquals(1, written);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(history).updateOutcome(eq(USER), eq(adviceDate), anyString(), captor.capture());
        String json = captor.getValue();
        assertTrue(json.contains("\"afterDays\":5"), json);
        assertTrue(json.contains("\"priceThen\":10.0"), json);
        assertTrue(json.contains("\"priceAfter\":15.0"), json);
        assertTrue(json.contains("\"pct\":50.0"), json);
        assertTrue(json.contains("\"userActed\":\"none\""), json);
    }

    /** 还没走满 N 个交易日 → **不写**（等下次），而不是写一个"当前涨幅"冒充结果。 */
    @Test
    void backfill_notEnoughTradingDaysYet_leavesBlank() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        LocalDate adviceDate = today.minusDays(12);
        AdviceEntry e = entry(adviceDate, "600206", null);
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        stubMonth(history, e);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(eq("600206"), any(), any()))
                .thenReturn(candles(adviceDate, N, 10.0, 1.0)); // 只有 5 根 → 取不到第 5 根之后

        int written = service(history, mock(TradingHistoryRepository.class), kline).backfill(USER, today);

        assertEquals(0, written);
        verify(history, never()).updateOutcome(anyString(), any(), anyString(), anyString());
    }

    /** 已有 outcome → 跳过（幂等：结果只写一次，重复回填不得改写历史）。 */
    @Test
    void backfill_alreadyFilled_isSkipped() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        AdviceEntry e = entry(today.minusDays(12), "600206", "{\"afterDays\":5}");
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        stubMonth(history, e);
        KlineService kline = mock(KlineService.class);

        int written = service(history, mock(TradingHistoryRepository.class), kline).backfill(USER, today);

        assertEquals(0, written);
        verify(kline, never()).klineRange(anyString(), any(), any());
    }

    /** 行情取不到 / K 线为空 → 不写半成品。 */
    @Test
    void backfill_noKline_leavesBlank() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        AdviceEntry e = entry(today.minusDays(12), "600206", null);
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        stubMonth(history, e);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(anyString(), any(), any())).thenReturn(List.of());

        assertEquals(0, service(history, mock(TradingHistoryRepository.class), kline).backfill(USER, today));
        verify(history, never()).updateOutcome(anyString(), any(), anyString(), anyString());
    }

    /** 建议日之后用户有动作 → userActed=traded（只记「有没有操作」，不是「听没听」）。 */
    @Test
    void backfill_detectsUserAction() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        LocalDate adviceDate = today.minusDays(12);
        AdviceEntry e = entry(adviceDate, "600206", null);
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        stubMonth(history, e);
        when(history.updateOutcome(anyString(), any(), anyString(), anyString())).thenReturn(true);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(anyString(), any(), any())).thenReturn(candles(adviceDate, 10, 10.0, 1.0));
        TradingHistoryRepository trades = mock(TradingHistoryRepository.class);
        when(trades.findAll(anyString())).thenReturn(List.of(TradeRecord.of(
                "t1", "600206", "有研新材", TradeDirection.SELL, new BigDecimal("12.0"), 100,
                adviceDate.plusDays(2), LocalTime.of(14, 50), null, null, null, null,
                new BigDecimal("1.79"), LocalDateTime.now(), null, "order1")));

        service(history, trades, kline).backfill(USER, today);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(history).updateOutcome(eq(USER), eq(adviceDate), anyString(), captor.capture());
        assertTrue(captor.getValue().contains("\"userActed\":\"traded\""), captor.getValue());
    }

    /** 流水读不到 → userActed=unknown（**不谎报 none**：「不知道」和「没操作」是两回事）。 */
    @Test
    void backfill_tradeReadFailure_reportsUnknownNotNone() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        LocalDate adviceDate = today.minusDays(12);
        AdviceEntry e = entry(adviceDate, "600206", null);
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        stubMonth(history, e);
        when(history.updateOutcome(anyString(), any(), anyString(), anyString())).thenReturn(true);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(anyString(), any(), any())).thenReturn(candles(adviceDate, 10, 10.0, 1.0));
        TradingHistoryRepository trades = mock(TradingHistoryRepository.class);
        when(trades.findAll(anyString())).thenThrow(new IllegalStateException("流水文件损坏"));

        service(history, trades, kline).backfill(USER, today);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(history).updateOutcome(eq(USER), eq(adviceDate), anyString(), captor.capture());
        assertTrue(captor.getValue().contains("\"userActed\":\"unknown\""), captor.getValue());
        assertFalse(captor.getValue().contains("\"userActed\":\"none\""));
    }

    /** 太新的建议（自然日不够 2×N）→ 连行情都不问（省请求）。 */
    @Test
    void backfill_tooRecent_skipsBeforeAskingMarket() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        AdviceEntry e = entry(today.minusDays(3), "600206", null);
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        stubMonth(history, e);
        KlineService kline = mock(KlineService.class);

        assertEquals(0, service(history, mock(TradingHistoryRepository.class), kline).backfill(USER, today));
        verify(kline, never()).klineRange(anyString(), any(), any());
    }
}
