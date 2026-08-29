package com.adaiadai.core.kernel.ai;

/**
 * JsonTailFilter — 流式增量中 AI 回复「JSON 回执尾巴」的渐进过滤（ai-calling-governance 批 2）。
 * <p>
 * 聊天 system prompt 要求 AI 在正文结束后「另起一行输出 JSON」（summary/tags/domain 回执）。
 * 同步路径由 {@code LlmResponseParser.extractNaturalText} 统一剥离；流式路径正文逐块转发时
 * 无法预知后面是否还有 JSON——本过滤器按「看见 {@code \n{} 或 {@code \n``` 即开始扣留」策略处理：
 * <ul>
 *   <li>未触发扣留：外发除末字符外的全部内容（留 1 字符缓冲防 {@code \n{} 被 chunk 边界切开）；</li>
 *   <li>触发扣留后：后续增量全部缓冲，不再外发；</li>
 *   <li>流结束 {@link #flush}：确认为 JSON（调用方 extractJson 判定）→ 丢弃缓冲；
 *       全文无 JSON → 缓冲实为正文，返回给调用方补发。</li>
 * </ul>
 * 权威定稿始终以 onComplete 的最终正文（extractNaturalText 结果）为准，草稿漏删/误删都会被定稿覆盖。
 */
public final class JsonTailFilter {

    private final StringBuilder buf = new StringBuilder();
    private boolean holding;

    /**
     * 过滤一块增量，返回可安全外发的部分（可能为空串；调用方跳过空串）。
     */
    public String filter(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        buf.append(chunk);
        if (holding) return "";

        int idx = tailStart(buf);
        if (idx >= 0) {
            holding = true;
            String out = buf.substring(0, idx);
            buf.delete(0, idx);
            return out;
        }
        // 未见尾巴起点：外发到倒数第 1 字符为止（"\n{" 跨 chunk 切开时留待下块判定）
        if (buf.length() > 1) {
            int safe = buf.length() - 1;
            String out = buf.substring(0, safe);
            buf.delete(0, safe);
            return out;
        }
        return "";
    }

    /**
     * 流结束。@param discard true = 缓冲确认为 JSON 回执尾巴（丢弃）；false = 缓冲是正文（返回补发）。
     */
    public String flush(boolean discard) {
        String rest = buf.toString();
        buf.setLength(0);
        return discard ? "" : rest;
    }

    /** 最早出现的尾巴起点："\n{"（JSON 回执另起一行）或 "\n```"（markdown 围栏包裹）。无则 -1。 */
    private static int tailStart(CharSequence s) {
        int a = indexOf(s, "\n{");
        int b = indexOf(s, "\n```");
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static int indexOf(CharSequence s, String target) {
        if (target.length() > s.length()) return -1;
        outer:
        for (int i = 0; i <= s.length() - target.length(); i++) {
            for (int j = 0; j < target.length(); j++) {
                if (s.charAt(i + j) != target.charAt(j)) continue outer;
            }
            return i;
        }
        return -1;
    }
}
