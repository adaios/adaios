package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.RoundBoundary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoundBoundaryRepository 单元测试（2026-10-06 rounds 批）。
 * 用真 InMemoryFileStorage 验证 upsert / remove / 损坏降级 / 「最后操作生效」语义。
 */
class RoundBoundaryRepositoryTest {

    private static final String USER = "u";

    private RoundBoundaryRepository repo(InMemoryFileStorage storage) {
        return new RoundBoundaryRepository(storage);
    }

    private static RoundBoundary b(String symbol, String buyId, String mode, String note) {
        return new RoundBoundary(symbol, buyId, "2026-08-05", mode, note, "2026-10-06T10:00:00");
    }

    @Test
    void noFile_returnsEmpty() {
        assertTrue(repo(new InMemoryFileStorage()).findByUser(USER).isEmpty());
    }

    @Test
    void upsertThenFind_roundTrips() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        RoundBoundaryRepository r = repo(storage);
        r.upsert(USER, b("600206", "t_600206_2026-08-05_BUY", RoundBoundary.MODE_CUT, "这里开始第二段"));

        List<RoundBoundary> all = r.findByUser(USER);
        assertEquals(1, all.size());
        assertEquals("600206", all.get(0).symbol());
        assertEquals("t_600206_2026-08-05_BUY", all.get(0).anchorBuyId());
        assertEquals(RoundBoundary.MODE_CUT, all.get(0).mode());
        assertEquals("这里开始第二段", all.get(0).note());
        assertTrue(all.get(0).hasSplit(), "cut 携带切分动作");
        // File First：文件可读
        assertTrue(storage.read(USER, "trading/round-boundaries.json").contains("这里开始第二段"));
    }

    @Test
    void upsert_sameAnchor_replacesAndMovesToEnd() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        RoundBoundaryRepository r = repo(storage);
        r.upsert(USER, b("600206", "buy1", RoundBoundary.MODE_CUT, null));
        r.upsert(USER, b("600206", "buy2", RoundBoundary.MODE_MERGE, null));

        r.upsert(USER, b("600206", "buy1", null, "只备注"));   // 同 (symbol, anchorBuyId) = 覆盖 + 移到末尾

        List<RoundBoundary> all = r.findByUser(USER);
        assertEquals(2, all.size(), "同锚点覆盖不新增");
        assertEquals("buy2", all.get(0).anchorBuyId());
        assertEquals("buy1", all.get(1).anchorBuyId(), "最后操作的排到末尾（= 最后操作生效）");
        assertNull(all.get(1).mode(), "cut 被覆盖为「仅备注」");
        assertEquals("只备注", all.get(1).note());
        assertFalse(all.get(1).hasSplit());
    }

    @Test
    void remove_deletesOnlyThatAnchor_idempotent() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        RoundBoundaryRepository r = repo(storage);
        r.upsert(USER, b("600206", "buy1", RoundBoundary.MODE_CUT, null));
        r.upsert(USER, b("600206", "buy2", RoundBoundary.MODE_MERGE, null));

        r.remove(USER, "600206", "buy1");
        List<RoundBoundary> all = r.findByUser(USER);
        assertEquals(1, all.size());
        assertEquals("buy2", all.get(0).anchorBuyId());

        r.remove(USER, "600206", "buy1");   // 幂等：再删一次不炸
        assertEquals(1, r.findByUser(USER).size());
    }

    @Test
    void corruptedFile_backedUpBeforeDegrade() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write(USER, "trading/round-boundaries.json", "{not-json");
        assertTrue(repo(storage).findByUser(USER).isEmpty(), "损坏文件视为无人工边界（降级回纯自动口径）");
        assertEquals("{not-json", storage.read(USER, "trading/round-boundaries.json.bak-corrupt"),
                "P2-交易95：原文先备份再降级（防写侧全量重写把残余内容盖掉）");
    }

    @Test
    void corruptedFile_writeDoesNotLoseOriginal_canRecoverFromBak() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write(USER, "trading/round-boundaries.json", "{not-json");
        RoundBoundaryRepository r = repo(storage);
        r.upsert(USER, b("600206", "buy1", RoundBoundary.MODE_CUT, null));   // 写侧全量重写
        assertEquals(1, r.findByUser(USER).size(), "写侧照常工作（损坏后重建）");
        assertEquals("{not-json", storage.read(USER, "trading/round-boundaries.json.bak-corrupt"),
                "被覆盖的原文仍在 .bak-corrupt（可人工恢复）——P2-交易95");
    }

    @Test
    void missingAnchor_isSkipped() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write(USER, "trading/round-boundaries.json",
                "{\"boundaries\":[{\"symbol\":\"600206\"},"
                        + "{\"symbol\":\"600206\",\"anchorBuyId\":\"buy1\",\"mode\":\"cut\"}]}");
        List<RoundBoundary> all = repo(storage).findByUser(USER);
        assertEquals(1, all.size(), "缺 anchorBuyId 的行跳过");
        assertEquals("buy1", all.get(0).anchorBuyId());
    }
}
