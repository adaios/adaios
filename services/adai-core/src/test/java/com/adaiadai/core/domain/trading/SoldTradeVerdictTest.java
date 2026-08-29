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
        assertTrue(v.contains("R66"), "亏超 5% 应判扛单违反 R66，实际: " + v);
    }

    @Test
    void loss8pct_breaksStopLoss() {
        // P2-5（2026-08-17）：阈值 -10% → -5%（用户确认贴合课程 R67/R72 3-5%）
        String v = SoldTradeVerdict.compute(-8.0, 3);
        assertTrue(v.contains("R66"), "亏 8% 也应判扛单违反 R66（旧阈值 10% 会漏判），实际: " + v);
    }

    @Test
    void loss4pct_shortIsR53() {
        // 亏 4% < 5% → 不判 R66；短持仓 → R53
        String v = SoldTradeVerdict.compute(-4.0, 4);
        assertTrue(v.contains("R53"), "亏 4% 短持仓应判 R53，实际: " + v);
    }

    @Test
    void shortLoss_r53() {
        String v = SoldTradeVerdict.compute(-4.8, 4);
        assertTrue(v.contains("R53"), "短持仓小亏应判 R53 没涨=错，实际: " + v);
    }

    @Test
    void longLoss_requiresReview() {
        String v = SoldTradeVerdict.compute(-4.5, 30);
        assertTrue(v.contains("复盘"), "久持小亏应提示复盘，实际: " + v);
    }

    @Test
    void longLoss_marksR53() {
        // B3-5（2026-08-23，P2-交易11 半修残留）：久持小亏（非短持仓）也标 R53 延展——
        // 前端纪律遵守率按 verdict 含规则号判违规，旧末分支无规则号 → 遵守率虚高
        String v = SoldTradeVerdict.compute(-4.5, 30);
        assertTrue(v.contains("R53"), "久持小亏应标 R53（延展），前端才计入违规，实际: " + v);
    }

    // ── 第三阶段：用户规则参数化（阈值 + 规则引用按用户配置）──

    @Test
    void customThreshold_lessStrict_doesNotBreak() {
        // 用户自定义止损阈值 8%（比默认 5% 宽松）：亏 6% 不判扛单
        String v = SoldTradeVerdict.compute(-6.0, 3, 8.0, 5, "R66", "R53");
        assertTrue(!v.contains("R66"), "用户阈值 8%：亏 6% 不应判 R66，实际: " + v);
    }

    @Test
    void customThreshold_stricter_breaks() {
        // 用户自定义止损阈值 3%（更严）：亏 4% 即判扛单
        String v = SoldTradeVerdict.compute(-4.0, 3, 3.0, 5, "R66", "R53");
        assertTrue(v.contains("R66"), "用户阈值 3%：亏 4% 应判 R66，实际: " + v);
    }

    @Test
    void customShortHoldDays_stricter() {
        // 用户自定义短持仓 3 天：持有 4 天小亏 → 判短持仓（默认 5 天不判）
        String v = SoldTradeVerdict.compute(-3.0, 4, 5.0, 3, "R66", "R53");
        assertTrue(v.contains("R53"), "用户短持仓 3 天：持有 4 天小亏应判 R53，实际: " + v);
    }

    @Test
    void customRuleRef_usesUserReference() {
        // 用户自定义规则引用（非 R66/R53，如自编号 S1）：前端遵守率按用户规则统计
        String v = SoldTradeVerdict.compute(-6.0, 3, 5.0, 5, "S1", "S2");
        assertTrue(v.contains("S1"), "用户规则引用 S1 应出现在文案，实际: " + v);
        assertTrue(!v.contains("R66"), "不应出现默认 R66 引用，实际: " + v);
    }
}
