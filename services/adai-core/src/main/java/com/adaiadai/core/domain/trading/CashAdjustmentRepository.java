package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.util.List;

/**
 * CashAdjustmentRepository — 对账调整存储端口（RFC 20261003 C4，2026-10-03）。
 * 实现见 {@code infrastructure/storage/CashAdjustmentFileRepository}（File First）。
 */
public interface CashAdjustmentRepository {

    /** 全部调整（时间倒序）。 */
    List<CashAdjustment> findAll(String userId);

    /** 追加一条调整。 */
    void append(String userId, CashAdjustment adjustment);

    /** 调整累计额（无记录返回 0）。 */
    default BigDecimal total(String userId) {
        return findAll(userId).stream()
                .map(CashAdjustment::amount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
