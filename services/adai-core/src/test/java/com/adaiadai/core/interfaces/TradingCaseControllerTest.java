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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        when(appService.annotateWithCheck(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new TradingCaseAppService.AnnotateResult(record, null));
        mvc("trading").perform(post("/api/v1/trading/cases")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\",\"buyDate\":\"2026-08-03\",\"buyType\":\"B1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.id").value("2026-08-03_000725"))
                .andExpect(jsonPath("$.case.symbol").value("000725"))
                .andExpect(jsonPath("$.case.features.drawdownFromHighPct").value(52.3))
                .andExpect(jsonPath("$.case.verify.+5dReturnPct").value(18.2));
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
        when(appService.annotateWithCheck(anyString(), anyString(), any(), any(), any(), any(), any()))
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
        when(appService.annotateWithCheck(anyString(), anyString(), any(), any(), any(), any(), any()))
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
        when(appService.detail(anyString(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(new TradingCaseAppService.CaseDetail(sampleRecord(), List.of(c), null));
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

    // ── 环 3：AI 理解（insight）──

    @Test
    void insight_withoutPlugin_returns403() throws Exception {
        mvc().perform(post("/api/v1/trading/cases/2026-08-03_000725/insight")
                        .header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    @Test
    void insight_success_returnsUpdatedCaseWithAiInsight() throws Exception {
        CaseRecord withInsight = new CaseRecord(
                "2026-08-03_000725", "000725", "京东方A", LocalDate.of(2026, 8, 3), "B1",
                "回踩 60 日线 + 地量", List.of("缩量回踩"), LocalDateTime.of(2026, 8, 30, 10, 0),
                new CaseRecord.CaseWindow(60, 30),
                sampleRecord().features(), sampleRecord().verify(),
                new CaseRecord.CaseAiInsight("缩量回踩黄线获支撑，教科书式 B1",
                        List.of("缩量回踩", "黄线支撑"), 0.9, false));
        when(appService.generateInsight(anyString(), anyString())).thenReturn(withInsight);
        mvc("trading").perform(post("/api/v1/trading/cases/2026-08-03_000725/insight")
                        .header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiInsight.summary").value(org.hamcrest.Matchers.containsString("教科书式 B1")))
                .andExpect(jsonPath("$.aiInsight.confidence").value(0.9));
    }

    @Test
    void insight_llmFailure_returns400WithHumanMessage() throws Exception {
        when(appService.generateInsight(anyString(), anyString()))
                .thenThrow(new TradingException("AI 理解生成失败，请稍后重试：summary 为空"));
        mvc("trading").perform(post("/api/v1/trading/cases/2026-08-03_000725/insight")
                        .header("X-User-Id", "adai"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("AI 理解生成失败")));
    }

    // ── 2026-08-31 批量导入 ──

    @Test
    void importCases_success_returnsResults() throws Exception {
        when(appService.importCases(anyString(), anyString())).thenReturn(java.util.List.of(
                new TradingCaseAppService.CaseImportResult("华纳药厂", "600027",
                        LocalDate.of(2026, 3, 5), "ok", null, null),
                new TradingCaseAppService.CaseImportResult("航天发展", null, null,
                        "skipped", "笔记缺日期（跳过）", null)));
        mvc("trading").perform(post("/api/v1/trading/cases/import")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"## 华纳药厂\\n- 2026-03-05\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ok"))
                .andExpect(jsonPath("$[0].symbol").value("600027"))
                .andExpect(jsonPath("$[1].status").value("skipped"));
    }

    @Test
    void importCases_withoutPlugin_403() throws Exception {
        mvc().perform(post("/api/v1/trading/cases/import")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"## 华纳药厂\"}"))
                .andExpect(status().isForbidden());
    }

    // ── 环 4：判定当下（match）──

    @Test
    void match_withoutPlugin_returns403() throws Exception {
        mvc().perform(post("/api/v1/trading/cases/match")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void match_success_returnsSimilarCases() throws Exception {
        TradingCaseAppService.MatchItem item = new TradingCaseAppService.MatchItem(
                "2026-08-03_000725", "000725", "京东方A", LocalDate.of(2026, 8, 3),
                "B1", 92.5, 18.2, "缩量回踩黄线获支撑");
        when(appService.match(anyString(), anyString(), any()))
                .thenReturn(new TradingCaseAppService.MatchResponse("000725", List.of(item), null));
        mvc("trading").perform(post("/api/v1/trading/cases/match")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\",\"date\":\"2026-08-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("000725"))
                .andExpect(jsonPath("$.matches[0].similarityPercent").value(92.5))
                .andExpect(jsonPath("$.matches[0].caseId").value("2026-08-03_000725"));
    }

    @Test
    void match_emptyLibrary_returnsEmptyMatches() throws Exception {
        when(appService.match(anyString(), anyString(), any()))
                .thenReturn(new TradingCaseAppService.MatchResponse("000725", List.of(), null));
        mvc("trading").perform(post("/api/v1/trading/cases/match")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000725\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    void match_invalidSymbol_returns400() throws Exception {
        mvc("trading").perform(post("/api/v1/trading/cases/match")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── 日期宽容解析（2026-08-30 用户反馈 400：date=20260826）──

    @Test
    void match_basicDateFormat_acceptsYyyyMMdd() throws Exception {
        java.util.concurrent.atomic.AtomicReference<LocalDate> received = new java.util.concurrent.atomic.AtomicReference<>();
        when(appService.match(anyString(), anyString(), any())).thenAnswer(inv -> {
            received.set(inv.getArgument(2));
            return new TradingCaseAppService.MatchResponse("000831", List.of(), null);
        });
        mvc("trading").perform(post("/api/v1/trading/cases/match")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000831\",\"date\":\"20260826\"}"))
                .andExpect(status().isOk());
        assertEquals(LocalDate.of(2026, 8, 26), received.get(),
                "yyyyMMdd 应被解析为 LocalDate 2026-08-26");
    }

    @Test
    void match_isoDateFormat_acceptsYyyyMmDd() throws Exception {
        when(appService.match(anyString(), anyString(), any())).thenReturn(
                new TradingCaseAppService.MatchResponse("000831", List.of(), null));
        mvc("trading").perform(post("/api/v1/trading/cases/match")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000831\",\"date\":\"2026-08-26\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void match_invalidDateFormat_returns400WithHumanMessage() throws Exception {
        mvc("trading").perform(post("/api/v1/trading/cases/match")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"000831\",\"date\":\"2026-8-26\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("日期格式不正确")));
    }
}
