package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.EquityCurveService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final TradingAppService tradingAppService;
    private final PluginService pluginService;

    public EquityCurveController(EquityCurveService equityCurveService,
                                 TradingAppService tradingAppService,
                                 PluginService pluginService) {
        this.equityCurveService = equityCurveService;
        this.tradingAppService = tradingAppService;
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
                "endDate", curve.endDate(),
                // 2026-09-16：逐日「当日盈亏」（券商口径）——周期盈亏由它累加；顺带给前端每日盈亏用
                "dailyPnl", curve.dailyPnl()));
    }

    /**
     * 今日 / 本周 / 本月盈亏（金额 + 比例）——2026-09-15 用户要求「券商 App 那样的日周月盈亏」。
     * <pre>
     * GET /api/v1/trading/pnl-periods →
     * {today:{pnl,pct,base,from,partial}, week:{...}, month:{...}, asOf, anchorDate, note}
     * </pre>
     * 口径与资金曲线同源（逐日总资产差分，剔除银证转账），pct 可为 null（区间起点前无数据 /
     * 锚定日之前不可追溯）——**null 一律不渲染成 0%**。
     */
    @GetMapping("/pnl-periods")
    public ResponseEntity<?> pnlPeriods(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_TRADING)) {
            return ResponseEntity.status(403).body(Map.of("error", "trading 插件未启用，无法使用交易功能"));
        }
        EquityCurveService.PnlPeriods p = equityCurveService.periods(userId);
        // 2026-09-15 口径对齐：资金曲线差分的「今日」含 K 线价差，与账户卡的「当日盈亏」
        // （dailyPnlDetail，券商当日口径）会差出一截——同一屏两个「今日盈亏」打架就是口径漂移。
        // 这里用 dailyPnlDetail 覆盖 today，并把差额回填进 week/month（三者保持自洽）；
        // dailyPnlDetail 失败则退回纯差分（不阻断，也不编造）。
        java.time.LocalDate today = java.time.LocalDate.now();
        try {
            TradingAppService.DailyPnlDetail detail = tradingAppService.dailyPnlDetail(userId, today);
            BigDecimal realToday = detail.todayPnl();
            // P1-2（2026-09-17 深审修复）：盘前 / 非交易日时 dailyPnlDetail 算的是**上一交易日**——
            // 那种数既不能覆盖 today，更不能把差额回填进 week/month（会把上一交易日的盈亏
            // 又加进本周/本月 → 双计）。只有「算的就是今天」才允许覆盖与回填。
            boolean sameDay = detail.effectiveDate() == null || today.equals(detail.effectiveDate());
            if (realToday != null && p.today() != null && sameDay) {
                BigDecimal adjust = realToday.subtract(p.today().pnl());
                p = new EquityCurveService.PnlPeriods(
                        setPnl(p.today(), realToday),
                        withPnl(p.week(), adjust),
                        withPnl(p.month(), adjust),
                        p.asOf(), p.anchorDate(), p.note());
            }
        } catch (Exception ignored) {
            // 退回差分口径
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("today", period(p.today()));
        body.put("week", period(p.week()));
        body.put("month", period(p.month()));
        body.put("asOf", p.asOf());
        body.put("anchorDate", p.anchorDate() != null ? p.anchorDate().toString() : null);
        body.put("note", p.note());
        return ResponseEntity.ok(body);
    }

    private static java.util.Map<String, Object> period(EquityCurveService.PeriodPnl p) {
        if (p == null) return null;
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("pnl", p.pnl());
        m.put("pct", p.pct());
        m.put("base", p.base());
        m.put("from", p.from() != null ? p.from().toString() : null);
        m.put("partial", p.partial());
        return m;
    }

    /** 区间金额加 delta（周/月：把 today 的差分换成当日盈亏口径后的差额回填）。 */
    private static EquityCurveService.PeriodPnl withPnl(EquityCurveService.PeriodPnl p, BigDecimal delta) {
        return p == null ? null : recalc(p, p.pnl().add(delta));
    }

    /** 直接用给定金额替换（today：换成与账户卡同源的当日盈亏）。 */
    private static EquityCurveService.PeriodPnl setPnl(EquityCurveService.PeriodPnl p, BigDecimal pnl) {
        return p == null ? null : recalc(p, pnl);
    }

    private static EquityCurveService.PeriodPnl recalc(EquityCurveService.PeriodPnl p, BigDecimal pnl) {
        BigDecimal pct = null;
        if (p.base() != null && p.base().signum() > 0) {
            pct = pnl.divide(p.base(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        }
        return new EquityCurveService.PeriodPnl(p.key(), pnl, pct, p.base(), p.from(), p.partial());
    }
}
