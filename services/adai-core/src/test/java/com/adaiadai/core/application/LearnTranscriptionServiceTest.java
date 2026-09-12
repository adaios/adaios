package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnQuota;
import com.adaiadai.core.domain.learn.LearnQuotaRepository;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.kernel.ai.AsrClient;
import com.adaiadai.core.kernel.ai.AudioTranscoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnTranscriptionServiceTest — 转写与**费用闸**（RFC 20260912 §3.8，费用可控条 1~5）。
 * <p>
 * 覆盖：不可用时的**人话**原因（缺凭证 / 缺 ffmpeg）、转写稿命中留痕 → 零费用复用、
 * 超配额硬闸拒绝、可用时的完整链路（下载→转码→云端→留痕→记账）、单次预估口径。
 * 这些断言就是「费用可控」条款的回归网：任何人改动都要先过这关。
 */
class LearnTranscriptionServiceTest {

    private static final int QUOTA = 36000;

    private final AsrClient asrClient = mock(AsrClient.class);
    private final AudioTranscoder transcoder = mock(AudioTranscoder.class);
    private final LearnQuotaRepository quotaRepository = mock(LearnQuotaRepository.class);
    private final LearnCardRepository cardRepository = mock(LearnCardRepository.class);
    private final LearnFetchService fetchService = mock(LearnFetchService.class);

    private LearnTranscriptionService service;

    @BeforeEach
    void setUp() {
        service = new LearnTranscriptionService(asrClient, transcoder, quotaRepository,
                cardRepository, fetchService, 0.288d);
        when(asrClient.available()).thenReturn(true);
        when(transcoder.available()).thenReturn(true);
        when(quotaRepository.view(anyString(), any())).thenReturn(
                new LearnQuota(YearMonth.now().toString(), 0, 0d, QUOTA));
    }

    private static LearnSource video(int durationSeconds) {
        return new LearnSource("bilibili", "BV1xx411c7mD", "https://www.bilibili.com/video/BV1xx411c7mD",
                "某视频", "某UP", "2026-05-05", null, true, "https://audio/x.m4s", durationSeconds, List.of());
    }

    // ── 可用性与人话降级 ──

    @Test
    void unavailable_mentionsMissingCredential_notTechnicalJargon() {
        when(asrClient.available()).thenReturn(false);

        assertFalse(service.available());
        String reason = service.unavailableReason();
        assertTrue(reason.contains("凭证"), "缺凭证要人话说清楚");
        assertTrue(reason.contains("有字幕的视频和文章不受影响"), "要说明哪些还能用，而不是一刀切不可用");
    }

    @Test
    void unavailable_ffmpeg_reasonMentionsIt() {
        when(transcoder.available()).thenReturn(false);

        assertFalse(service.available());
        assertTrue(service.unavailableReason().contains("ffmpeg"));
    }

    @Test
    void available_noReason() {
        assertTrue(service.available());
        assertNull(service.unavailableReason());
    }

    @Test
    void transcribe_whenUnavailable_throwsHumanMessage_withoutSpending() {
        when(asrClient.available()).thenReturn(false);

        LearnException e = assertThrows(LearnException.class, () -> service.transcribe("adai", video(600)));

        assertTrue(e.getMessage().contains("凭证"));
        verify(fetchService, never()).downloadAudio(anyString(), anyString());
        verify(quotaRepository, never()).consume(anyString(), any(), anyInt(), anyDouble());
    }

    // ── 费用可控条 2：同一素材只转写一次 ──

    @Test
    void transcribe_cachedTranscript_reusesWithZeroCost() {
        when(cardRepository.readRaw("adai", "bilibili-BV1xx411c7mD-transcript.txt"))
                .thenReturn("上次转写好的全文");

        LearnTranscriptionService.TranscriptionResult result = service.transcribe("adai", video(600));

        assertEquals("上次转写好的全文", result.text());
        assertTrue(result.fromCache(), "命中留痕应标记复用");
        assertEquals(0d, result.costYuan(), "复用零费用");
        assertEquals(0, result.usedSeconds());
        verify(asrClient, never()).transcribe(any(), anyString());
        verify(quotaRepository, never()).consume(anyString(), any(), anyInt(), anyDouble());
    }

    @Test
    void transcriptRawName_isStablePerSource() {
        assertEquals("bilibili-BV1xx411c7mD-transcript.txt",
                LearnTranscriptionService.transcriptRawName(video(600)));
    }

    // ── 费用可控条 4：月度硬闸 ──

    @Test
    void transcribe_exceedingMonthlyQuota_rejectedBeforeSpending() {
        when(quotaRepository.view(anyString(), any()))
                .thenReturn(new LearnQuota(YearMonth.now().toString(), 35400, 2.83d, QUOTA));

        LearnException e = assertThrows(LearnException.class, () -> service.transcribe("adai", video(1800)));

        assertTrue(e.getMessage().contains("额度"), "超配额要人话说明，不是技术报错");
        assertTrue(e.getMessage().contains("下月 1 日重置"));
        verify(fetchService, never()).downloadAudio(anyString(), anyString());
        verify(asrClient, never()).transcribe(any(), anyString());
    }

    @Test
    void transcribe_quotaAlreadyExhausted_rejected() {
        when(quotaRepository.view(anyString(), any()))
                .thenReturn(new LearnQuota(YearMonth.now().toString(), QUOTA, 10.4d, QUOTA));

        assertThrows(LearnException.class, () -> service.transcribe("adai", video(60)));
        verify(asrClient, never()).transcribe(any(), anyString());
    }

    @Test
    void transcribe_fittingExactlyInRemainingQuota_isAllowed() {
        when(quotaRepository.view(anyString(), any()))
                .thenReturn(new LearnQuota(YearMonth.now().toString(), QUOTA - 600, 0d, QUOTA));
        when(fetchService.downloadAudio(anyString(), anyString())).thenReturn(new byte[]{1});
        when(transcoder.toAsrCompatible(any(), anyString())).thenReturn(new byte[]{2});
        when(asrClient.transcribe(any(), anyString())).thenReturn("转写全文");
        when(quotaRepository.consume(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(new LearnQuota(YearMonth.now().toString(), QUOTA, 0.048d, QUOTA));

        LearnTranscriptionService.TranscriptionResult result = service.transcribe("adai", video(600));

        assertEquals("转写全文", result.text());
        assertFalse(result.fromCache());
    }

    // ── 正常链路：下载 → 转码 → 云端 → 留痕 → 记账 ──

    @Test
    void transcribe_successPath_transcodesUploadsArchivesAndRecordsCost() {
        when(fetchService.downloadAudio("bilibili", "https://audio/x.m4s")).thenReturn(new byte[]{9, 9});
        when(transcoder.toAsrCompatible(any(), eq("m4s"))).thenReturn(new byte[]{8, 8, 8});
        when(asrClient.transcribe(any(), eq("learn-BV1xx411c7mD.mp3"))).thenReturn("转写全文……");
        when(quotaRepository.consume(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(new LearnQuota(YearMonth.now().toString(), 1710, 0.1368d, QUOTA));

        LearnTranscriptionService.TranscriptionResult result = service.transcribe("adai", video(1710));

        assertEquals("转写全文……", result.text());
        assertEquals(1710, result.usedSeconds());
        assertEquals(0.1368d, result.costYuan(), 1e-9);
        // 源必留痕：转写稿永久留存 → 下次零费用
        verify(cardRepository).saveRaw("adai", "bilibili-BV1xx411c7mD-transcript.txt", "转写全文……");
        // 记账：时长与费用都入账
        verify(quotaRepository).consume(eq("adai"), any(), eq(1710), eq(0.1368d));
    }

    @Test
    void transcribe_ledgerWriteFailure_stopsWithHumanMessage_butKeepsTranscript() {
        when(fetchService.downloadAudio(anyString(), anyString())).thenReturn(new byte[]{9});
        when(transcoder.toAsrCompatible(any(), anyString())).thenReturn(new byte[]{8});
        when(asrClient.transcribe(any(), anyString())).thenReturn("转写全文");
        when(quotaRepository.consume(anyString(), any(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("disk full"));

        LearnException e = assertThrows(LearnException.class, () -> service.transcribe("adai", video(600)));

        assertTrue(e.getMessage().contains("记账"), "记账失败要如实说，不要伪装成 AI 失败");
        assertTrue(e.getMessage().contains("不会重复花钱"), "要让用户知道重试是安全的（转写稿已留痕）");
        // 转写稿先留痕再记账：钱花了但账没记时，重试命中缓存 → 不再重复花钱
        verify(cardRepository).saveRaw(eq("adai"), eq("bilibili-BV1xx411c7mD-transcript.txt"), eq("转写全文"));
    }

    @Test
    void transcribe_asrFailure_doesNotArchiveAndDoesNotCharge() {
        when(fetchService.downloadAudio(anyString(), anyString())).thenReturn(new byte[]{9});
        when(transcoder.toAsrCompatible(any(), anyString())).thenReturn(new byte[]{8});
        when(asrClient.transcribe(any(), anyString())).thenThrow(new IllegalStateException("云端转写失败（SERVER_ERROR）"));

        LearnException e = assertThrows(LearnException.class, () -> service.transcribe("adai", video(600)));

        assertTrue(e.getMessage().contains("云端转写失败"));
        verify(cardRepository, never()).saveRaw(anyString(), anyString(), anyString());
        verify(quotaRepository, never()).consume(anyString(), any(), anyInt(), anyDouble());
    }

    // ── 单次可预期（费用可控条 5）──

    @Test
    void estimate_knownDuration_usesConfiguredUnitPrice() {
        LearnTranscriptionService.CostEstimate estimate = service.estimate("adai", video(3600));

        assertEquals(3600, estimate.durationSeconds());
        assertTrue(estimate.durationKnown());
        assertEquals(0.288d, estimate.estimatedYuan(), 1e-9);
        assertFalse(estimate.exceedsQuota());
        assertEquals(QUOTA, estimate.remainSeconds());
    }

    @Test
    void estimate_unknownDuration_assumes30Minutes_andSaysSo() {
        LearnTranscriptionService.CostEstimate estimate = service.estimate("adai", video(0));

        assertEquals(LearnTranscriptionService.ASSUMED_SECONDS_WHEN_UNKNOWN, estimate.durationSeconds());
        assertFalse(estimate.durationKnown(), "时长未知必须显式标记（提示文案不能假装精确）");
    }

    @Test
    void estimate_whenWouldExceedQuota_flagsIt() {
        when(quotaRepository.view(anyString(), any()))
                .thenReturn(new LearnQuota(YearMonth.now().toString(), QUOTA - 60, 0d, QUOTA));

        assertTrue(service.estimate("adai", video(3600)).exceedsQuota());
    }

    @Test
    void quota_viewExposesMonthUsageAndUnitPrice() {
        when(quotaRepository.view(anyString(), any()))
                .thenReturn(new LearnQuota(YearMonth.now().toString(), 1200, 0.096d, QUOTA));

        LearnTranscriptionService.QuotaView view = service.quota("adai");

        assertEquals(1200, view.usedSeconds());
        assertEquals(0.096d, view.usedYuan(), 1e-9);
        assertEquals(QUOTA - 1200, view.remainSeconds());
        assertEquals(0.288d, view.yuanPerHour(), 1e-9);
        assertTrue(view.asrAvailable());
    }

    @Test
    void humanHours_readable() {
        assertEquals("0 分钟", LearnTranscriptionService.humanHours(0));
        assertEquals("30 分钟", LearnTranscriptionService.humanHours(1800));
        assertEquals("1 小时", LearnTranscriptionService.humanHours(3600));
        assertEquals("2.5 小时", LearnTranscriptionService.humanHours(9000));
    }
}
