package com.adaiadai.core.kernel.rhythm;

import com.adaiadai.core.kernel.IdGenerator;

import java.time.LocalDate;

/**
 * Rhythm — 节律（Kernel builtin 基础能力，RFC 20260923 B 批）。
 * <p>
 * <b>节律不是待办</b>：待办是一次性、有终点的动作（OPEN → DONE）；
 * 节律是周期性复现的习惯（每周四发版、每天跑步），**没有「完成」这个状态**
 * （用户原话：「这是我的工作周期习惯，不是待办」）。
 * <p>
 * 与 {@code Todo} 的三点不同：
 * <ol>
 *   <li>状态是 {@code active/paused/retired}，不是 {@code OPEN/DONE}；</li>
 *   <li>带 {@code recurrence}（RRULE，如 {@code FREQ=WEEKLY;BYDAY=TH}），
 *       由 {@link RruleSchedule} 判定「今天命中吗」；</li>
 *   <li>带有效期区间（{@code validFrom/validUntil}）——「我可能这周四不加班了」
 *       就是填 {@code validUntil} 或转 PAUSED，<b>不删除条目</b>（历史保留）。</li>
 * </ol>
 * <p>
 * 采用 File First：存储为 {@code data/{userId}/rhythm/YYYY/MM.md} 中的 Markdown 条目。
 *
 * @param id             节律标识 {@code rhy_yyyyMMdd_HHmmssSSS}
 * @param title          一句话（如「周四固定发版加班」）
 * @param recurrence     RRULE（{@link RruleSchedule} 解析；判定以 {@code validFrom} 为周期起点）
 * @param status         active / paused / retired（**无 DONE**）
 * @param sourceRecordId 源记录 ID（记录自动转节律时关联，手建为 null）
 * @param validFrom      生效日（同时是 RRULE 的 anchor，等价 iCalendar DTSTART）
 * @param validUntil     失效日（可空 = 至今有效；到期不删条目，只停止命中）
 * @param createdAt      创建日期
 * @param updatedAt      最后更新日期
 */
public record Rhythm(
        String id,
        String title,
        String recurrence,
        RhythmStatus status,
        String sourceRecordId,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate createdAt,
        LocalDate updatedAt
) {

    /** 便捷构造：新建（ACTIVE、无 validUntil）。 */
    public Rhythm(String id, String title, String recurrence, String sourceRecordId, LocalDate validFrom) {
        this(id, title, recurrence, RhythmStatus.ACTIVE, sourceRecordId, validFrom, null, validFrom, validFrom);
    }

    /**
     * 这一天是否命中本节点律。
     * <p>
     * 三关：状态必须是 ACTIVE · 必须落在有效期区间内 · RRULE 必须命中。
     * RRULE 脏数据（手改坏了文件）按<b>不命中</b>处理——周期条目不该因为格式问题炸掉简报。
     */
    public boolean occursOn(LocalDate date) {
        if (date == null || status != RhythmStatus.ACTIVE) return false;
        if (validFrom != null && date.isBefore(validFrom)) return false;
        if (validUntil != null && date.isAfter(validUntil)) return false;
        try {
            return RruleSchedule.parse(recurrence).matches(date, validFrom);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 生成节律 ID（单调时间戳，同毫秒不碰撞覆盖）。 */
    public static String generateId() {
        return IdGenerator.monotonic("rhy_");
    }
}
