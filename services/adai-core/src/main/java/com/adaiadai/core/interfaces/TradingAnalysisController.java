package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAnalysisService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingAnalysisController — 「分析总结」三粒度端点（design-final §5 · R-05 · blueprint §八，2026-10-06）。
 *
 * <pre>
 * GET /api/v1/trading/analysis/global                    → 全局：我是个什么样的交易者
 * GET /api/v1/trading/analysis/symbol?symbol=600206      → 单标的：这只票做了几笔
 * GET /api/v1/trading/analysis/round?id=600206_2026-08-05 → 单笔：这一笔到底发生了什么
 * </pre>
 *
 * <p><b>契约三条</b>（design §5）：缺数据 → value=null + 说明（<b>不出 0</b>）；每个数字带
 * {@code trace}（可回溯到哪几笔 / 哪几天）；<b>只陈述、不评价、不建议</b>。描述与对照分开——
 * 没有规则的用户，{@code contrast.hasRules=false} 且明说「判不了守没守」，<b>不拿别人的规则替他判</b>。
 *
 * <p>独立 Controller（与 {@link TradingRoundController} 同模式，2 参构造）；插件门控 403 人话；
 * 参数不合法 400 人话（{@code scope} 不认识 / symbol 缺 / id 缺或形状不对 / 找不到该笔）。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingAnalysisController {

    private final TradingAnalysisService analysisService;
    private final PluginService pluginService;

    public TradingAnalysisController(TradingAnalysisService analysisService, PluginService pluginService) {
        this.analysisService = analysisService;
        this.pluginService = pluginService;
    }

    /** 三粒度统一入口（scope = global / symbol / round；symbol、id 按 scope 二选一必填）。 */
    @GetMapping("/analysis/{scope}")
    public ResponseEntity<?> analyze(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("scope") String scope,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "id", required = false) String id) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        if (!"global".equals(scope) && !"symbol".equals(scope) && !"round".equals(scope)) {
            return badRequest("scope 只能是 global（全局）/ symbol（单标的）/ round（单笔）");
        }
        if ("symbol".equals(scope) && (symbol == null || symbol.isBlank())) {
            return badRequest("symbol 必填（看哪只票，如 ?symbol=600206）");
        }
        if ("round".equals(scope) && (id == null || id.isBlank())) {
            return badRequest("id 必填（看哪一笔，如 ?id=600206_2026-08-05）");
        }
        try {
            TradingAnalysisService.AnalysisView view = switch (scope) {
                case "global" -> analysisService.global(userId);
                case "symbol" -> analysisService.symbol(userId, symbol);
                default -> analysisService.round(userId, id);
            };
            return ResponseEntity.ok(view(view));
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

    /** 400 人话（参数不合法；与 TradingRoundController 同口径）。 */
    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    // ── 视图 → JSON 形状（LocalDate → 字符串，与其他 DTO 口径一致）──

    private static Map<String, Object> view(TradingAnalysisService.AnalysisView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", v.scope());
        m.put("label", v.label());
        List<Map<String, Object>> facts = new ArrayList<>();
        for (TradingAnalysisService.Fact f : v.description()) facts.add(fact(f));
        m.put("description", facts);
        m.put("contrast", contrast(v.contrast()));
        m.put("summary", summary(v.summary()));
        return m;
    }

    private static Map<String, Object> fact(TradingAnalysisService.Fact f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", f.key());
        m.put("label", f.label());
        m.put("value", plain(f.value()));
        m.put("unit", f.unit());
        m.put("trace", trace(f.trace()));
        return m;
    }

    private static Map<String, Object> trace(TradingAnalysisService.Trace t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roundIds", t.roundIds());
        m.put("dates", t.dates());
        m.put("note", t.note());
        return m;
    }

    private static Map<String, Object> contrast(TradingAnalysisService.Contrast c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasRules", c.hasRules());
        m.put("reason", c.reason());
        List<Map<String, Object>> hits = new ArrayList<>();
        for (TradingAnalysisService.RuleCount h : c.ruleHits()) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("rule", h.rule());
            hm.put("text", h.text());
            hm.put("count", h.count());
            hm.put("roundIds", h.roundIds());
            hits.add(hm);
        }
        m.put("ruleHits", hits);
        return m;
    }

    private static Map<String, Object> summary(TradingAnalysisService.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fact", s.fact());
        m.put("contrast", s.contrast());
        m.put("question", s.question());
        return m;
    }

    /** 值原样直出；列表 / 视图 record 递归转换（唯一目的是把 LocalDate 变成字符串）。 */
    private static Object plain(Object v) {
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) out.add(plain(o));
            return out;
        }
        if (v instanceof TradingAnalysisService.RoundBrief b) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("start", date(b.start()));
            m.put("end", date(b.end()));
            m.put("pnl", b.pnl());
            m.put("pnlPct", b.pnlPct());
            m.put("holdDays", b.holdDays());
            m.put("unresolved", b.unresolved());
            m.put("reason", b.reason());
            return m;
        }
        if (v instanceof TradingAnalysisService.Bucket b) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", b.label());
            m.put("count", b.count());
            m.put("roundIds", b.roundIds());
            return m;
        }
        if (v instanceof TradingAnalysisService.PeriodBucket b) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", b.period());
            m.put("count", b.count());
            m.put("pnl", b.pnl());
            m.put("roundIds", b.roundIds());
            return m;
        }
        if (v instanceof TradingAnalysisService.SizeBucket b) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", b.label());
            m.put("count", b.count());
            m.put("avgPnlPct", b.avgPnlPct());
            m.put("roundIds", b.roundIds());
            return m;
        }
        return v;
    }

    private static String date(LocalDate d) {
        return d != null ? d.toString() : null;
    }
}
