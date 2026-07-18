package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * PositionRepository — 持仓存储接口（端口定义）。
 * <p>
 * 定义在 domain/trading 层，实现由 infrastructure/storage 提供。
 * 采用 File First：持仓数据以 {@code data/trading/positions.md} 文件存储。
 */
public interface PositionRepository {

    /**
     * 获取所有持仓。
     */
    List<Position> findAll();

    /**
     * 根据代码查找持仓。
     */
    Optional<Position> findBySymbol(String symbol);

    /**
     * 保存或更新持仓列表（全量替换）。
     */
    void saveAll(List<Position> positions);

    /**
     * 获取现金余额。
     */
    default BigDecimal cashBalance() {
        return BigDecimal.ZERO;
    }

    /**
     * 获取当前投资组合快照。
     */
    default PortfolioSnapshot snapshot() {
        return PortfolioSnapshot.of(findAll(), cashBalance());
    }
}
