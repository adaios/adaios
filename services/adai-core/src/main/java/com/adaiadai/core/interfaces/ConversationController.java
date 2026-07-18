package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ConversationController — conversation lifecycle endpoints.
 * <p>
 * {@code POST /api/v1/conversations/end} — summarize a conversation,
 * save as a record, return summary + tags.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final AiClient aiClient;
    private final RecordRepository recordRepository;

    public ConversationController(AiClient aiClient, RecordRepository recordRepository) {
        this.aiClient = aiClient;
        this.recordRepository = recordRepository;
    }

    @PostMapping("/end")
    public ResponseEntity<EndConversationResponse> endConversation(
            @RequestBody EndConversationRequest request) {

        log.info("Conversation end | turns={}", request.turns().size());

        // Build prompt from all turns
        String turnText = buildTurnText(request.turns());
        String prompt = """
                以下是一段对话记录，请总结这段对话的核心内容（不超过100字），并给出相关标签。
                输出 JSON（不要包裹 markdown 代码块）：
                {
                  "summary": "对话总结",
                  "tags": ["标签1", "标签2"],
                  "sentiment": "neutral",
                  "actionable": false,
                  "actionSuggestion": null
                }

                对话内容：
                %s
                """.formatted(turnText);

        var contextPackage = com.adaiadai.core.kernel.context.engine.ContextPackage.simple(
                "conversation", "",
                "对话总结", turnText, List.of(), prompt
        );

        AiUnderstanding understanding = aiClient.understand(contextPackage);

        // Save as a record
        String id = RecordFileRepository.generateId();
        String summaryText = understanding.summary();
        List<String> tags = understanding.tags();

        ContentRecord record = new ContentRecord(
                id, "conversation", "ai_summary",
                summaryText.length() > 50 ? summaryText.substring(0, 50) : summaryText,
                summaryText,
                tags != null ? tags : List.of(),
                LocalDateTime.now()
        );
        recordRepository.save(record);

        log.info("Conversation summary saved | recordId={} | tags={}", id, tags);

        return ResponseEntity.ok(new EndConversationResponse(id, summaryText, tags));
    }

    private String buildTurnText(List<String> turns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            String prefix = (i % 2 == 0) ? "用户" : "AI";
            sb.append(prefix).append("：").append(turns.get(i)).append("\n");
        }
        return sb.toString();
    }

    public record EndConversationRequest(
            List<String> turns
    ) {}

    public record EndConversationResponse(
            String recordId,
            String summary,
            List<String> tags
    ) {}
}
