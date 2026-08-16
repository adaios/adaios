package com.adaiadai.core.domain.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** SoldTradeVerdict — 规则对照判定测试（D1，2026-08-16）。 */
class SoldTradeVerdictTest {

    @Test
    void profit_ok() {
        assertTrue(SoldTradeVerdict.compute(5.5, 10).contains("盈利"));
    }

    @Test
    void bigLoss_breaksStopLoss() {
        String v = SoldTradeVerdict.compute(-12.82, 3);
        assertTrue(v.contains("R66"), "亏超 10% 应判扛单违反 R66，实际: " + v);
    }

    @Test
    void shortLoss_r53() {
        String v = SoldTradeVerdict.compute(-6.96, 4);
        assertTrue(v.contains("R53"), "短持仓亏损应判 R53 没涨=错，实际: " + v);
    }

    @Test
    void longLoss_requiresReview() {
        String v = SoldTradeVerdict.compute(-5.5, 30);
        assertTrue(v.contains("复盘"), "久持亏损应提示复盘，实际: " + v);
    }
}
