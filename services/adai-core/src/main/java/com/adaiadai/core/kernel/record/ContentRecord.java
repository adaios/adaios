package com.adaiadai.core.kernel.record;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ContentRecord — 最小个人事件单元。
 * <p>
 * 用户输入的原始记录，是一切上层能力的事实基础。
 * 采用 File First：每个实例对应 {@code data/records/YYYY/MM/} 下的一个 Markdown 文件。
 *
 * @param id         唯一标识，格式 {@code rec_yyyyMMdd_HHmmss}
 * @param type       记录类型（trade / life / research / note）
 * @param source     来源（user_input / auto_collect / external_import）
 * @param title      简短标题（用于 Timeline 展示）
 * @param content    正文内容（Markdown 格式）
 * @param tags       标签列表
 * @param createdAt  记录产生时间
 */
public record ContentRecord(
        String id,
        String type,
        String source,
        String title,
        String content,
        List<String> tags,
        LocalDateTime createdAt
) {

    /**
     * 从文件系统路径推断记录所属的年份和月份（用于目录组织）。
     */
    public String yearMonth() {
        return createdAt != null
                ? "%04d/%02d".formatted(createdAt.getYear(), createdAt.getMonthValue())
                : "unknown";
    }
}
