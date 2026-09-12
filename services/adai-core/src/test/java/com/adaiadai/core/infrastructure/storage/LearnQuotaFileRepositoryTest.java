package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnQuota;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LearnQuotaFileRepositoryTest — 转写配额记账（RFC 20260912 §3.8 费用可控条 4）。
 * <p>
 * 覆盖：空账期归零、同月累加、**月初自动重置**（换月即新键，不靠定时任务）、损坏账本降级为空、
 * 落盘路径与 per-user 隔离、金额精度（4 位）与剩余额度不为负。
 */
class LearnQuotaFileRepositoryTest {

    private static final int QUOTA = 36000;

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final LearnQuotaFileRepository repository = new LearnQuotaFileRepository(storage, QUOTA);

    @Test
    void view_emptyLedger_returnsZerosWithQuota() {
        LearnQuota quota = repository.view("adai", YearMonth.of(2026, 9));

        assertEquals("2026-09", quota.month());
        assertEquals(0, quota.usedSeconds());
        assertEquals(0d, quota.usedYuan());
        assertEquals(QUOTA, quota.quotaSeconds());
        assertEquals(QUOTA, quota.remainSeconds());
        assertFalse(quota.exceeded());
    }

    @Test
    void consume_accumulatesWithinSameMonth() {
        repository.consume("adai", YearMonth.of(2026, 9), 1710, 0.1368d);
        LearnQuota after = repository.consume("adai", YearMonth.of(2026, 9), 900, 0.072d);

        assertEquals(2610, after.usedSeconds());
        assertEquals(0.2088d, after.usedYuan(), 1e-9);
        assertEquals(QUOTA - 2610, after.remainSeconds());
    }

    @Test
    void consume_newMonth_resetsAutomatically() {
        repository.consume("adai", YearMonth.of(2026, 9), 30000, 2.4d);

        LearnQuota october = repository.view("adai", YearMonth.of(2026, 10));

        assertEquals(0, october.usedSeconds(), "月初自动重置：新月份键不存在即归零（无需定时任务）");
        assertEquals(0d, october.usedYuan());
        // 上月账目仍在文件里（可追溯）
        assertEquals(30000, repository.view("adai", YearMonth.of(2026, 9)).usedSeconds());
    }

    @Test
    void ledger_persistsUnderLearnDir() {
        repository.consume("adai", YearMonth.of(2026, 9), 60, 0.0048d);

        String content = storage.read("adai", "learn/_quota.json");
        assertTrue(content != null && content.contains("2026-09"), "账本应落 data/{userId}/learn/_quota.json");
        assertTrue(content.contains("usedSeconds"));
        assertTrue(content.contains("usedYuan"));
    }

    @Test
    void ledger_isPerUser() {
        repository.consume("adai", YearMonth.of(2026, 9), 600, 0.048d);

        assertEquals(0, repository.view("bob", YearMonth.of(2026, 9)).usedSeconds());
        assertEquals(600, repository.view("adai", YearMonth.of(2026, 9)).usedSeconds());
    }

    @Test
    void corruptLedger_failsClosed_doesNotSilentlyResetQuota() {
        // 对抗审查 P2-1：原先「解析失败 → 按空账本处理」= 一次损坏就把额度闸门永久打开
        // （额度归零 → 可无限转写）。改为 fail-closed：拒绝操作 + 告警，等人工修文件。
        storage.write("adai", "learn/_quota.json", "{ 这不是 JSON");

        assertThrows(StorageException.class, () -> repository.view("adai", YearMonth.of(2026, 9)));
        assertThrows(StorageException.class,
                () -> repository.consume("adai", YearMonth.of(2026, 9), 120, 0.0096d));
    }

    @Test
    void missingLedger_isStillTreatedAsFreshUser() {
        // 与「损坏」区分：文件不存在 = 全新用户（正常路径，不能因为 fail-closed 就把新用户也挡住）
        assertEquals(0, repository.view("adai", YearMonth.of(2026, 9)).usedSeconds());
    }

    @Test
    void consume_negativeDelta_refundsReservation() {
        repository.consume("adai", YearMonth.of(2026, 9), 1800, 0.144d);

        LearnQuota after = repository.consume("adai", YearMonth.of(2026, 9), -1800, -0.144d);

        assertEquals(0, after.usedSeconds(), "转写失败要能把预留退回（否则用户为失败买单）");
        assertEquals(0d, after.usedYuan(), 1e-9);
    }

    @Test
    void quota_remainSecondsNeverNegative_andExceededFlag() {
        LearnQuota quota = repository.consume("adai", YearMonth.of(2026, 9), QUOTA + 500, 0.5d);

        assertEquals(0, quota.remainSeconds(), "剩余额度不为负");
        assertTrue(quota.exceeded(), "超配额应可判定（硬闸依据）");
    }

    @Test
    void consume_negativeOrZero_countsAsZero() {
        LearnQuota quota = repository.consume("adai", YearMonth.of(2026, 9), -100, -1d);

        assertEquals(0, quota.usedSeconds());
        assertEquals(0d, quota.usedYuan());
    }

    @Test
    void consume_roundsYuanTo4Decimals() {
        LearnQuota quota = repository.consume("adai", YearMonth.of(2026, 9), 1, 0.00008000001d);

        assertEquals(0.0001d, quota.usedYuan(), 1e-9);
    }
}
