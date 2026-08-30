package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradingCaseFileRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingCaseAppServiceTest — 案例用例编排（第四阶段环 3：LLM 理解）。
 * <p>
 * 验证：generateInsight 成功落盘 aiInsight（summary/keyFeatures/confidence）、
 * LLM 输出非 JSON → 业务异常不落半成品、案例不存在 → 业务异常。
 */
class TradingCaseAppServiceTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradingCaseRepository repository = new TradingCaseFileRepository(storage);
    private final KlineService klineService = mock(KlineService.class);
    private final TradingAppService tradingAppService = mock(TradingAppService.class);
    private final AiClient aiClient = mock(AiClient.class);
    private TradingCaseAppService appService;

    @BeforeEach
    void setUp() {
        appService = new TradingCaseAppService(klineService, repository, tradingAppService, aiClient);
        when(klineService.klineRange(anyString(), any(), any()))
                .thenReturn(buildCandles());
    }

    /** 90 根日 K：前 60 上涨回撤 + buyDate + 后 30 上涨（buyDate = idx 60）。 */
    private List<Candle> buildCandles() {
        List<Candle> list = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < 90; i++) {
            double close = i < 60 ? 10 + i * 0.05 : 12.5 + (i - 60) * 0.08;
            list.add(new Candle(start.plusDays(i), close * 0.99, close * 1.02, close * 0.98, close, 1000 + i));
        }
        return list;
    }

    private CaseRecord sample() {
        CaseRecord record = appService.annotate("adai", "000725", LocalDate.of(2026, 3, 5), "B1",
                "回踩 60 日线 + 地量", List.of("缩量回踩"), null);
        // 绕过 annotate 的 K 线 mock：annotate 用 klineRange（已 mock），name 查 lookupName（mock 返回 null）
        return record;
    }

    @Test
    void generateInsight_success_persistsAiInsight() {
        CaseRecord record = sample();
        when(aiClient.generate(any(), any())).thenReturn("""
                {"summary":"缩量回踩黄线（60日线）获支撑，KDJ 低位金叉，次日放量启动——教科书式 B1",
                 "keyFeatures":["缩量回踩","黄线支撑","KDJ低位金叉"],"confidence":0.9}
                """);

        CaseRecord updated = appService.generateInsight("adai", record.id());

        assertEquals("缩量回踩黄线（60日线）获支撑，KDJ 低位金叉，次日放量启动——教科书式 B1",
                updated.aiInsight().summary());
        assertEquals(3, updated.aiInsight().keyFeatures().size());
        assertEquals(0.9, updated.aiInsight().confidence(), 0.001);
        assertEquals(false, updated.aiInsight().reviewed());
        // 已持久化（再次读取 aiInsight 非空）
        CaseRecord reloaded = repository.findById("adai", record.id()).orElseThrow();
        assertEquals("缩量回踩黄线（60日线）获支撑，KDJ 低位金叉，次日放量启动——教科书式 B1",
                reloaded.aiInsight().summary());
    }

    @Test
    void generateInsight_llmReturnsNonJson_throwsBusinessException() {
        CaseRecord record = sample();
        when(aiClient.generate(any(), any())).thenReturn("抱歉，我今天状态不好");

        TradingException ex = assertThrows(TradingException.class,
                () -> appService.generateInsight("adai", record.id()));
        assertTrue(ex.getMessage().contains("AI 理解生成失败"), ex.getMessage());
        // 不落半成品：aiInsight 仍为空
        CaseRecord reloaded = repository.findById("adai", record.id()).orElseThrow();
        assertEquals("", reloaded.aiInsight().summary());
    }

    @Test
    void generateInsight_emptySummary_throwsBusinessException() {
        CaseRecord record = sample();
        when(aiClient.generate(any(), any())).thenReturn("{\"summary\":\"\",\"keyFeatures\":[]}");

        assertThrows(TradingException.class,
                () -> appService.generateInsight("adai", record.id()));
    }

    @Test
    void generateInsight_caseNotExists_throwsBusinessException() {
        assertThrows(TradingException.class,
                () -> appService.generateInsight("adai", "2099-01-01_000725"));
    }

    // ── 环 4：判定当下（match）──

    @Test
    void match_emptyLibrary_returnsEmptyMatches() {
        // 库空 → 静默空（不报错，不影响规则判定）
        TradingCaseAppService.MatchResponse resp = appService.match("adai", "000725", null);
        assertEquals("000725", resp.symbol());
        assertEquals(0, resp.matches().size());
    }

    @Test
    void match_returnsTopSimilarCases() {
        // 预置一个案例（buyDate 2026-03-05，特征由 annotate 计算）
        CaseRecord c1 = sample();
        // 查询同形态：构造一个与案例特征接近的 K 线（同样「涨后回撤横盘」形态）
        when(klineService.klineRange(anyString(), any(), any())).thenReturn(buildCandles());

        TradingCaseAppService.MatchResponse resp = appService.match("adai", "000725", LocalDate.of(2026, 3, 5));

        assertEquals(1, resp.matches().size(), "库中 1 案例应命中");
        TradingCaseAppService.MatchItem item = resp.matches().get(0);
        assertEquals(c1.id(), item.caseId());
        assertTrue(item.similarityPercent() > 80,
                "同形态相似度应较高，实际 " + item.similarityPercent());
    }

    @Test
    void match_klineUnavailable_throwsBusinessException() {
        sample();
        when(klineService.klineRange(anyString(), any(), any())).thenReturn(List.of());
        TradingException ex = assertThrows(TradingException.class,
                () -> appService.match("adai", "000725", LocalDate.of(2026, 3, 5)));
        assertTrue(ex.getMessage().contains("K 线数据"), ex.getMessage());
    }
}
