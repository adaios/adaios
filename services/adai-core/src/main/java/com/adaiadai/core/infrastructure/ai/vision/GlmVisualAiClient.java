package com.adaiadai.core.infrastructure.ai.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * GlmVisualAiClient — 智谱 GLM 视觉模型实现。
 * <p>
 * OpenAI 兼容端点 {@code /chat/completions}，图片以 base64 data URL 传入，
 * 文本指令要求返回 JSON（summary/category/extractedText/tags）。
 * <p>
 * 免费模型：GLM-4.1V-Thinking-Flash（支持 base64；Thinking 输出 <think>/<answer> 壳，GlmResponseParser 已剥壳）。
 * API Key 在 .env 配置 {@code GLM_API_KEY}。
 */
@Component
public class GlmVisualAiClient implements VisualAiClient {

    private static final Logger log = LoggerFactory.getLogger(GlmVisualAiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String PROMPT = """
            你是阿呆的个人 AI 助手。分析这张图片，只返回 JSON（不要 markdown 代码块，不要多余文字）：
            {
              "summary": "一句话概括图片内容（不超过 20 字，像标签一样简洁）",
              "category": "图片类别：trading(持仓/行情截图) / whiteboard(白板/手写笔记) / invoice(单据/发票) / memo(备忘录/便签) / photo(其他照片)",
              "extractedText": "图片中的文字内容（OCR），无文字则返回空字符串",
              "tags": ["标签1", "标签2"]
            }
            """;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String apiUrl;
    private final String model;

    public GlmVisualAiClient(
            @Value("${GLM_API_KEY:}") String apiKey,
            @Value("${GLM_BASE_URL:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
            @Value("${adai.ai.vision.model:glm-4.1v-thinking-flash}") String model
    ) {
        this.httpClient = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))  // 不走系统代理（Privoxy）
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.apiKey = apiKey;
        this.apiUrl = baseUrl + "/chat/completions";
        this.model = model;

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GLM_API_KEY 未设置，GlmVisualAiClient 将无法工作");
        } else {
            log.info("GlmVisualAiClient 初始化 | url={} | model={}", this.apiUrl, this.model);
        }
    }

    @Override
    public ImageUnderstanding understand(ImageRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("GLM_API_KEY 未配置，无法调用视觉模型");
            throw new RuntimeException("视觉 AI 未配置：缺少 GLM_API_KEY");
        }
        try {
            String requestBody = buildRequestBody(request);
            log.info("[GLM-Vision] 请求 model={} | caption={}",
                    model, request.caption() != null ? request.caption() : "");

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("GLM API 返回 " + response.statusCode() + ": "
                        + truncate(response.body()));
            }
            String answer = parseAnswer(response.body());
            return GlmResponseParser.parse(answer);
        } catch (Exception e) {
            log.error("GLM 视觉理解失败: {}", e.getMessage(), e);
            throw new RuntimeException("视觉理解失败: " + e.getMessage(), e);
        }
    }

    // ── 请求/响应 ──

    private String buildRequestBody(ImageRequest request) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 1024);
        root.put("temperature", 0.3);

        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");

        // 图片（base64 data URL）
        ObjectNode image = content.addObject();
        image.put("type", "image_url");
        String dataUrl = "data:" + request.contentType() + ";base64," + request.base64Image();
        image.putObject("image_url").put("url", dataUrl);

        // 文本指令 + 可选用户备注
        String textPrompt = PROMPT;
        if (request.caption() != null && !request.caption().isBlank()) {
            textPrompt = textPrompt + "\n用户备注：" + request.caption();
        }
        content.addObject().put("type", "text").put("text", textPrompt);

        return MAPPER.writeValueAsString(root);
    }

    private String parseAnswer(String responseBody) throws Exception {
        JsonNode root = MAPPER.readTree(responseBody);
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
