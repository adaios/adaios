package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Position — 持仓。
 * <p>
 * 一只资产的持仓汇总。由交易流水汇总生成。
 * 采用 File First：每个资产在 {@code data/trading/positions.md} 中有一行记录。
 *
 * @param symbol      股票/资产代码
 * @param name        资产名称
 * @param quantity    当前持仓数量
 * @param avgCost     平均成本价
 * @param currentPrice 当前市价（由用户输入或行情更新）
 * @param lastUpdated  最后更新时间
 */
public record Position(
        String symbol,
        String name,
        int quantity,
        BigDecimal avgCost,
        BigDecimal currentPrice,
        LocalDateTime lastUpdated
) {

    /**
     * 持仓市值。
     */
    public BigDecimal marketValue() {
        return currentPrice.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * 持仓成本。
     */
    public BigDecimal costValue() {
        return avgCost.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * 浮动盈亏金额。
     */
    public BigDecimal pnl() {
        return marketValue().subtract(costValue());
    }

    /**
     * 浮动盈亏百分比。
     */
    public BigDecimal pnlPercent() {
        if (avgCost.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(avgCost)
                .divide(avgCost, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 以当前市价平仓全部持仓的金额。
     */
    public BigDecimal liquidationValue() {
        return marketValue();
    }
}
