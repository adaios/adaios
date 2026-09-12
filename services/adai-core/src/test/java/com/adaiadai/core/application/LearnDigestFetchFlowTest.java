package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnQuota;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.kernel.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnDigestFetchFlowTest — 链接喂入流水线与**转写费用确认流**（RFC 20260912 §3.5/§3.8 条 5）。
 * <p>
 * 这是 D 形态的核心回归网：链接进来 → 抓 →（有字幕就直接结构化；无字幕则先报价、等用户点头）
 * → 转写 → 结构化 → 落盘。任何一环的降级都必须**人话 + 不产半成品**。
 * 与 {@code LearnDigestAppServiceTest}（素材路径，B 形态先例）互补。
 */
class LearnDigestFetchFlowTest {

    private static final String CARD_JSON = """
            {"title":"回调一半的判定","type":"trading","tags":["止损","回调"],
            "core_view":"回调到一半才是买点","key_points":["02:31 一半=(high+low)/2"],
            "questions":["与课程口径一致吗？"],"trade_related":true,"trade_note":"与 R66 互补"}""";

    private final AiClient aiClient = mock(AiClient.class);
    private final LearnCardRepository repository = mock(LearnCardRepository.class);
    private final LearnFetchService fetchService = mock(LearnFetchService.class);
    private final LearnTranscriptionService transcriptionService = mock(LearnTranscriptionService.class);

    private final Executor directExecutor = Runnable::run;
    private LearnDigestAppService service;

    @BeforeEach
    void setUp() {
        service = new LearnDigestAppService(aiClient, repository, directExecutor, fetchService, transcriptionService);
        when(aiClient.generate(any(), any())).thenReturn(CARD_JSON);
        when(transcriptionService.unavailableReason()).thenReturn(null);
    }

    private static LearnSource withSubtitle() {
        return new LearnSource("bilibili", "BV1xx411c7mD", "https://www.bilibili.com/video/BV1xx411c7mD",
                "某视频", "某UP", "2026-05-05", "字幕第一句\n字幕第二句", false, null, 2244, List.of());
    }

    private static LearnSource withoutSubtitle() {
        return new LearnSource("bilibili", "BV1xx411c7mD", "https://www.bilibili.com/video/BV1xx411c7mD",
                "某视频", "某UP", "2026-05-05", null, true, "https://audio/x.m4s", 2244, List.of());
    }

    private static LearnTranscriptionService.CostEstimate estimate(int seconds, double yuan, boolean known) {
        return new LearnTranscriptionService.CostEstimate(seconds, known, yuan, 0, 36000, 36000, false);
    }

    private LearnDigestAppService.DigestSubmitResult submitUrl(String url) {
        return service.submit("adai", new LearnDigestAppService.DigestRequest(url, null, null, null, null, null));
    }

    // ── 有字幕：直接结构化（免费路径）──

    @Test
    void urlWithSubtitle_digestsDirectly_withoutTranscriptionOrConfirmation() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withSubtitle());

        LearnDigestAppService.DigestSubmitResult result =
                submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        assertEquals(LearnDigestAppService.STATUS_PENDING, result.status());
        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_DONE, job.status());
        assertEquals(LearnCard.TYPE_TRADING, job.type());
        assertEquals("回调一半的判定", job.title());
        verify(transcriptionService, never()).transcribe(anyString(), any());
        verify(repository).save(eq("adai"), any(LearnCard.class));
    }

    @Test
    void urlWithSubtitle_cardCarriesFetchedSourceMetadata() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withSubtitle());

        submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        org.mockito.ArgumentCaptor<LearnCard> captor =
                org.mockito.ArgumentCaptor.forClass(LearnCard.class);
        verify(repository).save(eq("adai"), captor.capture());
        LearnCard card = captor.getValue();
        assertEquals("bilibili", card.platform());
        assertEquals("某UP", card.author());
        assertEquals("2026-05-05", card.published());
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", card.url());
    }

    @Test
    void articleUrl_platformShowsHostNotGenericWord() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(new LearnSource(
                "article", "abc123", "https://example.com/post/1", "某文章", "示例站点", null,
                "正文内容足够长……", false, null, 0, List.of()));

        submitUrl("https://example.com/post/1");

        org.mockito.ArgumentCaptor<LearnCard> captor =
                org.mockito.ArgumentCaptor.forClass(LearnCard.class);
        verify(repository).save(eq("adai"), captor.capture());
        assertEquals("example.com", captor.getValue().platform(), "文章卡片平台显示域名，比裸 article 有信息量");
    }

    // ── 无字幕：报价 + 等确认（费用可控条 5）──

    @Test
    void urlWithoutSubtitle_awaitsConfirmation_withCostAndSourceEcho() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withoutSubtitle());
        when(transcriptionService.estimate(anyString(), any())).thenReturn(estimate(2244, 0.1795d, true));

        submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_NEEDS_CONFIRMATION, job.status());
        assertTrue(job.message().contains("37 分钟"), "要说清多长：37 分钟");
        assertTrue(job.message().contains("0.18"), "要给出预计金额");
        assertTrue(job.message().contains("本月剩余额度"), "要带上本月剩余额度");
        assertNotNull(job.cost());
        assertEquals(2244, job.cost().durationSeconds());
        assertEquals(0.1795d, job.cost().estimatedYuan(), 1e-9);
        assertNotNull(job.source());
        assertEquals("某视频", job.source().title());
        // 关键：报价阶段**没有花钱**
        verify(transcriptionService, never()).transcribe(anyString(), any());
        verify(repository, never()).save(anyString(), any(LearnCard.class));
    }

    @Test
    void confirmTrue_transcribesThenDigests() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withoutSubtitle());
        when(transcriptionService.estimate(anyString(), any())).thenReturn(estimate(2244, 0.1795d, true));
        when(transcriptionService.transcribe(anyString(), any())).thenReturn(
                new LearnTranscriptionService.TranscriptionResult("转写出来的全文……", false, 2244, 0.1795d,
                        new LearnQuota(YearMonth.now().toString(), 2244, 0.1795d, 36000)));
        submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        service.confirm("adai", true);

        verify(transcriptionService, times(1)).transcribe(eq("adai"), any());
        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_DONE, job.status());
        assertEquals("回调一半的判定", job.title());
        verify(repository).save(eq("adai"), any(LearnCard.class));
    }

    @Test
    void confirmFalse_cancelsWithoutSpending() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withoutSubtitle());
        when(transcriptionService.estimate(anyString(), any())).thenReturn(estimate(2244, 0.1795d, true));
        submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        LearnDigestAppService.DigestJobStatus after = service.confirm("adai", false);

        assertEquals(LearnDigestAppService.STATUS_CANCELLED, after.status());
        assertTrue(after.message().contains("没花钱"), "取消要明确告诉用户没产生费用");
        verify(transcriptionService, never()).transcribe(anyString(), any());
        verify(repository, never()).save(anyString(), any(LearnCard.class));
    }

    @Test
    void confirm_withoutPendingTask_throwsHumanMessage() {
        LearnException e = assertThrows(LearnException.class, () -> service.confirm("adai", true));
        assertTrue(e.getMessage().contains("没有等待确认"));
    }

    @Test
    void confirm_whenAsrUnavailable_doesNotReachConfirmation() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withoutSubtitle());
        when(transcriptionService.unavailableReason()).thenReturn("服务器上还没装转码工具（ffmpeg）…");

        submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_FAILED, job.status());
        assertTrue(job.message().contains("ffmpeg"), "不可用要人话说明（fail-visible，不静默产废卡）");
        verify(transcriptionService, never()).estimate(anyString(), any());
    }

    // ── 失败路径 ──

    @Test
    void fetchFailure_jobFailedWithHumanMessage_nothingPersisted() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString()))
                .thenThrow(new LearnException("这篇文章抓不下来（可能有反爬），把正文粘进来我照样能整理"));

        submitUrl("https://example.com/post/1");

        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_FAILED, job.status());
        assertTrue(job.message().contains("粘进来"));
        verify(repository, never()).save(anyString(), any(LearnCard.class));
    }

    @Test
    void quotaExceeded_jobFailedBeforeConfirming() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withoutSubtitle());
        when(transcriptionService.estimate(anyString(), any())).thenReturn(
                new LearnTranscriptionService.CostEstimate(2244, true, 0.1795d, 35900, 36000, 100, true));

        submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        LearnDigestAppService.DigestJobStatus job = service.digestJobStatus("adai");
        assertEquals(LearnDigestAppService.STATUS_FAILED, job.status());
        assertTrue(job.message().contains("额度"), "超配额要在**报价阶段**就拦住（不是转写失败后才发现）");
        verify(transcriptionService, never()).transcribe(anyString(), any());
    }

    // ── 输入归一：素材框里只粘了一个链接 ──

    @Test
    void bareUrlInContentField_isTreatedAsLinkNotMaterial() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withSubtitle());

        service.submit("adai", new LearnDigestAppService.DigestRequest(
                null, "https://www.bilibili.com/video/BV1xx411c7mD", null, null, null, null));

        verify(fetchService).fetchAndArchive(eq("adai"), anyString());
    }

    @Test
    void linkWithMaterial_usesMaterialAndKeepsLinkAsSource() {
        service.submit("adai", new LearnDigestAppService.DigestRequest(
                "https://example.com/post/1", "这是用户粘进来的正文……", null, "example.com", null, null));

        verify(fetchService, never()).fetchAndArchive(anyString(), anyString());
        org.mockito.ArgumentCaptor<LearnCard> captor =
                org.mockito.ArgumentCaptor.forClass(LearnCard.class);
        verify(repository).save(eq("adai"), captor.capture());
        assertEquals("https://example.com/post/1", captor.getValue().url(), "给了正文时链接只作来源记录");
        assertEquals("example.com", captor.getValue().platform());
    }

    @Test
    void submit_withNeitherLinkNorMaterial_throwsHumanMessage() {
        LearnException e = assertThrows(LearnException.class,
                () -> service.submit("adai", new LearnDigestAppService.DigestRequest(
                        null, "   ", null, null, null, null)));

        assertTrue(e.getMessage().contains("不能为空"));
        assertEquals(LearnDigestAppService.STATUS_IDLE, service.digestJobStatus("adai").status());
    }

    @Test
    void inflightLinkSubmission_dedupesWithoutSecondFetch() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withoutSubtitle());
        when(transcriptionService.estimate(anyString(), any())).thenReturn(estimate(600, 0.048d, true));

        submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");
        LearnDigestAppService.DigestSubmitResult second = submitUrl("https://www.bilibili.com/video/BV2yy411c7mD");

        assertEquals(LearnDigestAppService.STATUS_NEEDS_CONFIRMATION, second.status(),
                "等确认期间再次提交 → 如实回待确认状态（不覆盖待确认任务、不重复抓取）");
        verify(fetchService, times(1)).fetchAndArchive(anyString(), anyString());
    }

    @Test
    void stageIsClearedAfterSettling() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(withSubtitle());

        submitUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        assertFalse(LearnDigestAppService.STAGE_STRUCTURING.equals(service.digestJobStatus("adai").stage()),
                "任务结束后不该残留进行中阶段（前端进度条要收掉）");
    }
}
