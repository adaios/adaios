package com.adaiadai.core.infrastructure.fetch;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToutiaoFetcherTest — 今日头条图文抓取（2026-09-13 平台抓取放开批）。
 * <p>
 * 覆盖：**PC 链接被改写成移动版再抓**（PC 站是反爬页、移动版才是服务端渲染）、
 * {@code RENDER_DATA} 的 URL 解码 + JSON 解析、**多个同名字段时挑最长的那个**
 * （正文天然最长，写死路径会在平台改版时空掉）、视频链接人话拒绝、以及域名白名单。
 */
class ToutiaoFetcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String base;
    private String html = "";
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    /** 正文需超过 MIN_TEXT_CHARS(100)。 */
    private static final String BODY =
            "<p>第一段：这篇文章要解决的问题，以及为什么它值得写下来。</p>"
                    + "<p>第二段：方法与推导，补充足够文字以越过正文长度下限。</p>"
                    + "<p>第三段：结论与边界条件，说明哪些场景下并不适用。</p>"
                    + "<p>第四段：把前面几点串起来，给出一个可以直接照做的结论。</p>"
                    + "<p>第五段：补充实践中的注意事项，避免常见误解与误用。</p>";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/i", ex -> {
            lastPath.set(ex.getRequestURI().getPath());
            respond(ex, 200, html);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 模拟头条页面：RENDER_DATA 是 URL 编码的 JSON。 */
    private static String pageWith(ObjectNode data) throws Exception {
        String encoded = URLEncoder.encode(MAPPER.writeValueAsString(data), StandardCharsets.UTF_8);
        return "<html><head><title>页面标题</title></head><body>"
                + "<script id=\"RENDER_DATA\" type=\"application/json\">" + encoded + "</script>"
                + "</body></html>";
    }

    private static ObjectNode dataNode(String title, String content) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode data = root.putObject("data");
        data.put("title", title);
        data.put("content", content);
        return root;
    }

    private ToutiaoFetcher fetcher() {
        return new ToutiaoFetcher(1, base, new OutboundHostPolicy(true, 3));
    }

    @Test
    void supports_onlyToutiaoHosts() {
        ToutiaoFetcher f = fetcher();
        assertTrue(f.supports("https://www.toutiao.com/article/7123456789012345678/"));
        assertTrue(f.supports("https://m.toutiao.com/i7123456789012345678/"));
        assertFalse(f.supports("https://eviltoutiao.com/article/7123456789012345678/"));
        assertFalse(f.supports("https://toutiao.com.attacker.com/x"));
    }

    @Test
    void fetch_pcUrl_isRewrittenToMobileVersion_andExtractsBody() throws Exception {
        html = pageWith(dataNode("一篇头条文章", BODY));
        LearnSource src = fetcher().fetch("https://www.toutiao.com/article/7123456789012345678/");

        assertEquals("toutiao", src.platform());
        assertEquals("7123456789012345678", src.sourceId());
        assertEquals("一篇头条文章", src.title());
        assertTrue(src.text().contains("第一段"));
        assertTrue(src.text().contains("第三段"));
        assertFalse(src.text().contains("<p>"), "正文要转成纯文本");
        assertEquals("/i7123456789012345678/", lastPath.get(),
                "PC 链接必须改写成移动版路径——PC 站返回的是 JS VM 反爬页");
    }

    /**
     * RENDER_DATA 里可能有多处同名字段；正文是最长的那个。
     * 这条锁住「按最长挑」而不是「按路径写死」——平台改版时前者仍能拿到正文。
     */
    @Test
    void fetch_multipleContentFields_picksTheLongest() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode data = root.putObject("data");
        data.put("content", "短字段");
        ObjectNode nested = data.putObject("seo");
        nested.put("content", BODY);          // 真正的正文在更深的层级
        html = pageWith(root);

        LearnSource src = fetcher().fetch("https://www.toutiao.com/article/7123456789012345678/");
        assertTrue(src.text().contains("第一段"), "应挑到最长的那个 content（真正的正文）");
    }

    @Test
    void fetch_videoUrl_humanMessage_notPretending() {
        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://www.toutiao.com/video/7123456789012345678/"));
        assertTrue(e.getMessage().contains("视频"), "视频要如实说读不了，而不是含糊失败");
    }

    @Test
    void fetch_noRenderData_humanMessageWithAlternative() {
        html = "<html><body>反爬页</body></html>";
        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://www.toutiao.com/article/7123456789012345678/"));
        assertTrue(e.getMessage().contains("粘进来"));
    }

    @Test
    void fetch_urlWithoutItemId_humanMessage() {
        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://www.toutiao.com/"));
        assertTrue(e.getMessage().contains("编号"));
    }

    @Test
    void itemId_recognizesBothArticleAndShortForm() {
        assertEquals("7123456789012345678",
                ToutiaoFetcher.itemId("https://www.toutiao.com/article/7123456789012345678/"));
        assertEquals("7123456789012345678",
                ToutiaoFetcher.itemId("https://m.toutiao.com/i7123456789012345678/"));
        org.junit.jupiter.api.Assertions.assertNull(ToutiaoFetcher.itemId("https://www.toutiao.com/"));
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
