package com.adaiadai.core.kernel;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IdGenerator — 全局唯一 ID 生成器。
 * <p>
 * 单调递增时间戳，保证同一毫秒内生成的 ID 不碰撞。
 * 毫秒碰撞会覆盖文件（历史 P0 同款：同毫秒两次 createRecord → 第二个覆盖第一个 → 数据丢失）。
 * 统一 Memory / Record / Card / Task 的 ID 生成（P1-2 修复）。
 */
public final class IdGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");
    private static final AtomicLong LAST_TS = new AtomicLong(0);

    private IdGenerator() {}

    /**
     * 生成 {@code <prefix>yyyyMMdd_HHmmssSSS} 形式的单调唯一 ID。
     *
     * @param prefix 前缀（如 mem_ / rec_ / card_ / task_）
     */
    public static String monotonic(String prefix) {
        long now = System.currentTimeMillis();
        long ts = LAST_TS.accumulateAndGet(now, (prev, cur) -> Math.max(cur, prev + 1));
        return prefix + FMT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()));
    }
}
