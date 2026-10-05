package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingPlanService;
import com.adaiadai.core.domain.trading.TradingPlan;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TradingPlanController — 操作计划端点契约（RFC 20261003-trading-plan-and-review-loop）。
 *
 * <p><b>P2-交易72（2026-10-05）新增</b>：{@code POST /plans/{date}/status} —— 当天事后的状态回填
 * （「今天没动」/「想动没动」）。契约要点：**不改动既有计划条目** · **同日重复提交幂等**
 * （{@code recorded=false} 如实回执，不假报落库）· **认不出的状态 400 不猜**。
 *
 * <p>用户原话（2026-09-23）：「那我今天没有买卖 怎么告诉你呢 你还在等我的数据」。
 */
class TradingPlanControllerTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 12);

    private MockMvc mvc;
    private TradingPlanService planService;
    private PluginService plugins;

    @BeforeEach
    void setUp() {
        planService = mock(TradingPlanService.class);
        plugins = mock(PluginService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TradingPlanController(planService, plugins)).build();
    }

    private static TradingPlan plan(String dayStatus) {
        return new TradingPlan(D,
                List.of(new TradingPlan.PlanItem("plan_1", "SELL", "600206", "有研新材",
                        "跌破 45.5", "LT", new java.math.BigDecimal("45.5"), 800,
                        "600206 跌破 45.5 清仓", false)),
                "只做计划内的票", dayStatus, LocalDateTime.of(2026, 10, 11, 21, 30));
    }

    /** 回填「今天没动」→ 200，回执含 dayStatus 与 recorded=true（真的落盘）。 */
    @Test
    void planStatus_recordsNoTrade() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(planService.recordDayStatus(eq("adai"), eq(D), eq(TradingPlan.DAY_STATUS_NO_TRADE)))
                .thenReturn(new TradingPlanService.DayStatusResult(plan(TradingPlan.DAY_STATUS_NO_TRADE), true));

        mvc.perform(post("/api/v1/trading/plans/2026-10-12/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NO_TRADE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-12"))
                .andExpect(jsonPath("$.dayStatus").value("NO_TRADE"))
                .andExpect(jsonPath("$.recorded").value(true))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].text").value("600206 跌破 45.5 清仓"))
                .andExpect(jsonPath("$.note").value("只做计划内的票"));
    }

    /** **同日重复提交幂等**：后端如实回 recorded=false（客户端据此说「已经记着了」，不假报落库）。 */
    @Test
    void planStatus_repeatIsIdempotentReportedAsNotRecorded() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(planService.recordDayStatus(anyString(), any(LocalDate.class), anyString()))
                .thenReturn(new TradingPlanService.DayStatusResult(plan(TradingPlan.DAY_STATUS_NO_TRADE), false));

        mvc.perform(post("/api/v1/trading/plans/2026-10-12/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NO_TRADE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(false))
                .andExpect(jsonPath("$.dayStatus").value("NO_TRADE"));
    }

    /** 「想动没动」是另一种状态——原样落盘（不折叠成 NO_TRADE）。 */
    @Test
    void planStatus_recordsWantedNotActedAsItsOwnValue() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(planService.recordDayStatus(eq("adai"), eq(D), eq(TradingPlan.DAY_STATUS_WANTED_NOT_ACTED)))
                .thenReturn(new TradingPlanService.DayStatusResult(
                        plan(TradingPlan.DAY_STATUS_WANTED_NOT_ACTED), true));

        mvc.perform(post("/api/v1/trading/plans/2026-10-12/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"WANTED_NOT_ACTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayStatus").value("WANTED_NOT_ACTED"))
                .andExpect(jsonPath("$.recorded").value(true));
    }

    /** 认不出的状态 → 400 人话，**一次都不许往服务层发**（宁可问清楚，不替他记一个他没说过的状态）。 */
    @Test
    void planStatus_unknownValue_400WithoutTouchingService() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);

        mvc.perform(post("/api/v1/trading/plans/2026-10-12/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(planService, never()).recordDayStatus(anyString(), any(LocalDate.class), anyString());
    }

    /** 日期格式坏 → 400（不落到「今天」上猜）。 */
    @Test
    void planStatus_badDate_400() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);

        mvc.perform(post("/api/v1/trading/plans/2026-10/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NO_TRADE\"}"))
                .andExpect(status().isBadRequest());

        verify(planService, never()).recordDayStatus(anyString(), any(LocalDate.class), anyString());
    }

    /** 交易插件未启用 → 403（与计划其余端点同门控）。 */
    @Test
    void planStatus_withoutPlugin_403() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(false);

        mvc.perform(post("/api/v1/trading/plans/2026-10-12/status")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NO_TRADE\"}"))
                .andExpect(status().isForbidden());
    }

    /** 读计划：dayStatus 一并返回（前端据此显示「这天你记的是：没动」，而不是当成没写）。 */
    @Test
    void getPlan_carriesDayStatus() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(planService.find("adai", D)).thenReturn(java.util.Optional.of(plan(TradingPlan.DAY_STATUS_NO_TRADE)));

        mvc.perform(get("/api/v1/trading/plans/2026-10-12").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayStatus").value("NO_TRADE"))
                .andExpect(jsonPath("$.date").value("2026-10-12"));
    }

    /** 收盘对账带上这天回填的状态——「没动」与「今天没有成交记录」互相印证（口径对齐 P2-交易67）。 */
    @Test
    void review_carriesDayStatus() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(planService.review("adai", D)).thenReturn(new TradingPlanService.PlanReview(
                D, true, List.of(), List.of(), 0, 0, TradingPlan.DAY_STATUS_NO_TRADE));

        mvc.perform(get("/api/v1/trading/plans/2026-10-12/review").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayStatus").value("NO_TRADE"))
                .andExpect(jsonPath("$.hasPlan").value(true));
    }
}
