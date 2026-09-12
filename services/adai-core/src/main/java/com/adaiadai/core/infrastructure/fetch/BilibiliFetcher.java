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
 * 无字幕 → {@code needsTranscription=true}（**实测多数视频确实无字幕**，这是 D 形态必须对接
 * ASR 的依据，见 pitfall「B站字幕接口默认拿不到」）。注意字幕接口未登录时常返回空列表——
 * 空列表只作「疑似无字幕」信号，真正的判决在转写分流处（fail-visible，不静默产废卡）。
 * <p>
 * 直连不走代理（技能文档口径：B站 API 国内直连）；出网受 B8 边界约束：只读内容页 API，
 * 不含登录态接口，不绕付费墙。
 */
@Component
@org.springframework.core.annotation.Order(0)
public class BilibiliFetcher implements LearnSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(BilibiliFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** BV 号（固定 12 位：BV + 10 位 base58）。 */
    private static final Pattern BV_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com";

    private final HttpClient httpClient;
    private final String apiBase;
    private final int maxRetry;

    public BilibiliFetcher(@Value("${adai.learn.bilibili.api-base:https://api.bilibili.com}") String apiBase,
                           @Value("${adai.learn.fetch.max-retry:2}") int maxRetry) {
        this.apiBase = apiBase;
        this.maxRetry = Math.max(1, maxRetry);
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
        return host != null && (host.endsWith("bilibili.com") || host.endsWith("b23.tv"));
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
        if (cid > 0) {
            try {
                subtitleText = fetchSubtitle(bvid, cid);
            } catch (Exception e) {
                log.warn("B站字幕获取失败（转写分流）| bvid={} | {}", bvid, e.getMessage());
            }
        }

        String audioUrl = null;
        boolean needsTranscription = subtitleText == null || subtitleText.isBlank();
        if (needsTranscription && cid > 0) {
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

        log.info("B站抓取完成 | bvid={} | title={} | 时长 {}s | 字幕 {} | 音频 {}",
                bvid, title, duration,
                needsTranscription ? "无" : subtitleText.length() + " 字",
                audioUrl == null ? "未取到" : "已取到");

        return new LearnSource(
                "bilibili", bvid, url, title, author, published,
                needsTranscription ? null : subtitleText,
                needsTranscription, audioUrl, (int) duration, raw);
    }

    @Override
    public byte[] downloadAudio(String audioUrl) {
        if (audioUrl == null || audioUrl.isBlank()) {
            throw new LearnException("拿不到这个视频的音频地址，没法转写");
        }
        HttpResponse<byte[]> resp = send(HttpRequest.newBuilder(URI.create(audioUrl))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) {
            throw new LearnException("音频下载失败（B站返回 " + resp.statusCode() + "），可稍后重试");
        }
        return resp.body();
    }

    // ── 内部 ──

    /** bvid 解析：URL 直取；b23.tv 短链先跟随跳转到最终 URL 再取。 */
    private String resolveBvid(String url) {
        Matcher m = BV_PATTERN.matcher(url == null ? "" : url);
        if (m.find()) return m.group();

        String host = hostOf(url);
        if (host != null && host.endsWith("b23.tv")) {
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
        String api = apiBase + "/x/web-interface/view?bvid=" + bvid;
        JsonNode root = getJson(api);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = root.path("message").asText("");
            throw new LearnException("B站没能返回这个视频的信息（" + (msg.isBlank() ? "code " + code : msg) + "），请确认链接可访问");
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new LearnException("B站返回的视频信息是空的，可能视频已失效");
        }
        return data;
    }

    private String fetchSubtitle(String bvid, long cid) {
        JsonNode root = getJson(apiBase + "/x/player/v2?bvid=" + bvid + "&cid=" + cid);
        if (root.path("code").asInt(-1) != 0) return null;
        JsonNode subs = root.path("data").path("subtitle").path("subtitles");
        if (!subs.isArray() || subs.isEmpty()) return null;
        JsonNode pick = subs.get(0);
        for (JsonNode s : subs) {
            String lan = s.path("lan").asText("");
            if (lan.startsWith("zh")) {
                pick = s;
                break;
            }
        }
        String subUrl = pick.path("subtitle_url").asText("");
        if (subUrl.isBlank()) return null;
        if (subUrl.startsWith("//")) subUrl = "https:" + subUrl;
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

    private JsonNode getJson(String apiUrl) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 412 || resp.statusCode() == 429) {
            throw new LearnException("B站限流了（" + resp.statusCode() + "），请稍后再试");
        }
        if (resp.statusCode() != 200) {
            throw new LearnException("B站接口返回 " + resp.statusCode() + "，抓取失败");
        }
        try {
            return MAPPER.readTree(resp.body());
        } catch (Exception e) {
            throw new LearnException("B站返回的内容无法解析，抓取失败");
        }
    }

    /** 带退避重试的发送（限流/网络抖动重试；4xx 业务错误不重试）。 */
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
