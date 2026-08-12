package com.adaiadai.core.infrastructure.ai.interaction;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.ai.llm.DeepSeekAiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * LoggingAiClient — AiClient 装饰器：自动记录每次 AI 交互（R1 AI 交互日志）。
 * <p>
 * 包装 {@link DeepSeekAiClient}，对 understand / generate / recognizeIntent 打点，
 * 将入参（scene / prompt 全文 / 预估 tokens / 关联 recordId / cardId）与响应
 * （耗时 / 状态 / 长度 / 摘要）落盘到 {@link AiInteractionLogger}。
 * <p>
 * {@code @Primary}：注册为 AiClient 的首选 bean，全部 7 个调用方零改动自动获得日志能力
 * （接口注入点拿到的是本装饰器，而非 DeepSeekAiClient）。
 * <p>
 * 日志失败不影响业务：AI 调用结果正常返回，只有记录环节 best-effort。
 */
@Component
@Primary
public class LoggingAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingAiClient.class);

    private final DeepSeekAiClient delegate;
    private final AiInteractionLogger logger;

    public LoggingAiClient(DeepSeekAiClient delegate, AiInteractionLogger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    @Override
    public AiUnderstanding understand(ContextPackage ctx) {
        return around(() -> delegate.understand(ctx), (trace, status, dur, result, error) -> {
            AiUnderstanding r = result;
            return base(trace, "understand", "deepseek", ctx.scene(),
                    ctx.prompt(), ctx.estimateTokens(),
                    status, dur, error,
                    r != null && r.rawResponse() != null ? r.rawResponse().length() : 0,
                    r != null ? summarizeUnderstanding(r) : null);
        });
    }

    @Override
    public String generate(ContextPackage contextPackage, String systemPrompt) {
        return around(() -> delegate.generate(contextPackage, systemPrompt),
                (trace, status, dur, result, error) -> base(
                        trace, "generate", "deepseek", contextPackage.scene(),
                        contextPackage.prompt(), contextPackage.estimateTokens(),
                        status, dur, error,
                        result != null ? result.length() : 0,
                        result != null ? truncate(result, 500) : null));
    }

    @Override
    public String recognizeIntent(String content) {
        return around(() -> delegate.recognizeIntent(content),
                (trace, status, dur, result, error) -> base(
                        trace, "recognizeIntent", "deepseek", "intent",
                        content, content != null ? content.length() / 2 : 0,
                        status, dur, error,
                        result != null ? result.length() : 0,
                        result != null ? truncate(result, 100) : null));
    }

    // ── 打点骨架 ──

    /**
     * 执行一次 AI 调用并记录日志（快照-恢复 trace，调用点 set 的锚点在装饰器返回后原样保留）。
     */
    private <T> T around(Supplier<T> call, Tracer<T> tracer) {
        AiTraceContext.Trace prev = AiTraceContext.get();
        long start = System.currentTimeMillis();
        try {
            T result = call.get();
            long duration = System.currentTimeMillis() - start;
            try {
                logger.log(userIdOf(prev), tracer.build(prev, "ok", duration, result, null));
            } catch (Exception e) {
                log.warn("AI 交互日志写入失败: {}", e.getMessage());
            }
            return result;
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - start;
            try {
                logger.log(userIdOf(prev), tracer.build(prev, "error", duration, null, e.getMessage()));
            } catch (Exception ignore) {
                // 日志绝不掩盖原始异常
            }
            throw e;
        } finally {
            AiTraceContext.restore(prev);
        }
    }

    @FunctionalInterface
    private interface Tracer<T> {
        AiInteractionLog build(AiTraceContext.Trace trace, String status, long durationMs, T result, String error);
    }

    /** 组装基础字段（trace 上的关联锚点可为 null，此时靠 scene/prompt 追溯）。 */
    private AiInteractionLog base(AiTraceContext.Trace trace, String kind, String model, String scene,
                                  String prompt, Integer estimatedTokens,
                                  String status, long durationMs, String error,
                                  Integer responseLength, String responseSummary) {
        return new AiInteractionLog(
                UUID.randomUUID().toString(),
                LocalDateTime.now().toString(),
                durationMs,
                userIdOf(trace),
                kind, scene,
                trace != null ? trace.recordId() : null,
                trace != null ? trace.cardId() : null,
                trace != null ? trace.source() : null,
                model, prompt, estimatedTokens,
                status, error, responseLength, responseSummary);
    }

    private String userIdOf(AiTraceContext.Trace trace) {
        return trace != null && trace.userId() != null ? trace.userId() : "default";
    }

    private String summarizeUnderstanding(AiUnderstanding r) {
        StringBuilder sb = new StringBuilder();
        if (r.summary() != null) sb.append("summary=").append(truncate(r.summary(), 80));
        if (r.tags() != null && !r.tags().isEmpty()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("tags=").append(List.copyOf(r.tags()).subList(0, Math.min(r.tags().size(), 5)));
        }
        if (r.insight() != null && !r.insight().isBlank()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("insight=").append(truncate(r.insight(), 80));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
