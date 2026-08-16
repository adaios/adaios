package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingAdviceAppService — 持仓建议引擎测试（RFC 20260815 §0：建议是目的）。
 * <p>
 * 覆盖：空仓、LLM 成功结构化解析、规则硬约束注入（R66-R95 在 prompt、R96 不在）、
 * LLM 失败/坏 JSON 降级基础数据不抛错、行情缺失用存储价兜底、LLM 漏票补齐。
 */
class TradingAdviceAppServiceTest {

    private Position pos(String symbol, String name, int qty, String avgCost, String currentPrice) {
        return new Position(symbol, name, qty, new BigDecimal(avgCost), new BigDecimal(currentPrice),
                LocalDateTime.now());
    }

    /** 带止损/入场/买点计划的持仓（RFC 20260816：建议引擎判定所需的用户提供数据）。 */
    private Position posWithPlan(String symbol, String name, int qty, String avgCost, String currentPrice,
                                 String entryDate, String stopLoss, String buyPoint) {
        return new Position(symbol, name, qty, new BigDecimal(avgCost), new BigDecimal(currentPrice),
                LocalDateTime.now(), LocalDate.parse(entryDate), new BigDecimal(stopLoss), buyPoint, null);
    }

    private MarketData quote(String code, String name, String price, String changePercent) {
        return new MarketData(code, name, new BigDecimal(price), new BigDecimal(price),
                new BigDecimal(price), new BigDecimal(price), new BigDecimal(price),
                new BigDecimal(changePercent), 0);
    }

    private TradingAdviceAppService service(PositionRepository positions, MarketDataSource market, AiClient ai) {
        // G-3：注入真实规则引擎（判定口径统一，测试即验证引擎行为）
        return new TradingAdviceAppService(positions, market, ai,
                new com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine());
    }

    private TradingAdviceAppService serviceWithTwoPositions(MarketDataSource market, AiClient ai) {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(
                pos("000725", "京东方A", 1000, "5.20", "5.46"),
                pos("600519", "贵州茅台", 100, "1400.00", "1420.00")
        ));
        return service(repo, market, ai);
    }

    // ── 空仓 ──

    @Test
    void generateAdvice_emptyPositions_returnsEmptyWithoutAi() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        AiClient ai = mock(AiClient.class);
        TradingAdviceAppService svc = service(repo, mock(MarketDataSource.class), ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        assertTrue(res.advice().isEmpty());
        assertTrue(res.summary().contains("空仓"));
        verify(ai, never()).generate(any(), any());
    }

    // ── LLM 成功路径 + 规则硬约束 ──

    @Test
    void generateAdvice_llmSuccess_parsesAdviceAndBackfillsMissingSymbols() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of(
                "000725", quote("000725", "京东方A", "5.46", "5.0"),
                "600519", quote("600519", "贵州茅台", "1420.00", "1.4")
        ));
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("""
                {
                  "advice": [
                    {
                      "symbol": "000725",
                      "suggestion": "reduce",
                      "reason": "仓位集中且涨势透支，按 R81 单票仓位纪律建议减仓",
                      "rules": ["R81", "R88"]
                    }
                  ],
                  "summary": "持仓 2 只，京东方仓位占比需下调"
                }
                """);
        TradingAdviceAppService svc = serviceWithTwoPositions(market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        // 逐票建议与持仓一一对应：LLM 只回了 000725，600519 以基础数据补齐
        assertEquals(2, res.advice().size());
        TradingAdviceAppService.TradingAdviceItem item = res.advice().get(0);
        assertEquals("000725", item.symbol());
        assertEquals("京东方A", item.name());
        assertEquals("reduce", item.suggestion());
        assertTrue(item.reason().contains("R81"), "reason 必须引用规则号");
        assertTrue(item.rules().contains("R81"));
        // position_percent 由后端计算（确定性）：5460 / (5460+142000) ≈ 3.70%
        assertEquals(0, item.positionPercent().compareTo(new BigDecimal("3.70")),
                "持仓占比应后端计算，实际: " + item.positionPercent());
        TradingAdviceAppService.TradingAdviceItem missing = res.advice().get(1);
        assertEquals("600519", missing.symbol());
        assertNull(missing.suggestion(), "LLM 漏掉的持仓无建议字段");
        assertNull(missing.reason());
        assertTrue(missing.rules().isEmpty());
        assertEquals("持仓 2 只，京东方仓位占比需下调", res.summary());

        // FP-P2c（2026-08-16）：硬判定信号段直接断言——茅台占比 96.3% 触发 R81 OVER_WEIGHT
        ArgumentCaptor<ContextPackage> hardCtx = ArgumentCaptor.forClass(ContextPackage.class);
        verify(ai).generate(hardCtx.capture(), any());
        assertTrue(hardCtx.getValue().prompt().contains("超 R81 上限 25%"),
                "茅台占比 96.3% 应触发 R81 超仓硬信号，实际 prompt: " + hardCtx.getValue().prompt());
        assertTrue(hardCtx.getValue().prompt().contains("→ suggestion 参考 reduce（R81）"),
                "超仓硬信号应标注参考 reduce");
    }

    @Test
    void generateAdvice_promptContainsRuleConstraints_r66toR95Only() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("{\"advice\": [], \"summary\": \"无建议\"}");
        TradingAdviceAppService svc = serviceWithTwoPositions(market, ai);

        svc.generateAdvice("default");

        // 硬约束：止损 R66-R80 + 仓位 R81-R95 必须注入 prompt（真实规则文本，非硬编码）
        ArgumentCaptor<ContextPackage> ctxCaptor = ArgumentCaptor.forClass(ContextPackage.class);
        verify(ai).generate(ctxCaptor.capture(), any());
        String prompt = ctxCaptor.getValue().prompt();
        assertTrue(prompt.contains("R66 只输一根K线"), "止损规则 R66 应注入，实际 prompt 无 R66");
        assertTrue(prompt.contains("R71 每天压力测试"), "止损规则 R71 应注入");
        assertTrue(prompt.contains("R81 100万以下分4-5个仓位"), "仓位规则 R81 应注入");
        assertTrue(prompt.contains("R95 输得起的钱炒股"), "仓位规则 R95 应注入");
        assertFalse(prompt.contains("R96"), "纪律类 R96 不在 R66-R95 硬约束内");
        assertFalse(prompt.contains("R1 "), "择时类 R1 不在 R66-R95 硬约束内");
        // 输出契约：要求引用规则号
        assertTrue(prompt.contains("必须引用具体规则号"), "输出要求必须强制引用规则号");
        // 持仓数据在 prompt 中
        assertTrue(prompt.contains("000725") && prompt.contains("贵州茅台"));
    }

    // ── 兜底路径 ──

    @Test
    void generateAdvice_llmThrows_fallsBackToBasicDataWithoutError() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of(
                "000725", quote("000725", "京东方A", "5.46", "0.0"),
                "600519", quote("600519", "贵州茅台", "1420.00", "0.0")
        ));
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenThrow(new RuntimeException("LLM 挂了"));
        TradingAdviceAppService svc = serviceWithTwoPositions(market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        // 兜底：返回基础数据（symbol/name/position_percent），无建议字段，不抛错
        assertEquals(2, res.advice().size());
        for (TradingAdviceAppService.TradingAdviceItem item : res.advice()) {
            assertNull(item.suggestion(), "兜底时无建议字段");
            assertNull(item.reason());
            assertTrue(item.rules().isEmpty());
            assertTrue(item.positionPercent() != null, "position_percent 由后端计算，兜底也应给出");
        }
        assertTrue(res.summary().contains("总市值"), "兜底 summary 应含基础数据");
    }

    @Test
    void generateAdvice_llmInvalidJson_fallsBackToBasicData() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("这不是 JSON，随便输出一段话");
        TradingAdviceAppService svc = serviceWithTwoPositions(market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        assertEquals(2, res.advice().size());
        assertNull(res.advice().get(0).suggestion(), "坏 JSON 也应降级为无建议字段");
    }

    @Test
    void generateAdvice_marketEmpty_usesStoredPriceForPercent() {
        // 行情数据源安全约定（异常返回空 Map）：降级用持仓存储价计算占比
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("""
                {"advice": [], "summary": "无建议"}
                """);
        TradingAdviceAppService svc = serviceWithTwoPositions(market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        // 存储价：000725 @5.46 → 5460；600519 @1420 → 142000；京东方占比 ≈ 3.70%
        assertEquals(0, res.advice().get(0).positionPercent().compareTo(new BigDecimal("3.70")));
    }

    @Test
    void generateAdvice_llmFabricatedSymbol_isDropped() {
        // 防幻觉：LLM 输出不存在的 symbol → 丢弃，不回填虚构标的
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("""
                {
                  "advice": [
                    {"symbol": "999999", "suggestion": "buy", "reason": "虚构标的", "rules": ["R81"]}
                  ],
                  "summary": "x"
                }
                """);
        TradingAdviceAppService svc = serviceWithTwoPositions(market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        assertEquals(2, res.advice().size());
        assertTrue(res.advice().stream().noneMatch(i -> i.symbol().equals("999999")),
                "LLM 幻觉的标的必须被丢弃");
    }

    @Test
    void generateAdvice_suggestionAlias_normalized() {
        // suggestion 别名归一：sell → clear、持有 → hold，未知值 → null
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("""
                {
                  "advice": [
                    {"symbol": "000725", "suggestion": "sell", "reason": "跌破止损位", "rules": ["R66"]},
                    {"symbol": "600519", "suggestion": "持有", "reason": "正常", "rules": []}
                  ],
                  "summary": "x"
                }
                """);
        TradingAdviceAppService svc = serviceWithTwoPositions(market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        assertEquals("clear", res.advice().get(0).suggestion(), "sell 应归一为 clear");
        assertEquals("hold", res.advice().get(1).suggestion(), "持有应归一为 hold");
    }

    // ── RFC 20260816 §3.1：止损/入场/买点注入 → clear 判定有数据可判 ──

    @Test
    void generateAdvice_promptContainsStopLossEntryAndBuyPoint() {
        // 用户提供的止损位/入场日期（第几天）/买点必须注入 prompt，LLM 才有数据判 R66
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(
                posWithPlan("000725", "京东方A", 1000, "5.20", "5.46", "2026-08-01", "5.00", "B1")));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725", quote("000725", "京东方A", "5.46", "0.5")));
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("{\"advice\": [], \"summary\": \"无建议\"}");
        TradingAdviceAppService svc = service(repo, market, ai);

        svc.generateAdvice("default");

        ArgumentCaptor<ContextPackage> ctxCaptor = ArgumentCaptor.forClass(ContextPackage.class);
        verify(ai).generate(ctxCaptor.capture(), any());
        String prompt = ctxCaptor.getValue().prompt();
        // 现价/止损位以 stripTrailingZeros 呈现：5.00 → 5，4.90 → 4.9
        assertTrue(prompt.contains("止损位 5"), "prompt 应注入止损位，实际: " + prompt);
        assertTrue(prompt.contains("入场"), "prompt 应注入入场信息（入场第几天）");
        assertTrue(prompt.contains("2026-08-01"), "prompt 应注入入场日期");
        assertTrue(prompt.contains("买点 B1"), "prompt 应注入买点");
        assertTrue(prompt.contains("已跌破止损位"), "prompt 应含止损硬判定（R66 clear）");
        assertTrue(prompt.contains("R53"), "prompt 应含入场未涨 R53 候选口径");
    }

    @Test
    void generateAdvice_currentPriceBelowStop_llmClear_accepted() {
        // 现价 < 止损位 → prompt 注入两价 + 硬判定；LLM 依数据判 clear（R66）→ 原样输出
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(
                posWithPlan("000725", "京东方A", 1000, "5.20", "5.46", "2026-08-01", "6.00", "B1")));
        MarketDataSource market = mock(MarketDataSource.class);
        // 行情现价 4.90 < 止损位 6.00 → 已跌破止损位
        when(market.quote(any())).thenReturn(Map.of("000725", quote("000725", "京东方A", "4.90", "-8.0")));
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("""
                {
                  "advice": [
                    {
                      "symbol": "000725",
                      "suggestion": "clear",
                      "reason": "现价 4.90 已跌破止损位 6.00，按 R66 止损纪律建议清仓",
                      "rules": ["R66"]
                    }
                  ],
                  "summary": "京东方已跌破止损位"
                }
                """);
        TradingAdviceAppService svc = service(repo, market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        assertEquals(1, res.advice().size());
        TradingAdviceAppService.TradingAdviceItem item = res.advice().get(0);
        assertEquals("000725", item.symbol());
        assertEquals("clear", item.suggestion(), "跌破止损位 → clear");
        assertTrue(item.reason().contains("R66"), "reason 应引用 R66");
        assertTrue(item.rules().contains("R66"));

        // 硬判定数据确实进 prompt（现价/止损位两价同现，LLM 才能判；4.90→4.9、6.00→6 剥尾零呈现）
        ArgumentCaptor<ContextPackage> ctxCaptor = ArgumentCaptor.forClass(ContextPackage.class);
        verify(ai).generate(ctxCaptor.capture(), any());
        String prompt = ctxCaptor.getValue().prompt();
        assertTrue(prompt.contains("现价 4.9"), "prompt 应含跌破止损位的现价");
        assertTrue(prompt.contains("止损位 6"), "prompt 应含止损位");
    }

    @Test
    void generateAdvice_noStopLossPlan_stillWorksWithUnknownMarker() {
        // 旧数据无止损/入场（null）→ prompt 以「未设置/入场日期未知」呈现，不 NPE、不编造
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(
                pos("000725", "京东方A", 1000, "5.20", "5.46")));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("000725", quote("000725", "京东方A", "5.46", "0.5")));
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("{\"advice\": [], \"summary\": \"无建议\"}");
        TradingAdviceAppService svc = service(repo, market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        ArgumentCaptor<ContextPackage> ctxCaptor = ArgumentCaptor.forClass(ContextPackage.class);
        verify(ai).generate(ctxCaptor.capture(), any());
        String prompt = ctxCaptor.getValue().prompt();
        assertTrue(prompt.contains("止损位 未设置"), "无止损位应显式标注未设置");
        assertTrue(prompt.contains("入场日期未知"), "无入场日期应显式标注未知");
        assertEquals(1, res.advice().size(), "无计划数据不影响建议流程");
    }

    @Test
    void positionPercent_usesTotalAssets_includingCash() {
        // FP-P2（2026-08-16 审查修复）：R81 分母 = 持仓市值 + 现金余额——
        // 单票市值 5460 + 现金 100 万 → 占比 ≈0.54%，不触发 OVER_WEIGHT（旧口径恒 100% 错发 reduce）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(
                pos("000725", "京东方A", 1000, "5.20", "5.46")));
        when(repo.cashBalance(any())).thenReturn(new BigDecimal("1000000"));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        AiClient ai = mock(AiClient.class);
        when(ai.generate(any(), any())).thenReturn("{\"advice\": [], \"summary\": \"无建议\"}");
        TradingAdviceAppService svc = service(repo, market, ai);

        TradingAdviceAppService.TradingAdviceResponse res = svc.generateAdvice("default");

        // 5460 / (5460 + 1000000) ≈ 0.54%
        assertEquals(0, res.advice().get(0).positionPercent().compareTo(new BigDecimal("0.54")),
                "持仓占比分母应含现金余额，实际: " + res.advice().get(0).positionPercent());
        // 且不触发 R81 OVER_WEIGHT 硬信号（旧口径 100% 必触发）
        ArgumentCaptor<ContextPackage> ctxCaptor = ArgumentCaptor.forClass(ContextPackage.class);
        verify(ai).generate(ctxCaptor.capture(), any());
        String prompt = ctxCaptor.getValue().prompt();
        assertFalse(prompt.contains("超 R81 上限"), "现金充足时不应触发 R81 超仓硬信号");
    }
}
