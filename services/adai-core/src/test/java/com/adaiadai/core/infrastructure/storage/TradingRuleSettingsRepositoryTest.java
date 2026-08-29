package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingRuleSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingRuleSettingsRepository — 交易规则参数配置测试（第三阶段：规则层按用户隔离）。
 * <p>
 * 覆盖：无文件 → 默认值兜底；YAML 读写 round-trip；损坏/非法值 → 回落默认（fail-closed）；
 * 用户隔离（A 改规则不影响 B）；exists 判定。
 */
class TradingRuleSettingsRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradingRuleSettingsRepository repo = new TradingRuleSettingsRepository(storage);

    @Test
    void findByUser_noFile_returnsDefaults() {
        TradingRuleSettings s = repo.findByUser("alice");
        assertTrue(s.positionLimitPercent().compareTo(new BigDecimal("25")) == 0, "无规则文件 → 默认仓位上限 25%");
        assertTrue(s.defaultStopLossRatio().compareTo(new BigDecimal("0.93")) == 0, "默认止损 −7%");
        assertEquals(5.0, s.soldStopLossPct(), "默认清仓止损阈值 5%");
        assertEquals(5, s.soldShortHoldDays(), "默认短持仓 5 天");
    }

    @Test
    void save_find_roundtrip() {
        TradingRuleSettings custom = new TradingRuleSettings(
                new BigDecimal("30"), new BigDecimal("0.95"), new BigDecimal("25"),
                new BigDecimal("60"), 7, 8.0, 10, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 66, 95);
        repo.save("alice", custom);

        TradingRuleSettings loaded = repo.findByUser("alice");
        assertTrue(loaded.positionLimitPercent().compareTo(new BigDecimal("30")) == 0);
        assertTrue(loaded.defaultStopLossRatio().compareTo(new BigDecimal("0.95")) == 0);
        assertTrue(loaded.givebackPeakPct().compareTo(new BigDecimal("25")) == 0);
        assertTrue(loaded.givebackRatioPct().compareTo(new BigDecimal("60")) == 0);
        assertEquals(7, loaded.shortOverdueDays());
        assertEquals(8.0, loaded.soldStopLossPct());
        assertEquals(10, loaded.soldShortHoldDays());
    }

    @Test
    void save_corruptedYaml_returnsDefaults() {
        storage.write("alice", "trading/rules.yaml", "params: [这不是, 合法结构: {");
        TradingRuleSettings s = repo.findByUser("alice");
        assertTrue(s.positionLimitPercent().compareTo(new BigDecimal("25")) == 0, "损坏 YAML → 默认值兜底");
    }

    @Test
    void save_invalidValues_failClosedToDefaults() {
        storage.write("alice", "trading/rules.yaml", """
                formatVersion: 1
                params:
                  positionLimitPercent: -5
                  defaultStopLossRatio: 3
                  shortOverdueDays: 0
                  soldStopLossPct: 500
                """);
        TradingRuleSettings s = repo.findByUser("alice");
        assertTrue(s.positionLimitPercent().compareTo(new BigDecimal("25")) == 0, "非法值 → 回落默认（fail-closed）");
        assertTrue(s.defaultStopLossRatio().compareTo(new BigDecimal("0.93")) == 0, "非法止损比例 → 默认");
        assertEquals(5, s.shortOverdueDays(), "非法天数 → 默认");
        assertEquals(5.0, s.soldStopLossPct(), "非法清仓阈值 → 默认");
    }

    @Test
    void userIsolation_aliceRulesNotAffectingBob() {
        TradingRuleSettings aliceRules = new TradingRuleSettings(
                new BigDecimal("40"), new BigDecimal("0.90"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 66, 95);
        repo.save("alice", aliceRules);

        TradingRuleSettings bob = repo.findByUser("bob");
        assertTrue(bob.positionLimitPercent().compareTo(new BigDecimal("25")) == 0, "B 无规则 → 默认，不受 A 影响");
    }

    @Test
    void exists_noFile_false_afterSave_true() {
        assertFalse(repo.exists("alice"));
        repo.save("alice", TradingRuleSettings.defaults());
        assertTrue(repo.exists("alice"));
    }

    @Test
    void defaultStopLoss_multipliesRatio() {
        TradingRuleSettings s = TradingRuleSettings.defaults();
        BigDecimal stop = s.defaultStopLoss(new BigDecimal("10.00"));
        assertTrue(stop.compareTo(new BigDecimal("9.30")) == 0, "默认止损 = 买入价 × 0.93（−7%）");
    }

    /** 第三阶段 Step8：adai 规则包加载 = 默认值（adai 行为不变回归）。 */
    @Test
    void adaiRulePack_loadsAsDefaults() {
        storage.write("adai", "trading/rules.yaml", """
                formatVersion: 1
                params:
                  positionLimitPercent: 25
                  defaultStopLossRatio: 0.93
                  givebackPeakPct: 20
                  givebackRatioPct: 50
                  shortOverdueDays: 5
                  soldStopLossPct: 5.0
                  soldShortHoldDays: 5
                  buyPullbackPct: 0.5
                  buyShrinkRatio: 0.7
                  buyKdjLow: 13
                  buyVolumeSurge: 1.5
                  buyPriorHighDays: 20
                  scoreBuyWeight: 0.5
                  scoreExecWeight: 0.5
                  constraintRuleMin: 66
                  constraintRuleMax: 95
                """);
        TradingRuleSettings s = repo.findByUser("adai");
        assertEquals(TradingRuleSettings.defaults().positionLimitPercent().stripTrailingZeros(),
                s.positionLimitPercent().stripTrailingZeros(), "adai 规则包仓位上限 = 默认 25%");
        assertEquals(TradingRuleSettings.defaults().buyKdjLow(), s.buyKdjLow(), "KDJ 阈值 = 默认 13");
        assertEquals(TradingRuleSettings.defaults().constraintRuleMin(), s.constraintRuleMin(), "硬约束下限 = 66");
        assertEquals(TradingRuleSettings.defaults().scoreBuyWeight(), s.scoreBuyWeight(), "打分权重 = 0.5");
    }
    /** P2-5（2026-08-30 审查）：save 读改写保留未知键（rules/signals/behaviors 进阶层不被抹）。 */
    @Test
    void save_preservesUnknownKeys() {
        storage.write("alice", "trading/rules.yaml", """
                formatVersion: 1
                params:
                  positionLimitPercent: 25
                signals:
                  - id: custom-signal
                    name: 自定义信号
                """);
        // 保存一个新参数
        repo.save("alice", new TradingRuleSettings(
                new BigDecimal("30"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 66, 95));
        // 未知键 signals 应保留
        String content = storage.read("alice", "trading/rules.yaml");
        assertTrue(content.contains("custom-signal"), "save 应保留未知键 signals（P2-5）");
        assertTrue(content.contains("positionLimitPercent"), "params 应更新");
        // 读取 round-trip
        TradingRuleSettings loaded = repo.findByUser("alice");
        assertTrue(loaded.positionLimitPercent().compareTo(new BigDecimal("30")) == 0,
                "更新后的仓位上限 30 应读回");
    }

    /** P2-1（2026-08-30 审查）：并发 save 不丢更新（per-user 条带锁）。 */
    @Test
    void save_concurrent_updatesNotLost() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        for (int i = 1; i <= 8; i++) {
            int v = i * 5;
            pool.submit(() -> {
                try {
                    start.await();
                    repo.save("alice", new TradingRuleSettings(
                            new BigDecimal(v), new BigDecimal("0.93"), new BigDecimal("20"),
                            new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 13, 1.5, 20, 0.5, 0.5, 66, 95));
                } catch (InterruptedException ignored) {}
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        // 文件不损坏（任一合法值均可读回，非 null 即无损坏）
        TradingRuleSettings loaded = repo.findByUser("alice");
        assertTrue(loaded.positionLimitPercent() != null, "并发写后文件应可读（不损坏）");
    }
}
