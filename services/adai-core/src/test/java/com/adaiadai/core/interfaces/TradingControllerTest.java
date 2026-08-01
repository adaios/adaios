package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.application.TradingReviewAppService;
import com.adaiadai.core.domain.trading.Position;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TradingController — detectConflicts 基于真实规则解析（#23 修复：不再硬编码规则名）。
 * 依赖 gradle test 运行时 cwd（services/adai-core）下可读的 os/trading-os/11-context/rules.md。
 */
class TradingControllerTest {

    private MockMvc buildMvc(TradingAppService tradingAppService) {
        TradingReviewAppService reviewAppService = mock(TradingReviewAppService.class);
        TradingController controller = new TradingController(tradingAppService, reviewAppService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void detectConflicts_noPositions_citesRealRule() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions()).thenReturn(List.of());
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/knowledge/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.conflicts[0].rule").value(containsString("R")))
                .andExpect(jsonPath("$.conflicts[0].description").value(containsString("空仓")));
    }

    @Test
    void detectConflicts_singlePosition_citesR96() throws Exception {
        Position single = new Position("600000", "浦发银行", 1000,
                new BigDecimal("10.00"), new BigDecimal("10.50"), LocalDateTime.now());
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions()).thenReturn(List.of(single));
        MockMvc mvc = buildMvc(trading);

        mvc.perform(get("/api/v1/trading/knowledge/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.conflicts[0].rule").value(containsString("R96")));
    }
}
