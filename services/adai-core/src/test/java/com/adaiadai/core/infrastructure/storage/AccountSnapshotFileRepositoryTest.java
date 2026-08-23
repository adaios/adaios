package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * AccountSnapshotFileRepository — P0-2/P1-3（2026-08-23）回归：
 * update 原子读-改-写（并发不丢更新）+ 写失败返回 null（调用方可感知）。
 */
class AccountSnapshotFileRepositoryTest {

    private AccountSnapshot snap(BigDecimal cash) {
        return new AccountSnapshot(cash.add(new BigDecimal("140000")), cash, cash, cash,
                new BigDecimal("140000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("150000"), LocalDate.of(2026, 8, 16));
    }

    @Test
    void update_returnsNext_andPersists() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        AccountSnapshotFileRepository repo = new AccountSnapshotFileRepository(storage);

        AccountSnapshot first = repo.update("default", cur -> snap(new BigDecimal("10000")));
        assertEquals(0, first.cash().compareTo(new BigDecimal("10000")));

        // 第二次 update 基于已落盘快照做读-改-写
        AccountSnapshot second = repo.update("default", cur -> {
            assertTrue(cur.isPresent(), "第二次 update 应读到已落盘快照");
            BigDecimal cash = cur.get().cash().add(new BigDecimal("1000"));
            return snap(cash);
        });
        assertEquals(0, second.cash().compareTo(new BigDecimal("11000")));
        // 落盘可查
        assertEquals(0, repo.findLatest("default").orElseThrow().cash().compareTo(new BigDecimal("11000")));
    }

    @Test
    void update_fnReturnsNull_doesNotSave() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        AccountSnapshotFileRepository repo = new AccountSnapshotFileRepository(storage);

        assertNull(repo.update("default", cur -> null), "fn 返回 null 应跳过保存");
        assertTrue(repo.findLatest("default").isEmpty(), "无快照落盘");
    }

    @Test
    void update_writeFailure_throws() {
        // B6-4（2026-08-23，P1-交易11）：写失败抛 StorageException（不再静默返回 null）——
        // 调用方必须感知账目未落盘，不得按成功继续
        FileStorage storage = mock(FileStorage.class);
        doThrow(new RuntimeException("磁盘写失败")).when(storage).write(anyString(), anyString(), anyString());
        AccountSnapshotFileRepository repo = new AccountSnapshotFileRepository(storage);

        assertThrows(com.adaiadai.core.infrastructure.storage.StorageException.class,
                () -> repo.update("default", cur -> snap(new BigDecimal("10000"))),
                "写失败必须抛 StorageException 而非静默");
    }

    @Test
    void update_concurrentIncrements_noLostUpdate() throws Exception {
        // P0-2（2026-08-23）：per-user 锁内原子 RMW——20 并发各 +1 现金，最终 = 初始 + 20（无丢失）
        InMemoryFileStorage storage = new InMemoryFileStorage();
        AccountSnapshotFileRepository repo = new AccountSnapshotFileRepository(storage);
        repo.update("default", cur -> snap(new BigDecimal("0")));

        int threads = 20;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger(0);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    repo.update("default", cur -> {
                        BigDecimal cash = cur.map(AccountSnapshot::cash).orElse(BigDecimal.ZERO).add(BigDecimal.ONE);
                        return snap(cash);
                    });
                    done.incrementAndGet();
                } catch (Throwable t) {
                    error.set(t);
                }
            }).start();
        }
        ready.await();
        go.countDown();
        // 等待全部线程完成
        while (done.get() < threads) {
            Thread.sleep(10);
        }
        if (error.get() != null) throw new AssertionError(error.get());

        assertEquals(20, done.get());
        assertEquals(0, repo.findLatest("default").orElseThrow().cash()
                .compareTo(new BigDecimal("20")), "20 并发 +1 现金 → 最终 20（无丢失覆盖）");
    }
}
