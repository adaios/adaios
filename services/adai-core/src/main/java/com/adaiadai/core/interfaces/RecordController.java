package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.context.IntentRecognizer.Intent;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
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
    private final ContextEngine contextEngine;
    private final RecordRepository recordRepository;
    private final CardFileRepository cardRepository;
    private final AiClient aiClient;

    public RecordController(IntentRecognizer intentRecognizer,
                            QuestionAppService questionAppService,
                            ContextEngine contextEngine,
                            RecordRepository recordRepository,
                            CardFileRepository cardRepository,
                            AiClient aiClient) {
        this.intentRecognizer = intentRecognizer;
        this.questionAppService = questionAppService;
        this.contextEngine = contextEngine;
        this.recordRepository = recordRepository;
        this.cardRepository = cardRepository;
        this.aiClient = aiClient;
    }

    @PostMapping
    public ResponseEntity<?> createRecord(@Valid @RequestBody CreateRecordRequest request) {
        ContentRecord record = buildRecord(request);
        recordRepository.save(record);

        // cardId present AND card file exists → continuation of chat, directly handle question
        if (request.cardId() != null && cardRepository.findById(request.cardId()).isPresent()) {
            log.info("Card continuation | cardId={} | content=\"{}\"", request.cardId(), truncate(request.content(), 40));
            return handleQuestion(record, request.cardId());
        }

        // New card or first request with cardId: resolve intent once
        Intent intent = resolveIntent(request, record);

        log.info("Intent | intent={} | recordId={} | cardId={} | content=\"{}\" | manual={}",
                intent, record.id(), request.cardId(), truncate(request.content(), 40), request.intent() != null);

        if (intent == Intent.QUESTION) {
            return handleQuestion(record, request.cardId());
        }
        return handleStatem(record);
    }

    /**
     * Resolve intent: manual > AI > regex. No session-aware for new cards.
     */
    private Intent resolveIntent(CreateRecordRequest request, ContentRecord record) {
        // 1. Manual override
        if (request.intent() != null) {
            return "question".equals(request.intent()) ? Intent.QUESTION : Intent.STATEMENT;
        }

        // 2. AI-based (always run, not gated by session)
        Intent aiIntent = intentRecognizer.recognizeWithAi(record.content());
        if (aiIntent != null) {
            return aiIntent;
        }

        // 3. Regex fallback (no session-aware)
        return intentRecognizer.recognize(record.content(), false, false);
    }

    /**
     * STATEMENT: save record + ContextEngine (Identity + TagIndex + Memory + Date) → AI tags/summary.
     */
    private ResponseEntity<StatemResponse> handleStatem(ContentRecord record) {
        List<String> tags = Collections.emptyList();
        String summary = null;

        try {
            // 走 ContextEngine 获取完整上下文（Identity + 标签索引 + Memory 回读 + 日期/星期）
            ContextPackage ctx = contextEngine.compose("note", record);
            AiUnderstanding understanding = aiClient.understand(ctx);
            tags = understanding.tags();
            summary = understanding.summary();
            if (summary == null || summary.isBlank() || summary.length() > 50) {
                summary = "recorded";
            }
        } catch (Exception e) {
            log.debug("AI tagging skipped for statement: {}", e.getMessage());
        }

        // Re-save with summary+tags persisted to file
        ContentRecord enriched = new ContentRecord(
                record.id(), record.type(), record.source(), record.title(), record.content(),
                tags != null ? tags : List.of(), record.createdAt(), "log", summary
        );
        recordRepository.save(enriched);

        return ResponseEntity.ok(new StatemResponse(
                "log", record.id(), record.content(), tags, summary
        ));
    }

    /**
     * QUESTION: save record + AI answer. Create card if first turn.
     */
    private ResponseEntity<QuestionResponse> handleQuestion(ContentRecord record, String cardId) {
        // Ensure card exists: create if this is the first turn
        if (cardId != null) {
            java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            String timeStr = record.createdAt().format(timeFmt);
            Optional<CardRecord> existing = cardRepository.findById(cardId);
            if (existing.isEmpty()) {
                CardRecord card = new CardRecord(
                        cardId, "conversation", "active",
                        List.of(), List.of(new CardRecord.Turn(true, record.content(), timeStr)),
                        null, record.createdAt(), record.createdAt()
                );
                cardRepository.save(card);
                log.info("Card created (first turn) | cardId={}", cardId);
            } else {
                // Append user turn to existing card
                CardRecord updated = existing.get()
                        .withTurn(true, record.content(), timeStr);
                cardRepository.save(updated);
                log.info("Card append | cardId={} | content=\"{}\"", cardId, truncate(record.content(), 40));
            }
        }

        QuestionAppService.AnswerResult result = questionAppService.answer(record, cardId);
        log.info("Answer completed | recordId={} | cardId={}", result.recordId(), cardId);

        return ResponseEntity.ok(new QuestionResponse(
                "question",
                result.recordId(),
                result.summary(),
                result.tags(),
                result.rawResponse()
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
            String summary
    ) {}

    public record QuestionResponse(
            String intent,
            String recordId,
            String summary,
            List<String> tags,
            String rawResponse
    ) {}

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
