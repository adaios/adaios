package com.adaiadai.core.application;

import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingParseAppService — 一句话交易解析单元测试。
 */
class TradingParseAppServiceTest {

    private AiClient aiClient;
    private TradingParseAppService service;

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        service = new TradingParseAppService(aiClient, new ObjectMapper());
    }

    private void stubLlm(String json) {
        when(aiClient.generate(any(ContextPackage.class), any(String.class))).thenReturn(json);
    }

    @Test
    void blankTextNotMatched() {
        TradingParseAppService.ParseResult r = service.parse("u1", "  ");
        assertFalse(r.matched());
    }

    @Test
    void llmMatchedBuy() {
        stubLlm("{\"matched\": true, \"symbol\": \"000725\", \"name\": \"京东方A\", \"direction\": \"BUY\", \"price\": 5.2, \"volume\": 1000}");
        TradingParseAppService.ParseResult r = service.parse("u1", "买了 1000 股京东方 @5.2");
        assertTrue(r.matched());
        assertEquals("000725", r.symbol());
        assertEquals("京东方A", r.name());
        assertEquals("BUY", r.direction());
        assertEquals(new BigDecimal("5.2"), r.price());
        assertEquals(1000, r.volume());
    }

    @Test
    void llmMatchedSell() {
        stubLlm("{\"matched\": true, \"symbol\": \"600519\", \"name\": \"贵州茅台\", \"direction\": \"SELL\", \"price\": 1500.5, \"volume\": 200}");
        TradingParseAppService.ParseResult r = service.parse("u1", "卖出 200 股茅台 1500.5");
        assertTrue(r.matched());
        assertEquals("SELL", r.direction());
        assertEquals(new BigDecimal("1500.5"), r.price());
        assertEquals(200, r.volume());
    }

    @Test
    void llmUnmatchedFallsThroughToRegex() {
        // LLM 判定 unmatched，但正则可兜底
        stubLlm("{\"matched\": false}");
        TradingParseAppService.ParseResult r = service.parse("u1", "买了 1000 股京东方 @5.2");
        assertTrue(r.matched());
        assertEquals("BUY", r.direction());
        assertEquals(1000, r.volume());
        assertEquals(new BigDecimal("5.2"), r.price());
    }

    @Test
    void llmThrowsFallsBackToRegex() {
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "卖了 500 股 000725 @8.8");
        assertTrue(r.matched());
        assertEquals("SELL", r.direction());
        assertEquals(500, r.volume());
        assertEquals("000725", r.symbol());
    }

    @Test
    void regexExtractsSymbolAndDirection() {
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "买入 000725 1000 股 5.20");
        assertTrue(r.matched());
        assertEquals("BUY", r.direction());
        assertEquals("000725", r.symbol());
        assertEquals(1000, r.volume());
        assertEquals(new BigDecimal("5.20"), r.price());
    }

    @Test
    void noTradeIntentNotMatched() {
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "今天天气不错");
        assertFalse(r.matched());
        assertNull(r.direction());
    }

    @Test
    void invalidPriceNotMatched() {
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "买了 1000 股京东方 @0");
        // 正则可匹配结构，但价格 0 不合法 → 不匹配（正确性优先）
        assertFalse(r.matched());
    }

    // ── RFC 20260816 §4.3：NL 一句话带止损/买点 ──

    @Test
    void llmMatchedWithStopLossAndBuyPoint() {
        stubLlm("{\"matched\": true, \"symbol\": \"000725\", \"name\": \"京东方A\", \"direction\": \"BUY\", \"price\": 5.2, \"volume\": 1000, \"stopLossPrice\": 4.9, \"buyPoint\": \"B1\", \"targetPrice\": 6.0, \"reason\": \"突破买入\"}");
        TradingParseAppService.ParseResult r = service.parse("u1", "买了 1000 股京东方 @5.2，止损 4.9，B1");
        assertTrue(r.matched());
        assertEquals(new BigDecimal("4.9"), r.stopLossPrice(), "LLM 止损位应解析");
        assertEquals("B1", r.buyPoint(), "LLM 买点应解析");
        assertEquals(new BigDecimal("6.0"), r.targetPrice(), "LLM 目标价应解析");
        assertEquals("突破买入", r.reason(), "LLM 原因应解析");
    }

    @Test
    void regexFallbackExtractsStopLossAndBuyPoint() {
        // LLM down → 正则兜底：「，止损 4.9，B1」→ stopLossPrice/buyPoint（RFC 20260816 §4.3）
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "买了 1000 股京东方 @5.2，止损 4.9，B1");
        assertTrue(r.matched());
        assertEquals("BUY", r.direction());
        assertEquals(1000, r.volume());
        assertEquals(new BigDecimal("5.2"), r.price());
        assertEquals(new BigDecimal("4.9"), r.stopLossPrice(), "正则应捕获止损位");
        assertEquals("B1", r.buyPoint(), "正则应捕获买点");
    }

    @Test
    void regexFallbackExtractsSellNoStopLoss() {
        // SELL 一句话不带止损 → 止损/买点保持 null（SELL 可空）
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "卖出 500 股 000725 @8.8");
        assertTrue(r.matched());
        assertEquals("SELL", r.direction());
        assertNull(r.stopLossPrice(), "SELL 无止损应保持 null");
        assertNull(r.buyPoint(), "SELL 无买点应保持 null");
    }

    @Test
    void regexFallbackExtractsSb1BuyPoint() {
        // 买点 SB1 也应被正则捕获（[，,](B1|B2|B3|SB1)）
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "买入 000725 1000 股 5.20，止损 5.0，SB1");
        assertTrue(r.matched());
        assertEquals("SB1", r.buyPoint());
        assertEquals(new BigDecimal("5.0"), r.stopLossPrice());
    }
}
