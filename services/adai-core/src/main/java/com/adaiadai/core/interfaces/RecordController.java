package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.application.RecordRetryService;
import com.adaiadai.core.application.RecordUnderstandingService;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.context.IntentRecognizer.Intent;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RecordController — unified input entry.
 * <p>
 * For STATEMENT: saves record + ContextEngine (Identity + TagIndex + Memory + Date) → AI tags/summary.
 * For QUESTION: saves record + full ContextEngine + AI answer.
 */
@RestController
@RequestMapping("/api/v1/records")
public class RecordController {

    private static final Logger log = LoggerFactory.getLogger(RecordController.class);

    private final IntentRecognizer intentRecognizer;
    private final QuestionAppService questionAppService;
    private final RecordUnderstandingService understandingService;
    private final RecordRepository recordRepository;
    private final CardFileRepository cardRepository;
    private final MemoryService memoryService;
    private final RecordRetryService recordRetryService;

    public RecordController(IntentRecognizer intentRecognizer,
                            QuestionAppService questionAppService,
                            RecordUnderstandingService understandingService,
                            RecordRepository recordRepository,
                            CardFileRepository cardRepository,
                            MemoryService memoryService,
                            RecordRetryService recordRetryService) {
        this.intentRecognizer = intentRecognizer;
        this.questionAppService = questionAppService;
        this.understandingService = understandingService;
        this.recordRepository = recordRepository;
        this.cardRepository = cardRepository;
        this.memoryService = memoryService;
        this.recordRetryService = recordRetryService;
    }

    @PostMapping
    public ResponseEntity<?> createRecord(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody CreateRecordRequest request) {
        ContentRecord record = buildRecord(request);

        // cardId present AND card file exists → continuation of chat, append turn to card only
        if (request.cardId() != null && cardRepository.findById(userId, request.cardId()).isPresent()) {
            log.info("Card continuation | cardId={} | content=\"{}\"", request.cardId(), truncate(request.content(), 40));
            return handleQuestion(userId, record, request.cardId());
        }

        // New record: save content record first
        recordRepository.save(userId, record);

        try {
            // New card or first request with cardId: resolve intent once
            Intent intent = resolveIntent(request, record);

            log.info("Intent | intent={} | recordId={} | cardId={} | content=\"{}\" | manual={}",
                    intent, record.id(), request.cardId(), truncate(request.content(), 40), request.intent() != null);

            if (intent == Intent.QUESTION) {
                return handleQuestion(userId, record, request.cardId());
            }
            return handleStatem(userId, record);
        } catch (Exception e) {
            // AI 处理失败 → 保留用户记录（不删除，数据不丢），AI 增强留空，
            // 由 RecordRetryService 每 15 分钟自动补齐 summary/tags
            log.warn("AI processing failed, record kept for retry | id={} | error={}",
                    record.id(), e.getMessage());
            ContentRecord kept = new ContentRecord(
                    record.id(), record.type(), record.source(), record.title(), record.content(),
                    List.of(), record.createdAt(), "log", "recorded", "life"
            );
            recordRepository.save(userId, kept);

            // 降级沉淀：AI 失败也入记忆（原文标 DEGRADED），AI 恢复后由重补升级为洞察
            try {
                Memory degraded = Memory.fromContentFallback(record.id(), record.content());
                memoryService.persist(userId, degraded);
                log.info("Memory degraded-persisted (AI failed) | recordId={}", record.id());
            } catch (Exception memEx) {
                log.debug("Degraded memory persist skipped: {}", memEx.getMessage());
            }

            return ResponseEntity.ok(new StatemResponse(
                    "log", record.id(), record.content(), List.of(), "recorded", "life"
            ));
        }
    }

    /**
     * Resolve intent: manual override > AI. No regex fallback, no silent log.
     */
    private Intent resolveIntent(CreateRecordRequest request, ContentRecord record) {
        // 1. Manual override
        if (request.intent() != null) {
            return switch (request.intent()) {
                case "question" -> Intent.QUESTION;
                default -> Intent.STATEMENT;
            };
        }

        // 2. AI-based — throws on failure, never silently returns STATEMENT
        return intentRecognizer.recognizeWithAi(record.content());
    }

    /**
     * STATEMENT: save record + ContextEngine (Identity + TagIndex + Memory + Date) → AI tags/summary.
     */
    private ResponseEntity<StatemResponse> handleStatem(String userId, ContentRecord record) {
        List<String> tags = Collections.emptyList();
        String summary = null;
        String domain = "life";
        AiUnderstanding understanding = null;

        try {
            // 走 ContextEngine 获取完整上下文（Identity + 标签索引 + Memory 回读 + 日期/星期）
            understanding = understandingService.composeAndUnderstand(userId, "note", record).understanding();
            tags = understanding.tags();
            summary = understanding.summary();
            domain = understanding.domain() != null ? understanding.domain() : "life";
        } catch (Exception e) {
            log.debug("AI tagging skipped for statement: {}", e.getMessage());
        }

        // summary 兜底在 try 外：AI 失败时也回退 "recorded"（与 controller 顶层降级路径一致）
        if (summary == null || summary.isBlank() || summary.length() > 50) {
            summary = "recorded";
        }

        // Re-save with summary+tags+domain persisted to file
        ContentRecord enriched = new ContentRecord(
                record.id(), record.type(), record.source(), record.title(), record.content(),
                tags != null ? tags : List.of(), record.createdAt(), "log", summary, domain
        );
        recordRepository.save(userId, enriched);

        // Persist AI understanding as memory (use full understanding with insight/patterns/preferences)
        if (understanding != null) {
            try {
                Memory memory = Memory.fromUnderstanding(record.id(), understanding);
                memoryService.persist(userId, memory);
                log.info("Memory persisted for statement | recordId={} | insight=\"{}\" | patterns={} | preferences={}",
                        record.id(), truncate(understanding.insight() != null ? understanding.insight() : summary, 40),
                        understanding.patterns() != null ? understanding.patterns().size() : 0,
                        understanding.preferences() != null ? understanding.preferences().size() : 0);
            } catch (Exception e) {
                log.debug("Memory persist skipped for statement: {}", e.getMessage());
            }
        } else {
            // AI 理解失败 → 降级沉淀：原文入记忆（标 DEGRADED），AI 恢复后由重补升级为洞察
            try {
                Memory degraded = Memory.fromContentFallback(record.id(), record.content());
                memoryService.persist(userId, degraded);
                log.info("Memory degraded-persisted (AI failed) | recordId={}", record.id());
            } catch (Exception e) {
                log.debug("Degraded memory persist skipped for statement: {}", e.getMessage());
            }
        }

        return ResponseEntity.ok(new StatemResponse(
                "log", record.id(), record.content(), tags, summary, domain
        ));
    }

    /**
     * QUESTION: save record + AI answer. Create card if first turn.
     */
    private ResponseEntity<QuestionResponse> handleQuestion(String userId, ContentRecord record, String cardId) {
        // Ensure card exists: create if this is the first turn
        if (cardId != null) {
            java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            String timeStr = record.createdAt().format(timeFmt);
            Optional<CardRecord> existing = cardRepository.findById(userId, cardId);
            if (existing.isEmpty()) {
                CardRecord card = new CardRecord(
                        cardId, "conversation", "active",
                        List.of(), List.of(new CardRecord.Turn(true, record.content(), timeStr)),
                        null, record.createdAt(), record.createdAt()
                );
                cardRepository.save(userId, card);
                log.info("Card created (first turn) | cardId={}", cardId);
            } else {
                // Append user turn to existing card
                CardRecord updated = existing.get()
                        .withTurn(true, record.content(), timeStr);
                cardRepository.save(userId, updated);
                log.info("Card append | cardId={} | content=\"{}\"", cardId, truncate(record.content(), 40));
            }
        }

        QuestionAppService.AnswerResult result = questionAppService.answer(userId, record, cardId);
        log.info("Answer completed | recordId={} | cardId={}", result.recordId(), cardId);

        return ResponseEntity.ok(new QuestionResponse(
                "question",
                result.recordId(),
                result.summary(),
                result.tags(),
                result.rawResponse(),
                result.domain()
        ));
    }

    private ContentRecord buildRecord(CreateRecordRequest request) {
        String id = RecordFileRepository.generateId();
        return new ContentRecord(
                id,
                request.type() != null ? request.type() : "note",
                "user_input",
                request.content().length() > 50
                        ? request.content().substring(0, 50) + "..."
                        : request.content(),
                request.content(),
                request.tags() != null ? request.tags() : List.of(),
                LocalDateTime.now()
        );
    }

    public record CreateRecordRequest(
            @NotBlank(message = "content cannot be empty")
            @Size(min = 1, max = 10000, message = "content length must be 1-10000")
            String content,
            String type,
            List<String> tags,
            String intent,       // "log" | "question" | null (auto)
            String cardId        // optional: card ID for active conversation
    ) {}

    public record StatemResponse(
            String intent,
            String recordId,
            String content,
            List<String> tags,
            String summary,
            String domain
    ) {}

    public record QuestionResponse(
            String intent,
            String recordId,
            String summary,
            List<String> tags,
            String rawResponse,
            String domain
    ) {}

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRecord(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id) {
        log.info("Delete record | id={}", id);
        // 不管 rec_ 还是 card_ 前缀，两个仓库都清理：
        // - rec_ 文件可能也在 cards/ 目录（旧版前端经 endConversation 存过去的）
        // - card_ 文件可能也有对应的 ContentRecord
        recordRepository.deleteById(userId, id);
        cardRepository.deleteById(userId, id);
        memoryService.deleteByRecordId(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/domain")
    public ResponseEntity<Void> updateDomain(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id, @RequestBody Map<String, String> body) {
        String domain = body.get("domain");
        if (domain == null || !List.of("life", "trading", "project").contains(domain)) {
            return ResponseEntity.badRequest().build();
        }
        log.info("Update domain | id={} | domain={}", id, domain);
        recordRepository.updateDomain(userId, id, domain);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/retry")
    public ResponseEntity<Map<String, Object>> triggerRetry(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        long before = memoryService.count(userId);
        recordRetryService.retryUnprocessed(userId);
        long after = memoryService.count(userId);
        long newMemories = after - before;
        log.info("手动触发重补完成 | 记忆: {} → {} ({})", before, after, newMemories);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "memoriesBefore", before,
                "memoriesAfter", after,
                "newMemories", newMemories
        ));
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
