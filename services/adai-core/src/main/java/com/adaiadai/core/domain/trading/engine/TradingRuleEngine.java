package com.adaiadai.core.domain.trading.engine;

import java.math.BigDecimal;
import java.util.List;

/**
 * TradingRuleEngine — 交易规则引擎（G-3 能力抽离，2026-08-16）。
 * <p>
 * 交易插件 jar 化的能力层：把建议引擎中「确定性可计算的规则判定」从应用服务抽离为独立引擎
 * 组件——规则解析 / 止损硬判定（R66）/ 仓位硬判定（R81）。判定口径与
 * {@code os/trading-engine/engine/} 规格文档一致（knowledge 为真相源，engine 为执行器）。
 * <p>
 * 建议是输出不是执行：引擎只产出判定信号（verdict），不做任何交易动作。
 */
public interface TradingRuleEngine {

    /**
     * 止损硬判定（R66 只输一根K线：收盘跌破止损位就走；R68 入场即设止损）。
     * <p>
     * 现价 &lt; 止损位 → BREACHED（已跌破止损位，suggestion 必须 clear）。
     * 止损位缺失（未设置）→ OK（无据可判，不硬判）。
     *
     * @param currentPrice  现价（实时行情优先，缺失用持仓存储价）
     * @param stopLossPrice 用户预设止损位（可 null）
     */
    StopLossResult evaluateStopLoss(BigDecimal currentPrice, BigDecimal stopLossPrice);

    /**
     * 仓位硬判定（R81 100万以下分4-5个仓位：单票 1/4~1/5，即占比上限 25%）。
     * <p>
     * 持仓占比 &gt; 25% → OVER_WEIGHT（超 R81 仓位上限，suggestion 参考 reduce）。
     *
     * @param positionPercent 单票持仓占比（0-100，后端按市值/总市值确定性计算）
     */
    PositionResult evaluatePosition(BigDecimal positionPercent);

    /**
     * 解析规则条目：{@code **R{n} 标题** + > 描述}（与 rules.md 格式契约一致）。
     * 建议引擎用它在 R66-R95 区间内抽取决策硬约束注入 prompt。
     */
    List<RuleEntry> parseRules(String content);

    /** 规则条目（从 rules.md 解析）。 */
    record RuleEntry(int number, String title, String detail) {}

    /** 止损判定结果。 */
    record StopLossResult(StopLossVerdict verdict, String ruleRef, String message) {}

    /** 仓位判定结果。 */
    record PositionResult(PositionVerdict verdict, String ruleRef, String message) {}
}
