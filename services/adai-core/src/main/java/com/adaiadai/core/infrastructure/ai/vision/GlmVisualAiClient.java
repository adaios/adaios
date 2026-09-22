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
import java.util.List;

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

    /**
     * 多图一次投递的理解 prompt（图文一体）：把 N 张图当**同一件事**综合理解，
     * 产出一段口语化综合总结 + 按序合并的 OCR 文本。
     */
    private static final String MULTI_PROMPT = """
            你是阿呆的个人 AI 助手。用户一次发来多张图片（按顺序给出），请把它们当作**同一件事**
            综合理解，只返回 JSON（不要 markdown 代码块，不要多余文字）：
            {
              "summary": "综合这几张图的一段话（2~3 句、口语化、像对朋友复述你看到了什么，不要罗列成标题）",
              "category": "图片类别：trading(持仓/行情截图) / whiteboard(白板/手写笔记) / invoice(单据/发票) / memo(备忘录/便签) / photo(其他照片)",
              "extractedText": "按顺序提取每张图片里的文字，每张前标注【第N张】；无文字则返回空字符串",
              "tags": ["标签1", "标签2"]
            }
            """;

    /** 图片追问 prompt（L4 图片问答）：自然语言回答，不要求 JSON。 */
    private static final String ASK_PROMPT = """
            你是阿呆的个人 AI 助手。用户就这张图片提问，请直接简洁准确地回答。
            不要输出 JSON，不要加多余前缀，直接给答案。
            """;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    /**
     * 输出预算（2026-08-27 模型切换配置化）：thinking 模型（glm-4.1v-thinking-flash）要 2048
     * 才有 answer 空间（P0-1：1024 被 think 吃光）；flash 模型（glm-4v-flash）上限仅 1024——
     * 2048 直接 400（code 1210）。按模型配 `adai.ai.vision.max-tokens`，默认 2048 保 thinking 兼容。
     */
    private final int maxTokens;

    public GlmVisualAiClient(
            @Value("${GLM_API_KEY:}") String apiKey,
            @Value("${GLM_BASE_URL:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
            @Value("${adai.ai.vision.model:glm-4.1v-thinking-flash}") String model,
            @Value("${adai.ai.vision.max-tokens:2048}") int maxTokens
    ) {
        this.httpClient = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))  // 不走系统代理（Privoxy）
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.apiKey = apiKey;
        this.apiUrl = baseUrl + "/chat/completions";
        this.model = model;
        this.maxTokens = maxTokens;

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GLM_API_KEY 未设置，GlmVisualAiClient 将无法工作");
        } else {
            log.info("GlmVisualAiClient 初始化 | url={} | model={} | maxTokens={}", this.apiUrl, this.model, this.maxTokens);
        }
    }

    @Override
    public ImageUnderstanding understand(ImageRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("GLM_API_KEY 未配置，无法调用视觉模型");
            throw new RuntimeException("视觉 AI 未配置：缺少 GLM_API_KEY");
        }
        try {
            String textPrompt = PROMPT;
            if (request.caption() != null && !request.caption().isBlank()) {
                textPrompt = textPrompt + "\n用户备注：" + request.caption();
            }
            log.info("[GLM-Vision] 请求 model={} | caption={}",
                    model, request.caption() != null ? request.caption() : "");
            String answer = sendAndParse(buildRequestBody(request, textPrompt));
            return GlmResponseParser.parse(answer);
        } catch (Exception e) {
            log.error("GLM 视觉理解失败: {}", e.getMessage(), e);
            throw new RuntimeException("视觉理解失败: " + e.getMessage(), e);
        }
    }

    /**
     * 多图一次理解（图文一体入账路径）：N 张图**一次**发给视觉模型综合理解，
     * 返回结构化结果（summary = 综合总结、extractedText = 按序合并 OCR）。
     * <p>
     * 相比「逐张 understand」：一次调用、天然组成上下文、总耗时从 N×~15s 降到 ~15s（P1-多图2 的串行超时根因之一）。
     */
    @Override
    public ImageUnderstanding understandMulti(List<ImageRequest> requests, String caption) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("图片不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.error("GLM_API_KEY 未配置，无法调用视觉模型");
            throw new RuntimeException("视觉 AI 未配置：缺少 GLM_API_KEY");
        }
        try {
            String textPrompt = MULTI_PROMPT;
            if (caption != null && !caption.isBlank()) {
                textPrompt = textPrompt + "\n用户备注：" + caption;
            }
            log.info("[GLM-Vision-Multi] 请求 model={} | images={} | caption={}",
                    model, requests.size(), caption != null ? caption : "");
            String answer = sendAndParse(buildMultiRequestBody(requests, textPrompt));
            return GlmResponseParser.parse(answer);
        } catch (Exception e) {
            log.error("GLM 多图理解失败: {}", e.getMessage(), e);
            throw new RuntimeException("多图理解失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String ask(ImageRequest request, String question) {
        return ask(request, question, null);
    }

    @Override
    public String ask(ImageRequest request, String question, Integer maxTokensOverride) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("GLM_API_KEY 未配置，无法调用视觉模型");
            throw new RuntimeException("视觉 AI 未配置：缺少 GLM_API_KEY");
        }
        try {
            String textPrompt = ASK_PROMPT + "\n用户问题：" + (question == null ? "" : question);
            log.info("[GLM-Vision-Ask] 请求 model={} | question={}",
                    model, truncate(question));
            String answer = sendAndParse(buildRequestBody(request, textPrompt, maxTokensOverride));
            String cleaned = GlmResponseParser.extractAnswer(answer);
            return (cleaned == null || cleaned.isBlank()) ? "（图片问答未获得有效回复）" : cleaned.strip();
        } catch (Exception e) {
            log.error("GLM 视觉追问失败: {}", e.getMessage(), e);
            throw new RuntimeException("图片追问失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String askMulti(List<ImageRequest> requests, String question) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("GLM_API_KEY 未配置，无法调用视觉模型");
            throw new RuntimeException("视觉 AI 未配置：缺少 GLM_API_KEY");
        }
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("图片不能为空");
        }
        try {
            String textPrompt = ASK_PROMPT + "\n用户问题：" + (question == null ? "" : question);
            log.info("[GLM-Vision-AskMulti] 请求 model={} | images={} | question={}",
                    model, requests.size(), truncate(question));
            String answer = sendAndParse(buildMultiRequestBody(requests, textPrompt));
            String cleaned = GlmResponseParser.extractAnswer(answer);
            return (cleaned == null || cleaned.isBlank()) ? "（图片问答未获得有效回复）" : cleaned.strip();
        } catch (Exception e) {
            // 多图请求失败（如模型多图兼容问题）→ 降级单图首张问答，不阻塞功能
            log.warn("GLM 多图问答失败，降级单图首张 | images={} | {}", requests.size(), e.getMessage());
            if (requests.size() > 1) {
                try {
                    return ask(requests.get(0), question);
                } catch (Exception e2) {
                    throw new RuntimeException("图片追问失败: " + e2.getMessage(), e2);
                }
            }
            throw new RuntimeException("图片追问失败: " + e.getMessage(), e);
        }
    }

    // ── 请求/响应 ──

    /**
     * 本次调用的输出上限：按调用覆盖优先（P2-learn25），其次全局配置 {@code adai.ai.vision.max-tokens}。
     *
     * @param override 调用方给的上限；null 或非正数视为未指定
     */
    private int effectiveMaxTokens(Integer override) {
        return (override != null && override > 0) ? override : maxTokens;
    }

    private String buildRequestBody(ImageRequest request, String textPrompt) throws Exception {
        return buildRequestBody(request, textPrompt, null);
    }

    /**
     * @param maxTokensOverride 本次调用的输出上限（P2-learn25）；null/非正数 = 用全局配置
     */
    private String buildRequestBody(ImageRequest request, String textPrompt, Integer maxTokensOverride) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        // P0-1（2026-08-18）：thinking 模型思考过程长，1024 被 think 吃光后 answer 无输出
        // （生产 5/7 张图仅返回 <think> 无 answer）。调到 2048 给 answer 留出空间。
        // 2026-08-27：按模型配置（flash 上限 1024，配置 ADAI_AI_VISION_MAX_TOKENS）。
        // 2026-09-16（P2-learn25）：允许调用方按次覆盖——长书页提取不再被全局上限静默截断。
        root.put("max_tokens", effectiveMaxTokens(maxTokensOverride));
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

        // 文本指令（理解 prompt 或追问 prompt）
        content.addObject().put("type", "text").put("text", textPrompt);

        return MAPPER.writeValueAsString(root);
    }

    /**
     * 多图请求体：一个 content 数组多个 {@code image_url} + 文本指令（Phase 1 带图 ask）。
     * OpenAI 兼容格式，GLM-4V 系列支持同消息多图。
     */
    private String buildMultiRequestBody(List<ImageRequest> requests, String textPrompt) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", 0.3);

        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");

        // 多张图片（base64 data URL，一次请求全部传入）
        for (ImageRequest r : requests) {
            ObjectNode image = content.addObject();
            image.put("type", "image_url");
            String dataUrl = "data:" + r.contentType() + ";base64," + r.base64Image();
            image.putObject("image_url").put("url", dataUrl);
        }

        // 文本指令（追问 prompt）
        content.addObject().put("type", "text").put("text", textPrompt);

        return MAPPER.writeValueAsString(root);
    }

    private String sendAndParse(String requestBody) throws Exception {
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
        JsonNode root = MAPPER.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
