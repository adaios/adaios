package com.adaiadai.core.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarketSnapshotRepository — 异动去重快照测试（Phase 2 主动推送）。
 * 验证签名读写 + 跨日自动重置。
 */
class MarketSnapshotRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final MarketSnapshotRepository repo = new MarketSnapshotRepository(storage);

    @Test
    void alertedSignatures_empty_whenNoFile() {
        assertTrue(repo.alertedSignatures("default", LocalDate.of(2026, 8, 6)).isEmpty());
    }

    @Test
    void saveSignatures_alerted_roundtrip() {
        LocalDate date = LocalDate.of(2026, 8, 6);
        Set<String> sigs = Set.of("600519:2026-08-06:loss", "600123:2026-08-06:gain");
        repo.saveSignatures("default", date, sigs);

        assertEquals(sigs, repo.alertedSignatures("default", date));
    }

    @Test
    void alertedSignatures_reset_whenCrossDay() {
        LocalDate d1 = LocalDate.of(2026, 8, 5);
        LocalDate d2 = LocalDate.of(2026, 8, 6);
        repo.saveSignatures("default", d1, Set.of("600519:2026-08-05:loss"));

        // 跨日：快照日期与查询日期不一致 → 视为未推送（隔日可重新触发）
        assertTrue(repo.alertedSignatures("default", d2).isEmpty());
    }
}
