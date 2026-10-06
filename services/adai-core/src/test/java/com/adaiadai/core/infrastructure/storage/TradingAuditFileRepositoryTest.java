package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingAuditRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingAuditFileRepositoryTest — 修改日志文件实现（2026-10-06 修复批）。
 * 覆盖：append/appendAll round-trip · 空批 no-op · per-user 锁并发不丢条（P2-交易97）·
 * Clock 注入的月份落点（P2-交易99）。
 */
class TradingAuditFileRepositoryTest {

    private static final String USER = "u";

    /** 固定时钟：2026-01-15（系统时区——断言月份落点不随运行日漂移）。 */
    private static final Clock FIXED = Clock.fixed(
            LocalDateTime.of(2026, 1, 15, 9, 30).atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    private static TradingAuditFileRepository repo(InMemoryFileStorage storage, Clock clock) {
        return new TradingAuditFileRepository(storage, clock);
    }

    private static TradingAuditRepository.AuditEntry entry(String field, String before, String after) {
        return new TradingAuditRepository.AuditEntry(LocalDateTime.of(2026, 1, 15, 9, 30),
                "t_1", field, before, after, "纠错·就地改");
    }

    @Test
    void appendThenFind_roundTrips() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TradingAuditFileRepository r = repo(storage, FIXED);
        r.append(USER, entry("volume", "1000", "800"));

        List<TradingAuditRepository.AuditEntry> all = r.findAll(USER);
        assertEquals(1, all.size());
        assertEquals("t_1", all.get(0).recordId());
        assertEquals("volume", all.get(0).field());
        assertEquals("1000", all.get(0).before());
        assertEquals("800", all.get(0).after());
        assertEquals("纠错·就地改", all.get(0).source());
    }

    @Test
    void appendAll_landsBatchInOneWrite() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TradingAuditFileRepository r = repo(storage, FIXED);
        r.append(USER, entry("first", "0", "1"));
        r.appendAll(USER, List.of(entry("second", "1", "2"), entry("third", "2", "3")));

        List<TradingAuditRepository.AuditEntry> all = r.findAll(USER);
        assertEquals(3, all.size());
        assertEquals(List.of("first", "second", "third"),
                all.stream().map(TradingAuditRepository.AuditEntry::field).toList(), "批量按序落盘、一条不丢");
    }

    @Test
    void appendAll_empty_noop() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TradingAuditFileRepository r = repo(storage, FIXED);
        r.appendAll(USER, List.of());
        assertTrue(r.findAll(USER).isEmpty());
        assertNull(storage.read(USER, "trading/audit/2026-01.jsonl"), "空批不产生文件");
    }

    /** P2-交易97：append 是读改写——并发不加锁会丢条目（后写覆盖前写）。 */
    @Test
    void concurrentAppends_noLostEntry() throws Exception {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TradingAuditFileRepository r = repo(storage, FIXED);
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    r.append(USER, entry("f" + id + "-" + i, "a", "b"));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发任务完成");
        assertEquals(threads * perThread, r.findAll(USER).size(), "并发 append 一条不丢");
    }

    /** P2-交易99：月份落点跟随注入时钟（不再看真实 LocalDate.now()）。 */
    @Test
    void monthPath_followsInjectedClock() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TradingAuditFileRepository r = repo(storage, FIXED);
        r.append(USER, entry("cash", "1", "2"));
        assertNotNull(storage.read(USER, "trading/audit/2026-01.jsonl"), "落点 = 注入时钟的月份");
    }
}
