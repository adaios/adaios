package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.PositionFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingHistoryFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingAppService — 交易业务规则测试（REVIEW #147 + RFC 20260815 闭环 + RFC 20260816 逐笔流水/新字段）。
 */
class TradingAppServiceTest {

    private Position pos(String symbol, int qty) {
        return new Position(symbol, symbol + "名", qty, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now());
    }

    private TradingAppService service(PositionRepository repo, RecordRepository records) {
        return service(repo, records, mock(TradingHistoryRepository.class));
    }

    private TradingAppService service(PositionRepository repo, RecordRepository records,
                                      TradingHistoryRepository history) {
        return new TradingAppService(repo, records, history,
                mock(WatchlistRepository.class), mock(SoldTradeRepository.class),
                mock(AccountSnapshotRepository.class), mock(TransferRepository.class),
                mock(MarketDataSource.class));
    }

    // ── 基础业务规则（REVIEW #147）──

    @Test
    void recordTrade_sellUnheld_throwsNotSilent() {
        // #147：SELL 未持有 symbol 必须报错，不再静默 no-op
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, mock(RecordRepository.class));

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 100, null, null, null, null, null, null));
    }

    @Test
    void recordTrade_sellOverHeld_throws() {
        // #147：卖出数量超过持仓 → 报错，防静默清仓失真
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        TradingAppService service = service(repo, mock(RecordRepository.class));

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 200, null, null, null, null, null, null));
    }

    @Test
    void recordTrade_fullSell_clearsPosition() {
        // 恰好全部卖出 → 清仓，0 持仓行不落盘
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                new BigDecimal("10.5"), 100, null, null, null, null, null, null);

        ArgumentCaptor<List<Position>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(any(), captor.capture());
        assertTrue(captor.getValue().isEmpty(), "清仓后不应残留 0 持仓行");
    }

    @Test
    void recordTrade_buyNew_createsPosition() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, null, null, new BigDecimal("9.5"), "B1", null, null);

        assertTrue(result.size() == 1 && result.get(0).symbol().equals("600000"),
                "首次买入应新建持仓");
    }

    // ── RFC 20260815：name 可空兜底 ──

    @Test
    void recordTrade_buyWithoutName_usesSymbolAsName() {
        // name 可空：缺名时以 symbol 兜底（写入持仓与交易记录都使用兜底名）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", null, TradeDirection.BUY,
                new BigDecimal("10.5"), 100, null, null, new BigDecimal("9.5"), "B1", null, null);

        assertEquals("600000", result.get(0).name(), "缺名时以 symbol 兜底为名称");
        ArgumentCaptor<List<Position>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(any(), captor.capture());
        assertEquals("600000", captor.getValue().get(0).name(), "落盘持仓也应为兜底名");
    }

    @Test
    void recordTrade_blankName_usesSymbolAsName() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "  ", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, null, null, new BigDecimal("9.5"), "B1", null, null);

        assertEquals("600000", result.get(0).name(), "空白名也以 symbol 兜底");
    }

    // ── RFC 20260815：recordTrade 成功写 domain=trading 记录（复盘提醒闭环） ──

    @Test
    void recordTrade_success_writesTradingRecord() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, null, null, new BigDecimal("9.5"), "B1", null, null);

        ArgumentCaptor<ContentRecord> captor = ArgumentCaptor.forClass(ContentRecord.class);
        verify(records).save(any(), captor.capture());
        ContentRecord r = captor.getValue();
        assertEquals("trading", r.domain(), "记录 domain 应为 trading（复盘关键词检测 + 时间线归类）");
        assertEquals("trade", r.type());
        assertEquals("auto_collect", r.source());
        assertTrue(r.title().contains("买入"), "标题应含方向");
        assertTrue(r.title().contains("浦发银行") && r.title().contains("100股") && r.title().contains("@10.5"),
                "标题应符合「买入 名称 数量股@价格」格式，实际: " + r.title());
        assertTrue(r.tags().contains("trading"), "记录应带 trading 标签");
    }

    @Test
    void recordTrade_failure_doesNotWriteRecord() {
        // recordTrade 失败（SELL 未持有 → TradingException）时不应留下交易记录
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        TradingAppService service = service(repo, records);

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 100, null, null, null, null, null, null));

        verify(records, never()).save(any(), any());
    }

    @Test
    void recordTrade_retryWithinWindow_doesNotDuplicateRecord() {
        // 幂等：窗口内已存在同标题记录（重试）→ 不重复写，防重复进时间线/复盘提醒
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        ContentRecord existing = new ContentRecord(
                "rec_old", "trade", "auto_collect",
                "买入 浦发银行 100股@10.5",
                "买入 浦发银行（600000）100股@10.5，成交金额 1050.00 元",
                List.of("trading", "交易"), LocalDateTime.now(), null, null, "trading");
        when(records.findAll(any())).thenReturn(List.of(existing));
        TradingAppService service = service(repo, records);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, null, null, new BigDecimal("9.5"), "B1", null, null);

        verify(records, never()).save(any(), any());
    }

    @Test
    void recordTrade_recordWriteFailure_doesNotFailTrade() {
        // 记录写入是附加动作：即使失败也不阻塞交易本身（持仓已落库）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenThrow(new RuntimeException("存储不可用"));
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, null, null, new BigDecimal("9.5"), "B1", null, null);

        assertTrue(result.size() == 1, "记录写入失败不应影响交易结果");
    }

    // ── RFC 20260816：逐笔流水（BUY/SELL 都写 TradeRecord）──

    @Test
    void recordTrade_buy_writesTradeRecordWithStopLossAndBuyPoint() {
        // BUY：流水带止损/买点/目标价/原因/入场日期，amount=price×volume，id 为 trade_ 前缀
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        TradingAppService service = service(repo, records, history);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16), null,
                new BigDecimal("9.5"), "B1", new BigDecimal("12.0"), "突破买入");

        ArgumentCaptor<TradeRecord> captor = ArgumentCaptor.forClass(TradeRecord.class);
        verify(history).append(any(), captor.capture());
        TradeRecord t = captor.getValue();
        assertTrue(t.id().startsWith("trade_"), "流水 ID 应为 trade_ 前缀，实际: " + t.id());
        assertEquals("600000", t.symbol());
        assertEquals(TradeDirection.BUY, t.direction());
        assertEquals(LocalDate.of(2026, 8, 16), t.entryDate(), "入场日期应落盘");
        assertEquals(new BigDecimal("9.5"), t.stopLossPrice(), "BUY 止损位应写入流水");
        assertEquals("B1", t.buyPoint(), "BUY 买点应写入流水");
        assertEquals(new BigDecimal("12.0"), t.targetPrice());
        assertEquals("突破买入", t.reason());
        assertEquals(0, new BigDecimal("1050.0").compareTo(t.amount()), "amount=price×volume");
        assertNotNull(t.timestamp(), "流水应带落盘时间");
    }

    @Test
    void recordTrade_sell_writesTradeRecordWithoutStopLoss() {
        // SELL：流水同样落盘，但止损/买点可空（不写止损）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 200)));
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        TradingAppService service = service(repo, records, history);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16), null, null, null, null, null);

        ArgumentCaptor<TradeRecord> captor = ArgumentCaptor.forClass(TradeRecord.class);
        verify(history).append(any(), captor.capture());
        TradeRecord t = captor.getValue();
        assertEquals(TradeDirection.SELL, t.direction());
        assertNull(t.stopLossPrice(), "SELL 流水不写止损");
        assertNull(t.buyPoint(), "SELL 流水不写买点");
    }

    @Test
    void recordTrade_buyWithoutEntryDate_defaultsToToday() {
        // entryDate 可空：缺省今天（TradeRequest 可空，服务层兜底）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        TradingAppService service = service(repo, records, history);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, null, null, new BigDecimal("9.5"), "B1", null, null);

        ArgumentCaptor<TradeRecord> captor = ArgumentCaptor.forClass(TradeRecord.class);
        verify(history).append(any(), captor.capture());
        assertEquals(LocalDate.now(), captor.getValue().entryDate(), "缺省入场日期应为今天");
    }

    @Test
    void recordTrade_buy_failure_doesNotWriteTradeRecord() {
        // recordTrade 失败（SELL 未持有）→ 不写流水（与不写时间线记录同口径）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        TradingAppService service = service(repo, records, history);

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 100, null, null, null, null, null, null));

        verify(history, never()).append(any(), any());
    }

    @Test
    void recordTrade_historyAppendFailure_doesNotFailTrade() {
        // 流水写入 best-effort：失败不阻塞交易本身（持仓已落库）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        doThrow(new RuntimeException("磁盘不可写")).when(history).append(any(), any());
        TradingAppService service = service(repo, records, history);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, null, null, new BigDecimal("9.5"), "B1", null, null);

        assertTrue(result.size() == 1, "流水写入失败不应影响交易结果");
    }

    // ── RFC 20260816：Position 新字段（entryDate 首买不覆盖 / 止损买点最近一次 BUY）──

    @Test
    void recordTrade_buyNew_setsEntryDateStopLossBuyPointOnPosition() {
        // 首次买入：entryDate（首买日）/stopLossPrice/buyPoint 落盘到持仓
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16), null,
                new BigDecimal("9.5"), "B1", null, null);

        Position p = result.get(0);
        assertEquals(LocalDate.of(2026, 8, 16), p.entryDate(), "首买日应落盘");
        assertEquals(new BigDecimal("9.5"), p.stopLossPrice());
        assertEquals("B1", p.buyPoint());
    }

    @Test
    void recordTrade_buyAddsPosition_keepsFirstEntryDate() {
        // 加仓不覆盖首买日：已有 entryDate 的持仓 BUY → entryDate 保留，止损/买点更新为最近一次 BUY
        Position existing = new Position("600000", "浦发银行", 100, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now(), LocalDate.of(2026, 8, 1), new BigDecimal("9.0"), "B2", null);
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(existing));
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 10), null,
                new BigDecimal("9.5"), "B1", null, null);

        Position updated = result.get(0);
        assertEquals(LocalDate.of(2026, 8, 1), updated.entryDate(), "加仓不覆盖首买日");
        assertEquals(new BigDecimal("9.5"), updated.stopLossPrice(), "止损更新为最近一次 BUY");
        assertEquals("B1", updated.buyPoint(), "买点更新为最近一次 BUY");
        assertEquals(200, updated.quantity());
    }

    @Test
    void recordTrade_buyAddsPosition_missingEntryDate_fallsBackToBuyDate() {
        // 旧数据持仓无 entryDate：加仓时以本次 BUY 入场日期补录
        Position existing = new Position("600000", "浦发银行", 100, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now());
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(existing));
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16), null,
                new BigDecimal("9.5"), "B1", null, null);

        assertEquals(LocalDate.of(2026, 8, 16), result.get(0).entryDate(), "旧持仓无首买日 → 本次 BUY 补录");
    }

    @Test
    void recordTrade_sell_keepsPlanFieldsOnPosition() {
        // SELL（未清仓）：保留 entryDate/止损/买点（不因卖出清空计划字段）
        Position existing = new Position("600000", "浦发银行", 200, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now(), LocalDate.of(2026, 8, 1), new BigDecimal("9.0"), "B2", "防守");
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(existing));
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                new BigDecimal("10.5"), 100, null, null, null, null, null, null);

        Position updated = result.get(0);
        assertEquals(LocalDate.of(2026, 8, 1), updated.entryDate(), "SELL 保留首买日");
        assertEquals(new BigDecimal("9.0"), updated.stopLossPrice(), "SELL 保留止损位");
        assertEquals("B2", updated.buyPoint(), "SELL 保留买点");
        assertEquals("防守", updated.role(), "SELL 保留角色");
        assertEquals(100, updated.quantity());
    }

    // ── RFC 20260816：旧 positions.md 无新列 → 解析兜底不报错 ──

    @Test
    void recordTrade_oldPositionsFileWithoutNewColumns_fallsBackToNull() {
        // 旧格式 positions.md（5 列，无 entryDate/stopLoss/buyPoint/role）→ 解析兜底 null 不报错，
        // 且交易后新列随持仓落盘可读回
        InMemoryFileStorage fs = new InMemoryFileStorage();
        fs.write("default", "trading/positions.md", """
                # 当前持仓

                | symbol | name | quantity | avgCost | currentPrice |
                |--------|------|----------|---------|--------------|
                | 600000 | 浦发银行 | 100 | 10.00 | 10.50 |

                cashBalance: 50000
                lastUpdated: 2026-07-12T11:30:00
                """);
        PositionFileRepository repo = new PositionFileRepository(fs);
        TradingHistoryFileRepository history = new TradingHistoryFileRepository(fs);
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = new TradingAppService(repo, records, history,
                mock(WatchlistRepository.class), mock(SoldTradeRepository.class),
                mock(AccountSnapshotRepository.class), mock(TransferRepository.class),
                mock(MarketDataSource.class));

        // 旧行（600000）无新列：BUY 加仓 → entryDate 以本次 BUY 补录，止损/买点更新
        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16), null,
                new BigDecimal("9.5"), "B1", null, null);

        Position merged = result.get(0);
        assertEquals(LocalDate.of(2026, 8, 16), merged.entryDate(), "旧行无 entryDate → 本次 BUY 落盘");
        assertEquals(new BigDecimal("9.5"), merged.stopLossPrice());
        assertEquals("B1", merged.buyPoint());
        assertEquals(200, merged.quantity());

        // 重新读文件：新列已随持仓写入（round-trip）
        Position reloaded = repo.findAll("default").get(0);
        assertEquals(LocalDate.of(2026, 8, 16), reloaded.entryDate(), "落盘文件应含新列");
        assertEquals(new BigDecimal("9.5"), reloaded.stopLossPrice());
        assertEquals("B1", reloaded.buyPoint());
    }

    // ── getPositions 实时行情注入（2026-08-16：盈亏展示正确性）──

    @Test
    void getPositions_injectsLivePrice_forPnl() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(
                new Position("000725", "京东方A", 1000, new BigDecimal("6.042"), new BigDecimal("6.042"),
                        LocalDateTime.now(), LocalDate.of(2026, 8, 16), null, null, null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725",
                new MarketData("000725", "京东方A", new BigDecimal("5.81"), new BigDecimal("5.81"),
                        new BigDecimal("5.81"), new BigDecimal("5.81"), new BigDecimal("5.81"),
                        new BigDecimal("-0.85"), 0)));
        TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), mock(AccountSnapshotRepository.class), mock(TransferRepository.class), market);

        List<Position> positions = service.getPositions("default");

        assertEquals(1, positions.size());
        assertEquals(0, positions.get(0).currentPrice().compareTo(new BigDecimal("5.81")),
                "现价应为行情价（盈亏展示依据）");
        assertEquals(0, positions.get(0).pnl().compareTo(new BigDecimal("-232")),
                "盈亏 = (5.81-6.042)*1000 = -232，实际: " + positions.get(0).pnl());
    }

    @Test
    void getPositions_quoteFails_fallsBackToStoredPrice() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("000725", 100)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenThrow(new RuntimeException("行情接口挂了"));
        TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), mock(AccountSnapshotRepository.class), mock(TransferRepository.class), market);

        List<Position> positions = service.getPositions("default");

        assertEquals(1, positions.size());
        assertEquals(0, positions.get(0).currentPrice().compareTo(new BigDecimal("10.5")),
                "行情失败用存储价，不报错");
    }

    // ── 自选/清仓/资金（RFC 20260816 交易数据智能）──

@org.junit.jupiter.api.Test
void watchlistImport_upsertsBySymbol() {
    PositionRepository repo = mock(PositionRepository.class);
    WatchlistRepository wl = mock(WatchlistRepository.class);
    when(wl.findAll(any())).thenReturn(new java.util.ArrayList<>());
    TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
            mock(TradingHistoryRepository.class), wl, mock(SoldTradeRepository.class),
            mock(AccountSnapshotRepository.class), mock(TransferRepository.class),
                mock(MarketDataSource.class));

    String content = "代码\t名称\t细分行业\t一二级行业\t长期形态\t中期形态\t短期形态\t近日指标提示\n"
            + "000725\t京东方Ａ\t元器件\t信息产业-元器件\t6\t8\t1\tKDJ死叉\n"
            + "601066\t中信建投\t证券\t金融-证券\t2\t10\t1\tKDJ死叉\n";
    var r = service.watchlistImport("default", content);

    assertEquals(2, r.imported());
    ArgumentCaptor<java.util.List<WatchlistItem>> cap = ArgumentCaptor.forClass(java.util.List.class);
    verify(wl).saveAll(eq("default"), cap.capture());
    assertEquals(2, cap.getValue().size());
    assertEquals("000725", cap.getValue().get(0).symbol());
    assertEquals("KDJ死叉", cap.getValue().get(0).signal());
    assertEquals(6, cap.getValue().get(0).longForm());
}

@org.junit.jupiter.api.Test
void soldImport_preservesExistingPsychology() {
    PositionRepository repo = mock(PositionRepository.class);
    SoldTradeRepository sold = mock(SoldTradeRepository.class);
    when(sold.findAll(any())).thenReturn(new java.util.ArrayList<>(java.util.List.of(
            new SoldTrade("600206", "有研新材", null, null, 3, "1+1", -12.82, "", "追高后恐慌割肉"))));
    TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
            mock(TradingHistoryRepository.class), mock(WatchlistRepository.class), sold,
            mock(AccountSnapshotRepository.class), mock(TransferRepository.class),
                mock(MarketDataSource.class));

    String content = "代码\t名称\t介入日期\t清仓日期\t持仓天数\t买卖次数\t持仓期涨幅%\n"
            + "600206\t有研新材\t20260731\t20260803\t3\t1+1\t-12.82\n";
    service.soldImport("default", content);

    ArgumentCaptor<java.util.List<SoldTrade>> cap = ArgumentCaptor.forClass(java.util.List.class);
    verify(sold).saveAll(eq("default"), cap.capture());
    assertEquals("追高后恐慌割肉", cap.getValue().get(0).psychology(),
            "重新导入应保留已有心理标注");
    assertEquals("2026-07-31", cap.getValue().get(0).buyDate().toString());
}

@org.junit.jupiter.api.Test
void importCashQuery_updatesCashAndPreciseCost() {
    PositionRepository repo = mock(PositionRepository.class);
    when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>(java.util.List.of(
            new Position("000725", "京东方A", 5300, new BigDecimal("6.042"), new BigDecimal("6.042"),
                    LocalDateTime.now(), LocalDate.of(2026, 8, 16), null, null, null))));
    AccountSnapshotRepository accountSnapshotRepository = mock(AccountSnapshotRepository.class);
    TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
            mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
            mock(SoldTradeRepository.class), accountSnapshotRepository,
            mock(TransferRepository.class), mock(MarketDataSource.class));

    String content = "人民币: 余额:292.88  可用:292.88  可取:292.88  参考市值:110212.00  资产:110504.88  盈亏:15235.55\n"
            + "编号 证券代码 证券名称 证券数量 成本价 当前价 最新市值 浮动盈亏\n"
            + "1 000725 京东方Ａ 5300.00 6.0421 5.8100 30793.00 -1229.57\n";
    var r = service.importCashQuery("default", content);

    assertEquals(0, r.cash().compareTo(new BigDecimal("292.88")));
    assertEquals(0, r.assets().compareTo(new BigDecimal("110504.88")));
    assertEquals(1, r.updatedCost(), "精确成本（4 位）应更新 1 只");
    // S5（2026-08-17）现金单一真源：现金只落 account.json（AccountSnapshot），不再写 positions.md cashBalance
    ArgumentCaptor<AccountSnapshot> snap = ArgumentCaptor.forClass(AccountSnapshot.class);
    verify(accountSnapshotRepository).save(eq("default"), snap.capture());
    assertEquals(0, snap.getValue().cash().compareTo(new BigDecimal("292.88")));
    verify(repo, never()).saveCashBalance(any(), any()); // 不写 positions.md cashBalance
    // 成本更新为 6.0421
    ArgumentCaptor<java.util.List<Position>> cap = ArgumentCaptor.forClass(java.util.List.class);
    verify(repo).saveAll(eq("default"), cap.capture());
    assertEquals(0, cap.getValue().get(0).avgCost().compareTo(new BigDecimal("6.0421")));
}

@org.junit.jupiter.api.Test
void importCashQuery_parseFail_throwsAndNeverSavesZero() {
    // P1-交易5（2026-08-17）修复：首行不是券商格式 → 抛错，绝不落零覆盖
    PositionRepository repo = mock(PositionRepository.class);
    when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>());
    AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
    TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
            mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
            mock(SoldTradeRepository.class), acc,
            mock(TransferRepository.class), mock(MarketDataSource.class));

    String bad = "随便贴的一段文字，不是通达信资金股份查询导出\n没有余额没有资产";
    assertThrows(com.adaiadai.core.domain.trading.TradingException.class,
            () -> service.importCashQuery("default", bad),
            "格式无法识别必须抛错而不是静默落零");
    verify(acc, never()).save(any(), any());          // 不写 account.json
    verify(repo, never()).saveCashBalance(any(), any()); // 不写 cashBalance
}

@org.junit.jupiter.api.Test
void importCashQuery_emptyContent_throws() {
    PositionRepository repo = mock(PositionRepository.class);
    when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>());
    AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
    TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
            mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
            mock(SoldTradeRepository.class), acc,
            mock(TransferRepository.class), mock(MarketDataSource.class));

    assertThrows(com.adaiadai.core.domain.trading.TradingException.class,
            () -> service.importCashQuery("default", ""),
            "空内容必须抛错");
    verify(acc, never()).save(any(), any());
}

@org.junit.jupiter.api.Test
void soldUpdatePsychology_marksTrade() {
    PositionRepository repo = mock(PositionRepository.class);
    SoldTradeRepository sold = mock(SoldTradeRepository.class);
    when(sold.findAll(any())).thenReturn(new java.util.ArrayList<>(java.util.List.of(
            new SoldTrade("600206", "有研新材", null, null, 3, "1+1", -12.82, "", ""))));
    TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
            mock(TradingHistoryRepository.class), mock(WatchlistRepository.class), sold,
            mock(AccountSnapshotRepository.class), mock(TransferRepository.class),
                mock(MarketDataSource.class));

    boolean ok = service.soldUpdatePsychology("default", "600206", "套牢死扛");

    assertTrue(ok);
    ArgumentCaptor<java.util.List<SoldTrade>> cap = ArgumentCaptor.forClass(java.util.List.class);
    verify(sold).saveAll(eq("default"), cap.capture());
    assertEquals("套牢死扛", cap.getValue().get(0).psychology());
}

    // ── 银证转账（2026-08-16 净投入跟踪）──

    @org.junit.jupiter.api.Test
    void recordTransfer_updatesPrincipalAndCash() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>());
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("110504.88"), new BigDecimal("292.88"),
                        new BigDecimal("292.88"), new BigDecimal("292.88"),
                        new BigDecimal("110212.00"), new BigDecimal("15235.55"),
                        BigDecimal.ZERO, new BigDecimal("150000"), LocalDate.of(2026, 8, 16))));
        TransferRepository transfers = mock(TransferRepository.class);
        TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), acc, transfers, mock(MarketDataSource.class));

        service.recordTransfer("default", "IN", new BigDecimal("10000"), LocalDate.of(2026, 8, 17), "补仓");

        ArgumentCaptor<AccountSnapshot> cap = ArgumentCaptor.forClass(AccountSnapshot.class);
        verify(acc).save(eq("default"), cap.capture());
        AccountSnapshot updated = cap.getValue();
        assertEquals(0, updated.principal().compareTo(new BigDecimal("160000")),
                "转入 1 万 → 净投入 16 万");
        assertEquals(0, updated.cash().compareTo(new BigDecimal("10292.88")),
                "现金 +1 万");
        assertEquals(0, updated.assets().compareTo(new BigDecimal("120504.88")),
                "资产 +1 万");
        verify(transfers).append(eq("default"), any());
    }

    @org.junit.jupiter.api.Test
    void recordTransfer_outDeductsPrincipal() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>());
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("110504.88"), new BigDecimal("292.88"),
                        new BigDecimal("292.88"), new BigDecimal("292.88"),
                        new BigDecimal("110212.00"), new BigDecimal("15235.55"),
                        BigDecimal.ZERO, new BigDecimal("150000"), LocalDate.of(2026, 8, 16))));
        TradingAppService service = new TradingAppService(repo, mock(RecordRepository.class),
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), acc, mock(TransferRepository.class),
                mock(MarketDataSource.class));

        service.recordTransfer("default", "OUT", new BigDecimal("50000"), LocalDate.of(2026, 8, 17), "提现");

        ArgumentCaptor<AccountSnapshot> cap = ArgumentCaptor.forClass(AccountSnapshot.class);
        verify(acc).save(eq("default"), cap.capture());
        assertEquals(0, cap.getValue().principal().compareTo(new BigDecimal("100000")),
                "转出 5 万 → 净投入 10 万");
        assertEquals(0, cap.getValue().cash().compareTo(new BigDecimal("-49707.12")),
                "现金 -5 万（负=透支，理论值）");
    }

    // ── 持仓元信息更新（web 编辑，2026-08-17 补端点）──

    @Test
    void updatePositionMeta_updatesStopLoss_keepsOtherFields() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll("default")).thenReturn(new java.util.ArrayList<>(List.of(
                new Position("600519", "贵州茅台", 100, new BigDecimal("1400"), new BigDecimal("1420"),
                        LocalDateTime.now(), LocalDate.of(2026, 8, 1),
                        new BigDecimal("1350"), "B1", "防守"))));
        TradingAppService service = service(repo, mock(RecordRepository.class));

        Position updated = service.updatePositionMeta("default", "600519", null, new BigDecimal("1302"));

        assertEquals(0, updated.stopLossPrice().compareTo(new BigDecimal("1302")), "止损应更新");
        assertEquals("B1", updated.buyPoint(), "买点保留");
        assertEquals("防守", updated.role(), "角色保留");
        ArgumentCaptor<List<Position>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(eq("default"), cap.capture());
        assertEquals("600519", cap.getValue().get(0).symbol());
    }

    @Test
    void updatePositionMeta_nullFields_keepExisting() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll("default")).thenReturn(new java.util.ArrayList<>(List.of(
                new Position("000725", "京东方A", 5300, new BigDecimal("6.0421"), new BigDecimal("5.81"),
                        LocalDateTime.now(), LocalDate.of(2026, 8, 16), null, null, null))));
        TradingAppService service = service(repo, mock(RecordRepository.class));

        Position updated = service.updatePositionMeta("default", "000725", "机动", null);

        assertNull(updated.stopLossPrice(), "不传止损 → 保持 null（未设）");
        assertEquals("机动", updated.role(), "角色更新");
    }

    @Test
    void updatePositionMeta_unknownSymbol_returnsNull() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll("default")).thenReturn(new java.util.ArrayList<>(List.of(
                new Position("600519", "贵州茅台", 100, new BigDecimal("1400"), new BigDecimal("1420"),
                        LocalDateTime.now()))));
        TradingAppService service = service(repo, mock(RecordRepository.class));

        assertNull(service.updatePositionMeta("default", "999999", null, new BigDecimal("10")),
                "不存在的 symbol → null");
        verify(repo, never()).saveAll(any(), any());
    }

    // ── P1-交易2（2026-08-17）：recordTrade 买卖同步市值，总资产只变手续费 ──

    @org.junit.jupiter.api.Test
    void recordTrade_buy_updatesMarketValue_assetsOnlyFees() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>(List.of(
                new Position("600519", "贵州茅台", 100, new BigDecimal("1400"), new BigDecimal("1420"),
                        LocalDateTime.now(), LocalDate.of(2026, 8, 1),
                        new BigDecimal("1350"), "B1", "防守"))));
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("150000"), new BigDecimal("10000"),
                        new BigDecimal("10000"), new BigDecimal("10000"),
                        new BigDecimal("140000"), new BigDecimal("2000"),
                        BigDecimal.ZERO, new BigDecimal("150000"), LocalDate.of(2026, 8, 16))));
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingAppService service = new TradingAppService(repo, records,
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), acc, mock(TransferRepository.class),
                mock(MarketDataSource.class));

        // 买入 100 股 @10：现金 -1000（无费简化），市值 +1000
        service.recordTrade("default", "600519", "贵州茅台", TradeDirection.BUY,
                new BigDecimal("10"), 100, LocalDate.of(2026, 8, 17), null,
                new BigDecimal("9.3"), "B1", null, null);

        ArgumentCaptor<AccountSnapshot> cap = ArgumentCaptor.forClass(AccountSnapshot.class);
        verify(acc).save(eq("default"), cap.capture());
        AccountSnapshot u = cap.getValue();
        assertEquals(0, u.cash().compareTo(new BigDecimal("8999.90")), "现金 10000 - 1000 - 手续费0.10");
        assertEquals(0, u.marketValue().compareTo(new BigDecimal("141000")), "市值 +1000（100股@10）");
        assertEquals(0, u.assets().compareTo(new BigDecimal("149999.90")), "总资产 = 现金+市值 = 150000 - 手续费0.10");
    }

    @org.junit.jupiter.api.Test
    void recordTrade_sell_updatesMarketValue_assetsOnlyFees() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>(List.of(
                new Position("600519", "贵州茅台", 200, new BigDecimal("1400"), new BigDecimal("1420"),
                        LocalDateTime.now(), LocalDate.of(2026, 8, 1),
                        new BigDecimal("1350"), "B1", "防守"))));
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("150000"), new BigDecimal("10000"),
                        new BigDecimal("10000"), new BigDecimal("10000"),
                        new BigDecimal("140000"), new BigDecimal("2000"),
                        BigDecimal.ZERO, new BigDecimal("150000"), LocalDate.of(2026, 8, 16))));
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingAppService service = new TradingAppService(repo, records,
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), acc, mock(TransferRepository.class),
                mock(MarketDataSource.class));

        // 卖出 100 股 @15：现金 +1500（无费简化），市值 -1500
        service.recordTrade("default", "600519", "贵州茅台", TradeDirection.SELL,
                new BigDecimal("15"), 100, LocalDate.of(2026, 8, 17), null,
                null, null, null, null);

        ArgumentCaptor<AccountSnapshot> cap = ArgumentCaptor.forClass(AccountSnapshot.class);
        verify(acc).save(eq("default"), cap.capture());
        AccountSnapshot u = cap.getValue();
        assertEquals(0, u.cash().compareTo(new BigDecimal("11499.10")), "现金 10000 + 1499.10（卖出回款扣费）");
        assertEquals(0, u.marketValue().compareTo(new BigDecimal("138500")), "市值 -1500（100股@15）");
        assertEquals(0, u.assets().compareTo(new BigDecimal("149999.10")), "总资产 = 现金+市值 = 150000 - 手续费0.90");
    }

    // ── 历史成交导入（第五份文件，2026-08-18）：补流水不重算持仓 + 幂等 + 对账 ──

    @org.junit.jupiter.api.Test
    void importHistoricalTrades_appendsFlow_only_noPositionMutation() {
        PositionRepository repo = mock(PositionRepository.class);
        // 当前持仓 = 券商快照（京东方 4800 股）
        when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>(List.of(
                new Position("000725", "京东方Ａ", 4800, new BigDecimal("6.2031"), new BigDecimal("6.47"),
                        LocalDateTime.now()))));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingAppService service = service(repo, mock(RecordRepository.class), history);

        String content = """
                成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
                20260803        14:52:56        600206          有研新材        卖出            -200.00         33.12000000         6624.00         151117          69351117                6620.05          A511358384
                20260817        10:02:51        000725          京东方Ａ        卖出            -1800.00        5.79000000         10422.00        67886           0102000024870368        10415.90         0903874313
                20260818        11:12:05        000725          京东方Ａ        买入            200.00          6.26000000         1252.00         123449          0101000048552566        -1252.11         0903874313
                20260818        00:00:00        000725          京东方Ａ        买入            0.00            0.00000000          10.08           0                                       -10.08           0903874313
                """;
        TradingAppService.HistoricalTradeImportResult r =
                service.importHistoricalTrades("default", content);

        // 3 笔真实成交落流水；1 行非交易（股息红利税）不落
        assertEquals(3, r.imported());
        assertEquals(0, r.skipped());
        assertEquals(1, r.nonTrades());
        ArgumentCaptor<TradeRecord> cap = ArgumentCaptor.forClass(TradeRecord.class);
        verify(history, times(3)).append(eq("default"), cap.capture());
        List<TradeRecord> appended = cap.getAllValues();
        assertEquals(TradeDirection.SELL, appended.get(0).direction());
        assertEquals("2026-08-03", appended.get(0).entryDate().toString());
        assertEquals("69351117", appended.get(0).orderId(), "成交编号落流水作幂等键");
        assertEquals(0, appended.get(0).fee().compareTo(new BigDecimal("3.95")), "fee = 券商实扣");
        // 持仓未被动过：verify 不调用 saveAll（只报告对账，不改数据）
        verify(repo, never()).saveAll(any(), any());
        // 对账：京东方 流水净 -1600（卖1800 买200）vs 快照 4800 → 基线缺口提示
        TradingAppService.ReconcileLine line = r.lines().stream()
                .filter(l -> l.symbol().equals("000725")).findFirst().orElseThrow();
        assertEquals(-1600, line.netVolume());
        assertEquals(4800, line.holdings());
        assertTrue(line.note().contains("窗口前基线"), "对账提示应指出基线缺口");
    }

    @org.junit.jupiter.api.Test
    void importHistoricalTrades_sameFileTwice_skipsByOrderId() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingAppService service = service(repo, mock(RecordRepository.class), history);

        String content = """
                成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
                20260803        14:53:51        002428          云南锗业        买入            400.00          68.14000000         27256.00        151747          0101000075800458        -27258.33        0903874313
                """;
        TradingAppService.HistoricalTradeImportResult first =
                service.importHistoricalTrades("default", content);
        assertEquals(1, first.imported());
        ArgumentCaptor<TradeRecord> cap = ArgumentCaptor.forClass(TradeRecord.class);
        verify(history, times(1)).append(eq("default"), cap.capture());
        // 第二次导入同一文件：已存在 orderId → 全部跳过（幂等）
        when(history.findAll(any())).thenReturn(cap.getAllValues());
        TradingAppService.HistoricalTradeImportResult second =
                service.importHistoricalTrades("default", content);
        assertEquals(0, second.imported());
        assertEquals(1, second.skipped());
        assertEquals(0, second.updated(), "旧记录已有成交时间 → 不回填");
    }

    @org.junit.jupiter.api.Test
    void importHistoricalTrades_reimportWithTime_backfillsMissingTradeTime() {
        // 2026-08-23：用户实测「全量重传 8 月不更新」——旧记录 tradeTime=null（首次导入无成交时间列），
        // 重传带成交时间的文件 → 回填不落新流水
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingAppService service = service(repo, mock(RecordRepository.class), history);

        // 首次导入：无「成交时间」列 → tradeTime=null
        String oldContent = """
                成交日期        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
                20260803        600206          有研新材        卖出            -200.00         33.12000000         6624.00         151117          69351117                6620.05          A511358384
                """;
        TradingAppService.HistoricalTradeImportResult first =
                service.importHistoricalTrades("default", oldContent);
        assertEquals(1, first.imported());
        ArgumentCaptor<TradeRecord> cap = ArgumentCaptor.forClass(TradeRecord.class);
        verify(history, times(1)).append(eq("default"), cap.capture());
        assertNull(cap.getValue().tradeTime(), "首次导入无成交时间列 → tradeTime=null");

        // 重传全量：同一 orderId 带成交时间 → 回填，不落新流水
        when(history.findAll(any())).thenReturn(cap.getAllValues());
        when(history.backfillTradeTime(eq("default"), eq(cap.getValue().id()),
                eq(java.time.LocalDate.of(2026, 8, 3)), eq(java.time.LocalTime.of(14, 52, 56))))
                .thenReturn(1);
        String newContent = """
                成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
                20260803        14:52:56        600206          有研新材        卖出            -200.00         33.12000000         6624.00         151117          69351117                6620.05          A511358384
                """;
        TradingAppService.HistoricalTradeImportResult second =
                service.importHistoricalTrades("default", newContent);

        assertEquals(0, second.imported(), "不落新流水");
        assertEquals(0, second.skipped(), "幂等命中但回填成功 → 不算跳过");
        assertEquals(1, second.updated(), "回填 1 笔");
        verify(history).backfillTradeTime(eq("default"), eq(cap.getValue().id()),
                eq(java.time.LocalDate.of(2026, 8, 3)), eq(java.time.LocalTime.of(14, 52, 56)));
        verify(history, times(1)).append(any(), any());
    }

    @org.junit.jupiter.api.Test
    void importHistoricalTrades_reimportSameFile_withTime_skipsNotBackfills() {
        // 旧记录已有成交时间（首次导入即带时间列）→ 重传全跳过（幂等），不回填
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(any())).thenReturn(new java.util.ArrayList<>());
        TradingAppService service = service(repo, mock(RecordRepository.class), history);

        String content = """
                成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格            成交金额        委托编号        成交编号                发生金额         股东代码
                20260803        14:52:56        600206          有研新材        卖出            -200.00         33.12000000         6624.00         151117          69351117                6620.05          A511358384
                """;
        TradingAppService.HistoricalTradeImportResult first =
                service.importHistoricalTrades("default", content);
        assertEquals(1, first.imported());
        ArgumentCaptor<TradeRecord> cap = ArgumentCaptor.forClass(TradeRecord.class);
        verify(history, times(1)).append(eq("default"), cap.capture());
        assertEquals(java.time.LocalTime.of(14, 52, 56), cap.getValue().tradeTime());

        when(history.findAll(any())).thenReturn(cap.getAllValues());
        TradingAppService.HistoricalTradeImportResult second =
                service.importHistoricalTrades("default", content);

        assertEquals(0, second.imported());
        assertEquals(1, second.skipped(), "已有时间 → 幂等跳过");
        assertEquals(0, second.updated());
        verify(history, never()).backfillTradeTime(any(), any(), any(), any());
    }

    // ── 本金设置（2026-08-18）：只改 principal，不动现金/资产/市值 ──

    @org.junit.jupiter.api.Test
    void setPrincipal_onlyChangesPrincipal_field() {
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(java.util.Optional.of(
                new AccountSnapshot(new BigDecimal("112566.91"), new BigDecimal("657.91"),
                        new BigDecimal("657.91"), new BigDecimal("657.91"),
                        new BigDecimal("111909.00"), new BigDecimal("18688.28"), BigDecimal.ZERO,
                        BigDecimal.ZERO, LocalDate.of(2026, 8, 18))));
        TradingAppService service = new TradingAppService(mock(PositionRepository.class),
                mock(RecordRepository.class), mock(TradingHistoryRepository.class),
                mock(WatchlistRepository.class), mock(SoldTradeRepository.class),
                acc, mock(TransferRepository.class), mock(MarketDataSource.class));

        AccountSnapshot updated = service.setPrincipal("default", new BigDecimal("150000"));

        assertEquals(0, updated.principal().compareTo(new BigDecimal("150000")), "本金 = 累计净投入");
        assertEquals(0, updated.cash().compareTo(new BigDecimal("657.91")), "现金不动");
        assertEquals(0, updated.assets().compareTo(new BigDecimal("112566.91")), "资产不动");
        assertEquals(0, updated.marketValue().compareTo(new BigDecimal("111909.00")), "市值不动");
        ArgumentCaptor<AccountSnapshot> cap = ArgumentCaptor.forClass(AccountSnapshot.class);
        verify(acc).save(eq("default"), cap.capture());
        assertEquals(0, cap.getValue().principal().compareTo(new BigDecimal("150000")));
    }

    @org.junit.jupiter.api.Test
    void setPrincipal_zeroOrNull_throws() {
        TradingAppService service = service(mock(PositionRepository.class), mock(RecordRepository.class));
        assertThrows(TradingException.class, () -> service.setPrincipal("default", null));
        assertThrows(TradingException.class, () -> service.setPrincipal("default", BigDecimal.ZERO));
        assertThrows(TradingException.class, () -> service.setPrincipal("default", new BigDecimal("-1")));
    }

    // ── 当日交易复盘聚合（RFC 20260822，纯客观数据）──

    @Test
    void getDailyTradeSummary_bucketsBySession() {
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        LocalDate day = LocalDate.of(2026, 8, 22);
        when(history.findAll(any())).thenReturn(List.of(
                // 早盘 2 笔（09:41 买 / 10:15 卖）
                TradeRecord.of("t1", "000725", "京东方A", TradeDirection.BUY,
                        new BigDecimal("5.2"), 1000, day, java.time.LocalTime.of(9, 41, 5),
                        null, null, null, null, null, day.atTime(9, 41), null, null),
                TradeRecord.of("t2", "000725", "京东方A", TradeDirection.SELL,
                        new BigDecimal("5.5"), 500, day, java.time.LocalTime.of(10, 15, 0),
                        null, null, null, null, null, day.atTime(10, 15), null, null),
                // 午盘 1 笔（13:20 买）
                TradeRecord.of("t3", "600519", "贵州茅台", TradeDirection.BUY,
                        new BigDecimal("1500"), 100, day, java.time.LocalTime.of(13, 20, 0),
                        null, null, null, null, null, day.atTime(13, 20), null, null),
                // 尾盘 1 笔（14:52 卖）
                TradeRecord.of("t4", "600206", "有研新材", TradeDirection.SELL,
                        new BigDecimal("33.12"), 200, day, java.time.LocalTime.of(14, 52, 56),
                        null, null, null, null, null, day.atTime(14, 52), null, null),
                // 无 tradeTime 的历史流水（旧数据）：计入 count/金额，不计时段
                TradeRecord.of("t5", "600000", "浦发银行", TradeDirection.BUY,
                        new BigDecimal("10.5"), 100, day, null,
                        null, null, null, null, null, day.atTime(8, 0), null, null)
        ));
        TradingAppService svc = service(mock(PositionRepository.class), mock(RecordRepository.class), history);

        TradingAppService.DailyTradeSummary s = svc.getDailyTradeSummary("default", day);

        assertEquals(5, s.count(), "含无时间旧流水共 5 笔");
        assertEquals(3, s.buyCount(), "3 买");
        assertEquals(2, s.sellCount(), "2 卖");
        assertEquals(3, s.sessions().size(), "三个时段桶");
        assertEquals(2, s.sessions().get(0).count(), "早盘 2 笔");
        assertEquals("早盘", s.sessions().get(0).name());
        assertEquals(1, s.sessions().get(1).count(), "午盘 1 笔");
        assertEquals("午盘", s.sessions().get(1).name());
        assertEquals(1, s.sessions().get(2).count(), "尾盘 1 笔");
        assertEquals("尾盘", s.sessions().get(2).name());
        assertEquals(java.time.LocalTime.of(9, 41, 5), s.firstTradeTime(), "首笔 09:41");
        assertEquals(java.time.LocalTime.of(14, 52, 56), s.lastTradeTime(), "末笔 14:52");
        // 金额：买入 5200 + 150000 + 1050（无时间旧流水也计金额）；卖出 2750 + 6624
        assertEquals(0, BigDecimal.valueOf(5200 + 150000 + 1050)
                .compareTo(BigDecimal.valueOf(s.buyAmount())), "买入金额含无时间流水");
        assertEquals(0, BigDecimal.valueOf(2750 + 6624)
                .compareTo(BigDecimal.valueOf(s.sellAmount())), "卖出金额 5.5×500 + 33.12×200");
    }

    @Test
    void getDailyTradeSummary_noTrades_returnsZero() {
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(any())).thenReturn(List.of());
        TradingAppService svc = service(mock(PositionRepository.class), mock(RecordRepository.class), history);

        TradingAppService.DailyTradeSummary s = svc.getDailyTradeSummary("default", LocalDate.of(2026, 8, 22));

        assertEquals(0, s.count());
        assertEquals(0, s.buyCount());
        assertEquals(0, s.sellCount());
        assertTrue(s.sessions().stream().allMatch(x -> x.count() == 0), "无成交所有时段 0");
        assertNull(s.firstTradeTime());
        assertNull(s.lastTradeTime());
    }
}

