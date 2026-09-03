package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void saveVerifyNull_indexEntryStoresJsonNull_notZeroPlaceholder() {
        // P2-案例5（2026-09-03）：verify 缺失时清单摘要落 JSON null（真实 0% 与「数据不足」可区分），
        // 不再存 0.0 占位——后验回填调度器回填 + rebuildIndex 后自然落真实值
        CaseRecord record = new CaseRecord(
                CaseRecord.idOf("000725", LocalDate.of(2026, 8, 3)), "000725", "京东方A",
                LocalDate.of(2026, 8, 3), "B1", null, List.of(), LocalDateTime.of(2026, 8, 30, 10, 0),
                new CaseRecord.CaseWindow(60, 30),
                new CaseRecord.CaseFeatures(52.3, 0.62, 8.4, true, -0.31, true,
                        "close_above_ma20_below_ma60", 1.8, "near", false, 5, false),
                null, CaseRecord.CaseAiInsight.empty());
        repository.save("adai", record);

        String index = storage.read("adai", "trading/cases/_index.json");
        assertTrue(index.contains("\"plus5dReturnPct\" : null") || index.contains("\"plus5dReturnPct\": null"),
                "verify 缺失 → 清单摘要应存 JSON null，实际 index=" + index);
        assertFalse(index.contains("\"plus5dReturnPct\" : 0.0") || index.contains("\"plus5dReturnPct\": 0.0"),
                "不得再以 0.0 占位（与真实 0% 混淆）");
        // round-trip：案例文件本身 verify 为 null，不因 index 摘要变化受影响
        assertTrue(repository.findById("adai", record.id()).isPresent());
        assertTrue(repository.findById("adai", record.id()).get().verify() == null);
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

    @Test
    void save_indexWriteFailure_rollsBackCaseFile() {
        // 模拟 index 写入失败（index 路径不可写 → 用存储层抛错的替换实现）
        var failing = new com.adaiadai.core.infrastructure.storage.TradingCaseFileRepository(
                new FileStorage() {
                    private final FileStorage inner = storage;

                    @Override public void write(String userId, String path, String content) {
                        if (path.equals("trading/cases/_index.json")) {
                            throw new com.adaiadai.core.infrastructure.storage.StorageException("index 写失败模拟");
                        }
                        inner.write(userId, path, content);
                    }
                    @Override public String read(String userId, String path) { return inner.read(userId, path); }
                    @Override public java.util.List<String> listFiles(String userId, String dir) { return inner.listFiles(userId, dir); }
                    @Override public boolean exists(String userId, String path) { return inner.exists(userId, path); }
                    @Override public void writeBytes(String userId, String path, byte[] content) { inner.writeBytes(userId, path, content); }
                    @Override public byte[] readBytes(String userId, String path) { return inner.readBytes(userId, path); }
                    @Override public void delete(String userId, String path) { inner.delete(userId, path); }
                    @Override public void append(String userId, String path, String content) { inner.append(userId, path, content); }
                });

        CaseRecord record = sample("000725", LocalDate.of(2026, 8, 3), "B1");
        assertThrows(com.adaiadai.core.infrastructure.storage.StorageException.class,
                () -> failing.save("adai", record));
        // 回滚：案例文件不残留 → 重试不再 409 卡死
        assertFalse(storage.exists("adai", "trading/cases/" + record.id() + ".json"),
                "index 失败应回滚案例文件（防 409 卡死）");
    }
}
