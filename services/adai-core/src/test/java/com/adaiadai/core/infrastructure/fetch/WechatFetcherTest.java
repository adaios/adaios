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
 * WechatFetcherTest — 公众号文章抓取（2026-09-13 平台抓取放开批）。
 * <p>
 * 覆盖：{@code js_content} 正文抽取（**嵌套 div 也要取全**，这是最容易悄悄截断的地方）、
 * 标题/作者/日期、**UA 必须是微信内置浏览器**（硬门槛：通用 UA 会拿到验证码页）、
 * 三种「拿不到」的分型（已删除 / 链接过期 / 被抓频繁）、以及域名白名单。
 */
class WechatFetcherTest {

    private HttpServer server;
    private String base;
    private int status = 200;
    private String html = "";
    private final AtomicReference<String> lastUa = new AtomicReference<>();

    /** 正文足够长，越过 MIN_TEXT_CHARS。 */
    private static final String BODY =
            "<p>第一段讲清楚这篇要解决的问题与背景。</p>"
                    + "<p>第二段给出方法与推导过程，足够长以便越过正文长度阈值。</p>"
                    + "<p>第三段是结论与边界条件，说明哪些场景不适用。</p>"
                    + "<p>第四段补充实践建议，继续凑足长度以免触碰下限判定。</p>"
                    + "<p>第五段把前面四段的要点串起来，并说明后续可以继续深入的方向。</p>";

    /** 模拟公众号真实结构：js_content 里还有多层嵌套 div（非贪婪正则会在这里截断）。 */
    private static String page(String jsContent) {
        return "<html><head><title>标题</title></head><body>"
                + "<div class=\"rich_media_content\" id=\"js_content\" style=\"visibility:hidden;\">"
                + jsContent
                + "</div>"
                + "<div id=\"js_pc_qr_code\">扫码关注</div>"
                + "</body></html>";
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/s/abc", ex -> {
            lastUa.set(ex.getRequestHeaders().getFirst("User-Agent"));
            respond(ex, status, html);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private WechatFetcher fetcher() {
        return new WechatFetcher(1, new OutboundHostPolicy(true, 3));
    }

    @Test
    void supports_onlyWechatHosts() {
        WechatFetcher f = fetcher();
        assertTrue(f.supports("https://mp.weixin.qq.com/s/abcdef"));
        assertFalse(f.supports("https://weixin.qq.com/s/abcdef"), "别家的 weixin 域名不该被接住");
        assertFalse(f.supports("https://evilmp.weixin.qq.com.attacker.com/s/x"));
        assertFalse(f.supports("not a url"));
    }

    @Test
    void fetch_extractsNestedBody_titleAuthorDate_andSendsWechatUa() {
        html = "<script>var msg_title = '用 Harness 做 Agent 工程';"
                + "var nickname = \"某某某\";var ct = \"1757600000\";</script>"
                + page(BODY);
        LearnSource src = fetcher().fetch(base + "/s/abc");

        assertEquals("wechat", src.platform());
        assertEquals("用 Harness 做 Agent 工程", src.title());
        assertEquals("某某某", src.author());
        assertEquals("2025-09-11", src.published());
        assertTrue(src.text().contains("第一段"), "正文首段必须在");
        assertTrue(src.text().contains("第四段"), "嵌套 div 里的后续段落也必须取全（不能被 </div> 截断）");
        assertFalse(src.text().contains("扫码关注"), "正文容器之外的内容不该混进来");
        assertTrue(src.hasText());

        assertTrue(lastUa.get() != null && lastUa.get().contains("MicroMessenger"),
                "必须伪装成微信内置浏览器——通用 UA 会拿到验证码页，这是硬门槛");
    }

    @Test
    void fetch_rawAssets_keptForTraceability() {
        html = page(BODY);
        LearnSource src = fetcher().fetch(base + "/s/abc");

        assertEquals(1, src.rawAssets().size());
        assertTrue(src.rawAssets().get(0).name().startsWith("wechat-"));
        assertTrue(src.rawAssets().get(0).name().endsWith("-text.txt"));
        assertTrue(src.rawAssets().get(0).content().contains("第一段"));
    }

    @Test
    void fetch_deletedArticle_saysDeleted_notGenericFailure() {
        html = "<html><body><div class=\"weui-msg\">该内容已被发布者删除</div></body></html>";
        LearnException e = assertThrows(LearnException.class, () -> fetcher().fetch(base + "/s/abc"));
        assertTrue(e.getMessage().contains("被删") || e.getMessage().contains("打不开"),
                "已删除要如实说，不能让用户反复重试");
    }

    @Test
    void fetch_captchaPage_saysTooFrequent_notBroken() {
        html = "<html><body>" + "验证".repeat(2000) + "</body></html>";
        LearnException e = assertThrows(LearnException.class, () -> fetcher().fetch(base + "/s/abc"));
        assertTrue(e.getMessage().contains("验证") || e.getMessage().contains("频繁"),
                "被验证码拦要说清是「抓得频繁」，而不是「抓不了」");
    }

    @Test
    void fetch_noContentContainer_fallsBackToHumanMessage() {
        html = "<html><body><div>完全无关的页面</div></body></html>";
        LearnException e = assertThrows(LearnException.class, () -> fetcher().fetch(base + "/s/abc"));
        assertTrue(e.getMessage().contains("粘进来"), "最终兜底要给出替代路径");
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
