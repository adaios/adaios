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
import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingMarketStage;
import com.adaiadai.core.domain.trading.TradingPlan;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingSyncState;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine;
import com.adaiadai.core.domain.trading.engine.TradingRuleEngine;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
        TradingPlanService planService = mock(TradingPlanService.class);
        WatchlistRepository watchlist = mock(WatchlistRepository.class);
        TradingSyncStateRepository syncState = mock(TradingSyncStateRepository.class);
        SoldTradeRepository soldRepo = mock(SoldTradeRepository.class);
        /**
         * P2-工程12①（2026-10-05）：从「内联 mock」提为字段——非交易日用例必须能把它 stub 成
         * **有候选**，否则 {@code tradeLogConfirm} 即使闸门被删也推不出东西（todayCandidates 返回空 →
         * forEachTradingUser 里的 return），用例照样绿（＝变异测不出来）。
         */
        TradeLogCollectService tradeLog = mock(TradeLogCollectService.class);
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        TradingMarketStageRepository stageRepo = mock(TradingMarketStageRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        PluginService plugin = mock(PluginService.class);
        AdviceHistoryRepository adviceRepo = mock(AdviceHistoryRepository.class);
        /** design-final §9#16：日上限守卫——默认计数源为空（未达上限，恒放行）。 */
        TradingPushGovernor governor = new TradingPushGovernor(mock(MarketPushRepository.class));
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
            TradingSessionPushService svc = spy(new TradingSessionPushService(posRepo, market, accounts, plugin, engine,
                    List.of(channel), acc, buyPoint, watchlist, pushSettings,
                    tradeLog, trading, stageRepo, adviceRepo,
                    narrator, syncState, soldRepo, evidence, planService, governor, knowledgeDir));
            // P2-工程11（2026-10-01）：把「今天是否交易日」固定为 true，与真实日历解耦。
            // 原先测试吃真实 LocalDate.now() → 每逢法定节假日（如 10-01 国庆）全量必红 24 条
            // ⚠️ 只有**节假日**会红，周末不会——闸门走 isTradingDay（只查节假日表、不判周末，
            //    周末由 cron MON-FRI 排除）；周末会红的是 afterDataSync 那条走 strict 的路径。
            // （实测 2251 tests / 24 failed，连跑 3 轮完全一致）。非交易日的早退语义另有专门用例
            // nonTradingDay_allEntrypointsSkipPushes 反向兜住——不是"不测了"，是"两个分支都测"。
            doReturn(true).when(svc).isTradingDayToday();
            // P2-工程12②（2026-10-05）：afterDataSync 的自证版闸门同样固定为交易日——
            // 原实现内联 isTradingDayStrict(LocalDate.now())，测试只能 assumeTrue 自跳过
            // （节假日「全绿」但这条路径一次没验）。两个分支分别由
            // afterDataSync_afterClose_pushesReviewImmediately（true）与
            // afterDataSync_nonTradingDay_onlyRecordsState_noPush（false）真断言覆盖；
            // 默认真身由 isTradingDayStrictToday_defaultImplementation_followsCalendar 直调兜住。
            doReturn(true).when(svc).isTradingDayStrictToday();
            return svc;
        }

        TradingSessionPushService buildSpy(LocalTime now) {
            TradingSessionPushService svc = build(); // 已是 spy（含闸门固定为交易日）
            doReturn(now).when(svc).nowTime();
            return svc;
        }
    }

    /**
     * RFC 20261003-trading-plan-and-review-loop §三（2026-10-03）：早盘**先念你昨晚定的计划**——
     * 引用用户原话、不改写成判断句（这是「你承诺、系统守」的入口）。
     */
    @Test
    void morningPlan_includesUserPlanVerbatim() {
        Rig rig = new Rig();
        when(rig.planService.find(any(), any(LocalDate.class))).thenReturn(Optional.of(
                new TradingPlan(LocalDate.now(), List.of(new TradingPlan.PlanItem(
                        "p1", "SELL", "600519", "贵州茅台", "跌破 1400", "LT",
                        new BigDecimal("1400"), null, "600519 跌破 1400 清仓", false)),
                        "", java.time.LocalDateTime.now())));
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("你昨晚定的"), "早盘应先念用户自己的计划，实际: " + content);
        assertTrue(content.contains("600519 跌破 1400 清仓"), "必须引用原话，实际: " + content);
    }

    /** 没写计划时早盘**不唠叨**——提醒写计划由 20:30 那条负责（避免一天两次打扰）。 */
    @Test
    void morningPlan_withoutPlan_doesNotNag() {
        Rig rig = new Rig();
        when(rig.planService.find(any(), any(LocalDate.class))).thenReturn(Optional.empty());
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        assertFalse(capture(rig.channel).content().contains("你昨晚定的"));
    }

    /** 20:30 提醒写「下一个交易日」的计划（用户拍板「提醒我写」）；**已写则不打扰**。 */
    @Test
    void planReminder_missingPlan_nudges_butSkipsWhenWritten() {
        Rig rig = new Rig();
        when(rig.planService.find(any(), any(LocalDate.class))).thenReturn(Optional.empty());
        TradingSessionPushService svc = rig.build();
        // P1（2026-10-03 增量深审）：planReminder 现在有**交易日闸门**——测试固定为交易日
        // （否则周六/节假日跑测试就会红；真实当天是不是交易日由闸门自己判）。
        doReturn(true).when(svc).isTradingDayToday();
        svc.planReminder();
        String body = capture(rig.channel).content();
        assertTrue(body.contains("作战计划还没写"), "没写就该提醒一次，实际: " + body);
        // ⚠️ P1-3：文案必须写**具体哪一天**（周五晚的「下个交易日」是周一，不能再含糊说「明天」）
        assertTrue(body.contains("月") && body.contains("（周"), "必须写明日期与星期，实际: " + body);

        Rig rig2 = new Rig();
        when(rig2.planService.find(any(), any(LocalDate.class))).thenReturn(Optional.of(
                new TradingPlan(LocalDate.now(), List.of(), "", java.time.LocalDateTime.now())));
        TradingSessionPushService svc2 = rig2.build();
        doReturn(true).when(svc2).isTradingDayToday();
        svc2.planReminder();
        org.mockito.Mockito.verify(rig2.channel, org.mockito.Mockito.never()).push(any(), any());
    }

    /** P1-3（2026-10-03 增量深审）：**非交易日不提醒**（原先漏了闸门 → 节假日照推）。 */
    @Test
    void planReminder_nonTradingDay_doesNotPush() {
        Rig rig = new Rig();
        when(rig.planService.find(any(), any(LocalDate.class))).thenReturn(Optional.empty());
        TradingSessionPushService svc = rig.build();
        doReturn(false).when(svc).isTradingDayToday();

        svc.planReminder();

        org.mockito.Mockito.verify(rig.channel, org.mockito.Mockito.never()).push(any(), any());
    }

    /**
     * RFC 20261003-trading-plan-and-review-loop §三（2026-10-03）：14:50 尾盘**先念今天的计划进度**
     * （引用原话 + 条件到没到 + 做没做 + ⚠️ 计划外），再是系统按规则算的卖点。
     */
    @Test
    void closeAdvice_includesPlanProgressFirst() {
        Rig rig = new Rig();
        when(rig.planService.review(any(), any(LocalDate.class))).thenReturn(
                new TradingPlanService.PlanReview(LocalDate.now(), true,
                        List.of(new TradingPlanService.ItemReview("600519", "贵州茅台", "SELL",
                                "跌破 1400", "600519 跌破 1400 清仓", true, true, "当日 高 1420 / 低 1390")),
                        List.of("000831 中国稀土 BUY 200股"), 1, 1, TradingPlan.DAY_STATUS_NONE));
        TradingSessionPushService svc = rig.build();

        svc.closeAdvice();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("今天的计划"), "尾盘应先念计划进度，实际: " + content);
        assertTrue(content.contains("600519 跌破 1400 清仓"), "必须引用用户原话");
        assertTrue(content.contains("计划外"), "计划外成交必须报出来（R96 的正面检查）");
    }

    /** 空仓但有今日计划 → 仍然发一条「计划进度」（空仓≠没事做）。 */
    @Test
    void closeAdvice_emptyPositions_butPlanExists_stillPushesProgress() {
        Rig rig = new Rig();
        rig.positions = List.of();
        when(rig.planService.review(any(), any(LocalDate.class))).thenReturn(
                new TradingPlanService.PlanReview(LocalDate.now(), true,
                        List.of(new TradingPlanService.ItemReview("000776", "广发证券", "BUY",
                                "回到 19.5", "000776 回到 19.5 以下买 500 股", false, false, null)),
                        List.of(), 0, 0, TradingPlan.DAY_STATUS_NONE));
        TradingSessionPushService svc = rig.build();

        svc.closeAdvice();

        PushChannel.PushMessage m = capture(rig.channel);
        assertEquals("计划进度", m.title());
        assertTrue(m.content().contains("000776 回到 19.5 以下买 500 股"), m.content());
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

    /**
     * P2-交易68（2026-09-23）：09:15 是盘前（集合竞价 09:15 才开始），行情源此刻返回的「现价」就是昨收
     * → `changePercent` 恒 0.00%；原文案渲染成 `昨收 94.74（0%）`，于是每天早盘固定出现 5 个 0%，
     * 用户当场质疑「都是 0%？」。改为**非零才带涨跌幅**。
     */
    @Test
    void morningPlan_zeroChangePercent_omitsThePercentInsteadOfShowingZero() {
        Rig rig = new Rig();
        rig.quotes = Map.of(
                "000725", quote("000725", "5.46", "0.0"),
                "600519", quote("600519", "1420.00", "0.00"));
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("昨收 5.46"), "昨收本身仍要给，实际: " + content);
        assertFalse(content.contains("%）"), "盘前恒 0 的今日涨跌幅不该出现（既无信息又像行情坏了），实际: " + content);
    }

    /** 反向：真的是盘中数据（changePercent 非 0）时，涨跌幅照旧要显示——别把有用信息一起砍掉。 */
    @Test
    void morningPlan_nonZeroChangePercent_stillShowsPercent() {
        Rig rig = new Rig();
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        assertTrue(capture(rig.channel).content().contains("（+1.2%）"),
                "盘中涨跌幅必须保留，实际: " + capture(rig.channel).content());
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
        // design-final §11.6 B1：action 改为陈述式事实（旧「清仓参考（R66）」含动作词，已退役）
        assertTrue(content.contains("已跌破你设的止损位 1380（R66）"), "破止损应给 R66 陈述句，实际: " + content);
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

    /** design-final §9#16（★V1）：当日推送已达 8 条上限 → 定时推送整个跳过（不落盘、不打扰）。 */
    @Test
    void dailyCapReached_sessionPush_skips() {
        Rig rig = new Rig();
        rig.governor = mock(TradingPushGovernor.class);
        when(rig.governor.admit(any(), any())).thenReturn(List.of());
        TradingSessionPushService svc = rig.build();

        svc.morningPlan();

        verify(rig.channel, never()).push(any(), any());
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

    /**
     * P2-交易67（2026-09-23）：**没导快照、但系统已经把账算出来了**（15:05 收盘更新写 `snapshotDate=今天`）
     * ——这时不该再回「快照我还没看到」。无交易日用户没有成交、也就没有理由去导快照，
     * 原行为让他结构上永远收不到复盘（用户 09-23 原话「那我今天没有买卖 怎么告诉你呢 你还在等我的数据」）。
     */
    @Test
    void closeSummary_notSyncedButSelfCalculatedAccount_stillReviewsWithSourceNote() {
        Rig rig = new Rig(); // syncState 默认空 → 今天没同步
        LocalDate today = LocalDate.now();
        rig.account = new AccountSnapshot(new BigDecimal("129867.01"), new BigDecimal("24101.01"),
                new BigDecimal("24101.01"), new BigDecimal("24101.01"),
                new BigDecimal("105766.00"), new BigDecimal("21087.30"),
                new BigDecimal("278.00"), new BigDecimal("150000"), today,
                AccountSnapshot.SOURCE_CALC);
        TradingSessionPushService svc = rig.build();

        svc.closeSummaryPush();

        String content = capture(rig.channel).content();
        assertTrue(content.contains("📋 收盘复盘"), "有自算账就该照常复盘，实际: " + content);
        assertTrue(content.contains("今天没等到你的成交导入"), "必须标注数据来源，实际: " + content);
        assertFalse(content.contains("我还没看到"), "不得再自称没数据，实际: " + content);
        // 仍不落标记（与「未同步」同口径）：他随后补导快照，还要能拿到含成交的那一版
        verify(rig.syncState, never()).markDailyReview(any(), any());
    }

    /** 反向：账的日期不是今天（旧快照）→ 不得拿旧账冒充今天，仍按「还没看到」如实说。 */
    @Test
    void closeSummary_staleAccountStillSaysNotSeen() {
        Rig rig = new Rig();
        rig.account = new AccountSnapshot(new BigDecimal("100000"), new BigDecimal("10000"),
                new BigDecimal("10000"), new BigDecimal("10000"),
                new BigDecimal("90000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("150000"), LocalDate.now().minusDays(3),
                AccountSnapshot.SOURCE_CALC);
        TradingSessionPushService svc = rig.build();

        svc.closeSummaryPush();

        assertTrue(capture(rig.channel).content().contains("我还没看到"),
                "旧日期的账不能冒充今天");
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

    /**
     * 收盘后（≥15:00）导入 → 立刻出复盘。
     * <p>
     * P2-工程12②（2026-10-05）：原用例用 {@code Assumptions.assumeTrue(isTradingDayStrict(now))}
     * **自跳过**——节假日跑测试时它"绿"得毫无信息量（这条路径一次没验），正是本条审查意见的病根。
     * 现改为 spy 覆写 {@code isTradingDayStrictToday()}（Rig 默认已固定为交易日），**真断言**，
     * 且与真实日历完全解耦；非交易日分支由下面那条用例真断言（原注释 2026-09-26 的脆弱点就此消除）。
     */
    @Test
    void afterDataSync_afterClose_pushesReviewImmediately() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        rig.sync = new TradingSyncState(today, LocalDateTime.now(), null);
        TradingSessionPushService svc = rig.buildSpy(LocalTime.of(15, 10));
        doReturn(true).when(svc).isTradingDayStrictToday(); // 显式交易日：不再 assumeTrue 自跳过

        svc.afterDataSync("adai");

        verify(rig.syncState, times(1)).recordSync(eq("adai"), eq(today), any());
        assertEquals("收盘复盘", capture(rig.channel).title());
    }

    /**
     * P2-工程12②（2026-10-05）：非交易日（周末 / 节假日）导入 → **只记状态、复盘一律不推**（真断言）。
     * <p>反向兜住 afterDataSync 的闸门：把它变异成恒真（＝节假日照推）本用例必红。
     */
    @Test
    void afterDataSync_nonTradingDay_onlyRecordsState_noPush() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        rig.sync = new TradingSyncState(today, LocalDateTime.now(), null);
        TradingSessionPushService svc = rig.buildSpy(LocalTime.of(15, 10)); // 即便已过 15:00
        doReturn(false).when(svc).isTradingDayStrictToday(); // 周末 / 法定节假日

        svc.afterDataSync("adai");

        verify(rig.syncState, times(1)).recordSync(eq("adai"), eq(today), any());
        verify(rig.channel, never()).push(any(), any());
    }

    /**
     * P2-工程11（2026-10-01）：非交易日（周末 / 法定节假日）——**7 个定时入口一律不推**。
     * <p>
     * 此前没有专门用例覆盖这个分支，它只被"测试恰好跑在非交易日"间接命中（于是把 24 条测试
     * 变成红色噪音）。现在闸门可覆写：本用例显式 fixed=false 兜住早退语义，
     * 其余用例 fixed=true 覆盖交易日行为——**两个分支都被测到**，且与真实日历无关。
     * <p>
     * P2-工程12①（2026-10-05）：原先只调了 5 个入口，**漏 {@code planReminder} 与 {@code tradeLogConfirm}**
     * ——审查官变异实测「删掉 {@code tradeLogConfirm} 的闸门 → 40 条全绿、零捕获」。今补齐两者：
     * <ul>
     *   <li>{@code planReminder}：stub「当晚没写计划」→ 闸门在则静默，闸门被删即推（有鉴别力）；</li>
     *   <li>{@code tradeLogConfirm}：**显式 stub 出非空候选**——否则 {@code todayCandidates} 返回空时
     *       即使闸门被删也推不出来（"不推"是空候选造成的假绿）；stub 非空后闸门被删即推。</li>
     * </ul>
     */
    @Test
    void nonTradingDay_allEntrypointsSkipPushes() {
        Rig rig = new Rig();
        // ⚠️ 鉴别力前提（P2-工程12①）：tradeLogConfirm 当日**确有待确认候选**，
        //    否则"没推"是空候选导致的假绿，删闸门也测不出来。
        when(rig.tradeLog.todayCandidates(any())).thenReturn(List.of(new TradeLogCandidate(
                "600487", "亨通光电", "BUY", new BigDecimal("18.42"), 100,
                LocalDate.now(), LocalTime.of(10, 30), "screenshot", true, null, null, "c1")));
        when(rig.tradeLog.summarize(any())).thenReturn("📋 今日操作汇总\n· 亨通光电 买入 100 股 @18.42");
        // ⚠️ 鉴别力前提：planReminder 当晚**还没写**计划（写了本来就不推，同样测不出闸门）
        when(rig.planService.find(any(), any(LocalDate.class))).thenReturn(Optional.empty());
        TradingSessionPushService svc = rig.build();
        doReturn(false).when(svc).isTradingDayToday(); // 模拟周末 / 国庆等休市日

        svc.morningPlan();
        svc.planReminder();
        svc.middayTracking();
        svc.closeAdvice();
        svc.closeSummaryPush();
        svc.closeAccountUpdate();
        svc.tradeLogConfirm();

        verify(rig.channel, never()).push(any(), any());
    }

    /**
     * P2-工程11 深审 P2-1 补丁（2026-10-01）：**默认实现必须真的被执行到**。
     * <p>
     * 此前该类唯一构造点（Rig）永远 spy + `doReturn(true)`、新用例又固定 false，
     * 于是 {@code isTradingDayToday()} 的真实方法体**一次都不执行**——把它变异成
     * {@code return true;}（＝节假日照推，正是 2026-08-17 / 08-30 修过的事故形态）测试仍全绿 63/0。
     * 本用例用 {@code doCallRealMethod} 还原真身，并以**固定日期**锚定两个分支
     * （法定节假日 → 非交易日；普通工作日 → 交易日），**与真实「今天」无关**，
     * 所以在任何日期跑都不会失去鉴别力。
     */
    @Test
    void isTradingDayToday_defaultImplementation_followsCalendar() {
        Rig rig = new Rig();
        TradingSessionPushService svc = rig.build();
        doCallRealMethod().when(svc).isTradingDayToday(); // 还原真实实现（Rig 默认 stub 成 true）

        // 日期先在 mockStatic 之外算好——否则 thenReturn 的实参会在 stubbing 进行中被一起拦下，
        // 报 UnfinishedStubbingException（2026-10-01 实测踩到；已进 pitfalls 候选）
        LocalDate holiday = LocalDate.of(2026, 10, 1);    // 2026 国庆
        LocalDate tradingDay = LocalDate.of(2026, 9, 30); // 周三，非节假日
        // ① 法定节假日 → 必须判为非交易日：恒 true 的变异在此被抓住
        try (var mocked = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            mocked.when(LocalDate::now).thenReturn(holiday);
            assertFalse(svc.isTradingDayToday(), "法定节假日必须判为非交易日（恒 true 变异在此被抓住）");
        }
        // ② 非节假日 → 必须放行：恒 false 的变异在此被抓住
        try (var mocked = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            mocked.when(LocalDate::now).thenReturn(tradingDay);
            assertTrue(svc.isTradingDayToday(), "非节假日必须放行（恒 false 变异在此被抓住）");
        }
    }

    /**
     * P2-工程12②（2026-10-05）：{@code isTradingDayStrictToday()} 的**默认实现必须真的被执行到**。
     * <p>
     * Rig 是唯一构造点、永远 spy + {@code doReturn(true)}，而新增的非交易日用例又固定 false →
     * 若不补本条，真实方法体零覆盖：把它变异成 {@code return true;}（＝周末/节假日照推复盘）
     * 测试仍会全绿。本用例用 {@code doCallRealMethod} 还原真身，以**固定日期**锚定三个分支，
     * **与真实「今天」无关**，因此在任何日期跑都不失去鉴别力。
     */
    @Test
    void isTradingDayStrictToday_defaultImplementation_followsCalendar() {
        Rig rig = new Rig();
        TradingSessionPushService svc = rig.build();
        doCallRealMethod().when(svc).isTradingDayStrictToday(); // 还原真实实现（Rig 默认 stub 成 true）

        LocalDate saturday = LocalDate.of(2026, 9, 19);   // 周六：strict 与 isTradingDay 的口径分界
        LocalDate holiday = LocalDate.of(2026, 10, 1);    // 2026 国庆
        LocalDate tradingDay = LocalDate.of(2026, 9, 30); // 周三，非节假日
        // ① 周末 → 必须判为非交易日（恒 true 的变异在此被抓住）
        try (var mocked = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            mocked.when(LocalDate::now).thenReturn(saturday);
            assertFalse(svc.isTradingDayStrictToday(),
                    "周末必须判为非交易日（这正是 strict 与 isTradingDay 的差别所在）");
        }
        // ② 法定节假日 → 必须判为非交易日
        try (var mocked = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            mocked.when(LocalDate::now).thenReturn(holiday);
            assertFalse(svc.isTradingDayStrictToday(), "法定节假日必须判为非交易日");
        }
        // ③ 普通工作日 → 必须放行（恒 false 的变异在此被抓住）
        try (var mocked = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            mocked.when(LocalDate::now).thenReturn(tradingDay);
            assertTrue(svc.isTradingDayStrictToday(), "普通工作日必须放行（恒 false 的变异在此被抓住）");
        }
    }

    @Test
    void afterDataSync_beforeClose_onlyRecordsState() {
        Rig rig = new Rig();
        LocalDate today = LocalDate.now();
        TradingSessionPushService svc = rig.buildSpy(LocalTime.of(14, 0));
        doReturn(true).when(svc).isTradingDayStrictToday(); // 固定交易日：本条测「收盘前」分支，不吃日历

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
