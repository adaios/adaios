package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.cases.CaseRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingCaseFileRepositoryTest — 案例 JSON 存储（2026-08-30 第四阶段环 2）。
 * <p>
 * 验证：save/findById round-trip（含嵌套 features/verify 序列化）、清单 upsert 不重复、
 * list 按 buyDate 倒序、delete、损坏文件降级空。
 */
class TradingCaseFileRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private TradingCaseFileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TradingCaseFileRepository(storage);
    }

    private CaseRecord sample(String symbol, LocalDate buyDate, String buyType) {
        return new CaseRecord(
                CaseRecord.idOf(symbol, buyDate), symbol, "京东方A", buyDate, buyType,
                "回踩 60 日线 + 地量", List.of("缩量回踩"), LocalDateTime.of(2026, 8, 30, 10, 0),
                new CaseRecord.CaseWindow(60, 30),
                new CaseRecord.CaseFeatures(52.3, 0.62, 8.4, true, -0.31, true,
                        "close_above_ma20_below_ma60", 1.8, "near", false, 5, false),
                new CaseRecord.CaseVerify(18.2, 24.5, -2.1, false),
                CaseRecord.CaseAiInsight.empty());
    }

    @Test
    void saveAndFindById_roundTrip_preservesNestedFields() {
        CaseRecord record = sample("000725", LocalDate.of(2026, 8, 3), "B1");
        repository.save("adai", record);

        Optional<CaseRecord> loaded = repository.findById("adai", record.id());
        assertTrue(loaded.isPresent());
        CaseRecord got = loaded.get();
        assertEquals(record.id(), got.id());
        assertEquals("000725", got.symbol());
        assertEquals(LocalDate.of(2026, 8, 3), got.buyDate());
        // 嵌套特征往返（含 @JsonProperty("+5dReturnPct") 特殊字段名）
        assertEquals(52.3, got.features().drawdownFromHighPct(), 0.001);
        assertEquals("near", got.features().yellowLineState());
        assertEquals(18.2, got.verify().plus5dReturnPct(), 0.001);
        assertEquals(24.5, got.verify().plus10dReturnPct(), 0.001);
        assertEquals(false, got.verify().stopLossHit());
        assertTrue(got.aiInsight() != null && !got.aiInsight().reviewed());
    }

    @Test
    void saveSameIdTwice_indexHasSingleEntry() {
        CaseRecord record = sample("000725", LocalDate.of(2026, 8, 3), "B1");
        repository.save("adai", record);
        repository.save("adai", record);  // upsert 覆盖
        assertEquals(1, repository.list("adai").size());
    }

    @Test
    void list_sortedByBuyDateDesc() {
        repository.save("adai", sample("000725", LocalDate.of(2026, 8, 3), "B1"));
        repository.save("adai", sample("600519", LocalDate.of(2026, 7, 20), "B2"));
        repository.save("adai", sample("300750", LocalDate.of(2026, 8, 10), "unknown"));

        List<CaseRecord> list = repository.list("adai");
        assertEquals(3, list.size());
        assertEquals("2026-08-10_300750", list.get(0).id());
        assertEquals("2026-08-03_000725", list.get(1).id());
        assertEquals("2026-07-20_600519", list.get(2).id());
    }

    @Test
    void userIsolation_sameIdDifferentUsers() {
        repository.save("adai", sample("000725", LocalDate.of(2026, 8, 3), "B1"));
        assertTrue(repository.findById("adai", "2026-08-03_000725").isPresent());
        assertFalse(repository.exists("bob", "2026-08-03_000725"), "bob 不应看到 adai 的案例");
    }

    @Test
    void delete_removesCaseAndIndexEntry() {
        CaseRecord record = sample("000725", LocalDate.of(2026, 8, 3), "B1");
        repository.save("adai", record);
        repository.delete("adai", record.id());
        assertFalse(repository.exists("adai", record.id()));
        assertEquals(0, repository.list("adai").size());
    }

    @Test
    void corruptedCaseFile_findByIdReturnsEmpty() {
        CaseRecord record = sample("000725", LocalDate.of(2026, 8, 3), "B1");
        repository.save("adai", record);
        storage.write("adai", "trading/cases/" + record.id() + ".json", "{not-json");
        assertFalse(repository.findById("adai", record.id()).isPresent(),
                "损坏案例文件 → 空（不抛错，调用方视为不存在）");
    }
}
