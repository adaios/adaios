package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FeedAppService 分页方向回归测试。
 * <p>
 * 契约：page 0 = 最新条目（最多 size 条），page 1 = 更早，往前翻无重复无遗漏。
 */
class FeedAppServiceTest {

    private InMemoryFileStorage fileStorage;
    private RecordFileRepository recordRepository;
    private MemoryService memoryService;
    private CardFileRepository cardRepository;
    private FeedAppService feedAppService;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        recordRepository = new RecordFileRepository(fileStorage);
        recordRepository.setTagIndexService(tagIndexService);
        memoryService = new MemoryService(fileStorage);
        cardRepository = new CardFileRepository(fileStorage);
        feedAppService = new FeedAppService(recordRepository, memoryService, null, cardRepository);
    }

    private void saveRecord(String id, LocalDateTime createdAt, String content) {
        recordRepository.save(new ContentRecord(
                id, "note", "user_input", content, content,
                List.of(), createdAt, "log", null, "life"
        ));
    }

    /** 建 N 条当天记录，时间从 09:00 递增到 09:N。 */
    private void saveRecordsToday(int n) {
        for (int i = 0; i < n; i++) {
            LocalDateTime t = LocalDate.now().atTime(9, i % 60, i / 60);
            saveRecord("rec_t" + i, t, "record " + i);
        }
    }

    @Test
    void page0_returnsNewestEntries() {
        saveRecordsToday(5); // rec_t0..rec_t4, t4 最新
        var resp = feedAppService.getFeed(null, 0, 5);
        assertEquals(5, resp.totalToday());
        assertEquals(5, resp.entries().size());
        // 页内时间升序（最新在页末）：get(0) 是页内最老，get(4) 是页内最新
        assertEquals("rec_t0", resp.entries().get(0).id());
        assertEquals("record 0", resp.entries().get(0).content());
        assertEquals("rec_t4", resp.entries().get(4).id());
    }

    @Test
    void page0_fillsFullPageWhenMoreThanSize() {
        // 12 条记录，size=5：page 0 应返回最新 5 条，不是余数块
        saveRecordsToday(12);
        var resp = feedAppService.getFeed(null, 0, 5);
        assertEquals(12, resp.totalToday());
        assertEquals(5, resp.entries().size(), "page 0 应填满一整页（最新 5 条）");
        assertEquals("rec_t7", resp.entries().get(0).id(), "页内最老 = 最新 5 条中的第一条");
        assertEquals("rec_t11", resp.entries().get(4).id(), "页内最新 = 今天的最后一条");
    }

    @Test
    void pages_areContiguousAndNonOverlapping() {
        saveRecordsToday(12);
        var p0 = feedAppService.getFeed(null, 0, 5);
        var p1 = feedAppService.getFeed(null, 1, 5);
        var p2 = feedAppService.getFeed(null, 2, 5);

        assertEquals(5, p0.entries().size());
        assertEquals(5, p1.entries().size());
        assertEquals(2, p2.entries().size(), "最后一页是余数块（最旧的 2 条）");

        // 无重叠
        List<String> ids0 = p0.entries().stream().map(e -> e.id()).toList();
        List<String> ids1 = p1.entries().stream().map(e -> e.id()).toList();
        List<String> ids2 = p2.entries().stream().map(e -> e.id()).toList();
        for (String id : ids0) assertFalse(ids1.contains(id) || ids2.contains(id));
        for (String id : ids1) assertFalse(ids2.contains(id));

        // 覆盖全部 12 条
        List<String> all = new java.util.ArrayList<>();
        all.addAll(ids0); all.addAll(ids1); all.addAll(ids2);
        assertEquals(12, all.size());
    }

    @Test
    void pageBeyondEnd_returnsEmpty() {
        saveRecordsToday(3);
        var resp = feedAppService.getFeed(null, 5, 5);
        assertEquals(0, resp.entries().size());
    }

    @Test
    void emptyDay_returnsEmpty() {
        var resp = feedAppService.getFeed(null, 0, 5);
        assertEquals(0, resp.totalToday());
        assertEquals(0, resp.entries().size());
    }
}
