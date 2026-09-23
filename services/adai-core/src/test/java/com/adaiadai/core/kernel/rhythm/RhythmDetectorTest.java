package com.adaiadai.core.kernel.rhythm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RhythmDetector 单测（RFC 20260923 B 批）：自然语言 → RRULE 的推断。
 * <p>
 * 关键取舍：**推断不出就返回 null**（调用方回落待办），宁可漏判成待办，
 * 也不把一次性任务硬塞进节律。（{@code isRhythmLike} 的正反例由 BriefAppServiceTest 覆盖。）
 */
class RhythmDetectorTest {

    @Test
    void detectRrule_mapsExplicitRhythms() {
        assertEquals("FREQ=WEEKLY;BYDAY=TH", RhythmDetector.detectRrule("周四固定发版加班"),
                "生产实据原句");
        assertEquals("FREQ=WEEKLY;BYDAY=TH", RhythmDetector.detectRrule("每周四晚发版"));
        assertEquals("FREQ=DAILY", RhythmDetector.detectRrule("每天跑步半小时"));
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1", RhythmDetector.detectRrule("每月 1 号交房租"));
        assertEquals("FREQ=WEEKLY", RhythmDetector.detectRrule("每周复盘一次"),
                "没说星期几 → 以生效日那天为准（iCalendar 省略 BYDAY 的语义）");
        assertEquals("FREQ=WEEKLY;BYDAY=WE", RhythmDetector.detectRrule("周三固定例会"));
    }

    @Test
    void detectRrule_returnsNullForOneOffTasks_soTheyFallBackToTodo() {
        assertNull(RhythmDetector.detectRrule("周四要交周报"), "一次性任务必须留给待办链路");
        assertNull(RhythmDetector.detectRrule("给妈打个电话"));
        assertNull(RhythmDetector.detectRrule("准备周会材料"));
        assertNull(RhythmDetector.detectRrule(null));
    }

    @Test
    void detectRrule_returnsNullWhenRhythmLikeButPeriodUnclear() {
        assertNull(RhythmDetector.detectRrule("定期复盘"),
                "命中 isRhythmLike 但推不出周期 → null（不硬转，交给待办）");
        assertNull(RhythmDetector.detectRrule("每年体检"),
                "「每年」暂不在支持的三频率内 → null，不硬塞一个近似周期（注入侧仍会被挡住）");
    }
}
