package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DomainActivityService 单元测试。
 */
class DomainActivityServiceTest {

    private InMemoryFileStorage fileStorage;
    private RecordFileRepository recordRepository;
    private DomainActivityService domainActivityService;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        recordRepository = new RecordFileRepository(fileStorage);
        domainActivityService = new DomainActivityService(recordRepository);
    }

    @Test
    void getActivity_emptyRecords_returnsAllDomainsWithZero() {
        DomainActivityService.DomainBriefActivity activity = domainActivityService.getActivity();

        assertEquals(3, activity.domains().size());
        for (var item : activity.domains()) {
            assertEquals(0, item.todayCount());
            assertEquals(0, item.weekCount());
            assertEquals("inactive", item.trend());
        }
    }

    @Test
    void getActivity_withRecentRecord_showsCounts() {
        recordRepository.save(new ContentRecord(
                "rec_" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")),
                "note", "user_input", "test", "测试记录",
                List.of("test"), LocalDateTime.now(), null, null, "trading"
        ));

        DomainActivityService.DomainBriefActivity activity = domainActivityService.getActivity();

        var trading = activity.domains().stream()
                .filter(d -> d.domain().equals("trading"))
                .findFirst().orElseThrow();
        assertEquals(1, trading.todayCount());
        assertEquals(1, trading.weekCount());
    }

    @Test
    void getActivity_multipleDomains() {
        recordRepository.save(new ContentRecord(
                "rec_" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_1",
                "note", "user_input", "life", "记录了生活",
                List.of(), LocalDateTime.now(), null, null, "life"
        ));
        recordRepository.save(new ContentRecord(
                "rec_" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_2",
                "note", "user_input", "trading", "买了股票",
                List.of(), LocalDateTime.now(), null, null, "trading"
        ));

        DomainActivityService.DomainBriefActivity activity = domainActivityService.getActivity();

        assertTrue(activity.domains().stream().anyMatch(d -> d.domain().equals("life") && d.todayCount() >= 1));
        assertTrue(activity.domains().stream().anyMatch(d -> d.domain().equals("trading") && d.todayCount() >= 1));
    }

    @Test
    void getActivity_oldRecord_notCountedInWeek() {
        recordRepository.save(new ContentRecord(
                "rec_20250601_100000",
                "note", "user_input", "old", "旧记录",
                List.of(), LocalDateTime.of(2025, 6, 1, 10, 0), null, null, "life"
        ));

        DomainActivityService.DomainBriefActivity activity = domainActivityService.getActivity();

        var life = activity.domains().stream()
                .filter(d -> d.domain().equals("life"))
                .findFirst().orElseThrow();
        assertEquals(0, life.weekCount());
        assertEquals("inactive", life.trend());
    }
}
