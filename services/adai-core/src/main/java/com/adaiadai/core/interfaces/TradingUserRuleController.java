package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingUserRuleService;
import com.adaiadai.core.domain.trading.UserRule;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TradingUserRuleController — 规则集三态端点（R-06 · blueprint §三 · design-final §5，rules 批 2026-10-06）。
 *
 * <pre>
 * GET    /api/v1/trading/rules/user        → 规则集全列表（候选 / 已认 / 自定义 三态分组）
 * POST   /api/v1/trading/rules/candidates  → 从数据里照一遍候选（每条带据）并返回全列表
 * POST   /api/v1/trading/rules/{id}/accept → 认下（body 可带 text = 认下时改）
 * PUT    /api/v1/trading/rules/{id}        → 改文本（已认 / 自定义）
 * DELETE /api/v1/trading/rules/{id}        → 弃掉一条（墓碑：下次生成不复活；幂等）
 * POST   /api/v1/trading/rules/custom      → 自己写一条（body {text, params?}）
 * </pre>
 *
 * <p>与既有 {@code GET/PUT /trading/rules}（参数化阈值，rules.yaml）<b>并存不冲突</b>——
 * 那是「你设的参数」，这是「你的规则条文」（设计 §4.1：rules.yaml 不变）。
 *
 * <p>独立 Controller（与 {@link TradingRoundController} 同模式）；插件门控 403 人话；
 * 参数不合法 / 找不到 / 状态不对 400 人话（Service 抛 {@link IllegalArgumentException}）。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingUserRuleController {

    private final TradingUserRuleService ruleService;
    private final PluginService pluginService;

    public TradingUserRuleController(TradingUserRuleService ruleService, PluginService pluginService) {
        this.ruleService = ruleService;
        this.pluginService = pluginService;
    }

    /** 规则集全列表（三态分组；前端一次拿全）。 */
    @GetMapping("/rules/user")
    public ResponseEntity<?> list(@RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(view(ruleService.list(userId)));
    }

    /** 从数据里照一遍候选（描述性统计 → 候选规则，每条带据）；返回刷新后的全列表。 */
    @PostMapping("/rules/candidates")
    public ResponseEntity<?> generate(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        return ResponseEntity.ok(view(ruleService.generateCandidates(userId)));
    }

    /** 认下一条候选（body 可带 text = 认下时改：「你勾选 / 改」都算认下）。 */
    @PostMapping("/rules/{id}/accept")
    public ResponseEntity<?> accept(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        String text = body == null ? null : str(body.get("text"));
        try {
            return ResponseEntity.ok(rule(ruleService.accept(userId, id, text)));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** 改文本（已认 / 自定义；候选要先认下）。 */
    @PutMapping("/rules/{id}")
    public ResponseEntity<?> edit(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(rule(ruleService.edit(userId, id, body == null ? null : str(body.get("text")))));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** 弃掉一条（墓碑——下次生成不复活；幂等：弃不存在的也算成功）。 */
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<?> dismiss(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable("id") String id) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        ruleService.dismiss(userId, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("id", id);
        return ResponseEntity.ok(m);
    }

    /** 自己写一条（三态之自定义——R-06 三来源之「自建」）。 */
    @SuppressWarnings("unchecked")
    @PostMapping("/rules/custom")
    public ResponseEntity<?> createCustom(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<?> denied = requirePlugin(userId);
        if (denied != null) return denied;
        String text = body == null ? null : str(body.get("text"));
        Map<String, Object> params = body != null && body.get("params") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : Map.of();
        try {
            return ResponseEntity.ok(rule(ruleService.createCustom(userId, text, params)));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    // ── 门控与形状 ──

    private ResponseEntity<?> requirePlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        return null;
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }

    private static Map<String, Object> view(TradingUserRuleService.RuleListView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", v.total());
        m.put("candidates", v.candidates().stream().map(TradingUserRuleController::rule).toList());
        m.put("accepted", v.accepted().stream().map(TradingUserRuleController::rule).toList());
        m.put("custom", v.custom().stream().map(TradingUserRuleController::rule).toList());
        return m;
    }

    private static Map<String, Object> rule(UserRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("state", r.state().name());
        m.put("text", r.text());
        m.put("source", r.source().name());
        m.put("params", r.params());
        m.put("evidence", evidence(r.evidence()));
        m.put("createdAt", r.createdAt());
        m.put("updatedAt", r.updatedAt());
        return m;
    }

    private static Map<String, Object> evidence(UserRule.Evidence e) {
        if (e == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("how", e.how());
        m.put("facts", e.facts());
        m.put("roundIds", e.roundIds());
        m.put("dates", e.dates());
        return m;
    }
}
