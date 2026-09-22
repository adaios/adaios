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
 * @param intent     "question" | "log" | null
 * @param summary    AI 生成的摘要
 * @param domain     所属领域（life / trading / project）
 * @param mediaIds   媒体附件引用（一次投递的多张图 → 同一个记录的多个附件；RFC
 *                   {@code 20260815-media-event-unification}）。空/缺省 = 无附件；
 *                   解析端永远拿到非 null 列表（紧凑构造器归一），旧文件缺该字段即空。
 */
public record ContentRecord(
        String id,
        String type,
        String source,
        String title,
        String content,
        List<String> tags,
        LocalDateTime createdAt,
        String intent,   // "question" | "log" | null
        String summary,  // AI-generated summary
        String domain,   // "life" | "trading" | "project"
        List<String> mediaIds  // 媒体附件 id（图文一体：一次输入 = 一条记录）；永远非 null
) {
    /** 紧凑构造器：mediaIds 归一为非 null 列表（旧文件/旧调用缺省 → 空列表）。 */
    public ContentRecord {
        if (mediaIds == null) {
            mediaIds = List.of();
        }
    }

    public ContentRecord(String id, String type, String source, String title,
                         String content, List<String> tags, LocalDateTime createdAt) {
        this(id, type, source, title, content, tags, createdAt, null, null, "life", List.of());
    }

    /** 兼容旧签名（无 mediaIds）——media-event-unification 之前的所有调用点无需改动。 */
    public ContentRecord(String id, String type, String source, String title,
                         String content, List<String> tags, LocalDateTime createdAt,
                         String intent, String summary, String domain) {
        this(id, type, source, title, content, tags, createdAt, intent, summary, domain, List.of());
    }

    /** 是否带媒体附件（图文一体记录）。 */
    public boolean hasMedia() {
        return !mediaIds.isEmpty();
    }

    /**
     * 从文件系统路径推断记录所属的年份和月份（用于目录组织）。
     */
    public String yearMonth() {
        return createdAt != null
                ? "%04d/%02d".formatted(createdAt.getYear(), createdAt.getMonthValue())
                : "unknown";
    }
}
