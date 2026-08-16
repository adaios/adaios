package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        return new TradingAppService(repo, records, history);
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
                        new BigDecimal("10.5"), 100, null, null, null, null, null));
    }

    @Test
    void recordTrade_sellOverHeld_throws() {
        // #147：卖出数量超过持仓 → 报错，防静默清仓失真
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        TradingAppService service = service(repo, mock(RecordRepository.class));

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 200, null, null, null, null, null));
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
                new BigDecimal("10.5"), 100, null, null, null, null, null);

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
                new BigDecimal("10.5"), 100, null, new BigDecimal("9.5"), "B1", null, null);

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
                new BigDecimal("10.5"), 100, null, new BigDecimal("9.5"), "B1", null, null);

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
                new BigDecimal("10.5"), 100, null, new BigDecimal("9.5"), "B1", null, null);

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
                new BigDecimal("10.5"), 100, null, new BigDecimal("9.5"), "B1", null, null);

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
                        new BigDecimal("10.5"), 100, null, null, null, null, null));

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
                new BigDecimal("10.5"), 100, null, new BigDecimal("9.5"), "B1", null, null);

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
                new BigDecimal("10.5"), 100, null, new BigDecimal("9.5"), "B1", null, null);

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
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16),
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
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16), null, null, null, null);

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
                new BigDecimal("10.5"), 100, null, new BigDecimal("9.5"), "B1", null, null);

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
                        new BigDecimal("10.5"), 100, null, null, null, null, null));

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
                new BigDecimal("10.5"), 100, null, new BigDecimal("9.5"), "B1", null, null);

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
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16),
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
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 10),
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
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16),
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
                new BigDecimal("10.5"), 100, null, null, null, null, null);

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
        TradingAppService service = new TradingAppService(repo, records, history);

        // 旧行（600000）无新列：BUY 加仓 → entryDate 以本次 BUY 补录，止损/买点更新
        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 16),
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
}
