package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradingProfileService;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingProfileService — 个人交易画像测试（RFC 20260905 A 层）。
 * <p>
 * 覆盖：客观统计（胜率/纪律违反率/中位盈亏/分布）、无清仓史降级空、
 * 主观层 profile.md 读写、组合注入文本。
 */
class TradingProfileServiceTest {

    private SoldTrade sold(String symbol, double pnl, int holdDays, String verdict) {
        return new SoldTrade(symbol, "测试", LocalDate.now().minusDays(holdDays),
                LocalDate.now(), holdDays, "5+1", pnl, verdict, "");
    }

    private TradingProfileService service(List<SoldTrade> sold) {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(sold);
        return new TradingProfileService(repo, new InMemoryFileStorage(),
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));
    }

    @Test
    void computeStats_aggregatesDisciplineAndWinRate() {
        TradingProfileService svc = service(List.of(
                sold("600584", -29.22, 12, "扛单超 5%——按 R66 只输一根K线"),
                sold("000725", 5.0, 30, "盈利了结"),
                sold("002131", -2.5, 3, "短持仓亏损——按 R53 没涨=错"),
                sold("000547", 20.0, 100, "盈利了结")));

        TradingProfileService.TradingProfileStats s = svc.computeStats("default");

        assertEquals(4, s.soldCount());
        assertEquals(50.0, s.winRatePct());
        assertEquals(50.0, s.disciplineViolationRatePct(), "扛单+短打 2/4 违反纪律");
        assertEquals(2, s.disciplineViolationCount());
        assertEquals(5.0, s.medianPnlPct(), "4 元素排序取上中位 pnls.get(2)=5.0");
        assertEquals(36, s.avgHoldDays(), "(12+30+3+100)/4=36");
        assertEquals(2, s.verdictBreakdown().get("盈利了结"));
    }

    @Test
    void computeStats_lossHoldNotCountedAsViolation() {
        // P2-认知1（2026-09-05 用户拍板 A）：「亏损持仓」普通亏损不计违纪——违纪只算扛单+短打
        TradingProfileService svc = service(List.of(
                sold("600584", -29.22, 12, "扛单超 5%——按 R66 只输一根K线"),
                sold("601888", -3.0, 60, "亏损持仓——按纪律复盘：止损/卖点是否按计划执行（R53）"),
                sold("000725", 5.0, 30, "盈利了结")));

        TradingProfileService.TradingProfileStats s = svc.computeStats("default");

        assertEquals(3, s.soldCount());
        assertEquals(1, s.disciplineViolationCount(), "扛单 1 笔算违纪；亏损持仓（-3% 拿 60 天）不计");
        assertEquals(33.3, s.disciplineViolationRatePct());
    }

    @Test
    void computeStats_noSold_returnsZero() {
        TradingProfileService svc = service(List.of());
        TradingProfileService.TradingProfileStats s = svc.computeStats("default");
        assertEquals(0, s.soldCount());
        assertEquals(0.0, s.winRatePct());
        assertTrue(s.verdictBreakdown().isEmpty());
    }

    @Test
    void objectiveProfileText_smallSample_showsCollectingNote() {
        // 🤔18（2026-09-05 对抗审）：<10 笔只给笔数不给胜率/纪律率（小样本勿下画像判断）
        TradingProfileService svc = service(List.of(
                sold("600584", -10.0, 12, "扛单超 5%——按 R66"),
                sold("000725", 8.0, 40, "盈利了结")));

        String text = svc.objectiveProfileText("default");

        assertTrue(text.contains("2 笔"), "应含清仓笔数");
        assertTrue(text.contains("样本不足"), "小样本应提示样本不足");
        assertFalse(text.contains("胜率 5"), "小样本不得给胜率数值（防误读）");
        assertTrue(text.contains("你的交易画像"), "段落标题");
    }

    @Test
    void objectiveProfileText_largeSample_includesStats() {
        // ≥10 笔才注入胜率/纪律率/分布
        java.util.List<SoldTrade> soldList = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) soldList.add(sold("000" + (100 + i), 5.0, 20, "盈利了结"));
        for (int i = 0; i < 4; i++) soldList.add(sold("100" + (200 + i), -12.0, 15, "扛单超 5%——按 R66"));
        TradingProfileService svc = service(soldList);

        String text = svc.objectiveProfileText("default");

        assertTrue(text.contains("12 笔"), "应含清仓笔数");
        assertTrue(text.contains("胜率"), "≥10 笔应有胜率");
        assertTrue(text.contains("纪律违反率"), "≥10 笔应有纪律违反率");
    }

    @Test
    void objectiveProfileText_noSold_returnsEmpty() {
        TradingProfileService svc = service(List.of());
        assertEquals("", svc.objectiveProfileText("default"));
    }

    @Test
    void subjectiveProfile_roundtrip_viaFile() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingProfileService svc = new TradingProfileService(repo, storage,
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));

        svc.saveProfile("default", "# 主观层\n\n你追高的手比抄底大 3 倍。");
        assertTrue(svc.rawProfile("default").contains("追高的手"));
        assertTrue(svc.subjectiveProfileText("default").contains("追高的手"));
        assertTrue(svc.subjectiveProfileText("default").contains("你的画像（主观层"), "应带注入前缀标题");
    }

    @Test
    void profileText_combinesObjectiveAndSubjective() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                sold("600584", -10.0, 12, "扛单超 5%")));
        TradingProfileService svc = new TradingProfileService(repo, storage,
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));
        svc.saveProfile("default", "S1 追高手大于抄底手。");

        String text = svc.profileText("default");
        assertTrue(text.contains("你的交易画像"), "客观层");
        assertTrue(text.contains("S1"), "主观层");
    }

    @Test
    void profileText_noDataAtAll_returnsEmpty() {
        TradingProfileService svc = service(List.of());
        assertEquals("", svc.profileText("default"));
    }

    // ── RFC 20260905 P2：建议遵守率（B 反哺 A）──

    private SoldTrade soldSellDate(String symbol, LocalDate sellDate, double pnl, String verdict) {
        return new SoldTrade(symbol, "测试", sellDate.minusDays(30), sellDate, 30, "5+1", pnl, verdict, "");
    }

    private com.adaiadai.core.domain.trading.AdviceEntry advice(String symbol, LocalDate date, String suggestion) {
        return new com.adaiadai.core.domain.trading.AdviceEntry("adv_1", date, symbol, "测试",
                suggestion, "按 R66", List.of("R66"), true,
                new java.math.BigDecimal("10"), "manual-advice", date.atTime(14, 0));
    }

    @Test
    void computeAdviceAdherence_countsFollowed() {
        LocalDate sell = LocalDate.of(2026, 9, 5);
        // 600584：建议 clear（9-1）→ 9-5 清仓 = 遵守；000725：无卖前 clear/reduce → 不计
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                soldSellDate("600584", sell, -29.22, "扛单超 5%"),
                soldSellDate("000725", sell, 5.0, "盈利了结")));
        com.adaiadai.core.domain.trading.AdviceHistoryRepository history =
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class);
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).atDay(1)))).thenReturn(List.of(
                advice("600584", sell.minusDays(4), "clear"),
                advice("000725", sell.minusDays(20), "hold")));
        TradingProfileService svc = new TradingProfileService(repo, new InMemoryFileStorage(), history);

        TradingProfileService.AdviceAdherence a = svc.computeAdviceAdherence("default");

        assertEquals(1, a.withAdviceCount(), "只有 600584 有卖前 clear/reduce 建议（hold 不计）");
        assertEquals(1, a.followedCount(), "建议 9-1、清仓 9-5 → 4 天内执行 = 遵守");
        assertEquals(100.0, a.followRatePct());
    }

    @Test
    void computeAdviceAdherence_lateExit_countsViolation() {
        LocalDate sell = LocalDate.of(2026, 9, 5);
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                soldSellDate("600584", sell, -29.22, "扛单超 5%")));
        com.adaiadai.core.domain.trading.AdviceHistoryRepository history =
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class);
        // 建议 9-1（卖前 4 天），但拖到 9-5 才清？→ 4 天内其实遵守。改：建议在卖前窗口内但清仓距建议 >10 天不可达——
        // 用「建议在 8-20（卖前 16 天）」模拟违反窗口（>10 天且仍在卖前 30 天窗内）
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).atDay(1)))).thenReturn(List.of(
                advice("600584", sell.minusDays(16), "clear")));
        // 8 月也有留痕（窗口跨月场景）——清空避免干扰
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).minusMonths(1).atDay(1))))
                .thenReturn(List.of());
        TradingProfileService svc = new TradingProfileService(repo, new InMemoryFileStorage(), history);

        TradingProfileService.AdviceAdherence a = svc.computeAdviceAdherence("default");

        assertEquals(1, a.withAdviceCount());
        assertEquals(0, a.followedCount(), "建议后 16 天才清仓 → 超过 10 天窗口 = 未遵守");
        assertEquals(0.0, a.followRatePct());
    }

    @Test
    void computeAdviceAdherence_oldSoldStillCounted_withSellDateAnchoredWindow() {
        // P1-1 回归（2026-09-05 三官）：60 天前清仓（now 窗口外）卖前有 clear 建议 → 仍计入遵守率
        // 原实现用 now() 窗口，旧清仓全被漏掉（遵守率虚高）——现在以 sellDate 为锚
        LocalDate sell = LocalDate.of(2026, 7, 1); // 距 now(9月) 60+ 天
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                soldSellDate("600584", sell, -29.22, "扛单超 5%")));
        com.adaiadai.core.domain.trading.AdviceHistoryRepository history =
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class);
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).atDay(1)))).thenReturn(List.of(
                advice("600584", sell.minusDays(5), "clear")));
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).minusMonths(1).atDay(1))))
                .thenReturn(List.of());
        TradingProfileService svc = new TradingProfileService(repo, new InMemoryFileStorage(), history);

        TradingProfileService.AdviceAdherence a = svc.computeAdviceAdherence("default");

        assertEquals(1, a.withAdviceCount(), "60 天前清仓的卖前建议不应被 now 窗口漏掉");
        assertEquals(1, a.followedCount());
        assertEquals(100.0, a.followRatePct());
    }

    @Test
    void adviceAdherenceText_injectsIntoProfile() {
        LocalDate sell = LocalDate.of(2026, 9, 5);
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                soldSellDate("600584", sell, -29.22, "扛单超 5%")));
        com.adaiadai.core.domain.trading.AdviceHistoryRepository history =
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class);
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).atDay(1)))).thenReturn(List.of(
                advice("600584", sell.minusDays(3), "clear")));
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).minusMonths(1).atDay(1))))
                .thenReturn(List.of());
        TradingProfileService svc = new TradingProfileService(repo, new InMemoryFileStorage(), history);

        String text = svc.profileText("default");

        assertTrue(text.contains("建议遵守率"), "画像应含建议遵守率");
        assertTrue(text.contains("100.0%"), "遵守率数值");
    }

    // ── RFC 20260905 远期：回头草拦截预留 ──

    @Test
    void symbolHistoryNote_returnsPastLossNote_whenSymbolInSoldHistory() {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                soldSellDate("600584", LocalDate.of(2026, 8, 3), -29.22, "扛单超 5%——按 R66 只输一根K线")));
        TradingProfileService svc = new TradingProfileService(repo, new InMemoryFileStorage(),
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));

        String note = svc.symbolHistoryNote("default", "600584");

        assertTrue(note.contains("-29.22"), "应含上次亏损%");
        assertTrue(note.contains("扛单"), "应含上次判定");
        assertTrue(note.contains("上次哪里做得不对"), "应含自省引导（主语是你，合规）");
    }

    @Test
    void symbolHistoryNote_returnsEmpty_whenSymbolNotInHistory() {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(
                soldSellDate("600584", LocalDate.of(2026, 8, 3), -29.22, "扛单超 5%")));
        TradingProfileService svc = new TradingProfileService(repo, new InMemoryFileStorage(),
                mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class));

        assertEquals("", svc.symbolHistoryNote("default", "000725"), "未做过的票无历史对照");
    }
}
