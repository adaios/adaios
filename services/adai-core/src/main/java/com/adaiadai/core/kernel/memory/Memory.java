package com.adaiadai.core.kernel.memory;

import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Memory — 个人记忆条目。
 * <p>
 * 由 AI 理解结果经过筛选、提炼后的长期记忆。
 * 与 Record 的区别：Record 是原始事件，Memory 是经 AI 理解后的沉淀。
 * <p>
 * 采用 File First：存储为 {@code data/memory/YYYY/MM.md} 中的 Markdown 条目。
 *
 * @param id         记忆标识 {@code mem_yyyyMMdd_HHmmss}
 * @param recordId   来源记录的 ID
 * @param summary    AI 生成的摘要
 * @param tags       AI 推断的标签
 * @param sentiment  情感倾向
 * @param actionable 是否需要行动
 * @param suggestion 行动建议
 * @param createdAt  记忆创建时间
 */
public record Memory(
        String id,
        String recordId,
        String summary,
        List<String> tags,
        String sentiment,
        boolean actionable,
        String suggestion,
        LocalDateTime createdAt
) {

    /**
     * 从 AI 理解结果创建记忆。
     */
    public static Memory fromUnderstanding(String recordId, AiUnderstanding understanding) {
        return new Memory(
                generateId(),
                recordId,
                understanding.summary(),
                understanding.tags(),
                understanding.sentiment(),
                understanding.actionable(),
                understanding.actionSuggestion(),
                LocalDateTime.now()
        );
    }

    static String generateId() {
        return "mem_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(LocalDateTime.now());
    }
}
