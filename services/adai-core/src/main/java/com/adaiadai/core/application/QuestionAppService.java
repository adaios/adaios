package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
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

        // ContextEngine 负责组装：Identity + 会话历史 + 卡片上下文 + 记忆回读 + 领域上下文
        ContextPackage contextPackage = contextEngine.compose("question", record, cardId);

        // AI 理解（回答问题 + 生成标签）
        AiUnderstanding understanding = aiClient.understand(contextPackage);

        log.info("=== 问答流程完成 | 标签={} ===", understanding.tags());

        // 将 AI 返回的标签写回 Record，触发 TagIndexService 更新索引
        if (understanding.tags() != null && !understanding.tags().isEmpty()) {
            ContentRecord enriched = new ContentRecord(
                    record.id(), record.type(), record.source(), record.title(), record.content(),
                    understanding.tags(), record.createdAt(), "question", understanding.summary()
            );
            recordRepository.save(enriched);
            log.info("Record 标签已更新 | recordId={} | tags={}", record.id(), understanding.tags());
        }

        // Save AI turn to card if cardId present
        if (cardId != null) {
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

            Optional<CardRecord> existing = cardRepository.findById(cardId);
            if (existing.isPresent()) {
                CardRecord updated = existing.get()
                        .withTurn(false, understanding.summary(), timeStr);
                cardRepository.save(updated);
                log.info("AI turn saved to card | cardId={}", cardId);
            }
        }

        // Persist AI understanding as memory
        if (understanding.summary() != null || (understanding.tags() != null && !understanding.tags().isEmpty())) {
            try {
                Memory memory = Memory.fromUnderstanding(record.id(), understanding);
                memoryService.persist(memory);
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
                understanding.rawResponse()
        );
    }

    public record AnswerResult(
            String recordId,
            String summary,
            List<String> tags,
            String rawResponse
    ) {}
}
