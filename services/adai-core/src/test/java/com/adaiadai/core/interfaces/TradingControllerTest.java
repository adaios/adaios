package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.application.TradingReviewAppService;
import com.adaiadai.core.domain.trading.PortfolioSnapshot;
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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TradingController — 全部 9 端点接口测试。
 * <p>
 * detectConflicts 基于真实规则解析（#23 修复：不再硬编码规则名），
 * 依赖 gradle test 运行时 cwd（services/adai-core）下可读的 os/trading-os/11-context/rules.md。
 * promote 测试写入 os/trading-os/99-inbox/2099-01-01_交易复盘.md（#211 文件名约定），测试后清理。
 */
class TradingControllerTest {

    private static final String PROMOTE_TEST_DATE = "2099-01-01";
    private static final Path PROMOTE_TEST_FILE = Paths.get("../../os/trading-os/99-inbox/" + PROMOTE_TEST_DATE + "_交易复盘.md")
            .toAbsolutePath().normalize();

    private MockMvc buildMvc(TradingAppService tradingAppService,
                             TradingReviewAppService reviewAppService) {
        return buildMvc(tradingAppService, reviewAppService, "trading");
    }

    /** "default"用户启用插件服务的 mock（promote 门控测试用：无 trading 插件 → 403）。 */
    private PluginService pluginService(String... plugins) {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById("default")).thenReturn(Optional.of(
                new Account("default", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), List.of(plugins))));
        return new PluginService(accounts, new PluginRegistry());
    }

    /** 指定"default"用户的插件（promote 门控测试用：无 trading 插件 → 403）。 */
    private MockMvc buildMvc(TradingAppService tradingAppService,
                             TradingReviewAppService reviewAppService,
                             String... defaultPlugins) {
        TradingController controller = new TradingController(tradingAppService, reviewAppService,
                pluginService(defaultPlugins));
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(controller)
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
        when(trading.recordTrade(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(position("600000")));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"600000","name":"浦发银行","direction":"BUY","price":10.5,"volume":100}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("600000"));
    }

    @Test
    void recordTrade_blankSymbol_400() throws Exception {
        MockMvc mvc = buildMvc(mock(TradingAppService.class));

        mvc.perform(post("/api/v1/trading/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol":"","name":"浦发银行","direction":"BUY","price":10.5,"volume":100}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordTrade_sellUnheld_tradingExceptionMapsTo400() throws Exception {
        // #147：SELL 未持有 → TradingException → GlobalExceptionHandler 映射 400 + 人话消息
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.recordTrade(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new com.adaiadai.core.domain.trading.TradingException("未持有 600000，无法卖出"));
        TradingController controller = new TradingController(trading, mock(TradingReviewAppService.class),
                pluginService("trading"));
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
        // RFC 20260814：promote 写入 os/trading-os/99-inbox（共享知识库）→ 无 trading 插件用户 403
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
                    // #178 A 档：提示入库候选不会自动融入 AI context（需在 trading-os 工作流融合后重建 11-context）
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("11-context")));

            // 文件真实写入 os/trading-os/99-inbox/
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

    // ── 规则冲突检测（保留原覆盖） ──

    @Test
    void detectConflicts_noPositions_citesRealRule() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions(any())).thenReturn(List.of());
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/knowledge/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.conflicts[0].rule").value(containsString("R")))
                .andExpect(jsonPath("$.conflicts[0].description").value(containsString("空仓")));
    }

    @Test
    void detectConflicts_singlePosition_citesR96() throws Exception {
        Position single = position("600000");
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions(any())).thenReturn(List.of(single));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/knowledge/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.conflicts[0].rule").value(containsString("R96")));
    }
}
