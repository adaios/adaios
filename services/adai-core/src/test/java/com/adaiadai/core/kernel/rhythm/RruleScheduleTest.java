package com.adaiadai.core.kernel.rhythm;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RruleSchedule 单元测试（RFC 20260923 B 批）。
 * <p>
 * 覆盖：WEEKLY/BYDAY · 省略 BYDAY 的默认 · INTERVAL · DAILY · MONTHLY 小月跳过 · UNTIL 含当日 ·
 * 多 BYDAY · 非法规则一律抛人话（不静默忽略）。
 */
class RruleScheduleTest {

    /** 2026-09-24 是周四，作为各用例的周期起点（等价 iCalendar DTSTART）。 */
    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 24);

    @Test
    void weeklyByDay_hitsOnlyThatWeekday() {
        RruleSchedule s = RruleSchedule.parse("FREQ=WEEKLY;BYDAY=TH");
        assertTrue(s.matches(ANCHOR, ANCHOR));
        assertTrue(s.matches(ANCHOR.plusWeeks(1), ANCHOR));
        assertFalse(s.matches(ANCHOR.plusDays(1), ANCHOR), "周五不命中");
        assertFalse(s.matches(ANCHOR.minusDays(7), ANCHOR), "生效日之前不命中");
    }

    @Test
    void weeklyWithoutByDay_defaultsToAnchorWeekday() {
        RruleSchedule s = RruleSchedule.parse("FREQ=WEEKLY");
        assertTrue(s.matches(ANCHOR.plusWeeks(2), ANCHOR));
        assertFalse(s.matches(ANCHOR.plusDays(3), ANCHOR));
    }

    @Test
    void weeklyInterval_twoSkipsAlternateWeeks() {
        RruleSchedule s = RruleSchedule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH");
        assertTrue(s.matches(ANCHOR, ANCHOR));
        assertFalse(s.matches(ANCHOR.plusWeeks(1), ANCHOR), "隔周：下一周不命中");
        assertTrue(s.matches(ANCHOR.plusWeeks(2), ANCHOR));
    }

    @Test
    void daily_andInterval() {
        assertTrue(RruleSchedule.parse("FREQ=DAILY").matches(ANCHOR.plusDays(5), ANCHOR));
        RruleSchedule every3 = RruleSchedule.parse("FREQ=DAILY;INTERVAL=3");
        assertTrue(every3.matches(ANCHOR.plusDays(3), ANCHOR));
        assertFalse(every3.matches(ANCHOR.plusDays(4), ANCHOR));
    }

    @Test
    void monthlyByMonthDay_skipsMonthsWithoutThatDay() {
        RruleSchedule s = RruleSchedule.parse("FREQ=MONTHLY;BYMONTHDAY=31");
        LocalDate anchor = LocalDate.of(2026, 9, 30);
        assertTrue(s.matches(LocalDate.of(2026, 10, 31), anchor));
        assertFalse(s.matches(LocalDate.of(2026, 11, 30), anchor),
                "11 月没有 31 号 → 跳过，不塌到月末（与 iCalendar 一致）");
        assertFalse(s.matches(LocalDate.of(2026, 10, 30), anchor));
    }

    @Test
    void until_isInclusive() {
        RruleSchedule s = RruleSchedule.parse("FREQ=DAILY;UNTIL=20261001");
        assertTrue(s.matches(LocalDate.of(2026, 10, 1), ANCHOR));
        assertFalse(s.matches(LocalDate.of(2026, 10, 2), ANCHOR));
    }

    @Test
    void multipleByDay() {
        RruleSchedule s = RruleSchedule.parse("FREQ=WEEKLY;BYDAY=MO,TH");
        assertTrue(s.matches(ANCHOR, ANCHOR));
        assertTrue(s.matches(ANCHOR.plusDays(4), ANCHOR), "下周一");
        assertFalse(s.matches(ANCHOR.plusDays(1), ANCHOR), "周五不在列表里");
    }

    @Test
    void invalidRules_throwHumanReadable_neverSilentlyIgnored() {
        assertThrows(RhythmException.class, () -> RruleSchedule.parse(""));
        assertThrows(RhythmException.class, () -> RruleSchedule.parse("BYDAY=TH"), "缺 FREQ");
        assertThrows(RhythmException.class, () -> RruleSchedule.parse("FREQ=YEARLY"), "不支持的频率");
        assertThrows(RhythmException.class, () -> RruleSchedule.parse("FREQ=WEEKLY;BYDAY=XX"), "非法星期码");
        assertThrows(RhythmException.class, () -> RruleSchedule.parse("FREQ=WEEKLY;INTERVAL=0"), "INTERVAL 必须 ≥1");
        assertThrows(RhythmException.class, () -> RruleSchedule.parse("FREQ=WEEKLY;FOO=1"),
                "未知字段拒绝——静默忽略会造出「看起来对、其实不是你说的那个周期」");
        assertThrows(RhythmException.class, () -> RruleSchedule.parse("FREQ=MONTHLY;BYMONTHDAY=32"));
        assertThrows(RhythmException.class, () -> RruleSchedule.parse("FREQ=WEEKLY;UNTIL=9月30"));
    }

    @Test
    void explain_isHumanReadable() {
        assertEquals("每天", RruleSchedule.parse("FREQ=DAILY").explain());
        assertEquals("每周四", RruleSchedule.parse("FREQ=WEEKLY;BYDAY=TH").explain());
        assertEquals("每月 1 号", RruleSchedule.parse("FREQ=MONTHLY;BYMONTHDAY=1").explain());
    }
}
