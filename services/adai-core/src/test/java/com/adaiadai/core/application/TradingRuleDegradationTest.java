package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.BuyPointDetector;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeVerdict;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingLot;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.engine.DefaultTradingRuleEngine;
import com.adaiadai.core.domain.trading.engine.PositionVerdict;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingRuleDegradationTest — 无规则用户降级验证（第三阶段，D6 决策）。
 * <p>
 * 无规则用户（无 data/{userId}/trading/rules.yaml）交易功能必须**不坏**：
 * 所有判定回落默认值（= adai 现有行为），只出客观数据、不掺个性化判断。
 * 这是「每个人有自己的交易系统，但规则可选」的核心保证。
 */
class TradingRuleDegradationTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradingRuleSettingsRepository settingsRepository =
            new TradingRuleSettingsRepository(storage);

    // ── 1. 引擎降级：无规则 → 默认仓位上限 25% ──

    @Test
    void ruleEngine_noRuleUser_usesDefaultPositionLimit() {
        DefaultTradingRuleEngine engine = new DefaultTradingRuleEngine(settingsRepository);
        // 无规则文件 → 25% 上限（与 adai 默认一致）
        assertEquals(PositionVerdict.OVER_WEIGHT,
                engine.evaluatePosition("bob", new BigDecimal("30")).verdict(),
                "无规则用户 30% > 默认 25% → OVER_WEIGHT（降级=默认行为）");
        assertEquals(PositionVerdict.OK,
                engine.evaluatePosition("bob", new BigDecimal("20")).verdict());
    }

    // ── 2. 行为标注降级：无规则 → 默认阈值（浮盈回吐 20%/50%、短线 5 天）──

    @Test
    void behaviorNotes_noRuleUser_usesDefaults() {
        // 构造：买 10 元持有 → K 线峰值 12（+20%）→ 现价 10.8（回吐 40% < 50% → 默认不标）
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(List.of(
                new Candle(buyDate.plusDays(1), 10.2, 12.0, 10.1, 11.8, 1000)));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("bob")).thenReturn(List.of(
                TradeRecord.of("t1", "600000", "浦发银行", TradeDirection.BUY,
                        new BigDecimal("10.0"), 1000, buyDate, LocalTime.of(10, 0),
                        new BigDecimal("9.0"), "B1", null, null, null,
                        LocalDateTime.of(buyDate, LocalTime.of(10, 0)), null, null)));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll("bob")).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(anyList())).thenReturn(Map.of(
                "600000", new MarketData("600000", "浦发银行", new BigDecimal("10.8"),
                        new BigDecimal("11.0"), new BigDecimal("11.5"), new BigDecimal("11.5"),
                        new BigDecimal("10.8"), new BigDecimal("-2"), 1000)));
        TradingLotService lotService = new TradingLotService(history, positions, market, kline, settingsRepository);

        List<TradingLotService.BehaviorNote> notes = lotService.analyzeBehaviors("bob", today);
        // 默认回吐阈值 50%：回吐 40% 不标；峰值 20% 达线但回吐不够 → giveback 不触发
        assertFalse(notes.stream().anyMatch(n -> "giveback".equals(n.type())),
                "无规则用户默认阈值：回吐 40% < 50% → 不标浮盈回吐（降级=默认行为）");
    }

    // ── 3. 清仓 verdict 降级：无规则 → 默认 -5%/5 天 ──

    @Test
    void soldVerdict_noRuleUser_usesDefaults() {
        String v = SoldTradeVerdict.compute(-8.0, 3);
        assertTrue(v.contains("R66"), "无规则用户亏 8% → 默认判 R66 扛单（与 adai 一致）");
        assertTrue(SoldTradeVerdict.compute(-3.0, 30).contains("R53"), "久持小亏 → R53 延展");
    }

    // ── 4. 买点信号降级：无规则 → 默认参数 ──

    @Test
    void buyPoint_noRuleUser_usesDefaultDetector() {
        // 26 根 K 线（前高 10 → 缩量回调到 4.8 → KDJ 低位）→ 默认参数命中 B1
        // 构造与 SoldScoreServiceTest.b1Candles 同款（已验证默认参数可命中）
        LocalDate buyDate = LocalDate.of(2026, 8, 1);
        java.util.List<Candle> cs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cs.add(new Candle(buyDate.minusDays(25 - i), 9, 10, 10, 9, 100_000));
        }
        for (int i = 0; i < 14; i++) {
            double close = 9.2 - i * 0.24;
            cs.add(new Candle(buyDate.minusDays(19 - i), close, close + 0.1, close + 0.2, close - 0.1, 30_000));
        }
        double[] v = {6000, 4000, 2000, 1000};
        for (int i = 0; i < 4; i++) {
            double close = 5.84 - i * 0.2;
            cs.add(new Candle(buyDate.minusDays(5 - i), close, close + 0.1, close + 0.2, close - 0.1, v[i]));
        }
        cs.add(new Candle(buyDate, 4.8, 5, 5.1, 4.7, 1_000));
        BuyPointDetector detector = new BuyPointDetector(0.5, 0.7, 13, 1.5, 20);
        BuyPointDetector.BuyPointResult r = detector.detect(cs);
        assertTrue(r.hit(), "无规则用户默认买点参数应命中 B1（降级=默认行为）");
    }

    // ── 5. 用户规则覆盖生效（对照：有规则用户 ≠ 无规则）──

    @Test
    void ruleFile_changesBehavior() {
        // 用户 bob 写规则：仓位上限 40% → 30% 不再超仓
        settingsRepository.save("bob", new com.adaiadai.core.domain.trading.TradingRuleSettings(
                new BigDecimal("40"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 66, 95));
        DefaultTradingRuleEngine engine = new DefaultTradingRuleEngine(settingsRepository);
        assertEquals(PositionVerdict.OK,
                engine.evaluatePosition("bob", new BigDecimal("30")).verdict(),
                "用户规则 40% 上限：30% 合规（规则生效）");
        assertEquals(PositionVerdict.OVER_WEIGHT,
                engine.evaluatePosition("bob", new BigDecimal("45")).verdict());
    }

    // ── 6. P1-3（2026-08-30 审查）：多用户知识泄漏回归 ──

    @Test
    void knowledgeSource_nonOwner_noOsKnowledgeInjected() {
        // 非 owner（bob）无私有 knowledge.md → 不注入 os/ adai 知识（B3 红线）
        com.adaiadai.core.kernel.knowledge.TradingKnowledgeSource ks =
                new com.adaiadai.core.kernel.knowledge.TradingKnowledgeSource(
                        "../../os/trading-engine/knowledge/context", storage, "adai");
        // bob 无 knowledge.md 文件
        assertEquals("", ks.enrich("bob", "trading"), "非 owner 无私有知识 → 不注入 os/ adai 知识");
        assertEquals("", ks.globalContext("bob"), "非 owner globalContext 也不注入 adai identity");
    }

    @Test
    void knowledgeSource_owner_fallsBackToOs() {
        // owner（adai）无私有知识文件（本测试 storage 空）→ 回落 os/ 全局知识（行为不变）
        com.adaiadai.core.kernel.knowledge.TradingKnowledgeSource ks =
                new com.adaiadai.core.kernel.knowledge.TradingKnowledgeSource(
                        "../../os/trading-engine/knowledge/context", storage, "adai");
        String enriched = ks.enrich("adai", "trading");
        assertTrue(enriched.contains("交易系统知识"), "owner 无私有知识 → 回落 os/ 知识（adai 行为不变）");
    }

    // ── 7. P0-2（2026-08-30 审查）：硬约束区间限制回归 ──

    @Test
    void constraintRange_limitedToR66to95_shrinkOnly() {
        // 允许在 R66-R95 内收缩（如 70-90 合法）
        TradingRuleSettings s1 = new TradingRuleSettings(
                new BigDecimal("25"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 70, 90);
        assertEquals(70, s1.constraintRuleMin());
        assertEquals(90, s1.constraintRuleMax());
        // 越界（min=200）→ 一起回落默认 66/95（不再产生空区间 fail-open）
        TradingRuleSettings s2 = new TradingRuleSettings(
                new BigDecimal("25"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 200, 95);
        assertEquals(66, s2.constraintRuleMin(), "min=200 越界 → 回落默认 66（防空区间 fail-open）");
        assertEquals(95, s2.constraintRuleMax());
        // min>max（倒挂）→ 回落默认
        TradingRuleSettings s3 = new TradingRuleSettings(
                new BigDecimal("25"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 80, 70);
        assertEquals(66, s3.constraintRuleMin(), "min>max 倒挂 → 回落默认");
        assertEquals(95, s3.constraintRuleMax());
        // 越界（max=500）→ 回落默认
        TradingRuleSettings s4 = new TradingRuleSettings(
                new BigDecimal("25"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 66, 500);
        assertEquals(95, s4.constraintRuleMax(), "max=500 越界 → 回落默认 95");
    }

    // ── 8. P2-2（2026-08-30 审查）：NaN/Infinity 回归 ──

    @Test
    void nanInfinity_values_fallBackToDefaults() {
        // NaN 穿透所有 <=/> 比较（NaN <= 0 恒 false）→ 必须显式 finite 校验回落默认
        TradingRuleSettings s = new TradingRuleSettings(
                new BigDecimal("25"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, Double.NaN, 5, 0.5, Double.POSITIVE_INFINITY, 13, 1.5, 20, 0.5, 0.5, 66, 95);
        assertEquals(5.0, s.soldStopLossPct(), "NaN 清仓阈值 → 回落默认 5.0");
        assertEquals(0.7, s.buyShrinkRatio(), "Infinity 缩量阈值 → 回落默认 0.7");
    }
}
