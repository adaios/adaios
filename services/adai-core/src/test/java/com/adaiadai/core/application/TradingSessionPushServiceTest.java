package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
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
        when(accounts.findAll()).thenReturn(List.of(
                new Account("adai", "admin", true, null),
                new Account("alice", "user", true, null)
        ));
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
        when(pluginService.hasPlugin(eq("alice"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(false);

        return new TradingSessionPushService(positions, market, accounts, pluginService,
                new DefaultTradingRuleEngine(), ai, List.of(channel),
                mock(AccountSnapshotRepository.class),
                mock(WatchlistBuyPointService.class), mock(WatchlistRepository.class));
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

        TradingSessionPushService svc = new TradingSessionPushService(positions, market, accounts,
                pluginService, new DefaultTradingRuleEngine(), mock(AiClient.class), List.of(channel),
                mock(AccountSnapshotRepository.class),
                mock(WatchlistBuyPointService.class), mock(WatchlistRepository.class));

        svc.closeAdvice();

        ArgumentCaptor<PushChannel.PushMessage> captor = ArgumentCaptor.forClass(PushChannel.PushMessage.class);
        verify(channel, times(1)).push(eq("adai"), captor.capture());
        assertTrue(captor.getValue().content().contains("空仓"), "空仓文案应友好");
        assertFalse(captor.getValue().content().contains("R66"), "空仓不引用规则");
    }
}
