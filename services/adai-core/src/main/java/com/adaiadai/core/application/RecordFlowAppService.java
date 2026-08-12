package com.adaiadai.core.application;

import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RecordFlowAppService — MVP 闭环编排服务。
 * <p>
 * 处理 LOG（记录）和 DECISION（决策求助）两种意图：
 * <ul>
 *   <li>LOG → 存 Record → Context Engine → AI 理解 → Memory 沉淀</li>
 *   <li>DECISION → 存 Record → Context Engine → AI 分析(含建议) → Memory 沉淀</li>
 * </ul>
 * QUESTION 意图由 {@link QuestionAppService} 处理。
 * <p>
 * compose→understand 统一走 {@link RecordUnderstandingService}（REVIEW #13 消重复）。
 */
@Service
public class RecordFlowAppService {

    private static final Logger log = LoggerFactory.getLogger(RecordFlowAppService.class);

    private final RecordUnderstandingService understandingService;
    private final MemoryService memoryService;

    public RecordFlowAppService(RecordUnderstandingService understandingService, MemoryService memoryService) {
        this.understandingService = understandingService;
        this.memoryService = memoryService;
    }

    /**
     * 处理 LOG 意图：Record → Context → AI → Memory。
     */
    public FlowResult process(String userId, ContentRecord record) {
        log.info("=== 记录流程开始 | userId={} | recordId={} | type={} ===", userId, record.id(), record.type());

        var result = understandingService.composeAndUnderstand(userId, record.type(), record);
        AiUnderstanding understanding = result.understanding();
        Memory memory = Memory.fromUnderstanding(record.id(), understanding);
        memoryService.persist(userId, memory);

        log.info("=== 记录流程完成 | 摘要={} | 情感={} ===", understanding.summary(), understanding.sentiment());

        return new FlowResult(record.id(), memory.id(), understanding, result.contextPackage().estimateTokens());
    }

    public record FlowResult(String recordId, String memoryId, AiUnderstanding understanding, int tokensEstimate) {}
}
