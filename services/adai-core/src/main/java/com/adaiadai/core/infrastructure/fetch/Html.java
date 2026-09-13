package com.adaiadai.core.infrastructure.fetch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Html — HTML 到文本 / 结构的公共工具（2026-09-13 平台抓取放开批）。
 * <p>
 * 原先是 {@link ArticleFetcher} 的私有静态方法；本批新增公众号 / 头条等抓取器后收敛到这里，
 * 避免「去脚本去导航」的规则在多个平台各写一份、各自漂移。
 * <p>
 * <b>P1-抓取1（2026-09-14 晚间批）</b>：所有「删掉整个元素」的动作改为**线性扫描**
 * （{@link #stripElement}）。原因：原先用 {@code (?is)<head.*?</head>} 这类惰性 DOTALL 正则，
 * 在**只有开标签、没有闭标签**的页面上（恶意或损坏 HTML）会退化成 O(n²)——
 * 官报实测 80KB 未闭合 {@code <head} 已跑 2.9s，4MB 就是小时级，而且没有超时：
 * 一个链接就能钉死 learn 执行线程（生产 2 核）。现在单次转换是 O(n)，并先做输入上限。
 */
final class Html {

    private Html() {
    }

    /**
     * 单次转换的输入上限（字符）：只取页面开头部分——上游本来就有 4MB 响应上限，
     * 这里再收一道，给「线性扫描 + 实体解码 + 多趟正则」留出毫秒级的最坏耗时预算。
     */
    static final int MAX_HTML_CHARS = 512_000;

    private static final Pattern DIV_TAG = Pattern.compile("(?i)<(/?)div\\b[^>]*>");

    private static final Pattern OG_TITLE = Pattern.compile(
            "(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern OG_TITLE_REV = Pattern.compile(
            "(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:title[\"']");
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    /** HTML → 纯文本（去脚本/样式/导航，块级标签转换行，解实体）。 */
    static String toText(String html) {
        if (html == null) return "";
        String s = html.length() > MAX_HTML_CHARS ? html.substring(0, MAX_HTML_CHARS) : html;
        s = stripElement(s, "head");
        s = stripElement(s, "script");
        s = stripElement(s, "style");
        s = stripElement(s, "noscript");
        s = stripElement(s, "nav");
        s = stripElement(s, "header");
        s = stripElement(s, "footer");
        s = stripElement(s, "aside");
        s = stripElement(s, "form");
        s = stripDelimited(s, "<!--", "-->");
        s = s.replaceAll("(?is)<(br|hr)[^>]*>", "\n");
        s = s.replaceAll("(?is)</(p|div|h[1-6]|li|tr|section|article|blockquote)>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = unescapeEntities(s);
        s = s.replaceAll("[ \\t\\x0B\\f\\r\\u00a0]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    static String unescapeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replaceAll("&#(\\d{2,5});", "");
    }

    /** 标题抽取：og:title → title 标签。只看开头一段（head 区必在文档起始处），避免大页面上的正则扫描。 */
    static String extractTitle(String html) {
        if (html == null) return null;
        String head = html.length() > MAX_HTML_CHARS ? html.substring(0, MAX_HTML_CHARS) : html;
        String t = firstGroup(OG_TITLE, head);
        if (t == null) t = firstGroup(OG_TITLE_REV, head);
        if (t == null) t = firstGroup(TITLE_TAG, head);
        if (t == null) return null;
        t = unescapeEntities(t).strip();
        return t.isBlank() ? null : t;
    }

    /**
     * 删除 {@code <tag ...>…</tag>} 整段（含标签本身），**线性 O(n)**。
     * <p>
     * 与惰性 DOTALL 正则的语义对齐：找到配对的闭标签就删；**找不到闭标签则原样保留**
     * （正则在这种情况下只是白扫一遍、并不删除内容）。开标签名后必须跟空白 / {@code >} / {@code /}，
     * 否则不算命中——这是为了不把 {@code <header>} 当成 {@code <head>}。
     */
    static String stripElement(String html, String tag) {
        return stripDelimited(html, "<" + tag, "</" + tag, true);
    }

    /** 删除 {@code open} 到最近一个 {@code close} 之间的内容（含两端），线性扫描。 */
    static String stripDelimited(String html, String open, String close) {
        return stripDelimited(html, open, close, false);
    }

    private static String stripDelimited(String html, String open, String close, boolean tagBoundary) {
        if (html == null || html.isEmpty()) return "";
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            int start = indexOfIgnoreCase(html, open, i);
            if (start < 0) {
                out.append(html, i, html.length());
                break;
            }
            if (tagBoundary) {
                int after = start + open.length();
                if (after < html.length()) {
                    char c = html.charAt(after);
                    if (!(Character.isWhitespace(c) || c == '>' || c == '/')) {
                        // 是别的标签（<header> vs <head>）→ 当普通文本继续往后找
                        out.append(html, i, after);
                        i = after;
                        continue;
                    }
                }
            }
            int end = indexOfIgnoreCase(html, close, start + open.length());
            if (end < 0) {
                // 没有闭标签：不删（与正则语义一致），原样保留剩余内容
                out.append(html, i, html.length());
                break;
            }
            int afterClose = html.indexOf('>', end);
            out.append(html, i, start).append(' ');
            i = afterClose < 0 ? html.length() : afterClose + 1;
        }
        return out.toString();
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int limit = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 取指定 id 的 div 的**内容**（标签平衡，不靠「第一个 {@code </div>}」猜）。
     * <p>
     * <b>为什么不能用正则一步到位</b>：正文容器普遍是嵌套 div（公众号的 {@code js_content}
     * 里就有好几层）。非贪婪匹配会停在第一个 {@code </div>} 上，把正文截成开头一小段——
     * 而症状是「抓到了、但内容不全」，比抓不到更难发现。这里显式做深度计数，
     * 遇到未闭合则取到末尾（容错，但结果可见地偏长而不是悄悄缺一半）。
     *
     * @return div 内容（含内层标签）；找不到该 id 返回 null
     */
    static String extractDivById(String html, String id) {
        if (html == null || id == null) return null;
        int idx = indexOfIdAttribute(html, id);
        if (idx < 0) return null;
        int openEnd = html.indexOf('>', idx);
        if (openEnd < 0) return null;
        int depth = 1;
        Matcher m = DIV_TAG.matcher(html);
        m.region(openEnd + 1, html.length());
        while (m.find()) {
            if (m.group(1).isEmpty()) {
                depth++;
            } else {
                depth--;
                if (depth == 0) return html.substring(openEnd + 1, m.start());
            }
        }
        return html.substring(openEnd + 1);
    }

    /** 找 {@code id="x"} / {@code id='x'}（大小写不敏感，整体值匹配）。 */
    private static int indexOfIdAttribute(String html, String id) {
        Pattern p = Pattern.compile("(?i)\\bid\\s*=\\s*[\"']" + Pattern.quote(id) + "[\"']");
        Matcher m = p.matcher(html);
        return m.find() ? m.start() : -1;
    }

    static String firstGroup(Pattern p, String html) {
        if (html == null) return null;
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }
}
