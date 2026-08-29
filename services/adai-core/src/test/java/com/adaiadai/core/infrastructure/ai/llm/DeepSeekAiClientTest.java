package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ── 2026-08-29 流式输出（P2-用户2：ai-calling-governance 批 2 聊天流式）──

    /** 本地 SSE stub 服务：返回 sseBody，并断言请求带 stream:true。 */
    private static com.sun.net.httpserver.HttpServer sseServer(String sseBody, int status,
                                                               java.util.concurrent.atomic.AtomicBoolean streamFlag) throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] req = exchange.getRequestBody().readAllBytes();
            streamFlag.set(new String(req, StandardCharsets.UTF_8).contains("\"stream\":true"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            if (status != 200) {
                byte[] err = sseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, err.length);
                try (var os = exchange.getResponseBody()) { os.write(err); }
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            try (var os = exchange.getResponseBody()) { os.write(sseBody.getBytes(StandardCharsets.UTF_8)); }
        });
        server.start();
        return server;
    }

    @Test
    void streamGenerate_forwardsDeltasAndReturnsFull() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: [DONE]

                """;
        var streamFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        var server = sseServer(sse, 200, streamFlag);
        try {
            DeepSeekAiClient c = new DeepSeekAiClient("sk-test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "pro", "flash");
            StringBuilder deltas = new StringBuilder();
            String full = c.streamGenerate(
                    ContextPackage.simple("trading", null, "t", "用户问题", List.of(), "用户问题"),
                    null, deltas::append);
            assertEquals("你好", full, "完整文本 = 各 delta 拼接");
            assertEquals("你好", deltas.toString(), "onDelta 逐块回调（增量）");
            assertTrue(streamFlag.get(), "流式请求必须带 stream:true");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamGenerate_httpError_throws() {
        var streamFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        com.sun.net.httpserver.HttpServer server;
        try {
            server = sseServer("{\"error\":\"boom\"}", 500, streamFlag);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            DeepSeekAiClient c = new DeepSeekAiClient("sk-test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "pro", "flash");
            assertThrows(RuntimeException.class, () -> c.streamGenerate(
                    ContextPackage.simple("trading", null, "t", "问题", List.of(), "问题"),
                    null, s -> { }), "HTTP 非 200 应抛异常（调用方降级非流式）");
        } finally {
            server.stop(0);
        }
    }
}
