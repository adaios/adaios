package com.adaiadai.core.infrastructure.ai.vision;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlmResponseParser — GLM 视觉回复解析测试。
 */
class GlmResponseParserTest {

    @Test
    void parseFullJson() {
        ImageUnderstanding u = GlmResponseParser.parse("""
                {"summary":"持仓截图","category":"trading","extractedText":"浦发银行 1000股","tags":["交易","持仓"]}""");

        assertEquals("持仓截图", u.summary());
        assertEquals("trading", u.category());
        assertEquals("浦发银行 1000股", u.extractedText());
        assertEquals(List.of("交易", "持仓"), u.tags());
    }

    @Test
    void parseInsideCodeFence() {
        ImageUnderstanding u = GlmResponseParser.parse("""
                ```json
                {"summary":"白板笔记","category":"whiteboard","extractedText":"Q3 目标","tags":["项目"]}
                ```""");

        assertEquals("白板笔记", u.summary());
        assertEquals("whiteboard", u.category());
        assertEquals("项目", u.tags().get(0));
    }

    @Test
    void parseThinkAnswerWrappedJson() {
        // GLM-4.1V-Thinking 实际输出：think 块里含大括号也不应干扰提取
        ImageUnderstanding u = GlmResponseParser.parse("""
                <think>用户发的持仓截图，需要按 JSON 格式概括（{"summary":...}）。</think>
                <answer>{"summary":"持仓截图","category":"trading","extractedText":"浦发银行","tags":["交易","持仓"]}</answer>""");

        assertEquals("持仓截图", u.summary());
        assertEquals("trading", u.category());
        assertEquals("浦发银行", u.extractedText());
        assertEquals(List.of("交易", "持仓"), u.tags());
    }

    @Test
    void parseThinkOnly_removesBlock() {
        ImageUnderstanding u = GlmResponseParser.parse("""
                <think>分析中</think>
                {"summary":"白板笔记","category":"whiteboard","extractedText":"Q3","tags":[]}""");

        assertEquals("白板笔记", u.summary());
        assertEquals("whiteboard", u.category());
    }

    // ── P0-1 回归（2026-08-18 生产：<think> 壳泄漏进 summary）──

    @Test
    void parseThinkUnclosed_noLeak() {
        // 生产形态：模型输出截断，<think> 未闭合且无 JSON —— 思考过程原文曾直接落 summary
        ImageUnderstanding u = GlmResponseParser.parse(
                "<think>用户现在需要分析这张图片是当日成交的交易记录截图，属于trading类。首先提取文字内容，然后做总结。");

        assertTrue(!u.summary().contains("<think>"), "summary 不得含 think 壳: " + u.summary());
        assertTrue(u.summary().contains("未收到有效回复"), "剥壳后为空应给默认文案: " + u.summary());
        assertEquals("photo", u.category());
    }

    @Test
    void parseThinkClosedNoContent_noLeak() {
        // 模型只输出了闭合 think 块，无答案无 JSON
        ImageUnderstanding u = GlmResponseParser.parse("<think>分析中</think>");

        assertTrue(!u.summary().contains("<think>"), "summary 不得含 think 壳: " + u.summary());
        assertTrue(u.summary().contains("未收到有效回复"), u.summary());
    }

    @Test
    void parseThinkClosedThenText_fallbackStripsThink() {
        // think 块闭合 + 非 JSON 文本：降级原文必须剥壳
        ImageUnderstanding u = GlmResponseParser.parse("<think>分析中</think>抱歉，我无法分析这张图片");

        assertTrue(!u.summary().contains("<think>"), "summary 不得含 think 壳: " + u.summary());
        assertTrue(u.summary().contains("抱歉"), u.summary());
    }

    @Test
    void parseUnclosedAnswerTag_stripsTag() {
        // <answer> 未闭合（JSON 截断）：剥掉 answer 标签本身，不得残留 <answer> 前缀
        ImageUnderstanding u = GlmResponseParser.parse(
                "<think>分析中</think><answer>{\"summary\":\"当日成交明细\",\"category\":\"trading\"}");

        assertTrue(!u.summary().contains("<answer>"), "summary 不得含 answer 标签: " + u.summary());
        assertTrue(!u.summary().contains("<think>"), "summary 不得含 think 标签: " + u.summary());
    }

    @Test
    void parseMixedTextAndJson() {
        ImageUnderstanding u = GlmResponseParser.parse("""
                这是一张图片的分析结果：
                {"summary":"发票","category":"invoice","extractedText":"金额 500 元","tags":[]}""");

        assertEquals("invoice", u.category());
        assertEquals("金额 500 元", u.extractedText());
        assertTrue(u.tags().isEmpty());
    }

    @Test
    void parseMissingFields_usesDefaults() {
        ImageUnderstanding u = GlmResponseParser.parse("{}");

        assertEquals("图片记录", u.summary());
        assertEquals("photo", u.category());
        assertEquals("", u.extractedText());
        assertTrue(u.tags().isEmpty());
    }

    @Test
    void parseBlank_returnsFallback() {
        ImageUnderstanding u = GlmResponseParser.parse("  ");

        assertTrue(u.summary().contains("未收到 AI 回复"));
        assertEquals("photo", u.category());
    }

    @Test
    void parseNoJson_returnsFallbackText() {
        ImageUnderstanding u = GlmResponseParser.parse("抱歉，我无法分析这张图片");

        assertTrue(u.summary().contains("抱歉"));
    }

    @Test
    void parseMalformedJson_returnsFallbackText() {
        ImageUnderstanding u = GlmResponseParser.parse("{oops");

        assertEquals("photo", u.category());
    }

    @Test
    void domainOf_mapsTrading() {
        assertEquals("trading", ImageUnderstanding.domainOf("trading"));
        assertEquals("life", ImageUnderstanding.domainOf("whiteboard"));
        assertEquals("life", ImageUnderstanding.domainOf("photo"));
        assertEquals("life", ImageUnderstanding.domainOf(null));
    }
}
