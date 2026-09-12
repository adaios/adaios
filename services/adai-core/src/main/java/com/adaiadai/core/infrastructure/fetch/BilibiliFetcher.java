package com.adaiadai.core.infrastructure.fetch;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.domain.learn.LearnSourceFetcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BilibiliFetcher — B站视频抓取（RFC 20260912 §3.5，learn 抓取批 2026-09-12）。
 * <p>
 * 三步取素材：
 * <ol>
 *   <li><b>元数据</b> {@code api.bilibili.com/x/web-interface/view?bvid=} → 标题/UP主/发布日期/时长/cid</li>
 *   <li><b>字幕</b> {@code x/player/v2?bvid=&cid=} → {@code subtitle.subtitles[].subtitle_url} → 正文</li>
 *   <li><b>音频线索</b> {@code x/player/playurl?bvid=&cid=&fnval=16} → {@code dash.audio[].baseUrl}</li>
 * </ol>
 * <b>「接口报错」与「确实没字幕」严格区分</b>（2026-09-12 对抗审查 P1-2 修复）：空字幕列表是**正常**
 * 情况（实测多数视频如此 → 走转写）；而限流/网络这类**可重试错误**不能降级成「无字幕」，
 * 否则会引导用户为本来能省的钱买单——此时置 {@link LearnSource#textUnavailableReason()}，由上层
 * fail-visible 提示重试，不进付费分支。
 * <p>
 * 直连不走代理；出网受 B8 边界约束：只读内容页 API，不含登录态接口，不绕付费墙。
 */
@Component
@Order(0)
public class BilibiliFetcher implements LearnSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(BilibiliFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** BV 号（固定 12 位：BV + 10 位 base58）。 */
    private static final Pattern BV_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com";

    /** B站域名白名单（内容页与短链）。 */
    private static final List<String> ALLOWED_HOSTS = List.of("bilibili.com", "b23.tv");
    /** 字幕文件域名白名单默认值（第三方响应给的地址必须收敛，见对抗审查 P0-1；可配置以便本地联调）。 */
    private static final String DEFAULT_SUBTITLE_HOSTS = "hdslb.com,bilibili.com";

    private final HttpClient httpClient;
    private final String apiBase;
    private final int maxRetry;
    private final List<String> subtitleHosts;

    public BilibiliFetcher(@Value("${adai.learn.bilibili.api-base:https://api.bilibili.com}") String apiBase,
                           @Value("${adai.learn.fetch.max-retry:2}") int maxRetry,
                           @Value("${adai.learn.bilibili.subtitle-hosts:" + DEFAULT_SUBTITLE_HOSTS + "}")
                           String subtitleHosts) {
        this.apiBase = apiBase;
        this.maxRetry = Math.max(1, maxRetry);
        this.subtitleHosts = substringHosts(subtitleHosts);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String platform() {
        return "bilibili";
    }

    @Override
    public boolean supports(String url) {
        String host = hostOf(url);
        return host != null && hostMatches(host, ALLOWED_HOSTS);
    }

    @Override
    public LearnSource fetch(String url) {
        String bvid = resolveBvid(url);
        JsonNode data = viewData(bvid);

        String title = data.path("title").asText("").strip();
        String author = data.path("owner").path("name").asText("").strip();
        long cid = data.path("cid").asLong(0);
        long duration = data.path("duration").asLong(0);
        String published = pubDate(data.path("pubdate").asLong(0));

        String subtitleText = null;
        String subtitleError = null;
        if (cid > 0) {
            try {
                subtitleText = fetchSubtitle(bvid, cid);
            } catch (Exception e) {
                // 可重试错误（限流/网络/接口报错）——不能当成「没字幕」，见类注释 P1-2
                subtitleError = "B站字幕接口这次没返回（可能限流了），稍后再试一次";
                log.warn("B站字幕获取失败，标记为可重试错误 | bvid={} | {}", bvid, e.getMessage());
            }
        }

        boolean needsTranscription = subtitleText == null || subtitleText.isBlank();
        String audioUrl = null;
        if (needsTranscription && cid > 0 && subtitleError == null) {
            try {
                audioUrl = fetchAudioUrl(bvid, cid);
            } catch (Exception e) {
                log.warn("B站音频地址获取失败 | bvid={} | {}", bvid, e.getMessage());
            }
        }

        List<LearnSource.RawAsset> raw = new ArrayList<>();
        raw.add(new LearnSource.RawAsset("bilibili-" + bvid + "-meta.json", metaJson(bvid, data)));
        if (subtitleText != null && !subtitleText.isBlank()) {
            raw.add(new LearnSource.RawAsset("bilibili-" + bvid + "-subtitle.txt", subtitleText));
        }

        log.info("B站抓取完成 | bvid={} | title={} | 时长 {}s | 字幕 {} | 音频 {} | 字幕异常 {}",
                bvid, title, duration,
                needsTranscription ? "无" : subtitleText.length() + " 字",
                audioUrl == null ? "未取到" : "已取到",
                subtitleError == null ? "无" : "有");

        return new LearnSource(
                "bilibili", bvid, url, title, author, published,
                needsTranscription ? null : subtitleText,
                needsTranscription, audioUrl, (int) duration, raw,
                needsTranscription ? subtitleError : null);
    }

    @Override
    public byte[] downloadAudio(String audioUrl) {
        if (audioUrl == null || audioUrl.isBlank()) {
            throw new LearnException("拿不到这个视频的音频地址，没法转写");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(audioUrl))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .GET().build();
        try {
            HttpBodies.Fetched fetched = HttpBodies.getBytes(httpClient, request, HttpBodies.MAX_AUDIO_BYTES);
            if (fetched.status() != 200 || fetched.bytes() == null || fetched.bytes().length == 0) {
                throw new LearnException("音频下载失败（B站返回 " + fetched.status() + "），可稍后重试");
            }
            return fetched.bytes();
        } catch (HttpBodies.TooLargeException e) {
            throw new LearnException("这个视频音频太大了，转写先跳过（可把正文粘进来）");
        } catch (IOException e) {
            throw new LearnException("音频下载失败（网络问题），可稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LearnException("音频下载被中断，请重试");
        }
    }

    // ── 内部 ──

    /** bvid 解析：URL 直取；b23.tv 短链先跟随跳转到最终 URL 再取。 */
    private String resolveBvid(String url) {
        Matcher m = BV_PATTERN.matcher(url == null ? "" : url);
        if (m.find()) return m.group();

        String host = hostOf(url);
        if (host != null && hostMatches(host, List.of("b23.tv"))) {
            try {
                HttpResponse<Void> resp = send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", UA)
                        .GET().build(), HttpResponse.BodyHandlers.discarding());
                String finalUrl = resp.uri() == null ? null : resp.uri().toString();
                String location = resp.headers().firstValue("location").orElse(null);
                for (String candidate : new String[]{finalUrl, location}) {
                    if (candidate == null) continue;
                    Matcher cm = BV_PATTERN.matcher(candidate);
                    if (cm.find()) return cm.group();
                }
            } catch (Exception e) {
                log.warn("B站短链解析失败 | url={} | {}", url, e.getMessage());
            }
        }
        throw new LearnException("没认出这个 B站链接里的视频号（BV 号），换个链接试试");
    }

    private JsonNode viewData(String bvid) {
        JsonNode root = getJson(apiBase + "/x/web-interface/view?bvid=" + bvid);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = root.path("message").asText("");
            throw new LearnException("B站没能返回这个视频的信息（"
                    + (msg.isBlank() ? "code " + code : msg) + "），请确认链接可访问");
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new LearnException("B站返回的视频信息是空的，可能视频已失效");
        }
        return data;
    }

    /**
     * 取字幕正文。
     *
     * @return 字幕文本；**null = 接口正常但没有字幕**（正常情况，走转写）
     * @throws LearnException 接口报错/限流（**可重试**，调用方不得当成「没字幕」）
     */
    private String fetchSubtitle(String bvid, long cid) {
        JsonNode root = getJson(apiBase + "/x/player/v2?bvid=" + bvid + "&cid=" + cid);
        if (root.path("code").asInt(-1) != 0) {
            throw new LearnException("B站字幕接口返回 code " + root.path("code").asInt(-1));
        }
        JsonNode subs = root.path("data").path("subtitle").path("subtitles");
        if (!subs.isArray() || subs.isEmpty()) return null;   // 确实没有字幕

        JsonNode pick = subs.get(0);
        for (JsonNode s : subs) {
            if (s.path("lan").asText("").startsWith("zh")) {
                pick = s;
                break;
            }
        }
        String subUrl = pick.path("subtitle_url").asText("");
        if (subUrl.isBlank()) return null;
        if (subUrl.startsWith("//")) subUrl = "https:" + subUrl;

        // 第三方响应给的地址必须收敛（对抗审查 P0-1）：只认 B站自己的字幕域名
        String subHost = hostOf(subUrl);
        if (subHost == null || !hostMatches(subHost, subtitleHosts)) {
            log.warn("字幕地址域名不在白名单，按无字幕处理 | host={}", subHost);
            return null;
        }

        JsonNode body = getJson(subUrl).path("body");
        if (!body.isArray() || body.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : body) {
            String content = b.path("content").asText("");
            if (!content.isBlank()) sb.append(content.strip()).append('\n');
        }
        return sb.toString().strip();
    }

    private String fetchAudioUrl(String bvid, long cid) {
        JsonNode root = getJson(apiBase + "/x/player/playurl?bvid=" + bvid + "&cid=" + cid + "&fnval=16&fourk=1");
        if (root.path("code").asInt(-1) != 0) return null;
        JsonNode dash = root.path("data").path("dash");
        JsonNode audios = dash.path("audio");
        if (audios.isArray() && !audios.isEmpty()) {
            String base = audios.get(0).path("baseUrl").asText("");
            if (!base.isBlank()) return base;
        }
        JsonNode durl = root.path("data").path("durl");
        if (durl.isArray() && !durl.isEmpty()) {
            String u = durl.get(0).path("url").asText("");
            if (!u.isBlank()) return u;
        }
        return null;
    }

    private String metaJson(String bvid, JsonNode data) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
            node.put("platform", "bilibili");
            node.put("bvid", bvid);
            node.put("title", data.path("title").asText(""));
            node.put("author", data.path("owner").path("name").asText(""));
            node.put("published", pubDate(data.path("pubdate").asLong(0)));
            node.put("duration_seconds", data.path("duration").asLong(0));
            node.put("cid", data.path("cid").asLong(0));
            node.put("desc", data.path("desc").asText(""));
            node.put("url", "https://www.bilibili.com/video/" + bvid);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return "{\"platform\":\"bilibili\",\"bvid\":\"" + bvid + "\"}";
        }
    }

    private String pubDate(long epochSeconds) {
        if (epochSeconds <= 0) return null;
        return LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.of("Asia/Shanghai")).toString();
    }

    /** 带退避重试 + 响应体上限的 JSON 请求（限流/5xx/网络抖动重试；业务 4xx 不重试）。 */
    private JsonNode getJson(String apiUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .GET().build();
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                HttpBodies.Fetched fetched = HttpBodies.getText(httpClient, request, HttpBodies.MAX_TEXT_BYTES);
                if (fetched.status() == 412 || fetched.status() == 429) {
                    throw new LearnException("B站限流了（" + fetched.status() + "），请稍后再试");
                }
                if (fetched.status() >= 500) {
                    if (attempt == maxRetry) {
                        throw new LearnException("B站接口返回 " + fetched.status() + "，抓取失败");
                    }
                    sleepBackoff(attempt);
                    continue;
                }
                if (fetched.status() != 200) {
                    throw new LearnException("B站接口返回 " + fetched.status() + "，抓取失败");
                }
                try {
                    return MAPPER.readTree(fetched.text());
                } catch (Exception e) {
                    throw new LearnException("B站返回的内容无法解析，抓取失败");
                }
            } catch (HttpBodies.TooLargeException e) {
                throw new LearnException("B站返回的内容太大了，先不抓这个");
            } catch (IOException e) {
                last = e;
                if (attempt == maxRetry) break;
                sleepBackoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LearnException("抓取被中断，请重试");
            }
        }
        throw new LearnException("连不上 B站（" + (last == null ? "网络超时" : last.getMessage()) + "），请稍后重试");
    }

    /** 无响应体要求的请求（短链跳转探测）。 */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                HttpResponse<T> resp = httpClient.send(request, handler);
                if (resp.statusCode() < 500) return resp;
                if (attempt == maxRetry) return resp;
            } catch (IOException e) {
                last = e;
                if (attempt == maxRetry) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LearnException("抓取被中断，请重试");
            }
            sleepBackoff(attempt);
        }
        throw new LearnException("连不上 B站（" + (last == null ? "网络超时" : last.getMessage()) + "），请稍后重试");
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 逗号分隔的字幕域名白名单（默认 hdslb.com,bilibili.com）。 */
    private static List<String> substringHosts(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of(DEFAULT_SUBTITLE_HOSTS.split(","));
        }
        return java.util.Arrays.stream(configured.split(","))
                .map(String::strip).filter(s -> !s.isBlank()).toList();
    }

    /** 域名白名单匹配：精确或子域（**必须补点**——`evilbilibili.com` 不能算 B站）。 */
    static boolean hostMatches(String host, List<String> allowed) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String base : allowed) {
            if (h.equals(base) || h.endsWith("." + base)) return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }
}
