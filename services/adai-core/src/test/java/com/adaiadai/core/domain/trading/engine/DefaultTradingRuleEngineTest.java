package com.adaiadai.core.domain.trading.engine;

import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * DefaultTradingRuleEngine — 规则引擎单元测试（G-3 能力抽离）。
 * <p>
 * 覆盖：止损硬判定（R66 跌破止损位 / R68 未设置不硬判）、仓位硬判定（R81 上限 25%，
 * 第三阶段：按用户规则配置可调）、规则条目解析（rules.md 格式契约）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultTradingRuleEngineTest {

    @Mock
    private TradingRuleSettingsRepository settingsRepository;

    private DefaultTradingRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultTradingRuleEngine(settingsRepository);
        // 默认：无规则配置 → 默认值兜底（lenient：部分测试不触发 evaluatePosition）
        org.mockito.Mockito.lenient()
                .when(settingsRepository.findByUser("adai")).thenReturn(TradingRuleSettings.defaults());
    }

    // ── 止损硬判定（R66）──

    @Test
    void evaluateStopLoss_priceBelowStopLoss_breached() {
        TradingRuleEngine.StopLossResult r = engine.evaluateStopLoss(
                "adai", new BigDecimal("4.80"), new BigDecimal("4.90"));
        assertEquals(StopLossVerdict.BREACHED, r.verdict());
        assertEquals("R66", r.ruleRef());
        assertTrue(r.message().contains("跌破止损位"));
    }

    @Test
    void evaluateStopLoss_priceAtOrAboveStopLoss_ok() {
        TradingRuleEngine.StopLossResult at = engine.evaluateStopLoss(
                "adai", new BigDecimal("4.90"), new BigDecimal("4.90"));
        assertEquals(StopLossVerdict.OK, at.verdict());

        TradingRuleEngine.StopLossResult above = engine.evaluateStopLoss(
                "adai", new BigDecimal("5.81"), new BigDecimal("4.90"));
        assertEquals(StopLossVerdict.OK, above.verdict());
        assertTrue(above.message().contains("未跌破"));
    }

    @Test
    void evaluateStopLoss_missingStopLoss_okWithoutVerdict() {
        // R68：止损位未设置 → 无据可判，不硬判（建议引擎已在买入时强制用户填写）
        TradingRuleEngine.StopLossResult r = engine.evaluateStopLoss("adai", new BigDecimal("5.00"), null);
        assertEquals(StopLossVerdict.OK, r.verdict());
        assertTrue(r.message().contains("R68"));
    }

    @Test
    void evaluateStopLoss_unavailablePrice_okWithoutVerdict() {
        // FP-P2c：现价不可用（null / ≤0）→ 无据可判，不硬判（防误触发 clear）
        assertEquals(StopLossVerdict.OK, engine.evaluateStopLoss("adai", null, new BigDecimal("4.90")).verdict());
        assertEquals(StopLossVerdict.OK, engine.evaluateStopLoss("adai", BigDecimal.ZERO, new BigDecimal("4.90")).verdict());
        assertEquals(StopLossVerdict.OK, engine.evaluateStopLoss("adai", new BigDecimal("-1"), new BigDecimal("4.90")).verdict());
    }

    // ── 仓位硬判定（R81）──

    @Test
    void evaluatePosition_over25Percent_overWeight() {
        TradingRuleEngine.PositionResult r = engine.evaluatePosition("adai", new BigDecimal("42.30"));
        assertEquals(PositionVerdict.OVER_WEIGHT, r.verdict());
        assertEquals("R81", r.ruleRef());
        assertTrue(r.message().contains("仓位上限"), "文案应含仓位上限（P2-7 区分用户/默认），实际: " + r.message());
    }

    @Test
    void evaluatePosition_atOrUnder25Percent_ok() {
        TradingRuleEngine.PositionResult at = engine.evaluatePosition("adai", new BigDecimal("25.00"));
        assertEquals(PositionVerdict.OK, at.verdict());

        TradingRuleEngine.PositionResult under = engine.evaluatePosition("adai", new BigDecimal("18.50"));
        assertEquals(PositionVerdict.OK, under.verdict());
    }

    @Test
    void evaluatePosition_null_ok() {
        assertEquals(PositionVerdict.OK, engine.evaluatePosition("adai", null).verdict());
    }

    /** 第三阶段：用户规则配置可调仓位上限（rules.yaml positionLimitPercent）。 */
    @Test
    void evaluatePosition_userRuleLimit30_overWeightAt28() {
        when(settingsRepository.findByUser("alice")).thenReturn(new TradingRuleSettings(
                new BigDecimal("30"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 66, 95));
        // 28% > 用户上限 30%？否——30% 上限下 28% 合规
        assertEquals(PositionVerdict.OK,
                engine.evaluatePosition("alice", new BigDecimal("28")).verdict());
        // 32% > 30% 上限 → OVER_WEIGHT
        assertEquals(PositionVerdict.OVER_WEIGHT,
                engine.evaluatePosition("alice", new BigDecimal("32")).verdict());
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
