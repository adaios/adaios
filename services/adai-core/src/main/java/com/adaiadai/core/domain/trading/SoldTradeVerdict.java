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
     * 规则对照判定。
     *
     * @param holdPnlPct 持仓期涨幅%（交易结果）
     * @param holdDays   持仓天数
     * @return verdict 文案（自然语言，无系统标签）
     */
    public static String compute(double holdPnlPct, int holdDays) {
        if (holdPnlPct >= 0) {
            return "盈利了结";
        }
        // 亏损：
        if (holdPnlPct <= -5.0) {
            // R66：亏损超 5%（课程止损幅度上限 R67/R72 为 3-5%）= 止损没执行（只输一根K线的纪律被违反）
            // P2-交易5（2026-08-17）：阈值 -10% → -5%（用户确认贴合课程）；旧 -10% 会让亏 8% 的扛单判成非违反
            return "扛单超 5%——按 R66 只输一根K线，止损位早该执行";
        }
        if (holdDays <= 5) {
            // R53：短持仓却亏 = 该涨不涨没及时处理
            return "短持仓亏损——按 R53 没涨=错，该涨不涨该拍掉";
        }
        // 其他亏损：持有较久仍亏 → 没快速脱离成本区（R53 延展）
        return "亏损持仓——按纪律复盘：止损/卖点是否按计划执行";
    }
}
