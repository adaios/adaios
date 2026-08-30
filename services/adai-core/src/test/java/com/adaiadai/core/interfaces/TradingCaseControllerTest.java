package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingCaseAppService;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.market.Candle;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TradingCaseControllerTest — 完美买点案例端点（2026-08-30 第四阶段环 1-2）。
 * <p>
 * 覆盖：插件门控 403、标注成功、非法 symbol 400、重复标注 400、列表、详情（含 kline 重放）、删除。
 */
class TradingCaseControllerTest {

    private final TradingCaseAppService appService = mock(TradingCaseAppService.class);
    private final PluginService pluginService = mock(PluginService.class);
    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MockMvc mvc(String... plugins) {
        when(pluginService.hasPlugin(anyString(), anyString())).thenReturn(
                java.util.Arrays.asList(plugins).contains(PluginRegistry.PLUGIN_TRADING));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new TradingCaseController(appService, pluginService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private CaseRecord sampleRecord() {
        return new CaseRecord(
                "2026-08-03_000725", "000725", "京东方A", LocalDate.of(2026, 8, 3), "B1",
                "回踩 60 日线 + 地量", List.of("缩量回踩"), LocalDateTime.of(2026, 8, 30, 10, 0),
                new CaseRecord.CaseWindow(60, 30),
                new CaseRecord.CaseFeatures(52.3, 0.62, 8.4, true, -0.31, true,
                        "close_above_ma20_below_ma60", 1.8, "near", false, 5, false),
                new CaseRecord.CaseVerify(18.2, 24.5, -2.1, false),
                CaseRecord.CaseAiInsight.empty());
    }

    @Test
    void annotate_withoutPlugin_returns403() throws Exception {
        mvc().perform(post("/api/v1/trading/cases")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\",\"buyDate\":\"2026-08-03\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void annotate_success_returnsCaseWithFeatures() throws Exception {
        CaseRecord record = sampleRecord();
        when(appService.annotate(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(record);
        mvc("trading").perform(post("/api/v1/trading/cases")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\",\"buyDate\":\"2026-08-03\",\"buyType\":\"B1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("2026-08-03_000725"))
                .andExpect(jsonPath("$.symbol").value("000725"))
                .andExpect(jsonPath("$.features.drawdownFromHighPct").value(52.3))
                .andExpect(jsonPath("$.verify.+5dReturnPct").value(18.2));
    }

    @Test
    void annotate_invalidSymbol_returns400() throws Exception {
        mvc("trading").perform(post("/api/v1/trading/cases")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"abc\",\"buyDate\":\"2026-08-03\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void annotate_missingBuyDate_returns400() throws Exception {
        mvc("trading").perform(post("/api/v1/trading/cases")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void annotate_duplicate_returns400WithHumanMessage() throws Exception {
        when(appService.annotate(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new TradingException("该案例已标注过（2026-08-03_000725），可查看或删除后重标"));
        mvc("trading").perform(post("/api/v1/trading/cases")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\",\"buyDate\":\"2026-08-03\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("已标注过")));
    }

    @Test
    void annotate_klineUnavailable_returns400WithHumanMessage() throws Exception {
        when(appService.annotate(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new TradingException("无法获取 000725 在 2026-08-03 前后的 K 线数据，请稍后重试或核对代码"));
        mvc("trading").perform(post("/api/v1/trading/cases")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\",\"buyDate\":\"2026-08-03\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("K 线数据")));
    }

    @Test
    void list_returnsCases() throws Exception {
        when(appService.list(anyString())).thenReturn(List.of(sampleRecord()));
        mvc("trading").perform(get("/api/v1/trading/cases").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("2026-08-03_000725"));
    }

    @Test
    void detail_withKline_returnsCaseAndCandles() throws Exception {
        Candle c = new Candle(LocalDate.of(2026, 6, 2), 4.2, 4.3, 4.15, 4.25, 123456);
        when(appService.detail(anyString(), anyString(), anyBoolean()))
                .thenReturn(new TradingCaseAppService.CaseDetail(sampleRecord(), List.of(c)));
        mvc("trading").perform(get("/api/v1/trading/cases/2026-08-03_000725")
                        .header("X-User-Id", "adai")
                        .param("kline", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseRecord.id").value("2026-08-03_000725"))
                .andExpect(jsonPath("$.kline[0].close").value(4.25));
    }

    @Test
    void delete_success_returnsDeleted() throws Exception {
        mvc("trading").perform(delete("/api/v1/trading/cases/2026-08-03_000725")
                        .header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        verify(appService).delete(anyString(), anyString());
    }

    @Test
    void delete_notExists_returns400() throws Exception {
        org.mockito.Mockito.doThrow(new TradingException("案例不存在：2026-08-03_000725"))
                .when(appService).delete(anyString(), anyString());
        mvc("trading").perform(delete("/api/v1/trading/cases/2026-08-03_000725")
                        .header("X-User-Id", "adai"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("案例不存在")));
    }
}
