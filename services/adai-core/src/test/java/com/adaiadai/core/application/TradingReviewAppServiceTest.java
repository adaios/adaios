package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.PortfolioSnapshot;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.infrastructure.storage.TradingReviewFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TradingReviewAppService — #12 修复验证。
 * 复盘必须走 ContextEngine（trading 场景）而非手拼 prompt，复盘 prompt 应包含复盘模板且不含 compose 的分析指令。
 */
class TradingReviewAppServiceTest {

    @Test
    void generateReview_usesContextEngine_tradingScene() {
        // ── 依赖 ──
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());

        PositionRepository positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll(any())).thenReturn(List.of());
        when(positionRepository.snapshot(any()))
                .thenReturn(PortfolioSnapshot.of(List.of(), BigDecimal.ZERO));

        // ContextEngine 注入交易知识 + 行情（模拟 Contributor/KnowledgeSource 生效后的 prompt）
        ContextEngine contextEngine = mock(ContextEngine.class);
        ContextPackage baseCtx = new ContextPackage(
                "trading", "用户身份摘要", "2026-08-01 交易复盘", "复盘正文",
                List.of("trading", "复盘"),
                List.of(),
                "【交易系统规则】只输一根K线；止损三级别。\n【行情】上证 3400，持仓实时价已更新。\n\n请分析这条记录，输出 JSON 格式",
                LocalDateTime.now(), List.of()
        );
        when(contextEngine.compose(any(), eq("trading"), any())).thenReturn(baseCtx);

        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(any(), any()))
                .thenReturn("今日执行纪律良好，明日关注 3400 关键位");

        TradingReviewFileRepository reviewRepository = mock(TradingReviewFileRepository.class);

        TradingReviewAppService service = new TradingReviewAppService(
                recordRepository, positionRepository, contextEngine, aiClient, reviewRepository);

        // ── 执行 ──
        LocalDate date = LocalDate.of(2026, 8, 1);
        String result = service.generateReview("default", date);

        // ── 验证 compose 走 trading 场景 + 合成记录含交易关键词 ──
        ArgumentCaptor<ContentRecord> recordCaptor = ArgumentCaptor.forClass(ContentRecord.class);
        verify(contextEngine).compose(any(), eq("trading"), recordCaptor.capture());
        ContentRecord reviewRecord = recordCaptor.getValue();
        assertTrue(reviewRecord.content().contains("复盘"), "复盘记录内容应含复盘关键词");
        assertTrue(reviewRecord.tags().contains("trading"), "复盘记录应带 trading 标签");

        // ── 验证 generate 收到的 prompt：去掉分析指令 + 追加复盘模板 + 生成 system 指令 ──
        ArgumentCaptor<ContextPackage> ctxCaptor = ArgumentCaptor.forClass(ContextPackage.class);
        verify(aiClient).generate(ctxCaptor.capture(), any());
        String prompt = ctxCaptor.getValue().prompt();
        assertTrue(prompt.contains("交易系统规则"), "注入的交易规则应保留在 prompt");
        assertFalse(prompt.contains("请分析这条记录"), "应去掉 compose 的 JSON 分析指令");
        assertTrue(prompt.contains("交易复盘"), "应包含复盘模板");
        assertTrue(prompt.contains("与系统规则对照"), "复盘模板应含规则对照节");

        // ── 验证持久化 ──
        verify(reviewRepository).save(any(), eq(date), anyString());
        assertEquals("今日执行纪律良好，明日关注 3400 关键位", result);
    }
}
