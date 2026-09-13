package com.adaiadai.core.infrastructure.fetch;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnFetchPolicy;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.domain.learn.LearnSourceFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WechatFetcher — 微信公众号文章抓取（2026-09-13 平台抓取放开批）。
 * <p>
 * <b>为什么以前不做、现在做了</b>：原口径把公众号归入「有反爬，抓不了」。2026-09-13 生产实测
 * 推翻了它——{@code mp.weixin.qq.com/s/<slug>} 用**浏览器/微信 UA** 请求即可拿到完整正文
 * （3.2MB HTML，{@code id="js_content"} 里是全文），无需登录。
 * <p>
 * <b>UA 是硬门槛</b>：同一个 URL 换成 {@code curl/8.5.0} 的 UA 会返回 18KB 的**验证码页**。
 * 也就是说「公众号抓不了」这个旧结论，很可能就是当年用默认 UA 试了一次得出的。
 * 本类固定伪装成微信内置浏览器（实测与 Chrome UA 都能过，MicroMessenger 更稳）。
 * <p>
 * <b>边界（如实）</b>：① 只做**低频按链接取**——批量/高频会触发验证码，故不做列表抓取；
 * ② 不绕任何付费墙/登录墙（B8）；③ 图片不下载（正文取文字，图片链接保留在原文里没意义，
 * 需要图的场景由「截图/选图整理」承担）。
 */
@Component
@Order(20)
public class WechatFetcher implements LearnSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(WechatFetcher.class);

    /**
     * 伪装成微信内置浏览器。**这是能否拿到正文的分水岭**（见类注释），
     * 不是一个可以随手改成通用 UA 的地方。
     */
    private static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.43(0x18002b2c) "
                    + "NetType/WIFI Language/zh_CN";

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", UA,
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language", "zh-CN,zh;q=0.9");

    static final int MAX_TEXT_CHARS = 50000;
    private static final int MIN_TEXT_CHARS = 100;

    private static final Pattern MSG_TITLE = Pattern.compile(
            "var\\s+msg_title\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern NICKNAME = Pattern.compile(
            "var\\s+nickname\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern CT = Pattern.compile("var\\s+ct\\s*=\\s*[\"'](\\d{9,12})[\"']");
    private static final Pattern OG_AUTHOR = Pattern.compile(
            "(?is)<meta[^>]+property=[\"']og:article:author[\"'][^>]+content=[\"']([^\"']+)[\"']");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final HopFetch hop;

    public WechatFetcher(@Value("${adai.learn.fetch.max-retry:2}") int maxRetry,
                         LearnFetchPolicy hostPolicy) {
        this.hop = new HopFetch(hostPolicy, maxRetry);
    }

    @Override
    public String platform() {
        return "wechat";
    }

    @Override
    public boolean supports(String url) {
        String host = hostOf(url);
        return host != null && (host.equals("mp.weixin.qq.com") || host.endsWith(".mp.weixin.qq.com"));
    }

    @Override
    public LearnSource fetch(String url) {
        String target = url.strip();
        String html = hop.page(target, HEADERS);

        String content = Html.extractDivById(html, "js_content");
        if (content == null) {
            throw classifyNoContent(html);
        }
        String text = Html.toText(content);
        if (text.length() < MIN_TEXT_CHARS) {
            throw classifyNoContent(html);
        }
        boolean truncated = text.length() > MAX_TEXT_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_TEXT_CHARS) + "\n\n（正文过长，已截断）";
        }

        String title = firstNonBlank(group(MSG_TITLE, html), Html.extractTitle(html));
        String author = firstNonBlank(group(NICKNAME, html), group(OG_AUTHOR, html));
        String published = parseCt(group(CT, html));
        String sourceId = ArticleFetcher.shortHash(target);

        log.info("公众号抓取完成 | {} | {} 字{}", target, text.length(), truncated ? "（已截断）" : "");
        return new LearnSource(
                "wechat", sourceId, target, title, author, published,
                text, false, null, 0,
                List.of(new LearnSource.RawAsset("wechat-" + sourceId + "-text.txt", text)));
    }

    // ── 内部 ──

    /**
     * 没拿到正文时**分清是哪一种**——三种情况对用户的意义完全不同，不该混成一句「抓不了」：
     * 文章已删（换一篇）、抓得太频繁被验证码拦（等会儿再来）、以及确实没抽出来（让用户粘正文）。
     */
    private static LearnException classifyNoContent(String html) {
        String h = html == null ? "" : html;
        if (h.contains("该内容已被发布者删除") || h.contains("此内容因违规无法查看")
                || h.contains("该公众号已迁移")) {
            return new LearnException("这篇公众号文章已经打不开了（被删或违规），换一篇给我吧");
        }
        if (h.contains("weui-msg") && h.length() < 60000) {
            return new LearnException("这篇公众号文章打不开（可能是链接过期），重新分享一次给我试试");
        }
        if (h.length() < 40000 && (h.contains("验证") || h.contains("verify"))) {
            return new LearnException("公众号这次给我返回了验证页（我抓得有点频繁），过一会儿再发我一次");
        }
        return new LearnException("这篇公众号文章的正文没读出来，把正文粘进来我照样能整理");
    }

    /** {@code var ct = "1694567890"} → {@code 2026-09-13}（微信给的是秒级时间戳）。 */
    static String parseCt(String ct) {
        if (ct == null || ct.isBlank()) return null;
        try {
            return Instant.ofEpochSecond(Long.parseLong(ct.strip()))
                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().format(DAY);
        } catch (Exception e) {
            return null;
        }
    }

    private static String group(Pattern p, String html) {
        if (html == null) return null;
        Matcher m = p.matcher(html);
        if (!m.find()) return null;
        String v = Html.unescapeEntities(m.group(1)).strip();
        return v.isBlank() ? null : v;
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
