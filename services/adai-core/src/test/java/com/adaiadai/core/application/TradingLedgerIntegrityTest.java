package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.SnapshotHolding;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingLedgerIntegrityTest — 交易账实一致性（2026-09-12 生产事故治本批，RFC 20260912）。
 * 覆盖：①锚定缺失 fail-closed（不再静默重放双计）②「仅补流水」安全模式
 * ③跨来源同笔合并回填（有编号 vs 无编号不再双落）④同价同量不同时刻不得误合并
 * ⑤卖超/未持有的真实成交仍落流水 + rejected 可见（不再消失）⑥预检 dryRun 零写入
 * ⑦对账闸门 integrity（派生持仓 vs 落地持仓 + 重放缺口）。
 */
class TradingLedgerIntegrityTest {

    private static final String USER = "default";

    /** 通达信「历史成交查询」导出结构（空格对齐；列序：日期 时间 代码 名称 买卖 数量 价格 金额 委托 成交编号 发生金额 股东 备注）。 */
    private static String tdxRow(LocalDate date, LocalTime time, String symbol, String name,
                                 String flag, int volume, String price, String amount, String orderId) {
        String d = date.toString().replace("-", "");
        return "%s        %s        %s          %s        %s            %s.00          %s         %s         %s           %s                %s          A000000000        证券买入"
                .formatted(d, time + ":00", symbol, name, flag, volume, price, amount, "1" + volume, orderId,
                        "-" + amount);
    }

    private static String tdxText(LocalDate date, LocalTime time, String symbol, String name,
                                  String flag, int volume, String price, String amount, String orderId) {
        String header = "成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格        成交金额        委托编号        成交编号                发生金额          股东代码          备注";
        return header + "\n" + tdxRow(date, time, symbol, name, flag, volume, price, amount, orderId) + "\n";
    }

    private Position pos(String symbol, int qty, String cost) {
        return new Position(symbol, symbol + "名", qty, new BigDecimal(cost), new BigDecimal(cost),
                LocalDateTime.now());
    }

    private TradeRecord record(String symbol, TradeDirection dir, int volume, String price,
                              LocalDate date, LocalTime time, String orderId, BigDecimal fee) {
        return TradeRecord.of("trade_" + symbol + "_" + volume, symbol, symbol + "名", dir,
                new BigDecimal(price), volume, date, time, null, null, null, null, fee,
                LocalDateTime.now(), null, orderId);
    }

    /** 服务装配：锚定仓储与流水仓储由调用方注入（其余 mock 默认值即可）。 */
    private TradingAppService service(PositionRepository repo, TradingHistoryRepository history,
                                      TradingAnchorRepository anchor) {
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        when(ruleRepo.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        return new TradingAppService(repo, mock(RecordRepository.class), history,
                mock(WatchlistRepository.class), mock(SoldTradeRepository.class),
                mock(AccountSnapshotRepository.class), mock(TransferRepository.class),
                mock(MarketDataSource.class), mock(TradingLotService.class), ruleRepo, anchor);
    }

    private TradingAnchorRepository anchorOf(SnapshotAnchor anchor, List<SnapshotHolding> holdings) {
        TradingAnchorRepository a = mock(TradingAnchorRepository.class);
        when(a.find(anyString())).thenReturn(anchor);
        when(a.holdings(anyString())).thenReturn(holdings);
        when(a.holdingsRecorded(anyString())).thenReturn(!holdings.isEmpty());
        return a;
    }

    // ── ① 锚定缺失 + 已有账目状态 + 需要回放 → fail-closed（不再静默重放双计）──

    @Test
    void importAnchorMissing_withExistingState_rejectsAndWritesNothing() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = anchorOf(SnapshotAnchor.empty(), List.of());
        TradingAppService service = service(repo, history, anchor);

        TradingException ex = assertThrows(TradingException.class, () ->
                service.importHistoricalTrades(USER,
                        tdxText(today, LocalTime.of(10, 0), "600000", "浦发银行", "买入", 100,
                                "10.50000000", "1050.00", "999001"), null, false));
        assertTrue(ex.getMessage().contains("锚定"), "应提示锚定缺失：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("仅补流水"), "应给出 append 逃生路径");
        verify(history, never()).append(anyString(), any());
        verify(repo, never()).saveAll(anyString(), any());
    }

    /** 全新用户（无持仓/无账户快照）没有双计风险 → 仍允许从零回放（不误伤首次使用）。 */
    @Test
    void importAnchorMissing_withoutExistingState_replaysFromScratch() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = anchorOf(SnapshotAnchor.empty(), List.of());
        TradingAppService service = service(repo, history, anchor);

        TradingAppService.HistoricalTradeImportResult r = service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(10, 0), "600000", "浦发银行", "买入", 100,
                        "10.50000000", "1050.00", "999002"), null, false);
        assertEquals(1, r.imported());
        verify(repo, times(1)).saveAll(anyString(), any());
    }

    // ── ②「仅补流水」安全模式：锚定缺失也能导，但持仓/现金绝不动 ──

    @Test
    void importAppendMode_missingAnchor_appendsOnlyWithoutTouchingPositions() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = anchorOf(SnapshotAnchor.empty(), List.of());
        TradingAppService service = service(repo, history, anchor);

        TradingAppService.HistoricalTradeImportResult r = service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(10, 0), "600000", "浦发银行", "买入", 100,
                        "10.50000000", "1050.00", "999003"),
                TradingAppService.ImportMode.APPEND, false);
        assertEquals(1, r.imported());
        assertEquals("append", r.syncMode());
        verify(repo, never()).saveAll(anyString(), any());
        verify(history, times(1)).append(anyString(), any());
    }

    // ── ③④ 幂等统一：跨来源同笔合并回填；同价同量不同时刻不得误合并 ──

    @Test
    void importWithOrderId_matchingOrderlessFingerprint_mergesInsteadOfDuplicating() {
        LocalDate today = LocalDate.now();
        // 已有：白天截图/记录归集的同一笔（无 orderId，10:00:00）
        TradeRecord existing = record("600000", TradeDirection.BUY, 100, "10.5", today,
                LocalTime.of(10, 0), null, null);
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(existing));
        when(history.mergeFromImport(anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        TradingAppService service = service(repo, history,
                anchorOf(new SnapshotAnchor(today, today), List.of()));

        TradingAppService.HistoricalTradeImportResult r = service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(10, 0), "600000", "浦发银行", "买入", 100,
                        "10.50000000", "1050.00", "999004"), null, false);
        assertEquals(0, r.imported(), "不得新增流水（跨来源同笔）");
        assertEquals(1, r.updated(), "应合并回填成交编号/费用");
        verify(history, never()).append(anyString(), any());
    }

    @Test
    void importSamePriceVolumeDifferentTime_isTreatedAsSeparateTrade() {
        LocalDate today = LocalDate.now();
        TradeRecord existing = record("600000", TradeDirection.BUY, 100, "10.5", today,
                LocalTime.of(9, 35), null, null);
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(existing));
        TradingAppService service = service(repo, history,
                anchorOf(new SnapshotAnchor(today, today), List.of()));

        TradingAppService.HistoricalTradeImportResult r = service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(14, 0), "600000", "浦发银行", "买入", 100,
                        "10.50000000", "1050.00", "999005"), null, false);
        assertEquals(1, r.imported(), "时刻明显不同 = 两笔真实成交，不得合并");
        verify(history, times(1)).append(anyString(), any());
    }

    // ── ⑤ 卖超/未持有：真实成交仍落流水 + rejected 可见，持仓/现金不动 ──

    @Test
    void replaySellExceedingHoldings_landsInLedgerAndIsReported() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600487", 100, "65.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        // 锚定日 = 昨天 → 今天的成交走回放
        TradingAppService service = service(repo, history,
                anchorOf(new SnapshotAnchor(today.minusDays(1), today.minusDays(1)), List.of()));

        TradingAppService.HistoricalTradeImportResult r = service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(10, 0), "600487", "亨通光电", "卖出", 400,
                        "65.31000000", "26124.00", "999006"), null, false);
        assertEquals(0, r.imported(), "无法归属 → 不进回放计数");
        assertEquals(1, r.rejected().size(), "必须进 rejected 明细（不再消失在「跳过」里）");
        assertTrue(r.rejected().get(0).reason().contains("超过可归属持仓"),
                r.rejected().get(0).reason());
        verify(history, times(1)).append(anyString(), any()); // 只落流水
        verify(repo, never()).saveAll(anyString(), any());    // 持仓不动
    }

    @Test
    void replaySellWithoutHolding_landsInLedgerAndIsReported() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo, history,
                anchorOf(new SnapshotAnchor(today.minusDays(1), today.minusDays(1)), List.of()));

        TradingAppService.HistoricalTradeImportResult r = service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(10, 0), "000831", "中国稀土", "卖出", 800,
                        "54.83000000", "43864.00", "999007"), null, false);
        assertEquals(1, r.rejected().size());
        assertTrue(r.rejected().get(0).reason().contains("未持有"), r.rejected().get(0).reason());
        verify(history, times(1)).append(anyString(), any());
        verify(repo, never()).saveAll(anyString(), any());
    }

    /** 预检也必须如实拒绝（不能给一份「确认后就 400」的假计划）。 */
    @Test
    void dryRun_withMissingAnchorAndExistingState_alsoFailsClosed() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo, history, anchorOf(SnapshotAnchor.empty(), List.of()));

        assertThrows(TradingException.class, () -> service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(10, 0), "600000", "浦发银行", "买入", 100,
                        "10.50000000", "1050.00", "999010"), null, true));
        verify(history, never()).append(anyString(), any());
    }

    /** 同一文件内完全相同的两行（同编号）只应回放一次（旧实现靠 orderIds 累加防住，重写不得退化）。 */
    @Test
    void replay_duplicateRowsWithinSameFile_areDeduplicated() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo, history,
                anchorOf(SnapshotAnchor.empty(), List.of())); // 无持仓无账户 → 允许从零回放
        String row = tdxRow(today, LocalTime.of(10, 0), "600000", "浦发银行", "买入", 100,
                "10.50000000", "1050.00", "999011");
        String header = "成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格        成交金额        委托编号        成交编号                发生金额          股东代码          备注";
        TradingAppService.HistoricalTradeImportResult r =
                service.importHistoricalTrades(USER, header + "\n" + row + "\n" + row + "\n", null, false);
        assertEquals(1, r.imported(), "同编号重复行只回放一次");
        assertEquals(1, r.skipped());
    }

    // ── ⑥ 预检 dryRun：只算计划，零写入 ──

    @Test
    void dryRun_reportsPlanWithoutWritingAnything() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo, history,
                anchorOf(new SnapshotAnchor(today.minusDays(1), today.minusDays(1)), List.of()));

        TradingAppService.HistoricalTradeImportResult plan = service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(10, 0), "600000", "浦发银行", "买入", 100,
                        "10.50000000", "1050.00", "999008"), null, true);
        assertEquals(1, plan.imported());
        assertTrue(plan.anchor().known());
        verify(history, never()).append(anyString(), any());
        verify(history, never()).mergeFromImport(anyString(), anyString(), any(), any(), any(), any());
        verify(repo, never()).saveAll(anyString(), any());
    }

    @Test
    void dryRun_previewsUnattributableRows() {
        LocalDate today = LocalDate.now();
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo, history,
                anchorOf(new SnapshotAnchor(today.minusDays(1), today.minusDays(1)), List.of()));

        TradingAppService.HistoricalTradeImportResult plan = service.importHistoricalTrades(USER,
                tdxText(today, LocalTime.of(10, 0), "000831", "中国稀土", "卖出", 800,
                        "54.83000000", "43864.00", "999009"), null, true);
        assertEquals(1, plan.rejected().size(), "预检就要告诉用户这笔无法归属");
        verify(history, never()).append(anyString(), any());
    }

    // ── ⑦ 对账闸门 integrity：派生持仓 vs 落地持仓 + 重放缺口 ──

    @Test
    void integrity_reportsDriftBetweenDerivedAndLandedPositions() {
        LocalDate anchorDate = LocalDate.of(2026, 9, 9);
        PositionRepository repo = mock(PositionRepository.class);
        // 落地 150 股，但「基线 100 + 锚点后买入 100」= 应有 200 股 → 差 −50
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600206", 150, "46.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                record("600206", TradeDirection.BUY, 100, "46.85", anchorDate.plusDays(1),
                        LocalTime.of(14, 53), "65872510", new BigDecimal("1.79"))));
        TradingAppService service = service(repo, history, anchorOf(
                new SnapshotAnchor(anchorDate, anchorDate),
                List.of(new SnapshotHolding("600206", "有研新材", 100))));

        TradingAppService.IntegrityReport report = service.integrity(USER);
        assertTrue(report.holdingsKnown());
        assertEquals(1, report.drift().size());
        TradingAppService.DriftLine line = report.drift().get(0);
        assertEquals(100, line.snapshotQty());
        assertEquals(100, line.ledgerDelta());
        assertEquals(200, line.derived());
        assertEquals(150, line.holdings());
        assertEquals(-50, line.diff());
        assertTrue(report.note().contains("账实不符"), report.note());
    }

    @Test
    void integrity_consistentPositions_reportsClean() {
        LocalDate anchorDate = LocalDate.of(2026, 9, 9);
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600206", 200, "46.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                record("600206", TradeDirection.BUY, 100, "46.85", anchorDate.plusDays(1),
                        LocalTime.of(14, 53), "65872510", new BigDecimal("1.79"))));
        TradingAppService service = service(repo, history, anchorOf(
                new SnapshotAnchor(anchorDate, anchorDate),
                List.of(new SnapshotHolding("600206", "有研新材", 100))));

        TradingAppService.IntegrityReport report = service.integrity(USER);
        assertTrue(report.drift().isEmpty());
        assertTrue(report.gaps().isEmpty());
        assertTrue(report.note().contains("账实一致"), report.note());
    }

    @Test
    void integrity_reportsReplayGapWhenSellExceedsBaseline() {
        LocalDate anchorDate = LocalDate.of(2026, 9, 9);
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                record("000831", TradeDirection.SELL, 800, "54.83", anchorDate.plusDays(1),
                        LocalTime.of(14, 53), "0104000068320388", new BigDecimal("4.38"))));
        TradingAppService service = service(repo, history, anchorOf(
                new SnapshotAnchor(anchorDate, anchorDate),
                List.of(new SnapshotHolding("000831", "中国稀土", 100))));

        TradingAppService.IntegrityReport report = service.integrity(USER);
        assertEquals(1, report.gaps().size(), "卖超 = 重放缺口，必须报出");
        assertTrue(report.gaps().get(0).reason().contains("重放时持仓不足"));
    }

    @Test
    void integrity_degradesHonestlyWhenAnchorOrBaselineMissing() {
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());

        TradingAppService noAnchor = service(repo, history, anchorOf(SnapshotAnchor.empty(), List.of()));
        TradingAppService.IntegrityReport r1 = noAnchor.integrity(USER);
        assertFalse(r1.anchor().known());
        assertTrue(r1.drift().isEmpty());
        assertTrue(r1.note().contains("锚定缺失"), r1.note());

        LocalDate anchorDate = LocalDate.of(2026, 9, 9);
        TradingAppService noBaseline = service(repo, history,
                anchorOf(new SnapshotAnchor(anchorDate, anchorDate), List.of()));
        TradingAppService.IntegrityReport r2 = noBaseline.integrity(USER);
        assertFalse(r2.holdingsKnown());
        assertTrue(r2.note().contains("基线未记录"), r2.note());
    }

    // ── 锚点回填（存量自愈）──

    @Test
    void backfillAnchor_writesDatesAndHoldings() {
        TradingAnchorRepository anchor = mock(TradingAnchorRepository.class);
        when(anchor.find(anyString())).thenReturn(new SnapshotAnchor(LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 9)));
        when(anchor.holdings(anyString())).thenReturn(List.of(new SnapshotHolding("600206", "有研新材", 600)));
        when(anchor.holdingsRecorded(anyString())).thenReturn(true);
        TradingAppService service = service(mock(PositionRepository.class), mock(TradingHistoryRepository.class), anchor);

        TradingAppService.AnchorStatus status = service.backfillAnchor(USER, LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 9), List.of(new SnapshotHolding("600206", "有研新材", 600)));
        assertTrue(status.known());
        assertEquals(LocalDate.of(2026, 9, 9), status.anchorDate());
        verify(anchor, times(1)).updatePositionsReplace(eq(USER), eq(LocalDate.of(2026, 9, 9)));
        verify(anchor, times(1)).updateCashImport(eq(USER), eq(LocalDate.of(2026, 9, 9)));
        verify(anchor, times(1)).recordHoldings(eq(USER), any());
    }

    @Test
    void backfillAnchor_emptyBody_rejects() {
        TradingAppService service = service(mock(PositionRepository.class),
                mock(TradingHistoryRepository.class), anchorOf(SnapshotAnchor.empty(), List.of()));
        assertThrows(TradingException.class, () -> service.backfillAnchor(USER, null, null, List.of()));
    }
}
