package com.adaiadai.core.domain.trading;

/**
 * SoldTradeVerdict — 清仓股规则对照判定（D1，2026-08-16）。
 * <p>
 * 从课程提炼的可执行口径（简化版，供用户复盘确认）：
 * <ul>
 *   <li>R53 没涨=错：买入后没快速脱离成本区 → 该涨不涨没处理</li>
 *   <li>R66 只输一根K线：止损是底线，大幅亏损 = 扛单违反止损</li>
 *   <li>纪律决定对错（复盘五步法，非 R85——R85 实为「分仓 vs 重仓目的不可混淆」，K47 2026-08-17 走查纠正假引用）</li>
 * </ul>
 * 判定输入：持仓期涨幅% + 持仓天数（通达信清仓导出字段）。
 */
public final class SoldTradeVerdict {

    private SoldTradeVerdict() {}

    /**
     * 规则对照判定（默认 adai 规则语义：止损阈值 -5%、短持仓 5 天）。
     * <p>
     * 第三阶段（用户规则层）：默认语义 = adai 规则包（R53 没涨=错 / R66 只输一根K线），
     * 文案保留规则引用（前端纪律遵守率按 verdict 含规则号统计，B3-5 契约）。
     * 其他用户可通过 {@link #compute(double, int, double, int, String, String)} 传自己的阈值与规则引用。
     *
     * @param holdPnlPct 持仓期涨幅%（交易结果）
     * @param holdDays   持仓天数
     * @return verdict 文案（自然语言，无系统标签）
     */
    public static String compute(double holdPnlPct, int holdDays) {
        return compute(holdPnlPct, holdDays, DEFAULT_STOP_LOSS_PCT, DEFAULT_SHORT_HOLD_DAYS,
                "R66", "R53");
    }

    /** 默认止损阈值 %（课程止损幅度上限 R67/R72 为 3-5%，P2-交易5 用户确认 -5%）。 */
    public static final double DEFAULT_STOP_LOSS_PCT = 5.0;

    /** 默认短持仓天数（R53 没涨=错：该涨不涨没处理）。 */
    public static final int DEFAULT_SHORT_HOLD_DAYS = 5;

    /**
     * 规则对照判定（按用户规则阈值 + 规则引用）。
     *
     * @param holdPnlPct       持仓期涨幅%
     * @param holdDays         持仓天数
     * @param stopLossPct      止损阈值%（亏损超过该值 = 扛单违反止损）
     * @param shortHoldDays    短持仓天数（短持仓却亏 = 该涨不涨）
     * @param stopLossRuleRef  止损规则引用（如 "R66"；前端遵守率按此统计）
     * @param shortHoldRuleRef 短持仓规则引用（如 "R53"）
     * @return verdict 文案（自然语言，无系统标签）
     */
    public static String compute(double holdPnlPct, int holdDays, double stopLossPct, int shortHoldDays,
                                 String stopLossRuleRef, String shortHoldRuleRef) {
        if (holdPnlPct >= 0) {
            return "盈利了结";
        }
        // 亏损：
        if (holdPnlPct <= -stopLossPct) {
            // 亏损超止损阈值 = 止损没执行（只输一根K线的纪律被违反）
            return "扛单超 " + formatPct(stopLossPct) + "%——按 " + stopLossRuleRef + " 只输一根K线，止损位早该执行";
        }
        if (holdDays <= shortHoldDays) {
            // 短持仓却亏 = 该涨不涨没及时处理
            return "短持仓亏损——按 " + shortHoldRuleRef + " 没涨=错，该涨不涨该拍掉";
        }
        // 其他亏损：持有较久仍亏 → 没快速脱离成本区（短持仓规则延展）
        // B3-5（2026-08-23，P2-交易11 半修残留）：标规则引用——前端纪律遵守率按 verdict 含规则号判违规，
        // 原末分支无规则号 → 持有较久小亏不计违规、遵守率虚高
        return "亏损持仓——按纪律复盘：止损/卖点是否按计划执行（" + shortHoldRuleRef + "）";
    }

    private static String formatPct(double pct) {
        long v = Math.round(pct);
        return v == pct ? String.valueOf(v) : String.valueOf(pct);
    }
}
