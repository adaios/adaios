package com.adaiadai.core.infrastructure.fetch;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnFetchPolicy;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.domain.learn.LearnSourceFetcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * ArticleFetcher — 通用文章 URL 抓取（RFC 20260912 §3.5，learn 抓取批 2026-09-12；
 * 2026-09-13 平台抓取放开批重构）。
 * <p>
 * 抓 HTML → 去 script/style/nav 转纯文本（保留标题结构）；反爬/Cloudflare 403 → 快照兜底；
 * 仍失败 → 人话提示请用户粘正文（fail-visible，不产废卡）。
 * <p>
 * <b>本批的两处变化</b>：
 * <ol>
 *   <li><b>出站骨架收敛到 {@link HopFetch}</b>：逐跳白名单 / 手工跟跳转 / 退避重试 / 响应体上限
 *       原先写在本类里；新增微博/公众号/头条三个抓取器后，这类「出站纪律」若各写一份必然会漂移，
 *       故抽成公共骨架，本类与三个新抓取器共用同一套。</li>
 *   <li><b>快照兜底改为默认关闭</b>：{@code archive.org} 在**大陆服务器实测不可达**
 *       （2026-09-13 生产实测 8 秒超时、DNS 返回 {@code 2001::1} 污染地址）。原先每次抓取失败
 *       都会去问一次快照，结果是**白等 8 秒再失败**——兜底不但没兜住，还给每次失败加了延迟。
 *       现在要显式配 {@code adai.learn.fetch.wayback-api} 才启用（配境外中转地址时才有意义）。</li>
 * </ol>
 * <p>
 * <b>与专用抓取器的分工</b>：本类仍是**兜底**（{@code supports} 接受任意 http/https，
 * 故 {@code @Order(100)} 排在所有专用抓取器之后）。微博/公众号/头条已各由专用抓取器接管
 * （它们需要各自的请求头与正文定位方式，通用抽取取不到好结果）。
 */
@Component
@Order(100)
public class ArticleFetcher implements LearnSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(ArticleFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", UA,
            "Accept", "text/html,application/xhtml+xml",
            "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

    /** 正文长度上限：与喂入端点 50000 字上限一致（超出截断并标注，防超 LLM 上下文）。 */
    static final int MAX_TEXT_CHARS = 50000;

    /** 正文长度下限：低于此值判为反爬空壳页（有登录墙/JS 渲染的站常见）。 */
    private static final int MIN_TEXT_CHARS = 200;

    private final String waybackApi;
    private final HopFetch hop;

    public ArticleFetcher(@Value("${adai.learn.fetch.wayback-api:}") String waybackApi,
                          @Value("${adai.learn.fetch.max-retry:2}") int maxRetry,
                          LearnFetchPolicy hostPolicy) {
        // 默认空 = 关闭快照兜底（见类注释：大陆服务器连不上 archive.org，开着只会白等）
        this.waybackApi = waybackApi == null ? "" : waybackApi.strip();
        this.hop = new HopFetch(hostPolicy, maxRetry);
    }

    @Override
    public String platform() {
        return "article";
    }

    @Override
    public boolean supports(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String scheme = java.net.URI.create(url.strip()).getScheme();
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
            html = hop.page(target, HEADERS);
        } catch (HopFetch.BlockedException direct) {
            String snapshot = waybackUrl(target);
            if (snapshot == null) {
                throw new LearnException("这篇文章抓不下来（可能有反爬），把正文粘进来我照样能整理");
            }
            log.info("原文抓取失败，改走快照 | url={} | snapshot={}", target, snapshot);
            try {
                html = hop.page(snapshot, HEADERS);
            } catch (LearnException archiveFail) {
                throw new LearnException("这篇文章抓不下来（可能有反爬），把正文粘进来我照样能整理");
            }
            // 注：快照也失败时用通用人话；而策略拒绝/跳转失控/过大等自带原因的失败**不走兜底**
            //（快照服务多半同样被拒，保留原始原因对排查更有用）
        }

        String title = Html.extractTitle(html);
        String text = Html.toText(html);
        if (text.isBlank() || text.length() < MIN_TEXT_CHARS) {
            throw new LearnException("这篇文章的正文几乎没抓到（可能要登录或有反爬），把正文粘进来我照样能整理");
        }
        boolean truncated = text.length() > MAX_TEXT_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_TEXT_CHARS) + "\n\n（正文过长，已截断）";
        }
        String site = Html.firstGroup(
                java.util.regex.Pattern.compile(
                        "(?is)<meta[^>]+property=[\"']og:site_name[\"'][^>]+content=[\"']([^\"']+)[\"']"),
                html);
        String sourceId = shortHash(target);

        log.info("文章抓取完成 | {} | {} 字{}", target, text.length(), truncated ? "（已截断）" : "");
        return new LearnSource(
                "article", sourceId, target, title, site, null,
                text, false, null, 0,
                java.util.List.of(new LearnSource.RawAsset("article-" + sourceId + "-text.txt", text)));
    }

    // ── 内部 ──

    /**
     * 快照查询（免费兜底）；**未配置或查不到 → null**。
     * <p>
     * 默认未配置：见类注释——{@code archive.org} 在大陆服务器不可达，开着只会给每次失败
     * 平白加 8 秒超时。这曾是一个「看起来有兜底、实际一直在空转」的静默失效。
     */
    private String waybackUrl(String url) {
        if (waybackApi.isBlank()) {
            return null;
        }
        try {
            String api = waybackApi + "?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
            HttpBodies.Fetched fetched = hop.once(api, HEADERS);
            if (!fetched.ok() || fetched.text() == null) return null;
            JsonNode snap = MAPPER.readTree(fetched.text()).path("archived_snapshots").path("closest");
            String snapUrl = snap.path("url").asText("");
            if (snapUrl.isBlank()) return null;
            // archive.org 返回的多为 http 链接，升级到 https（其余快照地址原样保留，不擅自改协议）
            if (snapUrl.startsWith("http://") && snapUrl.contains("archive.org")) {
                snapUrl = "https://" + snapUrl.substring("http://".length());
            }
            return snapUrl;
        } catch (Exception e) {
            log.warn("快照查询失败 | url={} | {}", url, e.getMessage());
            return null;
        }
    }

    // ── 静态工具（实现已收敛到 Html；此处保留同名入口，避免调用方与测试大范围改动）──

    static String htmlToText(String html) {
        return Html.toText(html);
    }

    static String unescapeEntities(String s) {
        return Html.unescapeEntities(s);
    }

    static String extractTitle(String html) {
        return Html.extractTitle(html);
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
}
