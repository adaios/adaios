package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.AdviceOutcomeService;
import com.adaiadai.core.application.TradingEvidenceService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * TradingEvidenceController — 「四要素铁证」的只读出口（RFC `20260922-trading-decision-copilot` A 批，2026-09-22）。
 *
 * <p>为什么单独一个控制器：{@link TradingController} 的构造点在生产与 5+ 处测试里直接 {@code new}，
 * 为一个只读查询去动它得不偿失；本类**只读**、无状态、不碰任何写入路径。
 *
 * <p>两条出口对应铁证的两要素：
 * <ul>
 *   <li>{@code GET /trading/evidence/history?dimension=…} —— <b>① 本人历史操作统计</b>
 *       （持仓时长 / 盈亏区间 / 清仓判定；**样本 &lt; 5 的组标记 sufficient=false**，
 *       调用方必须直说「样本还不够」而不是拿巧合当规律）。</li>
 *   <li>{@code GET /trading/evidence/rule/{ruleRef}} —— <b>③ 规则依据原文</b>
 *       （逐字来自 {@code rules.md}；没有这条规则 → 404，**绝不编造**）。</li>
 * </ul>
 *
 * <p>需 trading 插件（与交易域其他端点同口径）；规则原文属公共知识、与用户数据无关，
 * 故 {@code /rule/**} 不做插件门控（也方便运维直接核对原文）。
 */
@RestController
@RequestMapping("/api/v1/trading/evidence")
public class TradingEvidenceController {

    private final TradingEvidenceService evidenceService;
    private final AdviceOutcomeService adviceOutcomeService;
    private final PluginService pluginService;

    public TradingEvidenceController(TradingEvidenceService evidenceService,
                                     AdviceOutcomeService adviceOutcomeService,
                                     PluginService pluginService) {
        this.evidenceService = evidenceService;
        this.adviceOutcomeService = adviceOutcomeService;
        this.pluginService = pluginService;
    }

    /**
     * 本人历史操作统计（铁证①）。
     *
     * @param dimension {@code HOLD_DAYS}（默认）/ {@code PNL_BUCKET} / {@code VERDICT}
     */
    @GetMapping("/history")
    public ResponseEntity<?> history(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false, defaultValue = "HOLD_DAYS") String dimension) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        TradingEvidenceService.Dimension dim;
        try {
            dim = TradingEvidenceService.Dimension.valueOf(dimension.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "dimension 只支持 HOLD_DAYS / PNL_BUCKET / VERDICT（收到：" + dimension + "）"));
        }
        return ResponseEntity.ok(evidenceService.historyStats(userId, dim));
    }

    /**
     * 规则原文（铁证③）——逐字引用，可核对。
     *
     * @param ruleRef 规则引用串，{@code "R66"} / {@code "r66"} / {@code "66"} 都认
     */
    @GetMapping("/rule/{ruleRef}")
    public ResponseEntity<?> rule(@PathVariable String ruleRef) {
        return evidenceService.ruleTextOf(ruleRef)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error",
                        "没有这条规则的原文（编号 " + ruleRef + "）——我不会替你编一条出来")));
    }

    /**
     * 回填建议结果（铁证④「可追责」，RFC 20260922 A 批 A3）。
     *
     * <p>把**已到期**（发出满 N 个交易日）的建议补上「后来怎么样了」：`priceThen` / `priceAfter` / `pct`
     * 与「用户有没有操作」。**幂等**（已回填的跳过）· **只记事实、不判对错** · 行情取不到就留待下次
     * （**不写半成品**）。N 由 {@code adai.trading.advice-outcome-days} 配置（默认 5 个交易日）。
     *
     * <p>返回本次真正写入的条数。前提是先有留痕（经 {@code POST /trading/advice} 产生）。
     */
    @PostMapping("/backfill")
    public ResponseEntity<?> backfill(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        ResponseEntity<?> denied = requireTradingPlugin(userId);
        if (denied != null) return denied;
        int written = adviceOutcomeService.backfill(userId, LocalDate.now());
        return ResponseEntity.ok(Map.of("written", written));
    }

    private ResponseEntity<?> requireTradingPlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        return null;
    }
}
