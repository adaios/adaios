package com.adaiadai.core.domain.project;

import java.time.LocalDate;
import java.util.List;

/**
 * Task — 项目管理任务实体。
 * <p>
 * 采用 File First：存储为 {@code data/project/tasks/YYYY/MM.md} 中的 Markdown 条目。
 *
 * @param id          任务标识 {@code task_yyyyMMdd_HHmmss}
 * @param title       任务标题
 * @param description 任务描述
 * @param status      状态（TODO / DOING / DONE / CANCELLED）
 * @param priority    优先级（P0 / P1 / P2 / P3）
 * @param tags        标签列表
 * @param rfcRef      关联 RFC 引用，如 "20260725-layer6"
 * @param createdAt   创建日期
 * @param updatedAt   最后更新日期
 */
public record Task(
        String id,
        String title,
        String description,
        TaskStatus status,
        String priority,
        List<String> tags,
        String rfcRef,
        LocalDate createdAt,
        LocalDate updatedAt
) {

    /**
     * 生成任务 ID。
     */
    public static String generateId() {
        return "task_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS")
                .format(java.time.LocalDateTime.now());
    }
}
