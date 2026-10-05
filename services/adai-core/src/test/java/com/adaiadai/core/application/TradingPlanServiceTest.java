package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingException;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ── P2-交易72（2026-10-05）：当天事后的「今日状态」回填（今天没动 / 想动没动）──
    // 用户原话：「那我今天没有买卖 怎么告诉你呢 你还在等我的数据」——
    // 系统在等一个他没有地方填的状态。落点必须与既有计划**同一份记录**，且不互相吞字段。

    /**
     * 回填「今天没动」→ 落盘；**同日重复提交幂等**：第二次如实回 recorded=false 且文件一字未变。
     * （前端据此说「已经记着了」，而不是假报一次落库。）
     */
    @Test
    void recordDayStatus_noTrade_persistedAndIdempotent() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        TradingPlanService svc = service(fs, List.of(), List.of());
        LocalDate d = LocalDate.of(2026, 10, 12);
        String file = "trading/plans/" + d + ".json";

        TradingPlanService.DayStatusResult first =
                svc.recordDayStatus("default", d, TradingPlan.DAY_STATUS_NO_TRADE);

        assertTrue(first.recorded(), "第一次必须真的落盘");
        assertEquals(TradingPlan.DAY_STATUS_NO_TRADE, first.plan().dayStatus());
        String afterFirst = fs.read("default", file);
        assertTrue(afterFirst != null && afterFirst.contains("NO_TRADE"), "状态必须落进这一天自己的记录文件");

        TradingPlanService.DayStatusResult second =
                svc.recordDayStatus("default", d, TradingPlan.DAY_STATUS_NO_TRADE);

        assertFalse(second.recorded(), "同日同状态重复提交必须幂等：不重复落盘");
        assertEquals(afterFirst, fs.read("default", file), "幂等 = 文件内容一字未变");
        assertEquals(List.of(d), svc.dates("default"), "重复提交不得多出第二个日期文件");
    }

    /**
     * **与既有记录不冲突**：当天已写计划（条目 + 自我约束）时回填状态，items / note 一字不动。
     * 这是本条的硬要求——「今天没动」不许成为孤岛，也不许抹掉用户前晚写的计划。
     */
    @Test
    void recordDayStatus_keepsExistingPlanItemsAndNote() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        TradingPlanService svc = service(fs, List.of(), List.of());
        LocalDate d = LocalDate.of(2026, 10, 12);
        svc.saveFromLines("default", d, List.of("600206 跌破 45.5 清仓", "明天不动"), "只做计划内的票");

        svc.recordDayStatus("default", d, TradingPlan.DAY_STATUS_NO_TRADE);

        TradingPlan got = svc.find("default", d).orElseThrow();
        assertEquals(2, got.items().size(), "回填状态不得动用户已写的计划条目");
        assertEquals("只做计划内的票", got.note(), "回填状态不得动用户写的自我约束");
        assertEquals(TradingPlan.DAY_STATUS_NO_TRADE, got.dayStatus());
    }

    /** 反过来也不许吞：回填状态之后**再写计划**（覆盖写），已记的状态仍在。 */
    @Test
    void saveFromLines_keepsExistingDayStatus() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        TradingPlanService svc = service(fs, List.of(), List.of());
        LocalDate d = LocalDate.of(2026, 10, 12);
        svc.recordDayStatus("default", d, TradingPlan.DAY_STATUS_WANTED_NOT_ACTED);

        TradingPlan saved = svc.saveFromLines("default", d, List.of("600206 跌破 45.5 清仓"), "");

        assertEquals(TradingPlan.DAY_STATUS_WANTED_NOT_ACTED, saved.dayStatus(),
                "覆盖写计划不得抹掉他刚说的「想动没动」");
        assertEquals(TradingPlan.DAY_STATUS_WANTED_NOT_ACTED, svc.find("default", d).orElseThrow().dayStatus());
    }

    /** 「想动没动」与「今天没动」是两种状态：改状态是**更正**（recorded=true），不是重复提交。 */
    @Test
    void recordDayStatus_changeOfMind_isRecorded() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        TradingPlanService svc = service(fs, List.of(), List.of());
        LocalDate d = LocalDate.of(2026, 10, 12);
        svc.recordDayStatus("default", d, TradingPlan.DAY_STATUS_NO_TRADE);

        TradingPlanService.DayStatusResult r =
                svc.recordDayStatus("default", d, TradingPlan.DAY_STATUS_WANTED_NOT_ACTED);

        assertTrue(r.recorded(), "改主意是有效的更正，必须真的落盘");
        assertEquals(TradingPlan.DAY_STATUS_WANTED_NOT_ACTED, svc.find("default", d).orElseThrow().dayStatus());
    }

    /** 认不出的状态**不猜、不静默回落**（否则会替用户记下他没说过的状态）。 */
    @Test
    void recordDayStatus_unknownValue_throws() {
        TradingPlanService svc = service(new InMemoryFileStorage(), List.of(), List.of());

        assertThrows(TradingException.class,
                () -> svc.recordDayStatus("default", LocalDate.of(2026, 10, 12), "MAYBE"));
        assertThrows(TradingException.class,
                () -> svc.recordDayStatus("default", LocalDate.of(2026, 10, 12), ""));
    }

    /**
     * 对账带上这天回填的状态（口径对齐 P2-交易67）：「没动」与「今天没有成交记录」
     * 是互相印证的两句话，不是「缺数据」。
     */
    @Test
    void review_carriesDayStatus() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        LocalDate d = LocalDate.of(2026, 10, 12);
        TradingPlanService svc = service(fs, List.of(), List.of());
        svc.recordDayStatus("default", d, TradingPlan.DAY_STATUS_NO_TRADE);

        TradingPlanService.PlanReview r = svc.review("default", d);

        assertTrue(r.hasPlan(), "只回填了状态也是一份记录——hasPlan 不得当成「没写」");
        assertEquals(TradingPlan.DAY_STATUS_NO_TRADE, r.dayStatus());
        assertTrue(r.items().isEmpty());
        assertTrue(r.unplanned().isEmpty(), "一整天没有成交 → 计划外成交也必须为空（没动就是没动）");
    }

    /**
     * P2-交易72 并发契约（2026-10-05，独立并发审查实测 0 红后固化）：
     * {@link TradingPlanService#saveFromLines} 与 {@link TradingPlanService#recordDayStatus}
     * 必须共用**同一把「同一天」锁**——两者都是「读-改-写」，不共锁时会用各自读到的旧快照互相覆盖。
     *
     * <p>实测的坏结局：状态线程读到空快照后被调度走 → 计划线程写下 1 条 → 状态线程落盘自己的旧快照
     * → **用户的计划条目被「今天没动」静默抹成 0 条**（而全绿 CI 一无所知）。
     * 本用例把当时的确定性探针固化下来：状态线程「find 后挂起」，计划线程并发写。
     */
    @Test
    void concurrentSaveFromLines_andRecordDayStatus_shareTheSameDayLock() throws Exception {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        GatedPlanRepository repo = new GatedPlanRepository(fs);
        TradingHistoryRepository hist = mock(TradingHistoryRepository.class);
        when(hist.findAll(any())).thenReturn(List.of());
        KlineService kline = mock(KlineService.class);
        TradingPlanService svc = new TradingPlanService(repo, hist, kline);
        LocalDate d = LocalDate.of(2026, 10, 12);

        // B：「今天没动」这条写路径先读到一个空快照，随即挂起（持锁等待放行）
        Thread status = new Thread(
                () -> svc.recordDayStatus("default", d, TradingPlan.DAY_STATUS_NO_TRADE),
                GatedPlanRepository.STATUS_THREAD);
        status.start();
        assertTrue(repo.snapshotTaken.await(5, TimeUnit.SECONDS), "状态线程应先读到快照再挂起");

        // A：用户同时在另一处（web/app）保存计划
        Thread plan = new Thread(
                () -> svc.saveFromLines("default", d, List.of("600206 跌破 45.5 清仓"), ""),
                GatedPlanRepository.PLAN_THREAD);
        plan.start();

        assertFalse(repo.planReachedFind.await(400, TimeUnit.MILLISECONDS),
                "两条写路径必须共锁：状态回填落盘前，计划写不得读盘（否则双方用旧快照互相覆盖）");

        repo.release.countDown();
        status.join(5_000);
        plan.join(5_000);
        assertFalse(status.isAlive() || plan.isAlive(), "放行后两个线程都应结束");

        TradingPlan after = svc.find("default", d).orElseThrow();
        assertEquals(1, after.items().size(), "用户的计划条目不得被「今天没动」的旧快照抹成 0");
        assertEquals(TradingPlan.DAY_STATUS_NO_TRADE, after.dayStatus(), "「今天没动」也不得被计划覆盖写抹掉");
    }

    /**
     * 「find 后可挂起」的 {@link TradingPlanFileRepository} 替身：状态线程读到快照后停住，
     * 由测试决定何时放行；计划线程一读盘就记一笔（用于断言它进不来）。
     */
    private static final class GatedPlanRepository extends TradingPlanFileRepository {
        static final String STATUS_THREAD = "probe-status";
        static final String PLAN_THREAD = "probe-plan";

        final CountDownLatch snapshotTaken = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch planReachedFind = new CountDownLatch(1);

        GatedPlanRepository(InMemoryFileStorage fs) {
            super(fs);
        }

        @Override
        public Optional<TradingPlan> find(String userId, LocalDate date) {
            Optional<TradingPlan> snapshot = super.find(userId, date);
            String name = Thread.currentThread().getName();
            if (STATUS_THREAD.equals(name)) {
                snapshotTaken.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (PLAN_THREAD.equals(name)) {
                planReachedFind.countDown();
            }
            return snapshot;
        }
    }
}
