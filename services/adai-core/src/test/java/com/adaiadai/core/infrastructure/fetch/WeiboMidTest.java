package com.adaiadai.core.infrastructure.fetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WeiboMidTest — 微博 URL id → mid 换算（2026-09-13 平台抓取放开批）。
 * <p>
 * 样本对取自公开算法说明里的 doctest 用例（cnblogs「新浪微博mid和url的互算」）——
 * 这些是**外部给定的已知值**，所以能真正验证实现，而不是拿实现验证自己。
 * <p>
 * 为什么值得单独一个测试类：62 进制字母表顺序（{@code 0-9a-zA-Z} vs 常见的
 * {@code 0-9A-Za-z}）极易写错，而写错之后得到的是一个「看着合理但完全错误」的 mid ——
 * 接口返回空 → 表现为「微博抓不了」。这种 bug 从现象上会一路伪装成「平台反爬」。
 */
class WeiboMidTest {

    @Test
    void urlIdToMid_knownSamples_fromPublicDoctests() {
        assertEquals("3501756485200075", WeiboMid.urlIdToMid("z0JH2lOMb"));
        assertEquals("3501703397689247", WeiboMid.urlIdToMid("z0Ijpwgk7"));
        assertEquals("3501701648871479", WeiboMid.urlIdToMid("z0IgABdSn"));
        assertEquals("3500330408906190", WeiboMid.urlIdToMid("z08AUBmUe"));
        assertEquals("3500247231472384", WeiboMid.urlIdToMid("z06qL6b28"));
        assertEquals("3491700092079471", WeiboMid.urlIdToMid("yCtxn8IXR"));
        assertEquals("3486913690606804", WeiboMid.urlIdToMid("yAt1n2xRa"));
    }

    @Test
    void urlIdToMid_workedExample_fromArticleStepByStep() {
        // 原文逐步演算过的例子：z8ElgBLeQ → 分组 35 / 2061702 / 8999724 → 3520617028999724
        assertEquals("3520617028999724", WeiboMid.urlIdToMid("z8ElgBLeQ"));
    }

    @Test
    void urlIdToMid_illegalCharOrBlank_returnsNull_neverGuessing() {
        assertNull(WeiboMid.urlIdToMid("z8ElgBLeQ!"), "含字母表外字符 → 不猜，返回 null");
        assertNull(WeiboMid.urlIdToMid(null));
        assertNull(WeiboMid.urlIdToMid("   "));
    }
}
