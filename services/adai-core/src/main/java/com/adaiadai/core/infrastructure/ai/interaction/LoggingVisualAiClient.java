package com.adaiadai.core.infrastructure.ai.interaction;

import com.adaiadai.core.infrastructure.ai.vision.GlmVisualAiClient;
import com.adaiadai.core.infrastructure.ai.vision.ImageRequest;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * LoggingVisualAiClient — VisualAiClient 装饰器：自动记录图片理解/追问的 AI 交互（R1）。
 * <p>
 * 镜像 {@link LoggingAiClient}：包装 {@link GlmVisualAiClient}，对 understand / ask 打点落盘。
 * 图片理解不落 prompt 全文（base64 太大、非文本），记录图片备注/追问文本作为入参摘要。
 * <p>
 * {@code @Primary}：注册为 VisualAiClient 首选 bean，调用方零改动。
 */
@Component
@Primary
public class LoggingVisualAiClient implements VisualAiClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingVisualAiClient.class);

    private final GlmVisualAiClient delegate;
    private final AiInteractionLogger logger;

    public LoggingVisualAiClient(GlmVisualAiClient delegate, AiInteractionLogger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    @Override
    public ImageUnderstanding understand(ImageRequest request) {
        AiTraceContext.Trace prev = AiTraceContext.get();
        long start = System.currentTimeMillis();
        try {
            ImageUnderstanding r = delegate.understand(request);
            long duration = System.currentTimeMillis() - start;
            logEntry(prev, "visual.understand", "glm", request.caption(), "ok", null, summarize(r), duration);
            return r;
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - start;
            logEntry(prev, "visual.understand", "glm", request.caption(), "error", e.getMessage(), null, duration);
            throw e;
        } finally {
            AiTraceContext.restore(prev);
        }
    }

    @Override
    public String ask(ImageRequest request, String question) {
        AiTraceContext.Trace prev = AiTraceContext.get();
        long start = System.currentTimeMillis();
        try {
            String answer = delegate.ask(request, question);
            long duration = System.currentTimeMillis() - start;
            logEntry(prev, "visual.ask", "glm", question, "ok", null,
                    answer != null ? truncate(answer, 300) : null, duration);
            return answer;
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - start;
            logEntry(prev, "visual.ask", "glm", question, "error", e.getMessage(), null, duration);
            throw e;
        } finally {
            AiTraceContext.restore(prev);
        }
    }

    @Override
    public String askMulti(List<ImageRequest> requests, String question) {
        AiTraceContext.Trace prev = AiTraceContext.get();
        long start = System.currentTimeMillis();
        String prompt = multiPrompt(requests, question);
        try {
            String answer = delegate.askMulti(requests, question);
            long duration = System.currentTimeMillis() - start;
            logEntry(prev, "visual.ask", "glm", prompt, "ok", null,
                    answer != null ? truncate(answer, 300) : null, duration);
            return answer;
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - start;
            logEntry(prev, "visual.ask", "glm", prompt, "error", e.getMessage(), null, duration);
            throw e;
        } finally {
            AiTraceContext.restore(prev);
        }
    }

    /** 多图问答的入参摘要：标注图片数，复用 visual.ask kind（契约不变，prompt 带多图标记）。 */
    private String multiPrompt(List<ImageRequest> requests, String question) {
        return "[多图 ×" + (requests != null ? requests.size() : 0) + "] "
                + (question == null ? "" : question);
    }

    private void logEntry(AiTraceContext.Trace trace, String kind, String model, String prompt,
                          String status, String error, String responseSummary, long durationMs) {
        try {
            AiInteractionLog entry = new AiInteractionLog(
                    UUID.randomUUID().toString(),
                    LocalDateTime.now().toString(),
                    durationMs, // #218：视觉调用与文本调用一致，统计真实耗时
                    userIdOf(trace),
                    kind, "media",
                    trace != null ? trace.recordId() : null,
                    trace != null ? trace.cardId() : null,
                    trace != null ? trace.source() : null,
                    model,
                    prompt != null && !prompt.isBlank() ? prompt : "（图片理解，无备注）",
                    null, // 视觉调用无自定义 system 指令（#231：仅 generate 有）
                    null, // 图片无 token 预估
                    status, error,
                    responseSummary != null ? responseSummary.length() : 0,
                    responseSummary);
            logger.log(userIdOf(trace), entry);
        } catch (Exception e) {
            log.warn("AI 交互日志写入失败: {}", e.getMessage());
        }
    }

    private String summarize(ImageUnderstanding r) {
        if (r == null) return null;
        StringBuilder sb = new StringBuilder();
        if (r.summary() != null) sb.append("summary=").append(truncate(r.summary(), 80));
        if (r.category() != null) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("category=").append(r.category());
        }
        if (r.tags() != null && !r.tags().isEmpty()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("tags=").append(r.tags());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String userIdOf(AiTraceContext.Trace trace) {
        return trace != null && trace.userId() != null ? trace.userId() : "default";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
