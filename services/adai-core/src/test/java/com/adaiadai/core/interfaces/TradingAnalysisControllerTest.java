package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAnalysisService;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingAnalysisControllerTest — 三粒度分析端点（analytics 批，2026-10-06 · design §5）。
 * 覆盖：插件门控 403 · scope 分派与参数校验 400 · 视图序列化（LocalDate → 字符串、无规则对照）·
 * service 抛出的人话 400。
 */
class TradingAnalysisControllerTest {

    private TradingAnalysisController controller(TradingAnalysisService svc, boolean pluginOn) {
        PluginService plugins = mock(PluginService.class);
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(pluginOn);
        return new TradingAnalysisController(svc, plugins);
    }

    private static TradingAnalysisService.AnalysisView globalView() {
        TradingAnalysisService.Fact net = new TradingAnalysisService.Fact("capital-net", "净投入",
                new BigDecimal("50000.00"), "元",
                new TradingAnalysisService.Trace(List.of(), List.of(), "从资金流水推出"));
        TradingAnalysisService.Fact brief = new TradingAnalysisService.Fact("round-list", "逐笔", null, null,
                new TradingAnalysisService.Trace(List.of("600206_2026-08-05"), List.of("2026-08-05", "2026-08-12"), "点进每一笔"));
        TradingAnalysisService.RoundBrief rb = new TradingAnalysisService.RoundBrief(
                "600206_2026-08-05", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 12),
                new BigDecimal("1000"), new BigDecimal("10.0"), 5, false, null);
        TradingAnalysisService.Fact listFact = new TradingAnalysisService.Fact("briefs", "逐笔", List.of(rb), null,
                new TradingAnalysisService.Trace(List.of(), List.of(), null));
        TradingAnalysisService.Contrast contrast = new TradingAnalysisService.Contrast(
                false, "我还没有你的规则，判不了守没守", List.of());
        TradingAnalysisService.Summary summary = new TradingAnalysisService.Summary(
                "共 1 笔：1 胜 0 负", "我还没有你的规则，判不了守没守", null);
        return new TradingAnalysisService.AnalysisView("global", "全局",
                List.of(net, brief, listFact), contrast, summary);
    }

    @Test
    void withoutPlugin_403() {
        ResponseEntity<?> resp = controller(mock(TradingAnalysisService.class), false)
                .analyze("adai", "global", null, null);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("trading 插件未启用"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void global_ok_viewShape() {
        TradingAnalysisService svc = mock(TradingAnalysisService.class);
        when(svc.global(anyString())).thenReturn(globalView());

        ResponseEntity<?> resp = controller(svc, true).analyze("adai", "global", null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("global", body.get("scope"));
        assertEquals("全局", body.get("label"));

        List<Map<String, Object>> facts = (List<Map<String, Object>>) body.get("description");
        Map<String, Object> f0 = facts.get(0);
        assertEquals("capital-net", f0.get("key"));
        assertEquals(new BigDecimal("50000.00"), f0.get("value"));
        Map<String, Object> trace = (Map<String, Object>) f0.get("trace");
        assertTrue(String.valueOf(trace.get("note")).contains("资金流水"));

        Map<String, Object> contrast = (Map<String, Object>) body.get("contrast");
        assertEquals(Boolean.FALSE, contrast.get("hasRules"));
        assertTrue(String.valueOf(contrast.get("reason")).contains("判不了"));

        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        assertNull(summary.get("question"), "无规则 → 提问为 null");

        // RoundBrief 里的 LocalDate 变成字符串（与其他 DTO 口径一致）
        Map<String, Object> lf = facts.get(2);
        List<Map<String, Object>> briefs = (List<Map<String, Object>>) lf.get("value");
        assertEquals("2026-08-05", briefs.get(0).get("start"));
        assertEquals("2026-08-12", briefs.get(0).get("end"));
    }

    @Test
    void symbol_missingParam_400() {
        ResponseEntity<?> resp = controller(mock(TradingAnalysisService.class), true)
                .analyze("adai", "symbol", null, null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("symbol 必填"));
    }

    @Test
    void symbol_dispatchesWithParam() {
        TradingAnalysisService svc = mock(TradingAnalysisService.class);
        when(svc.symbol(anyString(), anyString())).thenReturn(globalView());

        ResponseEntity<?> resp = controller(svc, true).analyze("adai", "symbol", "600206", null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(svc).symbol("adai", "600206");
    }

    @Test
    void round_missingParam_400() {
        ResponseEntity<?> resp = controller(mock(TradingAnalysisService.class), true)
                .analyze("adai", "round", null, null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("id 必填"));
    }

    @Test
    void unknownScope_400() {
        ResponseEntity<?> resp = controller(mock(TradingAnalysisService.class), true)
                .analyze("adai", "weekly", null, null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("scope 只能是"));
    }

    @Test
    void round_unknownHumanReadable400() {
        TradingAnalysisService svc = mock(TradingAnalysisService.class);
        when(svc.round(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("找不到该笔：600206_2026-08-05（可能流水已变、边界已重切）"));

        ResponseEntity<?> resp = controller(svc, true).analyze("adai", "round", null, "600206_2026-08-05");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("找不到该笔"));
    }
}
