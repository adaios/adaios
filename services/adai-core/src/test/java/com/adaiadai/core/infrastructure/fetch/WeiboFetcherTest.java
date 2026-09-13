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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WeiboFetcherTest — 微博正文抓取（2026-09-13 平台抓取放开批）。
 * <p>
 * 覆盖：移动版链接直接取、**PC 版链接经 62 进制换算后取**、**XHR 头必须带上**
 * （这是能否拿到正文的硬门槛，不是可选优化）、长文走 extend、空正文人话、
 * 以及域名白名单。
 */
class WeiboFetcherTest {

    private HttpServer server;
    private String base;
    private int showStatus = 200;
    private String showBody = "";
    private String extendBody = "";

    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastXhr = new AtomicReference<>();
    private final AtomicReference<String> lastReferer = new AtomicReference<>();

    /** 正文需超过 MIN_TEXT_CHARS(20)。 */
    private static final String LONG_ENOUGH =
            "<a href='#'>#话题#</a> 这是微博正文，需要足够长才能通过最短长度判定，继续补充文字。";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/statuses/show", ex -> {
            lastQuery.set(ex.getRequestURI().getQuery());
            lastXhr.set(ex.getRequestHeaders().getFirst("X-Requested-With"));
            lastReferer.set(ex.getRequestHeaders().getFirst("Referer"));
            respond(ex, showStatus, showBody);
        });
        server.createContext("/statuses/extend", ex -> {
            lastQuery.set(ex.getRequestURI().getQuery());
            respond(ex, 200, extendBody);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private WeiboFetcher fetcher() {
        return new WeiboFetcher(1, base, new OutboundHostPolicy(true, 3));
    }

    private static String showJson(String text, boolean isLongText) {
        return "{\"ok\":1,\"data\":{\"id\":\"3520617028999724\",\"text\":\"" + text + "\","
                + "\"user\":{\"screen_name\":\"某某某\"},"
                + "\"created_at\":\"Sat Sep 13 12:00:00 +0800 2026\","
                + "\"isLongText\":" + isLongText + "}}";
    }

    @Test
    void supports_onlyWeiboHosts() {
        WeiboFetcher f = fetcher();
        assertTrue(f.supports("https://m.weibo.cn/status/3520617028999724"));
        assertTrue(f.supports("https://weibo.com/1234567890/z8ElgBLeQ"));
        assertTrue(f.supports("https://weibo.cn/sinaurl?u=x"));
        assertFalse(f.supports("https://evilweibo.com/1234567890/z8ElgBLeQ"));
        assertFalse(f.supports("https://weibo.com.attacker.com/x/y"));
    }

    @Test
    void fetch_mobileUrl_extractsText_author_date_andSendsXhrHeaders() {
        showBody = showJson(LONG_ENOUGH, false);
        LearnSource src = fetcher().fetch("https://m.weibo.cn/status/3520617028999724");

        assertEquals("weibo", src.platform());
        assertEquals("3520617028999724", src.sourceId());
        assertEquals("某某某", src.author());
        assertEquals("2026-09-13", src.published());
        assertTrue(src.text().contains("这是微博正文"), "HTML 正文要转成文本");
        assertFalse(src.text().contains("<a"), "标签不该留在正文里");
        assertFalse(src.needsTranscription(), "微博是图文，不需要转写");

        assertEquals("id=3520617028999724", lastQuery.get());
        assertEquals("XMLHttpRequest", lastXhr.get(),
                "X-Requested-With 是硬门槛：缺了会 302 到访客系统（看起来像「要登录」）");
        assertTrue(lastReferer.get() != null && lastReferer.get().contains("m.weibo.cn"),
                "Referer 是硬门槛：缺了会 403 errno 100015");
    }

    /** PC 版链接里的 id 和接口要的 mid 不是一回事——这条锁住换算确实接在了请求上。 */
    @Test
    void fetch_pcUrl_convertsUrlIdToMidBeforeCallingApi() {
        showBody = showJson(LONG_ENOUGH, false);
        fetcher().fetch("https://weibo.com/1234567890/z8ElgBLeQ");

        assertEquals("id=3520617028999724", lastQuery.get(),
                "z8ElgBLeQ 应换成 mid 3520617028999724，而不是把 url id 直接当 mid 用");
    }

    @Test
    void fetch_longText_prefersExtendApiContent() {
        showBody = showJson("这是折起的一小段开头。", true);
        extendBody = "{\"ok\":1,\"data\":{\"longTextContent\":\""
                + "这是完整长文正文，内容明显比折起版更长，用于验证确实改用了 extend 接口的结果。"
                + "\"}}";
        LearnSource src = fetcher().fetch("https://m.weibo.cn/status/3520617028999724");

        assertTrue(src.text().contains("完整长文正文"), "长微博应取 extend 的全文");
    }

    @Test
    void fetch_extendFails_fallsBackToTruncatedText_notFailingWholeFetch() {
        showBody = showJson(LONG_ENOUGH, true);
        extendBody = "不是 JSON";
        LearnSource src = fetcher().fetch("https://m.weibo.cn/status/3520617028999724");

        assertTrue(src.text().contains("这是微博正文"),
                "长文接口挂了不该让整次抓取失败——折起版也是可用内容");
    }

    @Test
    void fetch_emptyOrImageOnlyPost_humanMessage() {
        showBody = showJson("转发微博", false);
        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://m.weibo.cn/status/3520617028999724"));
        assertTrue(e.getMessage().contains("截图"), "纯图/纯转发要引导用截图，而不是含糊报错");
    }

    @Test
    void fetch_deletedPost_humanMessage() {
        showBody = "{\"ok\":0,\"data\":{}}";
        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://m.weibo.cn/status/3520617028999724"));
        assertTrue(e.getMessage().contains("读不到") || e.getMessage().contains("删除"));
    }

    @Test
    void fetch_unrecognizableUrl_humanMessage_notGuessing() {
        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://weibo.com/"));
        assertTrue(e.getMessage().contains("认不出来"));
    }

    @Test
    void parseCreatedAt_weiboFormat() {
        assertEquals("2026-09-13", WeiboFetcher.parseCreatedAt("Sat Sep 13 12:00:00 +0800 2026"));
        assertEquals("2025-01-05", WeiboFetcher.parseCreatedAt("Sun Jan 05 08:30:00 +0800 2025"));
        org.junit.jupiter.api.Assertions.assertNull(WeiboFetcher.parseCreatedAt("刚刚"));
        org.junit.jupiter.api.Assertions.assertNull(WeiboFetcher.parseCreatedAt(null));
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
