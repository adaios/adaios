package com.adaiadai.core.domain.trading;

import com.adaiadai.core.domain.trading.TradingProfileService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingProfileContributor — 个人画像上下文贡献者测试（RFC 20260905 A 层）。
 * <p>
 * 覆盖：trading/decision 场景注入画像、非交易场景 enrich 返回空、全局摘要存在。
 */
class TradingProfileContributorTest {

    private TradingProfileContributor contributor(String profileText) {
        TradingProfileService profile = mock(TradingProfileService.class);
        when(profile.profileText(anyString())).thenReturn(profileText);
        return new TradingProfileContributor(profile);
    }

    @Test
    void supports_tradingAndDecision_true() {
        TradingProfileContributor c = contributor("x");
        assertTrue(c.supports("trading"));
        assertTrue(c.supports("decision"));
        assertFalse(c.supports("life"));
        assertFalse(c.supports("project"));
    }

    @Test
    void enrich_tradingScene_returnsProfile() {
        TradingProfileContributor c = contributor("## 你的交易画像\n胜率 33%");
        String text = c.enrich("default", "ref", null);
        assertTrue(text.contains("你的交易画像"));
        assertTrue(text.contains("胜率 33%"));
    }

    @Test
    void enrich_noProfile_returnsEmpty() {
        TradingProfileContributor c = contributor("");
        assertEquals("", c.enrich("default", "ref", null));
    }

    @Test
    void globalContext_mentionsProfileAvailability_whenProfileExists() {
        TradingProfileContributor c = contributor("## 画像\n胜率 33%");
        assertTrue(c.globalContext("default").contains("交易风格与历史"),
                "有画像数据 → 可声称了解用户");
    }

    @Test
    void globalContext_empty_whenNoProfile() {
        // 💥4（2026-09-05 对抗审）：无画像数据不得声称了解用户（防 AI 编造「你上次…」幻觉）
        TradingProfileContributor c = contributor("");
        assertEquals("", c.globalContext("default"), "无画像 → 不注入「了解你」声明");
    }
}
