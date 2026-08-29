package com.adaiadai.core.kernel.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JsonTailFilter — 流式增量 JSON 尾巴过滤测试（ai-calling-governance 批 2）。
 * <p>
 * 关键边界：{@code \n{} 恰好被 chunk 边界切开（"\n" 在上块尾、"{json" 在下块头）——
 * 过滤器留 1 字符缓冲防误发。
 */
class JsonTailFilterTest {

    @Test
    void jsonTailStartWithinSingleChunk_isHeld() {
        JsonTailFilter f = new JsonTailFilter();
        String out1 = f.filter("你好\n");
        assertEquals("你好", out1, "末字符缓冲（\n 可能是尾巴开头）");

        String out2 = f.filter("{\"summary\":\"s\"}");
        assertEquals("", out2, "\n{ 命中 → 进入扣留，后续不再外发");

        assertEquals("", f.flush(true), "确认为 JSON 尾巴 → 缓冲丢弃");
    }

    @Test
    void jsonTailSplitAcrossChunks_isCaught() {
        JsonTailFilter f = new JsonTailFilter();
        assertEquals("正文", f.filter("正文\n"));       // "\n" 单独在上块尾 → 缓冲
        assertEquals("", f.filter("{不完整"));           // 下块 "{ 开头 → 扣留
        assertEquals("", f.flush(true));
    }

    @Test
    void plainText_flushesHeldTrailingChar() {
        JsonTailFilter f = new JsonTailFilter();
        String out1 = f.filter("你好\n世");
        assertEquals("你好\n", out1, "只外发到倒数第 1 字符（防 \\n{ 跨块切开）");
        String out2 = f.filter("界");
        assertEquals("世", out2, "上一块缓冲的尾字符随新块外发（仍留新尾字符）");

        String held = f.flush(false);
        assertEquals("界", held, "全文无 JSON → flush 补发最后被缓冲的字符");
    }

    @Test
    void pureTextFlush_returnsRemainder() {
        JsonTailFilter f = new JsonTailFilter();
        f.filter("收尾单字");
        String held = f.flush(false);
        assertEquals("字", held, "全文无 JSON → flush 补发被缓冲的末字符");
    }

    @Test
    void markdownFenceTail_isHeld() {
        JsonTailFilter f = new JsonTailFilter();
        f.filter("正文结束\n");
        String out = f.filter("```json");
        assertEquals("", out, "\\n``` 围栏包裹的尾巴同样扣留");
        assertEquals("", f.flush(true));
    }
}
