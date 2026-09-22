package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingSyncState;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TradingSyncStateRepositoryTest — 「账同步 / 复盘已发」状态的落盘契约（RFC `20260922` B 批 B3）。
 *
 * <p>重点在**降级方向**：读损坏 → 当作「没同步、没发过」（最坏多发一条，不会把没同步的账当成同步过）；
 * 写失败 → 只告警不抛（推送辅助状态不该让用户的导入失败）。
 */
class TradingSyncStateRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradingSyncStateRepository repo = new TradingSyncStateRepository(storage);

    @Test
    void noFile_returnsEmptyState() {
        assertEquals(TradingSyncState.empty(), repo.find("adai"));
    }

    @Test
    void recordSync_thenFind_hasSyncDateAndTime() {
        LocalDate today = LocalDate.of(2026, 9, 22);
        repo.recordSync("adai", today, LocalDateTime.of(2026, 9, 22, 15, 12, 3));

        TradingSyncState state = repo.find("adai");
        assertTrue(state.syncedOn(today));
        assertFalse(state.reviewPushedOn(today));
        assertNull(state.dailyReviewDate());
    }

    @Test
    void markDailyReview_keepsSyncDate() {
        LocalDate today = LocalDate.of(2026, 9, 22);
        repo.recordSync("adai", today, LocalDateTime.of(2026, 9, 22, 15, 12, 3));
        repo.markDailyReview("adai", today);

        TradingSyncState state = repo.find("adai");
        assertTrue(state.syncedOn(today), "标记复盘不得把「今天同步过」抹掉");
        assertTrue(state.reviewPushedOn(today));
    }

    @Test
    void recordSync_afterReview_keepsReviewMark() {
        LocalDate today = LocalDate.of(2026, 9, 22);
        repo.markDailyReview("adai", today);
        repo.recordSync("adai", today, LocalDateTime.of(2026, 9, 22, 16, 0));

        assertTrue(repo.find("adai").reviewPushedOn(today), "补导不得让当天复盘重发");
    }

    @Test
    void perUserIsolation() {
        repo.recordSync("adai", LocalDate.of(2026, 9, 22), LocalDateTime.now());
        assertEquals(TradingSyncState.empty(), repo.find("alice"));
    }

    @Test
    void brokenJson_treatedAsEmpty_neverAsSynced() {
        storage.write("adai", "trading/sync-state.json", "{这不是 JSON");
        assertEquals(TradingSyncState.empty(), repo.find("adai"),
                "读损坏必须当作「没同步」（fail-closed：宁可说没看到，也不发错的复盘）");
    }

    @Test
    void writeFailure_doesNotThrow() {
        FileStorage broken = mock(FileStorage.class);
        doThrow(new RuntimeException("disk full")).when(broken).write(anyString(), anyString(), anyString());
        TradingSyncStateRepository failing = new TradingSyncStateRepository(broken);

        // 不抛：推送辅助状态写失败不该让用户的导入动作失败
        failing.recordSync("adai", LocalDate.now(), LocalDateTime.now());
        failing.markDailyReview("adai", LocalDate.now());
        verify(broken, org.mockito.Mockito.atLeastOnce()).write(anyString(), anyString(), anyString());
    }
}
