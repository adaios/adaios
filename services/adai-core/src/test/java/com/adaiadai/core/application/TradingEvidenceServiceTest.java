package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.engine.TradingRuleEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingEvidenceServiceTest — 「四要素铁证」底座（RFC 20260922 A 批，2026-09-22）。
 *
 * <p>覆盖两件最要紧的事：① <b>样本门槛</b>——不足 N≥5 的桶必须标记 sufficient=false，
 * 且全组不足时人话直说「样本还不够」（不许拿 1-2 次巧合当规律）；② <b>规则原文只做搬运</b>——
 * 逐字来自 rules.md（经 {@link TradingRuleEngine#parseRules}），读不到就返回空，
 * **绝不编造一条规则**。
 */
class TradingEvidenceServiceTest {

    private static final String USER = "adai";

    @TempDir
    Path tmp;

    private static SoldTrade round(int holdDays, double pnlPct, String verdict) {
        return new SoldTrade("600206", "有研新材",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1).plusDays(holdDays),
                holdDays, "", pnlPct, verdict, null);
    }

    private TradingEvidenceService service(SoldTradeRepository repo, TradingRuleEngine engine) {
        return new TradingEvidenceService(repo, mock(TradingLotService.class), engine, tmp.toString());
    }

    private TradingEvidenceService serviceWith(SoldTradeRepository repo, TradingRuleEngine engine,
                                               Path dir) {
        return new TradingEvidenceService(repo, mock(TradingLotService.class), engine, dir.toString());
    }

    private static TradingLotService.TradingLotView lot(String symbol, LocalDate buyDate, String buyPoint) {
        return new TradingLotService.TradingLotView(
                symbol + "_" + buyDate, symbol, symbol + "名", buyDate,
                100, 100, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(1000),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, buyPoint, null,
                false, false, null, BigDecimal.ZERO);
    }

    // ── ① 历史统计 ──────────────────────────────────────────────────────

    @Test
    void historyStats_holdDaysBuckets_winRateAndThreshold() {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                // ≤1 天：5 次 4 胜（样本够）
                round(1, 2.0, "R53"), round(1, 1.0, "R53"), round(1, -1.0, "R53"),
                round(1, 3.0, "R53"), round(1, 4.0, "R53"),
                // 2-3 天：2 次（样本不够 → 不得当规律）
                round(3, 5.0, null), round(3, -2.0, null)));
        TradingEvidenceService svc = service(repo, mock(TradingRuleEngine.class));

        TradingEvidenceService.HistoryStats stats =
                svc.historyStats(USER, TradingEvidenceService.Dimension.HOLD_DAYS);

        assertEquals(7, stats.totalRounds());
        assertEquals(2, stats.buckets().size());
        TradingEvidenceService.HistoryBucket shortHold = stats.buckets().get(0);
        assertEquals("≤1 天", shortHold.label());
        assertEquals(5, shortHold.count());
        assertEquals(4, shortHold.wins());
        assertEquals(0.8, shortHold.winRate(), 1e-9);
        assertEquals(1.0, shortHold.avgHoldDays(), 1e-9);
        assertTrue(shortHold.sufficient(), "5 次达到门槛");

        TradingEvidenceService.HistoryBucket midHold = stats.buckets().get(1);
        assertEquals("2-3 天", midHold.label());
        assertEquals(2, midHold.count());
        assertFalse(midHold.sufficient(), "样本 < 5：不许当规律引用");
        assertTrue(stats.anySufficient());
        assertTrue(stats.note().contains("只报样本 ≥ 5"), stats.note());
    }

    @Test
    void historyStats_allBucketsThin_saysSampleNotEnough() {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                round(1, 2.0, null), round(5, -1.0, null)));
        TradingEvidenceService svc = service(repo, mock(TradingRuleEngine.class));

        TradingEvidenceService.HistoryStats stats =
                svc.historyStats(USER, TradingEvidenceService.Dimension.HOLD_DAYS);

        assertFalse(stats.anySufficient());
        assertTrue(stats.note().contains("样本还不够"), stats.note());
        assertTrue(stats.buckets().stream().noneMatch(TradingEvidenceService.HistoryBucket::sufficient));
    }

    @Test
    void historyStats_noRounds_isHonestAboutEmpty() {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingEvidenceService svc = service(repo, mock(TradingRuleEngine.class));

        TradingEvidenceService.HistoryStats stats =
                svc.historyStats(USER, TradingEvidenceService.Dimension.HOLD_DAYS);

        assertEquals(0, stats.totalRounds());
        assertTrue(stats.buckets().isEmpty());
        assertTrue(stats.note().contains("还没有清仓回合"), stats.note());
    }

    @Test
    void historyStats_pnlBucket_boundariesAreClosedAtLowerEdge() {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                round(2, 12.0, null), round(2, 10.0, null),   // ≥+10%
                round(2, 9.0, null),                          // +5~10%
                round(2, 0.0, null),                          // 0~+5%（0 不算盈）
                round(2, -5.0, null),                         // -5~0%
                round(2, -6.0, null)));                       // <-5%
        TradingEvidenceService svc = service(repo, mock(TradingRuleEngine.class));

        var stats = svc.historyStats(USER, TradingEvidenceService.Dimension.PNL_BUCKET);
        assertEquals(List.of("≥+10%", "+5~10%", "0~+5%", "-5~0%", "<-5%"),
                stats.buckets().stream().map(TradingEvidenceService.HistoryBucket::label).toList());
        var top = stats.buckets().get(0);
        assertEquals(2, top.count());
        assertEquals(2, top.wins());
        assertEquals(11.0, top.avgPnlPct(), 1e-9);
        var flat = stats.buckets().get(2);
        assertEquals(0, flat.wins(), "0% 不算盈（与既有胜率口径一致）");
    }

    @Test
    void historyStats_verdict_isDynamicAndBlankBecomesUnjudged() {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                round(2, 1.0, "R66"), round(2, -1.0, "R66"), round(2, 2.0, "R66"),
                round(2, 3.0, "R53"), round(2, 1.0, null), round(2, 1.0, "")));
        TradingEvidenceService svc = service(repo, mock(TradingRuleEngine.class));

        var stats = svc.historyStats(USER, TradingEvidenceService.Dimension.VERDICT);
        var labels = stats.buckets().stream().map(TradingEvidenceService.HistoryBucket::label).toList();
        assertTrue(labels.contains("R66"));
        assertTrue(labels.contains("R53"));
        assertTrue(labels.contains("（未判定）"), "空 verdict 归「未判定」，不伪装成某条规则");
    }

    /** A1 的「按形态分组」：形态记在**批次**上，join 不上就明说「未标形态」——**不猜**。 */
    @Test
    void historyStats_buyPointDimension_joinsLots_andMarksUnknownWhenMissing() {
        LocalDate d1 = LocalDate.of(2026, 9, 1);
        LocalDate d2 = LocalDate.of(2026, 9, 2);
        LocalDate d3 = LocalDate.of(2026, 9, 3);
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                new SoldTrade("600206", "有研新材", d1, d1.plusDays(2), 2, "", 2.0, null, null),
                new SoldTrade("600206", "有研新材", d2, d2.plusDays(2), 2, "", -1.0, null, null),
                new SoldTrade("000831", "中国稀土", d3, d3.plusDays(2), 2, "", 3.0, null, null)));
        TradingLotService lots = mock(TradingLotService.class);
        when(lots.lots(anyString(), anyString())).thenReturn(List.of(
                lot("600206", d1, "B2"),
                lot("600206", d2, "B2"))); // 000831 那笔没有批次 → 未标形态

        TradingEvidenceService svc = new TradingEvidenceService(
                repo, lots, mock(TradingRuleEngine.class), tmp.toString());
        TradingEvidenceService.HistoryStats stats =
                svc.historyStats(USER, TradingEvidenceService.Dimension.BUY_POINT);

        TradingEvidenceService.HistoryBucket b2 = stats.buckets().stream()
                .filter(b -> "B2".equals(b.label())).findFirst().orElseThrow();
        TradingEvidenceService.HistoryBucket unmarked = stats.buckets().stream()
                .filter(b -> "未标形态".equals(b.label())).findFirst().orElseThrow();
        assertEquals(2, b2.count(), "两笔能 join 上批次的归 B2");
        assertEquals(1, unmarked.count(), "join 不上就归「未标形态」，不猜一个形态出来");
        assertFalse(b2.sufficient(), "2 次 < 5 → 不许当规律引用");
    }

    /** 批次取数失败 → 形态维度整体降级为「未标形态」，不抛错、不阻断。 */
    @Test
    void historyStats_buyPoint_lotsFailure_degradesToUnmarked() {
        LocalDate d1 = LocalDate.of(2026, 9, 1);
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                new SoldTrade("600206", "有研新材", d1, d1.plusDays(2), 2, "", 2.0, null, null)));
        TradingLotService lots = mock(TradingLotService.class);
        when(lots.lots(anyString(), anyString())).thenThrow(new IllegalStateException("批次文件损坏"));

        TradingEvidenceService svc = new TradingEvidenceService(
                repo, lots, mock(TradingRuleEngine.class), tmp.toString());
        var stats = svc.historyStats(USER, TradingEvidenceService.Dimension.BUY_POINT);

        assertEquals("未标形态", stats.buckets().get(0).label());
        assertEquals(1, stats.totalRounds(), "总数照常报，不因维度降级而丢回合");
    }

    // ── ③ 规则原文 ──────────────────────────────────────────────────────

    @Test
    void ruleText_isVerbatimFromRulesFile() throws Exception {
        Files.writeString(tmp.resolve("rules.md"), "**R66 只输一根K线**\n> 核心理念：只输一根K线。\n");
        TradingRuleEngine engine = mock(TradingRuleEngine.class);
        when(engine.parseRules(anyString())).thenReturn(List.of(
                new TradingRuleEngine.RuleEntry(66, "只输一根K线", "核心理念：只输一根K线。")));
        TradingEvidenceService svc = service(mock(SoldTradeRepository.class), engine);

        var r = svc.ruleText(66).orElseThrow();
        assertEquals(66, r.number());
        assertEquals("只输一根K线", r.title());
        assertEquals("核心理念：只输一根K线。", r.detail(), "原文逐字搬运，不做改写");
    }

    @Test
    void ruleTextOf_acceptsCommonReferenceForms() throws Exception {
        Files.writeString(tmp.resolve("rules.md"), "**R66 只输一根K线**\n> 核心理念。\n");
        TradingRuleEngine engine = mock(TradingRuleEngine.class);
        when(engine.parseRules(anyString())).thenReturn(List.of(
                new TradingRuleEngine.RuleEntry(66, "只输一根K线", "核心理念。")));
        TradingEvidenceService svc = service(mock(SoldTradeRepository.class), engine);

        assertTrue(svc.ruleTextOf("R66").isPresent());
        assertTrue(svc.ruleTextOf("r66").isPresent());
        assertTrue(svc.ruleTextOf("66").isPresent());
        assertTrue(svc.ruleTextOf("R 66").isPresent());
        assertTrue(svc.ruleTextOf(null).isEmpty());
        assertTrue(svc.ruleTextOf("R").isEmpty());
        assertTrue(svc.ruleTextOf("R999").isEmpty(), "没有的规则就是没有，不编");
    }

    @Test
    void allRules_missingFile_returnsEmpty_andDoesNotFabricate() {
        TradingRuleEngine engine = mock(TradingRuleEngine.class);
        TradingEvidenceService svc = serviceWith(mock(SoldTradeRepository.class), engine,
                tmp.resolve("not-there"));

        assertTrue(svc.allRules().isEmpty(), "规则文件读不到 → 空（调用方不给引用，而不是编一条）");
        assertTrue(svc.ruleText(66).isEmpty());
    }
}
