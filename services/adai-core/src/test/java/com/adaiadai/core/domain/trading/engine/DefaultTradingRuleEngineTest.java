package com.adaiadai.core.domain.trading.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DefaultTradingRuleEngine — 规则引擎单元测试（G-3 能力抽离）。
 * <p>
 * 覆盖：止损硬判定（R66 跌破止损位 / R68 未设置不硬判）、仓位硬判定（R81 上限 25%）、
 * 规则条目解析（rules.md 格式契约）。
 */
class DefaultTradingRuleEngineTest {

    private final DefaultTradingRuleEngine engine = new DefaultTradingRuleEngine();

    // ── 止损硬判定（R66）──

    @Test
    void evaluateStopLoss_priceBelowStopLoss_breached() {
        TradingRuleEngine.StopLossResult r = engine.evaluateStopLoss(
                new BigDecimal("4.80"), new BigDecimal("4.90"));
        assertEquals(StopLossVerdict.BREACHED, r.verdict());
        assertEquals("R66", r.ruleRef());
        assertTrue(r.message().contains("跌破止损位"));
    }

    @Test
    void evaluateStopLoss_priceAtOrAboveStopLoss_ok() {
        TradingRuleEngine.StopLossResult at = engine.evaluateStopLoss(
                new BigDecimal("4.90"), new BigDecimal("4.90"));
        assertEquals(StopLossVerdict.OK, at.verdict());

        TradingRuleEngine.StopLossResult above = engine.evaluateStopLoss(
                new BigDecimal("5.81"), new BigDecimal("4.90"));
        assertEquals(StopLossVerdict.OK, above.verdict());
        assertTrue(above.message().contains("未跌破"));
    }

    @Test
    void evaluateStopLoss_missingStopLoss_okWithoutVerdict() {
        // R68：止损位未设置 → 无据可判，不硬判（建议引擎已在买入时强制用户填写）
        TradingRuleEngine.StopLossResult r = engine.evaluateStopLoss(new BigDecimal("5.00"), null);
        assertEquals(StopLossVerdict.OK, r.verdict());
        assertTrue(r.message().contains("R68"));
    }

    // ── 仓位硬判定（R81）──

    @Test
    void evaluatePosition_over25Percent_overWeight() {
        TradingRuleEngine.PositionResult r = engine.evaluatePosition(new BigDecimal("42.30"));
        assertEquals(PositionVerdict.OVER_WEIGHT, r.verdict());
        assertEquals("R81", r.ruleRef());
        assertTrue(r.message().contains("超 R81 上限 25%"));
    }

    @Test
    void evaluatePosition_atOrUnder25Percent_ok() {
        TradingRuleEngine.PositionResult at = engine.evaluatePosition(new BigDecimal("25.00"));
        assertEquals(PositionVerdict.OK, at.verdict());

        TradingRuleEngine.PositionResult under = engine.evaluatePosition(new BigDecimal("18.50"));
        assertEquals(PositionVerdict.OK, under.verdict());
    }

    @Test
    void evaluatePosition_null_ok() {
        assertEquals(PositionVerdict.OK, engine.evaluatePosition(null).verdict());
    }

    // ── 规则解析 ──

    @Test
    void parseRules_extractsNumberTitleDetail() {
        String content = """
                **R66 只输一根K线（核心理念）**
                > 止损设在进场K线最低价下方几个价位（或1%），收盘跌破就走。永不套牢。

                **R81 100万以下分4-5个仓位**
                > 每次交易只用1/4到1/5仓位，单票止损控制在1%-5%。
                """;
        List<TradingRuleEngine.RuleEntry> rules = engine.parseRules(content);
        assertEquals(2, rules.size());
        assertEquals(66, rules.get(0).number());
        assertEquals("只输一根K线（核心理念）", rules.get(0).title());
        assertTrue(rules.get(0).detail().contains("收盘跌破就走"));
        assertEquals(81, rules.get(1).number());
    }

    @Test
    void parseRules_nullOrBlank_empty() {
        assertTrue(engine.parseRules(null).isEmpty());
        assertTrue(engine.parseRules("  ").isEmpty());
        assertTrue(engine.parseRules("没有规则格式的文本").isEmpty());
    }
}
