package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * AskStreamController — 流式问答端点（ai-calling-governance 批 2，REVIEW P2-用户2）。
 * <p>
 * {@code POST /api/v1/records/ask-stream}（SSE 响应，协议见 api-spec §ask-stream）：
 * <pre>
 *   data: {"type":"text","content":"第一段"}          ← 正文增量（已剥离 JSON 回执尾巴）
 *   data: {"type":"meta","recordId":…,"summary":…,…} ← 末尾定稿（content=最终正文，权威）
 *   data: [DONE]
 *   data: {"type":"error","message":"人话"}           ← 中途失败（后仍跟 [DONE]）
 * </pre>
 * 语义：只服务「确定是问答」的路径（显式 question / cardId 续聊）；新输入的自动意图分流
 * 继续走旧同步端点 POST /records（兼容在网客户端）。副作用（record 回写/卡片/记忆）
 * 与同步路径完全一致——都走 {@link QuestionAppService}。
 */
@RestController
@RequestMapping("/api/v1/records")
public class AskStreamController {

    private static final Logger log = LoggerFactory.getLogger(AskStreamController.class);

    /**
     * SSE 连接超时：显式配置——Tomcat/容器 async 默认 ~30s 会掐断长回答
     * （2026-08-24 方案审查 ⭐⭐ 发现）；上限对齐前端 AI 超时 120s（S-9 矩阵）。
     */
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final QuestionAppService questionAppService;
    private final RecordRepository recordRepository;
    private final ObjectMapper objectMapper;
    private final Executor streamExecutor;

    public AskStreamController(QuestionAppService questionAppService,
                               RecordRepository recordRepository,
                               ObjectMapper objectMapper,
                               @Qualifier("askStreamExecutor") Executor streamExecutor) {
        this.questionAppService = questionAppService;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
        this.streamExecutor = streamExecutor;
    }

    // produces 显式 charset=UTF-8：SSE 规范默认 ISO-8859-1，中文 delta 在响应写出时即变 ?（不可逆）。
    // 方法体内再显式 setCharacterEncoding——MockMvc standalone 不透传 produces charset，双保险。
    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public ResponseEntity<SseEmitter> askStream(
            jakarta.servlet.http.HttpServletResponse servletResponse,
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody RecordController.CreateRecordRequest request) {
        servletResponse.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ContentRecord record = RecordController.buildRecord(request);
        String cardId = request.cardId();

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onTimeout(() -> log.warn("SSE 超时断开 | recordId={} | cardId={}", record.id(), cardId));
        emitter.onError(e -> log.warn("SSE 连接错误 | recordId={} | cardId={} | {}",
                record.id(), cardId, e.getMessage()));

        // 重发去重（S-9 同款，逻辑与同步路径共享）：命中 → 一次性推已有回答（前端渲染与流式一致）
        if (cardId != null) {
            var duplicate = questionAppService.findDuplicateResend(userId, cardId, request.content());
            if (duplicate.isPresent()) {
                var dup = duplicate.get();
                log.info("流式去重命中（超时重发）| cardId={} | content=\"{}\"",
                        cardId, truncate(request.content(), 40));
                try {
                    sendEvent(emitter, textPayload(dup.rawResponse() != null ? dup.rawResponse() : ""));
                    sendMeta(emitter, new QuestionAppService.StreamResult(
                            cardId, dup.summary(), dup.tags(), dup.rawResponse(), dup.domain()));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
                return ResponseEntity.ok(emitter);
            }
            // 建卡/追加用户轮次（首问建卡、续聊 append——与同步路径同口径）
            questionAppService.ensureCardWithUserTurn(userId, cardId, request.content(), record.createdAt());
        } else {
            // 新提问：先存记录 + intent=question 标记（#181 rebuild 幂等口径，controller 补标记）
            recordRepository.save(userId, withIntent(record, "question"));
        }

        try {
            streamExecutor.execute(() -> {
                try {
                    questionAppService.answerStream(userId, record, cardId, new QuestionAppService.AnswerStreamHandler() {
                        @Override
                        public void onDelta(String chunk) {
                            try {
                                sendEvent(emitter, textPayload(chunk));
                            } catch (Exception e) {
                                log.warn("SSE 发送 delta 失败（客户端可能已断开）| {}", e.getMessage());
                            }
                        }

                        @Override
                        public void onComplete(QuestionAppService.StreamResult result) {
                            try {
                                sendMeta(emitter, result);
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();
                                log.info("流式问答 SSE 完成 | recordId={} | cardId={}", record.id(), cardId);
                            } catch (Exception e) {
                                log.warn("SSE 收尾发送失败 | {}", e.getMessage());
                                emitter.completeWithError(e);
                            }
                        }

                        @Override
                        public void onError(String message) {
                            try {
                                sendEvent(emitter, errorPayload(message));
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();
                            } catch (Exception e) {
                                log.warn("SSE error 事件发送失败 | {}", e.getMessage());
                                emitter.completeWithError(e);
                            }
                        }
                    });
                } catch (Exception ex) {
                    log.error("流式问答任务异常 | recordId={} | cardId={} | {}", record.id(), cardId, ex.getMessage(), ex);
                    emitter.completeWithError(ex);
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("流式线程池拒绝（队列满）| recordId={}", record.id());
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        return ResponseEntity.ok(emitter);
    }

    /** meta 事件：recordId/summary/tags/domain/content（content=剥离 JSON 后的最终正文，权威定稿）。 */
    private void sendMeta(SseEmitter emitter, QuestionAppService.StreamResult result) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("type", "meta");
        payload.put("recordId", result.recordId());
        payload.put("summary", result.summary());
        payload.put("tags", result.tags() != null ? result.tags() : java.util.List.of());
        payload.put("domain", result.domain());
        payload.put("content", result.text() != null ? result.text() : "");
        sendEvent(emitter, payload);
    }

    /** text 事件载荷。 */
    private java.util.Map<String, Object> textPayload(String content) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", "text");
        m.put("content", content);
        return m;
    }

    /** error 事件载荷。 */
    private java.util.Map<String, Object> errorPayload(String message) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", "error");
        m.put("message", message);
        return m;
    }

    /**
     * 事件发送：JSON 序列化后以 UTF-8 字节发出——SseEmitter 的 String data 走
     * StringHttpMessageConverter（默认 ISO-8859-1），中文在写出时即变 ?；byte[] 走
     * ByteArrayHttpMessageConverter 原样透传，编码无关（MockMvc 与生产容器一致）。
     */
    private void sendEvent(SseEmitter emitter, java.util.Map<String, Object> payload) throws Exception {
        emitter.send(SseEmitter.event().data(
                objectMapper.writeValueAsString(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private ContentRecord withIntent(ContentRecord r, String intent) {
        return new ContentRecord(r.id(), r.type(), r.source(), r.title(), r.content(),
                r.tags(), r.createdAt(), intent, null, r.domain());
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
