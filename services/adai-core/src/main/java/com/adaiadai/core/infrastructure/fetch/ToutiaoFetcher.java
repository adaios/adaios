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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ToutiaoFetcher — 今日头条图文抓取（2026-09-13 平台抓取放开批）。
 * <p>
 * <b>关键在「走移动版」</b>：头条 PC 站（{@code www.toutiao.com/article/<id>/}）返回的是
 * 72,914 字节的 ByteDance JS VM 混淆反爬页（与抖音同款，无正文、无标题）；而移动版
 * {@code m.toutiao.com/i<id>/} 是**服务端渲染**的，正文全文就在页面里
 * {@code <script id="RENDER_DATA">} 那段 URL 编码的 JSON 中。
 * 所以本类的作用不是「绕过反爬」，而是**换一个正常的页面拿同样的内容**。
 * <p>
 * <b>不做的部分（如实）</b>：头条**视频**（{@code /video/<id>}）读不了——拿音轨需要 ttwid 与
 * 额外接口，收益不成正比，故明确人话告知而不是硬试。
 */
@Component
@Order(30)
public class ToutiaoFetcher implements LearnSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(ToutiaoFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 移动版 UA——PC UA 会被服务端路由到反爬页，这里与 {@code WeiboFetcher} 同口径。 */
    private static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", UA,
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language", "zh-CN,zh;q=0.9");

    static final int MAX_TEXT_CHARS = 50000;
    /** 正文下限：低于这个长度说明 RENDER_DATA 里挑中的不是正文（而是某个同名字段）。 */
    private static final int MIN_TEXT_CHARS = 100;

    private static final Pattern ID_ARTICLE = Pattern.compile("/(?:article|item)/(\\d{8,25})");
    private static final Pattern ID_SHORT = Pattern.compile("/i(\\d{8,25})");
    private static final Pattern ID_VIDEO = Pattern.compile("/video/(\\d{8,25})");
    private static final Pattern RENDER_DATA = Pattern.compile(
            "(?is)<script[^>]+id=[\"']RENDER_DATA[\"'][^>]*>(.*?)</script>");

    private final HopFetch hop;
    private final String mobileBase;

    public ToutiaoFetcher(@Value("${adai.learn.fetch.max-retry:2}") int maxRetry,
                          @Value("${adai.learn.fetch.toutiao-mobile-base:https://m.toutiao.com}")
                          String mobileBase,
                          LearnFetchPolicy hostPolicy) {
        this.mobileBase = mobileBase.endsWith("/") ? mobileBase.substring(0, mobileBase.length() - 1) : mobileBase;
        this.hop = new HopFetch(hostPolicy, maxRetry);
    }

    @Override
    public String platform() {
        return "toutiao";
    }

    @Override
    public boolean supports(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        return host.equals("toutiao.com") || host.endsWith(".toutiao.com");
    }

    @Override
    public LearnSource fetch(String url) {
        String target = url.strip();
        if (ID_VIDEO.matcher(pathOf(target)).find()) {
            throw new LearnException("头条的视频我暂时读不了（拿不到音轨），把字幕或要点粘给我也行");
        }
        String id = itemId(target);
        if (id == null) {
            throw new LearnException("这个头条链接里我没找到文章编号，重新分享一次给我试试");
        }
        // 走移动版（服务端渲染出正文）；PC 版是反爬页
        String mobileUrl = mobileBase + "/i" + id + "/";
        String html = hop.page(mobileUrl, HEADERS);

        String json = renderData(html);
        if (json == null) {
            throw new LearnException("这篇文章的正文没读出来，把正文粘进来我照样能整理");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("头条 RENDER_DATA 解析失败 | id={} | {}", id, e.getMessage());
            throw new LearnException("这篇文章的正文没读出来，把正文粘进来我照样能整理");
        }

        String text = Html.toText(longestText(root, "content", MIN_TEXT_CHARS));
        if (text.length() < MIN_TEXT_CHARS) {
            throw new LearnException("这篇文章的正文没读出来（可能是图集或视频），把正文粘进来我照样能整理");
        }
        boolean truncated = text.length() > MAX_TEXT_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_TEXT_CHARS) + "\n\n（正文过长，已截断）";
        }

        String title = firstNonBlank(firstText(root, "title"), Html.extractTitle(html));
        String author = firstText(root, "source");
        String sourceId = id;

        log.info("头条抓取完成 | id={} | {} 字{}", id, text.length(), truncated ? "（已截断）" : "");
        return new LearnSource(
                "toutiao", sourceId, target, title, author, null,
                text, false, null, 0,
                List.of(new LearnSource.RawAsset("toutiao-" + sourceId + "-text.txt", text)));
    }

    // ── 内部 ──

    /** 从链接取文章编号（{@code /article/<id>}、{@code /i<id>}）。 */
    static String itemId(String url) {
        String path = pathOf(url);
        Matcher a = ID_ARTICLE.matcher(path);
        if (a.find()) return a.group(1);
        Matcher i = ID_SHORT.matcher(path);
        if (i.find()) return i.group(1);
        return null;
    }

    private static String pathOf(String url) {
        try {
            String p = URI.create(url.strip()).getPath();
            return p == null ? "" : p;
        } catch (Exception e) {
            return "";
        }
    }

    /** 取 {@code RENDER_DATA} 的 JSON 文本（页面里是 URL 编码的）。 */
    private static String renderData(String html) {
        if (html == null) return null;
        Matcher m = RENDER_DATA.matcher(html);
        if (!m.find()) return null;
        String raw = m.group(1).strip();
        if (raw.isEmpty()) return null;
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;   // 已是明文就原样用
        }
    }

    /**
     * 在 JSON 树里递归找名为 {@code field} 的文本，返回**最长**的那个且超过 {@code minLength}。
     * <p>
     * 为什么不写死路径：头条的 {@code RENDER_DATA} 结构随版本变化，写死 {@code data.content}
     * 这种路径会在平台改版时突然空掉；而正文天然是页面里那个字段名的**最长**取值，
     * 用「最长」挑选对结构变化更有韧性（拿不到就返回空 → 人话，而不是取一个错的短字段当正文）。
     */
    private static String longestText(JsonNode root, String field, int minLength) {
        List<String> found = new ArrayList<>();
        collectText(root, field, found);
        String best = "";
        for (String s : found) {
            if (s != null && s.length() > best.length()) best = s;
        }
        return best.length() >= minLength ? best : "";
    }

    /** 第一个非空的指定字段（标题/来源这类**唯一**字段用它，不该按长度挑）。 */
    private static String firstText(JsonNode root, String field) {
        List<String> found = new ArrayList<>();
        collectText(root, field, found);
        for (String s : found) {
            if (s != null && !s.isBlank()) return Html.unescapeEntities(s).strip();
        }
        return null;
    }

    private static void collectText(JsonNode node, String field, List<String> out) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode v = node.get(field);
            if (v != null && v.isTextual()) out.add(v.asText());
            node.elements().forEachRemaining(child -> collectText(child, field, out));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectText(child, field, out));
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }
}
