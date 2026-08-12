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
        if (content == null || content.isBlank()) return Intent.STATEMENT;
        String result = aiClient.recognizeIntent(content);
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
