package com.adaiadai.core.infrastructure.fetch;

import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BilibiliFetcherTest — B站抓取解析（RFC 20260912 §3.5，learn 抓取批 2026-09-12）。
 * <p>
 * 用本地 mock server 锁住三类路径：**有字幕**（免费路径，不转写）、**无字幕**（转写分流，
 * 要给 audioUrl 与时长）、**接口报错/限流**（人话 + 不产废卡）。不依赖真实 B站网络，
 * 保证 CI 稳定；真实可达性另走人工实测。
 */
class BilibiliFetcherTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger viewCalls = new AtomicInteger();
    private final AtomicReference<String> lastViewQuery = new AtomicReference<>();
    private final AtomicReference<String> lastPlayurlQuery = new AtomicReference<>();

    private String viewBody = """
            {"code":0,"data":{"bvid":"BV1xx411c7mD","cid":123456,"title":"用 Harness 做 Agent 工程",
            "desc":"简介","duration":2244,"pubdate":1777910400,"owner":{"name":"某UP主"}}}""";
    private int viewStatus = 200;
    private String playerBody = """
            {"code":0,"data":{"subtitle":{"subtitles":[]}}}""";
    private String playurlBody = """
            {"code":0,"data":{"dash":{"audio":[{"baseUrl":"AUDIO_URL"}]}}}""";
    private String subtitleBody = """
            {"body":[{"from":0,"to":2,"content":"第一句"},{"from":2,"to":4,"content":"第二句"}]}""";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/x/web-interface/view", ex -> {
            viewCalls.incrementAndGet();
            lastViewQuery.set(ex.getRequestURI().getQuery());
            respond(ex, viewStatus, viewBody);
        });
        server.createContext("/x/player/v2", ex -> respond(ex, 200, playerBody));
        server.createContext("/x/player/playurl", ex -> {
            lastPlayurlQuery.set(ex.getRequestURI().getQuery());
            respond(ex, 200, playurlBody.replace("AUDIO_URL", base + "/audio.m4s"));
        });
        server.createContext("/subtitle.json", ex -> respond(ex, 200, subtitleBody));
        server.createContext("/audio.m4s", ex -> {
            byte[] body = new byte[]{7, 7, 7, 7};
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private BilibiliFetcher fetcher() {
        // 本地 mock server 是 127.0.0.1，故把字幕域名白名单配置成本机（生产为 hdslb.com/bilibili.com）
        return new BilibiliFetcher(base, 2, "127.0.0.1");
    }

    // ── 域名识别（B8 白名单：只认内容页域名）──

    @Test
    void supports_onlyBilibiliHosts() {
        BilibiliFetcher f = fetcher();
        assertTrue(f.supports("https://www.bilibili.com/video/BV1xx411c7mD"));
        assertTrue(f.supports("https://b23.tv/abcd"));
        assertFalse(f.supports("https://example.com/article"));
        assertFalse(f.supports("https://www.youtube.com/watch?v=x"));
        assertFalse(f.supports(null));
    }

    @Test
    void platform_isBilibili() {
        assertEquals("bilibili", fetcher().platform());
    }

    // ── 有字幕：免费路径（费用可控条 1）──

    @Test
    void fetch_withSubtitle_usesSubtitleAndDoesNotNeedTranscription() {
        playerBody = """
                {"code":0,"data":{"subtitle":{"subtitles":[
                  {"lan":"ai-zh","lan_doc":"中文","subtitle_url":"PLACEHOLDER/subtitle.json"}]}}}"""
                .replace("PLACEHOLDER", base);

        LearnSource source = fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD");

        assertFalse(source.needsTranscription(), "有字幕就不该走花钱的转写");
        assertTrue(source.text().contains("第一句") && source.text().contains("第二句"));
        assertEquals("BV1xx411c7mD", source.sourceId());
        assertEquals("用 Harness 做 Agent 工程", source.title());
        assertEquals("某UP主", source.author());
        assertEquals(2244, source.durationSeconds());
        assertEquals("2026-05-05", source.published());
    }

    @Test
    void fetch_withSubtitle_archivesMetaAndSubtitleButNoAudioDownload() {
        playerBody = """
                {"code":0,"data":{"subtitle":{"subtitles":[
                  {"lan":"zh-CN","lan_doc":"中文","subtitle_url":"PLACEHOLDER/subtitle.json"}]}}}"""
                .replace("PLACEHOLDER", base);

        LearnSource source = fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD");

        List<String> names = source.rawAssets().stream().map(LearnSource.RawAsset::name).toList();
        assertTrue(names.contains("bilibili-BV1xx411c7mD-meta.json"), "元数据必留痕");
        assertTrue(names.contains("bilibili-BV1xx411c7mD-subtitle.txt"), "字幕必留痕");
        String meta = source.rawAssets().stream()
                .filter(a -> a.name().endsWith("-meta.json")).findFirst().orElseThrow().content();
        assertTrue(meta.contains("\"bvid\"") && meta.contains("\"duration_seconds\" : 2244"));
    }

    // ── 无字幕：转写分流（D 形态必须对接 ASR 的依据）──

    @Test
    void fetch_withoutSubtitle_flagsTranscription_andCarriesAudioUrl() {
        playerBody = """
                {"code":0,"data":{"subtitle":{"subtitles":[]}}}""";

        LearnSource source = fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD");

        assertTrue(source.needsTranscription(), "无字幕 → 必须标记需转写（不静默产废卡）");
        assertEquals(null, source.text());
        assertEquals(base + "/audio.m4s", source.audioUrl());
        assertEquals(2244, source.durationSeconds(), "时长要带出来（费用预估的依据）");
        assertEquals(1, source.rawAssets().size(), "无字幕时只留元数据");
    }

    @Test
    void fetch_subtitleInterfaceErrors_marksRetryableInsteadOfOfferingPaidTranscription() {
        // 对抗审查 P1-2：接口报错/限流是**可重试错误**，不是「确实没字幕」。
        // 降级成「无字幕→花钱转写」= 引导用户为本来能省的钱买单。
        playerBody = """
                {"code":-352,"message":"风控校验失败"}""";

        LearnSource source = fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD");

        assertTrue(source.needsTranscription(), "确实需要文本，但——");
        assertTrue(source.textBlockedByError(), "——要标记为「可重试错误」，上层据此走人话失败而非付费分支");
        assertTrue(source.textUnavailableReason().contains("稍后再试"));
        assertEquals(null, source.audioUrl(), "报错时不该再去取音频（省一次请求）");
    }

    @Test
    void fetch_emptySubtitleList_isNormalNotError() {
        playerBody = """
                {"code":0,"data":{"subtitle":{"subtitles":[]}}}""";

        LearnSource source = fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD");

        assertTrue(source.needsTranscription());
        assertFalse(source.textBlockedByError(), "空列表是正常情况（实测多数视频如此）→ 走转写");
        assertTrue(source.audioUrl() != null, "正常无字幕时应取到音频线索");
    }

    @Test
    void supports_rejectsLookalikeHost() {
        BilibiliFetcher f = fetcher();
        assertFalse(f.supports("https://evilbilibili.com/video/BV1xx411c7mD"),
                "少一个点的 endsWith 会把 evilbilibili.com 当 B站");
        assertFalse(f.supports("https://bilibili.com.evil.com/x"));
    }

    @Test
    void fetch_subtitleHostOutsideWhitelist_treatedAsNoSubtitle() {
        // 第三方响应给的地址必须收敛（对抗审查 P0-1）：非白名单域名不 fetch
        playerBody = """
                {"code":0,"data":{"subtitle":{"subtitles":[
                  {"lan":"zh-CN","lan_doc":"中文","subtitle_url":"https://evil.example.com/sub.json"}]}}}""";

        LearnSource source = fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD");

        assertFalse(source.hasText(), "白名单外的字幕地址不抓（按无字幕处理）");
        assertTrue(source.needsTranscription());
    }

    @Test
    void fetch_playurlFails_keepsTranscriptionFlagWithNullAudio() {
        playurlBody = """
                {"code":-10403,"message":"抱歉您所在地区不可观看" }""";

        LearnSource source = fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD");

        assertTrue(source.needsTranscription());
        assertEquals(null, source.audioUrl(), "拿不到音频地址时如实为空（上层给不出转写会人话提示）");
    }

    // ── 失败路径：人话，不产半成品 ──

    @Test
    void fetch_videoNotExist_throwsHumanMessage() {
        viewBody = """
                {"code":-404,"message":"啥都木有"}""";

        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD"));

        assertTrue(e.getMessage().contains("啥都木有") || e.getMessage().contains("没能返回"));
    }

    @Test
    void fetch_rateLimited_throwsHumanMessage() {
        viewStatus = 412;

        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD"));

        assertTrue(e.getMessage().contains("限流"), "限流要人话提示稍后再试");
    }

    @Test
    void fetch_urlWithoutBv_throwsHumanMessage() {
        LearnException e = assertThrows(LearnException.class,
                () -> fetcher().fetch("https://www.bilibili.com/video/av12345"));

        assertTrue(e.getMessage().contains("BV"), "要说清楚没认出视频号");
    }

    @Test
    void fetch_bvExtractedFromQueryParams() {
        fetcher().fetch("https://www.bilibili.com/video/BV1xx411c7mD?p=1&t=30");

        assertTrue(lastViewQuery.get().contains("bvid=BV1xx411c7mD"));
        assertEquals(1, viewCalls.get());
    }

    // ── 音频下载（转写链路的输入；B站必须带 Referer，否则 403）──

    @Test
    void downloadAudio_returnsBytes() {
        byte[] audio = fetcher().downloadAudio(base + "/audio.m4s");

        assertEquals(4, audio.length);
    }

    @Test
    void downloadAudio_blankUrl_throwsHumanMessage() {
        assertThrows(LearnException.class, () -> fetcher().downloadAudio(""));
        assertThrows(LearnException.class, () -> fetcher().downloadAudio(null));
    }
}
