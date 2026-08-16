package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAdviceAppService;
import com.adaiadai.core.application.TradingParseAppService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.application.WatchlistBuyPointService;
import com.adaiadai.core.application.TradingReviewAppService;
import com.adaiadai.core.domain.trading.PortfolioSnapshot;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TradingController — 全部 10 端点接口测试。
 * <p>
 * promote 测试写入 os/trading-engine/99-inbox/2099-01-01_交易复盘.md（#211 文件名约定），测试后清理。
 * 规则冲突检测端点（/trading/knowledge/conflicts）已迁至 AdminController（REVIEW P-be-01），
 * 对应测试移至 AdminControllerTest（仍依赖真实 rules.md）。
 */
class TradingControllerTest {

    private static final String PROMOTE_TEST_DATE = "2099-01-01";
    private static final Path PROMOTE_TEST_FILE = Paths.get("../../os/trading-engine/99-inbox/" + PROMOTE_TEST_DATE + "_交易复盘.md")
            .toAbsolutePath().normalize();

    private MockMvc buildMvc(TradingAppService tradingAppService,
                             TradingReviewAppService reviewAppService) {
        return buildMvc(tradingAppService, reviewAppService, "trading");
    }

    /** "default"用户启用插件服务的 mock（promote 门控测试用：无 trading 插件 → 403）。
     *  G-2（2026-08-16）：findById(any())——任意 userId 继承插件配置（alice/adai 头测试走同一配置）；
     *  无插件用例显式传空数组。 */
    private PluginService pluginService(String... plugins) {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(any())).thenReturn(Optional.of(
                new Account("default", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), List.of(plugins))));
        return new PluginService(accounts, new PluginRegistry());
    }

    /** 指定"default"用户的插件（promote 门控测试用：无 trading 插件 → 403）。 */
    private MockMvc buildMvc(TradingAppService tradingAppService,
                             TradingReviewAppService reviewAppService,
                             String... defaultPlugins) {
        return buildMvc(tradingAppService, reviewAppService,
                mock(TradingAdviceAppService.class), defaultPlugins);
    }

    private MockMvc buildMvc(TradingAppService tradingAppService,
                             TradingReviewAppService reviewAppService,
                             TradingAdviceAppService adviceAppService,
                             String... defaultPlugins) {
        TradingController controller = new TradingController(tradingAppService, reviewAppService,
                adviceAppService, mock(TradingParseAppService.class), pluginService(defaultPlugins),
                mock(WatchlistBuyPointService.class));
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // GlobalExceptionHandler：TradingException/校验失败（RFC 20260816 BUY 缺止损）→ 400 + 人话消息
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private MockMvc buildMvc(TradingAppService tradingAppService) {
        return buildMvc(tradingAppService, mock(TradingReviewAppService.class));
    }

    private Position position(String symbol) {
        return new Position(symbol, "浦发银行", 1000,
                new BigDecimal("10.00"), new BigDecimal("10.50"), LocalDateTime.now());
    }

    // ── 持仓 / 组合快照 / 交易 ──

    @Test
    void getPositions_returnsList() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions(any())).thenReturn(List.of(position("600000")));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("600000"))
                .andExpect(jsonPath("$[0].pnl").isNumber());
    }

    @Test
    void getPositions_forwardsUserIdHeader() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions(any())).thenReturn(List.of());
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/positions").header("X-User-Id", "alice"))
                .andExpect(status().isOk());
        verify(trading).getPositions("alice");
    }

    @Test
    void getPortfolio_returnsSnapshot() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        PortfolioSnapshot snapshot = new PortfolioSnapshot(
                List.of(position("600000")),
                new BigDecimal("500.00"), new BigDecimal("10000.00"),
                new BigDecimal("10500.00"), new BigDecimal("2000.00"), LocalDateTime.now(),
                1);
        when(trading.getPortfolioSnapshot(any())).thenReturn(snapshot);
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPnl").value(500.00))
                .andExpect(jsonPath("$.totalCost").value(10000.00))
                .andExpect(jsonPath("$.totalValue").value(10500.00))
                .andExpect(jsonPath("$.cashBalance").value(2000.00))
                .andExpect(jsonPath("$.positionCount").value(1));
    }

    @Test
    void recordTrade_valid_returnsUpdatedPositions() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.recordTrade(any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(position("600000")));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"600000","name":"浦发银行","direction":"BUY","price":10.5,"volume":100,
                                "stopLossPrice":9.5,"buyPoint":"B1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("600000"));
    }

    @Test
    void recordTrade_buyMissingStopLoss_400() throws Exception {
        // RFC 20260816：BUY 缺止损位/买点 → 400 + 人话消息（不再放行无止损的买入）
        MockMvc mvc = buildMvc(mock(TradingAppService.class));

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"600000","name":"浦发银行","direction":"BUY","price":10.5,"volume":100}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("止损位")));
    }

    @Test
    void recordTrade_buyMissingBuyPoint_400() throws Exception {
        // BUY 只填止损缺买点 → 400（买点同样必填）
        MockMvc mvc = buildMvc(mock(TradingAppService.class));

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"600000","name":"浦发银行","direction":"BUY","price":10.5,"volume":100,
                                "stopLossPrice":9.5}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("买点")));
    }

    @Test
    void recordTrade_buyWithAllFields_200() throws Exception {
        // 全字段（含 entryDate/止损/买点/目标价/原因）→ 200
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.recordTrade(any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(position("600000")));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"600000","name":"浦发银行","direction":"BUY","price":10.5,"volume":100,
                                "entryDate":"2026-08-16","stopLossPrice":9.5,"buyPoint":"B1",
                                "targetPrice":12.0,"reason":"突破买入"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void recordTrade_sellWithoutStopLoss_200() throws Exception {
        // SELL：止损/买点可空 → 200（SELL 流水不写止损）
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.recordTrade(any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(position("600000")));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"600000","name":"浦发银行","direction":"SELL","price":10.5,"volume":100}"""))
                .andExpect(status().isOk());
    }

    @Test
    void recordTrade_blankSymbol_400() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class));

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"","name":"浦发银行","direction":"BUY","price":10.5,"volume":100,
                                "stopLossPrice":9.5,"buyPoint":"B1"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordTrade_sellUnheld_tradingExceptionMapsTo400() throws Exception {
        // #147：SELL 未持有 → TradingException → GlobalExceptionHandler 映射 400 + 人话消息
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.recordTrade(any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any()))
                .thenThrow(new com.adaiadai.core.domain.trading.TradingException("未持有 600000，无法卖出"));
        TradingController controller = new TradingController(trading, mock(TradingReviewAppService.class),
                mock(TradingAdviceAppService.class), mock(TradingParseAppService.class), pluginService("trading"),
                mock(WatchlistBuyPointService.class));
        ObjectMapper om = new ObjectMapper();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"600000","name":"浦发银行","direction":"SELL","price":10.5,"volume":100}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("无法卖出")));
    }

    // ── 持仓建议（RFC 20260815：建议引擎） ──

    @Test
    void generateAdvice_returnsStructuredAdvice() throws Exception {
        TradingAdviceAppService advice = mock(TradingAdviceAppService.class);
        when(advice.generateAdvice(any())).thenReturn(new TradingAdviceAppService.TradingAdviceResponse(
                List.of(new TradingAdviceAppService.TradingAdviceItem(
                        "000725", "京东方A", new BigDecimal("30.0"),
                        "reduce", "仓位占比 30% 超 R81 单票上限，建议减仓至 20%", List.of("R81"))),
                "持仓 1 只，京东方仓位偏高需调整"));
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), advice, "trading");

        mvc.perform(post("/api/v1/trading/advice").header("X-User-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advice[0].symbol").value("000725"))
                .andExpect(jsonPath("$.advice[0].name").value("京东方A"))
                .andExpect(jsonPath("$.advice[0].position_percent").value(30.0))
                .andExpect(jsonPath("$.advice[0].suggestion").value("reduce"))
                .andExpect(jsonPath("$.advice[0].reason").value(containsString("R81")))
                .andExpect(jsonPath("$.advice[0].rules[0]").value("R81"))
                .andExpect(jsonPath("$.summary").value(containsString("京东方")));
        verify(advice).generateAdvice("default");
    }

    @Test
    void generateAdvice_withoutTradingPlugin_403() throws Exception {
        // RFC 20260814：无 trading 插件用户不得生成建议（与 promote/recordTrade 403 同口径）
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class),
                mock(TradingAdviceAppService.class), new String[0]);

        mvc.perform(post("/api/v1/trading/advice").header("X-User-Id", "default"))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordTrade_nameOptional_ok() throws Exception {
        // RFC 20260815：name 可空（web 标注"名称（可选）"），缺名请求应 200（后端以 symbol 兜底）
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.recordTrade(any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(position("600000")));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"600000\",\"direction\":\"BUY\",\"price\":10.5,\"volume\":100,\"stopLossPrice\":9.5,\"buyPoint\":\"B1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void recordTrade_nameTooLong_400() throws Exception {
        // @Size(max=32)：超长名称 400（防脏数据进持仓/时间线）
        String longName = "很".repeat(33);
        MockMvc mvc = buildMvc(mock(TradingAppService.class));

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"600000\",\"name\":\"" + longName + "\",\"direction\":\"BUY\",\"price\":10.5,\"volume\":100,\"stopLossPrice\":9.5,\"buyPoint\":\"B1\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── 复盘 ──

    @Test
    void generateReview_returnsContent() throws Exception {
        TradingReviewAppService review = mock(TradingReviewAppService.class);
        when(review.generateReview(any(), any())).thenReturn("今日复盘内容");
        MockMvc mvc = buildMvc(mock(TradingAppService.class), review);

        mvc.perform(post("/api/v1/trading/review").param("date", "2026-08-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-08-02"))
                .andExpect(jsonPath("$.content").value("今日复盘内容"));
    }

    @Test
    void getReview_returnsContent() throws Exception {
        TradingReviewAppService review = mock(TradingReviewAppService.class);
        when(review.getReview(any(), any())).thenReturn("已有复盘");
        MockMvc mvc = buildMvc(mock(TradingAppService.class), review);

        mvc.perform(get("/api/v1/trading/review").param("date", "2026-08-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("已有复盘"));
    }

    @Test
    void getReview_notFound_whenBlank() throws Exception {
        TradingReviewAppService review = mock(TradingReviewAppService.class);
        when(review.getReview(any(), any())).thenReturn("  ");
        MockMvc mvc = buildMvc(mock(TradingAppService.class), review);

        mvc.perform(get("/api/v1/trading/review").param("date", "2026-08-02"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReviews_returnsDates() throws Exception {
        TradingReviewAppService review = mock(TradingReviewAppService.class);
        when(review.listReviews(any())).thenReturn(List.of(LocalDate.of(2026, 8, 2)));
        MockMvc mvc = buildMvc(mock(TradingAppService.class), review);

        mvc.perform(get("/api/v1/trading/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("2026-08-02"));
    }

    @Test
    void hasActivity_true_whenTrading() throws Exception {
        TradingReviewAppService review = mock(TradingReviewAppService.class);
        when(review.hasTradingActivity(any(), any())).thenReturn(true);
        MockMvc mvc = buildMvc(mock(TradingAppService.class), review);

        mvc.perform(get("/api/v1/trading/has-activity").param("date", "2026-08-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActivity").value(true));
    }

    // ── 知识反哺 ──

    @Test
    void promoteToInbox_noReview_404() throws Exception {
        TradingReviewAppService review = mock(TradingReviewAppService.class);
        when(review.getReview(any(), any())).thenReturn(null);
        MockMvc mvc = buildMvc(mock(TradingAppService.class), review);

        mvc.perform(post("/api/v1/trading/reviews/" + PROMOTE_TEST_DATE + "/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"测试\",\"sections\":[\"持仓\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void promoteToInbox_noTradingPlugin_403() throws Exception {
        // RFC 20260814：promote 写入 os/trading-engine/99-inbox（共享知识库）→ 无 trading 插件用户 403
        TradingReviewAppService review = mock(TradingReviewAppService.class);
        when(review.getReview(any(), any())).thenReturn("当日复盘内容");
        // 显式空插件（buildMvc 2 参重载默认给 trading，不能用）
        MockMvc mvc = buildMvc(mock(TradingAppService.class), review, new String[0]);

        mvc.perform(post("/api/v1/trading/reviews/" + PROMOTE_TEST_DATE + "/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"测试\",\"sections\":[\"持仓\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString("插件未启用")));
    }

    @Test
    void promoteToInbox_writesInboxFile() throws Exception {
        try {
            TradingReviewAppService review = mock(TradingReviewAppService.class);
            when(review.getReview(any(), any())).thenReturn("当日复盘内容");
            MockMvc mvc = buildMvc(mock(TradingAppService.class), review);

            mvc.perform(post("/api/v1/trading/reviews/" + PROMOTE_TEST_DATE + "/promote")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"测试\",\"sections\":[\"持仓\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ok"))
                    .andExpect(jsonPath("$.path").isString())
                    // #178 A 档：提示入库候选不会自动融入 AI context（需在 trading-engine 工作流融合后重建 knowledge/context）
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("knowledge/context")));

            // 文件真实写入 os/trading-engine/99-inbox/
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(PROMOTE_TEST_FILE),
                    "promote 应写入入库候选文件");
            String content = Files.readString(PROMOTE_TEST_FILE);
            org.junit.jupiter.api.Assertions.assertTrue(content.contains("当日复盘内容"));
            org.junit.jupiter.api.Assertions.assertTrue(content.contains("**用户备注：** 测试"));
        } finally {
            Files.deleteIfExists(PROMOTE_TEST_FILE);
        }
    }

    /**
     * #184：promote 内容脱敏——复盘含真实持仓数字，入库候选（进 git 追踪的 os/）必须替换为占位符。
     */
    @Test
    void sanitizeReviewContent_masksPositionNumbers() {
        String review = """
                今日无交易。持仓贵州茅台未动，成本1400现价1400。
                贵州茅台持有100股，市值14万，占用全部资金，现金余额为零。
                大盘三大指数收红（上证+1.02%、深证+1.42%）。
                """;
        String sanitized = TradingController.sanitizeReviewContent(review);

        // 持仓数字全部脱敏
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("100股"), "股数应脱敏");
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("14万"), "市值应脱敏");
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("1400"), "成本/现价应脱敏");
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("现金余额为零"), "现金余额应脱敏");

        // 占位符已替换
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("持有N股"));
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("市值（已脱敏）"));
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("成本（已脱敏）现价（已脱敏）"));
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("现金余额（已脱敏）"));

        // 标的名保留（公开信息 + 规则引用需要标的语境）
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("贵州茅台"));
        // 大盘指数等公开行情不误伤
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("上证+1.02%"));
    }

    @Test
    void sanitizeReviewContent_nullOrBlank_returnsAsIs() {
        org.junit.jupiter.api.Assertions.assertNull(TradingController.sanitizeReviewContent(null));
        org.junit.jupiter.api.Assertions.assertEquals("", TradingController.sanitizeReviewContent(""));
        org.junit.jupiter.api.Assertions.assertEquals("  ", TradingController.sanitizeReviewContent("  "));
    }

    @Test
    void recordTrade_withoutTradingPlugin_403() throws Exception {
        // REVIEW P2-B1：无 trading 插件用户不得写持仓（与 promote 403 同口径）
        // 显式空插件（buildMvc 2 参重载默认给 trading，不能用）
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);

        mvc.perform(post("/api/v1/trading/trades")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"600000\",\"name\":\"浦发银行\",\"direction\":\"BUY\",\"price\":10.0,\"volume\":100,\"stopLossPrice\":9.0,\"buyPoint\":\"B1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void generateReview_withoutTradingPlugin_403() throws Exception {
        // REVIEW P2-B1：无 trading 插件用户不得生成复盘（与 promote 403 同口径）
        // 显式空插件（buildMvc 2 参重载默认给 trading，不能用）
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);

        mvc.perform(post("/api/v1/trading/review")
                        .header("X-User-Id", "default")
                        .param("date", "2099-01-01"))
                .andExpect(status().isForbidden());
    }

    // ── G-2（2026-08-16）：交易闭环读端点同样门控（20260814 边界表「交易闭环端点不暴露」）──

    @Test
    void getPositions_withoutTradingPlugin_403() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);
        mvc.perform(get("/api/v1/trading/positions").header("X-User-Id", "default"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPortfolio_withoutTradingPlugin_403() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);
        mvc.perform(get("/api/v1/trading/portfolio").header("X-User-Id", "default"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTrades_withoutTradingPlugin_403() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);
        mvc.perform(get("/api/v1/trading/trades").header("X-User-Id", "default"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getReview_withoutTradingPlugin_403() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);
        mvc.perform(get("/api/v1/trading/review").header("X-User-Id", "default").param("date", "2099-01-01"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReviews_withoutTradingPlugin_403() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);
        mvc.perform(get("/api/v1/trading/reviews").header("X-User-Id", "default"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTrades_returnsTradeHistory() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getTradeHistory(any(), any(), any())).thenReturn(java.util.List.of(
                new TradeRecord("trade_1", "000725", "京东方A", TradeDirection.BUY,
                        new java.math.BigDecimal("5.2"), 1000, new java.math.BigDecimal("5200"),
                        java.time.LocalDate.of(2026, 8, 16), new java.math.BigDecimal("4.9"), "B1",
                        null, null, null, java.time.LocalDateTime.of(2026, 8, 16, 9, 30), null)));
        TradingController controller = new TradingController(trading, mock(TradingReviewAppService.class),
                mock(TradingAdviceAppService.class), mock(TradingParseAppService.class), pluginService("trading"),
                mock(WatchlistBuyPointService.class));
        ObjectMapper om = new ObjectMapper();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(get("/api/v1/trading/trades").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("000725"))
                .andExpect(jsonPath("$[0].stopLossPrice").value(4.9))
                .andExpect(jsonPath("$[0].buyPoint").value("B1"));
    }


    // ── 代码查名 + 持仓初始化导入（通达信，2026-08-16）──

    @Test
    void lookupName_returnsName() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.lookupName("000725")).thenReturn("京东方A");
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/lookup").param("symbol", "000725"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("000725"))
                .andExpect(jsonPath("$.name").value("京东方A"));
    }

    @Test
    void lookupName_withoutTradingPlugin_403() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);
        mvc.perform(get("/api/v1/trading/lookup").param("symbol", "000725"))
                .andExpect(status().isForbidden());
    }

    @Test
    void importPositions_importsAndReportsMissingStopLoss() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.importPositions(any(), any())).thenReturn(
                new TradingAppService.PositionImportResult(2,
                        java.util.List.of("600519 贵州茅台", "000725 京东方A")));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(post("/api/v1/trading/positions/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"symbol\":\"600519\",\"name\":\"贵州茅台\",\"quantity\":100,\"avgCost\":1400}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.missingStopLoss.length()").value(2));
    }

    @Test
    void importPositions_withoutTradingPlugin_403() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);
        mvc.perform(post("/api/v1/trading/positions/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    void saveImportFile_returnsTranscodedContent() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.saveImportFile(any(), any(), any())).thenReturn(
                new TradingAppService.ImportFileResult(
                        "trading/imports/2026-08/1_x.txt", "代码\t名称\n000725\t京东方A\n"));
        MockMvc mvc = buildMvc(trading);

        MockMultipartFile file = new MockMultipartFile(
                "file", "持仓股.txt", "text/plain", "GBK bytes".getBytes());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/trading/imports/save").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(org.hamcrest.Matchers.containsString("imports/")))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("000725")));
    }

    @Test
    void saveImportFile_withoutTradingPlugin_403() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class), mock(TradingReviewAppService.class), new String[0]);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/trading/imports/save").file(file))
                .andExpect(status().isForbidden());
    }
}

