package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.PendingClearance;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.SoldTradeFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RFC 20260909 批 1（清仓股流水自动收录）——ClearanceDetector 判定测试：
 * A 完整可推导（BUY ≥ SELL）→ 自动收录 flow 行（只填空白、幂等）；
 * B 缺买入基线（BUY &lt; SELL）→ 不落库、进 pending；detectPending 全量扫描。
 * 用真实 SoldTradeFileRepository（InMemory）+ mock 流水/持仓/规则，覆盖仓储锁内 upsert 语义。
 */
class ClearanceDetectorTest {

    private static final String USER = "default";

    private SoldTradeRepository soldRepo;
    private TradingHistoryRepository history;
    private ClearanceDetector detector;

    @BeforeEach
    void setUp() {
        soldRepo = new SoldTradeFileRepository(new InMemoryFileStorage());
        history = mock(TradingHistoryRepository.class);
        com.adaiadai.core.domain.trading.PositionRepository positions = mock(com.adaiadai.core.domain.trading.PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of());
        TradingRuleSettingsRepository rules = mock(TradingRuleSettingsRepository.class);
        when(rules.findByUser(anyString())).thenReturn(com.adaiadai.core.domain.trading.TradingRuleSettings.defaults());
        detector = new ClearanceDetector(history, positions, soldRepo, rules);
    }

    private TradeRecord trade(String symbol, TradeDirection dir, int volume, String price, String fee, LocalDate date) {
        return new TradeRecord("t_" + symbol + "_" + dir + "_" + volume + "_" + date,
                symbol, symbol + "名", dir, new BigDecimal(price), volume,
                new BigDecimal(price).multiply(BigDecimal.valueOf(volume)),
                date, LocalTime.of(10, 0), null, null, null, null,
                fee != null ? new BigDecimal(fee) : null,
                LocalDateTime.of(date, LocalTime.of(10, 0)), null, null);
    }

    @Test
    void sync_completeFlow_autoRecordsFlowRow() {
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.BUY, 100, "10.00", "0.50", LocalDate.of(2026, 9, 1)),
                trade("600000", TradeDirection.SELL, 100, "12.00", "0.60", LocalDate.of(2026, 9, 3))));

        ClearanceDetector.ClearanceOutcome out = detector.sync(USER, List.of("600000"));

        assertEquals(1, out.added().size(), "完整清仓应自动收录");
        assertTrue(out.pending().isEmpty());
        List<SoldTrade> sold = soldRepo.findAll(USER);
        assertEquals(1, sold.size());
        SoldTrade s = sold.get(0);
        assertEquals("flow", s.provenance());
        assertEquals("600000", s.symbol());
        assertEquals(LocalDate.of(2026, 9, 1), s.buyDate());
        assertEquals(LocalDate.of(2026, 9, 3), s.sellDate());
        assertEquals(3, s.holdDays());
        assertEquals(2, Integer.parseInt(s.tradeCount()));
        // ((1200-0.6)-(1000+0.5)) / (1000+0.5) × 100 ≈ 19.88
        assertTrue(Math.abs(s.holdPnlPct() - 19.88) < 0.01, "回合盈亏率应近似 19.88，实际 " + s.holdPnlPct());
    }

    @Test
    void sync_repeat_doesNotDuplicate() {
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.BUY, 100, "10.00", null, LocalDate.of(2026, 9, 1)),
                trade("600000", TradeDirection.SELL, 100, "12.00", null, LocalDate.of(2026, 9, 3))));
        detector.sync(USER, List.of("600000"));
        detector.sync(USER, List.of("600000")); // 幂等：重复触发不翻倍
        assertEquals(1, soldRepo.findAll(USER).size());
    }

    @Test
    void sync_missingBaseline_notRecorded_goesPending() {
        // 流水只含卖出（买入基线在窗口外）→ 不落库、pending 提示
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600519", TradeDirection.SELL, 100, "1500.00", null, LocalDate.of(2026, 9, 5))));

        ClearanceDetector.ClearanceOutcome out = detector.sync(USER, List.of("600519"));

        assertTrue(out.added().isEmpty());
        assertEquals(1, out.pending().size());
        PendingClearance p = out.pending().get(0);
        assertEquals("600519", p.symbol());
        assertTrue(soldRepo.findAll(USER).isEmpty(), "缺基线不得写脏档案");
    }

    @Test
    void sync_symbolAlreadyInSold_neverOverwrites() {
        // P1（用户拍板）：只填空白 symbol——已存在行（import 口径）绝不覆盖
        soldRepo.upsertFromFlow(USER, new SoldTrade("600000", "浦发银行",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20), 20, "2", 55.0,
                "盈利了结", "人工复盘", "import"));
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.BUY, 100, "10.00", null, LocalDate.of(2026, 9, 1)),
                trade("600000", TradeDirection.SELL, 100, "12.00", null, LocalDate.of(2026, 9, 3))));

        ClearanceDetector.ClearanceOutcome out = detector.sync(USER, List.of("600000"));

        assertTrue(out.added().isEmpty(), "已存在不得覆盖/重录");
        SoldTrade s = soldRepo.findAll(USER).get(0);
        assertEquals("import", s.provenance());
        assertEquals(20, s.holdDays(), "人工行内容不被 flow 推导覆盖");
    }

    @Test
    void detectPending_fullScan_returnsOnlyBaselineClearedSymbols() {
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600519", TradeDirection.SELL, 100, "1500.00", null, LocalDate.of(2026, 9, 5)),
                trade("000725", TradeDirection.BUY, 100, "6.10", null, LocalDate.of(2026, 9, 1)),
                trade("000725", TradeDirection.SELL, 100, "6.50", null, LocalDate.of(2026, 9, 6))));
        // 000725 完整 → 进 sold（sync），600519 缺基线 → pending
        detector.sync(USER, List.of("000725", "600519"));

        List<PendingClearance> pending = detector.detectPending(USER);
        assertEquals(1, pending.size());
        assertEquals("600519", pending.get(0).symbol());
        assertEquals(1, soldRepo.findAll(USER).size(), "完整清仓已收录，不再出现在 pending");
    }
}
