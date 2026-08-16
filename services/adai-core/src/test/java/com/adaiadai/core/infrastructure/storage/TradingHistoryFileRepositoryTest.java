package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingHistoryFileRepository — 逐笔流水文件存储（RFC 20260816 §2.1）单元测试。
 * 覆盖：append/findAll/findByDate round-trip、按月分文件、同月追加累积、用户隔离、文件内容可读 JSON。
 */
class TradingHistoryFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private TradingHistoryFileRepository repository;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new TradingHistoryFileRepository(fileStorage);
    }

    private TradeRecord trade(String id, String symbol, String date, String price, int volume,
                              String stopLoss, String buyPoint) {
        LocalDate entryDate = LocalDate.parse(date);
        return TradeRecord.of(id, symbol, "名称" + symbol, TradeDirection.BUY,
                new BigDecimal(price), volume, entryDate,
                stopLoss != null ? new BigDecimal(stopLoss) : null, buyPoint,
                null, null, null, entryDate.atTime(9, 30), "rec_" + id);
    }

    @Test
    void appendAndFindAll_roundTrip() {
        repository.append("default", trade("trade_1", "600000", "2026-08-01", "10.5", 100, "9.5", "B1"));
        repository.append("default", trade("trade_2", "600000", "2026-08-10", "10.0", 100, null, null));

        List<TradeRecord> all = repository.findAll("default");

        assertEquals(2, all.size());
        // 按 timestamp 倒序：最新在前
        assertEquals("trade_2", all.get(0).id());
        assertEquals("trade_1", all.get(1).id());

        TradeRecord t = all.get(1);
        assertEquals("trade_1", t.id());
        assertEquals("600000", t.symbol());
        assertEquals(TradeDirection.BUY, t.direction());
        assertEquals(LocalDate.of(2026, 8, 1), t.entryDate());
        assertEquals(new BigDecimal("9.5"), t.stopLossPrice());
        assertEquals("B1", t.buyPoint());
        assertEquals(0, new BigDecimal("1050.0").compareTo(t.amount()), "amount=price×volume");
        assertEquals("rec_trade_1", t.sourceRecordId());
    }

    @Test
    void append_sameMonth_appendsToExistingArray() {
        repository.append("default", trade("trade_1", "600000", "2026-08-01", "10.5", 100, "9.5", "B1"));
        repository.append("default", trade("trade_2", "600000", "2026-08-02", "10.0", 100, null, null));

        // 同月追加不覆盖前一条
        List<TradeRecord> all = repository.findAll("default");
        assertEquals(2, all.size());
        assertTrue(fileStorage.exists("default", "trading/trades/2026-08.json"),
                "应落盘到 data/{userId}/trading/trades/2026-08.json");
    }

    @Test
    void append_differentMonths_separateFiles() {
        repository.append("default", trade("trade_1", "600000", "2026-07-15", "10.5", 100, "9.5", "B1"));
        repository.append("default", trade("trade_2", "600000", "2026-08-01", "10.0", 100, null, null));

        assertTrue(fileStorage.exists("default", "trading/trades/2026-07.json"), "7 月独立文件");
        assertTrue(fileStorage.exists("default", "trading/trades/2026-08.json"), "8 月独立文件");
        assertEquals(2, repository.findAll("default").size(), "跨月合并查询");
    }

    @Test
    void findByDate_filtersByEntryDate() {
        repository.append("default", trade("trade_1", "600000", "2026-08-01", "10.5", 100, "9.5", "B1"));
        repository.append("default", trade("trade_2", "600000", "2026-08-02", "10.0", 100, null, null));
        repository.append("default", trade("trade_3", "600519", "2026-08-02", "1400.0", 100, "1350.0", "B2"));

        List<TradeRecord> onDate = repository.findByDate("default", LocalDate.of(2026, 8, 2));

        assertEquals(2, onDate.size(), "同日多笔都返回");
        assertTrue(onDate.stream().allMatch(t -> t.entryDate().equals(LocalDate.of(2026, 8, 2))));
        assertEquals(0, repository.findByDate("default", LocalDate.of(2026, 8, 3)).size(), "无交易日返回空");
    }

    @Test
    void findAll_empty_returnsEmpty() {
        assertTrue(repository.findAll("default").isEmpty());
        assertTrue(repository.findByDate("default", LocalDate.of(2026, 8, 1)).isEmpty());
    }

    @Test
    void userIsolation() {
        repository.append("alice", trade("trade_1", "600000", "2026-08-01", "10.5", 100, "9.5", "B1"));

        assertTrue(repository.findAll("bob").isEmpty(), "不同用户流水互相隔离");
        assertEquals(1, repository.findAll("alice").size());
    }

    @Test
    void fileContent_isReadableJsonWithPlanFields() {
        // File First：JSON 文件内容含计划字段（止损/买点/入场），人类可读
        repository.append("default", trade("trade_1", "600000", "2026-08-01", "10.5", 100, "9.5", "B1"));

        String content = fileStorage.read("default", "trading/trades/2026-08.json");
        assertTrue(content != null && content.contains("stopLossPrice"), "文件应含止损字段");
        assertTrue(content.contains("buyPoint"), "文件应含买点字段");
        assertTrue(content.contains("entryDate"), "文件应含入场日期字段");
        assertTrue(content.contains("2026-08-01"), "文件应含入场日期值");
        assertTrue(content.contains("9.5"), "文件应含止损值");
        assertTrue(content.contains("B1"), "文件应含买点值");
    }

    @Test
    void sellTrade_keepsNullStopLoss() {
        // SELL 流水止损/买点为 null → round-trip 保持 null（不写止损）
        TradeRecord sell = TradeRecord.of("trade_sell", "600000", "浦发银行", TradeDirection.SELL,
                new BigDecimal("10.5"), 100, LocalDate.of(2026, 8, 3),
                null, null, null, null, null, LocalDateTime.of(2026, 8, 3, 14, 0), null);
        repository.append("default", sell);

        TradeRecord loaded = repository.findAll("default").get(0);
        assertEquals(TradeDirection.SELL, loaded.direction());
        assertNull(loaded.stopLossPrice(), "SELL 止损保持 null");
        assertNull(loaded.buyPoint(), "SELL 买点保持 null");
    }
}
