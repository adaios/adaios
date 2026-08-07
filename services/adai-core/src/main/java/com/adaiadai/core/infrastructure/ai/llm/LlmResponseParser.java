package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.kernel.memory.MemoryPattern;
import com.adaiadai.core.kernel.memory.MemoryPreference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * LlmResponseParser — 从 LLM 回复中解析出 AiUnderstanding。
 * <p>
 * LLM 回复应该是 JSON 格式，包含 summary、insight、tags、sentiment、actionable、actionSuggestion 字段。
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

        // 尝试从回复中提取 JSON（LLM 可能包裹在 markdown 代码块中或放在文本末尾）
        String jsonStr = extractJson(rawResponse);
        if (jsonStr == null) {
            log.warn("LLM 回复中未找到 JSON，使用降级解析");
            return parseAsPlainText(rawResponse);
        }

        try {
            JsonNode root = MAPPER.readTree(jsonStr);

            // 优先取 JSON 中的 summary；如果没有（Question 模式），从自然文本中提取
            // 两种路径都执行 decodeUnicodeEscapes，处理 AI 回复中可能夹带的 unicode 转义码
            String summary;
            if (root.has("summary") && !root.get("summary").isNull()
                    && !root.get("summary").asText().isBlank()
                    && !"无摘要".equals(root.get("summary").asText())) {
                summary = decodeUnicodeEscapes(root.get("summary").asText());
            } else {
                summary = extractTextBeforeJson(rawResponse, jsonStr);
            }

            String domain = decodeUnicodeEscapes(getTextOrDefault(root, "domain", "life"));
            List<String> tags = getTags(root, "tags");
            String sentiment = getTextOrDefault(root, "sentiment", "neutral");
            boolean actionable = root.has("actionable") && root.get("actionable").asBoolean(false);
            String suggestion = root.has("actionSuggestion") && !root.get("actionSuggestion").isNull()
                    ? root.get("actionSuggestion").asText()
                    : null;

            // insight：可选字段，QUESTION 场景可能没有
            String insight = getTextOrNull(root, "insight");

            // patterns / preferences：可选字段，STATEMENT 场景可能提炼出
            List<MemoryPattern> patterns = parsePatternArray(root, "patterns");
            List<MemoryPreference> preferences = parsePreferenceArray(root, "preferences");

            return new AiUnderstanding(summary, insight, patterns, preferences,
                    tags, sentiment, domain, actionable, suggestion, rawResponse);

        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return parseAsPlainText(rawResponse);
        }
    }

    // ── 内部方法 ──

    private static String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();

        // 1. 如果整段文本就是 JSON
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        // 2. 从 ```json ... ``` 中提取
        int jsonStart = text.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = jsonStart + 7;
            int jsonEnd = text.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return text.substring(contentStart, jsonEnd).strip();
            }
        }

        // 3. 从 ``` ... ``` 中提取
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

        // 4. 查找末尾 JSON 块：自然语言 + JSON 混合输出
        int lastBrace = trimmed.lastIndexOf("{\n");
        if (lastBrace < 0) lastBrace = trimmed.lastIndexOf("{");
        if (lastBrace >= 0) {
            String candidate = trimmed.substring(lastBrace).strip();
            // 粗略校验：以 } 结尾且包含 tags 字段
            if (candidate.endsWith("}") && (candidate.contains("\"tags\"") || candidate.contains("\"domain\""))) {
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

    /**
     * 从自然文本 + JSON 的混合回复中提取文本部分。
     * AI 回复最大 4096 tokens（~3000 汉字），此处用 4000 字符作为安全上限，
     * 防止 LLM 输出意外过长（几乎不可能超过）。
     */
    private static String extractTextBeforeJson(String fullText, String jsonStr) {
        int idx = fullText.indexOf(jsonStr);
        if (idx <= 0) {
            return decodeUnicodeEscapes(fullText.strip());
        }
        String before = fullText.substring(0, idx).strip();
        if (before.isBlank()) {
            return decodeUnicodeEscapes(fullText.strip());
        }
        // 4000 字符安全上限（对应 max_tokens=4096）
        String text = before.length() > 4000 ? before.substring(0, 4000) + "…" : before;
        return decodeUnicodeEscapes(text);
    }

    /**
     * 从 LLM 回复中提取自然语言部分（剥离末尾 JSON 元数据）。
     * <p>
     * 用于写 card 对话 turn 与返回前端实时显示——与刷新后 parseTurns 只读 AI：第一行保持一致
     * （REVIEW #13/#11：card 文件混入 AI 原始 JSON 块 + 刷新后对话内容"减少"）。
     * 整段都是 JSON（无自然语言）时返回空串，由调用方回退 summary。
     */
    public static String extractNaturalText(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) return rawResponse;
        String jsonStr = extractJson(rawResponse);
        if (jsonStr == null) return rawResponse.strip();
        int idx = rawResponse.indexOf(jsonStr);
        if (idx <= 0) return ""; // 整段 JSON（含代码块围栏内），无自然语言
        String before = rawResponse.substring(0, idx)
                // 剥离 markdown 代码块围栏开标记（```json / ```），它属于元数据而非对话内容
                .replaceFirst("```(?:json)?\\s*$", "")
                .strip();
        return before.length() > 4000 ? before.substring(0, 4000) + "…" : before;
    }

    private static AiUnderstanding parseAsPlainText(String text) {
        // 非 JSON 回复：截取前 200 字符作为摘要，并解码 \\uXXXX 转义序列
        String decoded = decodeUnicodeEscapes(text);
        String summary = decoded.length() > 200 ? decoded.substring(0, 200) + "…" : decoded;
        return new AiUnderstanding(
                summary.strip(),
                null, null, null,
                List.of(),
                "neutral",
                "life",
                false,
                null,
                text
        );
    }

    /**
     * Decode escaped unicode sequences like backslash-u-4-hex-digits into actual characters.
     * Handles both single codes and surrogate pairs (e.g. emoji).
     */
    /**
     * Decode \\uXXXX escape sequences, handling surrogate pairs correctly.
     * e.g. \\uD83C\\uDF3F → 🌿 (U+1F33F).
     */
    private static String decodeUnicodeEscapes(String text) {
        if (text == null || !text.contains("\\u")) return text;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})");
        java.util.regex.Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            int code = Integer.parseInt(m.group(1), 16);
            if (code >= 0xD800 && code <= 0xDBFF) {
                // High surrogate: look ahead for a low surrogate \\uXXXX
                java.util.regex.Matcher next = pattern.matcher(text);
                next.region(m.end(), text.length());
                if (next.find() && next.start() == m.end()) {
                    int low = Integer.parseInt(next.group(1), 16);
                    if (low >= 0xDC00 && low <= 0xDFFF) {
                        int codepoint = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                        sb.append(Character.toChars(codepoint));
                        last = next.end();
                        // 跳过低代理，防止外层 matcher 从 m.end() 重复命中导致越界
                        m.region(next.end(), text.length());
                        continue;
                    }
                }
            }
            sb.append((char) code);
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    private static String getTextOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
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

    private static List<MemoryPattern> parsePatternArray(JsonNode root, String field) {
        JsonNode arr = root.get(field);
        if (arr == null || !arr.isArray() || arr.isEmpty()) return null;
        try {
            return MAPPER.readValue(arr.traverse(), new TypeReference<List<MemoryPattern>>() {});
        } catch (Exception e) {
            log.warn("解析 patterns 数组失败: {}", e.getMessage());
            return null;
        }
    }

    private static List<MemoryPreference> parsePreferenceArray(JsonNode root, String field) {
        JsonNode arr = root.get(field);
        if (arr == null || !arr.isArray() || arr.isEmpty()) return null;
        try {
            return MAPPER.readValue(arr.traverse(), new TypeReference<List<MemoryPreference>>() {});
        } catch (Exception e) {
            log.warn("解析 preferences 数组失败: {}", e.getMessage());
            return null;
        }
    }

    private static AiUnderstanding fallback(String message) {
        return new AiUnderstanding(message, null, null, null, List.of(), "unknown", "life", false, null, message);
    }
}
