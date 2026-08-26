package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DeepSeekAiClient 解析回归测试。
 * <p>
 * 背景（2026-08-14/15 连调实锤）：deepseek-v4-pro 是推理模型，max_tokens 被思维链吃满时
 * content 为空、finish_reason=length → 抛"返回空内容"。正解是提高各路径 max_tokens 让
 * 思维链 + 答案都落盘；reasoning_content 是思考过程不是答案，**不得回退当作结果**（会污染
 * JSON 解析）。本类锁定解析行为，防止"空内容"误判与 reasoning 误用回归。
 */
class DeepSeekAiClientTest {

    private final DeepSeekAiClient client = new DeepSeekAiClient("", "", "test", "test-flash");

    @Test
    void normalContent_returnsAsIs() throws Exception {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"STATEMENT","reasoning_content":"思考过程"}}]}""";
        assertEquals("STATEMENT", client.parseChatCompletion(body));
    }

    @Test
    void emptyContent_doesNotUseReasoning_throws() {
        // reasoning_content 是思考不是答案：content 空时不得回退 reasoning，仍报"空内容"走重试
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"我们需要判断意图：这是陈述"}}]}""";
        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.parseChatCompletion(body));
        assertEquals("DeepSeek API 返回空内容", ex.getMessage());
    }

    @Test
    void apiError_throws() {
        String body = """
                {"error":{"message":"invalid api key"}}""";
        assertThrows(RuntimeException.class, () -> client.parseChatCompletion(body));
    }

    @Test
    void noChoices_throws() {
        assertThrows(RuntimeException.class, () -> client.parseChatCompletion("{}"));
    }

    // ── 2026-08-26 模型分层（S-10）──

    @Test
    void modelFor_reviewUsesPro() {
        AiTraceContext.set("adai", null, null, "trading_review");
        try {
            assertEquals("test", client.modelFor(),
                    "复盘（trading_review）应走旗舰 pro 模型（要推理质量）");
        } finally {
            AiTraceContext.restore(null);
        }
    }

    @Test
    void modelFor_questionUsesFlash() {
        AiTraceContext.set("adai", "rec_x", null, "question");
        try {
            assertEquals("test-flash", client.modelFor(),
                    "问答（question）应走快模型 flash（高频交互提速）");
        } finally {
            AiTraceContext.restore(null);
        }
    }

    @Test
    void modelFor_noTraceUsesFlash() {
        AiTraceContext.restore(null);
        assertEquals("test-flash", client.modelFor(),
                "无 trace 上下文默认走 flash（保守快路径）");
    }
}
