package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.context.IntentRecognizer.Intent;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
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
 * Supports manual intent override via {@code intent} field in the request body.
 * For STATEMENT: saves record + returns AI-generated tags and a brief summary.
 * For QUESTION: saves record + AI answer, grouped into a session.
 */
@RestController
@RequestMapping("/api/v1/records")
public class RecordController {

    private static final Logger log = LoggerFactory.getLogger(RecordController.class);

    private final IntentRecognizer intentRecognizer;
    private final QuestionAppService questionAppService;
    private final RecordRepository recordRepository;
    private final CardFileRepository cardRepository;
    private final AiClient aiClient;

    public RecordController(IntentRecognizer intentRecognizer,
                            QuestionAppService questionAppService,
                            RecordRepository recordRepository,
                            CardFileRepository cardRepository,
                            AiClient aiClient) {
        this.intentRecognizer = intentRecognizer;
        this.questionAppService = questionAppService;
        this.recordRepository = recordRepository;
        this.cardRepository = cardRepository;
        this.aiClient = aiClient;
    }

    @PostMapping
    public ResponseEntity<?> createRecord(@Valid @RequestBody CreateRecordRequest request) {
        ContentRecord record = buildRecord(request);
        recordRepository.save(record);

        // cardId present AND card file exists → continuation of chat, skip intent
        if (request.cardId() != null && cardRepository.findById(request.cardId()).isPresent()) {
            appendToCard(request.cardId(), record);
            log.info("Card append | cardId={} | content=\"{}\"", request.cardId(), truncate(request.content(), 40));
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
     * Append a record turn to an existing card file.
     */
    private void appendToCard(String cardId, ContentRecord record) {
        java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        String timeStr = record.createdAt().format(timeFmt);

        Optional<CardRecord> existing = cardRepository.findById(cardId);
        if (existing.isPresent()) {
            CardRecord updated = existing.get()
                    .withTurn(true, record.content(), timeStr);
            cardRepository.save(updated);
        } else {
            CardRecord card = new CardRecord(
                    cardId, "conversation", "active",
                    List.of(), List.of(new CardRecord.Turn(true, record.content(), timeStr)),
                    null, record.createdAt(), record.createdAt()
            );
            cardRepository.save(card);
        }
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
     * STATEMENT: save record, return AI-generated tags and summary for frontend feedback.
     */
    private ResponseEntity<StatemResponse> handleStatem(ContentRecord record) {
        List<String> tags = Collections.emptyList();
        String summary = null;

        try {
            String prompt = """
                    用户记录了一条内容。请分析并总结。
                    内容：%s
                    输出 JSON：
                    {
                      "summary": "一句话确认摘要（10字以内）",
                      "tags": ["标签1", "标签2"],
                      "sentiment": "neutral",
                      "actionable": false
                    }
                    """.formatted(record.content());
            ContextPackage ctx = ContextPackage.simple(
                    "note", "",
                    record.title(), record.content(), record.tags(), prompt
            );
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
     * QUESTION: save record + AI answer.
     */
    private ResponseEntity<QuestionResponse> handleQuestion(ContentRecord record, String cardId) {
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
