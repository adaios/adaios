package com.adaiadai.core.domain.trading.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DefaultTradingRuleEngine — 规则引擎默认实现（G-3 能力抽离）。
 * <p>
 * 判定口径（与 {@code os/trading-engine/knowledge/context/rules.md} 一致）：
 * <ul>
 *   <li>R66 只输一根K线：收盘跌破止损位就走 → 现价 &lt; 止损位 → BREACHED</li>
 *   <li>R68 入场即设止损：止损位缺失不硬判（OK，无据可判）</li>
 *   <li>R81 100万以下分4-5个仓位：单票 1/4~1/5（上限 25%）→ 占比 &gt; 25% → OVER_WEIGHT</li>
 * </ul>
 * 引擎只产出判定信号（verdict），建议是输出不是执行——不做任何交易动作。
 */
@Component
public class DefaultTradingRuleEngine implements TradingRuleEngine {

    /** R81 单票仓位上限：100万以下分 4-5 仓，单票 1/4~1/5 → 25%。 */
    static final BigDecimal R81_MAX_POSITION_PERCENT = new BigDecimal("25");

    /** rules.md 规则条目格式：{@code **R{n} 标题** + > 描述}。 */
    private static final Pattern RULE_PATTERN = Pattern.compile(
            "\\*\\*R(\\d+)\\s+([^*\\n]+?)\\s*\\*\\*(?:\\n>\\s*([^\\n]+))?");

    @Override
    public StopLossResult evaluateStopLoss(BigDecimal currentPrice, BigDecimal stopLossPrice) {
        if (stopLossPrice == null) {
            // R68：止损位未设置 → 无据可判，不硬判（建议引擎已在买入时强制用户填写）
            return new StopLossResult(StopLossVerdict.OK, "R68",
                    "止损位未设置，无法判定（R68 入场即设止损）");
        }
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return new StopLossResult(StopLossVerdict.OK, "R66",
                    "现价不可用，无法判定（R66 只输一根K线）");
        }
        if (currentPrice.compareTo(stopLossPrice) < 0) {
            return new StopLossResult(StopLossVerdict.BREACHED, "R66",
                    "现价 " + currentPrice.stripTrailingZeros().toPlainString()
                            + " < 止损位 " + stopLossPrice.stripTrailingZeros().toPlainString()
                            + "，已跌破止损位（R66 收盘跌破就走）");
        }
        return new StopLossResult(StopLossVerdict.OK, "R66",
                "现价未跌破止损位（R66 只输一根K线）");
    }

    @Override
    public PositionResult evaluatePosition(BigDecimal positionPercent) {
        if (positionPercent == null) {
            return new PositionResult(PositionVerdict.OK, "R81", "持仓占比不可用，无法判定");
        }
        if (positionPercent.compareTo(R81_MAX_POSITION_PERCENT) > 0) {
            return new PositionResult(PositionVerdict.OVER_WEIGHT, "R81",
                    "持仓占比 " + positionPercent.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
                            + "% 超 R81 上限 25%（100万以下分4-5个仓位）");
        }
        return new PositionResult(PositionVerdict.OK, "R81",
                "持仓占比未超 R81 上限 25%");
    }

    @Override
    public List<RuleEntry> parseRules(String content) {
        if (content == null || content.isBlank()) return List.of();
        Matcher matcher = RULE_PATTERN.matcher(content);
        List<RuleEntry> rules = new ArrayList<>();
        while (matcher.find()) {
            rules.add(new RuleEntry(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2).strip(),
                    matcher.group(3) != null ? matcher.group(3).strip() : ""
            ));
        }
        return rules;
    }
}
