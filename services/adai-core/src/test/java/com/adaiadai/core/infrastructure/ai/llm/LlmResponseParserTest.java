package com.adaiadai.core.infrastructure.ai.llm;

import org.junit.jupiter.api.Test;
import com.adaiadai.core.kernel.ai.AiUnderstanding;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmResponseParser unit tests.
 * Tests various LLM response formats parsing into AiUnderstanding.
 */
class LlmResponseParserTest {

    // -- Standard JSON format --

    @Test
    void parse_fullJson() {
        String json = "{\"summary\": \"buy stock\", \"tags\": [\"invest\", \"tech\"], \"sentiment\": \"positive\", \"actionable\": true, \"actionSuggestion\": \"set stop\"}";
        AiUnderstanding r = LlmResponseParser.parse(json);
        assertEquals("buy stock", r.summary());
        assertEquals(List.of("invest", "tech"), r.tags());
        assertEquals("positive", r.sentiment());
        assertTrue(r.actionable());
        assertEquals("set stop", r.actionSuggestion());
    }

    @Test
    void parse_minimalJson() {
        String json = "{\"summary\": \"only summary\"}";
        AiUnderstanding r = LlmResponseParser.parse(json);
        assertEquals("only summary", r.summary());
        assertTrue(r.tags().isEmpty());
        assertEquals("neutral", r.sentiment());
        assertFalse(r.actionable());
        assertNull(r.actionSuggestion());
    }

    @Test
    void parse_nullActionSuggestion() {
        String json = "{\"summary\": \"test\", \"actionSuggestion\": null}";
        AiUnderstanding r = LlmResponseParser.parse(json);
        assertNull(r.actionSuggestion());
    }

    @Test
    void parse_noActionableField() {
        String json = "{\"summary\": \"record\"}";
        AiUnderstanding r = LlmResponseParser.parse(json);
        assertFalse(r.actionable());
    }

    // -- Markdown-wrapped JSON --

    @Test
    void parse_withJsonCodeBlock() {
        String response = """
                ```json
                {"summary": "good weather", "tags": ["weather"], "sentiment": "positive", "actionable": false, "actionSuggestion": null}
                ```""";
        AiUnderstanding r = LlmResponseParser.parse(response);
        assertEquals("good weather", r.summary());
        assertEquals(List.of("weather"), r.tags());
    }

    @Test
    void parse_withGenericCodeBlock() {
        String response = """
                ```
                {"summary": "test", "tags": [], "sentiment": "neutral", "actionable": false}
                ```""";
        AiUnderstanding r = LlmResponseParser.parse(response);
        assertEquals("test", r.summary());
    }

    @Test
    void parse_jsonAfterText() {
        String response = """
                Analysis result:
                ```json
                {"summary": "needs attention", "tags": ["reminder"], "sentiment": "neutral", "actionable": true, "actionSuggestion": "review tomorrow"}
                ```""";
        AiUnderstanding r = LlmResponseParser.parse(response);
        assertEquals("needs attention", r.summary());
        assertEquals(List.of("reminder"), r.tags());
        assertTrue(r.actionable());
    }

    @Test
    void parse_multipleCodeBlocks_usesFirst() {
        String response = """
                ```json
                {"summary": "first", "tags": [], "sentiment": "neutral", "actionable": false}
                ```
                text between
                ```json
                {"summary": "second", "tags": [], "sentiment": "neutral", "actionable": false}
                ```""";
        AiUnderstanding r = LlmResponseParser.parse(response);
        assertEquals("first", r.summary());
    }

    // -- Fallback (non-JSON) --

    @Test
    void parse_plainText_fallback() {
        AiUnderstanding r = LlmResponseParser.parse("Weather is nice today.");
        assertEquals("Weather is nice today.", r.summary());
        assertTrue(r.tags().isEmpty());
        assertEquals("neutral", r.sentiment());
        assertFalse(r.actionable());
    }

    @Test
    void parse_longPlainText_truncated() {
        String longText = "A".repeat(300);
        AiUnderstanding r = LlmResponseParser.parse(longText);
        // truncation: 200 chars + ellipsis
        assertEquals(201, r.summary().length());
        assertFalse(r.summary().isEmpty());
    }

    @Test
    void parse_shortPlainText_notTruncated() {
        AiUnderstanding r = LlmResponseParser.parse("short note");
        assertEquals("short note", r.summary());
    }

    // -- Edge cases --

    @Test
    void parse_nullInput() {
        AiUnderstanding r = LlmResponseParser.parse(null);
        assertNotNull(r.summary());
        assertFalse(r.summary().isBlank());
        assertEquals("unknown", r.sentiment());
    }

    @Test
    void parse_emptyInput() {
        AiUnderstanding r = LlmResponseParser.parse("");
        assertNotNull(r.summary());
        assertFalse(r.summary().isBlank());
    }

    @Test
    void parse_blankInput() {
        AiUnderstanding r = LlmResponseParser.parse("   ");
        assertNotNull(r.summary());
        assertFalse(r.summary().isBlank());
    }

    @Test
    void parse_malformedJson() {
        AiUnderstanding r = LlmResponseParser.parse("{summary: no quotes}");
        assertNotNull(r.summary());
    }

    @Test
    void parse_emptyTags() {
        String json = "{\"summary\": \"empty tags\", \"tags\": [], \"sentiment\": \"neutral\"}";
        AiUnderstanding r = LlmResponseParser.parse(json);
        assertTrue(r.tags().isEmpty());
    }

    @Test
    void parse_partialFields() {
        String json = "{\"summary\": \"only summary\", \"sentiment\": \"positive\"}";
        AiUnderstanding r = LlmResponseParser.parse(json);
        assertEquals("only summary", r.summary());
        assertTrue(r.tags().isEmpty());
        assertEquals("positive", r.sentiment());
        assertFalse(r.actionable());
    }

    @Test
    void parse_rawResponsePreserved() {
        String raw = "{\"summary\": \"test\", \"tags\": [], \"sentiment\": \"neutral\", \"actionable\": false}";
        AiUnderstanding r = LlmResponseParser.parse(raw);
        assertEquals(raw, r.rawResponse());
    }

    // -- Unicode escape decoding --

    @Test
    void parse_jsonSummaryDecodesUnicode() {
        // JSON with unicode escapes in summary value (simulates AI returning escaped text).
        // Backslash-u is constructed via char concat to avoid Java source unicode processing.
        String esc = "\\u0041\\u0042"; // = "AB" after decoding
        String json = "{\"summary\": \"hello " + esc + " world\", \"tags\": [], \"sentiment\": \"neutral\", \"actionable\": false}";
        AiUnderstanding r = LlmResponseParser.parse(json);
        assertTrue(r.summary().contains("AB"), "summary should contain decoded unicode: " + r.summary());
    }

    @Test
    void parse_textBeforeJsonDecodesUnicode() {
        // Mixed response simulating chat: AI text with escapes + domain-only JSON at end.
        String esc = "\\u0041\\u0042";
        String response = "hello " + esc + " world\n\n{\"domain\":\"life\"}";
        AiUnderstanding r = LlmResponseParser.parse(response);
        assertTrue(r.summary().contains("AB"), "text before json should decode unicode: " + r.summary());
        assertEquals("life", r.domain());
    }

    @Test
    void parse_textBeforeJsonOver500_notTruncated() {
        // Mixed response with text longer than 500 chars — should NOT truncate at old 500 limit.
        String longText = "A test sentence for checking truncation behavior. ".repeat(20);
        assertTrue(longText.length() > 600, "test text should be over 600 chars");
        String response = longText + "\n\n{\"domain\":\"life\"}";
        AiUnderstanding r = LlmResponseParser.parse(response);
        assertTrue(r.summary().length() > 500, "should not truncate text under 4000 chars");
    }

    @Test
    void parse_domainAlsoDecodesUnicode() {
        // domain field with unicode escape for letter 'i'
        String esc = "\\u0069"; // = "i"
        String json = "{\"summary\": \"test\", \"domain\": \"l" + esc + "fe\", \"tags\": [], \"sentiment\": \"neutral\", \"actionable\": false}";
        AiUnderstanding r = LlmResponseParser.parse(json);
        assertEquals("life", r.domain(), "domain should decode unicode escapes");
    }

    @Test
    void parse_unicodeSurrogatePair_emoji_decodesWithoutCrash() {
        // 回归：代理对（emoji）此前导致外层 matcher 重复命中低代理 → StringIndexOutOfBoundsException，
        // 整段回复降级纯文本并丢失 summary/tags。此用例验证不再崩溃且正确解码。
        String emoji = "\\uD83C\\uDF3F"; // 🌿 (U+1F33F) surrogate pair
        String raw = emoji + " 今天收获不错，继续保持";
        AiUnderstanding r = LlmResponseParser.parse(raw);
        assertNotNull(r);
        assertTrue(r.summary().contains("🌿"), "emoji 代理对应正确解码: " + r.summary());
        assertTrue(r.summary().contains("今天收获不错"), "代理对后的中文不应丢失: " + r.summary());
    }

    @Test
    void parse_mixedTextWithEmojiAndJson_doesNotCrash() {
        // 自然语言 + emoji 代理对 + JSON 混合输出
        String raw = "好的，" + "\\uD83C\\uDF3F" + " 已记录。\n```json\n{\"summary\": \"记录完成\", \"tags\": [\"日常\"], \"sentiment\": \"neutral\"}\n```";
        AiUnderstanding r = LlmResponseParser.parse(raw);
        assertNotNull(r);
        assertEquals("记录完成", r.summary(), "混合输出应提取到 JSON 的 summary");
    }

    // -- extractNaturalText（REVIEW #13/#11：写 card 与实时显示剥离 JSON，刷新后一致） --

    @Test
    void extractNaturalText_mixedTextAndJson_returnsNaturalText() {
        String response = "这是自然语言回复内容\n\n{\"summary\":\"摘要\",\"tags\":[\"日常\"],\"sentiment\":\"neutral\"}";
        assertEquals("这是自然语言回复内容", LlmResponseParser.extractNaturalText(response),
                "混合输出应剥离末尾 JSON，只留自然语言");
    }

    @Test
    void extractNaturalText_markdownJson_returnsNaturalText() {
        String response = "好的，已记录。\n```json\n{\"summary\":\"记录完成\"}\n```";
        assertEquals("好的，已记录。", LlmResponseParser.extractNaturalText(response),
                "markdown 包裹的 JSON 也应被剥离");
    }

    @Test
    void extractNaturalText_noJson_returnsStrippedText() {
        assertEquals("纯文本回复，无 JSON", LlmResponseParser.extractNaturalText("  纯文本回复，无 JSON  "),
                "无 JSON 时原样返回（trim）");
    }

    @Test
    void extractNaturalText_pureJson_returnsEmpty() {
        String json = "{\"summary\":\"摘要\",\"tags\":[],\"sentiment\":\"neutral\"}";
        assertEquals("", LlmResponseParser.extractNaturalText(json),
                "整段都是 JSON（无自然语言）返回空串，由调用方回退 summary");
    }

    @Test
    void extractNaturalText_nullOrBlank_returnsAsIs() {
        assertNull(LlmResponseParser.extractNaturalText(null));
        assertEquals("", LlmResponseParser.extractNaturalText(""));
        assertEquals("   ", LlmResponseParser.extractNaturalText("   "));
    }

    // ── #202：generate 正文代码块围栏剥离 ──

    @Test
    void stripCodeFences_removesMarkdownFence() {
        String fenced = "```markdown\n一、今日复盘\n二、操作回顾\n```";
        assertEquals("一、今日复盘\n二、操作回顾", LlmResponseParser.stripCodeFences(fenced),
                "应剥离 ```markdown 围栏，保留正文");
    }

    @Test
    void stripCodeFences_plainFenceAndNoFence() {
        assertEquals("正文内容", LlmResponseParser.stripCodeFences("```\n正文内容\n```"),
                "裸 ``` 围栏同样剥离");
        assertEquals("无围栏正文", LlmResponseParser.stripCodeFences("无围栏正文"),
                "无围栏时原样返回");
        assertNull(LlmResponseParser.stripCodeFences(null));
    }
}
