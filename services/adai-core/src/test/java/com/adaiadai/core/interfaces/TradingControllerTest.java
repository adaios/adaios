package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.application.TradingReviewAppService;
import com.adaiadai.core.domain.trading.PortfolioSnapshot;
import com.adaiadai.core.domain.trading.Position;
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
 * promote 测试写入 os/trading-os/99-inbox/review-2099-01-01.md，测试后清理。
 */
class TradingControllerTest {

    private static final String PROMOTE_TEST_DATE = "2099-01-01";
    private static final Path PROMOTE_TEST_FILE = Paths.get("../../os/trading-os/99-inbox/review-" + PROMOTE_TEST_DATE + ".md")
            .toAbsolutePath().normalize();

    private MockMvc buildMvc(TradingAppService tradingAppService,
                             TradingReviewAppService reviewAppService) {
        TradingController controller = new TradingController(tradingAppService, reviewAppService);
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
                new BigDecimal("10500.00"), new BigDecimal("2000.00"), LocalDateTime.now());
        when(trading.getPortfolioSnapshot(any())).thenReturn(snapshot);
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPnl").value(500.00))
                .andExpect(jsonPath("$.totalCost").value(10000.00))
                .andExpect(jsonPath("$.totalValue").value(10500.00))
                .andExpect(jsonPath("$.cashBalance").value(2000.00));
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
                    .andExpect(jsonPath("$.path").isString());

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
