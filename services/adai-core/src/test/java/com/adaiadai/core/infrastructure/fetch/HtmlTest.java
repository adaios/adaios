package com.adaiadai.core.infrastructure.fetch;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HtmlTest — HTML→文本的健壮性守卫（2026-09-14 晚间批 P1-抓取1）。
 * <p>
 * 背景：原先「删掉整个元素」用惰性 DOTALL 正则（{@code (?is)<head.*?</head>}），
 * 在**只有开标签没有闭标签**的页面上退化成 O(n²)——官报实测 80KB 未闭合 {@code <head} 2.9s，
 * 4MB 小时级、无超时，一个恶意链接就能钉死 learn 执行线程。本测试把「不再二次方」钉住。
 */
class HtmlTest {

    @Test
    void unclosedTags_doNotDegradeToQuadratic() {
        // 200KB 的未闭合 <head / <script / <nav：二次方实现约需 18s（80KB→2.9s 按 n² 外推）
        String junk = "<head ".repeat(40_000);   // 240KB
        String html = "<p>开头正文</p>" + junk + "<p>结尾正文</p>";

        String text = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> Html.toText(html), "未闭合标签不得让转换退化成 O(n²)");

        assertTrue(text.contains("开头正文"), "正文应保留: " + text.substring(0, Math.min(60, text.length())));
        assertTrue(text.contains("结尾正文"), "未闭合标签之后的正文也应保留");
    }

    @Test
    void stripsClosedElements_butNotSiblingTagsWithSamePrefix() {
        String html = "<html><head><title>标题</title></head>"
                + "<body><script>var a=1;</script><style>p{}</style>"
                + "<header>页头（按规则应删）</header>"
                + "<headline>同前缀但不同标签要保留</headline>"
                + "<nav>导航要删</nav>"
                + "<p>正文要保留</p>"
                + "<!-- 注释要删 --></body></html>";

        String text = Html.toText(html);

        assertFalse(text.contains("var a=1"), "script 内容应删除");
        assertFalse(text.contains("p{}"), "style 内容应删除");
        assertFalse(text.contains("导航要删"), "nav 内容应删除");
        assertFalse(text.contains("注释要删"), "HTML 注释应删除");
        assertTrue(text.contains("正文要保留"), "正文应保留: " + text);
        // header 是**自己的规则**要删的元素（不是被 <head> 的判定误伤）
        assertFalse(text.contains("页头（按规则应删）"), "header 元素本身按规则删除");
        // 关键边界：<headline> 不能被 <head> 的开标签判定误伤（tagBoundary 检查）
        assertTrue(text.contains("同前缀但不同标签要保留"),
                "<headline> 不是 <head>，不得被整段吃掉: " + text);
    }

    @Test
    void capsInputSize_atDocumentedLimit() {
        String big = "<p>前部正文</p>" + "x".repeat(Html.MAX_HTML_CHARS + 100)
                + "<p>超限之后的正文</p>";
        String text = Html.toText(big);

        assertTrue(text.contains("前部正文"), "上限内的内容照常处理");
        assertFalse(text.contains("超限之后的正文"), "超过上限的部分应被丢弃（防大页面拖死线程）");
    }

    @Test
    void extractTitle_isCappedAndStillWorks() {
        String html = "<head><meta property=\"og:title\" content=\"标题在这\"></head><body>正文</body>";
        assertEquals("标题在这", Html.extractTitle(html));

        String huge = "<p>" + "y".repeat(Html.MAX_HTML_CHARS + 10) + "</p><title>太靠后了</title>";
        org.junit.jupiter.api.Assertions.assertNull(Html.extractTitle(huge),
                "标题只在前段找（大页面不扫全文）");
    }
}
