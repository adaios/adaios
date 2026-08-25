package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * TradingLot — 交易批次（RFC 20260825 逐笔批次跟踪）。
 * <p>
 * 用户心智的「一笔买入」：同标的 + 同方向 + 同日合并为一个批次（一天最多一个买入批次），
 * 由逐笔流水（{@link TradeRecord}）重放推导，**不落盘**（批次视图 = 流水投影）。
 * 卖出按 LIFO 扣减批次（先卖最近买入的），批次剩余为 0 即关闭（形成回合，见 {@link #realizedPnl()}）。
 * <p>
 * 初始批次（{@link #initial()} = true）：positions.md 里有持仓但流水覆盖不到的底仓
 * （历史导入/持仓快照初始化前就持有的部分）——成本/止损/角色取持仓字段，保证「底仓」不被丢弃。
 *
 * @param lotId        批次稳定 ID（{@code {symbol}_{buyDate}_B}；初始批次 {@code {symbol}_INIT}，推导幂等）
 * @param symbol       股票/资产代码
 * @param name         资产名称
 * @param buyDate      批次买入日期（按日合并：同日多张成交单并入同一批次）
 * @param volume       批次买入数量
 * @param remaining    剩余数量（未卖出；0 = 已关闭）
 * @param costPrice    批次加权平均成本价（每股，含费，4 位小数）
 * @param stopLossPrice 批次止损位（可空——买入时未设，服务层按默认 −7% 兜底判定）
 * @param buyPoint     批次买点类型（B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他；可空）
 * @param role         批次角色（底仓/短线等；初始批次取持仓 role，可后续 web 编辑）
 * @param initial      是否初始批次（导入底仓，无对应流水）
 * @param realizedPnl  该批已实现盈亏（已卖出部分按批次成本分算；关闭 = 回合总盈亏）
 */
public record TradingLot(
        String lotId,
        String symbol,
        String name,
        LocalDate buyDate,
        int volume,
        int remaining,
        BigDecimal costPrice,
        BigDecimal stopLossPrice,
        String buyPoint,
        String role,
        boolean initial,
        BigDecimal realizedPnl
) {

    /** 批次是否已关闭（剩余 0 = 回合完成）。 */
    public boolean closed() {
        return remaining <= 0;
    }

    /** 批次剩余持仓成本（remaining × costPrice）。 */
    public BigDecimal costValue() {
        return costPrice.multiply(BigDecimal.valueOf(remaining));
    }
}
