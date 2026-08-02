package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    private final CardFileRepository cardRepository;
    private final MemoryService memoryService;

    public ConversationController(AiClient aiClient, RecordRepository recordRepository,
                                  CardFileRepository cardRepository,
                                  MemoryService memoryService) {
        this.aiClient = aiClient;
        this.recordRepository = recordRepository;
        this.cardRepository = cardRepository;
        this.memoryService = memoryService;
    }

    @PostMapping("/end")
    public ResponseEntity<EndConversationResponse> endConversation(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody EndConversationRequest request) {

        log.info("Conversation end | userId={} | turns={} | cardId={}",
                userId, request.turns().size(), request.cardId());

        // Build prompt from all turns
        String turnText = buildTurnText(request.turns());
        String prompt = """
                客观总结这段对话（不超过40字），避免人称代词。
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

        // AI 理解失败降级：不 500，用对话原文兜底（与 RecordController 降级模式一致）
        AiUnderstanding understanding;
        try {
            understanding = aiClient.understand(contextPackage);
        } catch (Exception e) {
            log.warn("Conversation end AI 总结失败，降级处理 | cardId={} | err={}",
                    request.cardId(), e.getMessage());
            understanding = null;
        }

        // Save as a record（summary 兜底：AI 成功但 summary 为空也走原文）
        String id = RecordFileRepository.generateId();
        String summaryText;
        List<String> tags;
        if (understanding != null && understanding.summary() != null && !understanding.summary().isBlank()) {
            summaryText = understanding.summary();
            tags = understanding.tags() != null ? understanding.tags() : List.of();
        } else {
            summaryText = fallbackSummary(turnText);
            tags = List.of();
        }

        ContentRecord record = new ContentRecord(
                id, "conversation", "ai_summary",
                summaryText.length() > 50 ? summaryText.substring(0, 50) : summaryText,
                summaryText,
                tags,
                LocalDateTime.now()
        );
        recordRepository.save(userId, record);

        // Update card file with summary and ended status
        if (request.cardId() != null) {
            Optional<CardRecord> existing = cardRepository.findById(userId, request.cardId());
            if (existing.isPresent()) {
                CardRecord updated = existing.get()
                        .withStatus("ended")
                        .withSummary(summaryText);
                cardRepository.save(userId, updated);
                log.info("Card updated | cardId={} | status=ended", request.cardId());
            }
        }

        // 沉淀记忆：AI 成功 → 洞察记忆；失败 → 原文降级（标 DEGRADED，AI 恢复后由重补升级）
        if (understanding != null) {
            Memory memory = Memory.fromUnderstanding(record.id(), understanding);
            memoryService.persist(userId, memory);
        } else {
            try {
                Memory degraded = Memory.fromContentFallback(record.id(), turnText);
                memoryService.persist(userId, degraded);
                log.info("Memory degraded-persisted (AI failed) | recordId={}", id);
            } catch (Exception e) {
                log.debug("Degraded memory persist skipped: {}", e.getMessage());
            }
        }

        log.info("Conversation summary saved | recordId={} | tags={} | cardId={}", id, tags, request.cardId());

        return ResponseEntity.ok(new EndConversationResponse(id, summaryText, tags));
    }

    /** AI 总结失败时的兜底 summary：对话原文（截断 50 字），保证 end 永不因 AI 异常返回 500。 */
    private String fallbackSummary(String turnText) {
        if (turnText == null || turnText.isBlank()) return "对话已结束";
        String clean = turnText.strip();
        return clean.length() > 50 ? clean.substring(0, 50) + "…" : clean;
    }

    private String buildTurnText(List<String> turns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            String prefix = (i % 2 == 0) ? "我" : "你";
            sb.append(prefix).append("：").append(turns.get(i)).append("\n");
        }
        return sb.toString();
    }

    public record EndConversationRequest(
            List<String> turns,
            String cardId
    ) {
        public EndConversationRequest { turns = turns != null ? turns : List.of(); }
    }

    public record EndConversationResponse(
            String recordId,
            String summary,
            List<String> tags
    ) {}
}
