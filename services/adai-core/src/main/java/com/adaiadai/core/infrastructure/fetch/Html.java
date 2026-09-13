package com.adaiadai.core.infrastructure.fetch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Html — HTML 到文本 / 结构的公共工具（2026-09-13 平台抓取放开批）。
 * <p>
 * 原先是 {@link ArticleFetcher} 的私有静态方法；本批新增公众号 / 头条等抓取器后收敛到这里，
 * 避免「去脚本去导航」的规则在多个平台各写一份、各自漂移。
 */
final class Html {

    private Html() {
    }

    private static final Pattern DIV_TAG = Pattern.compile("(?i)<(/?)div\\b[^>]*>");

    private static final Pattern OG_TITLE = Pattern.compile(
            "(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern OG_TITLE_REV = Pattern.compile(
            "(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:title[\"']");
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    /** HTML → 纯文本（去脚本/样式/导航，块级标签转换行，解实体）。 */
    static String toText(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<head.*?</head>", " ");
        s = s.replaceAll("(?is)<script.*?</script>", " ");
        s = s.replaceAll("(?is)<style.*?</style>", " ");
        s = s.replaceAll("(?is)<noscript.*?</noscript>", " ");
        s = s.replaceAll("(?is)<nav.*?</nav>", " ");
        s = s.replaceAll("(?is)<header.*?</header>", " ");
        s = s.replaceAll("(?is)<footer.*?</footer>", " ");
        s = s.replaceAll("(?is)<aside.*?</aside>", " ");
        s = s.replaceAll("(?is)<form.*?</form>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
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

    /** 标题抽取：og:title → title 标签。 */
    static String extractTitle(String html) {
        String t = firstGroup(OG_TITLE, html);
        if (t == null) t = firstGroup(OG_TITLE_REV, html);
        if (t == null) t = firstGroup(TITLE_TAG, html);
        if (t == null) return null;
        t = unescapeEntities(t).strip();
        return t.isBlank() ? null : t;
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
