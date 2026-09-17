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

    // ── P2-交易44（2026-09-14）：被丢掉的行必须可见（原来五处只 log.debug，用户看不到丢行）──

    @Test
    void parseLooseBatchDetailed_realScreenshot_reportsDroppedRows() {
        TradingParseAppService.LooseBatchParse p =
                service.parseLooseBatchDetailed("u1", REAL_ORDER_SCREENSHOT_TEXT);

        assertEquals(4, p.trades().size(), "已成 4 笔照旧解析");
        assertEquals(2, p.dropped().size(),
                "已报 1 笔 + 申购 1 笔都要如实上报——用户才知道「识别出 4 笔」之外还有 2 行没进候选");
        assertTrue(p.dropped().stream().anyMatch(d -> d.reason().contains("已报")),
                "未成交状态要说明原因：" + p.dropped());
        assertTrue(p.dropped().stream().anyMatch(d -> d.reason().contains("申购")),
                "申购/配号要说明原因：" + p.dropped());
        assertTrue(p.dropped().get(0).describe().startsWith("第 1 行"), p.dropped().get(0).describe());
    }

    @Test
    void parseLooseBatchDetailed_zeroPrice_reportedAsDropped() {
        // 有数量但价格为 0（送股/红股/占位行的典型形态）——原来静默丢弃，
        // 用户只看到「识别出 N 笔」而不知道这一行被跳过了
        String text = "识别交易动作：名称/代码 成交价/买卖 成交量/额 日期\n"
                + "有研新材 600206 0.000 买入 8 0.00 2026-08-26\n";
        TradingParseAppService.LooseBatchParse p = service.parseLooseBatchDetailed("u1", text);
        assertTrue(p.trades().isEmpty());
        assertEquals(1, p.dropped().size());
        assertTrue(p.dropped().get(0).reason().contains("不是有效成交"), p.dropped().get(0).reason());
    }

    /** 用户 2026-08-27 真实「当日成交」截图（VLM OCR，无状态列——名称 代码 价格 买卖 数量 金额 日期）。 */
    private static final String REAL_DAILY_TRADES_TEXT =
            "识别交易动作：名称/代码 成交价/买卖 成交量/额 日期\n"
                    + "天博配号 736448 0.000 买入 8 0.00 2026-08-26\n"
                    + "有研新材 600206 50.330 卖出 600 30198.00 2026-08-26\n"
                    + "中国稀土 000831 56.040 买入 100 5604.00 2026-08-26\n"
                    + "亨通光电 600487 64.830 买入 300 19449.00 2026-08-26\n"
                    + "广发证券 000776 21.170 买入 200 4234.00 2026-08-26";

    @Test
    void parseLooseBatch_dailyTradesNoStatusColumn_amountIsFilled() {
        // 2026-08-27：成交单无「状态」列，尾列是金额（30198.00）——金额=已成交 → 归集；
        // 天博配号（新股申购配号 + 价格 0）→ 跳过。应解析出 4 笔。
        java.util.List<TradingParseAppService.ParseResult> results =
                service.parseLooseBatch("u1", REAL_DAILY_TRADES_TEXT);

        assertEquals(4, results.size(), "有金额的 4 笔成交应全部解析，配号应过滤");
        TradingParseAppService.ParseResult sellYouyan = results.stream()
                .filter(r -> "600206".equals(r.symbol())).findFirst().orElseThrow();
        assertEquals("SELL", sellYouyan.direction());
        assertEquals(new BigDecimal("50.330"), sellYouyan.price());
        assertEquals(600, sellYouyan.volume());
        // 2026-08-27（用户反馈「今日 4 笔其实是昨天」）：成交单「日期」列必须提取——
        // 昨日成交今日确认 → entryDate 用截图日期而非确认当天
        assertEquals(java.time.LocalDate.of(2026, 8, 26), sellYouyan.tradeDate(),
                "行尾日期列 2026-08-26 应提取为成交日期");
        boolean hasTianbo = results.stream().anyMatch(r -> "736448".equals(r.symbol()));
        assertFalse(hasTianbo, "天博配号（新股申购配号）不得归集");
    }

    @Test
    void parseLooseBatch_leadingDate_extractsTradeDate() {
        // 2026-08-27：历史成交截图常见「日期 时间 名称 代码 价格 买卖 数量 金额」——日期在行首
        java.util.List<TradingParseAppService.ParseResult> results = service.parseLooseBatch("u1",
                "2026-08-26 14:56:09 有研新材 600206 50.330 卖出 600 30198.00\n"
                        + "2026-08-26 14:55:55 中国稀土 000831 56.040 买入 100 5604.00");
        assertEquals(2, results.size());
        assertEquals(java.time.LocalDate.of(2026, 8, 26), results.get(0).tradeDate(),
                "行首日期应提取为成交日期");
        assertEquals(java.time.LocalDate.of(2026, 8, 26), results.get(1).tradeDate());
    }

    @Test
    void parseLooseBatch_shortDate_noYear_usesCurrentYear() {
        // 2026-08-27：短日期 MM-dd（无年份）→ 当年；晚于今天视为去年（跨年场景）
        java.util.List<TradingParseAppService.ParseResult> results = service.parseLooseBatch("u1",
                "有研新材 600206 50.330 卖出 600 30198.00 08-26");
        assertEquals(1, results.size());
        java.time.LocalDate expected = java.time.LocalDate.of(
                java.time.LocalDate.now().getYear(), 8, 26);
        if (expected.isAfter(java.time.LocalDate.now())) expected = expected.minusYears(1);
        assertEquals(expected, results.get(0).tradeDate(), "短日期应解析为当年（跨年回退去年）");
    }

    @Test
    void parseLooseBatch_noDateColumn_tradeDateNull() {
        // 当日委托截图无日期列（只有委托时间）→ tradeDate=null（确认时回退确认当天）
        java.util.List<TradingParseAppService.ParseResult> results =
                service.parseLooseBatch("u1", REAL_ORDER_SCREENSHOT_TEXT);
        assertEquals(4, results.size());
        assertTrue(results.stream().allMatch(r -> r.tradeDate() == null),
                "无日期列的当日委托行 tradeDate 应为 null");
    }

    @Test
    void parseLooseBatch_noTailColumn_defaultsToFilled() {
        // 无尾列（名称 代码 价格 买卖 数量）→ 列齐全即成交行，默认归集
        java.util.List<TradingParseAppService.ParseResult> results =
                service.parseLooseBatch("u1", "京东方A 000725 5.200 买入 1000\n中国稀土 000831 56.040 买入 100");
        assertEquals(2, results.size());
        assertEquals("000725", results.get(0).symbol());
    }

    @Test
    void parseLooseBatch_missingCodeColumn_nameOnly() {
        // 2026-08-27：VLM OCR 漏代码列（名称 价格 买卖 数量 金额）→ 代码为 null、名称保留，
        // 由归集器按名称查代码补 symbol（此处只验解析层）
        java.util.List<TradingParseAppService.ParseResult> results = service.parseLooseBatch("u1",
                "识别交易动作：名称/代码 成交价/买卖 成交量/额 日期\n"
                        + "天博配号 0.000 买入 8 0.00 2026-08-26\n"
                        + "有研新材 50.330 卖出 600 30198.00 2026-08-26\n"
                        + "广发证券 21.170 买入 200 4234.00 2026-08-26");
        assertEquals(2, results.size(), "无代码列：配号跳过，两笔成交保留");
        assertNull(results.get(0).symbol(), "无代码列 → symbol=null（归集器补）");
        assertEquals("有研新材", results.get(0).name());
        assertEquals("SELL", results.get(0).direction());
        assertEquals(600, results.get(0).volume());
        assertEquals(new BigDecimal("50.330"), results.get(0).price());
        assertEquals("广发证券", results.get(1).name());
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

    // ── P0-交易53（2026-09-17 生产实测）：VLM 把表格「一行拆成多行」时必须能还原 ──

    /**
     * 用户 2026-09-17 真实「当日成交」截图——GLM 输出的**竖排**形态（每个单元格独占一行），
     * 三个单元格之间只剩换行、没有 {@code \h+} 分隔，横排正则 {@code TABLE_TRADE_PATTERN} 0 命中。
     * 原实现在这种版式下回退单笔解析（Schema 只能装一笔）→ 3 笔只落 1 笔。
     */
    private static final String REAL_VERTICAL_DAILY_TRADES_TEXT =
            "识别交易动作：当日成交\n"
                    + "开源证券 (****0888)\n"
                    + "名称/代码\n成交价/买卖\n成交量/额\n成交时间\n"
                    + "亨通光电\n600487\n68.270\n买入\n100\n6827.000\n13:08:59\n"
                    + "亨通光电\n600487\n67.730\n买入\n100\n6773.000\n10:13:10\n"
                    + "亨通光电\n600487\n67.920\n买入\n100\n6792.000\n10:11:44\n"
                    + "查看历史成交 >";

    @Test
    void parseLooseBatch_verticalTable_parsesAllThreeTrades() {
        java.util.List<TradingParseAppService.ParseResult> results =
                service.parseLooseBatch("u1", REAL_VERTICAL_DAILY_TRADES_TEXT);

        assertEquals(3, results.size(),
                "一张 3 笔买入的竖排截图必须还原 3 笔（原实现只落 1 笔——P0-交易53）");
        for (TradingParseAppService.ParseResult r : results) {
            assertEquals("600487", r.symbol());
            assertEquals("亨通光电", r.name());
            assertEquals("BUY", r.direction());
            assertEquals(100, r.volume(), "数量列是 100（6827 是成交额，不得当股数）");
        }
        assertEquals(new BigDecimal("68.270"), results.get(0).price());
        assertEquals(new BigDecimal("67.730"), results.get(1).price());
        assertEquals(new BigDecimal("67.920"), results.get(2).price());
    }

    @Test
    void parseLooseBatch_verticalTable_amountMismatch_correctsVolume() {
        // P1-交易56：数量列被识别成成交额（1000 股 vs 价 68.27 × 1000 = 68270 ≠ 6827）
        // → 用「成交额 ÷ 价格」反推修正为 100 股，而不是把 6827 当股数落库。
        java.util.List<TradingParseAppService.ParseResult> results = service.parseLooseBatch("u1",
                "亨通光电\n600487\n68.270\n买入\n1000\n6827.000\n13:08:59\n");

        assertEquals(1, results.size());
        assertEquals(100, results.get(0).volume(), "成交额交叉校验应把 1000 修正为 100");
    }

    @Test
    void parseLooseBatch_verticalTable_insufficientFields_noMatch() {
        // 四要素不全（有名称/代码/价格/方向但没数量）→ 不产出，宁可不认也不猜
        java.util.List<TradingParseAppService.ParseResult> results = service.parseLooseBatch("u1",
                "亨通光电\n600487\n68.270\n买入\n查看历史成交 >");
        assertTrue(results.isEmpty(), "凑不齐一笔时不得产出成交");

        // 但也不能静默：要如实上报「像是成交但缺数量」（P1-交易55）
        TradingParseAppService.LooseBatchParse p = service.parseLooseBatchDetailed("u1",
                "亨通光电\n600487\n68.270\n买入\n查看历史成交 >");
        assertTrue(p.trades().isEmpty());
        assertEquals(1, p.dropped().size(), "缺数量的疑似成交行必须上报：" + p.dropped());
        assertTrue(p.dropped().get(0).reason().contains("数量"), p.dropped().get(0).reason());
    }

}
