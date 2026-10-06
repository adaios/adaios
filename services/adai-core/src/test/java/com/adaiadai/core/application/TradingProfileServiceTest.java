package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradingProfileService;

import com.adaiadai.core.domain.trading.AdviceHistoryRepository;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRoundPort;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.LotStopLossOverrideRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingProfileService — 个人交易画像测试（RFC 20260905 A 层）。
 * <p>
 * 覆盖：客观统计（胜率/纪律违反率/中位盈亏/分布）、无回合降级空、
 * 主观层 profile.md 读写、组合注入文本。
 * <p>
 * P2-认知2（2026-10-05）后客观层数据源 = <b>逐笔回合</b>，故这里的统计用例一律用
 * **真实 {@link TradingLotService}**（mock 掉流水/持仓/行情/规则仓库）构造回合流水，
 * 而不是直接塞清仓表行——否则测不到「回合口径」这件事本身。
 */
class TradingProfileServiceTest {

    // ── 清仓表行（旧口径数据源；现只服务建议遵守率/逐票历史对照）──

    private SoldTrade sold(String symbol, double pnl, int holdDays, String verdict) {
        return new SoldTrade(symbol, "测试", LocalDate.now().minusDays(holdDays),
                LocalDate.now(), holdDays, "5+1", pnl, verdict, "");
    }

    // ── 逐笔流水（新口径数据源）：fee 显式 0，使回合收益率可精确断言 ──

    private static TradeRecord buy(String symbol, int vol, String price, LocalDate date) {
        return TradeRecord.of("t_b_" + symbol + "_" + date, symbol, symbol + "名", TradeDirection.BUY,
                new BigDecimal(price), vol, date, LocalTime.of(10, 0),
                null, null, null, null, BigDecimal.ZERO,
                LocalDateTime.of(date, LocalTime.of(10, 0)), null, null);
    }

    private static TradeRecord sell(String symbol, int vol, String price, LocalDate date) {
        return TradeRecord.of("t_s_" + symbol + "_" + date, symbol, symbol + "名", TradeDirection.SELL,
                new BigDecimal(price), vol, date, LocalTime.of(10, 0),
                null, null, null, null, BigDecimal.ZERO,
                LocalDateTime.of(date, LocalTime.of(10, 0)), null, null);
    }

    /** 一轮买入 → 卖清（同一标的、同一数量），价格决定回合收益率%。 */
    private static void addRound(List<TradeRecord> out, String symbol, String buyPrice, String sellPrice,
                                 LocalDate buyDate, LocalDate sellDate) {
        out.add(buy(symbol, 100, buyPrice, buyDate));
        out.add(sell(symbol, 100, sellPrice, sellDate));
    }

    private TradingLotService lotService(List<TradeRecord> trades) {
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(trades);
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(anyList())).thenReturn(Map.of());
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(List.of());
        TradingRuleSettingsRepository rules = mock(TradingRuleSettingsRepository.class);
        when(rules.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        LotStopLossOverrideRepository overrides = mock(LotStopLossOverrideRepository.class);
        when(overrides.findByUser(anyString())).thenReturn(Map.of());
        return new TradingLotService(history, positions, market, kline, rules, overrides);
    }

    /** 画像服务（清仓表行 + 回合流水；建议留痕为空）。 */
    private TradingProfileService service(List<SoldTrade> sold, List<TradeRecord> trades) {
        return build(sold, trades, mock(AdviceHistoryRepository.class), lotService(trades));
    }

    private TradingProfileService service(List<SoldTrade> sold) {
        return service(sold, List.of());
    }

    private TradingProfileService build(List<SoldTrade> sold, List<TradeRecord> trades,
                                        AdviceHistoryRepository advice, TradingRoundPort port) {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(sold);
        return new TradingProfileService(repo, new InMemoryFileStorage(), advice, port);
    }

    // ── 核心：回合口径 ≠ 清仓表「首买→末卖」纸上区间（P2-认知2 病根）──

    @Test
    void computeStats_twoRounds24Pct_notPaper224Pct() {
        // 病根复现（REVIEW P2-认知2，例：航天发展「+224%」实为两轮 +24%）：
        //   流水：10 元买 100 → 12.4 卖 100（+24%）；再 10 元买 100 → 12.4 卖 100（+24%）
        //   清仓表（旧数据源）：同一只票只有一条行，纸上区间收益 = +224%
        List<TradeRecord> trades = new ArrayList<>();
        addRound(trades, "600519", "10.00", "12.40",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9));
        addRound(trades, "600519", "10.00", "12.40",
                LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 6));
        List<SoldTrade> sold = List.of(new SoldTrade("600519", "测试",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 6), 32, "4+2", 224.0, "盈利了结", ""));

        TradingProfileService svc = service(sold, trades);
        TradingProfileService.TradingProfileStats s = svc.computeStats("default");

        assertEquals(2, s.roundCount(), "两个回合——不是清仓表的 1 笔（纸上区间）");
        assertEquals(24.0, s.medianPnlPct(), "回合级 +24%，不是纸上 +224%");
        assertEquals(100.0, s.winRatePct());
        assertFalse(svc.objectiveProfileText("default").contains("224"),
                "注入文本不得出现清仓表的纸上 +224%");
    }

    @Test
    void computeStats_aggregatesDisciplineAndWinRate() {
        // 4 个回合（每轮独立标的）：扛单 -29.22%/12 天、短打 -2.5%/3 天、两轮盈利
        List<TradeRecord> trades = new ArrayList<>();
        addRound(trades, "600584", "10.00", "7.078", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 14));
        addRound(trades, "000725", "10.00", "10.50", LocalDate.of(2026, 2, 2), LocalDate.of(2026, 3, 4));
        addRound(trades, "002131", "10.00", "9.75", LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 5));
        addRound(trades, "000547", "10.00", "12.00", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 10));
        TradingProfileService svc = service(List.of(), trades);

        TradingProfileService.TradingProfileStats s = svc.computeStats("default");

        assertEquals(4, s.roundCount());
        assertEquals(50.0, s.winRatePct());
        assertEquals(2, s.disciplineViolationCount(), "扛单+短打 2/4 违反纪律");
        assertEquals(50.0, s.disciplineViolationRatePct());
        assertEquals(5.0, s.medianPnlPct(), "4 元素排序取上中位 pnls.get(2)=5.0");
        assertEquals(36, s.avgHoldDays(), "(12+30+3+100)/4=36.25→36（自然日口径）");
        assertEquals(2, s.verdictBreakdown().get("盈利了结"), "结果分布键空间与旧口径一致");
    }

    @Test
    void computeStats_lossHoldNotCountedAsViolation() {
        // P2-认知1（2026-09-05 用户拍板 A）：「亏损持仓」普通亏损不计违纪——违纪只算扛单+短打
        // P2-认知2（2026-10-05）：回合无 verdict 字段 → 用 SoldTradeVerdict 同一套规则从回合重推
        List<TradeRecord> trades = new ArrayList<>();
        addRound(trades, "600584", "10.00", "7.078", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 14));
        addRound(trades, "601888", "10.00", "9.70", LocalDate.of(2026, 2, 2), LocalDate.of(2026, 4, 3));
        addRound(trades, "000725", "10.00", "10.50", LocalDate.of(2026, 5, 4), LocalDate.of(2026, 6, 3));
        TradingProfileService svc = service(List.of(), trades);

        TradingProfileService.TradingProfileStats s = svc.computeStats("default");

        assertEquals(3, s.roundCount());
        assertEquals(1, s.disciplineViolationCount(), "扛单 1 个回合算违纪；亏损 3% 拿 60 天不计");
        assertEquals(33.3, s.disciplineViolationRatePct());
    }

    @Test
    void computeStats_noRound_returnsZero() {
        TradingProfileService svc = service(List.of());
        TradingProfileService.TradingProfileStats s = svc.computeStats("default");
        assertEquals(0, s.roundCount());
        assertEquals(0.0, s.winRatePct());
        assertTrue(s.verdictBreakdown().isEmpty());
    }

    @Test
    void computeStats_roundSourceThrows_degradesToZeroWithoutBlowingUp() {
        // 空数据/数据源异常都不炸（画像降级为空）
        TradingRoundPort failing = userId -> {
            throw new IllegalStateException("回合数据源损坏");
        };
        TradingProfileService svc = build(List.of(), List.of(), mock(AdviceHistoryRepository.class), failing);
        TradingProfileService.TradingProfileStats s = svc.computeStats("default");
        assertEquals(0, s.roundCount());
        assertTrue(s.verdictBreakdown().isEmpty());
        assertEquals("", svc.objectiveProfileText("default"));
    }

    @Test
    void computeStats_openLot_doesNotCountAsRound() {
        // 只买没卖（未了结）→ 不是回合，不进统计
        List<TradeRecord> trades = List.of(buy("600519", 100, "10.00", LocalDate.of(2026, 1, 5)));
        TradingProfileService svc = service(List.of(), trades);
        assertEquals(0, svc.computeStats("default").roundCount());
    }

    // ── 注入文本与样本门槛 ──

    @Test
    void objectiveProfileText_smallSample_showsCollectingNote() {
        // 🤔18（2026-09-05 对抗审）：<10 笔只给笔数不给胜率/纪律率（小样本勿下画像判断）
        // P2-认知2：门槛口径随数据源改为回合数（门槛值 10 不变）
        List<TradeRecord> trades = new ArrayList<>();
        addRound(trades, "600584", "10.00", "9.00", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 14));
        addRound(trades, "000725", "10.00", "10.80", LocalDate.of(2026, 2, 2), LocalDate.of(2026, 3, 4));
        TradingProfileService svc = service(List.of(), trades);

        String text = svc.objectiveProfileText("default");

        assertTrue(text.contains("2 个回合"), "应含回合数");
        assertTrue(text.contains("样本不足"), "小样本应提示样本不足");
        assertFalse(text.contains("胜率 "), "小样本不得给胜率数值（防误读）");
        assertTrue(text.contains("你的交易画像"), "段落标题");
    }

    @Test
    void objectiveProfileText_largeSample_includesStatsAndRoundAnnotation() {
        // ≥10 个回合才注入胜率/纪律率/分布；口径标注必须是「逐笔回合口径」，不留旧标注
        List<TradeRecord> trades = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < 8; i++) {
            addRound(trades, "0001" + i, "10.00", "10.50",
                    base.plusDays(i * 20), base.plusDays(i * 20 + 10));
        }
        for (int i = 0; i < 4; i++) {
            addRound(trades, "1002" + i, "10.00", "8.80",
                    base.plusDays(i * 20), base.plusDays(i * 20 + 15));
        }
        TradingProfileService svc = service(List.of(), trades);

        String text = svc.objectiveProfileText("default");

        assertTrue(text.contains("12 个回合"), "应含回合数");
        assertTrue(text.contains("胜率"), "≥10 个回合应有胜率");
        assertTrue(text.contains("纪律违反率"), "≥10 个回合应有纪律违反率");
        assertTrue(text.contains("平均持仓"), "≥10 个回合应有平均持仓");
        assertTrue(text.contains("逐笔回合口径"), "口径标注换成逐笔回合");
        assertFalse(text.contains("清仓表口径"), "不得留旧口径标注");
        assertFalse(text.contains("首买"), "不得留旧口径措辞");
    }

    @Test
    void objectiveProfileText_noRound_returnsEmpty() {
        TradingProfileService svc = service(List.of());
        assertEquals("", svc.objectiveProfileText("default"));
    }

    @Test
    void subjectiveProfile_roundtrip_viaFile() {
        TradingProfileService svc = build(List.of(), List.of(), mock(AdviceHistoryRepository.class),
                lotService(List.of()));

        svc.saveProfile("default", "# 主观层\n\n你追高的手比抄底大 3 倍。");
        assertTrue(svc.rawProfile("default").contains("追高的手"));
        assertTrue(svc.subjectiveProfileText("default").contains("追高的手"));
        assertTrue(svc.subjectiveProfileText("default").contains("你的画像（主观层"), "应带注入前缀标题");
    }

    @Test
    void profileText_combinesObjectiveAndSubjective() {
        List<TradeRecord> trades = List.of(
                buy("600584", 100, "10.00", LocalDate.of(2026, 1, 2)),
                sell("600584", 100, "9.00", LocalDate.of(2026, 1, 14)));
        TradingProfileService svc = service(List.of(), trades);
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

    // ── RFC 20260905 P2：建议遵守率（B 反哺 A，仍走清仓表——不在本次口径切换范围）──

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
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).atDay(1)))).thenReturn(List.of(
                advice("600584", sell.minusDays(4), "clear"),
                advice("000725", sell.minusDays(20), "hold")));
        TradingProfileService svc = build(List.of(
                soldSellDate("600584", sell, -29.22, "扛单超 5%"),
                soldSellDate("000725", sell, 5.0, "盈利了结")), List.of(), history, lotService(List.of()));

        TradingProfileService.AdviceAdherence a = svc.computeAdviceAdherence("default");

        assertEquals(1, a.withAdviceCount(), "只有 600584 有卖前 clear/reduce 建议（hold 不计）");
        assertEquals(1, a.followedCount(), "建议 9-1、清仓 9-5 → 4 天内执行 = 遵守");
        assertEquals(100.0, a.followRatePct());
    }

    @Test
    void computeAdviceAdherence_lateExit_countsViolation() {
        LocalDate sell = LocalDate.of(2026, 9, 5);
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        // 建议在 8-20（卖前 16 天）：仍在卖前 30 天窗内，但距清仓 >10 天 → 未遵守
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).atDay(1)))).thenReturn(List.of(
                advice("600584", sell.minusDays(16), "clear")));
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).minusMonths(1).atDay(1))))
                .thenReturn(List.of());
        TradingProfileService svc = build(List.of(soldSellDate("600584", sell, -29.22, "扛单超 5%")),
                List.of(), history, lotService(List.of()));

        TradingProfileService.AdviceAdherence a = svc.computeAdviceAdherence("default");

        assertEquals(1, a.withAdviceCount());
        assertEquals(0, a.followedCount(), "建议后 16 天才清仓 → 超过 10 天窗口 = 未遵守");
        assertEquals(0.0, a.followRatePct());
    }

    @Test
    void computeAdviceAdherence_oldSoldStillCounted_withSellDateAnchoredWindow() {
        // P1-1 回归（2026-09-05 三官）：60 天前清仓（now 窗口外）卖前有 clear 建议 → 仍计入遵守率
        LocalDate sell = LocalDate.of(2026, 7, 1); // 距 now(9月) 60+ 天
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).atDay(1)))).thenReturn(List.of(
                advice("600584", sell.minusDays(5), "clear")));
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).minusMonths(1).atDay(1))))
                .thenReturn(List.of());
        TradingProfileService svc = build(List.of(soldSellDate("600584", sell, -29.22, "扛单超 5%")),
                List.of(), history, lotService(List.of()));

        TradingProfileService.AdviceAdherence a = svc.computeAdviceAdherence("default");

        assertEquals(1, a.withAdviceCount(), "60 天前清仓的卖前建议不应被 now 窗口漏掉");
        assertEquals(1, a.followedCount());
        assertEquals(100.0, a.followRatePct());
    }

    @Test
    void adviceAdherenceText_injectsIntoProfile() {
        LocalDate sell = LocalDate.of(2026, 9, 5);
        AdviceHistoryRepository history = mock(AdviceHistoryRepository.class);
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).atDay(1)))).thenReturn(List.of(
                advice("600584", sell.minusDays(3), "clear")));
        when(history.findByMonth(anyString(), eq(java.time.YearMonth.from(sell).minusMonths(1).atDay(1))))
                .thenReturn(List.of());
        TradingProfileService svc = build(List.of(soldSellDate("600584", sell, -29.22, "扛单超 5%")),
                List.of(), history, lotService(List.of()));

        String text = svc.profileText("default");

        assertTrue(text.contains("遵守率"), "画像应含遵守率（2026-10-06 B1：注入文本去「建议」）");
        assertTrue(text.contains("100.0%"), "遵守率数值");
    }

    // ── RFC 20260905 远期：回头草拦截预留 ──

    @Test
    void symbolHistoryNote_returnsPastLossNote_whenSymbolInSoldHistory() {
        TradingProfileService svc = build(List.of(
                soldSellDate("600584", LocalDate.of(2026, 8, 3), -29.22, "扛单超 5%——按 R66 只输一根K线")),
                List.of(), mock(AdviceHistoryRepository.class), lotService(List.of()));

        String note = svc.symbolHistoryNote("default", "600584");

        assertTrue(note.contains("-29.22"), "应含上次亏损%");
        assertTrue(note.contains("扛单"), "应含上次判定");
        assertTrue(note.contains("上次哪里做得不对"), "应含自省引导（主语是你，合规）");
    }

    @Test
    void symbolHistoryNote_returnsEmpty_whenSymbolNotInHistory() {
        TradingProfileService svc = build(List.of(
                soldSellDate("600584", LocalDate.of(2026, 8, 3), -29.22, "扛单超 5%")),
                List.of(), mock(AdviceHistoryRepository.class), lotService(List.of()));

        assertEquals("", svc.symbolHistoryNote("default", "000725"), "未做过的票无历史对照");
    }
}
