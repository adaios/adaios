package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.ai.llm.LlmResponseParser;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * QuestionAppService — 问答处理服务。
 * <p>
 * 处理 QUESTION 意图：存 Record（保证会话连续性），
 * 通过 ContextEngine 获取会话历史 + 卡片上下文 + 记忆回读，返回 AI 回答。
 */
@Service
public class QuestionAppService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAppService.class);

    private final ContextEngine contextEngine;
    private final CardFileRepository cardRepository;
    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final AiClient aiClient;

    public QuestionAppService(ContextEngine contextEngine, CardFileRepository cardRepository,
                              RecordRepository recordRepository,
                              MemoryService memoryService,
                              AiClient aiClient) {
        this.contextEngine = contextEngine;
        this.cardRepository = cardRepository;
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.aiClient = aiClient;
    }

    /**
     * 回答用户提问（带 cardId 上下文）。
     */
    public AnswerResult answer(String userId, ContentRecord record) {
        return answer(userId, record, null);
    }

    /**
     * 回答用户提问（默认 QUESTION 场景）。
     *
     * @param record 当前记录
     * @param cardId 可选卡片 ID，提供完整对话上下文
     */
    public AnswerResult answer(String userId, ContentRecord record, String cardId) {
        return answer(userId, record, cardId, "question");
    }

    /**
     * 回答用户提问（指定场景）。
     *
     * @param record 当前记录
     * @param cardId 可选卡片 ID，提供完整对话上下文
     * @param scene  场景标识："question" / "decision"
     */
    public AnswerResult answer(String userId, ContentRecord record, String cardId, String scene) {
        log.info("=== 问答流程开始 | userId={} | recordId={} | cardId={} | scene={} ===",
                userId, record.id(), cardId, scene);

        // ContextEngine 负责组装：Identity + 会话历史 + 卡片上下文 + 记忆回读 + Knowledge + 领域上下文
        ContextPackage contextPackage = contextEngine.compose(userId, scene, record, cardId);

        // AI 理解（回答问题 + 生成标签）
        AiUnderstanding understanding = aiClient.understand(contextPackage);

        log.info("=== 问答流程完成 | 标签={} ===", understanding.tags());

        String domain = understanding.domain() != null ? understanding.domain() : "life";

        // 将 AI 返回的标签写回 Record，触发 TagIndexService 更新索引
        // 只有非卡片续接（无 cardId）时才存记录，避免重复拆分
        if (cardId == null && understanding.tags() != null && !understanding.tags().isEmpty()) {
            ContentRecord enriched = new ContentRecord(
                    record.id(), record.type(), record.source(), record.title(), record.content(),
                    understanding.tags(), record.createdAt(), "question", understanding.summary(),
                    domain
            );
            recordRepository.save(userId, enriched);
            log.info("Record 标签已更新 | recordId={} | tags={}", record.id(), understanding.tags());
        }

        // 剥离 AI 原始回复末尾的 JSON 元数据，只保留自然语言（REVIEW #13/#11）：
        // 实时显示与刷新后 parseTurns 一致，card 文件不再混入游离 JSON 块
        String aiText = LlmResponseParser.extractNaturalText(understanding.rawResponse());
        if (aiText == null || aiText.isBlank()) {
            aiText = understanding.summary();
        }

        // Save AI turn to card if cardId present
        if (cardId != null) {
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

            Optional<CardRecord> existing = cardRepository.findById(userId, cardId);
            if (existing.isPresent()) {
                CardRecord updated = existing.get()
                        .withTurn(false, aiText, timeStr);
                cardRepository.save(userId, updated);
                log.info("AI turn saved to card | cardId={} | len={}", cardId,
                        aiText != null ? aiText.length() : 0);
            }
        }

        // Persist AI understanding as memory
        if (understanding.summary() != null || (understanding.tags() != null && !understanding.tags().isEmpty())) {
            try {
                Memory memory = Memory.fromUnderstanding(record.id(), understanding);
                memoryService.persist(userId, memory);
                log.info("Memory persisted for question | recordId={} | summary=\"{}\"", record.id(),
                        understanding.summary() != null && understanding.summary().length() > 40
                                ? understanding.summary().substring(0, 40) + "..."
                                : understanding.summary());
            } catch (Exception e) {
                log.debug("Memory persist skipped: {}", e.getMessage());
            }
        }

        return new AnswerResult(
                record.id(),
                understanding.summary(),
                understanding.tags(),
                aiText,
                domain
        );
    }

    public record AnswerResult(
            String recordId,
            String summary,
            List<String> tags,
            String rawResponse,
            String domain
    ) {}
}
