package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnQuota;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

    // ── P2-审查5（2026-09-17 deep 审 + B2 批修复）：图片日配额的「检查 + 记账」必须原子 ──

    @Test
    void tryConsumeImages_concurrentRequests_neverOversell() throws Exception {
        // 旧实现是「imagesOn 读一次 → 稍后 consumeImages 写一次」，两次独立加锁 ——
        // 并发请求各自读到「还没超」→ 一起写盘 → 日配额超卖。本用例在真并发下钉住原子性。
        int limit = 5;
        int threads = 24;
        LocalDate day = LocalDate.of(2026, 9, 17);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        var done = new java.util.concurrent.CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (repository.tryConsumeImages("adai", day, 1, limit).accepted()) {
                            accepted.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 拒掉不算受理（宁可少受理，也不能超卖）
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "并发任务应在超时前跑完");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(limit, accepted.get(), "并发下**恰好**受理 limit 次，一次都不许多");
        assertEquals(limit, repository.imagesOn("adai", day), "账本记的当日用量也必须正好等于上限");
    }

    @Test
    void tryConsumeImages_overLimit_doesNotWriteAtAll() {
        repository.tryConsumeImages("adai", LocalDate.of(2026, 9, 17), 4, 5);

        var refused = repository.tryConsumeImages("adai", LocalDate.of(2026, 9, 17), 2, 5);

        assertFalse(refused.accepted(), "4 + 2 > 5 → 必须拒");
        assertEquals(4, refused.used(), "拒绝时把当时用量带回来做文案");
        assertEquals(4, repository.imagesOn("adai", LocalDate.of(2026, 9, 17)), "被拒的请求一个字节都不该写");
    }

    @Test
    void tryConsumeImages_underLimit_accumulates() {
        repository.tryConsumeImages("adai", LocalDate.of(2026, 9, 17), 2, 5);
        var second = repository.tryConsumeImages("adai", LocalDate.of(2026, 9, 17), 3, 5);

        assertTrue(second.accepted(), "2 + 3 = 5 正好到上限，应当受理");
        assertEquals(5, second.used());
        assertEquals(5, repository.imagesOn("adai", LocalDate.of(2026, 9, 17)));
    }

    @Test
    void tryConsumeImages_limitZero_meansUnlimited() {
        var result = repository.tryConsumeImages("adai", LocalDate.of(2026, 9, 17), 99, 0);

        assertTrue(result.accepted(), "limit<=0 = 不限（与 imageDailyLimit=0 的既有语义一致）");
        assertEquals(99, repository.imagesOn("adai", LocalDate.of(2026, 9, 17)));
    }
}
