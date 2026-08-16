package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * CommissionCalculator — 手续费计算器（2026-08-16，用户四笔交割实例推得并确认）。
 * <p>
 * 费率（用户只玩沪深主板）：
 * <ul>
 *   <li>佣金    = 成交额 × 万 0.854（无最低 5 元，买入卖出都收，四舍五入到分）</li>
 *   <li>印花税  = 卖出 × 万 5（买入免）</li>
 *   <li>过户费  = 沪市(6/9 开头) × 万 0.1，深市不收复（买入卖出同规则）</li>
 * </ul>
 * 验证：山西汾酒 12230→1.04/0/0.12 · 云南锗业 21096→1.80/10.54/0 · 有研新材 7598→0.65/0/0.08、6624→0.57/3.31/0.07 · 云南锗业 27256→2.33/0/0。
 */
public final class CommissionCalculator {

    // 佣金实为万 0.854（五实例反推：12230→1.04/7598→0.65/21096→1.80/6624→0.57/27256→2.33 全对上）
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.0000854");
    private static final BigDecimal STAMP_TAX_RATE = new BigDecimal("0.0005");
    private static final BigDecimal TRANSFER_FEE_RATE = new BigDecimal("0.00001");
    private static final int SCALE = 2;
    private static final RoundingMode MODE = RoundingMode.HALF_UP;

    private CommissionCalculator() {}

    /** 是否沪市（6/9 开头）——过户费只收沪市。 */
    public static boolean isShanghai(String symbol) {
        return symbol != null && (symbol.startsWith("6") || symbol.startsWith("9"));
    }

    /**
     * 佣金（万 0.854，无最低 5 元）——**四舍五入到分**（券商实例五笔全对上）。
     */
    public static BigDecimal commission(BigDecimal turnover) {
        return turnover.multiply(COMMISSION_RATE).setScale(SCALE, MODE);
    }

    /**
     * 印花税（卖出 万 5；买入 0）——**去尾到分**（券商实例：10.548→10.54、3.312→3.31）。
     */
    public static BigDecimal stampTax(BigDecimal turnover, boolean sell) {
        if (!sell) return BigDecimal.ZERO;
        return turnover.multiply(STAMP_TAX_RATE)
                .setScale(SCALE, RoundingMode.FLOOR);
    }

    /**
     * 过户费（沪市 万 0.1；深市 0）——**四舍五入到分**（券商实例：0.07598→0.08、0.1223→0.12）。
     */
    public static BigDecimal transferFee(String symbol, BigDecimal turnover) {
        if (!isShanghai(symbol)) return BigDecimal.ZERO;
        return turnover.multiply(TRANSFER_FEE_RATE).setScale(SCALE, MODE);
    }

    /** 买入总成本 = 价×量 + 佣金 + 过户费(沪)。 */
    public static BigDecimal buyCost(String symbol, BigDecimal price, int volume) {
        BigDecimal turnover = price.multiply(BigDecimal.valueOf(volume));
        return turnover.add(commission(turnover)).add(transferFee(symbol, turnover));
    }

    /** 卖出回款 = 价×量 - 佣金 - 印花税 - 过户费(沪)。 */
    public static BigDecimal sellProceeds(String symbol, BigDecimal price, int volume) {
        BigDecimal turnover = price.multiply(BigDecimal.valueOf(volume));
        return turnover.subtract(commission(turnover))
                .subtract(stampTax(turnover, true))
                .subtract(transferFee(symbol, turnover));
    }

    /** 摊薄成本价 = 买入总成本 / 数量（recordTrade BUY 落盘用，4 位小数）。 */
    public static BigDecimal unitCost(String symbol, BigDecimal price, int volume) {
        return buyCost(symbol, price, volume)
                .divide(BigDecimal.valueOf(volume), 4, RoundingMode.HALF_UP);
    }
}
