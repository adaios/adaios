package com.adaiadai.core.application;

import com.adaiadai.core.application.TradingRoundService.TradeRound;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import com.adaiadai.core.domain.trading.UserRule;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.UserRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingUserRuleServiceTest — 规则集三态与「从数据长候选」（rules 批，2026-10-06 · R-06 · blueprint §三）。
 *
 * 覆盖：五类候选的生成条件与「每条带据」（可回溯 roundIds）· 样本不足不硬凑 ·
 * 刷新语义（候选刷新 / 已认永不覆盖 / 不再支持撤下）· 三态操作（认下/改/弃/自己写）与状态校验。
 */
class TradingUserRuleServiceTest {

    private static final String USER = "u";

    private TradingRoundService rounds;
    private TradingUserRuleService svc;

    @BeforeEach
    void setUp() {
        rounds = mock(TradingRoundService.class);
        stubRounds(List.of());
        TradingRuleSettingsPort settings = mock(TradingRuleSettingsPort.class);
        when(settings.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        svc = new TradingUserRuleService(rounds, settings, new UserRuleRepository(new InMemoryFileStorage()));
    }

    private void stubRounds(List<TradeRound> list) {
        when(rounds.rounds(anyString(), any(), anyInt()))
                .thenReturn(new TradingRoundService.RoundsView(list.size(), list));
    }

    /** 一笔已平仓（holdDays / pnlPct / peakPct / 被套加仓次数可指定；pnl 与 pnlPct 同号）。 */
    private static TradeRound r(String id, String pnlPct, int holdDays, String peakPct, int trapped) {
        String symbol = id.substring(0, id.indexOf('_'));
        String start = id.substring(id.indexOf('_') + 1);
        BigDecimal pct = new BigDecimal(pnlPct);
        return new TradeRound(id, symbol, symbol + "名",
                LocalDate.parse(start), LocalDate.parse(start), LocalDate.parse(start).plusDays(holdDays),
                holdDays, holdDays, 1, 1,
                new BigDecimal("10000"), pct.multiply(new BigDecimal("100")), pct,
                peakPct != null ? new BigDecimal(peakPct) : null, LocalDate.parse(start), null,
                0, trapped, null,
                List.of(), false, null, null);
    }

    // ── 候选生成：样本不足不硬凑 ──

    @Test
    void noData_producesNothing() {
        TradingUserRuleService.RuleListView v = svc.generateCandidates(USER);
        assertEquals(0, v.total(), "没数据 → 一条候选都不出（不硬凑）");
        assertTrue(v.candidates().isEmpty());
    }

    @Test
    void fourLosses_belowThreshold_noStoplossCandidate() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0)));
        TradingUserRuleService.RuleListView v = svc.generateCandidates(USER);
        assertTrue(v.candidates().stream().noneMatch(x -> x.id().equals("cand-stoploss")),
                "只 4 笔亏损（<5）→ 不出止损候选");
    }

    // ── 五类候选 ──

    /** 止损：6 笔亏损绝对值 [1,2,3,4,5,12]，p75 = 5 → 候选「亏到 -5% 就走（6 次里 5 次不超过 5%）」。 */
    @Test
    void stoploss_fromLossDistribution_withEvidence() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0),
                r("600250_2026-08-06", "-12", 3, null, 0)));

        UserRule c = svc.generateCandidates(USER).candidates().stream()
                .filter(x -> x.id().equals("cand-stoploss")).findFirst().orElseThrow();

        assertTrue(c.text().contains("-5%"), c.text());
        assertTrue(c.text().contains("6 次亏损里 5 次"), c.text());
        assertEquals(-5.0, ((Number) c.params().get("pct")).doubleValue());
        assertEquals(6, c.evidence().roundIds().size(), "哪几笔算的可回溯");
        assertTrue(c.evidence().facts().stream().anyMatch(f -> f.contains("最狠一笔亏 12%")), c.evidence().facts().toString());
    }

    /** 短线超期：7 笔平均 6.4 天 → 候选「持有超过 7 天算超期」。 */
    @Test
    void shortOverdue_fromAvgHold() {
        stubRounds(List.of(
                r("600206_2026-08-01", "1", 3, null, 0),
                r("600210_2026-08-02", "1", 4, null, 0),
                r("600220_2026-08-03", "-1", 5, null, 0),
                r("600230_2026-08-04", "1", 6, null, 0),
                r("600240_2026-08-05", "-1", 7, null, 0),
                r("600250_2026-08-06", "1", 8, null, 0),
                r("600260_2026-08-07", "1", 12, null, 0)));

        UserRule c = svc.generateCandidates(USER).candidates().stream()
                .filter(x -> x.id().equals("cand-short-overdue")).findFirst().orElseThrow();

        assertTrue(c.text().contains("超过 7 天"), c.text());
        assertTrue(c.text().contains("6.4 天"), c.text());
        assertEquals(7, ((Number) c.params().get("days")).intValue());
    }

    /** 盈转亏：2 笔峰值 ≥3% 但最终亏 → 候选（例子里带具体笔）。 */
    @Test
    void giveback_peakThenLoss() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-25.9", 9, "9.4", 0),
                r("600210_2026-08-02", "-4", 5, "5.0", 0),
                r("600220_2026-08-03", "3", 5, "8.0", 0)));   // 这笔赚着走的，不算

        UserRule c = svc.generateCandidates(USER).candidates().stream()
                .filter(x -> x.id().equals("cand-giveback")).findFirst().orElseThrow();

        assertTrue(c.text().contains("赚过 3%"), c.text());
        assertTrue(c.text().contains("2 笔"), c.text());
        assertEquals(2, c.evidence().roundIds().size());
        assertTrue(c.evidence().facts().stream().anyMatch(f -> f.contains("600206 2026-08-01")),
                "带具体例子：" + c.evidence().facts());
    }

    /** 被套不补仓：合计 3 次 → 候选。 */
    @Test
    void trappedAddon_threshold() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-5", 5, null, 2),
                r("600210_2026-08-02", "2", 5, null, 1)));

        UserRule c = svc.generateCandidates(USER).candidates().stream()
                .filter(x -> x.id().equals("cand-trapped-addon")).findFirst().orElseThrow();

        assertTrue(c.text().contains("3 次"), c.text());
        assertEquals(2, c.evidence().roundIds().size());
    }

    /** 亏损不扛：亏 3 笔平均 10.3 天 / 赚 3 笔平均 3 天 → 候选「亏损不扛过 3 天」。 */
    @Test
    void lossHold_lossHeldMuchLonger() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-5", 9, null, 0),
                r("600210_2026-08-02", "-5", 10, null, 0),
                r("600220_2026-08-03", "-5", 12, null, 0),
                r("600230_2026-08-04", "5", 2, null, 0),
                r("600240_2026-08-05", "5", 3, null, 0),
                r("600250_2026-08-06", "5", 4, null, 0)));

        UserRule c = svc.generateCandidates(USER).candidates().stream()
                .filter(x -> x.id().equals("cand-loss-hold")).findFirst().orElseThrow();

        assertTrue(c.text().contains("不扛过 3 天"), c.text());
        assertTrue(c.text().contains("10.3 天"), c.text());
    }

    // ── 刷新语义 ──

    @Test
    void regenerate_sameData_idempotent() {
        List<TradeRound> data = List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0));
        stubRounds(data);
        int first = svc.generateCandidates(USER).total();
        int second = svc.generateCandidates(USER).total();
        assertEquals(first, second, "同数据重复生成不重复添加");
    }

    @Test
    void regenerate_candidateRefreshes_whenDataChanges() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0)));
        String before = svc.generateCandidates(USER).candidates().stream()
                .filter(x -> x.id().equals("cand-stoploss")).findFirst().orElseThrow().text();

        // 数据变了：多一笔亏 20%
        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0),
                r("600250_2026-08-06", "-20", 3, null, 0)));
        String after = svc.generateCandidates(USER).candidates().stream()
                .filter(x -> x.id().equals("cand-stoploss")).findFirst().orElseThrow().text();

        assertFalse(before.equals(after), "数据变了候选数字跟着变：" + before + " → " + after);
        assertTrue(after.contains("6 次亏损"),
                "第一笔的候选列表长度变了（6 笔）");
    }

    @Test
    void regenerate_candidateRemoved_whenNoLongerSupported() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0)));
        assertTrue(svc.generateCandidates(USER).candidates().stream()
                .anyMatch(x -> x.id().equals("cand-stoploss")));

        // 数据换成全盈利 → 不再支持止损候选 → 如实撤下
        stubRounds(List.of(
                r("600206_2026-08-01", "5", 3, null, 0),
                r("600210_2026-08-02", "5", 3, null, 0)));
        assertTrue(svc.generateCandidates(USER).candidates().stream()
                .noneMatch(x -> x.id().equals("cand-stoploss")), "数据不再支持就撤下（不硬留）");
    }

    /** 永不覆盖：已认下的条目，数据再变也不动。 */
    @Test
    void regenerate_neverOverwritesAccepted() {
        List<TradeRound> data = List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0));
        stubRounds(data);
        svc.generateCandidates(USER);
        UserRule accepted = svc.accept(USER, "cand-stoploss", "我自己的止损线：亏到 -4% 就走");
        assertEquals(UserRule.UserRuleState.ACCEPTED, accepted.state());

        stubRounds(data);   // 同数据再生成
        svc.generateCandidates(USER);

        List<UserRule> all = svc.list(USER).accepted();
        assertEquals(1, all.size());
        assertEquals("我自己的止损线：亏到 -4% 就走", all.get(0).text(), "已认的文本永不覆盖");
        assertEquals(UserRule.UserRuleState.ACCEPTED, all.get(0).state());
        assertNotNull(all.get(0).evidence(), "认下保留「当时为什么长出来」的依据");
    }

    // ── 三态操作 ──

    @Test
    void accept_withoutText_keepsOriginal() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0)));
        String original = svc.generateCandidates(USER).candidates().stream()
                .filter(x -> x.id().equals("cand-stoploss")).findFirst().orElseThrow().text();

        UserRule accepted = svc.accept(USER, "cand-stoploss", null);

        assertEquals(original, accepted.text(), "不改就保原文");
    }

    @Test
    void edit_accepted_works_candidateRejected() {
        svc.createCustom(USER, "自建一条", Map.of());   // 占位
        UserRule custom = svc.list(USER).custom().get(0);
        UserRule edited = svc.edit(USER, custom.id(), "改过的文本");
        assertEquals("改过的文本", edited.text());

        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0)));
        svc.generateCandidates(USER);
        assertThrows(IllegalArgumentException.class,
                () -> svc.edit(USER, "cand-stoploss", "候选不能直接改"),
                "候选要先认下");
    }

    @Test
    void accept_custom_rejected_andUnknownRejected() {
        svc.createCustom(USER, "自建一条", Map.of());
        String customId = svc.list(USER).custom().get(0).id();

        assertThrows(IllegalArgumentException.class, () -> svc.accept(USER, customId, null),
                "自己写的不用认");
        assertThrows(IllegalArgumentException.class, () -> svc.accept(USER, "cand-不存在", null));
    }

    @Test
    void custom_createAndDismiss() {
        UserRule r = svc.createCustom(USER, "不追新股", Map.of("x", 1));
        assertEquals(UserRule.UserRuleState.CUSTOM, r.state());
        assertEquals(UserRule.RuleSource.USER, r.source());
        assertTrue(r.id().startsWith("usr-"));
        assertFalse(svc.list(USER).custom().isEmpty());

        svc.dismiss(USER, r.id());
        assertTrue(svc.list(USER).custom().isEmpty(), "弃掉后列表里没有了");

        svc.dismiss(USER, r.id());   // 幂等
    }

    /** P1-交易93：弃掉的候选留墓碑——同数据再生成不得复活（原实现直接删 → 下次刷新又出现）。 */
    @Test
    void dismiss_candidate_tombstoned_neverResurrects() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0)));
        assertEquals(2, svc.generateCandidates(USER).candidates().size(), "5 笔亏损 → 止损 + 短线超期两条候选");

        svc.dismiss(USER, "cand-stoploss");
        assertTrue(svc.list(USER).candidates().stream().noneMatch(x -> x.id().equals("cand-stoploss")),
                "弃掉后不在候选分组");
        assertEquals(1, svc.list(USER).total(), "墓碑不参与计数（只剩短线超期一条）");

        svc.generateCandidates(USER);
        assertTrue(svc.list(USER).candidates().stream().noneMatch(x -> x.id().equals("cand-stoploss")),
                "同数据再生成：划掉的候选不得复活（P1-交易93）");
        assertFalse(svc.list(USER).candidates().isEmpty(), "墓碑只挡它自己，其他候选照常生成");

        // 墓碑也不能再认 / 改（视同不存在——否则等于静默复活）
        assertThrows(IllegalArgumentException.class, () -> svc.accept(USER, "cand-stoploss", null));
        svc.dismiss(USER, "cand-stoploss");   // 幂等：已弃的再弃不报错
    }

    /** P1-交易93 同族：已认后再弃（「这条我不用了」）同样留墓碑——否则 generateCandidates 拿同一 id 重新长出候选。 */
    @Test
    void dismiss_accepted_alsoTombstoned_notResurrected() {
        stubRounds(List.of(
                r("600206_2026-08-01", "-1", 3, null, 0),
                r("600210_2026-08-02", "-2", 3, null, 0),
                r("600220_2026-08-03", "-3", 3, null, 0),
                r("600230_2026-08-04", "-4", 3, null, 0),
                r("600240_2026-08-05", "-5", 3, null, 0)));
        svc.generateCandidates(USER);
        svc.accept(USER, "cand-stoploss", "我的止损线");
        assertEquals(1, svc.list(USER).accepted().size());

        svc.dismiss(USER, "cand-stoploss");
        assertTrue(svc.list(USER).accepted().isEmpty(), "弃掉的不在已认分组");

        svc.generateCandidates(USER);
        assertTrue(svc.list(USER).candidates().stream().noneMatch(x -> x.id().equals("cand-stoploss")),
                "已认后弃的也不能在下次生成时复活成候选（P1-交易93）");
    }

    @Test
    void createCustom_blankRejected() {
        assertThrows(IllegalArgumentException.class, () -> svc.createCustom(USER, "  ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> svc.createCustom(USER, null, Map.of()));
    }

    @Test
    void edit_blankRejected() {
        assertThrows(IllegalArgumentException.class, () -> svc.edit(USER, "any", " "));
    }
}
