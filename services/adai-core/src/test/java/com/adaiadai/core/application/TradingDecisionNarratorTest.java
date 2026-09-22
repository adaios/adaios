package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingDecisionNarratorTest — 四要素铁证的渲染契约（RFC `20260922` B 批）。
 *
 * <p>钉住两条红线：
 * <ol>
 *   <li><b>四要素逐条可指认</b>：① 本人历史统计 / ② 数字证据链 / ③ 规则逐字原文 / ④ 位置；</li>
 *   <li><b>缺证据就闭嘴</b>：③ 取不到原文、行情取不到 → 返回 empty（调用方不得兜底编造）；
 *       ① 样本不足 → **如实说「样本还不够」**，绝不拿一两次巧合当规律。</li>
 * </ol>
 */
class TradingDecisionNarratorTest {

    private final TradingEvidenceService evidence = mock(TradingEvidenceService.class);
    private final SoldTradeRepository sold = mock(SoldTradeRepository.class);
    private final TradingRuleSettingsRepository settings = mock(TradingRuleSettingsRepository.class);

    private TradingDecisionNarrator narrator;

    @BeforeEach
    void setUp() {
        narrator = new TradingDecisionNarrator(evidence, sold, settings);
        lenient().when(sold.findAll(any())).thenReturn(List.of());
        lenient().when(settings.findByUser(any())).thenReturn(TradingRuleSettings.defaults());
    }

    // ── 夹具 ──

    private static MarketData quote(String symbol, String name, String price, String changePct) {
        return new MarketData(symbol, name, new BigDecimal(price), new BigDecimal(price),
                new BigDecimal(price), new BigDecimal("11.00"), new BigDecimal("9.50"),
                changePct == null ? null : new BigDecimal(changePct), 1000L);
    }

    private static Position pos(String symbol, String name, String avgCost, String currentPrice,
                                String stopLoss, String buyPoint, int qty) {
        return new Position(symbol, name, qty, new BigDecimal(avgCost), new BigDecimal(currentPrice),
                LocalDateTime.now(), LocalDate.of(2026, 8, 1), new BigDecimal(stopLoss), buyPoint, null);
    }

    private static SoldTrade sold(double pnlPct) {
        return new SoldTrade("600487", "亨通光电", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4),
                3, "1+1", pnlPct, "", "", "import");
    }

    private void stubRule(int number, String title, String detail) {
        when(evidence.ruleTextOf("R" + number))
                .thenReturn(Optional.of(new TradingEvidenceService.RuleText(number, title, detail)));
    }

    private void stubHistory(TradingEvidenceService.Dimension dim,
                             TradingEvidenceService.HistoryBucket... buckets) {
        boolean anySufficient = List.of(buckets).stream()
                .anyMatch(TradingEvidenceService.HistoryBucket::sufficient);
        when(evidence.historyStats("adai", dim)).thenReturn(new TradingEvidenceService.HistoryStats(
                dim, List.of(buckets), List.of(buckets).stream().mapToInt(
                        TradingEvidenceService.HistoryBucket::count).sum(), anySufficient, "note"));
    }

    private static WatchlistBuyPointService.WatchBuyPoint buyHit(String form) {
        return new WatchlistBuyPointService.WatchBuyPoint("600487", "亨通光电", form, 80,
                List.of("缩量到 0.6 倍", "KDJ.J=11 拐头向上"), List.of(), "2026-09-19");
    }

    // ── 买点 ──

    @Test
    void buyPointBlock_rendersAllFourElements() {
        stubRule(33, "B1三段条件", "连续下跌 → N型转折 → KDJ大负值 → 缩量确认，满足全部四条才视为B1。");
        stubHistory(TradingEvidenceService.Dimension.BUY_POINT,
                new TradingEvidenceService.HistoryBucket("B1", 6, 4, 4.0 / 6, 2.8, 2.0, true));
        when(sold.findAll("adai")).thenReturn(List.of(sold(-3), sold(-5), sold(-4), sold(-6)));

        var block = narrator.buyPointBlock("adai", buyHit("B1"),
                quote("600487", "亨通光电", "18.42", "2.0"));

        assertTrue(block.isPresent());
        String text = block.get().text();
        assertTrue(text.contains("亨通光电（600487） 昨收 18.42（数据到 2026-09-19）"),
                "早盘 09:15 未开盘，价格口径是昨收且必须带数据日期，实际: " + text);
        assertTrue(text.contains("① 你的历史：你过去 6 次在「B1」买入，4 次盈利、平均 +2.8%、平均持 2 天"), text);
        assertTrue(text.contains("② 证据：缩量到 0.6 倍 · KDJ.J=11 拐头向上 · 你的参数：KDJ.J < 13 · 缩量 < 0.7 倍"), text);
        assertTrue(text.contains("③ 规则：《B1三段条件》R33 原文「连续下跌"), "规则原文必须逐字，实际: " + text);
        assertTrue(text.contains("④ 位置：按你自己的止损习惯（4 次亏损了结、平均 -4.5%），这只见位约 17.59"), text);
        assertEquals(List.of("R33"), block.get().ruleRefs());
    }

    @Test
    void buyPointBlock_insufficientSample_saysSoInsteadOfFakeStats() {
        stubRule(33, "B1三段条件", "原文");
        stubHistory(TradingEvidenceService.Dimension.BUY_POINT,
                new TradingEvidenceService.HistoryBucket("B1", 2, 2, 1.0, 3.0, 2.0, false));

        String text = narrator.buyPointBlock("adai", buyHit("B1"),
                quote("600487", "亨通光电", "18.42", "2.0")).orElseThrow().text();

        assertTrue(text.contains("样本还不够（2 次，满 5 次我才给统计）"), text);
        assertFalse(text.contains("你过去 2 次在「B1」买入"), "样本不足不得给统计: " + text);
    }

    @Test
    void buyPointBlock_noHistoryAtAll_saysNeverBought() {
        stubRule(33, "B1三段条件", "原文");
        stubHistory(TradingEvidenceService.Dimension.BUY_POINT,
                new TradingEvidenceService.HistoryBucket("B2", 6, 4, 0.6, 2.0, 3.0, true));

        String text = narrator.buyPointBlock("adai", buyHit("B1"),
                quote("600487", "亨通光电", "18.42", "2.0")).orElseThrow().text();

        assertTrue(text.contains("你还没在「B1」上买过"), text);
    }

    @Test
    void buyPointBlock_missingRuleText_returnsEmpty() {
        // ③ 拿不到原文 → 整条不发（宁可不给，也不复述成「大概是这个意思」）
        when(evidence.ruleTextOf("R33")).thenReturn(Optional.empty());
        assertTrue(narrator.buyPointBlock("adai", buyHit("B1"),
                quote("600487", "亨通光电", "18.42", "2.0")).isEmpty());
    }

    @Test
    void buyPointBlock_formWithoutRuleInLibrary_returnsEmpty() {
        // B3 在规则库没有可逐字引用的条目 → 不编
        assertTrue(narrator.buyPointBlock("adai", buyHit("B3"),
                quote("600487", "亨通光电", "18.42", "2.0")).isEmpty());
    }

    @Test
    void buyPointBlock_missingQuote_returnsEmpty() {
        stubRule(33, "B1三段条件", "原文");
        assertTrue(narrator.buyPointBlock("adai", buyHit("B1"), null).isEmpty());
        MarketData noPrice = new MarketData("600487", "亨通光电", null, null, null, null, null, null, 0L);
        assertTrue(narrator.buyPointBlock("adai", buyHit("B1"), noPrice).isEmpty());
    }

    // ── 卖点 ──

    @Test
    void sellPointBlock_rendersAllFourElements() {
        stubRule(66, "止损位跌破即离场", "现价跌破你设的止损位 → 按纪律离场，不扛单。");
        stubHistory(TradingEvidenceService.Dimension.PNL_BUCKET,
                new TradingEvidenceService.HistoryBucket("+5~10%", 8, 7, 7.0 / 8, 7.1, 4.0, true));
        Position p = pos("600519", "贵州茅台", "1400.00", "1486.80", "1380.00", "B2", 100);

        var block = narrator.sellPointBlock("adai", p, quote("600519", "贵州茅台", "1486.80", "6.2"),
                new BigDecimal("96.5"), "清仓参考（R66）", List.of("R66"));

        assertTrue(block.isPresent());
        String text = block.get().text();
        assertTrue(text.contains("贵州茅台（600519） 现价 1486.8") && text.contains("→ 清仓参考（R66）"), text);
        assertTrue(text.contains("① 你的历史：你过去 8 次在「+5~10%」了结，7 次盈利、平均 +7.1%、平均持 4 天"), text);
        assertTrue(text.contains("② 证据：成本 1400 · 占比 96.5% · 止损 1380 · 持有 100 股"), text);
        assertTrue(text.contains("③ 规则：《止损位跌破即离场》R66 原文「现价跌破"), text);
        assertTrue(text.contains("④ 位置：这笔若现在了结，约 +6.2%（未扣手续费） · 距你的止损位 1380 还有 +7.74%"),
                text);
    }

    @Test
    void sellPointBlock_ruleTextMissing_returnsEmpty() {
        when(evidence.ruleTextOf("R66")).thenReturn(Optional.empty());
        Position p = pos("600519", "贵州茅台", "1400.00", "1486.80", "1380.00", "B2", 100);
        assertTrue(narrator.sellPointBlock("adai", p, quote("600519", "贵州茅台", "1486.80", "6.2"),
                new BigDecimal("96.5"), "清仓参考（R66）", List.of("R66")).isEmpty());
    }

    @Test
    void sellPointBlock_priceMissing_returnsEmpty() {
        Position p = pos("600519", "贵州茅台", "1400.00", "1486.80", "1380.00", "B2", 100);
        assertTrue(narrator.sellPointBlock("adai", p, null, BigDecimal.TEN, "持有", List.of()).isEmpty());
    }

    @Test
    void sellPointBlock_noRuleRefs_omitsRuleLineButStillRenders() {
        Position p = pos("600519", "贵州茅台", "1400.00", "1486.80", "1380.00", "B2", 100);
        String text = narrator.sellPointBlock("adai", p, quote("600519", "贵州茅台", "1486.80", "6.2"),
                BigDecimal.TEN, "持有", List.of()).orElseThrow().text();
        assertFalse(text.contains("③ 规则："), "没有规则引用就不该硬凑一条: " + text);
        assertTrue(text.contains("① 你的历史："), text);
    }

    @Test
    void sellPointBlock_negativeCostPosition_pnlIsHonest() {
        // 负成本（券商口径可能为负）：百分比无意义 → 如实说算不出，不编一个符号翻转的数
        Position p = new Position("600601", "方正科技", 100, new BigDecimal("-1.20"),
                new BigDecimal("5.00"), LocalDateTime.now(), LocalDate.of(2026, 8, 1),
                new BigDecimal("4.50"), "B1", null);
        String text = narrator.sellPointBlock("adai", p, quote("600601", "方正科技", "5.00", "0"),
                BigDecimal.TEN, "持有", List.of()).orElseThrow().text();
        assertTrue(text.contains("成本口径不完整"), text);
    }

    @Test
    void stopHabit_withTooFewLosers_saysItCannotTell() {
        stubRule(33, "B1三段条件", "原文");
        stubHistory(TradingEvidenceService.Dimension.BUY_POINT,
                new TradingEvidenceService.HistoryBucket("B1", 6, 4, 0.6, 2.0, 2.0, true));
        when(sold.findAll("adai")).thenReturn(List.of(sold(-3))); // 只有 1 次亏损了结

        String text = narrator.buyPointBlock("adai", buyHit("B1"),
                quote("600487", "亨通光电", "18.42", "2.0")).orElseThrow().text();

        assertTrue(text.contains("你的亏损了结记录还太少（1 次）"), text);
    }
}
