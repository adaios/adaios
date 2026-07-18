package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PortfolioSnapshot — 投资组合快照。
 *
 * @param positions    当前所有持仓
 * @param totalPnl     总盈亏
 * @param totalCost    总成本
 * @param totalValue   总市值
 * @param cashBalance  现金余额
 * @param snapshotTime 快照时间
 */
public record PortfolioSnapshot(
        List<Position> positions,
        BigDecimal totalPnl,
        BigDecimal totalCost,
        BigDecimal totalValue,
        BigDecimal cashBalance,
        LocalDateTime snapshotTime
) {

    public static PortfolioSnapshot of(List<Position> positions, BigDecimal cashBalance) {
        BigDecimal totalCost = positions.stream()
                .map(Position::costValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalValue = positions.stream()
                .map(Position::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPnl = totalValue.subtract(totalCost);

        return new PortfolioSnapshot(
                List.copyOf(positions),
                totalPnl, totalCost, totalValue,
                cashBalance, LocalDateTime.now()
        );
    }
}
