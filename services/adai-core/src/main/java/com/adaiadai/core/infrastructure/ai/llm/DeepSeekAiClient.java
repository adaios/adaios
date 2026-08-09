package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.context.engine.ContextPackage.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * DeepSeekAiClient — DeepSeek API 实现的 AI 客户端。
 * <p>
 * 双模式：
 * <ul>
 *   <li>STATEMENT（conversationHistory 为空）: 分析模式，0.3 temp，JSON 输出</li>
 *   <li>QUESTION（conversationHistory 非空）: 对话模式，0.7 temp，多轮 messages</li>
 * </ul>
 */
@Component
public class DeepSeekAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final String apiKey;
    private final String apiUrl;
    private final String model;

    public DeepSeekAiClient(
            @Value("${DEEPSEEK_API_KEY:}") String apiKey,
            @Value("${DEEPSEEK_BASE_URL:https://api.deepseek.com}") String baseUrl,
            @Value("${adai.ai.model:deepseek-chat}") String model
    ) {
        this.httpClient = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))  // 不走系统代理（Privoxy）
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.apiKey = apiKey;
        this.apiUrl = baseUrl + "/v1/chat/completions";
        this.model = model;

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DEEPSEEK_API_KEY 未设置，DeepSeekAiClient 将无法工作");
        } else {
            log.info("DeepSeekAiClient 初始化 | url={} | model={}", this.apiUrl, this.model);
        }
    }

    @Override
    public AiUnderstanding understand(ContextPackage contextPackage) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("DEEPSEEK_API_KEY 未配置，无法调用 DeepSeek API");
            throw new RuntimeException("AI 未配置：缺少 API Key");
        }

        List<ChatMessage> history = contextPackage.conversationHistory();
        boolean isChat = history != null && !history.isEmpty();

        try {
            String requestBody = isChat
                    ? buildChatRequestBody(contextPackage)
                    : buildAnalysisRequestBody(contextPackage);

            log.info("[DeepSeek] 请求 model={} | 模式={} | tokens 预估={}",
                    model, isChat ? "CHAT" : "ANALYSIS", contextPackage.estimateTokens());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String rawResponse = parseChatCompletion(response.body());

            log.info("[DeepSeek] 响应 received | status={} | 长度={}",
                    response.statusCode(), rawResponse.length());
            return LlmResponseParser.parse(rawResponse);

        } catch (java.net.http.HttpConnectTimeoutException e) {
            log.error("DeepSeek API 连接超时", e);
            throw new RuntimeException("AI 连接超时，请稍后重试", e);
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("DeepSeek API 请求超时", e);
            throw new RuntimeException("AI 请求超时，请稍后重试", e);
        } catch (Exception e) {
            log.error("DeepSeek API 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String generate(ContextPackage contextPackage, String systemPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("DEEPSEEK_API_KEY 未配置，无法调用 DeepSeek API");
            throw new RuntimeException("AI 未配置：缺少 API Key");
        }
        try {
            // 生成语义：自定义 system 引导正文格式，无 JSON 摘要指令；0.7 temp + 2048 tokens 适合结构化正文
            String body = buildSimpleBody(contextPackage.prompt(), 2048, 0.7, systemPrompt);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String content = parseChatCompletion(response.body());
            log.info("[DeepSeek] generate 响应 | 长度={}", content.length());
            return content;
        } catch (java.net.http.HttpConnectTimeoutException e) {
            log.error("DeepSeek API 连接超时", e);
            throw new RuntimeException("AI 连接超时，请稍后重试", e);
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("DeepSeek API 请求超时", e);
            throw new RuntimeException("AI 请求超时，请稍后重试", e);
        } catch (Exception e) {
            log.error("DeepSeek API 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String recognizeIntent(String content) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("AI 未配置：缺少 API Key");
        }
        try {
            String prompt = """
                    判断以下用户输入是否需要 AI 回复。
                    需要回复（提问、命令、要求等） → 返回 ask
                    不需要回复（纯记录、日记、随想） → 返回 log
                    只需返回一个词：ask 或 log。
                    输入：%s
                    结果：""".formatted(content);
            String body = buildSimpleBody(prompt, 50, 0.3);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String result = parseChatCompletion(resp.body()).strip().toLowerCase();
            if (result.contains("ask")) return "ask";
            return "log";
        } catch (Exception e) {
            throw new RuntimeException("AI 意图识别失败: " + e.getMessage(), e);
        }
    }

    // ── Body 构建 ──

    /**
     * ANALYSIS 模式（STATEMENT 场景）：
     * 单条 user message + JSON 输出指令，0.3 temperature。
     * <p>
     * brief 场景例外：使用中文温暖问候的系统 prompt，0.7 temperature。
     */
    private String buildAnalysisRequestBody(ContextPackage ctx) throws Exception {
        if ("brief".equals(ctx.scene())) {
            return buildSimpleBody(ctx.prompt(), 1024, 0.7,
                    "你是阿呆的个人 AI 助手。用中文回复，语气温暖。生成温暖的问候语。不要输出 JSON。不要使用 emoji 和 unicode 转义码。");
        }
        return buildSimpleBody(ctx.prompt(), 1024, 0.3);
    }

    /**
     * CHAT 模式（QUESTION 场景）：
     * 多轮 messages（system + 对话历史），0.7 temperature，2048 tokens。
     * <p>
     * System message 包含身份摘要、日期、相关记录、记忆回读等背景信息。
     * 对话历史已经是完整的 user/assistant 轮次（包含当前用户输入），
     * 直接使用，不再额外追加 recordContent。
     */
    private String buildChatRequestBody(ContextPackage ctx) throws Exception {
        var root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 4096);
        root.put("temperature", 0.7);

        var messages = MAPPER.createArrayNode();

        // System prompt：身份 + 风格 + domain 标注
        var systemMsg = MAPPER.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是阿呆的个人 AI 助手。用中文回复，语气温暖。回复结束后在末尾另起一行输出 JSON（不要包裹 markdown 代码块）：\n"
            + "{\n"
            + "  \"summary\": \"3-5个词概括本次问答主题，避免人称代词，像标签一样简洁\",\n"
            + "  \"tags\": [\"标签1\", \"标签2\"],\n"
            + "  \"sentiment\": \"positive 或 negative 或 neutral\",\n"
            + "  \"domain\": \"life(生活)/trading(交易)/project(项目)\",\n"
            + "  \"actionable\": true 或 false,\n"
            + "  \"actionSuggestion\": \"需要后续操作写建议，否则写 null\"\n"
            + "}\n"
            + "不要使用 emoji 和 unicode 转义码。");
        messages.add(systemMsg);

        // 背景知识：作为单独的 system 消息（model 在 system prompt 之后读取，
        // 但不会把背景知识当成"自己要说的内容"）
        String background = buildBackground(ctx);
        if (background != null) {
            var bgMsg = MAPPER.createObjectNode();
            bgMsg.put("role", "system");
            bgMsg.put("content", background);
            messages.add(bgMsg);
        }

        // 组装上下文（全局领域、知识源、记忆等）：从 prompt 中提取不包含当前记录的上下文部分
        String context = buildContextFromPrompt(ctx);
        if (context != null) {
            var ctxMsg = MAPPER.createObjectNode();
            ctxMsg.put("role", "system");
            ctxMsg.put("content", context);
            messages.add(ctxMsg);
        }

        // 对话历史：完整的 user/assistant 轮次
        List<ChatMessage> history = ctx.conversationHistory();
        if (history.isEmpty()) {
            log.warn("chat 模式但没有历史记录，回退到普通 prompt");
            var fallbackMsg = MAPPER.createObjectNode();
            fallbackMsg.put("role", "user");
            fallbackMsg.put("content", ctx.recordContent());
            messages.add(fallbackMsg);
        } else {
            for (ChatMessage msg : history) {
                var histMsg = MAPPER.createObjectNode();
                histMsg.put("role", msg.role());
                histMsg.put("content", msg.content());
                messages.add(histMsg);
            }
        }

        root.set("messages", messages);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * 构建背景知识文本（单独作为一条 system 消息，不含 AI 角色指令）。
     */
    private String buildBackground(ContextPackage ctx) {
        StringBuilder sb = new StringBuilder();

        // 相关历史记录 + 记忆回读
        if (ctx.relatedRefs() != null && !ctx.relatedRefs().isEmpty()) {
            for (String ref : ctx.relatedRefs()) {
                if (ref != null && !ref.isBlank()) {
                    sb.append(ref.strip()).append("\n");
                }
            }
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 从 prompt 中提取上下文部分（身份、日期、全局领域、知识、记忆等），
     * 排除"当前记录"和最终指令，作为 system 消息供 CHAT 模式使用。
     */
    private String buildContextFromPrompt(ContextPackage ctx) {
        if (ctx.prompt() == null || ctx.prompt().isBlank()) return null;

        String prompt = ctx.prompt();
        // 截取到"当前记录："之前的部分，因为后面是本次用户输入和指令
        int cutoff = prompt.indexOf("\n当前记录：");
        if (cutoff < 0) return null;

        String contextPart = prompt.substring(0, cutoff).strip();
        return contextPart.isEmpty() ? null : contextPart;
    }

    /**
     * 从 identityRef 文本中提取用户称呼。
     */
    private String extractName(String identityRef) {
        if (identityRef == null || identityRef.isBlank()) return null;
        // 匹配 "- 称呼：xxx" 或 "用户身份摘要：xxx" 后面的名字
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("称呼[：:]\\s*(\\S+)").matcher(identityRef);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 简单的单条 prompt 请求体（用于 STATEMENT 分析和意图识别）。
     */
    private String buildSimpleBody(String prompt, int maxTokens, double temperature) throws Exception {
        return buildSimpleBody(prompt, maxTokens, temperature, null);
    }

    /**
     * 带自定义 system prompt 的单条请求体。
     */
    private String buildSimpleBody(String prompt, int maxTokens, double temperature, String systemContent) throws Exception {
        var root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);

        var messages = MAPPER.createArrayNode();

        var systemMsg = MAPPER.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent != null ? systemContent : """
                分析一条个人记录，输出JSON。summary用3-5个词简短概括，不要完整句子；insight用一句话客观概括，有信息增量，不要复述原文，避免人称代词。用tags数组标注关键词标签；用domain字段判定所属领域(life/trading/project之一)。
                如果记录揭示了用户的长期行为模式或明确偏好，请在patterns/preferences数组中输出，每项包含content和confidence(0-1)。
                只输出JSON，不要包裹markdown。
                """.strip());
        messages.add(systemMsg);

        var userMsg = MAPPER.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);

        root.set("messages", messages);
        return MAPPER.writeValueAsString(root);
    }

    private String parseChatCompletion(String responseBody) throws Exception {
        JsonNode root = MAPPER.readTree(responseBody);

        // 检查 API 错误
        if (root.has("error")) {
            String errorMsg = root.get("error").path("message").asText("未知错误");
            log.error("DeepSeek API 返回错误: {}", errorMsg);
            throw new RuntimeException("DeepSeek API 错误: " + errorMsg);
        }

        // 提取 content
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("DeepSeek API 返回异常: 无 choices");
        }

        String content = choices.get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new RuntimeException("DeepSeek API 返回空内容");
        }

        return content;
    }
}
