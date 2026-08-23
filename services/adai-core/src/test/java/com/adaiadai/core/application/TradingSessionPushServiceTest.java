package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.push.PushChannel;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.application.TradeLogCollectService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingSessionPushService — 交易时段节奏推送测试（RFC 20260816）。
 * <p>
 * 覆盖：三节点模板内容（LLM 失败降级）/ LLM 成功用生成内容 / 渠道推送 / 插件门控 / 空仓文案。
 */
class TradingSessionPushServiceTest {

    private Position posWithPlan(String symbol, String name, String avgCost, String currentPrice,
                                 String stopLoss, String buyPoint, int qty) {
        return new Position(symbol, name, qty, new BigDecimal(avgCost), new BigDecimal(currentPrice),
                LocalDateTime.now(), LocalDate.of(2026, 8, 1), new BigDecimal(stopLoss), buyPoint, null);
    }

    private MarketData quote(String code, String price, String changePercent) {
        return new MarketData(code, "名称" + code, new BigDecimal(price), new BigDecimal(price),
                new BigDecimal(price), new BigDecimal("11.00"), new BigDecimal("9.50"),
                new BigDecimal(changePercent), 1000L);
    }

    /** 双持仓：京东方（止损 4.9）+ 茅台（占比高触发 R81）。LLM 抛异常 → 走模板。 */
    private TradingSessionPushService serviceWithPositions(PushChannel channel, AiClient ai) {
        return serviceWithPositions(channel, ai, "../../os/trading-engine/knowledge/context");
    }

    /** P1-交易4：带现金快照的 service（现金唯一真源 = AccountSnapshot，S5）。 */
    private TradingSessionPushService serviceWithCash(PushChannel channel, AiClient ai, String cash) {
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("1005460"), new BigDecimal(cash),
                        new BigDecimal(cash), new BigDecimal(cash),
                        new BigDecimal("5460"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null)));
        return serviceWithPositions(channel, ai, "../../os/trading-engine/knowledge/context", acc);
    }

    private TradingSessionPushService serviceWithPositions(PushChannel channel, AiClient ai, String knowledgeDir) {
        return serviceWithPositions(channel, ai, knowledgeDir, mock(AccountSnapshotRepository.class));
    }

    private TradingSessionPushService serviceWithPositions(PushChannel channel, AiClient ai, String knowledgeDir,
                                                           AccountSnapshotRepository acc) {
        return serviceWithPositions(channel, ai, knowledgeDir, acc, false);
    }

    /** B6-3：missingChangePercent=true 时行情 changePercent=null（模拟字段残缺，验证不 NPE）。 */
    private TradingSessionPushService serviceWithPositions(PushChannel channel, AiClient ai, String knowledgeDir,
                                                           AccountSnapshotRepository acc, boolean missingChangePercent) {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(any())).thenReturn(List.of(
                posWithPlan("000725", "京东方A", "5.20", "5.46", "4.90", "B1", 1000),
                posWithPlan("600519", "贵州茅台", "1400.00", "1420.00", "1380.00", "B2", 100)
        ));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of(
                "000725", missingChangePercent
                        ? new MarketData("000725", "京东方A", new BigDecimal("5.46"), new BigDecimal("5.40"),
                                new BigDecimal("5.46"), new BigDecimal("5.50"), new BigDecimal("5.30"),
                                null, 1000L)
                        : quote("000725", "5.46", "1.2"),
                "600519", missingChangePercent
                        ? new MarketData("600519", "贵州茅台", new BigDecimal("1420.00"), new BigDecimal("1425.00"),
                                new BigDecimal("1420.00"), new BigDecimal("1430.00"), new BigDecimal("1410.00"),
                                null, 1000L)
                        : quote("600519", "1420.00", "-0.3")
        ));
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(
                new Account("adai", "admin", true, null),
                new Account("alice", "user", true, null)
        ));
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
        when(pluginService.hasPlugin(eq("alice"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(false);

        // RFC 20260817：推送开关默认全开（findByUser 不 stub 返回 null → NPE → 不推送）
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());

        return new TradingSessionPushService(positions, market, accounts, pluginService,
                new DefaultTradingRuleEngine(), ai, List.of(channel),
                acc,
                mock(WatchlistBuyPointService.class), mock(WatchlistRepository.class),
                pushSettings, mock(TradeLogCollectService.class),
                knowledgeDir);
    }

    private static String eq(String s) { return org.mockito.ArgumentMatchers.eq(s); }

    // ── 三节点模板内容（LLM 失败降级）──

    @Test
    void morningPlan_template_containsPositionsAndStopLoss() {
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        TradingSessionPushService svc = serviceWithPositions(channel, ai);

        svc.morningPlan();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        PushChannel.PushMessage m = captor.getValue();
        assertEquals("早盘计划", m.title());
        assertEquals("session", m.type());
        assertTrue(m.content().contains("京东方"), "模板应含持仓名");
        assertTrue(m.content().contains("4.9"), "模板应含止损位");
        assertTrue(m.content().contains("择时"), "模板应含择时状态");
        verify(channel, never()).push(eq("alice"), any()); // 无插件用户不推
    }

    @Test
    void middayTracking_template_containsStopLossStatus() {
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        TradingSessionPushService svc = serviceWithPositions(channel, ai);

        svc.middayTracking();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        assertTrue(captor.getValue().content().contains("未触发止损"), "现价未破止损应标注未触发");
    }

    @Test
    void closeAdvice_template_containsRuleVerdicts() {
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        TradingSessionPushService svc = serviceWithPositions(channel, ai);

        svc.closeAdvice();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        String content = captor.getValue().content();
        assertTrue(content.contains("R66") || content.contains("R81"), "尾盘建议应引用规则编号，实际: " + content);
        assertTrue(content.contains("复盘"), "尾盘建议应提醒复盘");
    }

    @Test
    void closeAdvice_largeCash_doesNotTriggerR81() {
        // P1-交易4（2026-08-17）：占比分母 = 持仓市值 + 现金（S5 真源 AccountSnapshot.cash）——
        // 旧口径只算市值 → 单票恒 ~96% 必误发「超 R81 减仓」；现金 100 万时应占比 ~12% 持有
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        TradingSessionPushService svc = serviceWithCash(channel, ai, "1000000");

        svc.closeAdvice();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        String content = captor.getValue().content();
        assertFalse(content.contains("超 R81"), "现金充足时占比应回落，不得误发 R81 减仓，实际: " + content);
        assertTrue(content.contains("持有"), "现金充足应持有，实际: " + content);
    }

    @Test
    void closeAdvice_overMillion_singleStockOver25Pct_doesNotForceR81() {
        // B3-2（2026-08-23，P2-交易21 半修残留）：总资产超 100 万 → R81 前提不适用——
        // 单票占比 >25% 也不推「超 R81 减仓」（按 R82-R95 配置评估），与 TradingAdviceAppService 输出侧同口径
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        // 构造：茅台 300 股 ×1420 = 426000（占比 ~35%）+ 京东方 5460 + 现金 77 万 → 总资产 ~119.7 万 > 100 万
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("1197000"), new BigDecimal("770000"),
                        new BigDecimal("770000"), new BigDecimal("770000"),
                        new BigDecimal("431460"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null)));

        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(any())).thenReturn(List.of(
                posWithPlan("600519", "贵州茅台", "1400.00", "1420.00", "1380.00", "B2", 300),
                posWithPlan("000725", "京东方A", "5.20", "5.46", "4.90", "B1", 1000)
        ));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of(
                "600519", quote("600519", "1420.00", "-0.3"),
                "000725", quote("000725", "5.46", "1.2")
        ));
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account("adai", "admin", true, null)));
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        TradingSessionPushService svc = new TradingSessionPushService(positions, market, accounts,
                pluginService, new DefaultTradingRuleEngine(), ai, List.of(channel),
                acc,
                mock(WatchlistBuyPointService.class), mock(WatchlistRepository.class),
                pushSettings, mock(TradeLogCollectService.class),
                "../../os/trading-engine/knowledge/context");

        svc.closeAdvice();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        String content = captor.getValue().content();
        assertFalse(content.contains("超 R81"), "总资产超 100 万 → R81 前提不适用，不得强制减仓，实际: " + content);
        assertTrue(content.contains("持有"), "超 100 万应持有（按 R82-R95 评估），实际: " + content);
    }

    @Test
    void closeAdvice_missingChangePercent_noNpe() {
        // B6-3（2026-08-23，P1-交易13）：md 非 null 但 changePercent null → 显示 "-" 不 NPE
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        TradingSessionPushService svc = serviceWithPositions(channel, ai,
                "../../os/trading-engine/knowledge/context", mock(AccountSnapshotRepository.class),
                true); // missingChangePercent=true：构造 changePercent=null 的行情

        svc.closeAdvice();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        String content = captor.getValue().content();
        assertTrue(content.contains("-"), "changePercent 缺失应显示 '-'，实际: " + content);
        assertFalse(content.contains("null"), "不得出现 null 文案，实际: " + content);
    }

    // ── LLM 成功：用生成内容（阶段二）──

    @Test
    void morningPlan_llmSuccess_usesGeneratedContent() {
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("早上好，今天两只票：京东方守好 4.9 止损，茅台注意仓位，别追高。");
        TradingSessionPushService svc = serviceWithPositions(channel, ai);

        svc.morningPlan();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        assertTrue(captor.getValue().content().contains("京东方"), "LLM 生成内容应透出");
    }

    // ── 空仓 ──

    @Test
    void emptyPositions_friendlyCopy() {
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account("adai", "admin", true, null)));
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);

        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        TradingSessionPushService svc = new TradingSessionPushService(positions, market, accounts,
                pluginService, new DefaultTradingRuleEngine(), mock(AiClient.class), List.of(channel),
                mock(AccountSnapshotRepository.class),
                mock(WatchlistBuyPointService.class), mock(WatchlistRepository.class),
                pushSettings, mock(TradeLogCollectService.class),
                "../../os/trading-engine/knowledge/context");

        svc.closeAdvice();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        assertTrue(captor.getValue().content().contains("空仓"), "空仓文案应友好");
        assertFalse(captor.getValue().content().contains("R66"), "空仓不引用规则");
    }

    // ── 择时状态读取（P1 修复：配置路径注入，生产不再硬编码相对路径）──

    @Test
    void marketStage_readFromConfiguredDir() throws Exception {
        // 配置目录里放 current.md（含「当前判断」行）→ 模板应带出真实择时判断
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("knowledge");
        java.nio.file.Files.writeString(dir.resolve("current.md"),
                "# 当前交易状态\n\n## 市场阶段\n\n**当前判断**：空头区间，谨慎操作，不追高\n");
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        TradingSessionPushService svc = serviceWithPositions(channel, ai, dir.toString());

        svc.morningPlan();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        assertTrue(captor.getValue().content().contains("空头区间"),
                "配置路径应读到 current.md 的择时判断，实际: " + captor.getValue().content());
        assertFalse(captor.getValue().content().contains("择时状态未知"), "不应回退到未知");
    }

    @Test
    void marketStage_missingFile_fallsBackUnknown() {
        // 配置目录无 current.md → 降级「择时状态未知」（不抛异常）
        PushChannel channel = mock(PushChannel.class);
        when(channel.enabled()).thenReturn(true);
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        TradingSessionPushService svc = serviceWithPositions(channel, ai,
                java.nio.file.Paths.get("/nonexistent/knowledge-dir").toString());

        svc.morningPlan();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        assertTrue(captor.getValue().content().contains("择时状态未知"), "文件缺失应降级未知");
    }

    // ── 收盘账户更新（P1-交易3，2026-08-17）──

    @Test
    void closeAccountUpdate_allQuotes_persists() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(any())).thenReturn(List.of(
                posWithPlan("000725", "京东方A", "5.20", "5.46", "4.90", "B1", 1000),
                posWithPlan("600519", "贵州茅台", "1400.00", "1420.00", "1380.00", "B2", 100)
        ));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of(
                "000725", quote("000725", "5.46", "1.2"),
                "600519", quote("600519", "1420.00", "-0.3")
        ));
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account("adai", "admin", true, null)));
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("150000"), new BigDecimal("10000"),
                        new BigDecimal("10000"), new BigDecimal("10000"),
                        new BigDecimal("140000"), new BigDecimal("2000"),
                        BigDecimal.ZERO, new BigDecimal("150000"), LocalDate.of(2026, 8, 16))));
        // P0-2（2026-08-23）：closeAccountUpdate 走 update（原子 RMW），捕获计算结果
        java.util.concurrent.atomic.AtomicReference<AccountSnapshot> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(acc.update(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Function<java.util.Optional<AccountSnapshot>, AccountSnapshot> fn =
                    inv.getArgument(1);
            AccountSnapshot next = fn.apply(acc.findLatest(inv.getArgument(0)));
            saved.set(next);
            return next;
        });
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        TradingSessionPushService svc = new TradingSessionPushService(positions, market, accounts,
                pluginService, new DefaultTradingRuleEngine(), mock(AiClient.class), List.of(),
                acc, mock(WatchlistBuyPointService.class), mock(WatchlistRepository.class),
                pushSettings, mock(TradeLogCollectService.class),
                "/nonexistent/knowledge");

        svc.closeAccountUpdate();

        // 市值 = 1000×5.46 + 100×1420 = 5460 + 142000 = 147460
        assertEquals(0, saved.get().marketValue().compareTo(new BigDecimal("147460")));
    }

    @Test
    void closeAccountUpdate_missingQuote_skipsSave() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(any())).thenReturn(List.of(
                posWithPlan("000725", "京东方A", "5.20", "5.46", "4.90", "B1", 1000),
                posWithPlan("600519", "贵州茅台", "1400.00", "1420.00", "1380.00", "B2", 100)
        ));
        MarketDataSource market = mock(MarketDataSource.class);
        // 600519 缺行情（quote 没返回）→ 不应保存，旧快照保留
        when(market.quote(any())).thenReturn(Map.of("000725", quote("000725", "5.46", "1.2")));
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account("adai", "admin", true, null)));
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("150000"), new BigDecimal("10000"),
                        new BigDecimal("10000"), new BigDecimal("10000"),
                        new BigDecimal("140000"), new BigDecimal("2000"),
                        BigDecimal.ZERO, new BigDecimal("150000"), LocalDate.of(2026, 8, 16))));
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        TradingSessionPushService svc = new TradingSessionPushService(positions, market, accounts,
                pluginService, new DefaultTradingRuleEngine(), mock(AiClient.class), List.of(),
                acc, mock(WatchlistBuyPointService.class), mock(WatchlistRepository.class),
                pushSettings, mock(TradeLogCollectService.class),
                "/nonexistent/knowledge");

        svc.closeAccountUpdate();

        // 缺行情 → 跳过保存（不覆盖旧快照）
        verify(acc, never()).update(any(), any());
    }

    @Test
    void closeAccountUpdate_missingYesterdayClose_skipsSave() {
        // B3-3（2026-08-23，P1-交易3 半修残留）：价格齐全但某只昨收缺失 → todayPnl 残缺，
        // 必须整体跳过（不覆盖旧快照的当日盈亏）——与缺价格同等待遇
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(any())).thenReturn(List.of(
                posWithPlan("000725", "京东方A", "5.20", "5.46", "4.90", "B1", 1000),
                posWithPlan("600519", "贵州茅台", "1400.00", "1420.00", "1380.00", "B2", 100)
        ));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of(
                "000725", quote("000725", "5.46", "1.2"),
                // 茅台：价格有、昨收 null（行情字段残缺）
                "600519", new MarketData("600519", "贵州茅台", new BigDecimal("1420.00"), null,
                        new BigDecimal("1420.00"), new BigDecimal("1430.00"), new BigDecimal("1410.00"),
                        new BigDecimal("-0.3"), 1000L)
        ));
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account("adai", "admin", true, null)));
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("150000"), new BigDecimal("10000"),
                        new BigDecimal("10000"), new BigDecimal("10000"),
                        new BigDecimal("140000"), new BigDecimal("2000"),
                        BigDecimal.ZERO, new BigDecimal("150000"), LocalDate.of(2026, 8, 16))));
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        TradingSessionPushService svc = new TradingSessionPushService(positions, market, accounts,
                pluginService, new DefaultTradingRuleEngine(), mock(AiClient.class), List.of(),
                acc, mock(WatchlistBuyPointService.class), mock(WatchlistRepository.class),
                pushSettings, mock(TradeLogCollectService.class),
                "/nonexistent/knowledge");

        svc.closeAccountUpdate();

        // 昨收残缺 → 跳过保存（不覆盖旧快照的当日盈亏）
        verify(acc, never()).update(any(), any());
    }
    // ── 节假日守卫（P3，2026-08-17；B5-1 2026-08-23 补全 2026 官方 + 2027 预测）──
    @Test
    void holiday_skipsPush() {
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 10, 1)), "2026-10-01 国庆应休市");
        assertTrue(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 8, 17)), "2026-08-17 周一应开市");
        assertTrue(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 8, 20)), "2026-08-20 周四应开市");
    }

    @Test
    void holiday_2026_officialSchedule() {
        // 2026 官方（沪深交易所 2025-12-22 通知）：工作日休市日
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 2, 16)), "2026-02-16 春节应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 2, 23)), "2026-02-23 春节最后工作日应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 4, 6)), "2026-04-06 清明应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 5, 1)), "2026-05-01 劳动节应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 6, 19)), "2026-06-19 端午应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 9, 25)), "2026-09-25 中秋应休市");
        // B5-1：旧表误记 10-08 休市——官方 2026 国庆 10-07 结束，10-08 开市
        assertTrue(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2026, 10, 8)), "2026-10-08 国庆后应开市");
    }

    @Test
    void holiday_2027_predictiveSchedule() {
        // 2027 预测（官方通常年底发布，临时调休不追）
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2027, 1, 1)), "2027-01-01 元旦应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2027, 2, 3)), "2027-02-03 除夕应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2027, 2, 9)), "2027-02-09 春节末应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2027, 4, 5)), "2027-04-05 清明应休市");
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2027, 5, 3)), "2027-05-03 劳动节应休市");
        // C2（2026-08-23，隔离审查 P2-7）：2027 中秋在 9/15（农历八月十五）不在国庆；
        // 国庆 10/1-10/7，10/8 开市（无 8 天长假）
        assertFalse(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2027, 9, 15)), "2027-09-15 中秋应休市");
        assertTrue(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2027, 10, 8)), "2027-10-08 国庆后应开市（中秋不并国庆）");
        assertTrue(TradingSessionPushService.isTradingDay(
                java.time.LocalDate.of(2027, 8, 20)), "2027-08-20 周五应开市");
    }
}