package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.rhythm.Rhythm;
import com.adaiadai.core.kernel.rhythm.RhythmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RhythmFileRepository 单测（RFC 20260923 B 批）：File First 落盘与读回。
 */
class RhythmFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private RhythmFileRepository repository;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new RhythmFileRepository(fileStorage);
    }

    private static Rhythm sample(String id, String title, String rrule, RhythmStatus status, LocalDate day) {
        return new Rhythm(id, title, rrule, status, "rec_src", day, null, day, day);
    }

    @Test
    void saveAndReadBack_roundTrip() {
        LocalDate day = LocalDate.of(2026, 9, 23);
        repository.save("default", sample("rhy_1", "周四固定发版加班", "FREQ=WEEKLY;BYDAY=TH", RhythmStatus.ACTIVE, day));

        List<Rhythm> all = repository.findAll("default");
        assertEquals(1, all.size());
        Rhythm r = all.get(0);
        assertEquals("rhy_1", r.id());
        assertEquals("周四固定发版加班", r.title());
        assertEquals("FREQ=WEEKLY;BYDAY=TH", r.recurrence(), "RRULE 里的 ; 和 = 必须原样往返");
        assertEquals(RhythmStatus.ACTIVE, r.status());
        assertEquals("rec_src", r.sourceRecordId());
        assertEquals(day, r.validFrom());
        assertEquals(day, r.createdAt());
        assertNull(r.validUntil());
    }

    @Test
    void save_sameId_updatesInPlace() {
        LocalDate day = LocalDate.of(2026, 9, 23);
        repository.save("default", sample("rhy_1", "每天跑步", "FREQ=DAILY", RhythmStatus.ACTIVE, day));
        repository.save("default", new Rhythm("rhy_1", "每天跑步", "FREQ=DAILY",
                RhythmStatus.PAUSED, null, day, day.plusMonths(1), day, day.plusDays(1)));

        List<Rhythm> all = repository.findAll("default");
        assertEquals(1, all.size(), "同 id 更新不应变成两条");
        assertEquals(RhythmStatus.PAUSED, all.get(0).status());
        assertEquals(day.plusMonths(1), all.get(0).validUntil());
    }

    @Test
    void findAll_byStatus_filters() {
        LocalDate day = LocalDate.of(2026, 9, 23);
        repository.save("default", sample("rhy_a", "每天跑步", "FREQ=DAILY", RhythmStatus.ACTIVE, day));
        repository.save("default", sample("rhy_b", "每月交房租", "FREQ=MONTHLY;BYMONTHDAY=1", RhythmStatus.PAUSED, day));

        assertEquals(1, repository.findAll(RhythmStatus.ACTIVE, "default").size());
        assertEquals(1, repository.findAll(RhythmStatus.PAUSED, "default").size());
        assertEquals(2, repository.findAll((RhythmStatus) null, "default").size());
    }

    @Test
    void delete_removesOnlyTarget() {
        LocalDate day = LocalDate.of(2026, 9, 23);
        repository.save("default", sample("rhy_a", "每天跑步", "FREQ=DAILY", RhythmStatus.ACTIVE, day));
        repository.save("default", sample("rhy_b", "每周四发版", "FREQ=WEEKLY;BYDAY=TH", RhythmStatus.ACTIVE, day));

        repository.delete("default", "rhy_a");

        List<Rhythm> all = repository.findAll("default");
        assertEquals(1, all.size());
        assertEquals("rhy_b", all.get(0).id());
    }

    @Test
    void unknownStatus_readsAsPaused_conservative() {
        fileStorage.write("default", "rhythm/2026/09.md", """
                # 节律 - 2026/09

                ---
                id: rhy_bad
                title: 手改坏了的
                recurrence: FREQ=WEEKLY
                status: WHATEVER
                validFrom: 2026-09-23
                createdAt: 2026-09-23
                updatedAt: 2026-09-23
                ---
                手改坏了的
                """);

        List<Rhythm> all = repository.findAll("default");
        assertEquals(1, all.size(), "缺 sourceRecordId / validUntil 两个可选行也应能解析");
        assertEquals(RhythmStatus.PAUSED, all.get(0).status(),
                "未知状态保守读作 PAUSED——宁可少注入，也不凭空多提醒");
    }

    @Test
    void findById_missing_isEmpty() {
        assertTrue(repository.findById("default", "rhy_nope").isEmpty());
    }
}
