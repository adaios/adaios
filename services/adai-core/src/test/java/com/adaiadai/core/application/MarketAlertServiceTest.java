package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradingLot;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import com.adaiadai.core.infrastructure.storage.MarketSnapshotRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarketAlertService — 行情异动推送（Phase 2）单元测试。
 * 覆盖：三档异动检测 / 当日去重 / 网络失败不误推 / 无持仓跳过 / break-cost 开关。
 */
class MarketAlertServiceTest {

    private Position pos(String symbol, String name, String avgCost, String currentPrice) {
        return new Position(symbol, name, 200, new BigDecimal(avgCost), new BigDecimal(currentPrice),
                LocalDateTime.of(2026, 8, 6, 9, 30));
    }

    private MarketData quote(String code, String changePercent) {
        return new MarketData(code, "名称" + code, new BigDecimal("10.00"), new BigDecimal("10.00"),
                new BigDecimal("10.00"), new BigDecimal("11.00"), new BigDecimal("9.50"),
                new BigDecimal(changePercent), 1000L);
    }

    /** 指定现价的行情（真止损预警测试用——判定用 md.price() vs stopLossPrice）。 */
    private MarketData quoteAt(String code, String price, String changePercent) {
        return new MarketData(code, "名称" + code, new BigDecimal(price), new BigDecimal(price),
                new BigDecimal(price), new BigDecimal("11.00"), new BigDecimal("9.50"),
                new BigDecimal(changePercent), 1000L);
    }

    /** RFC 20260817：推送开关默认全开（findByUser 未 stub 返回 null → NPE → 不推送）。 */
    private PushSettingsRepository defaultPushSettings() {
        PushSettingsRepository ps = mock(PushSettingsRepository.class);
        when(ps.findByUser(anyString())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        return ps;
    }

    /** 构造依赖：snapshot 带「内存快照」语义（save 后下一次 alerted 可见）；push 由调用方 mock 传入。 */
    private MarketAlertService build(MarketDataSource market, PositionRepository positions,
                                     boolean breakCostEnabled, PushChannel push) {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account("default", "user", true, null)));

        Set<String> stored = new HashSet<>();
        MarketSnapshotRepository snapshot = mock(MarketSnapshotRepository.class);
        when(snapshot.alertedSignatures(anyString(), any())).thenAnswer(i -> new HashSet<>(stored));
        doAnswer(i -> {
            stored.clear();
            stored.addAll(i.getArgument(2));
            return null;
        }).when(snapshot).saveSignatures(anyString(), any(), any());

        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(anyString())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        return new MarketAlertService(market, positions, accounts, snapshot, java.util.List.of(push),
                mock(PluginService.class), new com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine(),
                pushSettings, mock(TradingLotService.class),
                3.0, 5.0, breakCostEnabled, 2.0);
    }

    /** RFC 20260825 批次级止损测试：注入可桩 derive 的批次服务。 */
    private MarketAlertService build(MarketDataSource market, PositionRepository positions,
                                     boolean breakCostEnabled, PushChannel push, TradingLotService lotService) {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account("default", "user", true, null)));

        Set<String> stored = new HashSet<>();
        MarketSnapshotRepository snapshot = mock(MarketSnapshotRepository.class);
        when(snapshot.alertedSignatures(anyString(), any())).thenAnswer(i -> new HashSet<>(stored));
        doAnswer(i -> {
            stored.clear();
            stored.addAll(i.getArgument(2));
            return null;
        }).when(snapshot).saveSignatures(anyString(), any(), any());

        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(anyString())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        return new MarketAlertService(market, positions, accounts, snapshot, java.util.List.of(push),
                mock(PluginService.class), new com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine(),
                pushSettings, lotService,
                3.0, 5.0, breakCostEnabled, 2.0);
    }

    /** 带止损位/买点的持仓（真止损预警测试用，RFC 20260816 用户提供数据）。 */
    private Position posWithStopLoss(String symbol, String name, String avgCost, String stopLoss) {
        return new Position(symbol, name, 200, new BigDecimal(avgCost), new BigDecimal("10.00"),
                LocalDateTime.of(2026, 8, 6, 9, 30),
                java.time.LocalDate.of(2026, 8, 1), new BigDecimal(stopLoss), "B1", null);
    }

    @Test
    void lossThreshold_createsStopLossPush() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600519", quote("600519", "-3.50")));
        PositionRepository positions = mock(PositionRepository.class);
        // avgCost 8.00 < 行情价 10.00 → 不误触 break-cost，仅 loss
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600519", "贵州茅台", "8.00", "9.00")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, true, push).poll("default");

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, times(1)).push(eq("default"), captor.capture());
        PushChannel.PushMessage e = captor.getValue();
        assertEquals("loss", e.type());
        assertEquals("600519", e.symbol());
        assertTrue(e.content().contains("单日大跌"));
        assertTrue(e.content().contains("还没设止损位"));
    }

    @Test
    void gainThreshold_createsGainPush() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600123", quote("600123", "+5.50")));
        PositionRepository positions = mock(PositionRepository.class);
        // avgCost 8.00 < 行情价 10.00 → 不误触 break-cost，仅 gain
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600123", "立昂微", "8.00", "9.00")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, true, push).poll("default");

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, times(1)).push(eq("default"), captor.capture());
        assertEquals("gain", captor.getValue().type());
    }

    @Test
    void lossPush_withStopLossSet_mentionsStopLossLine() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600519", quote("600519", "-3.50")));
        PositionRepository positions = mock(PositionRepository.class);
        // 已设止损（2026-08-17 用户补止损）→ 文案应提止损位而非「还没设止损位」
        Position p = new Position("600519", "贵州茅台", 200, new BigDecimal("8.00"),
                new BigDecimal("9.00"), LocalDateTime.of(2026, 8, 6, 9, 30),
                java.time.LocalDate.of(2026, 8, 1), new BigDecimal("7.44"), "B1", null);
        when(positions.findAll(anyString())).thenReturn(List.of(p));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, true, push).poll("default");

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, times(1)).push(eq("default"), captor.capture());
        assertTrue(captor.getValue().content().contains("止损位 7.44"), "已设止损 → 应提止损位");
        assertFalse(captor.getValue().content().contains("还没设止损位"), "已设止损 → 不应再说没设");
    }

    @Test
    void breakCost_createsRiskAlert() {
        // 现价低于成本（10.00 < 26.00）且当日无涨跌 → 仅 break-cost 推送
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600123", quote("600123", "+0.50")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600123", "立昂微", "26.00", "10.00")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, true, push).poll("default");

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, times(1)).push(eq("default"), captor.capture());
        assertEquals("break-cost", captor.getValue().type());
    }

    /**
     * 2026-08-20 生产「微信双份」回归：同股票同轮同时命中 loss + break-cost（如京东方当日
     * 大跌且跌破成本线）→ 旧实现各发一条，微信收到两条重叠内容。修复后合并为一条，
     * 类型取最严重（loss > break-cost），内容拼接。
     */
    @Test
    void sameSymbolMultiType_mergesIntoSinglePush() {
        MarketDataSource market = mock(MarketDataSource.class);
        // 现价 9.50 < 成本 10.00（break-cost 命中）；当日 -3.5%（loss 命中）
        when(market.quote(any())).thenReturn(Map.of("600519", quoteAt("600519", "9.50", "-3.50")));
        PositionRepository positions = mock(PositionRepository.class);
        Position p = new Position("600519", "贵州茅台", 200, new BigDecimal("10.00"),
                new BigDecimal("9.50"), LocalDateTime.of(2026, 8, 6, 9, 30),
                java.time.LocalDate.of(2026, 8, 1), new BigDecimal("9.00"), "B1", null);
        when(positions.findAll(anyString())).thenReturn(List.of(p));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, true, push).poll("default");

        // 同股票同轮只推一条（不是两条）
        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, times(1)).push(eq("default"), captor.capture());
        PushChannel.PushMessage m = captor.getValue();
        // 类型保留最严重（loss > break-cost）
        assertEquals("loss", m.type());
        // 内容拼接了两种提醒（大跌 + 跌破成本）
        assertTrue(m.content().contains("单日大跌"), "合并内容应含单日大跌提醒");
        assertTrue(m.content().contains("跌破成本"), "合并内容应含跌破成本提醒");
    }

    @Test
    void sameDay_dedup_noDuplicatePush() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600519", quote("600519", "-3.50")));
        PositionRepository positions = mock(PositionRepository.class);
        // avgCost 8.00 < 行情价 10.00 → 仅 loss 一类，便于验证去重
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600519", "贵州茅台", "8.00", "9.00")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        MarketAlertService service = build(market, positions, true, push);
        service.poll("default"); // 第一次：触发推送，签名入库
        service.poll("default"); // 第二次：签名已存在 → 不再推

        verify(push, times(1)).push(eq("default"), any());
    }

    @Test
    void noPositions_skipsQuoteAndPush() {
        MarketDataSource market = mock(MarketDataSource.class);
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of());
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, false, push).poll("default");

        verify(market, never()).quote(any());
    }

    @Test
    void networkEmpty_skipsPushWithoutCrash() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of()); // 网络/接口失败 → 空
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600519", "贵州茅台", "1321", "1300")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, false, push).poll("default");

        verify(push, never()).push(anyString(), any());
    }

    @Test
    void poll_noArg_coversAllEnabledAccounts_only() {
        // REVIEW #183：accounts = [adai, alice]（无 default，default 已随数据迁移移除）；
        // 真实数据在 adai → poll() 遍历全部启用账号，不再硬编码 default
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600519", quote("600519", "-3.50")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of()); // alice 无持仓
        when(positions.findAll("adai")).thenReturn(List.of(pos("600519", "贵州茅台", "8.00", "9.00")));

        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(
                new Account("adai", "admin", true, null, List.of(PluginRegistry.PLUGIN_TRADING)),
                new Account("alice", "user", true, null)));

        Set<String> stored = new HashSet<>();
        MarketSnapshotRepository snapshot = mock(MarketSnapshotRepository.class);
        when(snapshot.alertedSignatures(anyString(), any())).thenAnswer(i -> new HashSet<>(stored));
        doAnswer(i -> {
            stored.clear();
            stored.addAll(i.getArgument(2));
            return null;
        }).when(snapshot).saveSignatures(anyString(), any(), any());
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);

        new MarketAlertService(market, positions, accounts, snapshot, java.util.List.of(push),
                pluginService, new com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine(), defaultPushSettings(), mock(TradingLotService.class), 3.0, 5.0, true, 2.0).poll();

        verify(push, times(1)).push(eq("adai"), any());
        verify(push, never()).push(eq("default"), any());
        verify(push, never()).push(eq("alice"), any());
    }

    @Test
    void poll_noArg_skipsNonTradingPluginUsers() {
        // REVIEW S-4：写侧门控——无 trading 插件的启用账号不被轮询（不累积看不见的 push 残留）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600519", quote("600519", "-3.50")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600519", "贵州茅台", "8.00", "9.00")));

        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(
                new Account("adai", "admin", true, null, List.of(PluginRegistry.PLUGIN_TRADING)),
                new Account("alice", "user", true, null))); // alice enabled 但无 trading 插件

        Set<String> stored = new HashSet<>();
        MarketSnapshotRepository snapshot = mock(MarketSnapshotRepository.class);
        when(snapshot.alertedSignatures(anyString(), any())).thenAnswer(i -> new HashSet<>(stored));
        doAnswer(i -> {
            stored.clear();
            stored.addAll(i.getArgument(2));
            return null;
        }).when(snapshot).saveSignatures(anyString(), any(), any());
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
        when(pluginService.hasPlugin(eq("alice"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(false);

        new MarketAlertService(market, positions, accounts, snapshot, java.util.List.of(push),
                pluginService, new com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine(), defaultPushSettings(), mock(TradingLotService.class), 3.0, 5.0, true, 2.0).poll();

        verify(push, times(1)).push(eq("adai"), any());
        verify(push, never()).push(eq("alice"), any());
        verify(positions, never()).findAll("alice"); // 无插件用户不做无谓的行情轮询
    }

    @Test
    void breakCostDisabled_skipsCostAlert() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600123", quote("600123", "+0.50")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600123", "立昂微", "26.00", "10.00")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, false, push).poll("default");

        verify(push, never()).push(anyString(), any());
    }
    // ── 真止损预警（2026-08-16）：现价跌破用户预设止损位 → R66 硬判定 ──

    @Test
    void stopLoss_breached_createsPush() {
        // 现价 4.80 < 止损位 4.90 → 真止损推送（当日无涨跌，隔离其他信号）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725", quoteAt("000725", "4.80", "0.00")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(posWithStopLoss("000725", "京东方A", "5.20", "4.90")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, false, push).poll("default");

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, times(1)).push(eq("default"), captor.capture());
        PushChannel.PushMessage e = captor.getValue();
        assertEquals("stop-loss", e.type());
        assertEquals("000725", e.symbol());
        assertTrue(e.content().contains("跌破你的止损位 4.9"), "文案应含止损位，实际: " + e.content());
        assertTrue(e.content().contains("R66"), "文案应引用纪律规则 R66");
    }

    @Test
    void stopLoss_notBreached_noPush() {
        // 现价 5.00 > 止损位 4.90 → 不触发真止损
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725", quoteAt("000725", "5.50", "0.00")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(posWithStopLoss("000725", "京东方A", "5.20", "4.90")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, false, push).poll("default");

        verify(push, never()).push(anyString(), any());
    }

    @Test
    void stopLoss_missingStopLoss_noPush() {
        // 旧数据无止损位（null）→ R68 不硬判，跳过（买入时已强制填写，仅历史数据可能缺失）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725", quoteAt("000725", "4.80", "0.00")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("000725", "京东方A", "5.20", "4.80")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, false, push).poll("default");

        verify(push, never()).push(anyString(), any());
    }

    @Test
    void stopLoss_sameDayDeduplicated() {
        // 当日已推过 stop-loss → 第二轮轮询不再重复推（signature 去重）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725", quoteAt("000725", "4.80", "0.00")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(posWithStopLoss("000725", "京东方A", "5.20", "4.90")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        MarketAlertService svc = build(market, positions, false, push); // 隔离 break-cost，专测 stop-loss 去重
        svc.poll("default");
        svc.poll("default"); // 第二轮：快照已签名，不再推

        verify(push, times(1)).push(eq("default"), any());
    }
    // ── C3 接近止损预警（2026-08-16）──

    @org.junit.jupiter.api.Test
    void nearStopLoss_within2Percent_createsPush() {
        // 现价 5.00 vs 止损 4.90 → 距 2.0%（≤2% 触发）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725", quoteAt("000725", "5.00", "0.00")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(posWithStopLoss("000725", "京东方A", "5.20", "4.90")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, false, push).poll("default");

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, times(1)).push(eq("default"), captor.capture());
        assertEquals("near-stop-loss", captor.getValue().type());
        assertTrue(captor.getValue().content().contains("距止损位"));
    }

    @org.junit.jupiter.api.Test
    void nearStopLoss_farAway_noPush() {
        // 现价 5.50 vs 止损 4.90 → 距 10.9% > 2% 不触发
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725", quoteAt("000725", "5.50", "0.00")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(posWithStopLoss("000725", "京东方A", "5.20", "4.90")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        build(market, positions, false, push).poll("default");

        verify(push, never()).push(anyString(), any());
    }

    @Test
    void lotStopLoss_createsPush_whenLotPriceBelowItsOwnStop() {
        // RFC 20260825 §3 批次级止损（后端审查 P2-6 补测）：某批次现价破它自己的止损
        // （未设按默认 −7% 兜底）→ 单独推「批次止损预警」，不跟底仓混；与持仓级 R66 独立去重
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600000", quoteAt("600000", "8.80", "-3.0")));
        PositionRepository positions = mock(PositionRepository.class);
        // 持仓级止损未设（pos 无 stopLoss）→ 仅批次级触发，验证分支独立生效
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600000", "浦发银行", "10.00", "9.00")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        TradingLotService lots = mock(TradingLotService.class);
        TradingLot lot = new TradingLot("600000_2026-08-03_B", "600000", "浦发银行",
                LocalDate.of(2026, 8, 3), 1000, 500, new BigDecimal("10.0"),
                new BigDecimal("9.0"), null, null, false, BigDecimal.ZERO);
        when(lots.derive(anyString())).thenReturn(Map.of("600000", List.of(lot)));
        when(lots.effectiveStopLoss(lot)).thenReturn(new BigDecimal("9.0"));

        build(market, positions, true, push, lots).poll("default");

        ArgumentCaptor<PushChannel.PushMessage> cap = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, atLeastOnce()).push(eq("default"), cap.capture());
        assertTrue(cap.getAllValues().stream()
                        .anyMatch(m -> m.title().contains("批次止损预警")),
                "批次破自己的止损 → 批次止损预警推送（不跟底仓混）");
        assertTrue(cap.getAllValues().stream()
                        .anyMatch(m -> m.content().contains("2026-08-03 买入批次")),
                "推送内容含批次信息（日期/成本/止损）");
    }

    @Test
    void lotStopLoss_disabledSetting_noPush() {
        // 推送开关关闭 stop-loss → 批次级止损不推送（与持仓级同开关）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600000", quoteAt("600000", "8.80", "-3.0")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600000", "浦发银行", "10.00", "9.00")));
        PushChannel push = mock(PushChannel.class);
        when(push.enabled()).thenReturn(true);

        TradingLotService lots = mock(TradingLotService.class);
        TradingLot lot = new TradingLot("600000_2026-08-03_B", "600000", "浦发银行",
                LocalDate.of(2026, 8, 3), 1000, 500, new BigDecimal("10.0"),
                new BigDecimal("9.0"), null, null, false, BigDecimal.ZERO);
        when(lots.derive(anyString())).thenReturn(Map.of("600000", List.of(lot)));
        when(lots.effectiveStopLoss(lot)).thenReturn(new BigDecimal("9.0"));

        // 关闭 stop-loss 推送开关
        com.adaiadai.core.domain.trading.PushSettings ps = com.adaiadai.core.domain.trading.PushSettings.defaults();
        ps = ps.with("stop-loss", false);
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account("default", "user", true, null)));
        MarketSnapshotRepository snapshot = mock(MarketSnapshotRepository.class);
        when(snapshot.alertedSignatures(anyString(), any())).thenReturn(new HashSet<>());
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(anyString())).thenReturn(ps);
        MarketAlertService svc = new MarketAlertService(market, positions, accounts, snapshot,
                java.util.List.of(push), mock(PluginService.class),
                new com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine(),
                pushSettings, lots, 3.0, 5.0, true, 2.0);

        svc.poll("default");

        // stop-loss 开关只影响止损类；loss/break-cost 推送仍会触发——精确断言无批次止损预警
        ArgumentCaptor<PushChannel.PushMessage> cap = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(push, atLeastOnce()).push(eq("default"), cap.capture());
        assertTrue(cap.getAllValues().stream().noneMatch(m -> m.title().contains("批次止损预警")),
                "stop-loss 开关关闭 → 批次级止损不推送");
    }
}

