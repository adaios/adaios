package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.domain.learn.LearnSourceFetcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * LearnFetchServiceTest — 抓取分流与源留痕（RFC 20260912 抓取批，2026-09-12）。
 * <p>
 * 覆盖：首期不做平台的**人话拒绝**（不假装能抓）、分流到支持该链接的实现、无可处理实现 → 人话、
 * 抓到的原始素材落 {@code _raw/}、音频下载按平台分发、裸链接识别。
 */
class LearnFetchServiceTest {

    private final LearnCardRepository repository = mock(LearnCardRepository.class);

    /** 假抓取实现：记录调用次数，返回构造时给定的结果。 */
    private static class FakeFetcher implements LearnSourceFetcher {
        private final String platform;
        private final List<String> hosts;
        private final LearnSource result;
        private final List<String> fetched = new ArrayList<>();

        FakeFetcher(String platform, List<String> hosts, LearnSource result) {
            this.platform = platform;
            this.hosts = hosts;
            this.result = result;
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public boolean supports(String url) {
            return hosts.stream().anyMatch(url::contains);
        }

        @Override
        public LearnSource fetch(String url) {
            fetched.add(url);
            return result;
        }
    }

    private static LearnSource source(String platform, String id) {
        return new LearnSource(platform, id, "https://x/" + id, "标题", "作者", "2026-05-05",
                "正文内容", false, null, 0,
                List.of(new LearnSource.RawAsset(platform + "-" + id + "-meta.json", "{}")));
    }

    @Test
    void fetch_dispatchesToSupportingFetcher() {
        FakeFetcher bili = new FakeFetcher("bilibili", List.of("bilibili.com"), source("bilibili", "BV1"));
        FakeFetcher article = new FakeFetcher("article", List.of("http"), source("article", "abc"));
        LearnFetchService service = new LearnFetchService(List.of(bili, article), repository);

        LearnSource result = service.fetch("https://www.bilibili.com/video/BV1xx411c7mD");

        assertEquals("bilibili", result.platform());
        assertEquals(1, bili.fetched.size());
        assertTrue(article.fetched.isEmpty(), "已由 bilibili 处理，不该再走文章抓取");
    }

    @Test
    void fetch_youtube_rejectedWithHumanMessage_notPretending() {
        LearnFetchService service = new LearnFetchService(
                List.of(new FakeFetcher("article", List.of("http"), source("article", "a"))), repository);

        LearnException e = assertThrows(LearnException.class,
                () -> service.fetch("https://www.youtube.com/watch?v=abc"));

        assertTrue(e.getMessage().contains("连不上"), "应人话说明服务器网络限制，而非技术报错");
        assertTrue(e.getMessage().contains("粘进来"), "应给出替代路径（把字幕/正文粘进来）");
    }

    @Test
    void fetch_socialPlatforms_rejectedWithAlternative() {
        LearnFetchService service = new LearnFetchService(
                List.of(new FakeFetcher("article", List.of("http"), source("article", "a"))), repository);

        assertTrue(assertThrows(LearnException.class,
                () -> service.fetch("https://mp.weixin.qq.com/s/abcdef")).getMessage().contains("公众号"));
        assertTrue(assertThrows(LearnException.class,
                () -> service.fetch("https://www.zhihu.com/question/123")).getMessage().contains("知乎"));
        assertTrue(assertThrows(LearnException.class,
                () -> service.fetch("https://x.com/someone/status/1")).getMessage().contains("X/Twitter"));
    }

    @Test
    void fetch_noFetcherSupports_throwsHumanMessage() {
        LearnFetchService service = new LearnFetchService(
                List.of(new FakeFetcher("bilibili", List.of("bilibili.com"), source("bilibili", "BV1"))), repository);

        LearnException e = assertThrows(LearnException.class, () -> service.fetch("https://example.com/a"));
        assertTrue(e.getMessage().contains("粘进来"));
    }

    @Test
    void fetch_blankOrMalformedUrl_throwsHumanMessage() {
        LearnFetchService service = new LearnFetchService(List.of(), repository);
        assertThrows(LearnException.class, () -> service.fetch("  "));
        assertThrows(LearnException.class, () -> service.fetch("不是链接"));
    }

    @Test
    void fetchAndArchive_writesRawAssets_andReportsFailuresQuietly() {
        FakeFetcher bili = new FakeFetcher("bilibili", List.of("bilibili.com"), source("bilibili", "BV1"));
        LearnFetchService service = new LearnFetchService(List.of(bili), repository);

        service.fetchAndArchive("adai", "https://www.bilibili.com/video/BV1xx411c7mD");

        verify(repository).saveRaw(eq("adai"), eq("bilibili-BV1-meta.json"), anyString());
    }

    @Test
    void fetchAndArchive_rawWriteFailure_doesNotBreakFetch() {
        FakeFetcher bili = new FakeFetcher("bilibili", List.of("bilibili.com"), source("bilibili", "BV1"));
        LearnFetchService service = new LearnFetchService(List.of(bili), repository);
        org.mockito.Mockito.doThrow(new RuntimeException("disk"))
                .when(repository).saveRaw(anyString(), anyString(), anyString());

        // 留痕失败只告警，不影响抓取结果返回（素材已在内存中，卡片仍可产出）
        assertEquals("bilibili", service.fetchAndArchive("adai", "https://bilibili.com/x").platform());
    }

    @Test
    void downloadAudio_dispatchesByPlatform_andRejectsUnknown() {
        FakeFetcher bili = new FakeFetcher("bilibili", List.of("bilibili.com"), source("bilibili", "BV1")) {
            @Override
            public byte[] downloadAudio(String audioUrl) {
                return new byte[]{1, 2, 3};
            }
        };
        LearnFetchService service = new LearnFetchService(List.of(bili), repository);

        assertEquals(3, service.downloadAudio("bilibili", "https://audio/x.m4s").length);
        assertThrows(LearnException.class, () -> service.downloadAudio("article", "https://audio/x.m4s"));
    }

    @Test
    void downloadAudio_defaultImplementation_rejectsHumanly() {
        LearnSourceFetcher plain = new FakeFetcher("bilibili", List.of("bilibili.com"), source("bilibili", "BV1"));
        assertThrows(LearnException.class, () -> plain.downloadAudio("https://audio/x.m4s"));
    }

    @Test
    void hostOf_handlesValidAndInvalid() {
        assertEquals("www.bilibili.com", LearnFetchService.hostOf("https://www.bilibili.com/video/BV1"));
        assertEquals(null, LearnFetchService.hostOf("不是链接"));
        assertEquals(null, LearnFetchService.hostOf(null));
    }

    @Test
    void isBareUrl_onlySingleLineHttpLink() {
        assertTrue(LearnDigestAppService.isBareUrl("https://www.bilibili.com/video/BV1xx411c7mD"));
        assertTrue(LearnDigestAppService.isBareUrl("  http://example.com/a  "));
        assertFalse(LearnDigestAppService.isBareUrl("https://a.com\n正文"), "多行 = 正文不是纯链接");
        assertFalse(LearnDigestAppService.isBareUrl("看这个 https://a.com"), "带说明文字 = 正文");
        assertFalse(LearnDigestAppService.isBareUrl("字幕内容……"));
        assertFalse(LearnDigestAppService.isBareUrl(null));
    }

    @Test
    void archive_nullSource_isNoOp() {
        LearnFetchService service = new LearnFetchService(List.of(), repository);
        service.archive("adai", null);
        verify(repository, never()).saveRaw(anyString(), anyString(), anyString());
    }
}
