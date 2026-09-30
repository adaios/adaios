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
 * 2. LLM 失败时**本类**直接抛异常，不降级为 log。
 *    ⚠️ 但这只是本类的行为，不等于「全链路不静默降级」：调用链上游
 *    `RecordController.createRecord`（:128-151）与 `MediaController`（:116-122 / :183-190）
 *    的 catch 会把异常兜成 `intent=log, summary=recorded` 的 **200**——
 *    即 D4「宁可多说一句」只落在 prompt 成功路径上，LLM 失败时仍按 log 收下。
 *    该方向是否正确**待用户拍板**（REVIEW **P2-对话4 ①**，当前判为有意的 fail-safe）。
 *    2026-10-01 就地更正：原注释只写「不静默降级到 log」，与上游实际行为不符，
 *    会让后来者把「已降级」当成「不会降级」（独立审查 P2-对话4 ② 提出）。
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
     *
     * ⚠️ 「never silently returns log」只描述本方法：上游 catch 仍会兜成 log，见类注释 rules 第 2 条。
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
