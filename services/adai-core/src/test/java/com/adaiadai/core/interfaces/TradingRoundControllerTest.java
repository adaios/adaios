package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingRoundService;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingRoundControllerTest — 轮次出口 + 人工边界端点（rounds 批，2026-10-06）。
 * 覆盖：插件门控 403 · row 形状（id/unresolved/reason/boundary）· 边界设/改/撤销 200/400 · 备注 200/400。
 */
class TradingRoundControllerTest {

    private TradingRoundController controller(TradingRoundService svc, boolean pluginOn) {
        PluginService plugins = mock(PluginService.class);
        when(plugins.hasPlugin(anyString(), anyString())).thenReturn(pluginOn);
        return new TradingRoundController(svc, plugins);
    }

    private static TradingRoundService.TradeRound round(String id, boolean unresolved) {
        return new TradingRoundService.TradeRound(
                id, "600000", "600000名",
                LocalDate.of(2026, 1, 5), unresolved ? null : LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 7),
                unresolved ? 0 : 2, unresolved ? 1 : 3, unresolved ? 0 : 1, 1,
                unresolved ? null : new BigDecimal("1000.00"), unresolved ? null : new BigDecimal("100.00"),
                unresolved ? null : new BigDecimal("10.00"),
                unresolved ? null : new BigDecimal("12.00"), unresolved ? null : LocalDate.of(2026, 1, 6),
                unresolved ? null : new BigDecimal("-1.00"),
                0, 0, unresolved ? null : new BigDecimal("-2.00"),
                List.of(), unresolved, unresolved ? "只有卖出记录……如实：判不了" : null,
                new TradingRoundService.RoundBoundaryView("600000", "2026-01-05",
                        unresolved ? null : "t_600000_2026-01-05_BUY", "auto", null, null));
    }

    @Test
    void rounds_withoutPlugin_403() {
        ResponseEntity<?> resp = controller(mock(TradingRoundService.class), false).rounds("adai", null, 50);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("trading 插件未启用"));
    }

    /** row 形状：id / unresolved / reason / boundary 都对外（前端列表与备注的稳定引用）。 */
    @SuppressWarnings("unchecked")
    @Test
    void rounds_ok_exposesIdUnresolvedAndBoundary() {
        TradingRoundService svc = mock(TradingRoundService.class);
        when(svc.rounds(anyString(), any(), anyInt())).thenReturn(
                new TradingRoundService.RoundsView(2,
                        List.of(round("600000_2026-01-07", false), round("600001_2026-01-05", true))));

        ResponseEntity<?> resp = controller(svc, true).rounds("adai", null, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(2, body.get("total"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rounds");

        Map<String, Object> ok = rows.get(0);
        assertEquals("600000_2026-01-07", ok.get("id"));
        assertEquals(Boolean.FALSE, ok.get("unresolved"));
        assertNull(ok.get("reason"));
        Map<String, Object> b = (Map<String, Object>) ok.get("boundary");
        assertEquals("auto", b.get("source"));
        assertEquals("600000", b.get("symbol"));
        assertEquals("t_600000_2026-01-05_BUY", b.get("anchorBuyId"));

        Map<String, Object> un = rows.get(1);
        assertEquals(Boolean.TRUE, un.get("unresolved"));
        assertTrue(String.valueOf(un.get("reason")).contains("判不了"));
        assertNull(un.get("pnl"), "判不了不编造 0");
        assertNull(un.get("pnlPct"));
    }

    @Test
    void setBoundary_ok_returnsAck() {
        TradingRoundService svc = mock(TradingRoundService.class);
        when(svc.setBoundary(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new TradingRoundService.BoundaryAck("600000", "t_600000_2026-01-08_BUY",
                        "2026-01-08", "cut", null));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", "600000");
        body.put("anchorBuyId", "t_600000_2026-01-08_BUY");
        body.put("mode", "cut");

        ResponseEntity<?> resp = controller(svc, true).setBoundary("adai", body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("ok"));
        verify(svc).setBoundary("adai", "600000", "t_600000_2026-01-08_BUY", "cut", null);
    }

    @Test
    void setBoundary_withoutPlugin_403() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", "600000");
        body.put("anchorBuyId", "buy1");
        body.put("mode", "cut");
        ResponseEntity<?> resp = controller(mock(TradingRoundService.class), false).setBoundary("adai", body);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void setBoundary_missingMode_400() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", "600000");
        body.put("anchorBuyId", "buy1");
        ResponseEntity<?> resp = controller(mock(TradingRoundService.class), true).setBoundary("adai", body);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("mode 必填"));
    }

    @Test
    void setBoundary_badAnchor_400() {
        TradingRoundService svc = mock(TradingRoundService.class);
        when(svc.setBoundary(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("锚定的买入不存在：anchorBuyId 必须是 600000 的真实买入流水 id"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", "600000");
        body.put("anchorBuyId", "t_不存在");
        body.put("mode", "cut");

        ResponseEntity<?> resp = controller(svc, true).setBoundary("adai", body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("锚定的买入不存在"));
    }

    @Test
    void updateRound_note_ok() {
        TradingRoundService svc = mock(TradingRoundService.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", "试探仓");

        ResponseEntity<?> resp = controller(svc, true).updateRound("adai", "600000_2026-01-05", body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("试探仓"));
        verify(svc).setRoundNote("adai", "600000_2026-01-05", "试探仓");
    }

    @Test
    void updateRound_missingNoteKey_400() {
        ResponseEntity<?> resp = controller(mock(TradingRoundService.class), true)
                .updateRound("adai", "600000_2026-01-05", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("note 必填"));
    }

    /** 备注传空串 = 清除（响应 note 为 null，前端据此清空展示）。 */
    @SuppressWarnings("unchecked")
    @Test
    void updateRound_emptyNote_clears() {
        TradingRoundService svc = mock(TradingRoundService.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", "");

        ResponseEntity<?> resp = controller(svc, true).updateRound("adai", "600000_2026-01-05", body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(svc).setRoundNote("adai", "600000_2026-01-05", "");
        assertNull(((Map<String, Object>) resp.getBody()).get("note"));
    }

    @Test
    void updateRound_unknownRound_400() {
        TradingRoundService svc = mock(TradingRoundService.class);
        doThrow(new IllegalArgumentException("找不到该笔：600000_2026-01-05（可能流水已变、边界已重切）"))
                .when(svc).setRoundNote(anyString(), anyString(), any());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", "x");

        ResponseEntity<?> resp = controller(svc, true).updateRound("adai", "600000_2026-01-05", body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody()).contains("找不到该笔"));
    }
}
