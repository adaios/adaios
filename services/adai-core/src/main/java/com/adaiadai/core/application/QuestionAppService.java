package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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
    private final AiClient aiClient;

    public QuestionAppService(ContextEngine contextEngine, AiClient aiClient) {
        this.contextEngine = contextEngine;
        this.aiClient = aiClient;
    }

    /**
     * 回答用户提问。
     */
    public AnswerResult answer(ContentRecord record) {
        log.info("=== 问答流程开始 | recordId={} ===", record.id());

        // 走 ContextEngine 获取会话历史 + Identity
        ContextPackage contextPackage = contextEngine.compose("question", record);

        // AI 理解（回答问题 + 生成标签）
        AiUnderstanding understanding = aiClient.understand(contextPackage);

        log.info("=== 问答流程完成 | 标签={} ===", understanding.tags());

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
