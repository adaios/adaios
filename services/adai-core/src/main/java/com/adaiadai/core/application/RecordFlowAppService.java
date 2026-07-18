package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
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
 */
@Service
public class RecordFlowAppService {

    private static final Logger log = LoggerFactory.getLogger(RecordFlowAppService.class);

    private final ContextEngine contextEngine;
    private final AiClient aiClient;
    private final MemoryService memoryService;

    public RecordFlowAppService(ContextEngine contextEngine, AiClient aiClient, MemoryService memoryService) {
        this.contextEngine = contextEngine;
        this.aiClient = aiClient;
        this.memoryService = memoryService;
    }

    /**
     * 处理 LOG 意图：Record → Context → AI → Memory。
     */
    public FlowResult process(ContentRecord record) {
        log.info("=== 记录流程开始 | recordId={} | type={} ===", record.id(), record.type());

        ContextPackage contextPackage = contextEngine.compose(record.type(), record);
        AiUnderstanding understanding = aiClient.understand(contextPackage);
        Memory memory = Memory.fromUnderstanding(record.id(), understanding);
        memoryService.persist(memory);

        log.info("=== 记录流程完成 | 摘要={} | 情感={} ===", understanding.summary(), understanding.sentiment());

        return new FlowResult(record.id(), memory.id(), understanding, contextPackage.estimateTokens());
    }

    /**
     * 处理 DECISION 意图：Record → Context(含会话历史+持仓) → AI 分析 → Memory。
     * 与 process 的区别：AI 被引导给出决策建议，而非仅理解。
     */
    public DecisionResult processDecision(ContentRecord record) {
        log.info("=== 决策流程开始 | recordId={} | type={} ===", record.id(), record.type());

        ContextPackage contextPackage = contextEngine.compose(record.type(), record);
        AiUnderstanding understanding = aiClient.understand(contextPackage);
        Memory memory = Memory.fromUnderstanding(record.id(), understanding);
        memoryService.persist(memory);

        log.info("=== 决策流程完成 | 建议={} ===", understanding.actionSuggestion());

        return new DecisionResult(
                record.id(), memory.id(), understanding, contextPackage.estimateTokens());
    }

    public record FlowResult(String recordId, String memoryId, AiUnderstanding understanding, int tokensEstimate) {}
    public record DecisionResult(String recordId, String memoryId, AiUnderstanding understanding, int tokensEstimate) {}
}
