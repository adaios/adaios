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

    @Test
    void regexHandUnit_convertsToShares() {
        // 「5 手」= 500 股（A股 1手=100股）
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "卖了 5 手京东方A @5.8");
        assertTrue(r.matched());
        assertEquals("SELL", r.direction());
        assertEquals(500, r.volume());
        assertEquals("京东方A", r.name());
    }

    @Test
    void regexSharesUnit_noConversion() {
        when(aiClient.generate(any(ContextPackage.class), any(String.class)))
                .thenThrow(new RuntimeException("LLM down"));
        TradingParseAppService.ParseResult r = service.parse("u1", "买了 300 股京东方A @5.2");
        assertTrue(r.matched());
        assertEquals(300, r.volume());
    }

    @Test
    void llmHandUnit_convertsToShares() {
        // LLM 侧也换算（prompt 已要求）
        stubLlm("{\"matched\": true, \"symbol\": \"000725\", \"name\": \"京东方A\", \"direction\": \"SELL\", \"price\": 5.8, \"volume\": 500}");
        TradingParseAppService.ParseResult r = service.parse("u1", "卖了 5 手京东方A @5.8");
        assertTrue(r.matched());
        assertEquals(500, r.volume());
    }

    // ── parseLooseBatch：截图表格批量解析（2026-08-26 截图归集缺口修复）──

    /** 用户 2026-08-26 真实「当日委托」截图（VLM 识别文字）：5 笔委托，其中 4 笔已成、1 笔已报、1 笔申购。 */
    private static final String REAL_ORDER_SCREENSHOT_TEXT =
            "当日委托 开源证券 (****0888) 名称/代码 价格/买卖 数量/状态 委托时间 "
                    + "云南锗业 002428 93.480 卖出 100 已报 14:57:15 撤 "
                    + "广发证券 000776 21.170 买入 200 已成 14:56:27 撤 "
                    + "亨通光电 600487 64.840 买入 300 已成 14:56:09 撤 "
                    + "中国稀土 000831 56.040 买入 100 已成 14:55:55 撤 "
                    + "有研新材 600206 50.330 卖出 600 已成 14:55:43 撤 "
                    + "天博申购 732448 62.650 买入 4000 已确认 14:52:55 撤 查看历史委托 >";

    @Test
    void parseLooseBatch_realScreenshot_parsesOnlyFilledTrades() {
        // 今天真实截图：应解析出 4 笔已成（广发/亨通/稀土/有研），滤掉 1 笔已报（云南，未成交）+ 1 笔申购（天博）
        java.util.List<TradingParseAppService.ParseResult> results =
                service.parseLooseBatch("u1", REAL_ORDER_SCREENSHOT_TEXT);

        assertEquals(4, results.size(), "已成 4 笔应全部解析，已报/申购应过滤");

        TradingParseAppService.ParseResult buyHengtong = results.stream()
                .filter(r -> "600487".equals(r.symbol())).findFirst().orElseThrow();
        assertEquals("亨通光电", buyHengtong.name());
        assertEquals("BUY", buyHengtong.direction());
        assertEquals(new BigDecimal("64.840"), buyHengtong.price());
        assertEquals(300, buyHengtong.volume());

        TradingParseAppService.ParseResult sellYouyan = results.stream()
                .filter(r -> "600206".equals(r.symbol())).findFirst().orElseThrow();
        assertEquals("SELL", sellYouyan.direction());
        assertEquals(600, sellYouyan.volume());

        boolean hasYunnan = results.stream().anyMatch(r -> "002428".equals(r.symbol()));
        assertFalse(hasYunnan, "云南锗业已报未成交，不得归集");
        boolean hasTianbo = results.stream().anyMatch(r -> "732448".equals(r.symbol()));
        assertFalse(hasTianbo, "天博申购为非交易，不得归集");
    }

    @Test
    void parseLooseBatch_plainText_noMatch() {
        // 普通聊天/非表格文字 → 空列表（不影响单笔 parseLoose 回退）
        java.util.List<TradingParseAppService.ParseResult> results =
                service.parseLooseBatch("u1", "今天天气不错");
        assertTrue(results.isEmpty());
    }

    @Test
    void parseLooseBatch_tableWithPendingAndFilled_filtersStatus() {
        // 同一股票两笔：一笔已成、一笔已报（部分成交/挂单）→ 只归集成交那笔
        java.util.List<TradingParseAppService.ParseResult> results = service.parseLooseBatch("u1",
                "云南锗业 002428 93.480 卖出 100 已报 14:57:15 撤 云南锗业 002428 92.500 卖出 200 已成 14:50:00 撤");
        assertEquals(1, results.size());
        assertEquals(200, results.get(0).volume());
        assertEquals("SELL", results.get(0).direction());
    }

    @Test
    void parseLooseBatch_blankInput_empty() {
        assertTrue(service.parseLooseBatch("u1", "  ").isEmpty());
        assertTrue(service.parseLooseBatch("u1", null).isEmpty());
    }

}
