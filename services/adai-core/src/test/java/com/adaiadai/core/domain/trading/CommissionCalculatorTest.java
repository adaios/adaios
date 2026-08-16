package com.adaiadai.core.domain.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommissionCalculator — 手续费费率测试（用户四笔交割实例逐分验证，2026-08-16）。
 */
class CommissionCalculatorTest {

    private void assertAmount(double expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(String.valueOf(expected)).compareTo(actual),
                "期望 " + expected + " 实际 " + actual);
    }

    @Test
    void buy_shanxiFenJiu_exactMatch() {
        // 买入 山西汾酒 12230 → 佣金 1.04 / 印花税 0 / 过户费 0.12（沪）
        BigDecimal turnover = new BigDecimal("12230");
        assertAmount(1.04, CommissionCalculator.commission(turnover));
        assertAmount(0, CommissionCalculator.stampTax(turnover, false));
        assertAmount(0.12, CommissionCalculator.transferFee("600809", turnover));
    }

    @Test
    void sell_yunnanGermanium_exactMatch() {
        // 卖出 云南锗业 21096 → 佣金 1.80 / 印花税 10.54 / 过户费 0（深）
        BigDecimal turnover = new BigDecimal("21096");
        assertAmount(1.80, CommissionCalculator.commission(turnover));
        assertAmount(10.54, CommissionCalculator.stampTax(turnover, true));
        assertAmount(0, CommissionCalculator.transferFee("002428", turnover));
    }

    @Test
    void buy_youyanNewMaterial_exactMatch() {
        // 买入 有研新材 7598 → 佣金 0.65 / 印花税 0 / 过户费 0.08（沪）
        BigDecimal turnover = new BigDecimal("7598");
        assertAmount(0.65, CommissionCalculator.commission(turnover));
        assertAmount(0, CommissionCalculator.stampTax(turnover, false));
        assertAmount(0.08, CommissionCalculator.transferFee("600206", turnover));
    }

    @Test
    void sell_youyanNewMaterial_exactMatch() {
        // 卖出 有研新材 6624 → 佣金 0.57 / 印花税 3.31 / 过户费 0.07（沪）
        BigDecimal turnover = new BigDecimal("6624");
        assertAmount(0.57, CommissionCalculator.commission(turnover));
        assertAmount(3.31, CommissionCalculator.stampTax(turnover, true));
        assertAmount(0.07, CommissionCalculator.transferFee("600206", turnover));
    }

    @Test
    void buy_yunnanGermanium_exactMatch() {
        // 买入 云南锗业 27256 → 佣金 2.33 / 印花税 0 / 过户费 0（深）
        BigDecimal turnover = new BigDecimal("27256");
        assertAmount(2.33, CommissionCalculator.commission(turnover));
        assertAmount(0, CommissionCalculator.stampTax(turnover, false));
        assertAmount(0, CommissionCalculator.transferFee("002428", turnover));
    }

    @Test
    void unitCost_includesFees() {
        // 山西汾酒 买入 12230 + 1.04 + 0.12 = 12231.16 / 100 = 122.3116
        BigDecimal unit = CommissionCalculator.unitCost("600809", new BigDecimal("122.30"), 100);
        assertEquals(0, unit.compareTo(new BigDecimal("122.3116")));
    }

    @Test
    void sellProceeds_deductsFees() {
        // 云南锗业 卖出 21096 - 1.80 - 10.54 - 0 = 21083.66
        BigDecimal proceeds = CommissionCalculator.sellProceeds("002428", new BigDecimal("105.48"), 200);
        assertEquals(0, proceeds.compareTo(new BigDecimal("21083.66")));
    }

    @Test
    void isShanghai_rule() {
        assertTrue(CommissionCalculator.isShanghai("600809"));
        assertTrue(CommissionCalculator.isShanghai("900901"));
        assertFalse(CommissionCalculator.isShanghai("000725"));
        assertFalse(CommissionCalculator.isShanghai("002428"));
        assertFalse(CommissionCalculator.isShanghai("300502"));
    }
}
