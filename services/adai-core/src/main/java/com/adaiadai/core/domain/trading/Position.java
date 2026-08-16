package com.adaiadai.core.domain.trading;

import com.fasterxml.jackson.annotation.JsonGetter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Position — 持仓。
 * <p>
 * 一只资产的持仓汇总。由交易流水汇总生成。
 * 采用 File First：每个资产在 {@code data/trading/positions.md} 中有一行记录。
 * <p>
 * RFC 20260816 §2.2 加字段：entryDate（首买日，加仓不覆盖）/ stopLossPrice（最近一次 BUY 的止损位）/
 * buyPoint（最近一次 BUY 的买点）/ role（web 持仓编辑，主仓/副仓/防守等）。后三个可空；
 * 旧 positions.md 无新列时解析兜底为 null（freeze MINOR，不报错）。
 *
 * @param symbol       股票/资产代码
 * @param name         资产名称
 * @param quantity     当前持仓数量
 * @param avgCost      平均成本价
 * @param currentPrice 当前市价（由用户输入或行情更新）
 * @param lastUpdated  最后更新时间
 * @param entryDate    首买日（首次 BUY 落盘，加仓不覆盖；可空=旧数据未补录）
 * @param stopLossPrice 止损位（最近一次 BUY 的值，SELL 保留；可空）
 * @param buyPoint     买点类型（最近一次 BUY 的值，SELL 保留；可空）
 * @param role         持仓角色（web 编辑：防守/前锋/中场/机动 + 主仓/副仓；可空）
 */
public record Position(
        String symbol,
        String name,
        int quantity,
        BigDecimal avgCost,
        BigDecimal currentPrice,
        LocalDateTime lastUpdated,
        LocalDate entryDate,
        BigDecimal stopLossPrice,
        String buyPoint,
        String role
) {

    /**
     * 旧 6 字段便捷构造（无入场/止损/买点/角色，兼容历史调用与旧数据解析）。
     */
    public Position(String symbol, String name, int quantity, BigDecimal avgCost,
                    BigDecimal currentPrice, LocalDateTime lastUpdated) {
        this(symbol, name, quantity, avgCost, currentPrice, lastUpdated, null, null, null, null);
    }

    /**
     * 持仓市值。
     */
    @JsonGetter
    public BigDecimal marketValue() {
        return currentPrice.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * 持仓成本。
     */
    @JsonGetter
    public BigDecimal costValue() {
        return avgCost.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * 浮动盈亏金额。
     */
    @JsonGetter
    public BigDecimal pnl() {
        return marketValue().subtract(costValue());
    }

    /**
     * 浮动盈亏百分比。
     */
    @JsonGetter
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
    @JsonGetter
    public BigDecimal liquidationValue() {
        return marketValue();
    }
}
