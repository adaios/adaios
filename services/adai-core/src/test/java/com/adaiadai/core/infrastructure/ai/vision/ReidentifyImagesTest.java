package com.adaiadai.core.infrastructure.ai.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 一次性重识别工具（P0-1 数据清洗，2026-08-18）：对生产 7 张脏图重新调用 GLM，
 * 打印原始响应 + 修复后解析结果。跑完即删。
 * <p>
 * 依赖真实 GLM API（GLM_API_KEY 环境变量）+ /tmp/glm-reidentify 图片目录——
 * 无 key 时跳过（2026-08-18：改为 assumeTrue，避免全量测试在无 key 环境红）。
 */
class ReidentifyImagesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reidentifyAll() throws Exception {
        String apiKey = System.getenv("GLM_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "GLM_API_KEY 未设置——跳过真实视觉调用");
        HttpClient http = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        String prompt = """
                你是阿呆的个人 AI 助手。分析这张图片，只返回 JSON（不要 markdown 代码块，不要多余文字）：
                {
                  "summary": "一句话概括图片内容（不超过 20 字，像标签一样简洁）",
                  "category": "图片类别：trading(持仓/行情截图) / whiteboard(白板/手写笔记) / invoice(单据/发票) / memo(备忘录/便签) / photo(其他照片)",
                  "extractedText": "图片中的文字内容（OCR），无文字则返回空字符串",
                  "tags": ["标签1", "标签2"]
                }
                用户备注：总结下今日操作
                """;

        Path dir = Path.of("/tmp/glm-reidentify");
        try (var stream = Files.list(dir)) {
            for (Path img : stream.sorted().toList()) {
                if (!img.toString().endsWith(".png")) continue;
                String id = img.getFileName().toString().replace(".png", "");
                byte[] bytes = Files.readAllBytes(img);
                String b64 = Base64.getEncoder().encodeToString(bytes);

                ObjectNode root = MAPPER.createObjectNode();
                root.put("model", "glm-4.1v-thinking-flash");
                root.put("max_tokens", 1024);
                root.put("temperature", 0.3);
                ArrayNode messages = root.putArray("messages");
                ObjectNode user = messages.addObject();
                user.put("role", "user");
                ArrayNode content = user.putArray("content");
                ObjectNode image = content.addObject();
                image.put("type", "image_url");
                image.putObject("image_url").put("url", "data:image/png;base64," + b64);
                content.addObject().put("type", "text").put("text", prompt);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root)))
                        .timeout(Duration.ofSeconds(60))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                String raw = "";
                if (resp.statusCode() == 200) {
                    raw = MAPPER.readTree(resp.body()).path("choices").path(0).path("message").path("content").asText();
                } else {
                    raw = "HTTP " + resp.statusCode() + ": " + resp.body();
                }
                Files.writeString(Path.of("/tmp/glm-reidentify/results.txt"),
                        "RAW|" + id + "|" + raw.replace("\n", "\\n") + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                ImageUnderstanding u = GlmResponseParser.parse(raw);
                Files.writeString(Path.of("/tmp/glm-reidentify/results.txt"),
                        "RESULT|" + id
                                + "|summary=" + u.summary()
                                + "|category=" + u.category()
                                + "|extractedText=" + u.extractedText()
                                + "|tags=" + u.tags() + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        }
    }
}
