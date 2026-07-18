package com.adaiadai.core.infrastructure.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * LlmResponseParser — 从 LLM 回复中解析出 AiUnderstanding。
 * <p>
 * LLM 回复应该是 JSON 格式，包含 summary、tags、sentiment、actionable、actionSuggestion 字段。
 * 即使回复格式有偏差也能降级处理。
 */
public class LlmResponseParser {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmResponseParser() {}

    /**
     * 从 LLM 回复文本解析出 AiUnderstanding。
     *
     * @param rawResponse LLM 的完整回复文本
     * @return 解析后的理解结果
     */
    public static AiUnderstanding parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return fallback("未收到 AI 回复");
        }

        // 尝试从回复中提取 JSON（LLM 可能包裹在 markdown 代码块中）
        String jsonStr = extractJson(rawResponse);
        if (jsonStr == null) {
            log.warn("LLM 回复中未找到 JSON，使用降级解析");
            return parseAsPlainText(rawResponse);
        }

        try {
            JsonNode root = MAPPER.readTree(jsonStr);

            String summary = getTextOrDefault(root, "summary", "无摘要");
            List<String> tags = getTags(root, "tags");
            String sentiment = getTextOrDefault(root, "sentiment", "neutral");
            boolean actionable = root.has("actionable") && root.get("actionable").asBoolean(false);
            String suggestion = root.has("actionSuggestion") && !root.get("actionSuggestion").isNull()
                    ? root.get("actionSuggestion").asText()
                    : null;

            return new AiUnderstanding(summary, tags, sentiment, actionable, suggestion, rawResponse);

        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return parseAsPlainText(rawResponse);
        }
    }

    // ── 内部方法 ──

    private static String extractJson(String text) {
        // 尝试直接解析
        String trimmed = text.strip();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        // 尝试从 ```json ... ``` 中提取
        int jsonStart = text.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = jsonStart + 7;
            int jsonEnd = text.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return text.substring(contentStart, jsonEnd).strip();
            }
        }
        // 尝试从 ``` ... ``` 中提取
        int codeStart = text.indexOf("```");
        if (codeStart >= 0) {
            int contentStart = text.indexOf('\n', codeStart) + 1;
            int codeEnd = text.indexOf("```", contentStart);
            if (codeEnd > contentStart) {
                String candidate = text.substring(contentStart, codeEnd).strip();
                if (candidate.startsWith("{")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static AiUnderstanding parseAsPlainText(String text) {
        // 非 JSON 回复：截取前 200 字符作为摘要
        String summary = text.length() > 200 ? text.substring(0, 200) + "…" : text;
        return new AiUnderstanding(
                summary.strip(),
                List.of(),
                "neutral",
                false,
                null,
                text
        );
    }

    private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : defaultValue;
    }

    private static List<String> getTags(JsonNode node, String field) {
        JsonNode tags = node.get(field);
        if (tags == null || !tags.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode tag : tags) {
            if (tag.isTextual() && !tag.asText().isBlank()) {
                result.add(tag.asText());
            }
        }
        return result;
    }

    private static AiUnderstanding fallback(String message) {
        return new AiUnderstanding(message, List.of(), "unknown", false, null, message);
    }
}
