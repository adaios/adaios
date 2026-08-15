package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.market.MarketData;
import com.adaiadai.core.kernel.market.MarketDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
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

    private MarketData quote(String code, String name, String price, String changePercent) {
        return new MarketData(code, name, new BigDecimal(price), new BigDecimal(price),
                new BigDecimal(price), new BigDecimal(price), new BigDecimal(price),
                new BigDecimal(changePercent), 0);
    }

    private TradingAdviceAppService service(PositionRepository positions, MarketDataSource market, AiClient ai) {
        return new TradingAdviceAppService(positions, market, ai);
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
}
