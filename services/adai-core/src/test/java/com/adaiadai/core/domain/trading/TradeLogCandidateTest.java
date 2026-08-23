package com.adaiadai.core.domain.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradeLogCandidate.sameTrade — B6-2（2026-08-23，P1-交易12）回归：
 * ±10% 区间去重——旧固定 10 股桶过宽吞笔（10 vs 19）/过窄漏去重（100 vs 110）双缺陷。
 */
class TradeLogCandidateTest {

    private TradeLogCandidate c(String symbol, String dir, Integer volume) {
        return new TradeLogCandidate(symbol, symbol + "名", dir,
                volume != null ? new BigDecimal("10.0") : null, volume, "text",
                volume != null);
    }

    @Test
    void sameTrade_volumeWithin10pct_merge() {
        // OCR 波动 100/105/109 → ±10% 内同笔（旧 10 股桶 100/105 同、109 不同 → 漏去重）
        assertTrue(c("000725", "BUY", 100).sameTrade(c("000725", "BUY", 105)), "100 vs 105 差 5% 应同笔");
        assertTrue(c("000725", "BUY", 100).sameTrade(c("000725", "BUY", 109)), "100 vs 109 差 9% 应同笔");
        assertTrue(c("000725", "BUY", 100).sameTrade(c("000725", "BUY", 110)), "100 vs 110 差 10% 边界应同笔");
    }

    @Test
    void sameTrade_volumeOver10pct_separate() {
        // 真实两笔不得互吞（旧 10 股桶 10 vs 19 同桶 → 吞笔）
        assertFalse(c("000725", "BUY", 10).sameTrade(c("000725", "BUY", 19)), "10 vs 19 差 90% 必须分开");
        assertFalse(c("000725", "BUY", 100).sameTrade(c("000725", "BUY", 120)), "100 vs 120 差 20% 必须分开");
        assertFalse(c("000725", "BUY", 100).sameTrade(c("000725", "BUY", 500)), "100 vs 500 不同量级必须分开");
    }

    @Test
    void sameTrade_differentSymbolOrDirection_separate() {
        assertFalse(c("000725", "BUY", 100).sameTrade(c("600519", "BUY", 100)), "不同代码分开");
        assertFalse(c("000725", "BUY", 100).sameTrade(c("000725", "SELL", 100)), "不同方向分开");
    }

    @Test
    void sameTrade_nullVolume_mergeByKey() {
        // 不完整候选（volume 缺失）→ 同 symbol+direction 视为同笔（与 dedupeKey 语义一致）
        assertTrue(c("000725", "BUY", null).sameTrade(c("000725", "BUY", null)), "双 null volume 同笔");
        assertTrue(c("000725", "BUY", null).sameTrade(c("000725", "BUY", 100)), "null vs 100 同笔（无据可比）");
    }

    @Test
    void sameTrade_nullObject_false() {
        assertFalse(c("000725", "BUY", 100).sameTrade(null));
    }
}
