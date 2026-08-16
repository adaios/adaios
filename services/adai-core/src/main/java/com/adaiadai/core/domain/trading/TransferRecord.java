package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * TransferRecord — 银证转账记录（2026-08-16，净投入跟踪）。
 * <p>
 * 本金（净投入）= 转入累计 - 转出累计；现金 = 导入基准 + Σ转入 - Σ转出。
 * 转账低频，表单提交即可——阿呆据此全自动推导总资产/总盈亏。
 *
 * @param id     记录 ID
 * @param type   IN=转入（银行卡→证券）/ OUT=转出（证券→银行卡）
 * @param amount 金额（正数）
 * @param date   日期
 * @param note   备注（可空）
 */
public record TransferRecord(
        String id,
        String type,
        BigDecimal amount,
        LocalDate date,
        String note
) {
    public TransferRecord {
        if (type == null || type.isBlank()) type = "IN";
        if (amount == null) amount = BigDecimal.ZERO;
        if (date == null) date = LocalDate.now();
        if (note == null) note = "";
    }

    public boolean isIn() {
        return "IN".equals(type);
    }
}
