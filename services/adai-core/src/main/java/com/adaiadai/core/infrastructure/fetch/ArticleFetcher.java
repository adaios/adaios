package com.adaiadai.core.infrastructure.fetch;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.domain.learn.LearnSourceFetcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ArticleFetcher — 文章 URL 抓取（RFC 20260912 §3.5，learn 抓取批 2026-09-12）。
 * <p>
 * curl 抓 HTML → 去 script/style/nav 转纯文本（保留标题结构）；反爬/Cloudflare 403 →
 * **Web Archive 兜底**；仍失败 → 人话提示请用户粘正文（fail-visible，不产废卡）。
 * <p>
 * 社交平台（公众号/知乎/小红书/X 等）反爬与登录墙多，**首期不做自动抓取**（由
 * {@code LearnFetchRouter} 拦截并提示粘正文/截图），不强行绕过（B8 版权与授权边界）。
 */
@Component
@org.springframework.core.annotation.Order(100)
public class ArticleFetcher implements LearnSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(ArticleFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** 正文长度上限：与喂入端点 50000 字上限一致（超出截断并标注，防超 LLM 上下文）。 */
    static final int MAX_TEXT_CHARS = 50000;

    private static final Pattern OG_TITLE = Pattern.compile(
            "(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final Pattern OG_TITLE_REV = Pattern.compile(
            "(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:title[\"']");
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_SITE = Pattern.compile(
            "(?is)<meta[^>]+property=[\"']og:site_name[\"'][^>]+content=[\"']([^\"']+)[\"']");

    private final HttpClient httpClient;
    private final String waybackApi;
    private final int maxRetry;

    public ArticleFetcher(@Value("${adai.learn.fetch.wayback-api:https://archive.org/wayback/available}") String waybackApi,
                          @Value("${adai.learn.fetch.max-retry:2}") int maxRetry) {
        this.waybackApi = waybackApi;
        this.maxRetry = Math.max(1, maxRetry);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String platform() {
        return "article";
    }

    @Override
    public boolean supports(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String scheme = URI.create(url.strip()).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public LearnSource fetch(String url) {
        String target = url.strip();
        String html;
        try {
            html = getHtml(target);
        } catch (LearnException direct) {
            String snapshot = waybackUrl(target);
            if (snapshot == null) {
                throw new LearnException("这篇文章抓不下来（可能有反爬），把正文粘进来我照样能整理");
            }
            log.info("原文抓取失败，改走 Web Archive 快照 | url={} | snapshot={}", target, snapshot);
            try {
                html = getHtml(snapshot);
            } catch (LearnException archiveFail) {
                throw new LearnException("这篇文章抓不下来（可能有反爬），把正文粘进来我照样能整理");
            }
        }

        String title = extractTitle(html);
        String text = htmlToText(html);
        if (text.isBlank() || text.length() < 200) {
            throw new LearnException("这篇文章的正文几乎没抓到（可能要登录或有反爬），把正文粘进来我照样能整理");
        }
        boolean truncated = text.length() > MAX_TEXT_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_TEXT_CHARS) + "\n\n（正文过长，已截断）";
        }
        String site = firstGroup(OG_SITE, html);
        String sourceId = shortHash(target);

        log.info("文章抓取完成 | {} | {} 字{}", target, text.length(), truncated ? "（已截断）" : "");
        return new LearnSource(
                "article", sourceId, target, title, site, null,
                text, false, null, 0,
                List.of(new LearnSource.RawAsset("article-" + sourceId + "-text.txt", text)));
    }

    // ── 内部 ──

    private String getHtml(String url) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET().build());
        int code = resp.statusCode();
        if (code == 403 || code == 401 || code == 429 || code == 503) {
            throw new LearnException("这篇文章拒绝访问（" + code + "）");
        }
        if (code != 200) {
            throw new LearnException("文章页返回 " + code + "，抓取失败");
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new LearnException("文章页返回空内容");
        }
        return body;
    }

    /** Web Archive 快照查询（免费兜底）；无快照 → null。 */
    private String waybackUrl(String url) {
        try {
            String api = waybackApi + "?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
            HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", UA)
                    .GET().build());
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            JsonNode snap = MAPPER.readTree(resp.body()).path("archived_snapshots").path("closest");
            String snapUrl = snap.path("url").asText("");
            if (snapUrl.isBlank()) return null;
            // archive.org 返回的多为 http 链接，升级到 https（其余快照地址原样保留，不擅自改协议）
            if (snapUrl.startsWith("http://") && snapUrl.contains("archive.org")) {
                snapUrl = "https://" + snapUrl.substring("http://".length());
            }
            return snapUrl;
        } catch (Exception e) {
            log.warn("Web Archive 查询失败 | url={} | {}", url, e.getMessage());
            return null;
        }
    }

    /** HTML → 纯文本（去脚本/样式/导航，块级标签转换行，解实体）。 */
    static String htmlToText(String html) {
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

    static String extractTitle(String html) {
        String t = firstGroup(OG_TITLE, html);
        if (t == null) t = firstGroup(OG_TITLE_REV, html);
        if (t == null) t = firstGroup(TITLE_TAG, html);
        if (t == null) return null;
        t = unescapeEntities(t).strip();
        return t.isBlank() ? null : t;
    }

    private static String firstGroup(Pattern p, String html) {
        if (html == null) return null;
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** 文章短标识（_raw/ 命名 + 「同素材只处理一次」幂等键）。 */
    static String shortHash(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 500) return resp;
                if (attempt == maxRetry) return resp;
            } catch (IOException e) {
                last = e;
                if (attempt == maxRetry) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LearnException("抓取被中断，请重试");
            }
            try {
                Thread.sleep(500L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        throw new LearnException("连不上这篇文章的站点（" + (last == null ? "网络超时" : last.getMessage()) + "），请稍后重试");
    }
}
