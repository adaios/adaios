package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 通过 DeepSeek 兼容的 OpenAI API 格式调用模型。
 * API Key 从环境变量 {@code DEEPSEEK_API_KEY} 读取。
 * <p>
 * 当 {@code adai.ai.provider=deepseek} 时激活。
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

        try {
            String requestBody = buildRequestBody(contextPackage.prompt());
            log.info("[DeepSeek] 请求 model={} | tokens 预估={}", model, contextPackage.estimateTokens());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String rawResponse = parseChatCompletion(response.body());

            log.info("[DeepSeek] 响应 received | status={}", response.statusCode());
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
            String body = buildRequestBody(prompt, 50);
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

    private String buildRequestBody(String prompt) throws Exception {
        return buildRequestBody(prompt, 1024);
    }

    private String buildRequestBody(String prompt, int maxTokens) throws Exception {
        var requestBody = MAPPER.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", 0.3);

        var messages = MAPPER.createArrayNode();
        var systemMsg = MAPPER.createObjectNode();
        systemMsg.put("role", "system");

        // 场景感知：如果 prompt 要求直接回答问题，不强制 JSON 输出
        boolean isQuestion = prompt.startsWith("你是一个个人 AI 助手。请直接回答");
        if (isQuestion) {
            systemMsg.put("content", "你是一个个人 AI 助手。请直接回答用户的问题，用自然语言，简洁准确。");
            requestBody.put("max_tokens", 512);
        } else {
            systemMsg.put("content", """
                    你是一个个人 AI 助手。用户输入一条记录，你需要分析它并输出 JSON。
                    只输出 JSON，不要包裹在 markdown 代码块中。
                    """.strip());
        }
        messages.add(systemMsg);

        var userMsg = MAPPER.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);

        requestBody.set("messages", messages);
        return MAPPER.writeValueAsString(requestBody);
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
