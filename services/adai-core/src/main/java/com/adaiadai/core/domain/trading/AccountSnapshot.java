package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AccountSnapshot — 账户总体快照（资金股份查询导入，RFC 20260816）。
 * <p>
 * 数据源 = 通达信「资金股份查询」首行（券商口径，含手续费摊薄）——顶层账户卡直接展示，
 * 不实时计算（用户确认：数据依赖导入，日常定时任务收市后更新）。
 *
 * @param assets       总资产（参考市值+余额）
 * @param cash         余额（可用资金）
 * @param available    可用
 * @param withdrawable 可取
 * @param marketValue  参考市值
 * @param pnl          持仓浮动盈亏（券商口径，含手续费——非总盈亏）
 * @param todayPnl     当日盈亏（持仓明细「当日盈亏」列求和，可空）
 * @param principal    累计投入本金（用户提供，2026-08-16——总盈亏 = 资产 - 本金）
 * @param snapshotDate 快照日期
 */
public record AccountSnapshot(
        BigDecimal assets,
        BigDecimal cash,
        BigDecimal available,
        BigDecimal withdrawable,
        BigDecimal marketValue,
        BigDecimal pnl,
        BigDecimal todayPnl,
        BigDecimal principal,
        LocalDate snapshotDate
) {
    public AccountSnapshot {
        if (assets == null) assets = BigDecimal.ZERO;
        if (cash == null) cash = BigDecimal.ZERO;
        if (available == null) available = BigDecimal.ZERO;
        if (withdrawable == null) withdrawable = BigDecimal.ZERO;
        if (marketValue == null) marketValue = BigDecimal.ZERO;
        if (pnl == null) pnl = BigDecimal.ZERO;
        if (todayPnl == null) todayPnl = BigDecimal.ZERO;
        if (principal == null) principal = BigDecimal.ZERO;
        if (snapshotDate == null) snapshotDate = LocalDate.now();
    }

    /** 账户总盈亏 = 总资产 - 本金（本金 > 0 时有效；否则退回持仓浮盈）。 */
    public BigDecimal totalPnl() {
        return principal.compareTo(BigDecimal.ZERO) > 0 ? assets.subtract(principal) : pnl;
    }
}
