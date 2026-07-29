package com.adaiadai.core.infrastructure.ai.llm;

import org.junit.jupiter.api.Test;

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
}
