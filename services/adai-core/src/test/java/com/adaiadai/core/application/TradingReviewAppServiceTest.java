package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
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
                recordRepository, positionRepository, mock(AccountSnapshotRepository.class),
                contextEngine, aiClient, reviewRepository, mock(TradingLotService.class),
                mock(TradingAppService.class),
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));

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

    @Test
    void generateReview_injectsBehaviorNotes_intoReviewBody() {
        // RFC 20260825：行为标注注入复盘（亏损加仓/追高等进当晚复盘）
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        PositionRepository positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll(any())).thenReturn(List.of());
        when(positionRepository.snapshot(any()))
                .thenReturn(PortfolioSnapshot.of(List.of(), BigDecimal.ZERO));
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.compose(any(), eq("trading"), any())).thenReturn(new ContextPackage(
                "trading", "用户身份摘要", "2026-08-01 交易复盘", "复盘正文",
                List.of("trading", "复盘"), List.of(),
                "【交易系统规则】止损三级别。\n\n请分析这条记录，输出 JSON 格式",
                LocalDateTime.now(), List.of()));
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(any(), any())).thenReturn("复盘正文输出");
        TradingReviewFileRepository reviewRepository = mock(TradingReviewFileRepository.class);
        TradingLotService lotService = mock(TradingLotService.class);
        when(lotService.analyzeBehaviors(any(), any())).thenReturn(List.of(
                new TradingLotService.BehaviorNote("loss-avg-down", "亏损加仓", "600000", "浦发银行",
                        LocalDate.of(2026, 8, 1), "买价 9.2 低于上一买批成本 10.0——越跌越买/补仓摊薄")));

        TradingReviewAppService service = new TradingReviewAppService(
                recordRepository, positionRepository, mock(AccountSnapshotRepository.class),
                contextEngine, aiClient, reviewRepository, lotService,
                mock(TradingAppService.class),
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));
        LocalDate date = LocalDate.of(2026, 8, 1);
        service.generateReview("default", date);

        ArgumentCaptor<ContentRecord> recordCaptor = ArgumentCaptor.forClass(ContentRecord.class);
        verify(contextEngine).compose(any(), eq("trading"), recordCaptor.capture());
        assertTrue(recordCaptor.getValue().content().contains("当日行为标注"),
                "复盘正文应含行为标注小节");
        assertTrue(recordCaptor.getValue().content().contains("亏损加仓"),
                "行为标注内容应注入复盘（阿呆观察，纪律对照）");
        verify(lotService).analyzeBehaviors(eq("default"), eq(date));
    }

    @Test
    void hasTradingActivity_true_whenDailyTradesExist() {
        // 2026-08-26 复盘卡点：口径 = 当日真实成交 > 0（非关键词扫描对话记录）
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getDailyTradeSummary(any(), any())).thenReturn(
                new TradingAppService.DailyTradeSummary("2026-08-02", 2, 1, 1,
                        1000, 500, List.of(), null, null));
        TradingReviewAppService service = new TradingReviewAppService(
                mock(RecordRepository.class), mock(PositionRepository.class),
                mock(AccountSnapshotRepository.class), mock(ContextEngine.class),
                mock(AiClient.class), mock(TradingReviewFileRepository.class),
                mock(TradingLotService.class), trading,
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));

        assertTrue(service.hasTradingActivity("default", LocalDate.of(2026, 8, 2)));
    }

    @Test
    void hasTradingActivity_false_whenNoDailyTrades() {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getDailyTradeSummary(any(), any())).thenReturn(
                new TradingAppService.DailyTradeSummary("2026-08-02", 0, 0, 0,
                        0, 0, List.of(), null, null));
        TradingReviewAppService service = new TradingReviewAppService(
                mock(RecordRepository.class), mock(PositionRepository.class),
                mock(AccountSnapshotRepository.class), mock(ContextEngine.class),
                mock(AiClient.class), mock(TradingReviewFileRepository.class),
                mock(TradingLotService.class), trading,
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));

        assertFalse(service.hasTradingActivity("default", LocalDate.of(2026, 8, 2)));
    }

    @Test
    void generateReview_injectsAdviceCompareSection_whenSoldTodayWithHistory() {
        // RFC 20260905 B③：当日清仓 + 曾有建议 → 复盘注入「阿呆当时说 X → 你做了 Y → 结果 Z」
        LocalDate date = LocalDate.of(2026, 9, 5);
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        PositionRepository positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll(any())).thenReturn(List.of());
        when(positionRepository.snapshot(any()))
                .thenReturn(PortfolioSnapshot.of(List.of(), BigDecimal.ZERO));

        TradingAppService trading = mock(TradingAppService.class);
        when(trading.soldList(any())).thenReturn(List.of(
                new com.adaiadai.core.domain.trading.SoldTrade("600584", "长电科技",
                        date.minusDays(12), date, 12, "5+1", -29.22,
                        "扛单超 5%——按 R66 只输一根K线", "")));
        when(trading.getDailyTradeSummary(any(), any())).thenReturn(
                new TradingAppService.DailyTradeSummary(date.toString(), 1, 0, 1, 0, 30460, List.of(), null, null));

        com.adaiadai.core.domain.trading.AdviceHistoryRepository adviceHistory =
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class);
        // P1-2（2026-09-05 三官）：复盘对照以 sellDate 所在月扫卖前建议
        when(adviceHistory.findByMonth(eq("default"), eq(java.time.YearMonth.from(date).atDay(1)))).thenReturn(List.of(
                new com.adaiadai.core.domain.trading.AdviceEntry("adv_1", date.minusDays(3), "600584", "长电科技",
                        "clear", "跌破止损位，按 R66 只输一根K线", List.of("R66"), true,
                        new BigDecimal("20.0"), "manual-advice",
                        date.minusDays(3).atTime(14, 50))));
        when(adviceHistory.findByMonth(eq("default"), eq(java.time.YearMonth.from(date).minusMonths(1).atDay(1))))
                .thenReturn(List.of());

        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.compose(any(), eq("trading"), any())).thenReturn(new ContextPackage(
                "trading", "用户身份摘要", date + " 交易复盘", "复盘正文",
                List.of("trading", "复盘"), List.of(),
                "【交易系统规则】止损三级别。\n\n请分析这条记录，输出 JSON 格式",
                LocalDateTime.now(), List.of()));
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(any(), any())).thenReturn("复盘输出");

        TradingReviewAppService service = new TradingReviewAppService(
                recordRepository, positionRepository, mock(AccountSnapshotRepository.class),
                contextEngine, aiClient, mock(TradingReviewFileRepository.class),
                mock(TradingLotService.class), trading, adviceHistory);

        service.generateReview("default", date);

        ArgumentCaptor<ContentRecord> recordCaptor = ArgumentCaptor.forClass(ContentRecord.class);
        verify(contextEngine).compose(any(), eq("trading"), recordCaptor.capture());
        String content = recordCaptor.getValue().content();
        assertTrue(content.contains("建议对照"), "复盘正文应含建议对照节");
        assertTrue(content.contains("阿呆当时说"), "应含阿呆当时说");
        assertTrue(content.contains("清仓"), "应含当时建议动作（中文，⚠️8）");
        assertTrue(content.contains("长电科技"), "应含标的");
        assertTrue(content.contains("-29.22"), "应含实际结果（持仓期涨幅）");
        assertTrue(content.contains("扛单超 5%"), "应含规则对照判定");
    }

    @Test
    void generateReview_noAdviceCompare_whenNoSoldThatDay() {
        // 当日无清仓 → 无建议对照段（不影响复盘）
        LocalDate date = LocalDate.of(2026, 9, 5);
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        PositionRepository positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll(any())).thenReturn(List.of());
        when(positionRepository.snapshot(any()))
                .thenReturn(PortfolioSnapshot.of(List.of(), BigDecimal.ZERO));
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.soldList(any())).thenReturn(List.of());
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.compose(any(), eq("trading"), any())).thenReturn(new ContextPackage(
                "trading", "用户身份摘要", date + " 交易复盘", "复盘正文",
                List.of("trading", "复盘"), List.of(),
                "【交易系统规则】止损三级别。\n\n请分析这条记录，输出 JSON 格式",
                LocalDateTime.now(), List.of()));
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(any(), any())).thenReturn("复盘输出");

        TradingReviewAppService service = new TradingReviewAppService(
                recordRepository, positionRepository, mock(AccountSnapshotRepository.class),
                contextEngine, aiClient, mock(TradingReviewFileRepository.class),
                mock(TradingLotService.class), trading,
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));

        service.generateReview("default", date);

        ArgumentCaptor<ContentRecord> recordCaptor = ArgumentCaptor.forClass(ContentRecord.class);
        verify(contextEngine).compose(any(), eq("trading"), recordCaptor.capture());
        String content = recordCaptor.getValue().content();
        assertFalse(content.contains("建议对照"), "当日无清仓不应有建议对照节");
    }

    @Test
    void generateReview_postSellAdvice_notReferenced() {
        // P1-2 回归（2026-09-05 三官）：清仓「之后」生成的建议不得出现在对照里（date ≤ sellDate 过滤）
        LocalDate date = LocalDate.of(2026, 9, 5);
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        PositionRepository positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll(any())).thenReturn(List.of());
        when(positionRepository.snapshot(any()))
                .thenReturn(PortfolioSnapshot.of(List.of(), BigDecimal.ZERO));
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.soldList(any())).thenReturn(List.of(
                new com.adaiadai.core.domain.trading.SoldTrade("600584", "长电科技",
                        date.minusDays(12), date, 12, "5+1", -29.22,
                        "扛单超 5%——按 R66 只输一根K线", "")));
        when(trading.getDailyTradeSummary(any(), any())).thenReturn(
                new TradingAppService.DailyTradeSummary(date.toString(), 1, 0, 1, 0, 30460, List.of(), null, null));
        com.adaiadai.core.domain.trading.AdviceHistoryRepository adviceHistory =
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class);
        // 唯一建议在 sellDate 当天之后（9-7）→ 必须被 date ≤ sellDate 过滤掉
        when(adviceHistory.findByMonth(eq("default"), eq(java.time.YearMonth.from(date).atDay(1)))).thenReturn(List.of(
                new com.adaiadai.core.domain.trading.AdviceEntry("adv_post", date.plusDays(2), "600584", "长电科技",
                        "clear", "清仓后才给的建议", List.of("R66"), false,
                        null, "manual-advice", date.plusDays(2).atTime(9, 0))));
        when(adviceHistory.findByMonth(eq("default"), eq(java.time.YearMonth.from(date).minusMonths(1).atDay(1))))
                .thenReturn(List.of());
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.compose(any(), eq("trading"), any())).thenReturn(new ContextPackage(
                "trading", "用户身份摘要", date + " 交易复盘", "复盘正文",
                List.of("trading", "复盘"), List.of(),
                "【交易系统规则】止损三级别。\n\n请分析这条记录，输出 JSON 格式",
                LocalDateTime.now(), List.of()));
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(any(), any())).thenReturn("复盘输出");
        TradingReviewAppService service = new TradingReviewAppService(
                recordRepository, positionRepository, mock(AccountSnapshotRepository.class),
                contextEngine, aiClient, mock(TradingReviewFileRepository.class),
                mock(TradingLotService.class), trading, adviceHistory);

        service.generateReview("default", date);

        ArgumentCaptor<ContentRecord> recordCaptor = ArgumentCaptor.forClass(ContentRecord.class);
        verify(contextEngine).compose(any(), eq("trading"), recordCaptor.capture());
        String content = recordCaptor.getValue().content();
        assertFalse(content.contains("建议对照"), "只有清仓后建议 → 对照段不应出现（卖后建议不算「当时说」）");
    }

    // ── 2026-09-07 复盘超时修复批：提交即返回（后台生成）+ 同日去重 ──

    /** submit 测试环境：reviewRepository 落盘即回读（模拟文件），aiClient 立即返回。 */
    private record ReviewSubmitHarness(TradingReviewAppService service, AiClient ai,
                                       TradingReviewFileRepository repo) {}

    private ReviewSubmitHarness reviewSubmitHarness(java.util.concurrent.Executor executor) {
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        PositionRepository positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll(any())).thenReturn(List.of());
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.compose(any(), eq("trading"), any())).thenReturn(new ContextPackage(
                "trading", "用户身份摘要", "复盘", "正文",
                List.of("trading", "复盘"), List.of(),
                "【交易系统规则】止损三级别。\n\n请分析这条记录，输出 JSON 格式",
                LocalDateTime.now(), List.of()));
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("复盘内容");
        TradingReviewFileRepository repo = mock(TradingReviewFileRepository.class);
        java.util.concurrent.atomic.AtomicReference<String> saved = new java.util.concurrent.atomic.AtomicReference<>();
        when(repo.read(any(), any())).thenAnswer(inv -> saved.get());
        doAnswer(inv -> {
            saved.set(inv.getArgument(2));
            return null;
        }).when(repo).save(any(), any(), anyString());
        TradingReviewAppService service = new TradingReviewAppService(
                recordRepository, positionRepository, mock(AccountSnapshotRepository.class),
                contextEngine, ai, repo,
                mock(TradingLotService.class), mock(TradingAppService.class),
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class),
                executor);
        return new ReviewSubmitHarness(service, ai, repo);
    }

    @Test
    void submitReview_noExisting_returnsPendingAndCompletesInline() {
        ReviewSubmitHarness h = reviewSubmitHarness(Runnable::run); // 同步执行器：submit 内即跑完
        LocalDate date = LocalDate.of(2026, 8, 2);

        TradingReviewAppService.ReviewSubmitResult first = h.service().submitReview("default", date);

        assertEquals("pending", first.status(), "首次提交应返回 pending（已受理）");
        assertEquals("2026-08-02", first.date());
        verify(h.ai()).generate(any(), any()); // 同步执行器内已跑完一次生成
        assertNotNull(h.repo().read("default", date), "生成应已落盘");

        // 第二次提交：文件已存在 → exists，且不重复调 AI（今天已生成的复盘不再烧钱重跑）
        TradingReviewAppService.ReviewSubmitResult second = h.service().submitReview("default", date);
        assertEquals("exists", second.status());
        verify(h.ai(), times(1)).generate(any(), any());
    }

    @Test
    void submitReview_sameDateRunning_dedupes() {
        // 阻塞执行器：只入队不跑 → 首次 pending、二次 running、任务仅入队一次
        java.util.List<Runnable> backlog = new java.util.ArrayList<>();
        java.util.concurrent.Executor blocker = backlog::add;
        ReviewSubmitHarness h = reviewSubmitHarness(blocker);
        LocalDate date = LocalDate.of(2026, 8, 2);

        TradingReviewAppService.ReviewSubmitResult first = h.service().submitReview("default", date);
        TradingReviewAppService.ReviewSubmitResult second = h.service().submitReview("default", date);

        assertEquals("pending", first.status());
        assertEquals("running", second.status(), "同日在生成中 → running 去重");
        assertEquals(1, backlog.size(), "同一日期只应入队一次生成任务");
        verify(h.ai(), never()).generate(any(), any()); // 尚未执行

        backlog.get(0).run(); // 跑完 → 落盘 → 再次提交 exists
        assertEquals("exists", h.service().submitReview("default", date).status());
        verify(h.ai(), times(1)).generate(any(), any());
    }
}

