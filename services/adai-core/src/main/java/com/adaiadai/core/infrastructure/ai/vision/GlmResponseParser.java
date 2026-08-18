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

    /** GLM-4.1V-Thinking 自动输出 <think>…</think><answer>…</answer> 壳，解析前剥掉。 */
    private static final java.util.regex.Pattern THINK_BLOCK =
            java.util.regex.Pattern.compile("(?s)<think>.*?</think>");
    private static final java.util.regex.Pattern ANSWER_BLOCK =
            java.util.regex.Pattern.compile("(?s)<answer>([\\s\\S]*?)</answer>");

    private GlmResponseParser() {}

    /**
     * 提取 GLM 回复的自然语言回答（图片追问，L4）。
     * <p>
     * 剥掉 Thinking 模型的 {@code <think>/<answer>} 壳；无壳时原样返回。
     */
    public static String extractAnswer(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) return "";
        return stripThinkAnswer(rawResponse);
    }

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
        // P0-1（2026-08-18 生产）：降级路径必须用剥壳后文本——
        // GLM-4.1V-Thinking 输出 <think> 壳时，若 JSON 提取失败走 fallback(rawResponse)
        // 会把思考过程原文泄漏进 summary（生产 5 条 rec_20260818_1627* 全中，用户可见 + 交易归集中断）。
        String cleaned = stripThinkAnswer(rawResponse);
        String jsonStr = extractJson(cleaned);
        if (jsonStr == null) {
            log.warn("GLM 回复中未找到 JSON，降级为原文（已剥 think 壳）");
            return fallback(cleaned);
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
            return fallback(cleaned);
        }
    }

    // ── 内部方法 ──

    private static ImageUnderstanding fallback(String text) {
        // P0-1：剥壳后为空（模型只输出了 think 思考过程）→ 给默认文案，不落空串
        if (text == null || text.isBlank()) {
            return new ImageUnderstanding("图片理解失败：未收到有效回复", "photo", "", List.of());
        }
        // W-P3-13（2026-08-17）：截断避免劈开 surrogate pair（emoji）——后移一位补全高代理项
        String s = text.length() > 100 ? truncateSafe(text, 100) + "…" : text;
        return new ImageUnderstanding(s, "photo", "", List.of());
    }

    /** 安全截断：若截断点在 surrogate pair 中间则多取一位（不劈开 emoji）。 */
    private static String truncateSafe(String text, int max) {
        if (text.length() <= max) return text;
        int end = max;
        if (end > 0 && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            end++; // 把低代理项一并保留，避免半个 emoji
        }
        return text.substring(0, end);
    }

    /**
     * 从 LLM 回复中提取 JSON（支持整段 JSON / ```json 代码块 / 末尾 JSON 混合 / think-answer 壳）。
     */
    private static String extractJson(String text) {
        String cleaned = stripThinkAnswer(text);
        String trimmed = cleaned.strip();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        int codeStart = cleaned.indexOf("```");
        if (codeStart >= 0) {
            int contentStart = cleaned.indexOf('\n', codeStart) + 1;
            int codeEnd = cleaned.indexOf("```", contentStart);
            if (codeEnd > contentStart) {
                String candidate = cleaned.substring(contentStart, codeEnd).strip();
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

    /**
     * 剥掉 Thinking 模型的 <think>/<answer> 壳：优先取 answer 内容；
     * 无 answer 标签则移除 think 块，保留其余文本。
     * P0-1（2026-08-18）：<think> 未闭合（模型输出截断）时也剥掉——<think> 起到文末
     * 均为思考过程，不得泄漏进 summary。
     */
    private static String stripThinkAnswer(String text) {
        if (text == null || text.isBlank()) return text;
        java.util.regex.Matcher answer = ANSWER_BLOCK.matcher(text);
        if (answer.find()) {
            return answer.group(1).strip();
        }
        String stripped = THINK_BLOCK.matcher(text).replaceAll("").strip();
        // 未闭合 think 块（无 </think>）：THINK_BLOCK 匹配不到，手工从 <think> 起到文末删除
        int unclosedThink = stripped.indexOf("<think>");
        if (unclosedThink >= 0) {
            stripped = stripped.substring(0, unclosedThink).strip();
        }
        // 未闭合 answer 块（有 <answer> 无 </answer>，如 JSON 被截断）：
        // 剥掉标签本身，保留其后内容（供 extractJson 提取）
        int unclosedAnswer = stripped.indexOf("<answer>");
        if (unclosedAnswer >= 0) {
            stripped = stripped.substring(unclosedAnswer + "<answer>".length()).strip();
        }
        return stripped;
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
