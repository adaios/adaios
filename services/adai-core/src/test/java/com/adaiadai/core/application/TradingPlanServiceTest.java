package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingPlan;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradingPlanFileRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingPlanService — 次日操作计划（RFC 20261003-trading-plan-and-review-loop §二~四，2026-10-03）。
 *
 * <p>定位：系统只「记你的话 · 到点提醒 · 收盘对账」，**不生成计划、不给建议**。
 * 一句话解析刻意**不接 LLM**（计划是纪律承诺，解析错了比解析慢更糟）。
 */
class TradingPlanServiceTest {

    private TradingPlanService service(InMemoryFileStorage fs, List<TradeRecord> flow, List<Candle> candles) {
        TradingPlanFileRepository repo = new TradingPlanFileRepository(fs);
        TradingHistoryRepository hist = mock(TradingHistoryRepository.class);
        when(hist.findAll(any())).thenReturn(flow);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(candles);
        return new TradingPlanService(repo, hist, kline);
    }

    /** 一句话 → 卖出 + 下方条件价。 */
    @Test
    void parseItem_sellWithDownsideCondition() {
        TradingPlanService svc = service(new InMemoryFileStorage(), List.of(), List.of());

        TradingPlan.PlanItem it = svc.parseItem("600206 跌破 45.5 清仓");

        assertEquals("SELL", it.action());
        assertEquals("600206", it.symbol());
        assertEquals("LT", it.condOp());
        assertEquals(0, it.condPrice().compareTo(new BigDecimal("45.5")));
        assertEquals("600206 跌破 45.5 清仓", it.text(), "必须原样保留用户的话，供提醒时引用");
    }

    /** 一句话 → 买入 + 数量（「500 股」）。 */
    @Test
    void parseItem_buyWithQuantity() {
        TradingPlanService svc = service(new InMemoryFileStorage(), List.of(), List.of());

        TradingPlan.PlanItem it = svc.parseItem("000776 回到 19.5 以下买 500 股");

        assertEquals("BUY", it.action());
        assertEquals(0, it.condPrice().compareTo(new BigDecimal("19.5")));
        assertEquals(500, it.quantity());
    }

    /** 「明天不动」是合法计划（空仓也是一种决定），方向识别为 HOLD。 */
    @Test
    void parseItem_holdIsValidPlan() {
        TradingPlanService svc = service(new InMemoryFileStorage(), List.of(), List.of());
        assertEquals("HOLD", svc.parseItem("明天不动").action());
    }

    /** 写 → 读 往返（File First：JSON 落盘，日期为文件名）。 */
    @Test
    void saveThenFind_roundTrip() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        TradingPlanService svc = service(fs, List.of(), List.of());
        LocalDate d = LocalDate.of(2026, 10, 8);

        svc.saveFromLines("default", d, List.of("600206 跌破 45.5 清仓", "明天不动"), "只做计划内的票");

        TradingPlan got = svc.find("default", d).orElseThrow();
        assertEquals(2, got.items().size());
        assertEquals("只做计划内的票", got.note());
        assertEquals(List.of(d), svc.dates("default"));
    }

    /** 对账：条件触发（当日最低 45.0 ≤ 45.5）+ 已执行（当日有该标的卖出）。 */
    @Test
    void review_triggeredAndExecuted() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        LocalDate d = LocalDate.of(2026, 10, 8);
        List<TradeRecord> flow = List.of(
                TradeRecord.of("t1", "600206", "有研新材", TradeDirection.SELL, new BigDecimal("45.20"), 800,
                        d, null, null, null, null, null, null, LocalDateTime.now(), null, "oid1"));
        List<Candle> candles = List.of(new Candle(d, 46.0, 46.5, 45.0, 45.2, 100));
        TradingPlanService svc = service(fs, flow, candles);
        svc.saveFromLines("default", d, List.of("600206 跌破 45.5 清仓"), "");

        TradingPlanService.PlanReview r = svc.review("default", d);

        assertTrue(r.hasPlan());
        assertEquals(1, r.triggeredCount(), "当日最低 45.0 ≤ 45.5 → 必须判为已触发");
        assertEquals(1, r.executedCount(), "当日有 600206 卖出 → 必须判为已执行");
        assertEquals(Boolean.TRUE, r.items().get(0).triggered());
        assertEquals(Boolean.TRUE, r.items().get(0).executed());
    }

    /** 对账：**计划外成交**——当日有成交但该标的根本不在计划里（R96 四不原则的正面检查）。 */
    @Test
    void review_unplannedTradeIsListed() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        LocalDate d = LocalDate.of(2026, 10, 8);
        List<TradeRecord> flow = List.of(
                TradeRecord.of("t1", "600206", "有研新材", TradeDirection.SELL, new BigDecimal("45.20"), 800,
                        d, null, null, null, null, null, null, LocalDateTime.now(), null, "oid1"),
                TradeRecord.of("t2", "000831", "中国稀土", TradeDirection.BUY, new BigDecimal("52.00"), 200,
                        d, null, null, null, null, null, null, LocalDateTime.now(), null, "oid2"));
        TradingPlanService svc = service(fs, flow,
                List.of(new Candle(d, 46.0, 46.5, 45.0, 45.2, 100)));
        svc.saveFromLines("default", d, List.of("600206 跌破 45.5 清仓"), "");

        TradingPlanService.PlanReview r = svc.review("default", d);

        assertEquals(1, r.unplanned().size(), "000831 不在计划里 → 必须报为计划外");
        assertTrue(r.unplanned().get(0).contains("000831"), r.unplanned().toString());
    }

    /** 没写计划的日期：hasPlan=false（**不编造空计划**），但计划外成交照报。 */
    @Test
    void review_noPlan_hasPlanFalse() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        LocalDate d = LocalDate.of(2026, 10, 9);
        TradingPlanService svc = service(fs, List.of(), List.of());

        TradingPlanService.PlanReview r = svc.review("default", d);

        assertFalse(r.hasPlan());
        assertTrue(r.items().isEmpty());
    }

    /**
     * P1-6（独立审查 2026-10-03 修复）：「跌到 X」是**下方**条件。
     * 原来 isDownside 不认「到」，实测被判成上方（GE）→ 触发判定**反转**。
     */
    @Test
    void parseItem_daoXia_isDownsideCondition() {
        TradingPlanService svc = service(new InMemoryFileStorage(), List.of(), List.of());

        TradingPlan.PlanItem it = svc.parseItem("跌到 5.5 我买 500 股");

        assertEquals("LT", it.condOp(), "「跌到」必须是下方条件，实际: " + it.condOp());
        assertEquals(0, it.condPrice().compareTo(new BigDecimal("5.5")));
    }

    /** 「涨到 X」仍必须是上方条件（与上一条互为反向，防改过头）。 */
    @Test
    void parseItem_zhangDao_isUpsideCondition() {
        TradingPlanService svc = service(new InMemoryFileStorage(), List.of(), List.of());
        assertEquals("GE", svc.parseItem("涨到 95 减半").condOp());
    }

    /**
     * P1-7（独立审查 2026-10-03 修复）：「没听懂」不再等于「明确不动」。
     * 原来无方向词回落 HOLD → review 里静默不参与判定，用户永远不知道这条没被理解。
     */
    @Test
    void parseItem_unrecognizedDirection_isUnknownNotHold() {
        TradingPlanService svc = service(new InMemoryFileStorage(), List.of(), List.of());

        assertEquals("UNKNOWN", svc.parseItem("600206 看着办吧").action(), "听不懂就如实说听不懂");
        assertEquals("HOLD", svc.parseItem("明天不动").action(), "用户明确写的不动仍是 HOLD");
        assertEquals("HOLD", svc.parseItem("600206 不加仓").action(), "否定词不是买入信号");
    }
}
