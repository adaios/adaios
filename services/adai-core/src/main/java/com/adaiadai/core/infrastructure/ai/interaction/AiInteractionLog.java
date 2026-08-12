package com.adaiadai.core.infrastructure.ai.interaction;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AiInteractionLog — 一次 AI 交互的完整记录（R1 AI 交互日志）。
 * <p>
 * 目标是回答"提示词怎么组装的"：什么场景（scene）、Context 拼了哪些信息（prompt 全文 + 预估 tokens）、
 * 关联哪条记录/卡片（recordId/cardId）、模型返回什么（响应摘要 + 耗时 + 状态）。
 * <p>
 * File First 落盘为 JSONL（每行一条），路径 {@code data/{userId}/ai-logs/YYYY/MM/ai-log-YYYY-MM-DD.jsonl}。
 *
 * @param traceId         调用 ID（UUID，一次 AI 调用唯一）
 * @param ts              调用结束时间（ISO-8601）
 * @param durationMs      耗时（毫秒）
 * @param userId          用户 ID
 * @param kind            调用类型：understand / generate / recognizeIntent / visual.understand / visual.ask
 * @param scene           场景（trading / project / life / note / question / brief / conversation 等）
 * @param recordId        关联记录 ID（调用点通过 {@link AiTraceContext} 挂载，可为 null）
 * @param cardId          关联卡片 ID（可为 null）
 * @param source          调用来源（question / log / retry / brief / trading_review / conversation / media / intent 等）
 * @param model           模型族标识：deepseek / glm
 * @param prompt          发给模型的完整 prompt 全文（understand/generate 为 ContextPackage.prompt()，intent 为用户原文）
 * @param estimatedTokens 预估输入 tokens（ContextPackage.estimateTokens()）
 * @param status          调用结果：ok / error
 * @param error           错误信息（status=error 时）
 * @param responseLength  响应文本长度（字符）
 * @param responseSummary 响应摘要（截断，便于日志页预览；understand 为 summary+tags，generate/ask 为正文截断）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiInteractionLog(
        String traceId,
        String ts,
        Long durationMs,
        String userId,
        String kind,
        String scene,
        String recordId,
        String cardId,
        String source,
        String model,
        String prompt,
        Integer estimatedTokens,
        String status,
        String error,
        Integer responseLength,
        String responseSummary
) {
}
