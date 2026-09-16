package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.EquityCurveService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** EquityCurveController — 资金曲线端点测试（2026-09-04 决策方案 A）。 */
class EquityCurveControllerTest {

    private MockMvc mvc;
    private EquityCurveService svc;
    private TradingAppService trading;
    private PluginService plugins;

    @BeforeEach
    void setUp() {
        svc = mock(EquityCurveService.class);
        plugins = mock(PluginService.class);
        // 2026-09-15 日周月盈亏批：Controller 组合 dailyPnlDetail 对齐「今日」口径——
        // 默认给 0（无覆盖效果），需要断言覆盖时由用例自己 stub。
        trading = mock(TradingAppService.class);
        when(trading.dailyPnlDetail(anyString(), any())).thenReturn(new TradingAppService.DailyPnlDetail(
                java.math.BigDecimal.ZERO, List.of(), java.util.Map.of()));
        mvc = MockMvcBuilders.standaloneSetup(new EquityCurveController(svc, trading, plugins)).build();
    }

    @Test
    void equityCurve_withPlugin_returnsPoints() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(svc.build("adai")).thenReturn(new EquityCurveService.EquityCurve(List.of(
                new EquityCurveService.EquityPoint(java.time.LocalDate.of(2026, 8, 3),
                        new java.math.BigDecimal("12000.00"), new java.math.BigDecimal("0"),
                        new java.math.BigDecimal("12000.00"), new java.math.BigDecimal("10000"),
                        new java.math.BigDecimal("1.2000"), new java.math.BigDecimal("0.0000"))),
                0, "2026-08-03", "2026-08-03"));

        mvc.perform(get("/api/v1/trading/equity-curve").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].date").value("2026-08-03"))
                .andExpect(jsonPath("$.points[0].totalAssets").value(12000.0))
                .andExpect(jsonPath("$.points[0].netValue").value(1.2))
                .andExpect(jsonPath("$.startDate").value("2026-08-03"));
    }

    @Test
    void equityCurve_withoutPlugin_403() throws Exception {
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(false);
        mvc.perform(get("/api/v1/trading/equity-curve").header("X-User-Id", "alice"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pnlPeriods_todayUsesDailyPnlAndBackfillsWeekMonth() throws Exception {
        // 差分口径今日 +464.58，当日盈亏口径 −503.90 → today 取后者，差额回填进周/月（三者自洽）
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);
        java.math.BigDecimal base = new java.math.BigDecimal("82459.53");
        when(svc.periods("adai")).thenReturn(new EquityCurveService.PnlPeriods(
                new EquityCurveService.PeriodPnl("today", new java.math.BigDecimal("464.58"),
                        new java.math.BigDecimal("0.56"), base, java.time.LocalDate.now(), false),
                new EquityCurveService.PeriodPnl("week", new java.math.BigDecimal("-637.82"),
                        new java.math.BigDecimal("-0.76"), new java.math.BigDecimal("83561.93"),
                        java.time.LocalDate.now().minusDays(1), false),
                new EquityCurveService.PeriodPnl("month", new java.math.BigDecimal("-44701.59"),
                        new java.math.BigDecimal("-30.28"), new java.math.BigDecimal("147625.70"),
                        java.time.LocalDate.now().withDayOfMonth(1), true),
                java.time.LocalDate.now().toString(), java.time.LocalDate.of(2026, 9, 11), ""));
        when(trading.dailyPnlDetail(anyString(), any())).thenReturn(new TradingAppService.DailyPnlDetail(
                new java.math.BigDecimal("-503.90"), List.of(), java.util.Map.of()));

        mvc.perform(get("/api/v1/trading/pnl-periods").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.pnl").value(-503.90))
                .andExpect(jsonPath("$.today.pct").value(-0.61))
                // 周：−637.82 + (−503.90 − 464.58) = −1606.30
                .andExpect(jsonPath("$.week.pnl").value(-1606.30))
                // 月：−44701.59 + (−968.48) = −45670.07
                .andExpect(jsonPath("$.month.pnl").value(-45670.07))
                .andExpect(jsonPath("$.month.partial").value(true))
                .andExpect(jsonPath("$.anchorDate").value("2026-09-11"));
    }
}
