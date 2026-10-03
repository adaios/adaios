package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingRoundService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingRoundController — 「一轮完整交易」的识别与交易规则检查端点
 * （RFC 20261003-trading-plan-and-review-loop §五，2026-10-03）。
 *
 * <pre>
 * GET /api/v1/trading/rounds?symbol=600206&amp;limit=50 → {total, rounds:[{…}]}
 * </pre>
 *
 * <p>独立 Controller（与 {@link EquityCurveController} / {@code TradingEvidenceController} 同模式，
 * 不塞 15 参构造）；插件门控同口径（403 人话）。
 *
 * <p><b>边界</b>：**只陈述事实、不作建议**——输出是「峰值 +9.4%、最终 −25.9%、扛了 9 个交易日、
 * 命中 R55」，绝不是「你应该在 +9.4% 卖出」。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingRoundController {

    private final TradingRoundService roundService;
    private final PluginService pluginService;

    public TradingRoundController(TradingRoundService roundService, PluginService pluginService) {
        this.roundService = roundService;
        this.pluginService = pluginService;
    }

    /** 轮次列表（需 trading 插件）。{@code symbol} 可选（单只更快）；{@code limit} 默认 50。 */
    @GetMapping("/rounds")
    public ResponseEntity<?> rounds(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        TradingRoundService.RoundsView view = roundService.rounds(userId, symbol, limit);
        List<Map<String, Object>> rows = view.rounds().stream().map(TradingRoundController::row).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", view.total());
        body.put("rounds", rows);
        return ResponseEntity.ok(body);
    }

    /** 一轮的对外形状（LocalDate → 字符串，与其他 DTO 口径一致；null 一律保持 null，不编造 0）。 */
    private static Map<String, Object> row(TradingRoundService.TradeRound r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", r.symbol());
        m.put("name", r.name());
        m.put("start", r.start() != null ? r.start().toString() : null);
        m.put("lastBuy", r.lastBuy() != null ? r.lastBuy().toString() : null);
        m.put("end", r.end() != null ? r.end().toString() : null);
        m.put("open", r.end() == null);          // 未平仓（当前持仓）
        m.put("holdDays", r.holdDays());
        m.put("tradeDays", r.tradeDays());
        m.put("buyCount", r.buyCount());
        m.put("sellCount", r.sellCount());
        m.put("buyAmount", r.buyAmount());
        m.put("pnl", r.pnl());
        m.put("pnlPct", r.pnlPct());
        m.put("peakPct", r.peakPct());
        m.put("peakDate", r.peakDate() != null ? r.peakDate().toString() : null);
        m.put("troughPct", r.troughPct());
        m.put("addOnCount", r.addOnCount());
        m.put("addOnTrappedCount", r.addOnTrappedCount());
        m.put("d3Pct", r.d3Pct());
        m.put("hits", r.hits().stream().map(h -> {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("rule", h.rule());
            hm.put("text", h.text());
            hm.put("data", h.data());
            return hm;
        }).toList());
        return m;
    }
}
