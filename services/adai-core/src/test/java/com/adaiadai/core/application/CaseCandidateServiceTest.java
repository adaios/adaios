package com.adaiadai.core.application;

import com.adaiadai.core.application.TradingRoundService.RuleHit;
import com.adaiadai.core.application.TradingRoundService.TradeRound;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.UserRule;
import com.adaiadai.core.domain.trading.cases.CaseCandidate;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.CaseCandidateFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.UserRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CaseCandidateServiceTest — 案例候选（2026-10-08，UI/UX 批 ③）。
 *
 * 覆盖：候选挑取（阈值不硬凑 · 每方向 top2 · 买点 K 线口径与 MA5 标题）·
 * 规则对照（止损 SUPPORT/AGAINST · 盈转亏 · 引擎命中兜底 · 无对照不硬编）·
 * 三态决定（收下落盘 · 双击幂等 · 已弃不可复活 · 墓碑不复活 · 脏 id 最小墓碑 · 非法 id 400）。
 */
class CaseCandidateServiceTest {

    private static final String USER = "u";
    private static final String NOW = "2026-10-08T10:00";

    private TradingRoundService rounds;
    private TradingAppService app;
    private SoldAfterCloseService afterClose;
    private KlineService klines;
    private UserRuleRepository userRules;
    private CaseCandidateFileRepository repo;
    private CaseCandidateService svc;

    @BeforeEach
    void setUp() {
        rounds = mock(TradingRoundService.class);
        app = mock(TradingAppService.class);
        afterClose = mock(SoldAfterCloseService.class);
        klines = mock(KlineService.class);
        userRules = new UserRuleRepository(new InMemoryFileStorage());
        repo = new CaseCandidateFileRepository(new InMemoryFileStorage());
        svc = new CaseCandidateService(rounds, app, afterClose, klines, userRules, repo);
        stubRounds(List.of());
        stubSold(List.of(), List.of());
    }

    // ── helpers ──

    private void stubRounds(List<TradeRound> list) {
        when(rounds.rounds(anyString(), any(), anyInt()))
                .thenReturn(new TradingRoundService.RoundsView(list.size(), list));
    }

    private void stubSold(List<SoldTrade> sold, List<SoldAfterCloseService.AfterClose> acs) {
        when(app.soldList(anyString())).thenReturn(sold);
        when(afterClose.compute(any())).thenReturn(acs);
    }

    private void stubKline(List<Candle> candles) {
        when(klines.klineRange(anyString(), any(), any())).thenReturn(candles);
    }

    private static SoldTrade sold(String symbol, String name, String sellDate,
                                  double holdPnlPct, int holdDays) {
        LocalDate d = LocalDate.parse(sellDate);
        return new SoldTrade(symbol, name, d.minusDays(holdDays), d, holdDays, "1+1",
                holdPnlPct, "", "", "import");
    }

    private static SoldAfterCloseService.AfterClose ac(String symbol, String name,
                                                       String sellDate, Double pct) {
        String dir = pct == null ? null : (pct > 0 ? "up" : pct < 0 ? "down" : "flat");
        return new SoldAfterCloseService.AfterClose(symbol, name, sellDate, sellDate, 10.0,
                "2026-10-07", 12.0, pct, dir, null);
    }

    /** 一轮已平仓（name = symbol；hits 可传引擎命中）。 */
    private static TradeRound round(String symbol, String start, String pnlPct, int holdDays,
                                    String peakPct, int trapped, List<RuleHit> hits) {
        BigDecimal pct = new BigDecimal(pnlPct);
        LocalDate s = LocalDate.parse(start);
        return new TradeRound(symbol + "_" + start, symbol, symbol, s, s, s.plusDays(holdDays),
                holdDays, holdDays, 1, 1, new BigDecimal("10000"),
                pct.multiply(new BigDecimal("100")), pct,
                peakPct != null ? new BigDecimal(peakPct) : null, s, null,
                0, trapped, null, hits, false, null, null);
    }

    private static Candle candle(String date, double close) {
        return new Candle(LocalDate.parse(date), close, close, close, close, 1000);
    }

    /** 认下一条规则进规则集（对照源①）。 */
    private void acceptRule(String id, String text, Map<String, Object> params) {
        UserRule r = UserRule.candidate(id, text, params,
                new UserRule.Evidence("how", List.of(), List.of(), List.of()), NOW)
                .accepted(null, NOW);
        userRules.upsert(USER, r);
    }

    /** 买点 K 线基座：08-03..08-12 前史 + 末日最新（08-12 收盘 buyClose，最新 latestClose）。 */
    private static List<Candle> buyKline(double buyClose, double latestClose) {
        return List.of(
                candle("2026-08-03", 10.0), candle("2026-08-04", 10.0),
                candle("2026-08-05", 10.0), candle("2026-08-06", 10.0),
                candle("2026-08-07", 10.0), candle("2026-08-10", 10.0),
                candle("2026-08-11", 10.0), candle("2026-08-12", buyClose),
                candle("2026-10-07", latestClose));
    }

    // ── 卖点类：挑候选 ──

    @Test
    void sell_belowThreshold_notCandidate() {
        stubSold(
                List.of(sold("600001", "甲", "2026-08-01", 5, 10),
                        sold("600002", "乙", "2026-08-02", 5, 10),
                        sold("600003", "丙", "2026-08-03", 5, 10)),
                List.of(ac("600001", "甲", "2026-08-01", 20.0),
                        ac("600002", "乙", "2026-08-02", 3.0),      // <8% 不硬凑
                        ac("600003", "丙", "2026-08-03", null)));   // 算不出不硬凑
        List<CaseCandidate> pending = svc.list(USER).pending();
        assertEquals(1, pending.size(), "只有过 8% 阈值的才是候选");
        assertEquals("sell-600001-2026-08-01", pending.get(0).id());
        assertEquals(CaseCandidate.Outcome.EARLY, pending.get(0).outcome());
    }

    @Test
    void sell_topTwoPerDirection() {
        stubSold(
                List.of(sold("600001", "甲", "2026-08-01", 5, 10),
                        sold("600002", "乙", "2026-08-02", 5, 10),
                        sold("600003", "丙", "2026-08-03", 5, 10),
                        sold("600004", "丁", "2026-08-04", 5, 10),
                        sold("600005", "戊", "2026-08-05", 5, 10),
                        sold("600006", "己", "2026-08-06", 5, 10)),
                List.of(ac("600001", "甲", "2026-08-01", 25.0),
                        ac("600002", "乙", "2026-08-02", 20.0),
                        ac("600003", "丙", "2026-08-03", 15.0),
                        ac("600004", "丁", "2026-08-04", 9.0),
                        ac("600005", "戊", "2026-08-05", -12.0),
                        ac("600006", "己", "2026-08-06", -9.0)));
        List<CaseCandidate> pending = svc.list(USER).pending();
        assertEquals(List.of(
                        "sell-600001-2026-08-01", "sell-600002-2026-08-02",   // 涨：25 / 20
                        "sell-600005-2026-08-05", "sell-600006-2026-08-06"),  // 跌：|12| / |9|
                pending.stream().map(CaseCandidate::id).toList(),
                "每方向只取幅度最大的 2 条；涨组在前");
    }

    @Test
    void sell_titleAndNotes_factsOnly() {
        stubSold(List.of(sold("603993", "洛阳钼业", "2026-08-19", 10, 30)),
                List.of(ac("603993", "洛阳钼业", "2026-08-19", 18.44)));
        CaseCandidate c = svc.list(USER).pending().get(0);
        assertEquals("洛阳钼业 08-19 卖了之后又涨 18.4%", c.title());
        assertEquals(18.44, c.changePct(), 0.001);
        assertEquals("卖掉之后到现在 +18.4%。",
                c.notes().get(c.notes().size() - 1), "没对照时只摆事实");
        assertNull(c.ruleRel(), "没有规则 → 不硬编对照");
    }

    // ── 卖点类：规则对照（止损 SUPPORT / AGAINST）──

    @Test
    void sell_stoploss_supportWhenLossWithinLine() {
        acceptRule("cand-stoploss", "止损：亏到 5% 就走（你 6 次亏损里 5 次亏得不超过 5%）",
                Map.of("pct", -5.0));
        stubSold(List.of(sold("603993", "洛阳钼业", "2026-08-19", -3.2, 30)),
                List.of(ac("603993", "洛阳钼业", "2026-08-19", 18.44)));
        CaseCandidate c = svc.list(USER).pending().get(0);
        assertEquals(CaseCandidate.RuleRel.SUPPORT, c.ruleRel());
        assertEquals("cand-stoploss", c.ruleId());
        assertEquals("止损：亏到 5% 就走", c.ruleText(), "存到「（」前的主句");
        assertEquals("支持你写的「止损：亏到 5% 就走」—— 这一笔亏到 3.2% 就平了。", c.notes().get(0));
    }

    @Test
    void sell_stoploss_againstWhenLossBeyondLine() {
        acceptRule("cand-stoploss", "止损：亏到 5% 就走（你 6 次亏损里 5 次亏得不超过 5%）",
                Map.of("pct", -5.0));
        stubSold(List.of(sold("603993", "洛阳钼业", "2026-08-19", -12.0, 30)),
                List.of(ac("603993", "洛阳钼业", "2026-08-19", -9.0)));
        CaseCandidate c = svc.list(USER).pending().get(0);
        assertEquals(CaseCandidate.RuleRel.AGAINST, c.ruleRel());
        assertEquals("和你写的「止损：亏到 5% 就走」相反 —— 这一笔亏了 12%。", c.notes().get(0));
    }

    // ── 买点类：K 线口径 + MA5 标题 ──

    @Test
    void buy_success_ma5Above_titleAndChange() {
        stubRounds(List.of(round("601899", "2026-08-12", "5.0", 30, null, 0, List.of())));
        stubKline(buyKline(10.6, 13.0));   // 买在 10.6（> 5 日线 10.12），最新 13.0 → +22.64%
        List<CaseCandidate> pending = svc.list(USER).pending();
        assertEquals(1, pending.size());
        CaseCandidate c = pending.get(0);
        assertEquals(CaseCandidate.Kind.BUY, c.kind());
        assertEquals(CaseCandidate.Outcome.SUCCESS, c.outcome());
        assertEquals("buy-601899-2026-08-12", c.id());
        assertEquals("601899 08-12 买在 5 日线上方", c.title());
        assertEquals(22.64, c.changePct(), 0.001);
        assertEquals("买了之后到现在 +22.6%。", c.notes().get(c.notes().size() - 1));
    }

    @Test
    void buy_ma5Below() {
        stubRounds(List.of(round("601899", "2026-08-12", "5.0", 30, null, 0, List.of())));
        stubKline(buyKline(7.0, 13.0));    // 买在 7.0（< 5 日线 9.4）
        CaseCandidate c = svc.list(USER).pending().get(0);
        assertEquals("601899 08-12 买在 5 日线下方", c.title());
        assertEquals(85.71, c.changePct(), 0.01);
    }

    @Test
    void buy_insufficientHistory_titleFallsBack() {
        stubRounds(List.of(round("601899", "2026-08-12", "5.0", 30, null, 0, List.of())));
        stubKline(List.of(candle("2026-08-12", 10.6), candle("2026-10-07", 13.0)));
        CaseCandidate c = svc.list(USER).pending().get(0);
        assertEquals("601899 08-12 买入", c.title(), "前史不够算不出 MA5 → 退化标题");
    }

    @Test
    void buy_belowThreshold_notCandidate() {
        stubRounds(List.of(round("601899", "2026-08-12", "5.0", 30, null, 0, List.of())));
        stubKline(buyKline(10.6, 11.13));  // +5% < 8%
        assertTrue(svc.list(USER).pending().isEmpty(), "买后涨幅不到 8% 不出候选");
    }

    // ── 买点类：对照（用户规则优先 → 引擎命中兜底）──

    @Test
    void buy_givebackAgainst_fromUserRule() {
        acceptRule("cand-giveback", "盈转亏：赚过 3% 后回落到成本就走（你有 15 笔赚过又扛成亏）",
                Map.of("peakPct", 3.0));
        stubRounds(List.of(round("601899", "2026-08-12", "-6.0", 30, "9.4", 0, List.of())));
        stubKline(buyKline(10.6, 8.0));    // 买后跌 → FAILED
        CaseCandidate c = svc.list(USER).pending().get(0);
        assertEquals(CaseCandidate.RuleRel.AGAINST, c.ruleRel());
        assertEquals("cand-giveback", c.ruleId());
        assertEquals("和你写的「盈转亏：赚过 3% 后回落到成本就走」相反 —— 这一笔赚到过 +9.4%，最后亏了。",
                c.notes().get(0));
    }

    @Test
    void buy_engineHit_whenNoUserRule() {
        stubRounds(List.of(round("601899", "2026-08-12", "-6.0", 30, null, 0,
                List.of(new RuleHit("R66", "收盘跌破止损位后未当日走（按当日成本 −4%，R72 取中值）", "d")))));
        stubKline(buyKline(10.6, 8.0));
        CaseCandidate c = svc.list(USER).pending().get(0);
        assertEquals(CaseCandidate.RuleRel.AGAINST, c.ruleRel());
        assertEquals("R66", c.ruleId());
        assertEquals("命中 R66：收盘跌破止损位后未当日走", c.notes().get(0), "引擎命中截「（」尾巴");
    }

    // ── 三态决定 ──

    @Test
    void accept_persists_pendingMovesToAccepted() {
        stubSold(List.of(sold("603993", "洛阳钼业", "2026-08-19", 10, 30)),
                List.of(ac("603993", "洛阳钼业", "2026-08-19", 18.44)));
        String id = svc.list(USER).pending().get(0).id();
        CaseCandidate acc = svc.accept(USER, id, "我的卖飞案例");
        assertEquals(CaseCandidate.State.ACCEPTED, acc.state());
        assertEquals("我的卖飞案例", acc.title(), "「改一改」收下时改名");

        var after = svc.list(USER);
        assertTrue(after.pending().isEmpty(), "收下后不在等你认里");
        assertEquals(1, after.accepted().size());
        assertEquals("我的卖飞案例", after.accepted().get(0).title());
        assertEquals(CaseCandidate.State.ACCEPTED, repo.findByUser(USER).get(0).state(), "决定落盘");
    }

    @Test
    void accept_idempotent_doubleClick() {
        stubSold(List.of(sold("603993", "洛阳钼业", "2026-08-19", 10, 30)),
                List.of(ac("603993", "洛阳钼业", "2026-08-19", 18.44)));
        String id = svc.list(USER).pending().get(0).id();
        svc.accept(USER, id, "改一");
        CaseCandidate again = svc.accept(USER, id, "改二");
        assertEquals("改一", again.title(), "重复收下原样返回——第一次决定为准");
        assertEquals(1, repo.findByUser(USER).size());
    }

    @Test
    void accept_unknown_throws() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.accept(USER, "sell-999999-2026-01-01", null));
        assertTrue(e.getMessage().contains("不在列表里"), e.getMessage());
    }

    @Test
    void accept_dismissed_throws_neverResurrects() {
        stubSold(List.of(sold("603993", "洛阳钼业", "2026-08-19", 10, 30)),
                List.of(ac("603993", "洛阳钼业", "2026-08-19", 18.44)));
        String id = svc.list(USER).pending().get(0).id();
        svc.dismiss(USER, id);
        assertThrows(IllegalArgumentException.class, () -> svc.accept(USER, id, null),
                "弃掉的是墓碑——不能再收（同规则集对 DISMISSED 的口径）");
    }

    @Test
    void dismiss_tombstone_neverAppearsAgain() {
        stubSold(List.of(sold("603993", "洛阳钼业", "2026-08-19", 10, 30)),
                List.of(ac("603993", "洛阳钼业", "2026-08-19", 18.44)));
        String id = svc.list(USER).pending().get(0).id();
        svc.dismiss(USER, id);
        svc.dismiss(USER, id);   // 幂等：已弃再弃不炸
        assertTrue(svc.list(USER).pending().stream().noneMatch(c -> c.id().equals(id)),
                "弃掉的不复活（P1-交易93）");
        assertEquals(CaseCandidate.State.DISMISSED,
                repo.findByUser(USER).stream()
                        .filter(c -> c.id().equals(id)).findFirst().orElseThrow().state());
    }

    @Test
    void dismiss_minimalTomb_whenCandidateGone() {
        svc.dismiss(USER, "sell-999999-2026-01-01");
        CaseCandidate tomb = repo.findByUser(USER).stream()
                .filter(c -> c.id().equals("sell-999999-2026-01-01")).findFirst().orElseThrow();
        assertEquals(CaseCandidate.State.DISMISSED, tomb.state());
        assertEquals("999999", tomb.symbol(), "从 id 还原的最小墓碑");
        assertEquals("2026-01-01", tomb.date());
    }

    @Test
    void dismiss_invalidId_throws() {
        assertThrows(IllegalArgumentException.class, () -> svc.dismiss(USER, "abc"));
    }
}
