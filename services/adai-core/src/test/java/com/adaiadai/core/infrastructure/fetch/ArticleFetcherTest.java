package com.adaiadai.core.infrastructure.fetch;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ArticleFetcherTest — 文章抓取与正文抽取（RFC 20260912 §3.5，learn 抓取批 2026-09-12）。
 * <p>
 * 覆盖：正文抽取（去脚本/样式/导航、实体解码、块级转换行）、标题抽取（og:title → title）、
 * **403 → Web Archive 兜底**（不直接放弃）、仍失败 → 人话「把正文粘进来」（fail-visible，不产废卡）、
 * 反爬空壳页识别、留痕命名幂等键。
 */
class ArticleFetcherTest {

    private HttpServer server;
    private String base;
    private int articleStatus = 200;
    private String articleHtml = "";
    private int waybackStatus = 200;
    private String waybackBody = "";
    private final AtomicInteger articleCalls = new AtomicInteger();
    private final AtomicInteger waybackCalls = new AtomicInteger();

    private static final String LONG_BODY =
            "这是一篇足够长的文章正文，用于通过「正文过短视为反爬空壳页」的判定。" +
            "内容需要超过两百个字符才能在抓取端被认为是有效正文，因此这里重复若干次以凑足长度。" +
            "第一段讲背景，第二段讲方法，第三段讲结论与边界条件，第四段给出实践建议与注意事项。" +
            "补充内容继续：抓取层只负责把网页变成可读文本，结构化交给下游的 LLM 完成。" +
            "再补一句以保证长度稳妥超过阈值，避免测试因字符数差异而偶发失败。" +
            "继续补充：抓取层与解析层解耦，正文长度阈值只用于识别反爬空壳页，不参与内容语义判断。" +
            "这一段是为了让样例正文明显长于阈值，测试不应依赖边界值附近的行为，否则改一个字就会红。";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/article", ex -> {
            articleCalls.incrementAndGet();
            respond(ex, articleStatus, articleHtml);
        });
        server.createContext("/wayback", ex -> {
            waybackCalls.incrementAndGet();
            respond(ex, waybackStatus, waybackBody);
        });
        server.createContext("/snapshot", ex -> respond(ex, 200, articleHtml));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private ArticleFetcher fetcher() {
        return new ArticleFetcher(base + "/wayback", 1);
    }

    private static String page(String title, String body) {
        return "<html><head><title>" + title + "</title>"
                + "<meta property=\"og:title\" content=\"" + title + "\">"
                + "<meta property=\"og:site_name\" content=\"示例站点\">"
                + "<style>body{color:red}</style><script>var x=1;</script></head>"
                + "<body><nav>导航栏不该出现</nav><h1>" + title + "</h1><p>" + body + "</p></body></html>";
    }

    // ── 域名识别 ──

    @Test
    void supports_httpAndHttpsOnly() {
        ArticleFetcher f = fetcher();
        assertTrue(f.supports("https://example.com/a"));
        assertTrue(f.supports("http://example.com/a"));
        assertFalse(f.supports("ftp://example.com/a"));
        assertFalse(f.supports("不是链接"));
        assertFalse(f.supports(null));
    }

    // ── 正文/标题抽取 ──

    @Test
    void htmlToText_dropsScriptStyleNav_andKeepsParagraphs() {
        String text = ArticleFetcher.htmlToText(page("标题", LONG_BODY));

        assertFalse(text.contains("var x=1"), "脚本内容不得进正文");
        assertFalse(text.contains("color:red"), "样式内容不得进正文");
        assertFalse(text.contains("导航栏不该出现"), "导航区不得进正文");
        assertTrue(text.contains("第一段讲背景"), "正文段落必须保留");
        assertTrue(text.contains("\n"), "块级标签应转换为换行，保留可读结构");
    }

    @Test
    void htmlToText_decodesEntities() {
        String text = ArticleFetcher.htmlToText("<p>a &amp; b &lt;c&gt; &quot;d&quot;&nbsp;e</p>");

        assertTrue(text.contains("a & b <c> \"d\" e"));
    }

    @Test
    void extractTitle_prefersOgTitle_thenTitleTag() {
        assertEquals("OG 标题", ArticleFetcher.extractTitle(
                "<meta property=\"og:title\" content=\"OG 标题\"><title>标签标题</title>"));
        assertEquals("标签标题", ArticleFetcher.extractTitle("<title>标签标题</title>"));
        assertEquals(null, ArticleFetcher.extractTitle("<html><body>无标题</body></html>"));
    }

    @Test
    void shortHash_stableAndDistinct() {
        String a = ArticleFetcher.shortHash("https://example.com/a");
        assertEquals(a, ArticleFetcher.shortHash("https://example.com/a"), "同 URL 幂等键必须稳定");
        assertNotEquals(a, ArticleFetcher.shortHash("https://example.com/b"));
        assertEquals(12, a.length());
    }

    // ── 正常抓取 ──

    @Test
    void fetch_success_extractsTextTitleAndArchivesFullText() {
        articleHtml = page("Harness 工程实践", LONG_BODY);

        LearnSource source = fetcher().fetch(base + "/article");

        assertEquals("article", source.platform());
        assertEquals("Harness 工程实践", source.title());
        assertEquals("示例站点", source.author());
        assertFalse(source.needsTranscription(), "文章不需要转写");
        assertTrue(source.text().contains("第一段讲背景"));
        assertEquals(1, articleCalls.get());
        assertEquals(0, waybackCalls.get(), "直抓成功不该去查快照");
        List<String> names = source.rawAssets().stream().map(LearnSource.RawAsset::name).toList();
        assertEquals(1, names.size());
        assertTrue(names.get(0).matches("article-[0-9a-f]{12}-text\\.txt"), "留痕名要可寻址（幂等键）");
    }

    @Test
    void fetch_shortShellPage_throwsHumanMessage() {
        articleHtml = "<html><body><p>请开启 JavaScript</p></body></html>";

        LearnException e = assertThrows(LearnException.class, () -> fetcher().fetch(base + "/article"));

        assertTrue(e.getMessage().contains("粘进来"), "抓不到就给替代路径，不硬撑");
    }

    // ── 403 → Web Archive 兜底 ──

    @Test
    void fetch_403FallsBackToWaybackSnapshot() {
        articleStatus = 403;
        waybackBody = "{\"archived_snapshots\":{\"closest\":{\"url\":\"" + base + "/snapshot\",\"available\":true}}}";
        articleHtml = page("快照里的标题", LONG_BODY);

        LearnSource source = fetcher().fetch(base + "/article");

        assertEquals(1, waybackCalls.get(), "403 应触发快照兜底");
        assertEquals("快照里的标题", source.title());
        assertTrue(source.text().contains("第一段讲背景"));
    }

    @Test
    void fetch_403AndNoSnapshot_throwsHumanMessage() {
        articleStatus = 403;
        waybackBody = "{\"archived_snapshots\":{}}";

        LearnException e = assertThrows(LearnException.class, () -> fetcher().fetch(base + "/article"));

        assertTrue(e.getMessage().contains("粘进来"));
        assertEquals(1, waybackCalls.get());
    }

    @Test
    void fetch_403AndSnapshotAlsoFails_throwsHumanMessage() {
        articleStatus = 403;
        waybackBody = "{\"archived_snapshots\":{\"closest\":{\"url\":\"" + base + "/missing\",\"available\":true}}}";

        LearnException e = assertThrows(LearnException.class, () -> fetcher().fetch(base + "/article"));

        assertTrue(e.getMessage().contains("粘进来"));
    }

    @Test
    void fetch_serverError_fallsBackThenThrowsHumanMessage() {
        articleStatus = 500;

        LearnException e = assertThrows(LearnException.class, () -> fetcher().fetch(base + "/article"));

        assertTrue(e.getMessage().contains("粘进来"), "5xx 也走快照兜底，兜不住就给人话 + 替代路径");
        assertEquals(1, waybackCalls.get(), "直抓失败应先尝试快照兜底");
    }

    @Test
    void fetch_overlongText_truncatedWithNotice() {
        articleHtml = page("长文", LONG_BODY.repeat(300));

        LearnSource source = fetcher().fetch(base + "/article");

        assertTrue(source.text().length() <= ArticleFetcher.MAX_TEXT_CHARS + 40, "正文须截断到上限内（防超 LLM 上下文）");
        assertTrue(source.text().contains("已截断"));
    }
}
