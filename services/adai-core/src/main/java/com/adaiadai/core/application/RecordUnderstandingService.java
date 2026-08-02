package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.springframework.stereotype.Service;

/**
 * RecordUnderstandingService — 统一的"组合上下文 → AI 理解"用例。
 * <p>
 * 收敛三处重复编排（REVIEW #13）：
 * <ul>
 *   <li>{@link RecordFlowAppService}（LOG 闭环）</li>
 *   <li>{@link RecordController}（STATEMENT 理解 + 写回）</li>
 *   <li>{@link RecordRetryService}（定时重补）</li>
 * </ul>
 * 三处共享 compose→understand 两步，persist/写回差异保留在各调用方。
 */
@Service
public class RecordUnderstandingService {

    private final ContextEngine contextEngine;
    private final AiClient aiClient;

    public RecordUnderstandingService(ContextEngine contextEngine, AiClient aiClient) {
        this.contextEngine = contextEngine;
        this.aiClient = aiClient;
    }

    /**
     * 组合上下文（ContextEngine.compose）→ AI 理解（AiClient.understand）。
     *
     * @param scene  场景（note/question/trading 等）
     * @param record 待理解的记录
     * @return 理解结果 + 上下文包（调用方按需取 estimateTokens）
     */
    public UnderstandingResult composeAndUnderstand(String userId, String scene, ContentRecord record) {
        ContextPackage ctx = contextEngine.compose(userId, scene, record);
        AiUnderstanding understanding = aiClient.understand(ctx);
        return new UnderstandingResult(understanding, ctx);
    }

    public record UnderstandingResult(AiUnderstanding understanding, ContextPackage contextPackage) {}
}
