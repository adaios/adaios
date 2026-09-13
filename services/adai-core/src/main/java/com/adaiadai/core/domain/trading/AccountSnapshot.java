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
 * @param todayPnlSource 当日盈亏的**来源**（P2-交易48，2026-09-14）：
 *        {@link #SOURCE_BROKER}（券商资金股份文件「当日盈亏」列求和）/
 *        {@link #SOURCE_CALC}（系统按成交流水精确计算）/
 *        null（存量数据或未标注——**不编造**，前端不标来源）。
 *        起因：同名字段有三个可能来源（券商文件 / 收盘精确计算 / 上一次重算遗留），UI 只显示一个数字，
 *        2026-09-13 用户实测「−2837 是周六算的」时只能翻日志才定位到根因。
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
        LocalDate snapshotDate,
        String todayPnlSource
) {
    /** 当日盈亏来源：券商资金股份导出「当日盈亏」列求和（券商权威口径）。 */
    public static final String SOURCE_BROKER = "broker";
    /** 当日盈亏来源：系统按当日成交流水精确计算（已实现 + 持仓日浮动 + 股息/红利税）。 */
    public static final String SOURCE_CALC = "calc";

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
        // todayPnlSource 保持 null = 未知（不编造来源，前端据此不标注）
        if (todayPnlSource != null && todayPnlSource.isBlank()) todayPnlSource = null;
    }

    /** 9 参兼容构造（来源未知）——存量调用点/读取旧文件用。 */
    public AccountSnapshot(BigDecimal assets, BigDecimal cash, BigDecimal available,
                           BigDecimal withdrawable, BigDecimal marketValue, BigDecimal pnl,
                           BigDecimal todayPnl, BigDecimal principal, LocalDate snapshotDate) {
        this(assets, cash, available, withdrawable, marketValue, pnl, todayPnl, principal,
                snapshotDate, null);
    }

    /** 账户总盈亏 = 总资产 - 本金（本金 > 0 时有效；否则退回持仓浮盈）。 */
    public BigDecimal totalPnl() {
        return principal.compareTo(BigDecimal.ZERO) > 0 ? assets.subtract(principal) : pnl;
    }
}
