package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void modelFor_tradingCaseInsightUsesFlash() {
        // P3-案例1（2026-09-03）：案例 AI 理解（trading_case_insight）显式登记归 flash——
        // 归纳性文本生成不需旗舰推理，默认分支即 flash，此处锁死防误升 pro
        AiTraceContext.set("adai", null, null, "trading_case_insight");
        try {
            assertEquals("test-flash", client.modelFor(),
                    "案例 AI 理解（trading_case_insight）应走快模型 flash");
        } finally {
            AiTraceContext.restore(null);
        }
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

    // ── 2026-09-26（task-log「202 剩余」）：意图 / 生成不得回落到「分析记录 → 输出 JSON」的默认 system ──

    /** 本地 stub：捕获请求体，返回一段普通 content（非流式）。 */
    private static com.sun.net.httpserver.HttpServer captureServer(
            java.util.concurrent.atomic.AtomicReference<String> captured, String content) throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = ("{\"choices\":[{\"message\":{\"content\":\"" + content
                    + "\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (var os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();
        return server;
    }

    @Test
    void recognizeIntent_usesClassifierSystem_notAnalysisInstruction() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = captureServer(captured, "ask");
        try {
            DeepSeekAiClient c = new DeepSeekAiClient("sk-test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "pro", "flash");
            assertEquals("ask", c.recognizeIntent("明天天气怎么样？"));
            String body = captured.get();
            assertTrue(body.contains("意图分类器"), "意图识别必须带分类 system：" + body);
            assertFalse(body.contains("patterns"), "不得回落到「分析记录输出 JSON」默认 system：" + body);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generate_withoutCustomSystem_usesGenerationSystem_notAnalysisInstruction() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = captureServer(captured, "一段正文");
        try {
            DeepSeekAiClient c = new DeepSeekAiClient("sk-test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "pro", "flash");
            assertEquals("一段正文", c.generate(
                    ContextPackage.simple("trading", null, "t", "写一段复盘", List.of(), "写一段复盘"), null));
            String body = captured.get();
            assertTrue(body.contains("自然地写出"), "generate(null) 必须用生成语义 system：" + body);
            assertFalse(body.contains("patterns"), "不得回落到分析指令（要求输出 JSON）：" + body);
        } finally {
            server.stop(0);
        }
    }

    // ── RFC 20260929 批 1 ④：v1 单条 system + 动态参考垫底（legacy 多条 system 不变） ──

    @Test
    void chatRequest_v1_hasSingleSystem_andDynamicRefsOnLastUser() throws Exception {
        ContextPackage ctx = new ContextPackage("question", "身份摘要", "标题", "当前问题", List.of(),
                List.of("", "## 相关历史记录\n- [2026-09-01 10:00] (note) 旧事",
                        "## AI 对你的近期理解\n- 偏好：日线级别"),
                "prompt", java.time.LocalDateTime.now(),
                List.of(new ContextPackage.ChatMessage("user", "第一轮"),
                        new ContextPackage.ChatMessage("assistant", "回答一"),
                        new ContextPackage.ChatMessage("user", "当前问题")),
                "life(生活)", "稳定前缀\n当前日期：2026-09-29");

        var messages = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(client.buildChatRequestBody(ctx)).path("messages");

        assertEquals(4, messages.size(), "1 条 system + 3 条对话历史");
        int systemCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).path("role").asText())) systemCount++;
        }
        assertEquals(1, systemCount, "v1 只能有一条 system（兼容性 + 缓存前缀稳定）");

        String sys = messages.get(0).path("content").asText();
        assertTrue(sys.contains("稳定前缀"));
        assertFalse(sys.contains("相关历史记录"), "动态参考不得进 system（否则前缀每轮都变）");

        String last = messages.get(3).path("content").asText();
        assertTrue(last.contains("本次参考"), "动态参考应垫在当前问句之前");
        assertTrue(last.contains("相关历史记录"));
        assertTrue(last.contains("当前问题"), "当前问句必须保留在最后");
        assertEquals("user", messages.get(3).path("role").asText());
    }

    @Test
    void chatRequest_legacy_keepsMultipleSystems() throws Exception {
        ContextPackage ctx = new ContextPackage("question", "身份摘要", "标题", "当前问题", List.of(),
                List.of("## 当前会话对话历史\n- 用户：第一轮", "", ""),
                "处理一条新记录。\n\n身份摘要\n当前日期：2026-09-29\n场景：question\n\n当前记录：\n---\n内容\n---\n",
                java.time.LocalDateTime.now(),
                List.of(new ContextPackage.ChatMessage("user", "第一轮"),
                        new ContextPackage.ChatMessage("user", "当前问题")),
                "life(生活)");

        var messages = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(client.buildChatRequestBody(ctx)).path("messages");

        int systemCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).path("role").asText())) systemCount++;
        }
        assertTrue(systemCount >= 2, "legacy 仍是多条 system（角色 + 背景 + 上下文），回滚目标");
        assertEquals("第一轮", messages.get(systemCount).path("content").asText(),
                "legacy 的历史原样追加，不带「本次参考」");
    }


    @Test
    void v1_systemMessageIdenticalAcrossTurns_prefixIsCacheable() throws Exception {
        // 客户端侧：system 只由 stableSystem + 输出契约组成，**不随历史变化**（动态参考垫在最后一条 user）。
        // ⚠️ 对抗审查 P2-4：本用例两侧传入**同一个** stableSystem，因此它只证明「客户端不把历史塞进 system」；
        // **引擎产出**的 stableSystem 跨轮稳定性由
        // ContextAssemblyV1Test.stableSystem_fromEngine_isIdenticalAcrossTurns 覆盖。
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String sys3 = firstSystem(mapper, chatPkg(3));
        String sys5 = firstSystem(mapper, chatPkg(5));
        assertEquals(sys3, sys5, "v1：system 不随轮次变化（前缀可缓存）");
    }

    /** 造一次 CHAT 装配：轮次不同、动态参考也不同（模拟真实每轮变化），但稳定前缀相同。 */
    private ContextPackage chatPkg(int turns) {
        java.util.List<ContextPackage.ChatMessage> hist = new java.util.ArrayList<>();
        for (int i = 0; i < turns; i++) {
            hist.add(new ContextPackage.ChatMessage(i % 2 == 0 ? "user" : "assistant", "第" + (i + 1) + "轮"));
        }
        return new ContextPackage("question", "身份摘要", "标题", "当前问题", List.of(),
                List.of("", "## 相关历史记录\n- 旧事" + turns, "## AI 对你的近期理解\n- 偏好" + turns),
                "prompt", java.time.LocalDateTime.now(), hist, "life(生活)", "稳定前缀S\n当前日期：2026-09-29");
    }

    private String firstSystem(com.fasterxml.jackson.databind.ObjectMapper mapper, ContextPackage ctx) throws Exception {
        return mapper.readTree(client.buildChatRequestBody(ctx))
                .path("messages").get(0).path("content").asText();
    }

    // ── D4 路由口径（2026-09-30 用户拍板：A「说出来」＋ 宁可它多说一句） ──

    @Test
    void intentJudge_leansAsk_whenAmbiguous() {
        // 反向可验证：本批之前没有任何「信号不足怎么办」的指引——这正是那 47% 被判 log 的成因。
        assertTrue(DeepSeekAiClient.INTENT_SYSTEM_LEAN_ASK.contains("信号不足"),
                "倾向版 system 要给出兜底（信号不足 → ask）");
        // 审查 Q1：只写倾向不写 log 正例，模型会把明确记录也判 ask（费用与体感双升）→ 必须钉住正例
        assertTrue(DeepSeekAiClient.INTENT_SYSTEM_LEAN_ASK.contains("今天天气不错"),
                "倾向版 system 必须带 log 正例（防倾向过度）");
        assertTrue(DeepSeekAiClient.INTENT_SYSTEM_LEAN_ASK.contains("帮我记一下"),
                "并要区分「带记录指令」与「没带指令的情绪短句」");

        String user = DeepSeekAiClient.intentPromptLeanAsk("今天很开心");
        assertTrue(user.contains("信号不足") || user.contains("拿不准"), "user prompt 也要给兜底");
        assertTrue(user.contains("今天很开心"), "输入原样带入");
    }

    @Test
    void leanAskPrompt_isSeparateFromLegacy_mediaKeepsOldWording() {
        // 对抗审查 P1-1：倾向**只能**作用于文本入口，媒体入口（MediaController）继续走旧口径。
        // 本用例是那条隔离的守门人——谁把倾向塞进旧常量，它就红
        //（媒体链路一旦被波及：>500 字配文会从「可作 log 落盘」变成 400，图已落盘、记录未建）。
        assertFalse(DeepSeekAiClient.INTENT_SYSTEM.contains("信号不足"),
                "旧 system（媒体入口用）不得含倾向");
        assertFalse(DeepSeekAiClient.intentPrompt("随便一句话").contains("信号不足"),
                "旧 user prompt 同样不得含倾向");
        // 旧口径仍照常带回输入
        assertTrue(DeepSeekAiClient.intentPrompt("随便一句话").contains("随便一句话"));
    }

}
