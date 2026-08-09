package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.record.CardRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CardFileRepository — findTodayCards updatedAt 过滤测试（REVIEW updatedAt 时间基准）。
 * <p>
 * 卡片目录按 createdAt 组织，但对话可跨日续接（updatedAt 落到最后活跃日）。
 * findTodayCards 应返回"指定日期最后活跃"的卡片，而非"指定日期创建"的卡片。
 */
class CardFileRepositoryTest {

    private InMemoryFileStorage storage;
    private CardFileRepository repository;

    @BeforeEach
    void setUp() {
        storage = new InMemoryFileStorage();
        repository = new CardFileRepository(storage);
    }

    private CardRecord card(String id, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new CardRecord(id, "conversation", "active", List.of(), List.of(), null, createdAt, updatedAt);
    }

    @Test
    void findTodayCards_filtersByUpdatedAt_lastActiveDay() {
        // 卡片 A：8-07 创建、8-09 续接（跨日续接 → 归最后活跃日 8-09）
        repository.save("default", card("card_a",
                LocalDateTime.of(2026, 8, 7, 22, 0),
                LocalDateTime.of(2026, 8, 9, 1, 30)));
        // 卡片 B：8-09 创建并活跃
        repository.save("default", card("card_b",
                LocalDateTime.of(2026, 8, 9, 9, 0),
                LocalDateTime.of(2026, 8, 9, 10, 0)));
        // 卡片 C：8-08 创建，之后无续接（8-09 不活跃，应排除）
        repository.save("default", card("card_c",
                LocalDateTime.of(2026, 8, 8, 8, 0),
                LocalDateTime.of(2026, 8, 8, 9, 0)));

        List<CardRecord> today = repository.findTodayCards("default", LocalDate.of(2026, 8, 9));

        assertEquals(2, today.size(), "8-09 最后活跃：A（跨日续接）+ B，C 不活跃应排除");
        assertTrue(today.stream().anyMatch(c -> c.id().equals("card_a")));
        assertTrue(today.stream().anyMatch(c -> c.id().equals("card_b")));
        assertTrue(today.stream().noneMatch(c -> c.id().equals("card_c")));
    }

    @Test
    void findTodayCards_noCardActiveOnDate_returnsEmpty() {
        repository.save("default", card("card_d",
                LocalDateTime.of(2026, 8, 1, 10, 0),
                LocalDateTime.of(2026, 8, 1, 11, 0)));

        List<CardRecord> none = repository.findTodayCards("default", LocalDate.of(2026, 8, 9));

        assertTrue(none.isEmpty(), "无 8-09 活跃卡片时应返回空");
    }
}
