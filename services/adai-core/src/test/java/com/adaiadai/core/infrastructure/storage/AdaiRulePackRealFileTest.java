package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingRuleSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdaiRulePackRealFileTest — 验证 adai 规则包格式（全默认值）可被加载。
 * <p>
 * P2-8（2026-08-30 审查）：原测试读真实 data/adai/trading/rules.yaml——data/ 被 gitignore、
 * 规则包生成无脚本入库，新 clone/CI 环境无规则包即红（环境依赖隐式）。
 * 改为**测试内构造等价内容**（与 TradingRuleSettingsRepositoryTest 同风格），
 * 真实文件验证移入一次性校验脚本（见 ai-engineering/assets/projects/adai-core.md）。
 */
class AdaiRulePackRealFileTest {

    @Test
    void adaiRulePack_fullDefaults_parsesCorrectly() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        // 构造等价于 data/adai/trading/rules.yaml 的内容（全默认值 = 原硬编码，adai 行为不变）
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
        TradingRuleSettingsRepository repo = new TradingRuleSettingsRepository(storage);
        TradingRuleSettings s = repo.findByUser("adai");
        // 规则包 = 默认值：仓位上限 25 / KDJ 13 / 硬约束 66-95（adai 行为不变回归）
        assertTrue(s.positionLimitPercent().compareTo(new BigDecimal("25")) == 0,
                "adai 规则包 positionLimitPercent = 25，实际 " + s.positionLimitPercent());
        assertTrue(s.buyKdjLow() == 13, "KDJ 阈值 = 13");
        assertTrue(s.constraintRuleMin() == 66 && s.constraintRuleMax() == 95,
                "硬约束区间 66-95");
        assertTrue(repo.exists("adai"), "规则包文件存在");
    }
}
