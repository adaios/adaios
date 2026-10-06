package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingUserRuleService;
import com.adaiadai.core.domain.trading.UserRule;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingUserRuleControllerTest — 规则集三态端点（rules 批，2026-10-06 · R-06 · design §5）。
 * 覆盖：插件门控 403 · 列表三态分组与依据子结构 · 生成/认下/改/弃/自写 200/400。
 */
class TradingUserRuleControllerTest {

    private TradingUserRuleController controller(TradingUserRuleService svc, boolean pluginOn) {
        PluginService plugins = mock(PluginService.class);
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(pluginOn);
        return new TradingUserRuleController(svc, plugins);
    }

    private static UserRule candidate() {
        return UserRule.candidate("cand-stoploss", "止损：亏到 -3% 就走（你 6 次亏损里 5 次不超过 3%）",
                Map.of("pct", -3.0),
                new UserRule.Evidence("从你 6 笔亏损的亏损幅度统计", List.of("亏损 6 笔"),
                        List.of("600206_2026-08-05"), List.of("2026-08-05")),
                "2026-10-06T20:00:00");
    }

    private static TradingUserRuleService.RuleListView view() {
        return new TradingUserRuleService.RuleListView(2,
                List.of(candidate()),
                List.of(UserRule.candidate("cand-x", "已认的", Map.of(), null, "2026-10-06T20:10:00")
                        .accepted(null, "2026-10-06T20:10:00")),
                List.of(UserRule.custom("usr-abc", "不追新股", Map.of(), "2026-10-06T21:00:00")));
    }

    @Test
    void withoutPlugin_403() {
        ResponseEntity<?> list = controller(mock(TradingUserRuleService.class), false).list("adai");
        assertEquals(HttpStatus.FORBIDDEN, list.getStatusCode());
        assertTrue(String.valueOf(list.getBody()).contains("trading 插件未启用"));

        ResponseEntity<?> gen = controller(mock(TradingUserRuleService.class), false).generate("adai", null);
        assertEquals(HttpStatus.FORBIDDEN, gen.getStatusCode());
    }

    @SuppressWarnings("unchecked")
    @Test
    void list_ok_threeStatesGroupedWithEvidence() {
        TradingUserRuleService svc = mock(TradingUserRuleService.class);
        when(svc.list(anyString())).thenReturn(view());

        ResponseEntity<?> resp = controller(svc, true).list("adai");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(2, body.get("total"));

        Map<String, Object> c = ((List<Map<String, Object>>) body.get("candidates")).get(0);
        assertEquals("CANDIDATE", c.get("state"));
        assertEquals("DATA", c.get("source"));
        Map<String, Object> ev = (Map<String, Object>) c.get("evidence");
        assertEquals("从你 6 笔亏损的亏损幅度统计", ev.get("how"));
        assertEquals(List.of("600206_2026-08-05"), ev.get("roundIds"), "每条带据 · 可回溯");
        assertEquals(-3.0, ((Number) ((Map<String, Object>) c.get("params")).get("pct")).doubleValue());

        Map<String, Object> a = ((List<Map<String, Object>>) body.get("accepted")).get(0);
        assertEquals("ACCEPTED", a.get("state"));

        Map<String, Object> u = ((List<Map<String, Object>>) body.get("custom")).get(0);
        assertEquals("CUSTOM", u.get("state"));
        assertEquals("USER", u.get("source"));
        assertNull(u.get("evidence"), "自定义没有依据");
    }

    @Test
    void generate_ok_verifies() {
        TradingUserRuleService svc = mock(TradingUserRuleService.class);
        when(svc.generateCandidates(anyString())).thenReturn(view());

        ResponseEntity<?> resp = controller(svc, true).generate("adai", null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(svc).generateCandidates("adai");
    }

    @Test
    void accept_ok_withAndWithoutText() {
        TradingUserRuleService svc = mock(TradingUserRuleService.class);
        when(svc.accept(anyString(), anyString(), any())).thenReturn(candidate());

        ResponseEntity<?> noText = controller(svc, true).accept("adai", "cand-stoploss", null);
        assertEquals(HttpStatus.OK, noText.getStatusCode());
        verify(svc).accept("adai", "cand-stoploss", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", "我改过的止损线");
        ResponseEntity<?> withText = controller(svc, true).accept("adai", "cand-stoploss", body);
        assertEquals(HttpStatus.OK, withText.getStatusCode());
        verify(svc).accept("adai", "cand-stoploss", "我改过的止损线");
    }

    @Test
    void accept_unknown_400() {
        TradingUserRuleService svc = mock(TradingUserRuleService.class);
        when(svc.accept(anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("找不到这条规则：cand-不存在（可能已弃掉）"));

        ResponseEntity<?> resp = controller(svc, true).accept("adai", "cand-不存在", null);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("找不到这条规则"));
    }

    @Test
    void edit_missingText_400() {
        TradingUserRuleService svc = mock(TradingUserRuleService.class);
        when(svc.edit(anyString(), anyString(), isNull()))
                .thenThrow(new IllegalArgumentException("text 必填（改成什么）"));

        ResponseEntity<?> resp = controller(svc, true).edit("adai", "usr-abc", null);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("text 必填"));
    }

    @Test
    void dismiss_ok() {
        TradingUserRuleService svc = mock(TradingUserRuleService.class);
        ResponseEntity<?> resp = controller(svc, true).dismiss("adai", "cand-stoploss");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("ok"));
        verify(svc).dismiss("adai", "cand-stoploss");
    }

    @Test
    void custom_ok_and_blank400() {
        TradingUserRuleService svc = mock(TradingUserRuleService.class);
        when(svc.createCustom(anyString(), anyString(), any()))
                .thenReturn(UserRule.custom("usr-new", "不追新股", Map.of(), "2026-10-06T22:00:00"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", "不追新股");
        ResponseEntity<?> ok = controller(svc, true).createCustom("adai", body);
        assertEquals(HttpStatus.OK, ok.getStatusCode());

        TradingUserRuleService svc2 = mock(TradingUserRuleService.class);
        doThrow(new IllegalArgumentException("text 必填（写一条你自己的规则）"))
                .when(svc2).createCustom(anyString(), any(), any());
        ResponseEntity<?> bad = controller(svc2, true).createCustom("adai", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertTrue(String.valueOf(bad.getBody()).contains("text 必填"));
    }
}
