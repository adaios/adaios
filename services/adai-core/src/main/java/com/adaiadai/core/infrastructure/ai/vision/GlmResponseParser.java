package com.adaiadai.core.infrastructure.ai.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * GlmResponseParser — 从 GLM 视觉模型回复解析 ImageUnderstanding。
 * <p>
 * 期望回复为 JSON（summary/category/extractedText/tags），
 * 即使被 markdown 代码块包裹或混入杂文本也能提取，解析失败降级为原文。
 */
public class GlmResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GlmResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GlmResponseParser() {}

    /**
     * 从 GLM 视觉回复解析结构化理解。
     *
     * @param rawResponse 模型回复全文（JSON 或自然语言混合）
     * @return 解析后的图片理解
     */
    public static ImageUnderstanding parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return fallback("图片理解失败：未收到 AI 回复");
        }
        String jsonStr = extractJson(rawResponse);
        if (jsonStr == null) {
            log.warn("GLM 回复中未找到 JSON，降级为原文");
            return fallback(rawResponse.strip());
        }
        try {
            JsonNode root = MAPPER.readTree(jsonStr);
            String summary = textOr(root, "summary", "图片记录");
            String category = textOr(root, "category", "photo");
            String extractedText = textOr(root, "extractedText", "");
            List<String> tags = tagsOf(root, "tags");
            return new ImageUnderstanding(summary, category, extractedText, tags);
        } catch (Exception e) {
            log.warn("GLM 回复 JSON 解析失败: {}", e.getMessage());
            return fallback(rawResponse.strip());
        }
    }

    // ── 内部方法 ──

    private static ImageUnderstanding fallback(String text) {
        String s = text.length() > 100 ? text.substring(0, 100) + "…" : text;
        return new ImageUnderstanding(s, "photo", "", List.of());
    }

    /**
     * 从 LLM 回复中提取 JSON（支持整段 JSON / ```json 代码块 / 末尾 JSON 混合）。
     */
    private static String extractJson(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
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
        int lastBrace = trimmed.lastIndexOf("{");
        if (lastBrace >= 0) {
            String candidate = trimmed.substring(lastBrace).strip();
            if (candidate.endsWith("}")) {
                try {
                    MAPPER.readTree(candidate);
                    return candidate;
                } catch (Exception ignored) {
                    // 解析失败，不是有效 JSON
                }
            }
        }
        return null;
    }

    private static String textOr(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull() && !v.asText().isBlank()) ? v.asText().strip() : defaultValue;
    }

    private static List<String> tagsOf(JsonNode node, String field) {
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) return List.of();
        List<String> tags = new ArrayList<>();
        for (JsonNode t : arr) {
            if (t.isTextual() && !t.asText().isBlank()) {
                tags.add(t.asText());
            }
        }
        return tags;
    }
}
