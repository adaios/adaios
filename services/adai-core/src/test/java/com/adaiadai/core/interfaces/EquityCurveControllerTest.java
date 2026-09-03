package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.EquityCurveService;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

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
    private PluginService plugins;

    @BeforeEach
    void setUp() {
        svc = mock(EquityCurveService.class);
        plugins = mock(PluginService.class);
        mvc = MockMvcBuilders.standaloneSetup(new EquityCurveController(svc, plugins)).build();
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
}
