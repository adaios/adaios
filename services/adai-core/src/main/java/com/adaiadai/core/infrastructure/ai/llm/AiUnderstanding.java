package com.adaiadai.core.infrastructure.ai.llm;

import java.util.List;

/**
 * AiUnderstanding — AI 理解结果。
 * <p>
 * LLM 分析 ContextPackage 后的输出。包含：
 * <ul>
 *   <li>摘要 — 一句话概括发生了什么</li>
 *   <li>标签 — AI 推断的相关标签</li>
 *   <li>情感 — 情感倾向分析</li>
 *   <li>行动建议 — 是否需要后续操作</li>
 *   <li>原始回复 — LLM 的完整回复文本（用于调试和扩展）</li>
 * </ul>
 * <p>
 * 这是 Memory 层的输入：理解结果经筛选后沉淀为长期记忆。
 *
 * @param summary     一句话摘要
 * @param tags        AI 推断的标签列表
 * @param sentiment   情感倾向（positive / negative / neutral）
 * @param actionable  是否需要后续操作
 * @param actionSuggestion 行动建议（当 actionable 为 true 时）
 * @param rawResponse 原始 LLM 回复
 */
public record AiUnderstanding(
        String summary,
        List<String> tags,
        String sentiment,
        boolean actionable,
        String actionSuggestion,
        String rawResponse
) {
}
