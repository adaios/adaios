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
 * @param stopLossPrice 人工止损位（最近一次 BUY 的值，SELL 保留；web 可编辑；可空）
 * @param buyPoint     买点类型（最近一次 BUY 的值，SELL 保留；可空）
 * @param role         持仓角色（web 编辑：防守/前锋/中场/机动 + 主仓/副仓；可空）
 * @param computedStopLossPrice 系统计算止损位（风险预算公式动态算出，不落盘；可空=无本金/异常输入）
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
        String role,
        BigDecimal computedStopLossPrice
) {

    /**
     * 旧 6 字段便捷构造（无入场/止损/买点/角色，兼容历史调用与旧数据解析）。
     */
    public Position(String symbol, String name, int quantity, BigDecimal avgCost,
                    BigDecimal currentPrice, LocalDateTime lastUpdated) {
        this(symbol, name, quantity, avgCost, currentPrice, lastUpdated, null, null, null, null, null);
    }

    /**
     * 10 参构造（无计算止损，兼容既有调用；计算止损由服务层组装时填充）。
     */
    public Position(String symbol, String name, int quantity, BigDecimal avgCost,
                    BigDecimal currentPrice, LocalDateTime lastUpdated,
                    LocalDate entryDate, BigDecimal stopLossPrice, String buyPoint, String role) {
        this(symbol, name, quantity, avgCost, currentPrice, lastUpdated,
                entryDate, stopLossPrice, buyPoint, role, null);
    }

    /**
     * 生效止损位（**一条线**）＝**人工覆盖，系统兜底**：
     * 用户手填了就用手填的（覆盖），没填才回落到系统计算值；两者皆空 → null（未设，判定跳过）。
     * R66 判定 / 接近止损预警 / 建议引擎 / 复盘 / 推送统一用本值——三端只看这一条，不再各算各的。
     *
     * <p>⚠️ 口径变更（2026-10-09 · 用户拍板原话）：「**系统计算是默认，人手动输入是覆盖；
     * 这概念，按理说只有一个止损价位**」。原实现是 `max(人工, 计算)`（取更严格），会出现两件怪事：
     * ① 我把线主动往下挪了，系统仍按更高的那条判我「破了你的线」；
     * ② app 首屏按人工位、推送/R66 按生效位 → 同一只票给出**相反结论**（前端官 P2-1）。
     */
    @JsonGetter
    public BigDecimal effectiveStopLoss() {
        return stopLossPrice != null ? stopLossPrice : computedStopLossPrice;
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
     * <p>
     * **成本价 ≤ 0 → null（前端显示「—」），不给数字**（2026-09-13 负成本批）：
     * 成本价经反复做 T / 分红摊到 0 以下时，「(现价−成本)/成本」这个式子在数学上仍然是数，
     * 但语义已经翻转——负成本下它算出的是负的百分比（实测 600601：成本 −5.078 / 现价 14.83
     * → −392%，而券商口径是 +134%），会把人误导成巨亏。
     * 盈亏**金额**不受影响（市场价−成本额，负成本自然算出更高的浮盈，与券商一致）。
     * 另外前端一律要把 null 渲染成「—」而不是 0%——「0%」会被读成「没涨没跌」，也是错的。
     */
    @JsonGetter
    public BigDecimal pnlPercent() {
        if (avgCost == null || avgCost.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
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
