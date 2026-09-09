package com.adaiadai.core.domain.trading;

import java.time.LocalDate;

/**
 * PendingClearance — 待补清仓档案提示（RFC 20260909 批 1 双轨条件 B）。
 * <p>
 * 流水证明某股已清仓（持仓已无、流水有卖出）但买入基线在流水窗口外（BUY 总量 &lt; SELL 总量），
 * 无法推导完整档案 → <b>不落脏档案</b>，只进提示清单（GET /trading/sold 响应 pendingClearances），
 * 前端横幅引导用户导入清仓股导出补全档案。
 *
 * @param symbol   股票代码
 * @param name     股票名称
 * @param sellDate 最近一次清仓（卖出）日期
 * @param reason   人话提示文案（为什么缺 + 怎么补）
 */
public record PendingClearance(
        String symbol,
        String name,
        LocalDate sellDate,
        String reason
) {
    public PendingClearance {
        if (symbol == null) symbol = "";
        if (name == null) name = "";
        if (reason == null) reason = "";
    }
}
