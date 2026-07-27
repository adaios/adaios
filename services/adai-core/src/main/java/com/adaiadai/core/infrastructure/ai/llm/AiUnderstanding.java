package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.kernel.memory.MemoryPattern;
import com.adaiadai.core.kernel.memory.MemoryPreference;

import java.util.List;

/**
 * AiUnderstanding — AI 理解结果。
 * <p>
 * LLM 分析 ContextPackage 后的输出。包含：
 * <ul>
 *   <li>summary — 简短标记（3-5 词），用于 Record 标题/卡片展示</li>
 *   <li>insight — 一句话洞察，有信息增量，用于 Memory 沉淀</li>
 *   <li>patterns — 行为模式（可选），AI 从记录中提炼的长期规律</li>
 *   <li>preferences — 用户偏好（可选），AI 从记录中提炼的偏好信息</li>
 *   <li>标签 — AI 推断的相关标签</li>
 *   <li>情感 — 情感倾向分析</li>
 *   <li>领域 — 记录所属 OS（life / trading / project）</li>
 *   <li>行动建议 — 是否需要后续操作</li>
 *   <li>原始回复 — LLM 的完整回复文本（用于调试和扩展）</li>
 * </ul>
 * <p>
 * 这是 Memory 层的输入：理解结果经筛选后沉淀为长期记忆。
 *
 * @param summary     简短标记（3-5 词），给 Record 卡片展示
 * @param insight     一句话洞察，有信息增量，给 Memory 沉淀（QUESTION 场景可为 null）
 * @param patterns    行为模式列表（可选，STATEMENT 场景 AI 可能提炼出模式）
 * @param preferences 用户偏好列表（可选，STATEMENT 场景 AI 可能提炼出偏好）
 * @param tags        AI 推断的标签列表
 * @param sentiment   情感倾向（positive / negative / neutral）
 * @param domain      记录所属领域（life / trading / project，默认 life）
 * @param actionable  是否需要后续操作
 * @param actionSuggestion 行动建议（当 actionable 为 true 时）
 * @param rawResponse 原始 LLM 回复（用于调试和扩展）
 */
public record AiUnderstanding(
        String summary,
        String insight,
        List<MemoryPattern> patterns,
        List<MemoryPreference> preferences,
        List<String> tags,
        String sentiment,
        String domain,
        boolean actionable,
        String actionSuggestion,
        String rawResponse
) {
}
