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
     * 获取该用户所有持仓。
     */
    List<Position> findAll(String userId);

    /**
     * 根据代码查找持仓。
     */
    Optional<Position> findBySymbol(String userId, String symbol);

    /**
     * 保存或更新持仓列表（全量替换）。
     */
    void saveAll(String userId, List<Position> positions);

    /**
     * 获取现金余额。
     */
    default BigDecimal cashBalance(String userId) {
        return BigDecimal.ZERO;
    }

    /**
     * 获取当前投资组合快照。
     */
    default PortfolioSnapshot snapshot(String userId) {
        return PortfolioSnapshot.of(findAll(userId), cashBalance(userId));
    }

    /**
     * 保存导入文件（上传留存，2026-08-16）：写入 {@code data/{userId}/{path}}（UTF-8 文本）。
     */
    default void saveImportFile(String userId, String path, String content) {
        // 默认实现：不可用（由文件存储实现覆盖）
    }


    /**
     * 保存现金余额（资金股份查询导入，2026-08-16）：写 positions.md 的 cashBalance 行。
     */
    default void saveCashBalance(String userId, java.math.BigDecimal cash) {
        // 默认实现：不可用（由文件存储实现覆盖）
    }

}
