package com.adaiadai.core.application;

import com.adaiadai.core.application.TradingAnalysisService.AnalysisView;
import com.adaiadai.core.application.TradingAnalysisService.Fact;
import com.adaiadai.core.application.TradingRoundService.TradeRound;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingAnalysisServiceTest — 分析总结三粒度（analytics 批，2026-10-06 · R-05 · blueprint §八）。
 *
 * 覆盖四条原则与三段总结的不变量：描述与对照分开（无规则 → 判不了 + 不提问）·
 * 不编（profit-factor 不写 ∞、unresolved 不漏 0、无快照标算不出）· 可回溯（每个 fact 带 trace）·
 * 三段（事实 → 对照 → 一个提问）。
 */
class TradingAnalysisServiceTest {

    // ── fixtures ──

    private static TradingRoundService.RuleHit hit(String rule, String text) {
        return new TradingRoundService.RuleHit(rule, text, null);
    }

    private static TradeRound closed(String id, String symbol, String start, String end,
                                     String pnl, String pnlPct, TradingRoundService.RuleHit... hits) {
        return new TradeRound(id, symbol, symbol + "名",
                LocalDate.parse(start), LocalDate.parse(start), LocalDate.parse(end),
                5, 3, 2, 2,
                new BigDecimal("10000"), new BigDecimal(pnl), new BigDecimal(pnlPct),
                new BigDecimal("9.4"), LocalDate.parse(start), new BigDecimal("-5.0"),
                1, 0, new BigDecimal("1.5"),
                List.of(hits), false, null, null);
    }

    private static TradeRound unresolved(String id, String symbol, String start) {
        return new TradeRound(id, symbol, symbol + "名",
                LocalDate.parse(start), null, null,
                0, 0, 0, 1,
                null, null, null, null, null, null, 0, 0, null,
                List.of(), true, "只有卖出记录、流水未覆盖建仓——如实：判不了", null);
    }

    private static TradeRecord trade(String id, String symbol, String date, LocalTime time, TradeDirection dir) {
        return new TradeRecord(id, symbol, symbol + "名", dir, new BigDecimal("10.00"), 100,
                new BigDecimal("1000.00"), LocalDate.parse(date), time,
                null, null, null, null, null, LocalDate.parse(date).atTime(time), null, null);
    }

    private static EquityCurveService.EquityCurve emptyCurve() {
        return new EquityCurveService.EquityCurve(List.of(), 0, null, null);
    }

    private static TradingAnalysisService service(boolean hasRules, List<TradeRound> rounds,
                                                  List<TradeRecord> flow) {
        return service(hasRules, rounds, flow, emptyCurve());
    }

    private static TradingAnalysisService service(boolean hasRules, List<TradeRound> rounds,
                                                  List<TradeRecord> flow,
                                                  EquityCurveService.EquityCurve curve) {
        TradingRoundService rs = mock(TradingRoundService.class);
        when(rs.rounds(anyString(), any(), anyInt()))
                .thenReturn(new TradingRoundService.RoundsView(rounds.size(), rounds));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(flow);
        EquityCurveService equity = mock(EquityCurveService.class);
        when(equity.build(anyString())).thenReturn(curve);
        TradingRuleSettingsPort rules = mock(TradingRuleSettingsPort.class);
        when(rules.exists(anyString())).thenReturn(hasRules);
        return new TradingAnalysisService(rs, history, equity, rules);
    }

    private static Fact fact(AnalysisView v, String key) {
        return v.description().stream().filter(f -> key.equals(f.key())).findFirst()
                .orElseThrow(() -> new AssertionError("没有 " + key + " 这个数字：" + v.description()));
    }

    // ── 全局 ──

    /** 描述与对照分开：没有规则 → 明说判不了，且提问为 null（不硬凑）。 */
    @Test
    void global_withoutRules_descriptionOnly_cantJudge_noQuestion() {
        TradingAnalysisService svc = service(false,
                List.of(closed("600206_2026-08-05", "600206", "2026-08-05", "2026-08-12", "1000", "10.0",
                        hit("R55", "盈转亏没走"))),
                List.of());

        AnalysisView v = svc.global("u");

        assertEquals("global", v.scope());
        assertFalse(v.contrast().hasRules());
        assertTrue(v.contrast().reason().contains("判不了"), "明说判不了守没守");
        assertTrue(v.contrast().ruleHits().isEmpty());
        assertNull(v.summary().question(), "没有规则 → 不提问");
        assertNotNull(fact(v, "rounds-count"), "描述仍全（人人可用）");
        assertEquals(1, fact(v, "rounds-count").value());
    }

    /** 有规则：命中按次数聚合排序，三段总结的对照与提问都落地。 */
    @Test
    void global_withRules_aggregatesHits_andAsksFossilize() {
        TradingAnalysisService svc = service(true,
                List.of(
                        closed("600206_2026-08-05", "600206", "2026-08-05", "2026-08-12", "1000", "10.0",
                                hit("R55", "盈转亏没走")),
                        closed("600210_2026-08-01", "600210", "2026-08-01", "2026-08-08", "-500", "-5.0",
                                hit("R55", "盈转亏没走"), hit("R62", "亏损加仓"))),
                List.of());

        AnalysisView v = svc.global("u");

        assertTrue(v.contrast().hasRules());
        assertEquals(2, v.contrast().ruleHits().size());
        assertEquals("R55", v.contrast().ruleHits().get(0).rule(), "按命中次数降序");
        assertEquals(2, v.contrast().ruleHits().get(0).count());
        assertEquals(2, v.contrast().ruleHits().get(0).roundIds().size(), "命中哪几笔可回溯");
        assertTrue(v.summary().contrast().contains("R55"), "对照句点名规则：" + v.summary().contrast());
        assertNotNull(v.summary().question());
        assertTrue(v.summary().question().contains("固化成你的规则"), "提问照 blueprint §八：" + v.summary().question());
    }

    /** 不编：从没亏过 → 盈亏比算不出（null + 说明），不写 ∞ 当数。 */
    @Test
    void global_profitFactor_neverLoss_isNull_notInfinity() {
        TradingAnalysisService svc = service(false,
                List.of(closed("600206_2026-08-05", "600206", "2026-08-05", "2026-08-12", "1000", "10.0")),
                List.of());

        Fact pf = fact(svc.global("u"), "profit-factor");

        assertNull(pf.value(), "不写 ∞ / 不填 0");
        assertTrue(pf.trace().note().contains("算不出"), "如实说明：" + pf.trace().note());
    }

    /** 没有笔也不炸：如实说「还没有」，分布/分层为空不编数。 */
    @Test
    void global_empty_graceful() {
        AnalysisView v = service(false, List.of(), List.of()).global("u");

        assertEquals(0, fact(v, "rounds-count").value());
        assertTrue(v.summary().fact().contains("还没有"));
        assertEquals(List.of(), fact(v, "by-size").value(), "分不了层如实给空列表");
        assertTrue(fact(v, "by-size").trace().note().contains("分不了"));
    }

    /** 资金层：没有账户快照 → capital-net=null + 说明（复用 G-06/G-07，不编数）。 */
    @Test
    void global_capital_noSnapshot_marksUnable() {
        Fact net = fact(service(false, List.of(), List.of(), emptyCurve()).global("u"), "capital-net");

        assertNull(net.value());
        assertTrue(net.trace().note().contains("算不出"));
    }

    /** P1-交易94：非空曲线的 capital-net 溯源要如实——invested 起点是快照本金基准，不是「全由流水推出」。 */
    @Test
    void global_capital_traceHonest_notClaimingNoManualInput() {
        EquityCurveService.EquityCurve curve = new EquityCurveService.EquityCurve(
                List.of(new EquityCurveService.EquityPoint(
                        LocalDate.parse("2026-10-06"), new BigDecimal("110504.88"), new BigDecimal("292.88"),
                        new BigDecimal("110212.00"), new BigDecimal("150000"), new BigDecimal("0.74"),
                        BigDecimal.ZERO)),
                0, "2026-08-01", "2026-10-06");

        Fact net = fact(service(false, List.of(), List.of(), curve).global("u"), "capital-net");

        assertEquals(new BigDecimal("150000"), net.value(), "净投入取曲线末点 invested");
        assertTrue(net.trace().note().contains("快照"), "如实说清基准来自快照 —— " + net.trace().note());
        assertTrue(net.trace().note().contains("转账"), "转账净额是流水推出的那部分 —— " + net.trace().note());
        assertFalse(net.trace().note().contains("不靠手填"),
                "不得再宣称「不靠手填」（与 EquityCurveService 实现不符，P1-交易94）");
    }

    /** 可回溯（验收 4）：三种粒度的每个数字都带 trace。 */
    @Test
    void allFacts_carryTrace() {
        List<TradeRound> rounds = List.of(
                closed("600206_2026-08-05", "600206", "2026-08-05", "2026-08-12", "1000", "10.0",
                        hit("R55", "盈转亏没走")),
                unresolved("600210_2026-08-05", "600210", "2026-08-05"));

        for (AnalysisView v : List.of(
                service(true, rounds, List.of()).global("u"),
                service(true, rounds, List.of()).symbol("u", "600206"),
                service(true, rounds, List.of()).round("u", "600206_2026-08-05"))) {
            for (Fact f : v.description()) {
                assertNotNull(f.trace(), v.scope() + " 的 " + f.key() + " 没有 trace");
                assertNotNull(f.trace().roundIds(), f.key() + " trace.roundIds 不为 null");
                assertNotNull(f.trace().dates(), f.key() + " trace.dates 不为 null");
                assertNotNull(f.trace().note(), f.key() + " trace.note 不为 null（说清怎么算的）");
            }
        }
    }

    // ── 单标的 ──

    @Test
    void symbol_totalsAndBriefs() {
        List<TradeRound> rounds = List.of(
                closed("600206_2026-08-05", "600206", "2026-08-05", "2026-08-12", "1000", "10.0"),
                closed("600206_2026-07-01", "600206", "2026-07-01", "2026-07-10", "-500", "-5.0"),
                unresolved("600206_2026-06-01", "600206", "2026-06-01"));

        AnalysisView v = service(false, rounds, List.of()).symbol("u", "600206");

        assertEquals("symbol", v.scope());
        assertEquals(new BigDecimal("500"), fact(v, "symbol-pnl").value(), "只累计已平仓（unresolved 不掺进来）");
        assertEquals("1 / 2", fact(v, "symbol-wins").value());
        @SuppressWarnings("unchecked")
        List<TradingAnalysisService.RoundBrief> briefs =
                (List<TradingAnalysisService.RoundBrief>) fact(v, "round-list").value();
        assertEquals(3, briefs.size(), "逐笔含 unresolved（如实列出）");
        assertTrue(briefs.get(2).unresolved());
        assertNull(briefs.get(2).pnl(), "判不了不编 0");
    }

    @Test
    void symbol_blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> service(false, List.of(), List.of()).symbol("u", " "));
    }

    // ── 单笔 ──

    /** 单笔：unresolved 只出跨度 + 如实说明，不出结果（不编）。 */
    @Test
    void round_unresolved_isHonest_noFabricatedResult() {
        List<TradeRound> rounds = List.of(unresolved("600210_2026-08-05", "600210", "2026-08-05"));

        AnalysisView v = service(true, rounds, List.of()).round("u", "600210_2026-08-05");

        assertEquals("round", v.scope());
        assertEquals(2, v.description().size(), "只有 span + unresolved 两条，不出结果");
        assertNull(fact(v, "unresolved").value());
        assertTrue(v.summary().fact().contains("判不了"));
        assertNull(v.summary().question(), "判不了的笔不提问");
    }

    /** 单笔：正常笔出结果 + 三段（峰值事实 → 规则对照 → 提问）。 */
    @Test
    void round_closed_resultAndThreeParts() {
        List<TradeRound> rounds = List.of(closed("600206_2026-08-05", "600206", "2026-08-05", "2026-08-12",
                "1000", "10.0", hit("R55", "盈转亏没走")));

        AnalysisView v = service(true, rounds, List.of()).round("u", "600206_2026-08-05");

        assertTrue(String.valueOf(fact(v, "result").value()).contains("1000"));
        assertTrue(fact(v, "result").trace().note().contains("清仓定结果"));
        assertTrue(v.summary().fact().contains("峰值"), "事实段照 blueprint §八：" + v.summary().fact());
        assertTrue(v.summary().contrast().contains("R55"));
        assertNotNull(v.summary().question());
        assertTrue(v.summary().question().contains("固化"), "提问照 blueprint §八：" + v.summary().question());
    }

    /** 做 T 次数：同日先卖后买算一次；先买后卖不算（口径随 rounds：清仓后买回 = 做 T）。 */
    @Test
    void round_dayTrade_countsSellThenBuyOnly() {
        List<TradeRecord> flow = List.of(
                trade("t1", "600206", "2026-08-06", LocalTime.of(9, 30), TradeDirection.SELL),
                trade("t2", "600206", "2026-08-06", LocalTime.of(10, 0), TradeDirection.BUY),
                trade("t3", "600206", "2026-08-07", LocalTime.of(9, 30), TradeDirection.BUY),
                trade("t4", "600206", "2026-08-07", LocalTime.of(14, 0), TradeDirection.SELL));
        List<TradeRound> rounds = List.of(closed("600206_2026-08-05", "600206", "2026-08-05", "2026-08-12",
                "1000", "10.0"));

        Fact t = fact(service(false, rounds, flow).round("u", "600206_2026-08-05"), "t-count");

        assertEquals(1, t.value(), "8-06 先卖后买 = 1 次；8-07 先买后卖不算");
        assertTrue(t.trace().dates().contains("2026-08-06"));
    }

    @Test
    void round_unknown_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service(false, List.of(), List.of()).round("u", "600206_2026-08-05"));
    }

    @Test
    void round_badIdShape_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service(false, List.of(), List.of()).round("u", "abc"));
    }
}
