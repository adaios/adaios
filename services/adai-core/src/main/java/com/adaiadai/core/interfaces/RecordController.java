package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.context.IntentRecognizer.Intent;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
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
import java.time.temporal.ChronoUnit;
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

    private static final long SESSION_TIMEOUT_MINUTES = 5;

    private final IntentRecognizer intentRecognizer;
    private final QuestionAppService questionAppService;
    private final RecordRepository recordRepository;
    private final AiClient aiClient;

    public RecordController(IntentRecognizer intentRecognizer,
                            QuestionAppService questionAppService,
                            RecordRepository recordRepository,
                            AiClient aiClient) {
        this.intentRecognizer = intentRecognizer;
        this.questionAppService = questionAppService;
        this.recordRepository = recordRepository;
        this.aiClient = aiClient;
    }

    @PostMapping
    public ResponseEntity<?> createRecord(@Valid @RequestBody CreateRecordRequest request) {
        ContentRecord record = buildRecord(request);
        recordRepository.save(record);

        // 1. Manual override: if frontend explicitly specified intent, use it
        Intent intent = resolveIntent(request, record);

        log.info("Intent | intent={} | recordId={} | content=\"{}\" | manual={}",
                intent, record.id(), truncate(request.content(), 40), request.intent() != null);

        if (intent == Intent.QUESTION) {
            return handleQuestion(record);
        }
        return handleStatem(record);
    }

    /**
     * Resolve intent: manual > AI > regex > session-aware.
     */
    private Intent resolveIntent(CreateRecordRequest request, ContentRecord record) {
        // 1. Manual override
        if (request.intent() != null) {
            return "question".equals(request.intent()) ? Intent.QUESTION : Intent.STATEMENT;
        }

        // 2. Session-aware: check if the previous record was a QUESTION within timeout
        boolean inSession = isInConversation(record.createdAt());

        // 3. AI-based (only when we're not clearly in a session)
        if (!inSession) {
            Intent aiIntent = intentRecognizer.recognizeWithAi(record.content());
            if (aiIntent != null) {
                return aiIntent;
            }
        }

        // 4. Regex + session-aware final fallback
        return intentRecognizer.recognize(record.content(), inSession, inSession);
    }

    /**
     * Check if there was a QUESTION record within the session timeout window.
     */
    private boolean isInConversation(LocalDateTime currentTime) {
        List<ContentRecord> allRecords = recordRepository.findAll();
        for (ContentRecord r : allRecords) {
            if (r.createdAt().isAfter(currentTime)) continue;
            long minutesAgo = ChronoUnit.MINUTES.between(r.createdAt(), currentTime);
            if (minutesAgo > SESSION_TIMEOUT_MINUTES) break;

            // Check if this record was treated as a question
            Intent intent = intentRecognizer.recognize(r.content(), false, false);
            if (intent == Intent.QUESTION) {
                return true;
            }
        }
        return false;
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
            // Use summary from parsed JSON; if empty, use raw as fallback
            summary = understanding.summary();
            if (summary == null || summary.isBlank() || summary.length() > 50) {
                summary = "recorded";
            }
        } catch (Exception e) {
            log.debug("AI tagging skipped for statement: {}", e.getMessage());
        }

        return ResponseEntity.ok(new StatemResponse(
                "log", record.id(), record.content(), tags, summary
        ));
    }

    /**
     * QUESTION: save record + AI answer.
     */
    private ResponseEntity<QuestionResponse> handleQuestion(ContentRecord record) {
        QuestionAppService.AnswerResult result = questionAppService.answer(record);
        log.info("Answer completed | recordId={}", result.recordId());

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
            String intent       // "log" | "question" | null (auto)
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
