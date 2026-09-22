package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.AdviceHistoryRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.PushSettings;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingMarketStage;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingSyncState;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine;
import com.adaiadai.core.domain.trading.engine.TradingRuleEngine;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.infrastructure.storage.TradingMarketStageRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.infrastructure.storage.TradingSyncStateRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.push.PushChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingSessionPushService — 决策时点推送测试（RFC `20260922` B 批）。
 *
 * <p>覆盖：四节点的**形态契约**（早盘合并买点 / 午间知会不催操作 / 尾盘四要素 + 账日期 /
 * 复盘随同步触发）、行情失败的显式降级（B4/P1-交易60）、以及不制造噪音的几条静默规则。
 * 四要素**文本本身**的细节在 {@code TradingDecisionNarratorTest}。
 */
class TradingSessionPushServiceTest {

    // ── 测试台 ──

    static Position pos(String symbol, String name, String avgCost, String currentPrice,
                        String stopLoss, String buyPoint, int qty) {
        return new Position(symbol, name, qty, new BigDecimal(avgCost), new BigDecimal(currentPrice),
                LocalDateTime.now(), LocalDate.of(2026, 8, 1), new BigDecimal(stopLoss), buyPoint, null);
    }

    static MarketData quote(String code, String price, String changePercent) {
        return new MarketData(code, "名称" + code, new BigDecimal(price), new BigDecimal(price),
                new BigDecimal(price), new BigDecimal("11.00"), new BigDecimal("9.50"),
                changePercent == null ? null : new BigDecimal(changePercent), 1000L);
    }

    static WatchlistItem watch(String symbol, String name) {
        return new WatchlistItem(symbol, name, "", "", 0, 0, 0, "", LocalDate.now());
    }

    static TradeRecord trade(String id, String symbol, String name, TradeDirection dir,
                             String price, int volume) {
        return TradeRecord.of(id, symbol, name, dir, new BigDecimal(price), volume,
                LocalDate.now(), null, null, null, null, null, null,
                LocalDateTime.now(), null, null);
    }

    /** 持仓行情：京东方未破止损（4.90）、茅台现价 1420 占绝对多数（触发 R81 减仓）。 */
    static Map<String, MarketData> defaultQuotes() {
        return Map.of(
                "000725", quote("000725", "5.46", "1.2"),
                "600519", quote("600519", "1420.00", "-0.3"));
    }

    static AccountSnapshot defaultAccount() {
        return new AccountSnapshot(new BigDecimal("160000"), new BigDecimal("10000"),
                new BigDecimal("10000"), new BigDecimal("10000"),
                new BigDecimal("150000"), new BigDecimal("2000"),
                BigDecimal.ZERO, new BigDecimal("150000"), LocalDate.of(2026, 9, 19),
                AccountSnapshot.SOURCE_BROKER);
    }

    /** 测试台：字段可直接改（未显式设置的用默认 mock），{@link #build()} 组装。 */
    static final class Rig {
        PushChannel channel = mock(PushChannel.class);
        List<Position> positions = List.of(
                pos("000725", "京东方A", "5.20", "5.46", "4.90", "B1", 1000),
                pos("600519", "贵州茅台", "1400.00", "1420.00", "1380.00", "B2", 100));
        Map<String, MarketData> quotes = defaultQuotes();
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        TradingAppService trading = mock(TradingAppService.class);
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        WatchlistBuyPointService buyPoint = mock(WatchlistBuyPointService.class);
        WatchlistRepository watchlist = mock(WatchlistRepository.class);
        TradingSyncStateRepository syncState = mock(TradingSyncStateRepository.class);
        SoldTradeRepository soldRepo = mock(SoldTradeRepository.class);
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        TradingMarketStageRepository stageRepo = mock(TradingMarketStageRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        PluginService plugin = mock(PluginService.class);
        AdviceHistoryRepository adviceRepo = mock(AdviceHistoryRepository.class);
        AccountSnapshot account = defaultAccount();
        List<SoldTrade> sold = List.of();
        TradingSyncState sync = TradingSyncState.empty();
        WatchlistBuyPointService.ScanResult scan =
                new WatchlistBuyPointService.ScanResult(List.of(), List.of(), null);
        List<TradeRecord> todayTrades = List.of();
        TradingAppService.IntegrityReport integrity = new TradingAppService.IntegrityReport(
                null, true, List.of(), List.of(), List.of(), "");
        PushSettings pushSettingsValue = PushSettings.defaults();
        String knowledgeDir = "../../os/trading-engine/knowledge/context";
        List<WatchlistItem> watchlistItems = List.of();

        TradingSessionPushService build() {
            when(channel.enabled()).thenReturn(true);
            PositionRepository posRepo = mock(PositionRepository.class);
            when(posRepo.findAll(any())).thenReturn(positions);
            MarketDataSource market = mock(MarketDataSource.class);
            when(market.quote(any())).thenReturn(quotes);
            when(accounts.findAll()).thenReturn(List.of(
                    new Account("adai", "admin", true, null),
                    new Account("alice", "user", true, null)));
            when(plugin.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
            when(plugin.hasPlugin(eq("alice"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(false);
            when(pushSettings.findByUser(any())).thenReturn(pushSettingsValue);
            when(acc.findLatest(any())).thenReturn(Optional.of(account));
            when(trading.getTradeHistory(any(), any(), any())).thenReturn(todayTrades);
            when(trading.integrity(any())).thenReturn(integrity);
            when(soldRepo.findAll(any())).thenReturn(sold);
            when(ruleRepo.findByUser(any())).thenReturn(TradingRuleSettings.defaults());
            when(watchlist.findAll(any())).thenReturn(watchlistItems);
            when(buyPoint.scanWatchlistDetailed(any(), any())).thenReturn(scan);
            when(syncState.find(any())).thenReturn(sync);
            TradingRuleEngine engine = new DefaultTradingRuleEngine(ruleRepo);
            TradingEvidenceService evidence =
                    new TradingEvidenceService(soldRepo, mock(TradingLotService.class), engine, knowledgeDir);
            TradingDecisionNarrator narrator = new TradingDecisionNarrator(evidence, soldRepo, ruleRepo);
            return new TradingSessionPushService(posRepo, market, accounts, plugin, engine,
                    List.of(channel), acc, buyPoint, watchlist, pushSettings,
                    mock(TradeLogCollectService.class), trading, stageRepo, adviceRepo,
                    narrator, syncState, soldRepo, evidence, knowledgeDir);
        }

        TradingSessionPushService buildSpy(LocalTime now) {
            TradingSessionPushService svc = spy(build());
            doReturn(now).when(svc).nowTime();
            return svc;
        }
    }

    private static PushChannel.PushMessage capture(PushChannel channel) {
        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        return captor.getValue();
    }

    /** 一个能通过四要素渲染的自选买点（形态 B1 → 规则库 R33 有条目）。 */
    private static WatchlistBuyPointService.WatchBuyPoint buyHit(String symbol, String name) {
        return new WatchlistBuyPointService.WatchBuyPoint(symbol, name, "B1", 80,
                List.of("缩量到 0.6 倍", "KDJ.J=11 拐头向上"),
                List.of(), TradingSessionPushService.previousTradingDay(LocalDate.now()).toString());
    }

    // ── B1 · 早盘 ──

    @Test
    void morningPlan_positions_carryAccountDateAndStopLoss() {
        Rig rig = new Rig();
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        PushChannel.PushMessage m = capture(rig.channel);
        assertEquals("早盘计划", m.title());
        assertEquals("session", m.type());
        assertTrue(m.content().contains("按你 09-19 的账"), "必须标账的日期，实际: " + m.content());
        assertTrue(m.content().contains("京东方A"), "应含持仓，实际: " + m.content());
        assertTrue(m.content().contains("昨收 5.46"), "早盘行情是昨收，措辞不得写成今日，实际: " + m.content());
        assertTrue(m.content().contains("止损 4.9"), "应含止损位，实际: " + m.content());
        assertTrue(m.content().contains("择时"), "应含择时状态，实际: " + m.content());
        // P0-1：正文含持仓名/价格 → 锁屏不得照搬
        assertFalse(m.notificationContent().contains("京东方"), "锁屏不得出现持仓名: " + m.notificationContent());
        assertFalse(m.notificationContent().contains("5.46"), "锁屏不得出现价格: " + m.notificationContent());
        verify(rig.channel, never()).push(eq("alice"), any()); // 无插件用户不推
    }

    @Test
    void morningPlan_emptyPositions_keepsFriendlyCopy() {
        Rig rig = new Rig();
        rig.positions = List.of();
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        assertTrue(capture(rig.channel).content().contains("空仓也是一种策略"));
    }

    @Test
    void morningPlan_quotesAllMissing_degradesExplicitly() {
        Rig rig = new Rig();
        rig.quotes = Map.of(); // 双源都失败：MarketDataSource 返回空 Map
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("行情我没取到"), "行情失败必须显式说，实际: " + content);
        assertFalse(content.contains("昨收"), "不得拿空洞数字充数，实际: " + content);
    }

    @Test
    void morningPlan_buyPointHit_rendersFourElements() {
        Rig rig = new Rig();
        rig.watchlistItems = List.of(watch("600487", "亨通光电"));
        rig.scan = new WatchlistBuyPointService.ScanResult(
                List.of(buyHit("600487", "亨通光电")), List.of(), null);
        rig.quotes = Map.of(
                "000725", quote("000725", "5.46", "1.2"),
                "600519", quote("600519", "1420.00", "-0.3"),
                "600487", quote("600487", "18.42", "2.0"));
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("今天有 1 只进了你的条件"), "买点段应出现，实际: " + content);
        assertTrue(content.contains("亨通光电"), "应含标的，实际: " + content);
        assertTrue(content.contains("① 你的历史："), "缺①，实际: " + content);
        assertTrue(content.contains("② 证据："), "缺②，实际: " + content);
        assertTrue(content.contains("③ 规则："), "缺③，实际: " + content);
        assertTrue(content.contains("④ 位置："), "缺④，实际: " + content);
        assertTrue(content.contains("R33"), "③ 应给出规则编号，实际: " + content);
    }

    @Test
    void morningPlan_buyPointDisabledBySettings_doesNotScan() {
        Rig rig = new Rig();
        rig.pushSettingsValue = PushSettings.defaults().with("buy-point", false);
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        verify(rig.buyPoint, never()).scanWatchlistDetailed(any(), any());
        assertFalse(capture(rig.channel).content().contains("进了你的条件"));
    }

    @Test
    void morningPlan_buyPointStaleData_neverPushed() {
        Rig rig = new Rig();
        rig.watchlistItems = List.of(watch("600487", "亨通光电"));
        // 数据停在很久以前（tdx 数据包滞后那类）：判定所用 K 线不是最近一个已收盘交易日 → 不推
        WatchlistBuyPointService.WatchBuyPoint stale = new WatchlistBuyPointService.WatchBuyPoint(
                "600487", "亨通光电", "B1", 80, List.of("缩量"), List.of(), "2026-01-05");
        rig.scan = 
                new WatchlistBuyPointService.ScanResult(List.of(stale), List.of(), "2026-01-05");
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        assertFalse(capture(rig.channel).content().contains("进了你的条件"), "陈旧数据不得冒充今日信号");
    }

    @Test
    void morningPlan_marketWideUnavailable_reportedEvenWithoutHits() {
        Rig rig = new Rig();
        rig.watchlistItems = List.of(
                watch("600487", "亨通光电"),
                watch("600206", "有研新材"));
        rig.scan = 
                new WatchlistBuyPointService.ScanResult(List.of(), List.of(
                        new WatchlistBuyPointService.Unavailable("600487", "亨通光电"),
                        new WatchlistBuyPointService.Unavailable("600206", "有研新材")), null);
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("我没取到行情"), "取数失败必须可见（P1-交易60），实际: " + content);
        assertTrue(content.contains("亨通光电"), "应点名没取到的标的，实际: " + content);
    }

    // ── B5 · 午间（知会）──

    @Test
    void midday_noBreach_silent() {
        Rig rig = new Rig(); // 两只都没破止损
        TradingSessionPushService svc = rig.build();

        svc.middayTracking();

        verify(rig.channel, never()).push(any(), any());
    }

    @Test
    void midday_breach_reportsFactsWithoutUrging() {
        Rig rig = new Rig();
        // 茅台跌破 1380 止损
        rig.quotes = Map.of(
                "000725", quote("000725", "5.46", "1.2"),
                "600519", quote("600519", "1370.00", "-2.1"));
        TradingSessionPushService svc = rig.build();

        svc.middayTracking();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("已在你设的 1380 下方"), "只报位置，实际: " + content);
        assertTrue(content.contains("只是告诉你位置"), "应显式声明不催操作，实际: " + content);
        for (String urging : List.of("快卖", "建议减仓", "清仓", "赶紧")) {
            assertFalse(content.contains(urging), "午间不得出现催促词「" + urging + "」: " + content);
        }
    }

    @Test
    void midday_emptyPositions_silent() {
        Rig rig = new Rig();
        rig.positions = List.of();
        TradingSessionPushService svc = rig.build();

        svc.middayTracking();

        verify(rig.channel, never()).push(any(), any());
    }

    @Test
    void midday_quotesMissing_silent() {
        Rig rig = new Rig();
        rig.positions = List.of(pos("600519", "贵州茅台", "1400.00", "1420.00", "1380.00", "B2", 100));
        rig.quotes = Map.of();
        TradingSessionPushService svc = rig.build();

        svc.middayTracking();

        // 知会性质：没有可信位置就不发（不发即不误导）
        verify(rig.channel, never()).push(any(), any());
    }

    // ── B2 · 尾盘（卖点）──

    @Test
    void closeAdvice_breach_rendersFourElementsWithAccountDate() {
        Rig rig = new Rig();
        rig.quotes = Map.of(
                "000725", quote("000725", "5.46", "1.2"),
                "600519", quote("600519", "1370.00", "-2.1"));
        TradingSessionPushService svc = rig.build();

        svc.closeAdvice();

        PushChannel.PushMessage m = capture(rig.channel);
        String content = m.content();
        assertEquals("尾盘卖点", m.title());
        assertTrue(content.contains("按你 09-19 的账"), "尾盘必须标账日期，实际: " + content);
        assertTrue(content.contains("清仓参考（R66）"), "破止损应给 R66，实际: " + content);
        assertTrue(content.contains("① 你的历史：") && content.contains("③ 规则："),
                "卖点必须带四要素，实际: " + content);
        assertTrue(content.contains("R66"), "③ 应引用规则编号，实际: " + content);
        assertFalse(m.notificationContent().contains("贵州茅台"), "锁屏不得出现持仓名");
    }

    @Test
    void closeAdvice_noTrigger_silent() {
        Rig rig = new Rig();
        // 大额现金 → 茅台占比被摊薄，不触发 R81；也都没破止损
        rig.account = new AccountSnapshot(new BigDecimal("3000000"), new BigDecimal("2900000"),
                new BigDecimal("2900000"), new BigDecimal("2900000"),
                new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("3000000"), LocalDate.of(2026, 9, 19), AccountSnapshot.SOURCE_BROKER);
        TradingSessionPushService svc = rig.build();

        svc.closeAdvice();

        verify(rig.channel, never()).push(any(), any());
    }

    @Test
    void closeAdvice_emptyPositions_silent() {
        Rig rig = new Rig();
        rig.positions = List.of();
        TradingSessionPushService svc = rig.build();

        svc.closeAdvice();

        verify(rig.channel, never()).push(any(), any());
    }

    @Test
    void closeAdvice_quotesAllMissing_explicitDegrade() {
        Rig rig = new Rig();
        rig.quotes = Map.of();
        TradingSessionPushService svc = rig.build();

        svc.closeAdvice();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("行情我没取到"), "实际: " + content);
    }

    @Test
    void closeAdvice_recordsAdviceHistoryWithBasisSnapshot() {
        Rig rig = new Rig();
        rig.quotes = Map.of(
                "000725", quote("000725", "5.46", "1.2"),
                "600519", quote("600519", "1370.00", "-2.1"));
        TradingSessionPushService svc = rig.build();

        svc.closeAdvice();

        ArgumentCaptor<com.adaiadai.core.domain.trading.AdviceEntry> captor =
                ArgumentCaptor.forClass(com.adaiadai.core.domain.trading.AdviceEntry.class);
        verify(rig.adviceRepo, times(1)).append(eq("adai"), captor.capture());
        var entry = captor.getValue();
        assertEquals("clear", entry.suggestion());
        assertEquals("session-push", entry.source());
        assertTrue(entry.basis() != null && entry.basis().contains("1370"),
                "A3 依据快照应记下当时的价，实际: " + entry.basis());
    }

    // ── B3 · 收盘复盘（同步后触发）──

    @Test
    void closeSummary_synced_buildsReviewAndMarks() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        rig.sync = new TradingSyncState(today, LocalDateTime.now(), null);
        rig.todayTrades = List.of(
                trade("t1", "000725", "京东方A", TradeDirection.BUY, "5.20", 1000),
                trade("d1", "600519", "贵州茅台", TradeDirection.BUY, "0", 0)); // 股息不计
        TradingSessionPushService svc = rig.build();

        svc.closeSummaryPush();

        PushChannel.PushMessage m = capture(rig.channel);
        assertEquals("收盘复盘", m.title());
        assertEquals("close-summary", m.type());
        String content = m.content();
        assertTrue(content.contains("今天你做了 1 笔"), "股息流水不计入笔数，实际: " + content);
        assertTrue(content.contains("记账：买 京东方A 1000 股 @5.2"), "实际: " + content);
        assertTrue(content.contains("账实：一致"), "实际: " + content);
        assertTrue(content.contains("只记流水的：无"), "实际: " + content);
        assertFalse(m.notificationContent().contains("京东方"), "锁屏不得出现标的");
        verify(rig.syncState, times(1)).markDailyReview(eq("adai"), eq(today));
    }

    @Test
    void closeSummary_notSynced_saysNotSeenAndDoesNotMark() {
        Rig rig = new Rig(); // syncState 默认空 → 今天没同步
        LocalDate today = LocalDate.now();
        TradingSessionPushService svc = rig.build();

        svc.closeSummaryPush();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("我还没看到"), "未同步不得硬出一份复盘，实际: " + content);
        // 不落标记：用户补导之后仍要能拿到真正的复盘（D4「可晚于 15:30」）
        verify(rig.syncState, never()).markDailyReview(any(), any());
    }

    @Test
    void closeSummary_alreadyPushedToday_skips() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        rig.sync = 
                new TradingSyncState(today, LocalDateTime.now(), today);
        TradingSessionPushService svc = rig.build();

        svc.closeSummaryPush();

        verify(rig.channel, never()).push(any(), any());
    }

    @Test
    void closeSummary_driftAndDegraded_reportedHonestly() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        rig.sync = new TradingSyncState(today, LocalDateTime.now(), null);
        rig.integrity = new TradingAppService.IntegrityReport(
                null, true,
                List.of(new TradingAppService.DriftLine("000725", "京东方A", 1000, 0, 900, 900, 0, "")),
                List.of(),
                List.of(new TradingAppService.DegradedLine("600487", "亨通光电", TradeDirection.SELL, 400,
                        new BigDecimal("18.42"), today, true, "锚定日推断")),
                "");
        TradingSessionPushService svc = rig.build();

        svc.closeSummaryPush();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("对不上"), "实际: " + content);
        assertTrue(content.contains("1 笔（亨通光电 卖 400 股）"), "只记流水的必须点名，实际: " + content);
        assertTrue(content.contains("锚定日是推断出来的"), "推断要如实说，实际: " + content);
    }

    @Test
    void closeSummary_holdingsUnknown_saysCannotTell() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        rig.sync = new TradingSyncState(today, LocalDateTime.now(), null);
        rig.integrity = new TradingAppService.IntegrityReport(
                null, false, List.of(), List.of(), List.of(), "还没有持仓快照基线");
        TradingSessionPushService svc = rig.build();

        svc.closeSummaryPush();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("还判不了"), "不知道 ≠ 没问题，实际: " + content);
        assertFalse(content.contains("账实：一致"), "判不了时绝不能报一致，实际: " + content);
    }

    @Test
    void afterDataSync_afterClose_pushesReviewImmediately() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        rig.sync = new TradingSyncState(today, LocalDateTime.now(), null);
        TradingSessionPushService svc = rig.buildSpy(LocalTime.of(15, 10));

        svc.afterDataSync("adai");

        verify(rig.syncState, times(1)).recordSync(eq("adai"), eq(today), any());
        assertEquals("收盘复盘", capture(rig.channel).title());
    }

    @Test
    void afterDataSync_beforeClose_onlyRecordsState() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        TradingSessionPushService svc = rig.buildSpy(LocalTime.of(14, 0));

        svc.afterDataSync("adai");

        verify(rig.syncState, times(1)).recordSync(eq("adai"), eq(today), any());
        verify(rig.channel, never()).push(any(), any()); // 收盘前导入：留给 15:30 用最新的账算
    }

    // ── 行情可用性 / 新鲜度口径 ──

    @Test
    void quotesUsable_anyPriceIsEnough_forPartialGaps() {
        var data = new TradingSessionPushService.SessionData(
                List.of(pos("600519", "贵州茅台", "1400", "1420", "1380", "B2", 100)),
                Map.of(), "择时状态未知", BigDecimal.ZERO, LocalDate.now());
        assertFalse(TradingSessionPushService.quotesUsable(data), "一只都没有 → 不可用");
        var partial = new TradingSessionPushService.SessionData(
                List.of(pos("600519", "贵州茅台", "1400", "1420", "1380", "B2", 100)),
                Map.of("600519", quote("600519", "1420", "0")), "择时状态未知", BigDecimal.ZERO, LocalDate.now());
        assertTrue(TradingSessionPushService.quotesUsable(partial), "有一只可用即算可用（逐只缺在正文点名）");
    }

    @Test
    void freshEnough_usesPreviousTradingDay() {
        LocalDate expected = LocalDate.of(2026, 9, 18); // 周五
        assertTrue(TradingSessionPushService.freshEnough("2026-09-18", expected));
        assertTrue(TradingSessionPushService.freshEnough("2026-09-19", expected), "盘后重跑用当日数据也算新鲜");
        assertFalse(TradingSessionPushService.freshEnough("2026-09-17", expected), "比上一交易日还旧 → 不推");
        assertFalse(TradingSessionPushService.freshEnough(null, expected));
        assertFalse(TradingSessionPushService.freshEnough("不是日期", expected));
    }

    @Test
    void previousTradingDay_skipsWeekend() {
        // 2026-09-21 是周一 → 上一交易日应为周五 09-18
        assertEquals(LocalDate.of(2026, 9, 18),
                TradingSessionPushService.previousTradingDay(LocalDate.of(2026, 9, 21)));
        // 2026-10-08（国庆后开市）→ 上一交易日 09-30（09-25 中秋、10-01~10-07 休市）
        assertEquals(LocalDate.of(2026, 9, 30),
                TradingSessionPushService.previousTradingDay(LocalDate.of(2026, 10, 8)));
    }

    // ── 择时状态（三级读取）──

    @Test
    void marketStage_userBear_beatsCurrentMd() {
        Rig rig = new Rig();
        TradingMarketStage stage = new TradingMarketStage("bear", "2026-09-04T10:00:00");
        when(rig.stageRepo.findByUser(any())).thenReturn(stage);
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        assertTrue(capture(rig.channel).content().contains("空头"), "用户手动判定应权威");
    }

    @Test
    void marketStage_missingFile_fallsBackUnknown() {
        Rig rig = new Rig();
        rig.knowledgeDir = "/nonexistent/knowledge-dir";
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        assertTrue(capture(rig.channel).content().contains("择时状态未知"));
    }

    // ── 收盘账户更新（P1-交易3，未属 B 批改动）──

    @Test
    void closeAccountUpdate_allQuotes_persists() {
        Rig rig = new Rig();
        java.util.concurrent.atomic.AtomicReference<AccountSnapshot> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(rig.acc.update(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            AccountSnapshot next = fn.apply(rig.acc.findLatest(inv.getArgument(0)));
            saved.set(next);
            return next;
        });
        TradingSessionPushService svc = rig.build();

        svc.closeAccountUpdate();

        // 市值 = 1000×5.46 + 100×1420 = 5460 + 142000 = 147460
        assertEquals(0, saved.get().marketValue().compareTo(new BigDecimal("147460")));
    }

    @Test
    void closeAccountUpdate_missingQuote_skipsSave() {
        Rig rig = new Rig();
        rig.quotes = Map.of("000725", quote("000725", "5.46", "1.2"));
        TradingSessionPushService svc = rig.build();

        svc.closeAccountUpdate();

        verify(rig.acc, never()).update(any(), any());
    }

    @Test
    void closeAccountUpdate_missingYesterdayClose_skipsSave() {
        Rig rig = new Rig();
        rig.quotes = Map.of(
                "000725", quote("000725", "5.46", "1.2"),
                "600519", new MarketData("600519", "贵州茅台", new BigDecimal("1420.00"), null,
                        new BigDecimal("1420.00"), new BigDecimal("1430.00"), new BigDecimal("1410.00"),
                        new BigDecimal("-0.3"), 1000L));
        TradingSessionPushService svc = rig.build();

        svc.closeAccountUpdate();

        verify(rig.acc, never()).update(any(), any());
    }

    // ── 节假日守卫（P3，2026-08-17；B5-1 2026-08-23 补全 2026 官方 + 2027 预测）──

    @Test
    void holiday_skipsPush() {
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 10, 1)), "2026-10-01 国庆应休市");
        assertTrue(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 8, 17)), "2026-08-17 周一应开市");
        assertTrue(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 8, 20)), "2026-08-20 周四应开市");
    }

    @Test
    void holiday_2026_officialSchedule() {
        // 2026 官方（沪深交易所 2025-12-22 通知）：工作日休市日
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 2, 16)), "2026-02-16 春节应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 2, 23)), "2026-02-23 春节末应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 4, 6)), "2026-04-06 清明应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 5, 1)), "2026-05-01 劳动节应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 6, 19)), "2026-06-19 端午应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 9, 25)), "2026-09-25 中秋应休市");
        // B5-1：旧表误记 10-08 休市——官方 2026 国庆 10-07 结束，10-08 开市
        assertTrue(TradingSessionPushService.isTradingDay(LocalDate.of(2026, 10, 8)), "2026-10-08 国庆后应开市");
    }

    @Test
    void holiday_2027_predictiveSchedule() {
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2027, 1, 1)), "2027-01-01 元旦应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2027, 2, 3)), "2027-02-03 除夕应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2027, 2, 9)), "2027-02-09 春节末应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2027, 4, 5)), "2027-04-05 清明应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2027, 5, 3)), "2027-05-03 劳动节应休市");
        assertFalse(TradingSessionPushService.isTradingDay(LocalDate.of(2027, 9, 15)), "2027-09-15 中秋应休市");
        assertTrue(TradingSessionPushService.isTradingDay(LocalDate.of(2027, 10, 8)), "2027-10-08 国庆后应开市");
        assertTrue(TradingSessionPushService.isTradingDay(LocalDate.of(2027, 8, 20)), "2027-08-20 周五应开市");
    }

    // ── 锁屏 fail-closed（P2-推送1）──

    @Test
    void pushMessage_notificationContent_isFailClosedWhenLockScreenMissing() {
        PushChannel.PushMessage m = new PushChannel.PushMessage(
                "标题", "正文含持仓名 京东方A 5.46", "session", null, null, LocalTime.now());
        assertNull(m.lockScreenContent());
        assertEquals(PushChannel.PushMessage.NEUTRAL_LOCK_SCREEN, m.notificationContent());
        assertFalse(m.notificationContent().contains("京东方"), "漏传锁屏版不得回落完整正文");
    }
}
