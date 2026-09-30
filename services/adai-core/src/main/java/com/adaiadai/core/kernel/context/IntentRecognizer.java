package com.adaiadai.core.kernel.context;

import com.adaiadai.core.kernel.ai.AiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * IntentRecognizer — intent recognition.
 *
 * Rules:
 * 1. LLM 判定是否需要回复（ask/log）。
 * 2. LLM 失败时直接抛异常，不静默降级到 log。
 */
@Component
public class IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizer.class);

    private final AiClient aiClient;

    public IntentRecognizer(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    /**
     * AI-based recognition: call LLM to decide ask or log.
     * Throws on LLM failure — never silently returns log.
     */
    public Intent recognizeWithAi(String content) {
        return recognizeWithAi(content, false);
    }

    /**
     * @param leanAsk 是否使用「偏向需要回复」的判据（D4「宁可它多说一句」）。
     *                **文本入口传 true；媒体入口必须传 false**——见
     *                {@link com.adaiadai.core.kernel.ai.AiClient#recognizeIntentLeanAsk} 的说明
     *                （对抗审查 P1-1：倾向会改变图片资产形态、并让超长配文从 log 变 400）。
     */
    public Intent recognizeWithAi(String content, boolean leanAsk) {
        if (content == null || content.isBlank()) return Intent.STATEMENT;
        String result = leanAsk ? aiClient.recognizeIntentLeanAsk(content) : aiClient.recognizeIntent(content);
        if ("ask".equals(result)) {
            return Intent.QUESTION;
        }
        return Intent.STATEMENT;
    }

    public enum Intent {
        STATEMENT,
        QUESTION
    }
}
