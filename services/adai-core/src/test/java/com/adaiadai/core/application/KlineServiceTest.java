package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
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

/** KlineService — 主源/兜底可配置（C1，2026-08-16；2026-08-23 用户确认腾讯优先）。 */
class KlineServiceTest {

    private Candle c(int day, double close) {
        return new Candle(LocalDate.of(2026, 8, day), 10, 11, 9, close, 1000);
    }

    /** 便捷构造：tencent 主源（生产默认 2026-08-23 起）。 */
    private KlineService tencentFirst(KlineSource tencent, KlineSource eastMoney) {
        return new KlineService("tencent", eastMoney, tencent);
    }

    @Test
    void primaryReturnsCandles_noFallback() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(eq("600519"), anyInt()))
                .thenReturn(List.of(c(1, 10.5), c(2, 10.8)));
        KlineSource eastMoney = mock(KlineSource.class);
        KlineService svc = tencentFirst(tencent, eastMoney);

        List<Candle> result = svc.kline("600519", 120);

        assertEquals(2, result.size());
        assertEquals(10.8, result.get(1).close());
        verify(eastMoney, org.mockito.Mockito.never()).kline(anyString(), anyInt());
    }

    @Test
    void primaryEmpty_fallsBackToTencent() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt()))
                .thenReturn(List.of(c(1, 9.8), c(2, 10.1), c(3, 10.3)));
        KlineService svc = tencentFirst(tencent, eastMoney);

        List<Candle> result = svc.kline("000725", 120);

        assertEquals(3, result.size(), "主源空 → 东财兜底");
        verify(eastMoney).kline(anyString(), anyInt());
    }

    @Test
    void bothEmpty_returnsEmpty() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineService svc = tencentFirst(tencent, eastMoney);

        assertTrue(svc.kline("600519", 120).isEmpty());
    }

    @Test
    void eastMoneyPrimaryConfig_switchesOrder() {
        // 配置 kline-primary=eastmoney → 东财主源（历史行为，可配置回退）
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt()))
                .thenReturn(List.of(c(1, 10.5), c(2, 10.8)));
        KlineSource tencent = mock(KlineSource.class);
        KlineService svc = new KlineService("eastmoney", eastMoney, tencent);

        List<Candle> result = svc.kline("600519", 120);

        assertEquals(2, result.size());
        verify(tencent, org.mockito.Mockito.never()).kline(anyString(), anyInt());
    }

    // ── P2-1 熔断回归（2026-08-18 生产：东财被限刷 1154 次 WARN）──

    @Test
    void circuitBreaksAfterConsecutiveFailures() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of()); // 主源必失败
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt()))
                .thenReturn(List.of(c(1, 9.8), c(2, 10.1)));
        KlineService svc = tencentFirst(tencent, eastMoney);

        // 前两次失败 → 仍打主源
        svc.kline("600519", 120);
        svc.kline("600519", 120);
        org.mockito.Mockito.verify(tencent, org.mockito.Mockito.times(2)).kline(anyString(), anyInt());
        assertTrue(!svc.isCircuitOpen(), "未达阈值不应熔断");

        // 第三次失败 → 熔断
        svc.kline("600519", 120);
        assertTrue(svc.isCircuitOpen(), "连续失败达阈值应熔断");

        // 熔断期间 → 直接走兜底，不再打主源
        svc.kline("000725", 120);
        svc.kline("601318", 120);
        org.mockito.Mockito.verify(tencent, org.mockito.Mockito.times(3)).kline(anyString(), anyInt());
        org.mockito.Mockito.verify(eastMoney, org.mockito.Mockito.times(5)).kline(anyString(), anyInt());
    }

    @Test
    void circuitRecoversAfterPrimarySucceeds() {
        KlineSource tencent = mock(KlineSource.class);
        // 第一次失败 → 后续成功（主源恢复）
        when(tencent.kline(anyString(), anyInt()))
                .thenReturn(List.of())
                .thenReturn(List.of(c(1, 10.5), c(2, 10.8)));
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 9.8)));
        KlineService svc = tencentFirst(tencent, eastMoney);

        svc.kline("600519", 120); // 失败
        List<Candle> result = svc.kline("600519", 120); // 成功

        assertEquals(2, result.size());
        assertTrue(!svc.isCircuitOpen(), "主源恢复后不熔断");
        org.mockito.Mockito.verify(eastMoney, org.mockito.Mockito.times(1)).kline(anyString(), anyInt());
    }

    @Test
    void circuitHalfOpen_probesPrimaryAfterCooldown() throws Exception {
        KlineSource tencent = mock(KlineSource.class);
        // 触发熔断：全部失败
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 9.8)));
        KlineService svc = tencentFirst(tencent, eastMoney);

        svc.kline("600519", 120);
        svc.kline("600519", 120);
        svc.kline("600519", 120); // 熔断
        assertTrue(svc.isCircuitOpen());

        // 熔断期内不发主源
        svc.kline("600519", 120);
        org.mockito.Mockito.verify(tencent, org.mockito.Mockito.times(3)).kline(anyString(), anyInt());

        // 用反射推进时间越过冷却 → 半开恢复，下一次走主源探测
        java.lang.reflect.Field f = KlineService.class.getDeclaredField("circuitOpenUntil");
        f.setAccessible(true);
        f.setLong(svc, System.currentTimeMillis() - 1);
        assertTrue(!svc.isCircuitOpen(), "冷却结束后应恢复探测");
        svc.kline("600519", 120);
        org.mockito.Mockito.verify(tencent, org.mockito.Mockito.times(4)).kline(anyString(), anyInt());
    }
}
