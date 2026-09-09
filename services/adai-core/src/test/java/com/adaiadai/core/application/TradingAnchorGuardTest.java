package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TransferRecord;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-交易34 治本（2026-09-09）——券商快照锚定防重守卫测试：
 * 锚定（持仓 replace / 资金股份导入）之后，entryDate ≤ 锚定日的增量
 * （手动成交补录、转账补记、sync 回放）不再重复入账。
 * <p>
 * 与 TradingAppServiceTest 的既有业务测试互补：本文件只覆盖锚定新增逻辑，
 * 使用纯 mock（服务 11 参构造 + mock 锚定仓储）。
 */
class TradingAnchorGuardTest {

    private static final String USER = "default";

    private Position pos(String symbol, int qty) {
        return new Position(symbol, symbol + "名", qty, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now());
    }

    /** 锚定 mock：默认空锚定；可用 when(...) 覆写。 */
    private TradingAnchorRepository anchorRepo() {
        TradingAnchorRepository a = mock(TradingAnchorRepository.class);
        when(a.find(anyString())).thenReturn(SnapshotAnchor.empty());
        return a;
    }

    private TradingAppService service(PositionRepository repo, AccountSnapshotRepository acc,
                                      TransferRepository transferRepo, TradingAnchorRepository anchor) {
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        when(ruleRepo.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        return new TradingAppService(repo, mock(RecordRepository.class),
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), acc, transferRepo,
                mock(MarketDataSource.class), mock(TradingLotService.class), ruleRepo, anchor);
    }

    // ── 手动/确认成交：entryDate ≤ 锚定日 → 拒绝 ──

    @Test
    void recordTrade_coveredByAnchor_rejectsWithHumanMessage() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100)));
        TradingAnchorRepository anchor = anchorRepo();
        when(anchor.find(anyString()))
                .thenReturn(new SnapshotAnchor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 9)));
        TradingAppService service = service(repo, mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class), anchor);

        TradingException ex = assertThrows(TradingException.class, () ->
                service.recordTrade(USER, "600000", "浦发银行", TradeDirection.BUY,
                        new BigDecimal("10.5"), 100, LocalDate.of(2026, 9, 9), null,
                        null, null, null, null));
        assertTrue(ex.getMessage().contains("已包含"), "应提示已包含在券商快照中");
        assertTrue(ex.getMessage().contains("历史成交导入"), "应指路历史成交导入/重导快照");
        verify(repo, never()).saveAll(anyString(), any());
    }

    @Test
    void recordTrade_afterAnchorDate_allowedAndUpdatesPositions() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100)));
        TradingAnchorRepository anchor = anchorRepo();
        when(anchor.find(anyString()))
                .thenReturn(new SnapshotAnchor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 9)));
        TradingAppService service = service(repo, mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class), anchor);

        service.recordTrade(USER, "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 9, 10), null,
                null, null, null, null);
        verify(repo, times(1)).saveAll(eq(USER), argThat(list ->
                list.size() == 1 && list.get(0).quantity() == 200));
    }

    @Test
    void recordTrade_noAnchor_legacyBehaviorAllowed() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos("600000", 100)));
        TradingAppService service = service(repo, mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class), anchorRepo());

        service.recordTrade(USER, "600000", "浦发银行", TradeDirection.SELL,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 9, 1), null,
                null, null, null, null);
        verify(repo, times(1)).saveAll(anyString(), any());
    }

    // ── 转账：date ≤ 最近资金股份导入锚定日 → 拒绝（现金已含在快照）──

    @Test
    void recordTransfer_coveredByCashAnchor_rejects() {
        TradingAnchorRepository anchor = anchorRepo();
        when(anchor.find(anyString()))
                .thenReturn(new SnapshotAnchor(null, LocalDate.of(2026, 9, 9)));
        TransferRepository transferRepo = mock(TransferRepository.class);
        TradingAppService service = service(mock(PositionRepository.class),
                mock(AccountSnapshotRepository.class), transferRepo, anchor);

        TradingException ex = assertThrows(TradingException.class, () ->
                service.recordTransfer(USER, "OUT", new BigDecimal("15000"),
                        LocalDate.of(2026, 9, 9), "提现"));
        assertTrue(ex.getMessage().contains("已包含"), "应提示现金变动已在快照内");
        assertTrue(ex.getMessage().contains("设置本金"), "应指路纯净投入用设置本金");
        verify(transferRepo, never()).append(anyString(), any(TransferRecord.class));
    }

    @Test
    void recordTransfer_afterCashAnchor_allowed() {
        TradingAnchorRepository anchor = anchorRepo();
        when(anchor.find(anyString()))
                .thenReturn(new SnapshotAnchor(null, LocalDate.of(2026, 9, 9)));
        TransferRepository transferRepo = mock(TransferRepository.class);
        TradingAppService service = service(mock(PositionRepository.class),
                mock(AccountSnapshotRepository.class), transferRepo, anchor);

        TransferRecord r = service.recordTransfer(USER, "OUT", new BigDecimal("5000"),
                LocalDate.of(2026, 9, 10), "提现");
        assertNotNull(r);
        verify(transferRepo, times(1)).append(anyString(), any(TransferRecord.class));
    }

    // ── 锚定记录：replace 持仓导入 / 资金股份导入 落地锚定日 ──

    @Test
    void importPositions_replaceTrue_recordsAnchor() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = anchorRepo();
        TradingAppService service = service(repo, mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class), anchor);

        service.importPositions(USER, List.of(new TradingAppService.PositionImportItem(
                "600000", "浦发银行", 100, new BigDecimal("10.0"), null, null, null, null)), true);
        verify(repo, times(1)).saveAll(anyString(), any());
        verify(anchor, times(1)).updatePositionsReplace(eq(USER), any(LocalDate.class));
    }

    @Test
    void importPositions_noReplace_doesNotRecordAnchor() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = anchorRepo();
        TradingAppService service = service(repo, mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class), anchor);

        service.importPositions(USER, List.of(new TradingAppService.PositionImportItem(
                "600000", "浦发银行", 100, new BigDecimal("10.0"), null, null, null, null)), false);
        verify(anchor, never()).updatePositionsReplace(anyString(), any(LocalDate.class));
    }

    @Test
    void importCashQuery_recordsCashAnchor() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = anchorRepo();
        TradingAppService service = service(repo, mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class), anchor);
        String cashText = """
                人民币: 余额:292.88  可用:292.88  可取:292.88  参考市值:110212.00  资产:110504.88  盈亏:15235.55
                -------------------------------------------------------------------------------------------------------
                编号        证券代码        证券名称        证券数量        可卖数量        成本价          当前价          最新市值        今买数量        今卖数量        浮动盈亏        盈亏比例(%)        股东代码
                1           600809          山西汾酒        100.00          100.00          122.3849        123.5200        12352.00        0.00            0.00            113.44          0.927              A511358384
                """;
        TradingAppService.CashImportResult r = service.importCashQuery(USER, cashText);
        assertEquals(0, r.cash().compareTo(new BigDecimal("292.88")));
        verify(anchor, times(1)).updateCashImport(eq(USER), any(LocalDate.class));
    }
}
