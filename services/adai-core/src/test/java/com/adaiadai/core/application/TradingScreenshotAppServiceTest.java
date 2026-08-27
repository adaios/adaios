package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * TradingScreenshotAppService — 截图入账用例（2026-08-26，交易闭环第一环）。
 * 覆盖：单图归集 / 多图逐张 / 超张数拦截 / 空列表拦截 / VLM 失败降级 / 非图片与超限拦截。
 * <p>
 * 关键不变量：截图入账**不建记录**——本服务只调 VLM + 归集器，无任何 record 依赖（对比
 * MediaRecordAppService.recordImage 落原图/建记录/沉淀记忆）。
 */
class TradingScreenshotAppServiceTest {

    private VisualAiClient glm;
    private TradeLogCollectService collectService;
    private TradingScreenshotAppService service;

    private static final TradeLogCandidate CANDIDATE = new TradeLogCandidate(
            "002428", "云南锗业", "SELL", new BigDecimal("93.48"), 100, null, "image", true);

    @BeforeEach
    void setUp() {
        glm = mock(VisualAiClient.class);
        collectService = mock(TradeLogCollectService.class);
        when(collectService.collect(any(), any(), any())).thenReturn(List.of());
        service = new TradingScreenshotAppService(glm, collectService);
    }

    private static byte[] png(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i % 251);
        return b;
    }

    @Test
    void collect_singleImage_hitsCandidates() {
        // 2026-08-27：归集原料 = extractedText（OCR 全文）优先——mock extractedText 为完整表格文字
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "股票交易记录", "trading", "云南锗业 002428 93.480 卖出 100 已成 14:56:09", List.of("交易")));
        when(collectService.todayCandidates(any())).thenReturn(List.of(CANDIDATE));

        var r = service.collect("u1", List.of(png(100)), List.of("image/png"));

        assertEquals(1, r.total());
        assertEquals(1, r.processed());
        assertEquals(0, r.errors().size());
        assertEquals(1, r.candidates().size());
        assertEquals("002428", r.candidates().get(0).symbol());
        // 归集器收到的是 extractedText（OCR 全文）而非 summary 概括
        verify(collectService).collect(eq("u1"), eq("云南锗业 002428 93.480 卖出 100 已成 14:56:09"), eq("image"));
    }

    @Test
    void collect_extractedTextBlank_fallsBackToSummary() {
        // extractedText 为空（如 flash 模型只给概括）→ 回退 summary（不丢归集尝试）
        when(glm.understand(any())).thenReturn(new ImageUnderstanding(
                "股票交易记录", "trading", "", List.of("交易")));
        when(collectService.todayCandidates(any())).thenReturn(List.of());

        service.collect("u1", List.of(png(100)), List.of("image/png"));

        verify(collectService).collect(eq("u1"), eq("股票交易记录"), eq("image"));
    }

    @Test
    void collect_multipleImages_collectsEach() {
        when(glm.understand(any())).thenReturn(new ImageUnderstanding("第一张", "photo", "", List.of()));
        when(collectService.todayCandidates(any())).thenReturn(List.of(CANDIDATE));

        var r = service.collect("u1",
                List.of(png(100), png(200)),
                List.of("image/jpeg", "image/jpeg"));

        assertEquals(2, r.processed());
        // 逐张调用归集器（跨图去重由归集器 sameTrade 负责）
        verify(collectService, times(2)).collect(any(), any(), any());
        assertEquals(1, r.candidates().size());
    }

    @Test
    void collect_moreThanThree_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.collect("u1",
                List.of(png(1), png(1), png(1), png(1)),
                List.of("image/png", "image/png", "image/png", "image/png")));
    }

    @Test
    void collect_empty_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.collect("u1", List.of(), List.of()));
    }

    @Test
    void collect_vlmFailure_reportsErrorAndKeepsCandidates() {
        when(glm.understand(any())).thenThrow(new RuntimeException("GLM 服务不可用"));
        when(collectService.todayCandidates(any())).thenReturn(List.of());

        var r = service.collect("u1", List.of(png(100)), List.of("image/png"));

        assertEquals(0, r.processed());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).contains("识别失败"));
        assertEquals(0, r.candidates().size());
    }

    @Test
    void collect_nonImage_rejectedPerImage() {
        var r = service.collect("u1", List.of(png(100)), List.of("application/pdf"));

        assertEquals(0, r.processed());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).contains("不是图片"));
        verify(collectService, never()).collect(anyString(), anyString(), anyString());
    }

    @Test
    void collect_oversize_rejectedPerImage() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        var r = service.collect("u1", List.of(big), List.of("image/png"));

        assertEquals(0, r.processed());
        assertTrue(r.errors().get(0).contains("5MB"));
    }

    @Test
    void collect_partialFailure_othersStillProcessed() {
        when(glm.understand(any())).thenThrow(new RuntimeException("GLM 服务不可用"));
        when(collectService.todayCandidates(any())).thenReturn(List.of());

        var r = service.collect("u1",
                List.of(png(100), png(200)),
                List.of("image/png", "image/jpeg"));

        assertEquals(2, r.errors().size());
        assertEquals(0, r.processed());
    }
}
