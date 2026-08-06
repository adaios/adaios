package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarketPushRepository — 推送事件按日持久化测试（Phase 2 主动推送）。
 */
class MarketPushRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final MarketPushRepository repo = new MarketPushRepository(storage);

    @Test
    void append_findByDate_roundtrip() {
        LocalDate date = LocalDate.of(2026, 8, 6);
        repo.append("default", date, new MarketPushEvent("push_1", "600519", "贵州茅台", "跌了", "loss", "14:05"));
        repo.append("default", date, new MarketPushEvent("push_2", "600123", "立昂微", "涨了", "gain", "14:35"));

        List<MarketPushEvent> events = repo.findByDate("default", date);
        assertEquals(2, events.size());
        assertEquals("loss", events.get(0).type());
        assertEquals("600519", events.get(0).symbol());
        assertEquals("涨了", events.get(1).message());
        assertEquals("14:35", events.get(1).time());
    }

    @Test
    void findByDate_noFile_returnsEmpty() {
        assertTrue(repo.findByDate("default", LocalDate.of(2026, 8, 6)).isEmpty());
    }

    @Test
    void append_datesIsolated() {
        LocalDate d1 = LocalDate.of(2026, 8, 5);
        LocalDate d2 = LocalDate.of(2026, 8, 6);
        repo.append("default", d1, new MarketPushEvent("push_1", "600519", "贵州茅台", "昨日", "loss", "10:00"));
        repo.append("default", d2, new MarketPushEvent("push_2", "600123", "立昂微", "今日", "gain", "14:00"));

        assertEquals(1, repo.findByDate("default", d1).size());
        assertEquals(1, repo.findByDate("default", d2).size());
        assertEquals("今日", repo.findByDate("default", d2).get(0).message());
    }
}
