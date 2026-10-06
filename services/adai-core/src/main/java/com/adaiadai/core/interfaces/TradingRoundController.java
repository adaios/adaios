package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingRoundService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * GET  /api/v1/trading/rounds?symbol=600206&amp;limit=50 → {total, rounds:[{…}]}
 * POST /api/v1/trading/rounds/boundaries  {symbol, anchorBuyId, mode, note} → 人工边界（cut/merge/auto）
 * PUT  /api/v1/trading/rounds/{id}        {note}                         → 给某笔加 / 清备注
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
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        TradingRoundService.RoundsView view = roundService.rounds(userId, symbol, limit);
        List<Map<String, Object>> rows = view.rounds().stream().map(TradingRoundController::row).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", view.total());
        body.put("rounds", rows);
        return ResponseEntity.ok(body);
    }

    /**
     * 设 / 改 / 撤销一条人工边界（人工 &gt; 自动，design-final §2.3/§7）：
     * cut=从锚定那笔买入开始算新的一笔 · merge=与上一笔合并 · auto=撤销人工切分（自动让位回自动口径）。
     * note 缺省 = 保留旧备注；note 传空串 = 清除备注。
     */
    @PostMapping("/rounds/boundaries")
    public ResponseEntity<?> setBoundary(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        String symbol = str(body.get("symbol"));
        String anchorBuyId = str(body.get("anchorBuyId"));
        String mode = str(body.get("mode"));
        if (symbol == null || anchorBuyId == null) {
            return badRequest("symbol 与 anchorBuyId 必填（锚定哪只票的哪笔买入流水）");
        }
        if (mode == null) {
            return badRequest("mode 必填：cut（从这笔算新的一笔）/ merge（与上一笔合并）/ auto（撤销）");
        }
        String note = body.get("note") instanceof String s ? s : null;
        try {
            TradingRoundService.BoundaryAck ack = roundService.setBoundary(userId, symbol, anchorBuyId, mode, note);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ok");
            m.put("symbol", ack.symbol());
            m.put("anchorBuyId", ack.anchorBuyId());
            m.put("anchorDate", ack.anchorDate());
            m.put("mode", ack.mode());
            m.put("note", ack.note());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** 给某笔加 / 清备注（id = {代码}_{开始日}，如 600206_2026-08-05；备注锚定该笔首笔买入，切分动作保留）。 */
    @PutMapping("/rounds/{id}")
    public ResponseEntity<?> updateRound(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("id") String roundId,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        if (!body.containsKey("note")) {
            return badRequest("note 必填（清空备注传空串）");
        }
        String note = body.get("note") instanceof String s ? s : null;
        try {
            roundService.setRoundNote(userId, roundId, note);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ok");
            m.put("id", roundId);
            m.put("note", note == null || note.isBlank() ? null : note);
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** 插件门控（403 人话）；通过 → null。 */
    private ResponseEntity<?> requirePlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        return null;
    }

    /** 400 人话（参数不合法；与 TradingController 同口径）。 */
    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /** 宽松取字符串（非 String / 空白 → null）。 */
    private static String str(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    /** 一轮的对外形状（LocalDate → 字符串，与其他 DTO 口径一致；null 一律保持 null，不编造 0）。 */
    private static Map<String, Object> row(TradingRoundService.TradeRound r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
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
        m.put("unresolved", r.unresolved());   // true = 只有卖出、没有建仓流水（成本/盈亏判不了）
        m.put("reason", r.reason());
        TradingRoundService.RoundBoundaryView b = r.boundary();
        if (b != null) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("symbol", b.symbol());
            bm.put("anchorDate", b.anchorDate());
            bm.put("anchorBuyId", b.anchorBuyId());
            bm.put("source", b.source());
            bm.put("mode", b.mode());
            bm.put("note", b.note());
            m.put("boundary", bm);
        }
        return m;
    }
}
