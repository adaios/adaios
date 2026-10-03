package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingPlanService;
import com.adaiadai.core.domain.trading.TradingPlan;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingPlanController — 次日操作计划端点
 * （RFC 20261003-trading-plan-and-review-loop §二~四，2026-10-03）。
 *
 * <pre>
 * GET  /api/v1/trading/plans                  → {dates:[…]}
 * GET  /api/v1/trading/plans/{date}           → {date, note, items:[…]}（不存在 → 404 人话）
 * POST /api/v1/trading/plans/{date}           → body {lines:[「600206 跌破 45.5 清仓」…], note:"…"}
 * GET  /api/v1/trading/plans/{date}/review    → 收盘对账（计划 vs 实际 + 计划外成交）
 * </pre>
 *
 * <p>独立 Controller（与 {@link EquityCurveController} / {@link TradingRoundController} 同模式）。
 * <b>边界</b>：本端点**不生成计划、不给建议**——它只把用户说的话存下来、把对账事实列出来。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingPlanController {

    private final TradingPlanService planService;
    private final PluginService pluginService;

    public TradingPlanController(TradingPlanService planService, PluginService pluginService) {
        this.planService = planService;
        this.pluginService = pluginService;
    }

    /** 有计划的日期（倒序）。 */
    @GetMapping("/plans")
    public ResponseEntity<?> dates(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = gate(userId);
        if (denied != null) return denied;
        List<String> dates = new ArrayList<>();
        for (LocalDate d : planService.dates(userId)) dates.add(d.toString());
        return ResponseEntity.ok(Map.of("dates", dates));
    }

    /** 读某天的计划（无计划 → 404 人话，**不返回空壳假计划**）。 */
    @GetMapping("/plans/{date}")
    public ResponseEntity<?> get(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("date") String date) {
        ResponseEntity<?> denied = gate(userId);
        if (denied != null) return denied;
        LocalDate d;
        try {
            d = LocalDate.parse(date);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "日期格式应为 yyyy-MM-dd"));
        }
        return planService.find(userId, d)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(view(p)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "这天还没有写操作计划")));
    }

    /** 写某天的计划（一句话列表，覆盖写）。 */
    @PostMapping("/plans/{date}")
    public ResponseEntity<?> save(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("date") String date,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = gate(userId);
        if (denied != null) return denied;
        LocalDate d;
        try {
            d = LocalDate.parse(date);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "日期格式应为 yyyy-MM-dd"));
        }
        List<String> lines = new ArrayList<>();
        Object raw = body != null ? body.get("lines") : null;
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o != null) lines.add(String.valueOf(o));
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String one : s.split("\\n")) if (!one.isBlank()) lines.add(one);
        }
        if (lines.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "计划不能是空的——写一句就行，比如「600206 跌破 45.5 清仓」；"
                            + "当天不打算动手，就写「明天不动」"));
        }
        String note = body != null && body.get("note") != null ? String.valueOf(body.get("note")) : "";
        TradingPlan plan = planService.saveFromLines(userId, d, lines, note);
        return ResponseEntity.ok(view(plan));
    }

    /** 收盘对账（计划 vs 实际 + ⚠️ 计划外成交）。 */
    @GetMapping("/plans/{date}/review")
    public ResponseEntity<?> review(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("date") String date) {
        ResponseEntity<?> denied = gate(userId);
        if (denied != null) return denied;
        LocalDate d;
        try {
            d = LocalDate.parse(date);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "日期格式应为 yyyy-MM-dd"));
        }
        TradingPlanService.PlanReview r = planService.review(userId, d);
        List<Map<String, Object>> items = new ArrayList<>();
        for (TradingPlanService.ItemReview it : r.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", it.symbol());
            m.put("name", it.name());
            m.put("action", it.action());
            m.put("condition", it.condition());
            m.put("text", it.text());
            m.put("triggered", it.triggered());
            m.put("executed", it.executed());
            m.put("evidence", it.evidence());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", r.date().toString());
        out.put("hasPlan", r.hasPlan());
        out.put("items", items);
        out.put("unplanned", r.unplanned());
        out.put("triggeredCount", r.triggeredCount());
        out.put("executedCount", r.executedCount());
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> view(TradingPlan p) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TradingPlan.PlanItem it : p.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.id());
            m.put("action", it.action());
            m.put("symbol", it.symbol());
            m.put("name", it.name());
            m.put("condition", it.condition());
            m.put("condOp", it.condOp());
            m.put("condPrice", it.condPrice());
            m.put("quantity", it.quantity());
            m.put("text", it.text());
            m.put("done", it.done());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", p.date().toString());
        out.put("note", p.note());
        out.put("items", items);
        return out;
    }

    private ResponseEntity<?> gate(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        return null;
    }
}
