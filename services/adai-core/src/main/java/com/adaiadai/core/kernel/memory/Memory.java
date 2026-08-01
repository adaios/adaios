package com.adaiadai.core.kernel.memory;

import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Memory — 个人记忆条目。
 * <p>
 * 由 AI 理解结果经过筛选、提炼后的长期记忆。
 * 与 Record 的区别：Record 是原始事件（含简短 summary 标记），Memory 是经 AI 理解后的洞察沉淀。
 * <p>
 * 包含逐步提炼的 pattern（行为模式）和 preference（用户偏好），随时间累积置信度。
 * <p>
 * 采用 File First：存储为 {@code data/memory/YYYY/MM.md} 中的 Markdown 条目。
 *
 * @param id          记忆标识 {@code mem_yyyyMMdd_HHmmss}
 * @param recordId    来源记录的 ID
 * @param summary     AI 洞察（insight），有信息增量的理解沉淀，非原文复述
 * @param patterns    行为模式列表（可选），从该记录中提炼的模式
 * @param preferences 用户偏好列表（可选），从该记录中提炼的偏好
 * @param tags        AI 推断的标签
 * @param sentiment   情感倾向
 * @param actionable  是否需要行动
 * @param suggestion  行动建议
 * @param createdAt   记忆创建时间
 */
public record Memory(
        String id,
        String recordId,
        String summary,
        List<MemoryPattern> patterns,
        List<MemoryPreference> preferences,
        List<String> tags,
        String sentiment,
        boolean actionable,
        String suggestion,
        LocalDateTime createdAt
) {

    /**
     * 从 AI 理解结果创建记忆。
     * <p>
     * summary 使用 understanding.insight()（有信息增量的洞察），
     * 如果 insight 为空（如 QUESTION 场景），回退到 understanding.summary()。
     * patterns/preferences 直接从 understanding 传递。
     */
    public static Memory fromUnderstanding(String recordId, AiUnderstanding understanding) {
        // insight 优先：有洞察用洞察，没有（QUESTION 场景）用 summary 兜底
        String memorySummary = understanding.insight() != null
                ? understanding.insight()
                : understanding.summary();
        return new Memory(
                generateId(),
                recordId,
                memorySummary,
                understanding.patterns(),
                understanding.preferences(),
                understanding.tags(),
                understanding.sentiment(),
                understanding.actionable(),
                understanding.actionSuggestion(),
                LocalDateTime.now()
        );
    }

    /**
     * 从记录内容构造降级记忆（AI 理解失败时的保底沉淀）。
     * <p>
     * 不经过 AI 提炼，仅把原文（截断）作为事实沉淀，标 {@code suggestion=DEGRADED} 区分于洞察记忆。
     * AI 恢复后由 RecordRetryService 重补，MemoryService.persist 的升级语义会用洞察覆盖降级条目。
     */
    public static Memory fromContentFallback(String recordId, String content) {
        String fallback = content == null ? "" : content.strip();
        if (fallback.length() > 100) {
            fallback = fallback.substring(0, 100) + "…";
        }
        return new Memory(
                generateId(), recordId, fallback,
                List.of(), List.of(), List.of(),
                "neutral", false, "DEGRADED", LocalDateTime.now()
        );
    }

    /**
     * 是否降级记忆（AI 失败时由原文保底沉淀）。
     */
    public static boolean isDegraded(Memory memory) {
        return memory != null && "DEGRADED".equals(memory.suggestion());
    }

    static String generateId() {
        return "mem_" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS")
                .format(LocalDateTime.now());
    }
}
