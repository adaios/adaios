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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WeiboFetcher — 微博正文抓取（2026-09-13 平台抓取放开批）。
 * <p>
 * <b>为什么以前不做、现在做了</b>：原口径把微博和公众号/知乎/小红书一起归入「要登录，抓不了」，
 * 由 {@code LearnFetchService} 在门口拦掉。2026-09-13 在生产服务器实测**推翻了这一假设**：
 * {@code m.weibo.cn/statuses/show?id=<mid>} 带上**移动端 XHR 头**就能拿到完整正文 JSON，
 * **不需要任何 cookie**。既然免登录可读，就没有理由继续把用户挡在门外——
 * 那条「人话拒绝」不是安全边界，只是一条基于错误假设的保守判断。
 * <p>
 * <b>headers 是硬门槛，不是锦上添花</b>（实测请求头矩阵）：
 * 去掉 {@code X-Requested-With} → 302 跳到 visitor.passport 访客系统（看起来像「要登录」，
 * 从而被误判成需要登录态）；只留 {@code X-Requested-With} 去掉 {@code Referer}
 * → 403 errno 100015「不合法的请求」。两者都带 → 200 + 完整正文。
 * <p>
 * <b>不做的部分（如实）</b>：① **按用户/话题批量列**（{@code api/container/getIndex} 实测 432，
 * 与 RSSHub #20512 同现象）——本类只做「给一条链接、取这一条」；② 视频类内容；
 * ③ 不碰任何需要登录态的接口，不绕登录墙（B8 边界不变）。
 */
@Component
@Order(10)
public class WeiboFetcher implements LearnSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(WeiboFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    /** 见类注释：这四个头缺一不可（尤其是 X-Requested-With 与 Referer）。 */
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", UA,
            "Referer", "https://m.weibo.cn/",
            "X-Requested-With", "XMLHttpRequest",
            "MWeibo-Pwa", "1",
            "Accept", "application/json, text/plain, */*",
            "Accept-Language", "zh-CN,zh;q=0.9");

    /** 正文长度上限（与喂入端点 50000 字口径一致，防超 LLM 上下文）。 */
    static final int MAX_TEXT_CHARS = 50000;

    /** 正文长度下限：低于这个值基本是纯图片/纯转发，结构不出东西，宁可人话让用户补。 */
    private static final int MIN_TEXT_CHARS = 20;

    private static final Pattern MID_IN_PATH = Pattern.compile("/(?:status|detail)/(\\d{8,25})");
    private static final Pattern MID_IN_QUERY = Pattern.compile("(?:^|&)id=(\\d{8,25})");
    private static final Pattern PC_PATH = Pattern.compile("^/(?:u/)?\\d+/([0-9a-zA-Z]{6,12})/?$");
    /**
     * {@code "Sat Sep 13 12:00:00 +0800 2026"} 这类格式。
     * <p>
     * ⚠️ <b>年份不能靠懒惰匹配去「捞四位数字」</b>：时区偏移 {@code +0800} 本身就是四位，
     * 写成 {@code .*?(\d{4})} 会先命中它，解析出「0800 年」这种荒谬结果（测试当场抓到）。
     * 时、分、秒与时区显式写出来，年份就只能是结尾那一段。
     */
    private static final Pattern CREATED_AT = Pattern.compile(
            "[A-Z][a-z]{2} ([A-Z][a-z]{2}) (\\d{1,2}) \\d{2}:\\d{2}:\\d{2} \\S+ (\\d{4})");
    private static final Map<String, String> MONTHS = Map.ofEntries(
            Map.entry("Jan", "01"), Map.entry("Feb", "02"), Map.entry("Mar", "03"),
            Map.entry("Apr", "04"), Map.entry("May", "05"), Map.entry("Jun", "06"),
            Map.entry("Jul", "07"), Map.entry("Aug", "08"), Map.entry("Sep", "09"),
            Map.entry("Oct", "10"), Map.entry("Nov", "11"), Map.entry("Dec", "12"));

    private final HopFetch hop;
    private final String apiBase;

    public WeiboFetcher(@Value("${adai.learn.fetch.max-retry:2}") int maxRetry,
                        @Value("${adai.learn.fetch.weibo-api-base:https://m.weibo.cn}") String apiBase,
                        LearnFetchPolicy hostPolicy) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.hop = new HopFetch(hostPolicy, maxRetry);
    }

    @Override
    public String platform() {
        return "weibo";
    }

    @Override
    public boolean supports(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        return host.equals("weibo.com") || host.endsWith(".weibo.com")
                || host.equals("weibo.cn") || host.endsWith(".weibo.cn");
    }

    @Override
    public LearnSource fetch(String url) {
        String mid = midOf(url);
        if (mid == null) {
            throw new LearnException("这条微博的链接我认不出来"
                    + "（要形如 weibo.com/<uid>/<id> 或 m.weibo.cn/status/<id> 的地址）");
        }
        JsonNode root = readJson(apiBase + "/statuses/show?id=" + mid,
                "微博这次没返回能读的内容，稍后再发我一次");
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull() || data.isEmpty()) {
            throw new LearnException("这条微博读不到（可能已删除，或仅自己可见）");
        }

        String text = Html.toText(data.path("text").asText(""));
        // 长微博：正文接口只给折起的一段，完整版在 extend 接口
        if (data.path("isLongText").asBoolean(false)) {
            String longer = longText(mid);
            if (longer != null && !longer.isBlank()) {
                text = longer;
            }
        }
        if (text.isBlank() || text.length() < MIN_TEXT_CHARS) {
            throw new LearnException("这条微博的正文几乎是空的（可能是纯图片或纯转发），把内容截图发我也行");
        }
        boolean truncated = text.length() > MAX_TEXT_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_TEXT_CHARS) + "\n\n（正文过长，已截断）";
        }

        String author = textOrNull(data.path("user").path("screen_name").asText(""));
        String published = parseCreatedAt(data.path("created_at").asText(""));
        String title = headline(text);

        log.info("微博抓取完成 | mid={} | {} 字{}", mid, text.length(), truncated ? "（已截断）" : "");
        return new LearnSource(
                "weibo", mid, url.strip(), title, author, published,
                text, false, null, 0,
                List.of(new LearnSource.RawAsset("weibo-" + mid + "-text.txt", text)));
    }

    // ── 内部 ──

    private JsonNode readJson(String apiUrl, String failMessage) {
        String body = hop.page(apiUrl, HEADERS);
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("微博响应不是 JSON | url={} | {}", apiUrl, e.getMessage());
            throw new LearnException(failMessage);
        }
    }

    /** 长文接口；失败只记日志并退回折起正文（**不因为长文拿不到就整体失败**）。 */
    private String longText(String mid) {
        try {
            JsonNode root = readJson(apiBase + "/statuses/extend?id=" + mid, "微博长文没取到");
            String longText = root.path("data").path("longTextContent").asText("");
            return longText.isBlank() ? null : Html.toText(longText);
        } catch (Exception e) {
            log.warn("微博长文接口失败，退回折起正文 | mid={} | {}", mid, e.getMessage());
            return null;
        }
    }

    /**
     * 从链接取 mid：移动版直接用路径段，PC 版用 62 进制换算（见 {@link WeiboMid}）。
     * 认不出返回 null（调用方转人话，**不猜**）。
     */
    static String midOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.strip());
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getQuery() == null ? "" : uri.getQuery();

            Matcher m = MID_IN_PATH.matcher(path);
            if (m.find()) return m.group(1);

            Matcher q = MID_IN_QUERY.matcher(query);
            if (q.find()) return q.group(1);

            Matcher pc = PC_PATH.matcher(path);
            if (pc.find()) return WeiboMid.urlIdToMid(pc.group(1));

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code "Sat Sep 13 12:00:00 +0800 2026"} → {@code "2026-09-13"}；认不出返回 null。 */
    static String parseCreatedAt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = CREATED_AT.matcher(raw);
        if (!m.find()) return null;
        String month = MONTHS.get(m.group(1));
        if (month == null) return null;
        try {
            return m.group(3) + "-" + month + "-" + String.format("%02d", Integer.parseInt(m.group(2)));
        } catch (Exception e) {
            return null;
        }
    }

    /** 微博没有标题：用正文首行当前 40 字（够展示「抓到了什么」，真实标题交给下游 LLM 生成）。 */
    private static String headline(String text) {
        String firstLine = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
        if (firstLine.isBlank()) return null;
        return firstLine.length() <= 40 ? firstLine : firstLine.substring(0, 40) + "…";
    }

    private static String textOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
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
