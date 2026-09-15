package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.ConversationText;
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
        String turnText = ConversationText.fromAlternating(request.turns());
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

        // R1 AI 交互日志：挂载卡片锚点
        AiTraceContext.set(userId, null, request.cardId(), "conversation");

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

        // E-A 写侧保真（memory-fidelity.md，2026-09-15 用户拍板 P4①）：
        // 正文 = 对话原文（"我：/你："交替），AI 转述降入 summary 字段；source 由
        // ai_summary 改为 user_input —— 作为「本条正文是原话」的新旧可分标记
        // （存量 source=ai_summary 且正文为转述，E-C 存量迁移据此辨识，无需逐条判断）。
        String originalText = turnText.isBlank() ? summaryText : turnText;
        ContentRecord record = new ContentRecord(
                id, "conversation", "user_input",
                truncate(originalText, 50),
                originalText,
                tags,
                LocalDateTime.now(),
                null,          // intent：conversation 不参与 #144 的 question 排除逻辑
                summaryText,   // summary = AI 转述（原先占据正文位置的内容）
                "life"         // domain：本批不扩 domain 透传（RFC 20260822 P1 另行拍板）
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

    /** 标题用的单行截断（title 是派生字段：落盘不存，读回由正文首行重建）。 */
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
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
