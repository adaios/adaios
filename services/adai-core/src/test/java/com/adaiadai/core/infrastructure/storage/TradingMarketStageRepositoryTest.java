package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingMarketStage;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingMarketStageRepository — 活跃市值区间（用户手动判定）持久化测试（v3.41，2026-09-04）。
 * <p>
 * 覆盖：无文件 → null（回退 current.md）；写入/读取 round-trip；损坏/非法值 → null（降级不坏）；
 * 用户隔离；非法 stage 构造拒绝；写盘失败抛 StorageException（P0-1 fail-visible）。
 */
class TradingMarketStageRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradingMarketStageRepository repo = new TradingMarketStageRepository(storage);

    @Test
    void findByUser_noFile_returnsNull() {
        assertNull(repo.findByUser("alice"), "从未手动判定 → null（推送回退 current.md 推断）");
    }

    @Test
    void save_find_roundtrip() {
        TradingMarketStage bear = new TradingMarketStage("bear", "2026-09-04T09:00:00");
        repo.save("adai", bear);

        TradingMarketStage loaded = repo.findByUser("adai");
        assertNotNull(loaded);
        assertEquals("bear", loaded.stage());
        assertEquals("2026-09-04T09:00:00", loaded.updatedAt());

        // 覆盖写入：bear → bull
        repo.save("adai", new TradingMarketStage("bull", "2026-09-04T10:30:00"));
        TradingMarketStage reloaded = repo.findByUser("adai");
        assertEquals("bull", reloaded.stage());
        assertEquals("2026-09-04T10:30:00", reloaded.updatedAt());
    }

    @Test
    void userIsolation() {
        repo.save("adai", new TradingMarketStage("bear", "2026-09-04T09:00:00"));
        assertNull(repo.findByUser("alice"), "A 用户设置不影响 B 用户（未设置 → null）");
        assertNotNull(repo.findByUser("adai"));
    }

    @Test
    void corruptedFile_returnsNull() {
        storage.write("adai", "trading/market-stage.json", "{ stage: [损坏, 结构: ");
        assertNull(repo.findByUser("adai"), "损坏 JSON → null（回退规则推断，不阻断推送）");
    }

    @Test
    void invalidStageValue_returnsNull() {
        storage.write("adai", "trading/market-stage.json", "{\"stage\":\"neutral\",\"updatedAt\":\"2026-09-04T09:00:00\"}");
        assertNull(repo.findByUser("adai"), "非法区间值（非两档）→ null");
    }

    @Test
    void invalidStage_constructorRejects() {
        assertThrows(IllegalArgumentException.class,
                () -> new TradingMarketStage("neutral", "2026-09-04T09:00:00"),
                "非两档值构造必须拒绝（fail-closed，防脏数据落盘）");
        assertThrows(IllegalArgumentException.class,
                () -> new TradingMarketStage("bear", " "),
                "空 updatedAt 构造必须拒绝");
    }

    @Test
    void save_writeFailure_throws() {
        TradingMarketStageRepository failing = new TradingMarketStageRepository(new FileStorage() {
            @Override public void write(String userId, String path, String content) {
                throw new StorageException("磁盘写失败（测试）");
            }
            @Override public String read(String userId, String path) { return null; }
            @Override public java.util.List<String> listFiles(String userId, String dir) { return java.util.List.of(); }
            @Override public boolean exists(String userId, String path) { return false; }
            @Override public void writeBytes(String userId, String path, byte[] content) { }
            @Override public byte[] readBytes(String userId, String path) { return null; }
            @Override public void delete(String userId, String path) { }
            @Override public void append(String userId, String path, String content) { }
        });
        StorageException ex = assertThrows(StorageException.class,
                () -> failing.save("adai", new TradingMarketStage("bear", "2026-09-04T09:00:00")));
        assertTrue(ex.getMessage().contains("保存活跃市值区间失败"), "P0-1：写盘失败必须抛错，不静默 updated=true，实际: " + ex.getMessage());
    }
}
