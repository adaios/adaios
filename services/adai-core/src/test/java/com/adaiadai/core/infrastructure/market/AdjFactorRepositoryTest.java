package com.adaiadai.core.infrastructure.market;

import com.adaiadai.core.domain.trading.market.AdjustmentCalculator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdjFactorRepositoryTest — 除权因子（2026-08-30：TDX 数据正确性）。
 * <p>
 * 覆盖：方案文本解析（10派/10送转/组合/无比例跳过）、东财响应解析、解析失败空。
 */
class AdjFactorRepositoryTest {

    private final AdjFactorRepository repo = new AdjFactorRepository("/tmp/adj-test");

    @Test
    void parseProfile_cashOnly() {
        AdjustmentCalculator.AdjustmentEvent e =
                repo.parseProfile("10派280.2423元(含税)", LocalDate.of(2026, 6, 26));
        assertEquals(28.02423, e.cashPerShare(), 0.0001, "每股派息 = 280.24/10");
        assertEquals(0, e.sendPerShare());
        assertEquals(0, e.transferPerShare());
    }

    @Test
    void parseProfile_sendAndTransfer() {
        AdjustmentCalculator.AdjustmentEvent e =
                repo.parseProfile("10送4转6", LocalDate.of(2026, 5, 20));
        assertEquals(0.4, e.sendPerShare());
        assertEquals(0.6, e.transferPerShare());
        assertEquals(0, e.cashPerShare());
    }

    @Test
    void parseProfile_mixed() {
        AdjustmentCalculator.AdjustmentEvent e =
                repo.parseProfile("10送2转3派1.5元", LocalDate.of(2026, 4, 10));
        assertEquals(0.2, e.sendPerShare());
        assertEquals(0.3, e.transferPerShare());
        assertEquals(0.15, e.cashPerShare());
    }

    @Test
    void parseProfile_noRatio_returnsNull() {
        assertEquals(null, repo.parseProfile("不分配", LocalDate.of(2026, 1, 1)),
                "无送转派 → null（不构成除权事件）");
    }

    @Test
    void parseApi_extractsEvents() {
        String body = "{\"result\":{\"data\":["
                + "{\"EX_DIVIDEND_DATE\":\"2026-06-26 00:00:00\",\"IMPL_PLAN_PROFILE\":\"10派280.2423元(含税)\"},"
                + "{\"EX_DIVIDEND_DATE\":\"2025-12-19 00:00:00\",\"IMPL_PLAN_PROFILE\":\"10派239.57元\"}"
                + "]}}";
        List<AdjustmentCalculator.AdjustmentEvent> events = repo.parseApi(body);
        assertEquals(2, events.size());
        assertEquals(LocalDate.of(2026, 6, 26), events.get(0).exDate());
        assertEquals(28.02423, events.get(0).cashPerShare(), 0.0001);
        assertEquals(23.957, events.get(1).cashPerShare(), 0.0001);
    }

    @Test
    void parseApi_garbage_returnsEmpty() {
        assertTrue(repo.parseApi("not json").isEmpty());
        assertTrue(repo.parseApi(null).isEmpty());
        assertTrue(repo.parseApi("{\"result\":{\"data\":[]}}").isEmpty());
    }
}
