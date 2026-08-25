package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarketPushRepository — 推送事件按日持久化测试（Phase 2 主动推送）。
 */
class MarketPushRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final MarketPushRepository repo = new MarketPushRepository(storage);

    @Test
    void append_findByDate_roundtrip() {
        LocalDate date = LocalDate.of(2026, 8, 6);
        repo.append("default", date, new MarketPushEvent("push_1", "600519", "贵州茅台", "跌了", "loss", "14:05", null, "2999-01-01T00:00:00"));
        repo.append("default", date, new MarketPushEvent("push_2", "600123", "立昂微", "涨了", "gain", "14:35", null, "2999-01-01T00:00:00"));

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
        repo.append("default", d1, new MarketPushEvent("push_1", "600519", "贵州茅台", "昨日", "loss", "10:00", null, "2999-01-01T00:00:00"));
        repo.append("default", d2, new MarketPushEvent("push_2", "600123", "立昂微", "今日", "gain", "14:00", null, "2999-01-01T00:00:00"));

        assertEquals(1, repo.findByDate("default", d1).size());
        assertEquals(1, repo.findByDate("default", d2).size());
        assertEquals("今日", repo.findByDate("default", d2).get(0).message());
    }

    // ── B5-5/B6-1（2026-08-23）：损坏文件拒绝写回，防覆盖历史推送 ──

    @Test
    void append_syntaxBroken_keepsOriginal() {
        // 语法损坏（非 JSON）→ 拒绝写回，原文件保留
        LocalDate date = LocalDate.of(2026, 8, 23);
        storage.write("default", "trading/pushes/2026-08-23.json", "{{{ 半写文件");
        repo.append("default", date, new MarketPushEvent("push_1", "600519", "贵州茅台", "新", "loss", "14:05", null, "2999-01-01T00:00:00"));
        assertEquals("{{{ 半写文件", storage.read("default", "trading/pushes/2026-08-23.json"),
                "语法损坏必须保留原文件，不得覆盖");
    }

    @Test
    void append_structBrokenArrayElement_notOverwritten() {
        // B6-1：`[123]` 是合法 JSON 但元素非对象 → 结构损坏 → 拒绝写回（原 B5-5 只验语法会漏）
        LocalDate date = LocalDate.of(2026, 8, 23);
        storage.write("default", "trading/pushes/2026-08-23.json", "[123]");
        repo.append("default", date, new MarketPushEvent("push_1", "600519", "贵州茅台", "新", "loss", "14:05", null, "2999-01-01T00:00:00"));
        assertEquals("[123]", storage.read("default", "trading/pushes/2026-08-23.json"),
                "元素非对象的数组必须判损坏保留原文件");
    }

    @Test
    void append_structBrokenMissingId_notOverwritten() {
        // B6-1：`{"a":1}` 对象但缺 id → 结构损坏 → 拒绝写回
        LocalDate date = LocalDate.of(2026, 8, 23);
        storage.write("default", "trading/pushes/2026-08-23.json", "[{\"a\":1}]");
        repo.append("default", date, new MarketPushEvent("push_1", "600519", "贵州茅台", "新", "loss", "14:05", null, "2999-01-01T00:00:00"));
        assertEquals("[{\"a\":1}]", storage.read("default", "trading/pushes/2026-08-23.json"),
                "缺 id 元素必须判损坏保留原文件");
    }

    @Test
    void append_validArray_appends() {
        // 合法数组 → 正常追加
        LocalDate date = LocalDate.of(2026, 8, 23);
        storage.write("default", "trading/pushes/2026-08-23.json",
                "[{\"id\":\"push_old\",\"symbol\":\"600519\",\"name\":\"贵州茅台\",\"message\":\"旧\",\"type\":\"loss\",\"time\":\"10:00\",\"expiresAt\":\"2999-01-01T00:00:00\"}]");
        repo.append("default", date, new MarketPushEvent("push_new", "600123", "立昂微", "新", "gain", "14:35", null, "2999-01-01T00:00:00"));
        List<MarketPushEvent> events = repo.findByDate("default", date);
        assertEquals(2, events.size());
        assertEquals("push_old", events.get(0).id());
        assertEquals("push_new", events.get(1).id());
    }

    // ── B9-1（2026-08-23，P1-推送1）：title 透传 + 旧文件兼容 ──

    @Test
    void append_title_roundtrip() {
        LocalDate date = LocalDate.of(2026, 8, 23);
        repo.append("default", date, new MarketPushEvent("push_1", "600519", "贵州茅台",
                "📋 今日操作汇总", "session", "15:15", "今日操作确认", "2999-01-01T00:00:00"));
        List<MarketPushEvent> events = repo.findByDate("default", date);
        assertEquals(1, events.size());
        assertEquals("今日操作确认", events.get(0).title(), "title 应落盘并读回");
    }

    @Test
    void dismiss_removesById() {
        // B10-1（2026-08-23，P1-推送2）：按 id 移除单条——刷新/重启不复活
        LocalDate date = LocalDate.of(2026, 8, 23);
        repo.append("default", date, new MarketPushEvent("push_1", "600519", "贵州茅台", "跌了", "loss", "14:05", null, "2999-01-01T00:00:00"));
        repo.append("default", date, new MarketPushEvent("push_2", "600123", "立昂微", "涨了", "gain", "14:35", null, "2999-01-01T00:00:00"));

        assertTrue(repo.dismiss("default", date, "push_1"), "命中返回 true");
        List<MarketPushEvent> events = repo.findByDate("default", date);
        assertEquals(1, events.size());
        assertEquals("push_2", events.get(0).id(), "只删目标条，其余保留");
    }

    @Test
    void dismiss_unknownId_returnsFalse() {
        LocalDate date = LocalDate.of(2026, 8, 23);
        repo.append("default", date, new MarketPushEvent("push_1", "600519", "贵州茅台", "跌了", "loss", "14:05", null, "2999-01-01T00:00:00"));
        assertFalse(repo.dismiss("default", date, "push_nope"), "未命中返回 false");
        assertEquals(1, repo.findByDate("default", date).size(), "未命中不得误删");
    }

    @Test
    void findByDate_oldFileWithoutTitle_returnsNullTitle() {
        // 旧文件（2026-08-23 前）无 title 字段 → 读回 null（FeedAppService 按 type 兜底）
        LocalDate date = LocalDate.of(2026, 8, 6);
        storage.write("default", "trading/pushes/2026-08-06.json",
                "[{\"id\":\"push_1\",\"symbol\":\"600519\",\"name\":\"贵州茅台\",\"message\":\"跌了\",\"type\":\"loss\",\"time\":\"14:05\"}]");
        List<MarketPushEvent> events = repo.findByDate("default", date);
        assertEquals(1, events.size());
        assertEquals(null, events.get(0).title(), "旧文件无 title → null");
    }
}
