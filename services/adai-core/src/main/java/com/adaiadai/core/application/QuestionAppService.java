package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * QuestionAppService — 问答处理服务。
 * <p>
 * 处理 QUESTION 意图：存 Record（保证会话连续性），
 * 通过 ContextEngine 获取会话历史，返回 AI 回答。
 */
@Service
public class QuestionAppService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAppService.class);

    private final ContextEngine contextEngine;
    private final CardFileRepository cardRepository;
    private final AiClient aiClient;

    public QuestionAppService(ContextEngine contextEngine, CardFileRepository cardRepository,
                              AiClient aiClient) {
        this.contextEngine = contextEngine;
        this.cardRepository = cardRepository;
        this.aiClient = aiClient;
    }

    /**
     * 回答用户提问（带 cardId 上下文）。
     */
    public AnswerResult answer(ContentRecord record) {
        return answer(record, null);
    }

    /**
     * 回答用户提问。
     *
     * @param record 当前记录
     * @param cardId 可选卡片 ID，提供完整对话上下文
     */
    public AnswerResult answer(ContentRecord record, String cardId) {
        log.info("=== 问答流程开始 | recordId={} | cardId={} ===", record.id(), cardId);

        // 走 ContextEngine 获取会话历史 + Identity
        ContextPackage contextPackage = contextEngine.compose("question", record);

        // Append card turns context if available
        String cardContext = loadCardContext(cardId);

        if (!cardContext.isBlank()) {
            // Modify the prompt to include card conversation turns
            String enhancedPrompt = contextPackage.prompt().replace(
                    "当前记录：",
                    cardContext + "\n\n当前记录："
            );
            contextPackage = new ContextPackage(
                    contextPackage.scene(),
                    contextPackage.identityRef(),
                    contextPackage.recordTitle(),
                    contextPackage.recordContent(),
                    contextPackage.recordTags(),
                    contextPackage.relatedRefs(),
                    enhancedPrompt,
                    contextPackage.assembledAt()
            );
        }

        // AI 理解（回答问题 + 生成标签）
        AiUnderstanding understanding = aiClient.understand(contextPackage);

        log.info("=== 问答流程完成 | 标签={} ===", understanding.tags());

        // Save AI turn to card if cardId present
        if (cardId != null) {
            java.time.format.DateTimeFormatter timeFmt =
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            String timeStr = LocalDateTime.now().format(timeFmt);

            Optional<CardRecord> existing = cardRepository.findById(cardId);
            if (existing.isPresent()) {
                CardRecord updated = existing.get()
                        .withTurn(false, understanding.summary(), timeStr);
                cardRepository.save(updated);
                log.info("AI turn saved to card | cardId={}", cardId);
            }
        }

        return new AnswerResult(
                record.id(),
                understanding.summary(),
                understanding.tags(),
                understanding.rawResponse()
        );
    }

    /**
     * Load card conversation context for enhanced prompt.
     */
    private String loadCardContext(String cardId) {
        if (cardId == null) return "";

        Optional<CardRecord> card = cardRepository.findById(cardId);
        if (card.isEmpty()) return "";

        List<CardRecord.Turn> turns = card.get().turns();
        if (turns.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## 当前会话对话历史\n\n");
        for (CardRecord.Turn turn : turns) {
            String prefix = turn.isUser() ? "用户" : "AI";
            sb.append("- [").append(turn.time()).append("] ")
                    .append(prefix).append("：").append(turn.text()).append("\n");
        }

        log.info("Card context loaded | cardId={} | turns={}", cardId, turns.size());
        return sb.toString();
    }

    public record AnswerResult(
            String recordId,
            String summary,
            List<String> tags,
            String rawResponse
    ) {}
}
