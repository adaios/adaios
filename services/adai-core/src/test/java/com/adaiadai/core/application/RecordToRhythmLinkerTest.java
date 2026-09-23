package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RhythmFileRepository;
import com.adaiadai.core.kernel.rhythm.Rhythm;
import com.adaiadai.core.kernel.rhythm.RhythmException;
import com.adaiadai.core.kernel.rhythm.RhythmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecordToRhythmLinker 单测（RFC 20260923 B 批 · D5 写入侧分流）。
 * <p>
 * 这条链路的职责是「**别把习惯做成待办**」：周期表述 → 节律；其余 → 交回待办链路。
 */
class RecordToRhythmLinkerTest {

    private RhythmFileRepository rhythmRepository;
    private RecordToRhythmLinker linker;

    @BeforeEach
    void setUp() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        rhythmRepository = new RhythmFileRepository(storage);
        linker = new RecordToRhythmLinker(rhythmRepository, new RhythmAppService(rhythmRepository));
    }

    @Test
    void rhythmLikeRecord_createsRhythm_withRrule() {
        String id = linker.link("default", "rec_1", "log", "周四固定发版加班", true);

        assertNotNull(id, "周期习惯应转成节律");
        List<Rhythm> all = rhythmRepository.findAll("default");
        assertEquals(1, all.size());
        assertEquals("FREQ=WEEKLY;BYDAY=TH", all.get(0).recurrence());
        assertEquals(RhythmStatus.ACTIVE, all.get(0).status());
        assertEquals("rec_1", all.get(0).sourceRecordId());
    }

    @Test
    void oneOffTask_isNotRhythm_fallsBackToTodo() {
        assertNull(linker.link("default", "rec_2", "log", "周四要交周报", true),
                "含「周四」但是一次性任务 → 必须留给待办");
        assertTrue(rhythmRepository.findAll("default").isEmpty());
    }

    @Test
    void unmappablePeriod_isNotRhythm() {
        assertNull(linker.link("default", "rec_3", "log", "定期复盘", true),
                "推不出周期 → null（宁可漏判成待办，也不硬转）");
        assertTrue(rhythmRepository.findAll("default").isEmpty());
    }

    @Test
    void nonActionable_orNonLog_isSkipped() {
        assertNull(linker.link("default", "rec_4", "log", "每周四发版", false));
        assertNull(linker.link("default", "rec_5", "question", "每周四发版", true));
        assertTrue(rhythmRepository.findAll("default").isEmpty());
    }

    @Test
    void sameRecord_isIdempotent() {
        assertNotNull(linker.link("default", "rec_6", "log", "每周四发版", true));
        assertNull(linker.link("default", "rec_6", "log", "每周四发版", true), "同源记录不重复建节律");
        assertEquals(1, rhythmRepository.findAll("default").size());
    }

    @Test
    void occursOn_respectsValidityWindow() {
        LocalDate day = LocalDate.of(2026, 9, 24); // 周四
        Rhythm active = new Rhythm("rhy_1", "周四发版", "FREQ=WEEKLY;BYDAY=TH",
                RhythmStatus.ACTIVE, null, day, null, day, day);
        assertTrue(active.occursOn(day));
        assertTrue(active.occursOn(day.plusWeeks(1)));
        assertFalse(active.occursOn(day.plusDays(1)), "非命中日");

        Rhythm expired = new Rhythm("rhy_2", "周四发版", "FREQ=WEEKLY;BYDAY=TH",
                RhythmStatus.ACTIVE, null, day, day.plusWeeks(1), day, day);
        assertFalse(expired.occursOn(day.plusWeeks(2)), "过了 validUntil 不再命中（条目仍在，不删）");

        Rhythm retired = new Rhythm("rhy_3", "周四发版", "FREQ=WEEKLY;BYDAY=TH",
                RhythmStatus.RETIRED, null, day, null, day, day);
        assertFalse(retired.occursOn(day), "退役不命中");
    }

    @Test
    void createRhythm_rejectsIllegalRrule_withHumanMessage() {
        RhythmAppService service = new RhythmAppService(rhythmRepository);
        assertThrows(RhythmException.class,
                () -> service.createRhythm("default", "每周四发版", "FREQ=YEARLY", null));
        assertTrue(rhythmRepository.findAll("default").isEmpty(), "非法周期不得落半个对象");
    }
}
