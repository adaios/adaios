package com.adaiadai.core.infrastructure.fetch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WeiboMid — 微博 URL 里的 62 进制 id → mid（2026-09-13 平台抓取放开批）。
 * <p>
 * <b>为什么需要它</b>：微博分享出来的链接形如 {@code weibo.com/<uid>/<62进制id>}，
 * 而**免登录能拿到正文**的接口是 {@code m.weibo.cn/statuses/show?id=<mid>}——两者不是同一个值。
 * 不做这步换算，用户分享过来的微博链接就只能靠抓 PC 页面（实测被反爬挡）。
 * <p>
 * <b>算法</b>（公开换算，见 <a href="https://www.cnblogs.com/qiernonstop/p/3634354.html">来源</a>）：
 * 把 id 反转后每 4 个字符一组，每组再反转回来按 62 进制解成十进制；除**最后一组**
 * （对应原串开头那组）外，十进制不足 7 位就左侧补 0；各组正序拼接即 mid。
 * <p>
 * ⚠️ <b>字母表顺序是 {@code 0-9a-zA-Z}</b>——不是常见的 {@code 0-9A-Za-z}。用错顺序会得到
 * 一个「看着合理但完全错误」的 mid，表现为接口返回空、却看不出哪里错。本类用公开的已知样本对
 * 钉死这一点（见 {@code WeiboMidTest}）。
 */
final class WeiboMid {

    private WeiboMid() {
    }

    /** ⚠️ 顺序敏感：0-9 → a-z → A-Z（微博口径）。 */
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * URL 里的 62 进制 id → mid（十进制字符串）。
     *
     * @param urlId 形如 {@code z8ElgBLeQ} 的一段（URL 路径末段）
     * @return 十进制 mid；入参为空或含非法字符时返回 null（调用方转人话，不猜）
     */
    static String urlIdToMid(String urlId) {
        if (urlId == null || urlId.isBlank()) return null;
        String id = urlId.strip();
        String reversed = new StringBuilder(id).reverse().toString();
        int groups = (reversed.length() + 3) / 4;      // 每 4 字符一组（向上取整）
        List<String> parts = new ArrayList<>(groups);
        for (int i = 0; i < groups; i++) {
            int start = i * 4;
            int end = Math.min(start + 4, reversed.length());
            String chunk = new StringBuilder(reversed.substring(start, end)).reverse().toString();
            String dec = base62Decode(chunk);
            if (dec == null) return null;              // 非法字符：不猜
            // 除最后一组（原串开头那组）外，不足 7 位左侧补 0
            if (i < groups - 1 && dec.length() < 7) {
                dec = "0".repeat(7 - dec.length()) + dec;
            }
            parts.add(dec);
        }
        Collections.reverse(parts);
        return String.join("", parts);
    }

    /** 62 进制串 → 十进制串；含字母表外字符返回 null。每组最多 4 字符，不会溢出 long。 */
    private static String base62Decode(String s) {
        long num = 0;
        for (int i = 0; i < s.length(); i++) {
            int idx = ALPHABET.indexOf(s.charAt(i));
            if (idx < 0) return null;
            num = num * 62 + idx;
        }
        return Long.toString(num);
    }
}
