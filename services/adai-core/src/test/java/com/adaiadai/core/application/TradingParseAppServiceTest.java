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
}
