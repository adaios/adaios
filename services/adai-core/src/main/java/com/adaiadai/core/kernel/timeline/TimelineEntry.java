package com.adaiadai.core.kernel.timeline;

import java.time.LocalDateTime;
import java.util.List;

/**
 * TimelineEntry — 时间线条目。
 * <p>
 * Timeline 中的最小展示单元，由 Record 投影生成。
 *
 * @param id        记录 ID
 * @param type      记录类型
 * @param title     标题
 * @param tags      标签
 * @param dateTime  时间戳
 */
public record TimelineEntry(
        String id,
        String type,
        String title,
        List<String> tags,
        LocalDateTime dateTime
) {
}
