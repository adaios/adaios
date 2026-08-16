package com.adaiadai.core.domain.trading.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuleKnowledgeContractTest — 引擎口径与知识真相源契约测试（FP-S2 / B44）。
 * <p>
 * 目的：TradingRuleEngine 的判定口径（R66 止损 / R81 仓位）声称与 {@code os/trading-engine/knowledge/context/rules.md}
 * 一致——若知识侧修改关键语义（如 R66 不再是"收盘跌破"、R81 不再是"1/4~1/5"），
 * 引擎不会感知。本测试读真实 rules.md 断言关键判定词，知识变更即击穿，提醒同步引擎与规格。
 * <p>
 * 依赖：monorepo 真实知识文件（test cwd = services/adai-core，路径 ../../os/trading-engine/knowledge/context）。
 */
class RuleKnowledgeContractTest {

    private static final Path RULES_PATH = Paths.get("../../os/trading-engine/knowledge/context/rules.md")
            .toAbsolutePath().normalize();

    private String extractRule(String content, String ruleNo) {
        int start = content.indexOf("**" + ruleNo + " ");
        if (start < 0) return null;
        int end = content.indexOf("**R", start + ruleNo.length());
        if (end < 0) end = content.length();
        return content.substring(start, end);
    }

    private String readRules() throws IOException {
        return Files.readString(RULES_PATH, StandardCharsets.UTF_8);
    }

    @Test
    void rulesFile_exists() throws IOException {
        assertTrue(Files.isReadable(RULES_PATH), "rules.md 应存在（knowledge/context 迁移后路径）");
    }

    @Test
    void r66_mustRetainClosingBreakKeyword() throws IOException {
        // 引擎 evaluateStopLoss 声称口径=R66「收盘跌破就走」（现价近似）；语义变更须同步引擎
        String r66 = extractRule(readRules(), "R66");
        assertNotNull(r66, "R66 规则条目应存在");
        assertTrue(r66.contains("收盘跌破"), "R66 应含「收盘跌破」关键词（引擎口径依据）");
    }

    @Test
    void r81_mustRetainPositionRatio() throws IOException {
        // 引擎 evaluatePosition 上限 25% 依据 R81「1/4到1/5仓位」；比例变更须同步引擎
        String r81 = extractRule(readRules(), "R81");
        assertNotNull(r81, "R81 规则条目应存在");
        assertTrue(r81.contains("1/4") && r81.contains("1/5"),
                "R81 应含「1/4~1/5」仓位比例（引擎 25% 上限依据）");
    }
}
