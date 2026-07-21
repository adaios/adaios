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

        // System prompt：背景知识（不包含 JSON 输出指令）
        String systemContent = buildChatSystemPrompt(ctx);
        var systemMsg = MAPPER.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent);
        messages.add(systemMsg);

        // 对话历史：完整的 user/assistant 轮次（已从 card 文件拉取，包含当前用户输入）
        List<ChatMessage> history = ctx.conversationHistory();
        if (history.isEmpty()) {
            // 保险：如果没有历史记录，回退到 ANALYSIS 模式的 prompt
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
     * 构建对话模式的 System Prompt。
     * 包含身份摘要、日期、标签关联记录和记忆回读，但不包含 JSON 输出指令。
     * 让 AI 在末尾自然附带 JSON 标签。
     */
    private String buildChatSystemPrompt(ContextPackage ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是用户的个人 AI 助手阿呆。请用自然、简洁的中文与用户对话。\n\n");

        // 身份摘要
        if (ctx.identityRef() != null && !ctx.identityRef().isBlank()
                && !ctx.identityRef().contains("未配置")) {
            sb.append(ctx.identityRef()).append("\n");
        }

        // 当前场景
        sb.append("当前场景：").append(ctx.scene()).append("\n");

        // 相关历史记录（标签关联 + 记忆回读）
        if (ctx.relatedRefs() != null && !ctx.relatedRefs().isEmpty()) {
            for (String ref : ctx.relatedRefs()) {
                if (ref != null && !ref.isBlank()) {
                    sb.append("\n").append(ref.strip()).append("\n");
                }
            }
        }

        // 对话末尾附 JSON 标签
        sb.append("\n回答结束后，在末尾另起一行输出 JSON（不要包裹 markdown 代码块）：\n")
                .append("{\n")
                .append("  \"tags\": [\"标签1\", \"标签2\"],\n")
                .append("  \"sentiment\": \"positive 或 negative 或 neutral\",\n")
                .append("  \"actionable\": true 或 false,\n")
                .append("  \"actionSuggestion\": \"需要后续操作写建议，否则写 null\"\n")
                .append("}\n");

        return sb.toString();
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
                你是一个记录分析助手。用户输入一条记录，你需要分析它并输出 JSON。
                只输出 JSON，不要包裹在 markdown 代码块中。
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
