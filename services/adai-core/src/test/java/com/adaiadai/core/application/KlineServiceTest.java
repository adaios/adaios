package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    /** TDX 本地源 mock（默认空——测试网络源主/兜底逻辑不受影响）。 */
    private KlineSource tdxEmpty() {
        KlineSource tdx = mock(KlineSource.class);
        when(tdx.kline(anyString(), anyInt())).thenReturn(List.of());
        when(tdx.klineRange(anyString(), any(), any())).thenReturn(List.of());
        return tdx;
    }

    /** 便捷构造：tencent 主源（生产默认 2026-08-23 起；TDX 空 = 网络源逻辑）。 */
    private KlineService tencentFirst(KlineSource tencent, KlineSource eastMoney) {
        return new KlineService("tencent",
                true,
                false,
                eastMoney,
                tencent,
                tdxEmpty(),
                null);
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
    void tdxStale_mergesNetworkTail() {
        // 2026-09-16 生产事故回归：tdx 数据包停在 09-04，而原来「有本地数据就整段返回」
        // → 09-05 起的 K 线凭空消失（资金曲线一路沿用旧价、周期盈亏算成 0、
        // 买点扫描 dataDate 永远不是当日 → 15:10 推送静默失效）。
        // 现在：本地补长历史、网络补尾部缺口（重叠按日期去重）。
        LocalDate stale = LocalDate.now().minusDays(12);
        LocalDate fresh = LocalDate.now().minusDays(1);
        KlineSource tdx = mock(KlineSource.class);
        when(tdx.klineRange(anyString(), any(), any())).thenReturn(List.of(
                new Candle(stale, 10, 11, 9, 10.0, 1000),
                new Candle(stale.plusDays(1), 10, 11, 9, 10.5, 1000)));
        when(tdx.kline(anyString(), anyInt())).thenReturn(List.of(
                new Candle(stale, 10, 11, 9, 10.0, 1000)));

        KlineSource tencent = mock(KlineSource.class);
        when(tencent.klineRange(anyString(), any(), any())).thenReturn(List.of(
                new Candle(stale.plusDays(1), 10, 11, 9, 10.5, 1000), // 与本地重叠 → 去重
                new Candle(fresh, 10, 11, 9, 12.0, 1000)));
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of(
                new Candle(fresh, 10, 11, 9, 12.0, 1000)));

        KlineService svc = new KlineService("tencent",
                true,
                false,
                mock(KlineSource.class),
                tencent,
                tdx,
                null);

        var range = svc.klineRange("600519", stale, LocalDate.now());
        assertEquals(3, range.size(), "本地 2 根 + 网络补 1 根（重叠去重）：" + range);
        assertEquals(fresh, range.get(range.size() - 1).date(), "末端应是网络源补的新数据（原来会缺）");
        assertEquals(12.0, range.get(range.size() - 1).close(), 0.001);

        var recent = svc.kline("600519", 5);
        assertEquals(fresh, recent.get(recent.size() - 1).date(), "滞后时 kline 也要走网络源");
    }

    @Test
    void tdxStaleAndNetworkDown_fallsBackToStaleLocalInsteadOfEmpty() {
        // P2-交易58（2026-09-17 B2 批）：tdx 停在过去、网络源（主源 + 兜底）也拿不到时，
        // 原实现在这里静默 `return local`，调用方无从知道末端缺了多少天；本次改为**如实记 WARN**
        // （含缺口天数）后**仍回退**，保证案例库历史窗口拿到尽可能连续的区间而不是空表。
        LocalDate stale = LocalDate.now().minusDays(12);
        KlineSource tdx = mock(KlineSource.class);
        when(tdx.klineRange(anyString(), any(), any())).thenReturn(List.of(
                new Candle(stale, 10, 11, 9, 10.0, 1000)));
        when(tdx.kline(anyString(), anyInt())).thenReturn(List.of(
                new Candle(stale, 10, 11, 9, 10.0, 1000)));

        KlineSource tencent = mock(KlineSource.class);
        when(tencent.klineRange(anyString(), any(), any())).thenReturn(List.of());
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.klineRange(anyString(), any(), any())).thenReturn(List.of());
        when(eastMoney.kline(anyString(), anyInt())).thenReturn(List.of());

        KlineService svc = new KlineService("tencent",
                true,
                false,
                eastMoney,
                tencent,
                tdx,
                null);

        var range = svc.klineRange("600519", stale, LocalDate.now());

        assertEquals(1, range.size(), "网络全挂 → 回退滞后的本地数据（而不是空表）：" + range);
        assertEquals(stale, range.get(0).date());
    }

    @Test
    void tdxFresh_noNetworkCall() {
        // 反向：本地新鲜 → 保持零网络请求（不因修复引入无谓开销）
        LocalDate fresh = LocalDate.now().minusDays(1);
        KlineSource tdx = mock(KlineSource.class);
        when(tdx.kline(anyString(), anyInt()))
                .thenReturn(List.of(new Candle(fresh, 10, 11, 9, 10.0, 1000)));
        when(tdx.klineRange(anyString(), any(), any()))
                .thenReturn(List.of(new Candle(fresh, 10, 11, 9, 10.0, 1000)));
        KlineSource tencent = mock(KlineSource.class);

        KlineService svc = new KlineService("tencent",
                true,
                false,
                mock(KlineSource.class),
                tencent,
                tdx,
                null);
        var r = svc.kline("600519", 5);
        assertEquals(fresh, r.get(0).date());
        org.mockito.Mockito.verifyNoInteractions(tencent);
        var rr = svc.klineRange("600519", fresh.minusDays(10), fresh);
        assertEquals(1, rr.size());
        org.mockito.Mockito.verifyNoInteractions(tencent);
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
        KlineService svc = new KlineService("eastmoney",
                true,
                false,
                eastMoney,
                tencent,
                tdxEmpty(),
                null);

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


    // ── RFC 20260923 B/D 批：最后一层兜底（新浪）+ 行情可用性可见 ──

    /** 带新浪层的完整构造（sinaEnabled=false 即行为退回改动前）。 */
    private KlineService withSina(KlineSource tencent, KlineSource eastMoney,
                                  KlineSource sina, boolean sinaEnabled) {
        return new KlineService("tencent", true, sinaEnabled, eastMoney, tencent, tdxEmpty(), sina);
    }

    @Test
    void primaryAndFallbackDown_sinaTakesOver() {
        // 2026-09-22 生产实况：腾讯被 WAF 拦 + 东财被限 → 新浪顶上（否则整条 K 线链路空）
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(eq("600519"), anyInt())).thenReturn(List.of(c(1, 10.5)));
        KlineService svc = withSina(tencent, eastMoney, sina, true);

        List<Candle> result = svc.kline("600519", 120);

        assertEquals(1, result.size(), "新浪应顶上");
        assertTrue(svc.health().ok(), "拿到行情 → 可用");
        assertEquals("新浪", svc.health().lastSuccessSource());
    }

    @Test
    void sinaDisabled_behaviourIsAsBefore() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 10.5)));
        KlineService svc = withSina(tencent, eastMoney, sina, false);

        assertTrue(svc.kline("600519", 120).isEmpty(), "关掉新浪 = 返回空（不是偷偷用它）");
        org.mockito.Mockito.verify(sina, org.mockito.Mockito.never()).kline(anyString(), anyInt());
    }

    @Test
    void health_allSourcesDown_isNotOkAndNamesTheSymbol() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineService svc = withSina(tencent, eastMoney, sina, true);

        svc.kline("600519", 120);
        KlineService.Health h = svc.health();

        org.junit.jupiter.api.Assertions.assertFalse(h.ok(), "全拿不到 → 不可用");
        assertEquals(1, h.consecutiveFailures());
        assertEquals("600519", h.lastFailedSymbol());
        assertTrue(h.note().contains("没拿到"), "人话里要说清「没拿到」而不是沉默：" + h.note());
        assertEquals(List.of("tdx", "腾讯", "东财", "新浪"), h.sources());
    }

    @Test
    void health_successAfterFailures_resetsAndReportsSource() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource eastMoney = mock(KlineSource.class);
        when(eastMoney.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineService svc = withSina(tencent, eastMoney, sina, true);
        svc.kline("600519", 120);
        assertEquals(1, svc.health().consecutiveFailures());

        // 新浪恢复 → 计数清零、来源如实标
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 10.5)));
        svc.kline("600519", 120);

        KlineService.Health h = svc.health();
        assertTrue(h.ok());
        assertEquals(0, h.consecutiveFailures(), "成功即清零");
        assertEquals("新浪", h.lastSuccessSource());
    }

    @Test
    void health_neverQueried_isCalmAndSaysSo() {
        KlineService svc = withSina(mock(KlineSource.class), mock(KlineSource.class),
                mock(KlineSource.class), true);
        KlineService.Health h = svc.health();
        assertTrue(h.ok(), "还没查过 ≠ 不可用（不制造假警报）");
        assertTrue(h.note().contains("还没查过"), h.note());
    }
}
