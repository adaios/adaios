package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.AdviceOutcomeService;
import com.adaiadai.core.application.TradingEvidenceService;
import com.adaiadai.core.kernel.plugin.PluginService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingEvidenceControllerTest — 铁证只读出口（RFC 20260922 A 批）。
 * 覆盖：插件门控 403 · dimension 白名单 400 · 规则原文命中/未命中（未命中 404，**不编造**）。
 */
class TradingEvidenceControllerTest {

    private TradingEvidenceController controller(TradingEvidenceService svc, boolean pluginOn) {
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.hasPlugin(anyString(), anyString())).thenReturn(pluginOn);
        return new TradingEvidenceController(svc, mock(AdviceOutcomeService.class), pluginService);
    }

    /** 回填端点：门控 + 返回本次写入条数。 */
    @Test
    void backfill_requiresPlugin_andReturnsWrittenCount() {
        AdviceOutcomeService outcome = mock(AdviceOutcomeService.class);
        when(outcome.backfill(anyString(), any())).thenReturn(2);
        PluginService plugins = mock(PluginService.class);
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(true);

        ResponseEntity<?> resp = new TradingEvidenceController(
                mock(TradingEvidenceService.class), outcome, plugins).backfill("adai");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("2"), String.valueOf(resp.getBody()));
    }

    /** 回填端点同样受插件门控。 */
    @Test
    void backfill_withoutTradingPlugin_403() {
        ResponseEntity<?> resp = controller(mock(TradingEvidenceService.class), false).backfill("adai");
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    private TradingEvidenceService serviceReturning(TradingEvidenceService.HistoryStats stats) {
        TradingEvidenceService svc = mock(TradingEvidenceService.class);
        when(svc.historyStats(anyString(), any())).thenReturn(stats);
        return svc;
    }

    private static TradingEvidenceService.HistoryStats stats(boolean anySufficient) {
        return new TradingEvidenceService.HistoryStats(
                TradingEvidenceService.Dimension.HOLD_DAYS,
                List.of(new TradingEvidenceService.HistoryBucket("≤1 天", 6, 5, 5.0 / 6, 2.1, 1.0, true)),
                6, anySufficient, anySufficient ? "按「持仓时长」看你自己的 6 个回合" : "样本还不够");
    }

    @Test
    void history_withoutTradingPlugin_403() {
        ResponseEntity<?> resp = controller(serviceReturning(stats(true)), false)
                .history("adai", "HOLD_DAYS");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("trading 插件未启用"));
    }

    @Test
    void history_badDimension_400() {
        ResponseEntity<?> resp = controller(serviceReturning(stats(true)), true)
                .history("adai", "NOT_A_DIMENSION");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("HOLD_DAYS / PNL_BUCKET / VERDICT"));
    }

    @Test
    void history_ok_lowercaseDimensionAccepted() {
        TradingEvidenceService svc = serviceReturning(stats(true));
        ResponseEntity<?> resp = controller(svc, true).history("adai", "hold_days");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        verify(svc).historyStats("adai", TradingEvidenceService.Dimension.HOLD_DAYS);
    }

    @Test
    void rule_found_returnsVerbatim() {
        TradingEvidenceService svc = mock(TradingEvidenceService.class);
        when(svc.ruleTextOf("R66")).thenReturn(Optional.of(
                new TradingEvidenceService.RuleText(66, "只输一根K线", "核心理念：只输一根K线。")));

        ResponseEntity<?> resp = controller(svc, true).rule("R66");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(new TradingEvidenceService.RuleText(66, "只输一根K线", "核心理念：只输一根K线。"),
                resp.getBody());
    }

    @Test
    void rule_missing_404_andSaysItWontFabricate() {
        TradingEvidenceService svc = mock(TradingEvidenceService.class);
        when(svc.ruleTextOf("R999")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller(svc, true).rule("R999");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("不会替你编一条出来"));
    }

    /** 规则原文是公共知识 → 不做插件门控（无插件的用户/运维也能核对原文）。 */
    @Test
    void rule_doesNotRequirePlugin() {
        TradingEvidenceService svc = mock(TradingEvidenceService.class);
        when(svc.ruleTextOf("R53")).thenReturn(Optional.of(
                new TradingEvidenceService.RuleText(53, "没涨=错", "市场没证明你对。")));

        ResponseEntity<?> resp = controller(svc, false).rule("R53");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody() instanceof TradingEvidenceService.RuleText);
        // 显式提醒：这里**没有**走门控（与 history 不同）
        assertTrue(!(resp.getBody() instanceof Map));
    }
}
