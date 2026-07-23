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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "adai.ai.provider", havingValue = "deepseek")
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
            return new AiUnderstanding("AI 未配置：缺少 API Key", List.of(), "unknown", false, null, "");
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
            return new AiUnderstanding("AI 连接超时，请稍后重试", List.of(), "unknown", false, null, "");
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("DeepSeek API 请求超时", e);
            return new AiUnderstanding("AI 请求超时，请稍后重试", List.of(), "unknown", false, null, "");
        } catch (Exception e) {
            log.error("DeepSeek API 调用失败: {}", e.getMessage(), e);
            return new AiUnderstanding("AI 调用失败: " + e.getMessage(), List.of(), "unknown", false, null, "");
        }
    }

    @Override
    public String recognizeIntent(String content) {
        if (apiKey == null || apiKey.isBlank()) return "log";
        try {
            String prompt = """
                    分析以下用户输入的意图，只需返回一个词：log（纯记录）、question（提问）、decision（决策求助）。
                    输入：%s
                    意图：""".formatted(content);
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
            if (result.contains("question")) return "question";
            if (result.contains("decision")) return "decision";
            return "log";
        } catch (Exception e) {
            log.warn("意图识别 AI 兜底失败: {}", e.getMessage());
            return "log";
        }
    }

    // ── Body 构建 ──

    /**
     * ANALYSIS 模式（STATEMENT 场景）：
     * 单条 user message + JSON 输出指令，0.3 temperature。
     */
    private String buildAnalysisRequestBody(ContextPackage ctx) throws Exception {
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
        root.put("max_tokens", 2048);
        root.put("temperature", 0.7);

        var messages = MAPPER.createArrayNode();

        // System prompt：仅身份声明（一行）
        var systemMsg = MAPPER.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是阿呆的个人 AI 助手。");
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

        // 称呼
        String name = extractName(ctx.identityRef());
        if (name != null) {
            sb.append("用户称呼：").append(name).append("\n\n");
        }

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
        var root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);

        var messages = MAPPER.createArrayNode();

        var systemMsg = MAPPER.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", """
                分析一条个人记录，输出JSON。摘要用简洁的事实描述，不加主语（不要用"用户"或"你"）。
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
