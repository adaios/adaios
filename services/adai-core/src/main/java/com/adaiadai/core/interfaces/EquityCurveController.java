package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.EquityCurveService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * EquityCurveController — 资金曲线端点（2026-09-04 晚间自主批 IV，决策文档方案 A）。
 * <pre>
 * GET /api/v1/trading/equity-curve → {points:[{date,totalAssets,cash,marketValue,invested,netValue,drawdown}], skippedDays, startDate, endDate}
 * </pre>
 * 独立 Controller（不塞 TradingController 15 参构造）；插件门控同口径（403 人话）。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class EquityCurveController {

    private final EquityCurveService equityCurveService;
    private final PluginService pluginService;

    public EquityCurveController(EquityCurveService equityCurveService, PluginService pluginService) {
        this.equityCurveService = equityCurveService;
        this.pluginService = pluginService;
    }

    /** 资金曲线（需 trading 插件）。LocalDate 转字符串（与其他 DTO 的 buyDate 口径一致）。 */
    @GetMapping("/equity-curve")
    public ResponseEntity<?> equityCurve(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        EquityCurveService.EquityCurve curve = equityCurveService.build(userId);
        java.util.List<Map<String, Object>> points = curve.points().stream()
                .map(p -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("date", p.date().toString());
                    m.put("totalAssets", p.totalAssets());
                    m.put("cash", p.cash());
                    m.put("marketValue", p.marketValue());
                    m.put("invested", p.invested());
                    m.put("netValue", p.netValue());
                    m.put("drawdown", p.drawdown());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(Map.of(
                "points", points,
                "skippedDays", curve.skippedDays(),
                "startDate", curve.startDate(),
                "endDate", curve.endDate()));
    }
}
