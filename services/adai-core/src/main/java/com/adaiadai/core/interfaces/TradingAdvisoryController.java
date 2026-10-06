package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAdvisoryService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingAdvisoryController — 三环端点（design-final §5 · R-07 · 批6 advisory，2026-10-06）。
 *
 * <pre>
 * GET /api/v1/trading/advisory/buy?date=2026-10-07  → 买入环：对照你写的计划（date 缺省 = 今天）
 * GET /api/v1/trading/advisory/hold                  → 持仓环：用你的线说话（四要素闸门）
 * GET /api/v1/trading/advisory/sell                  → 卖出环：卖点评价（按你的尺子 + 卖飞了没）
 * </pre>
 *
 * <p><b>契约</b>（design §5 契约③）：响应只带 {@code statement + basis[] + ruleRef}（+ missing 时缺失项）——
 * <b>契约层不含建议字段</b>（验收 10）。缺数据 → value=null + 说明（不出 0，验收 5）；
 * 每条依据带 trace 可回溯（验收 4）。
 *
 * <p>独立 Controller（2 参构造）；插件门控 403 人话；ring 不认识 / date 形状不对 400 人话。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingAdvisoryController {

    private final TradingAdvisoryService advisoryService;
    private final PluginService pluginService;

    public TradingAdvisoryController(TradingAdvisoryService advisoryService, PluginService pluginService) {
        this.advisoryService = advisoryService;
        this.pluginService = pluginService;
    }

    /** 三环统一入口（ring = buy / hold / sell；date 仅买入环用，缺省 = 今天）。 */
    @GetMapping("/advisory/{ring}")
    public ResponseEntity<?> advisory(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("ring") String ring,
            @RequestParam(value = "date", required = false) String date) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        if (!TradingAdvisoryService.RING_BUY.equals(ring)
                && !TradingAdvisoryService.RING_HOLD.equals(ring)
                && !TradingAdvisoryService.RING_SELL.equals(ring)) {
            return badRequest("ring 只能是 buy（买入）/ hold（持仓）/ sell（卖出）");
        }
        LocalDate day = null;
        if (date != null && !date.isBlank()) {
            try {
                day = LocalDate.parse(date.trim());
            } catch (DateTimeParseException e) {
                return badRequest("date 应为 yyyy-MM-dd（你写的那一天的计划，如 ?date=2026-10-07）");
            }
        }
        TradingAdvisoryService.AdvisoryView view = switch (ring) {
            case TradingAdvisoryService.RING_BUY -> advisoryService.buy(userId, day);
            case TradingAdvisoryService.RING_HOLD -> advisoryService.hold(userId);
            default -> advisoryService.sell(userId);
        };
        return ResponseEntity.ok(view(view));
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    // ── 视图 → JSON 形状 ──

    private static Map<String, Object> view(TradingAdvisoryService.AdvisoryView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ring", v.ring());
        m.put("label", v.label());
        m.put("asOf", v.asOf());
        List<Map<String, Object>> items = new ArrayList<>();
        for (TradingAdvisoryService.AdvisoryItem i : v.items()) items.add(item(i));
        m.put("items", items);
        m.put("note", v.note());
        return m;
    }

    private static Map<String, Object> item(TradingAdvisoryService.AdvisoryItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", i.symbol());
        m.put("name", i.name());
        m.put("statement", i.statement());
        List<Map<String, Object>> basis = new ArrayList<>();
        for (TradingAdvisoryService.Basis b : i.basis()) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("key", b.key());
            bm.put("label", b.label());
            bm.put("value", b.value());
            bm.put("trace", b.trace());
            basis.add(bm);
        }
        m.put("basis", basis);
        m.put("ruleRef", i.ruleRef());
        m.put("missing", i.missing());
        return m;
    }
}
