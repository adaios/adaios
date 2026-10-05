package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KlineService — 链路 {@code tdx → 腾讯（双域名）→ 新浪}。
 * <p>
 * RFC 20260928 批 2（2026-09-28）：东财 K 线源出链路（长期不可达），新浪由「最后一层」升为**兜底**；
 * 本地数据包按用户「一周导入一次」的节奏降噪。构造签名随之变为
 * {@code (tdxEnabled, sinaEnabled, tencent, tdx, sina)}。
 */
class KlineServiceTest {

    private Candle c(int day, double close) {
        return new Candle(LocalDate.of(2026, 8, day), 10, 11, 9, close, 1000);
    }

    /** TDX 本地源 mock（默认空——测试网络源逻辑不受影响）。 */
    private KlineSource tdxEmpty() {
        KlineSource tdx = mock(KlineSource.class);
        when(tdx.kline(anyString(), anyInt())).thenReturn(List.of());
        when(tdx.klineRange(anyString(), any(), any())).thenReturn(List.of());
        return tdx;
    }

    /** 便捷构造：腾讯主源 + 新浪兜底（TDX 空）。 */
    private KlineService tencentFirst(KlineSource tencent, KlineSource sina) {
        return new KlineService(true, true, tencent, tdxEmpty(), sina);
    }

    @Test
    void primaryReturnsCandles_noFallback() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(eq("600519"), anyInt()))
                .thenReturn(List.of(c(1, 10.5), c(2, 10.8)));
        KlineSource sina = mock(KlineSource.class);
        KlineService svc = tencentFirst(tencent, sina);

        List<Candle> result = svc.kline("600519", 120);

        assertEquals(2, result.size());
        assertEquals(10.8, result.get(1).close());
        verify(sina, never()).kline(anyString(), anyInt());
    }

    @Test
    void primaryEmpty_fallsBackToSina() {
        // RFC 20260928 批 2：兜底位从东财换成新浪
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt()))
                .thenReturn(List.of(c(1, 9.8), c(2, 10.1), c(3, 10.3)));
        KlineService svc = tencentFirst(tencent, sina);

        List<Candle> result = svc.kline("000725", 120);

        assertEquals(3, result.size(), "主源空 → 新浪兜底");
        verify(sina).kline(anyString(), anyInt());
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

        KlineService svc = new KlineService(true, true, tencent, tdx, mock(KlineSource.class));

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
        KlineSource sina = mock(KlineSource.class);
        when(sina.klineRange(anyString(), any(), any())).thenReturn(List.of());
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of());

        KlineService svc = new KlineService(true, true, tencent, tdx, sina);

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

        KlineService svc = new KlineService(true, true, tencent, tdx, mock(KlineSource.class));
        var r = svc.kline("600519", 5);
        assertEquals(fresh, r.get(0).date());
        verifyNoInteractions(tencent);
        var rr = svc.klineRange("600519", fresh.minusDays(10), fresh);
        assertEquals(1, rr.size());
        verifyNoInteractions(tencent);
    }

    @Test
    void tdxDisabled_neverTouchesLocalSource() {
        // RFC 20260928 批 2 补的 env 挂钩（ADAI_TDX_ENABLED=false）要真的能关掉本地源
        KlineSource tdx = mock(KlineSource.class);
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 10.5)));
        KlineService svc = new KlineService(false, true, tencent, tdx, mock(KlineSource.class));

        assertEquals(1, svc.kline("600519", 5).size());
        verifyNoInteractions(tdx);
        assertEquals(List.of("腾讯", "新浪"), svc.health().sources(), "关掉本地 → 链路只剩两段");
        assertNull(svc.health().tdxLastDate());
    }

    @Test
    void bothEmpty_returnsEmpty() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineService svc = tencentFirst(tencent, sina);

        assertTrue(svc.kline("600519", 120).isEmpty());
    }

    // ── P2-1 熔断回归（2026-08-18 生产：东财被限刷 1154 次 WARN）──

    @Test
    void circuitBreaksAfterConsecutiveFailures() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of()); // 主源必失败
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt()))
                .thenReturn(List.of(c(1, 9.8), c(2, 10.1)));
        KlineService svc = tencentFirst(tencent, sina);

        // 前两次失败 → 仍打主源
        svc.kline("600519", 120);
        svc.kline("600519", 120);
        verify(tencent, times(2)).kline(anyString(), anyInt());
        assertFalse(svc.isCircuitOpen(), "未达阈值不应熔断");

        // 第三次失败 → 熔断
        svc.kline("600519", 120);
        assertTrue(svc.isCircuitOpen(), "连续失败达阈值应熔断");

        // 熔断期间 → 直接走兜底，不再打主源
        svc.kline("000725", 120);
        svc.kline("601318", 120);
        verify(tencent, times(3)).kline(anyString(), anyInt());
        verify(sina, times(5)).kline(anyString(), anyInt());
    }

    @Test
    void circuitRecoversAfterPrimarySucceeds() {
        KlineSource tencent = mock(KlineSource.class);
        // 第一次失败 → 后续成功（主源恢复）
        when(tencent.kline(anyString(), anyInt()))
                .thenReturn(List.of())
                .thenReturn(List.of(c(1, 10.5), c(2, 10.8)));
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 9.8)));
        KlineService svc = tencentFirst(tencent, sina);

        svc.kline("600519", 120); // 失败
        List<Candle> result = svc.kline("600519", 120); // 成功

        assertEquals(2, result.size());
        assertFalse(svc.isCircuitOpen(), "主源恢复后不熔断");
        verify(sina, times(1)).kline(anyString(), anyInt());
    }

    @Test
    void circuitHalfOpen_probesPrimaryAfterCooldown() throws Exception {
        KlineSource tencent = mock(KlineSource.class);
        // 触发熔断：全部失败
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 9.8)));
        KlineService svc = tencentFirst(tencent, sina);

        svc.kline("600519", 120);
        svc.kline("600519", 120);
        svc.kline("600519", 120); // 熔断
        assertTrue(svc.isCircuitOpen());

        // 熔断期内不发主源
        svc.kline("600519", 120);
        verify(tencent, times(3)).kline(anyString(), anyInt());

        // 用反射推进时间越过冷却 → 半开恢复，下一次走主源探测
        java.lang.reflect.Field f = KlineService.class.getDeclaredField("circuitOpenUntil");
        f.setAccessible(true);
        f.setLong(svc, System.currentTimeMillis() - 1);
        assertFalse(svc.isCircuitOpen(), "冷却结束后应恢复探测");
        svc.kline("600519", 120);
        verify(tencent, times(4)).kline(anyString(), anyInt());
    }

    // ── 兜底层（新浪）+ 行情可用性可见 ──

    @Test
    void primaryAndFallbackDown_fallbackTakesOver() {
        // 2026-09-22 生产实况：腾讯被 WAF 拦 + 东财被限 → 兜底层顶上（否则整条 K 线链路空）
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(eq("600519"), anyInt())).thenReturn(List.of(c(1, 10.5)));
        KlineService svc = tencentFirst(tencent, sina);

        List<Candle> result = svc.kline("600519", 120);

        assertEquals(1, result.size(), "兜底层应顶上");
        assertTrue(svc.health().ok(), "拿到行情 → 可用");
        assertEquals("新浪", svc.health().lastSuccessSource());
    }

    @Test
    void sinaDisabled_returnsEmptyInsteadOfUsingIt() {
        // 关掉兜底 = 链路退回「tdx → 腾讯」两段；主源空 → 返回空（不是偷偷用新浪）
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 10.5)));
        KlineService svc = new KlineService(true, false, tencent, tdxEmpty(), sina);

        assertTrue(svc.kline("600519", 120).isEmpty(), "关掉兜底 → 主源空即空");
        verify(sina, never()).kline(anyString(), anyInt());
        assertEquals(List.of("tdx", "腾讯"), svc.health().sources());
    }

    @Test
    void health_allSourcesDown_isNotOkAndNamesTheSymbol() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineService svc = tencentFirst(tencent, sina);

        svc.kline("600519", 120);
        KlineService.Health h = svc.health();

        assertFalse(h.ok(), "全拿不到 → 不可用");
        assertEquals(1, h.consecutiveFailures());
        assertEquals("600519", h.lastFailedSymbol());
        assertTrue(h.note().contains("没拿到"), "人话里要说清「没拿到」而不是沉默：" + h.note());
        assertEquals(List.of("tdx", "腾讯", "新浪"), h.sources(), "东财已出链路（RFC 20260928 批 2）");
    }

    @Test
    void health_successAfterFailures_resetsAndReportsSource() {
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineService svc = tencentFirst(tencent, sina);
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
    void health_reportsTdxLastDate() {
        // RFC 20260928 批 2 第 6 条：本地数据包停在哪天要能一眼看到——
        // 用户一周导入一次，「该导包了」的判据不再只靠日志
        LocalDate stale = LocalDate.now().minusDays(12);
        KlineSource tdx = mock(KlineSource.class);
        when(tdx.kline(anyString(), anyInt()))
                .thenReturn(List.of(new Candle(stale, 10, 11, 9, 10.0, 1000)));
        when(tdx.klineRange(anyString(), any(), any())).thenReturn(List.of());
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 12.0)));
        KlineService svc = new KlineService(true, true, tencent, tdx, mock(KlineSource.class));

        assertNull(svc.health().tdxLastDate(), "还没取过 → null（不编一个日期）");
        svc.kline("600519", 5);
        assertEquals(stale.toString(), svc.health().tdxLastDate());
    }

    @Test
    void health_neverQueried_isCalmAndSaysSo() {
        KlineService svc = tencentFirst(mock(KlineSource.class), mock(KlineSource.class));
        KlineService.Health h = svc.health();
        assertTrue(h.ok(), "还没查过 ≠ 不可用（不制造假警报）");
        assertTrue(h.note().contains("还没查过"), h.note());
    }

    @Test
    void tdxStaleLogging_isOncePerDate_evenWhenDatesAlternate() {
        // REVIEW P2-交易74 回归：去重键曾是**单个** volatile 变量，而生产上不同标的的 tdx 末尾日期
        // 有多个在轮转（实测 09-24 与 09-18 并存）→ 交替覆盖 → 每次都判成「新日期」又记一条
        // （乒乓效应：3 次自选扫描实测 39 条）。改成集合 + 原子 add 后，每个滞后日期只应记一条
        // （本用例两个日期交替调用 4 次 → 期望 2 条；旧实现会是 4 条）。
        LocalDate staleA = LocalDate.now().minusDays(12);
        LocalDate staleB = LocalDate.now().minusDays(20);
        KlineSource tdx = mock(KlineSource.class);
        when(tdx.kline(anyString(), anyInt()))
                .thenReturn(List.of(new Candle(staleA, 10, 11, 9, 10.0, 1000)))
                .thenReturn(List.of(new Candle(staleB, 10, 11, 9, 10.0, 1000)))
                .thenReturn(List.of(new Candle(staleA, 10, 11, 9, 10.0, 1000)))
                .thenReturn(List.of(new Candle(staleB, 10, 11, 9, 10.0, 1000)));
        when(tdx.klineRange(anyString(), any(), any())).thenReturn(List.of());
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 12.0)));
        KlineService svc = new KlineService(true, true, tencent, tdx, mock(KlineSource.class));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KlineService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 0; i < 4; i++) svc.kline("600519", 5);
        } finally {
            logger.detachAppender(appender);
        }

        long staleLogs = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("tdx 数据滞后"))
                .count();
        assertEquals(2, staleLogs, "两个滞后日期交替出现，各只应记一条（乒乓效应回归）：实际 " + staleLogs);
    }

    @Test
    void tdxGapWarning_isOncePerStaleDate() {
        // 同型封堵回归（2026-09-28 独立审查 P3-6 → 升 P2）：klineRange 的「tdx 缺口补齐失败」原为
        // **逐标的、每次调用**都记 —— 生产近 7 天 1192 条（172 只标的），而「本地止于」只有 4 种日期。
        // 按日期去重后，同一「本地止于」只应记一条（本用例连调 5 次 → 期望 1 条）。
        LocalDate stale = LocalDate.now().minusDays(20);
        KlineSource tdx = mock(KlineSource.class);
        when(tdx.klineRange(anyString(), any(), any()))
                .thenReturn(List.of(new Candle(stale, 10, 11, 9, 10.0, 1000)));
        when(tdx.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.klineRange(anyString(), any(), any())).thenReturn(List.of()); // 缺口补不上
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of());
        KlineSource sina = mock(KlineSource.class);
        when(sina.klineRange(anyString(), any(), any())).thenReturn(List.of());
        KlineService svc = new KlineService(true, true, tencent, tdx, sina);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KlineService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 0; i < 5; i++) {
                assertEquals(1, svc.klineRange("600519", stale.minusDays(30), LocalDate.now()).size(),
                        "网络补不上 → 回退滞后的本地数据（不是空表）");
            }
        } finally {
            logger.detachAppender(appender);
        }

        long gapLogs = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("tdx 缺口补齐失败"))
                .count();
        assertEquals(1, gapLogs, "同一「本地止于」日期只应记一条（同型封堵回归）：实际 " + gapLogs);
    }

    // ── REVIEW P2-交易58 收口：兜底源（新浪）主动体检（2026-10-04）──
    // 背景：兜底平时零调用、零体检，只在主源熔断时才被批量打过去——那恰是它最可能也挂的时刻。
    // 这组用例钉三件事：① 探测真的打兜底源、结果如实进 health；② 失败/异常不抛、不影响主链路；
    // ③ 非交易日 / 非交易时段不探（不白打网络），且时钟/日历判断用 spy 固定，**不吃真实日历**。

    /** 探测窗口内的被测对象：isProbeWindow 固定 true（避免逢节假日/盘后测试变红）。 */
    private KlineService probing(KlineSource tencent, KlineSource sina) {
        KlineService svc = spy(new KlineService(true, true, tencent, tdxEmpty(), sina));
        doReturn(true).when(svc).isProbeWindow();
        return svc;
    }

    @Test
    void probeFallback_sourceUp_healthSaysHealthy() {
        KlineSource sina = mock(KlineSource.class);
        when(sina.probe(anyString())).thenReturn(true);
        KlineService svc = probing(mock(KlineSource.class), sina);

        assertNull(svc.health().fallbackHealthy(), "还没探过 → null（不编一个健康）");
        assertNull(svc.health().fallbackLastProbeAt());

        svc.probeFallbackSource();

        KlineService.Health h = svc.health();
        assertEquals(Boolean.TRUE, h.fallbackHealthy(), "探到数据 → 健康");
        assertNotNull(h.fallbackLastProbeAt(), "探测时刻要留痕（人能看到「最后一次体检是什么时候」）");
        verify(sina).probe(anyString());
    }

    @Test
    void probeFallback_sourceDown_reportsFalse_andDoesNotThrow() {
        KlineSource sina = mock(KlineSource.class);
        when(sina.probe(anyString())).thenReturn(false);
        KlineService svc = probing(mock(KlineSource.class), sina);

        assertDoesNotThrow(svc::probeFallbackSource, "探测失败不许抛（跑在调度线程上）");

        KlineService.Health h = svc.health();
        assertEquals(Boolean.FALSE, h.fallbackHealthy(), "探不到 → 如实报 false");
        assertNotNull(h.fallbackLastProbeAt());
    }

    @Test
    void probeFallback_probeThrows_isSwallowedAsUnhealthy() {
        // KlineSource#probe 的约定是不抛；这里模拟「某个实现违约抛了」——KlineService 必须再兜一层
        KlineSource sina = mock(KlineSource.class);
        when(sina.probe(anyString())).thenThrow(new IllegalStateException("connection reset"));
        KlineService svc = probing(mock(KlineSource.class), sina);

        assertDoesNotThrow(svc::probeFallbackSource);
        assertEquals(Boolean.FALSE, svc.health().fallbackHealthy());
    }

    @Test
    void probeFallback_doesNotPolluteLinkHealth() {
        // 探测只反映**兜底源自己**：主源正常时兜底挂了，链路仍是 ok（否则会误报「行情不可用」）
        KlineSource tencent = mock(KlineSource.class);
        when(tencent.kline(anyString(), anyInt())).thenReturn(List.of(c(1, 10.5)));
        KlineSource sina = mock(KlineSource.class);
        when(sina.probe(anyString())).thenReturn(false);
        KlineService svc = probing(tencent, sina);

        svc.kline("600519", 5);          // 主源正常
        svc.probeFallbackSource();       // 兜底挂了

        KlineService.Health h = svc.health();
        assertTrue(h.ok(), "兜底体检不过 ≠ 链路不可用（不制造假警报）");
        assertEquals("腾讯", h.lastSuccessSource());
        assertNull(h.lastFailureAt(), "探测不得写链路失败时刻");
        assertEquals(0, h.consecutiveFailures(), "探测不得动熔断计数");
    }

    @Test
    void probeFallback_nonTradingDay_skipsWithoutTouchingSource() {
        KlineSource sina = mock(KlineSource.class);
        KlineService svc = spy(new KlineService(true, true, mock(KlineSource.class), tdxEmpty(), sina));
        doReturn(false).when(svc).isTradingDayToday(); // 周末 / 法定节假日
        doReturn(true).when(svc).inTradingSession(any());

        svc.probeFallbackSource();

        verify(sina, never()).probe(anyString());
        assertNull(svc.health().fallbackHealthy(), "没探过就保持 null（不假装探过）");
    }

    @Test
    void probeFallback_outsideTradingSession_skipsWithoutTouchingSource() {
        KlineSource sina = mock(KlineSource.class);
        KlineService svc = spy(new KlineService(true, true, mock(KlineSource.class), tdxEmpty(), sina));
        doReturn(true).when(svc).isTradingDayToday();
        doReturn(false).when(svc).inTradingSession(any()); // 盘前/午休/盘后

        svc.probeFallbackSource();

        verify(sina, never()).probe(anyString());
        assertNull(svc.health().fallbackHealthy());
    }

    @Test
    void probeFallback_fallbackDisabled_skipsAndStaysNull() {
        KlineSource sina = mock(KlineSource.class);
        KlineService svc = spy(new KlineService(true, false, mock(KlineSource.class), tdxEmpty(), sina));
        doReturn(true).when(svc).isProbeWindow();

        svc.probeFallbackSource();

        verify(sina, never()).probe(anyString());
        assertNull(svc.health().fallbackHealthy(), "兜底关闭 → 没有体检对象（启用与否看 sources）");
        assertEquals(List.of("tdx", "腾讯"), svc.health().sources());
    }

    @Test
    void probeFallback_failureAlert_isCooldowned_repeatedFailureIsWarn() {
        KlineSource sina = mock(KlineSource.class);
        when(sina.probe(anyString())).thenReturn(false);
        KlineService svc = probing(mock(KlineSource.class), sina);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KlineService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            svc.probeFallbackSource();
            svc.probeFallbackSource(); // 冷却期内（1 小时）
        } finally {
            logger.detachAppender(appender);
        }

        long probeErrors = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("兜底源主动探测未通过"))
                .count();
        long probeWarns = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("兜底源主动探测未通过"))
                .count();
        assertEquals(1, probeErrors, "冷却期内同类失败只应记一条 ERROR：实际 " + probeErrors);
        assertEquals(1, probeWarns, "冷却期内的失败降 WARN（不静默）：实际 " + probeWarns);
    }

    @Test
    void probeFallback_failureAfterRecovery_alertsAgain() {
        // 确认恢复（连续 2 次通过）后才清零冷却：下一轮故障属于**新的**故障周期，不该被旧冷却吃掉。
        // REVIEW P2-交易86 第 3 条把「恢复」从单次 ok 收紧为连续 2 次 ok——单次 ok 不算真恢复
        // （否则抖动源 fail→ok→fail 就能每 30 分钟洗掉冷却再报一条 ERROR）。
        KlineSource sina = mock(KlineSource.class);
        when(sina.probe(anyString())).thenReturn(false).thenReturn(true).thenReturn(true).thenReturn(false);
        KlineService svc = probing(mock(KlineSource.class), sina);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KlineService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            svc.probeFallbackSource(); // 失败 → ERROR（冷却起）
            svc.probeFallbackSource(); // 第 1 次通过 → 只是恢复迹象，冷却**不**清零
            svc.probeFallbackSource(); // 第 2 次通过 → 确认恢复，冷却清零
            svc.probeFallbackSource(); // 再失败 → 新故障周期，仍应 ERROR
        } finally {
            logger.detachAppender(appender);
        }

        long probeErrors = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("兜底源主动探测未通过"))
                .count();
        assertEquals(2, probeErrors, "确认恢复后的再次失败应重新报 ERROR（不被旧冷却吞掉）：实际 " + probeErrors);
    }

    // ── REVIEW P2-交易86（2026-10-05）：兜底探测的三处小口径 ──
    // ① 探测窗口覆盖到 15:00 收盘（cron 13-14 → 13-15，每交易日实际 10 次）；
    // ② isProbeWindow() 收进最外层 try，任何异常都不许从 @Scheduled 入口逃逸（且如实记 ERROR）；
    // ③ 冷却只在**确认恢复**（连续 2 次成功）后清零——抖动源 fail→ok→fail 的第二次失败仍只 WARN。

    /**
     * ① 触发点覆盖 15:00 且每交易日实际探测 10 次——用 Spring {@code CronExpression} **真算**触发点，
     * 再逐点过 {@link KlineService#inTradingSession(LocalTime)}（生产同一条收口路径）。
     * <p>
     * 钉住三件事：a. cron 触发到 15:00（修前 13-14 的最后一次是 14:30，14:30–15:00 整段空档）；
     * b. 过滤后的实际探测数 = 10（09:30/10:00/…/14:30/15:00）；c. 被过滤的恰好是 09:00 与 15:30。
     * 日期取 2026-10-13（周二，非节假日），并**硬断言它是交易日**——若节假日表变了，用例红而不是静默失效。
     */
    @Test
    void probeFallback_cronTicks_coverCloseAt1500_andYieldTenProbesPerTradingDay() {
        LocalDate day = LocalDate.of(2026, 10, 13);
        assertTrue(TradingSessionPushService.isTradingDayStrict(day),
                "用例前提：2026-10-13 应是交易日（节假日表若变更，这里必须先红，不能让它悄悄失去鉴别力）");

        KlineService svc = new KlineService(true, true, mock(KlineSource.class), tdxEmpty(),
                mock(KlineSource.class));
        CronExpression cron = CronExpression.parse(KlineService.CRON_FALLBACK_PROBE);

        List<LocalTime> ticks = new ArrayList<>();
        LocalDateTime cursor = day.atStartOfDay();
        for (int i = 0; i < 24; i++) {
            cursor = cron.next(cursor);
            if (cursor == null || !cursor.toLocalDate().equals(day)) break;
            ticks.add(cursor.toLocalTime());
        }

        assertEquals(12, ticks.size(), "工作日 cron 触发点应为 12 个（09:00–11:30、13:00–15:30 每半小时）：" + ticks);
        assertTrue(ticks.contains(LocalTime.of(15, 0)), "cron 必须触发到 15:00（窗口含 15:00，别留 14:30–15:00 空档）：" + ticks);

        List<LocalTime> probes = ticks.stream().filter(svc::inTradingSession).toList();
        assertEquals(10, probes.size(), "每个交易日实际探测次数：cron 触发点经窗口收口后应为 10：" + probes);
        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(15, 30)),
                ticks.stream().filter(t -> !svc.inTradingSession(t)).toList(),
                "被窗口过滤的应恰好是 09:00（行情还是上一交易日）与 15:30（盘后）：" + ticks);
        assertTrue(probes.contains(LocalTime.of(15, 0)), "收盘那一刻必须在探测点上：" + probes);
    }

    /**
     * ② 窗口判定/探测体抛异常时**不逃逸**，且如实记 ERROR（不静默）。
     * <p>
     * 两条路径都走真实 {@link KlineService#isProbeWindow()} 默认体：
     * a. 日历判断 {@code isTradingDayToday()} 抛（窗口谓词内部先求值它）；
     * b. 窗口谓词本身抛（模拟将来判据改得会抛）。
     */
    @Test
    void probeFallback_probeWindowThrows_isSwallowedAndLoggedAsError() {
        for (String path : List.of("calendar", "window")) {
            KlineSource sina = mock(KlineSource.class);
            KlineService svc = spy(new KlineService(true, true, mock(KlineSource.class), tdxEmpty(), sina));
            if (path.equals("calendar")) {
                doThrow(new IllegalStateException("holiday table blew up")).when(svc).isTradingDayToday();
            } else {
                doThrow(new IllegalStateException("probe window blew up")).when(svc).isProbeWindow();
            }

            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KlineService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                assertDoesNotThrow(svc::probeFallbackSource,
                        "调度入口不许因窗口判定异常而抛（path=" + path + "）");
            } finally {
                logger.detachAppender(appender);
            }

            long errors = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .filter(e -> e.getFormattedMessage().contains("兜底源探测体异常"))
                    .count();
            assertEquals(1, errors, "异常必须如实记一条 ERROR（不静默吞掉，path=" + path + "）：实际 " + errors);
            verify(sina, never()).probe(anyString());
        }
    }

    /**
     * ③ 抖动源 fail→ok→fail：单次 ok **不算**恢复，冷却不被洗掉 ⇒ 第二次失败仍只 WARN，全程 1 条 ERROR。
     * <p>
     * 反例（修前行为）：一成功就清零冷却 ⇒ 每 30 分钟就能再报一条 ERROR，与「1 小时最多一条」不符。
     */
    @Test
    void probeFallback_flappingSource_secondFailureStaysCooldownedWarnOnly() {
        KlineSource sina = mock(KlineSource.class);
        when(sina.probe(anyString())).thenReturn(false).thenReturn(true).thenReturn(false);
        KlineService svc = probing(mock(KlineSource.class), sina);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KlineService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            svc.probeFallbackSource(); // 失败 → ERROR（冷却起）
            svc.probeFallbackSource(); // 抖动：单次 ok，连续成功仅 1 次 → 冷却不清零
            svc.probeFallbackSource(); // 再失败 → 仍在冷却 → WARN
        } finally {
            logger.detachAppender(appender);
        }

        long probeErrors = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("兜底源主动探测未通过"))
                .count();
        long probeWarns = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("兜底源主动探测未通过"))
                .count();
        assertEquals(1, probeErrors, "抖动源的第二次失败必须仍在冷却里（只 1 条 ERROR）：实际 " + probeErrors);
        assertEquals(1, probeWarns, "冷却期内的失败降 WARN（不静默）：实际 " + probeWarns);
        assertEquals(Boolean.FALSE, svc.health().fallbackHealthy(), "健康位如实跟随最后一次探测");
    }

    @Test
    void probeFallback_recovers_logsInfoOnce() {
        KlineSource sina = mock(KlineSource.class);
        when(sina.probe(anyString())).thenReturn(false).thenReturn(true);
        KlineService svc = probing(mock(KlineSource.class), sina);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KlineService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            svc.probeFallbackSource(); // 失败
            svc.probeFallbackSource(); // 恢复
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(Boolean.TRUE, svc.health().fallbackHealthy());
        long recovered = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("兜底源探测恢复"))
                .count();
        assertEquals(1, recovered, "从「不健康」回到「健康」要留一条 INFO（失败→恢复是人关心的转折）");
    }

    @Test
    void inTradingSession_defaultImplementation_isHonest() {
        // 真实时段判断（防「恒 true / 恒 false」变异）：09:30–11:30、13:00–15:00 含边界
        KlineService svc = new KlineService(true, true, mock(KlineSource.class), tdxEmpty(),
                mock(KlineSource.class));
        assertFalse(svc.inTradingSession(LocalTime.of(9, 29)), "09:29 还没开盘");
        assertTrue(svc.inTradingSession(LocalTime.of(9, 30)));
        assertTrue(svc.inTradingSession(LocalTime.of(11, 30)), "11:30 是上午收盘那一刻");
        assertFalse(svc.inTradingSession(LocalTime.of(11, 31)), "午休不探");
        assertFalse(svc.inTradingSession(LocalTime.of(12, 59)));
        assertTrue(svc.inTradingSession(LocalTime.of(13, 0)));
        assertTrue(svc.inTradingSession(LocalTime.of(15, 0)), "15:00 收盘那一刻");
        assertFalse(svc.inTradingSession(LocalTime.of(15, 1)), "盘后不探（日 K 一天一变，重复探没有信息增量）");
    }

    @Test
    void isTradingDayToday_andIsProbeWindow_defaultImplementations_followCalendarAndClock() {
        // 默认实现直调（不经 spy stub）：期望值取自同一份日历/时段，防硬编码 true 或 false
        KlineService svc = new KlineService(true, true, mock(KlineSource.class), tdxEmpty(),
                mock(KlineSource.class));

        assertEquals(TradingSessionPushService.isTradingDayStrict(LocalDate.now()), svc.isTradingDayToday(),
                "交易日判断必须与推送侧同一份日历（周末 + 法定节假日都不算）");
        boolean expected = svc.isTradingDayToday() && svc.inTradingSession(LocalTime.now());
        assertEquals(expected, svc.isProbeWindow(), "探测窗口 = 交易日 ∧ 交易时段");
    }
}
