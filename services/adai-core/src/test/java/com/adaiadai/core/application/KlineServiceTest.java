package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KlineService — 主源东财 + 腾讯兜底（C1，2026-08-16）。 */
class KlineServiceTest {

    private Candle c(int day, double close) {
        return new Candle(LocalDate.of(2026, 8, day), 10, 11, 9, close, 1000);
    }

    @Test
    void primaryReturnsCandles_noFallback() {
        KlineSource primary = mock(KlineSource.class);
        when(primary.kline(eq("600519"), anyInt()))
                .thenReturn(List.of(c(1, 10.5), c(2, 10.8)));
        MarketDataSource fallback = mock(MarketDataSource.class);
        KlineService svc = new KlineService(primary, fallback);

        List<Candle> result = svc.kline("600519", 120);

        assertEquals(2, result.size());
        assertEquals(10.8, result.get(1).close());
        verify(fallback, org.mockito.Mockito.never()).kline(anyString(), anyInt());
    }

    @Test
    void primaryEmpty_fallsBackToTencent() {
        KlineSource primary = mock(KlineSource.class);
        when(primary.kline(anyString(), anyInt())).thenReturn(List.of());
        MarketDataSource fallback = mock(MarketDataSource.class);
        when(fallback.kline(anyString(), anyInt()))
                .thenReturn(List.of(c(1, 9.8), c(2, 10.1), c(3, 10.3)));
        KlineService svc = new KlineService(primary, fallback);

        List<Candle> result = svc.kline("000725", 120);

        assertEquals(3, result.size(), "主源空 → 腾讯兜底");
        verify(fallback).kline(anyString(), anyInt());
    }

    @Test
    void bothEmpty_returnsEmpty() {
        KlineSource primary = mock(KlineSource.class);
        when(primary.kline(anyString(), anyInt())).thenReturn(List.of());
        MarketDataSource fallback = mock(MarketDataSource.class);
        when(fallback.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineService svc = new KlineService(primary, fallback);

        assertTrue(svc.kline("600519", 120).isEmpty());
    }
}
