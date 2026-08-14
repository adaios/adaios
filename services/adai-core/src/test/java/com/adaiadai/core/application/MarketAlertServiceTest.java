package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.infrastructure.storage.MarketSnapshotRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.market.MarketData;
import com.adaiadai.core.kernel.market.MarketDataSource;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /** 构造依赖：snapshot 带「内存快照」语义（save 后下一次 alerted 可见）；push 由调用方 mock 传入。 */
    private MarketAlertService build(MarketDataSource market, PositionRepository positions,
                                     boolean breakCostEnabled, MarketPushRepository push) {
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

        return new MarketAlertService(market, positions, accounts, snapshot, push,
                mock(PluginService.class), 3.0, 5.0, breakCostEnabled);
    }

    @Test
    void lossThreshold_createsStopLossPush() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600519", quote("600519", "-3.50")));
        PositionRepository positions = mock(PositionRepository.class);
        // avgCost 8.00 < 行情价 10.00 → 不误触 break-cost，仅 loss
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600519", "贵州茅台", "8.00", "9.00")));
        MarketPushRepository push = mock(MarketPushRepository.class);

        build(market, positions, true, push).poll("default");

        ArgumentCaptor<MarketPushEvent> captor = ArgumentCaptor.forClass(MarketPushEvent.class);
        verify(push, times(1)).append(eq("default"), any(), captor.capture());
        MarketPushEvent e = captor.getValue();
        assertEquals("loss", e.type());
        assertEquals("600519", e.symbol());
        assertTrue(e.message().contains("止损预警"));
    }

    @Test
    void gainThreshold_createsGainPush() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600123", quote("600123", "+5.50")));
        PositionRepository positions = mock(PositionRepository.class);
        // avgCost 8.00 < 行情价 10.00 → 不误触 break-cost，仅 gain
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600123", "立昂微", "8.00", "9.00")));
        MarketPushRepository push = mock(MarketPushRepository.class);

        build(market, positions, true, push).poll("default");

        ArgumentCaptor<MarketPushEvent> captor = ArgumentCaptor.forClass(MarketPushEvent.class);
        verify(push, times(1)).append(eq("default"), any(), captor.capture());
        assertEquals("gain", captor.getValue().type());
    }

    @Test
    void breakCost_createsRiskAlert() {
        // 现价低于成本（10.00 < 26.00）且当日无涨跌 → 仅 break-cost 推送
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600123", quote("600123", "+0.50")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600123", "立昂微", "26.00", "10.00")));
        MarketPushRepository push = mock(MarketPushRepository.class);

        build(market, positions, true, push).poll("default");

        ArgumentCaptor<MarketPushEvent> captor = ArgumentCaptor.forClass(MarketPushEvent.class);
        verify(push, times(1)).append(eq("default"), any(), captor.capture());
        assertEquals("break-cost", captor.getValue().type());
    }

    @Test
    void sameDay_dedup_noDuplicatePush() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600519", quote("600519", "-3.50")));
        PositionRepository positions = mock(PositionRepository.class);
        // avgCost 8.00 < 行情价 10.00 → 仅 loss 一类，便于验证去重
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600519", "贵州茅台", "8.00", "9.00")));
        MarketPushRepository push = mock(MarketPushRepository.class);

        MarketAlertService service = build(market, positions, true, push);
        service.poll("default"); // 第一次：触发推送，签名入库
        service.poll("default"); // 第二次：签名已存在 → 不再推

        verify(push, times(1)).append(eq("default"), any(), any(MarketPushEvent.class));
    }

    @Test
    void noPositions_skipsQuoteAndPush() {
        MarketDataSource market = mock(MarketDataSource.class);
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of());
        MarketPushRepository push = mock(MarketPushRepository.class);

        build(market, positions, true, push).poll("default");

        verify(market, never()).quote(any());
    }

    @Test
    void networkEmpty_skipsPushWithoutCrash() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of()); // 网络/接口失败 → 空
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600519", "贵州茅台", "1321", "1300")));
        MarketPushRepository push = mock(MarketPushRepository.class);

        build(market, positions, true, push).poll("default");

        verify(push, never()).append(anyString(), any(), any(MarketPushEvent.class));
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
        MarketPushRepository push = mock(MarketPushRepository.class);

        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);

        new MarketAlertService(market, positions, accounts, snapshot, push,
                pluginService, 3.0, 5.0, true).poll();

        verify(push, times(1)).append(eq("adai"), any(), any(MarketPushEvent.class));
        verify(push, never()).append(eq("default"), any(), any());
        verify(push, never()).append(eq("alice"), any(), any());
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
        MarketPushRepository push = mock(MarketPushRepository.class);

        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(eq("adai"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(true);
        when(pluginService.hasPlugin(eq("alice"), eq(PluginRegistry.PLUGIN_TRADING))).thenReturn(false);

        new MarketAlertService(market, positions, accounts, snapshot, push,
                pluginService, 3.0, 5.0, true).poll();

        verify(push, times(1)).append(eq("adai"), any(), any(MarketPushEvent.class));
        verify(push, never()).append(eq("alice"), any(), any(MarketPushEvent.class));
        verify(positions, never()).findAll("alice"); // 无插件用户不做无谓的行情轮询
    }

    @Test
    void breakCostDisabled_skipsCostAlert() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600123", quote("600123", "+0.50")));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600123", "立昂微", "26.00", "10.00")));
        MarketPushRepository push = mock(MarketPushRepository.class);

        build(market, positions, false, push).poll("default");

        verify(push, never()).append(anyString(), any(), any(MarketPushEvent.class));
    }
}
