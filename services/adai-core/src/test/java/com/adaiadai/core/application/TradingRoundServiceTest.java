package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RoundBoundaryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingRoundService — 「一轮完整交易」识别（口径 C）与交易规则检查
 * （RFC 20261003-trading-plan-and-review-loop §五，2026-10-03）。
 *
 * <p>背景（生产实测）：通达信「清仓股」表是**标的级总账**（160/172 只标的的「买卖次数」等于其全部
 * 流水笔数），文件给 172 条而流水能还原 232 轮；同日「卖光→买回」实测 6 处（最快 82 秒）。
 */
class TradingRoundServiceTest {

    private static TradeRecord tr(String sym, LocalDate d, TradeDirection dir, String price, int vol) {
        return TradeRecord.of("t_" + sym + "_" + d + "_" + dir, sym, sym + "名", dir,
                new BigDecimal(price), vol, d, null, null, null, null, null, null,
                LocalDateTime.now(), null, "oid-" + d + "-" + dir);
    }

    private static Candle c(LocalDate d, double high, double low, double close) {
        return new Candle(d, close, high, low, close, 100);
    }

    private TradingRoundService service(List<TradeRecord> flow, List<Candle> candles) {
        TradingHistoryRepository hist = mock(TradingHistoryRepository.class);
        when(hist.findAll(any())).thenReturn(flow);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(candles);
        TradingRuleSettingsPort rules = mock(TradingRuleSettingsPort.class);
        when(rules.findByUser(any())).thenReturn(TradingRuleSettings.defaults());
        return new TradingRoundService(hist, kline, rules);
    }

    /** rounds 批（2026-10-06）：带真实人工边界仓储（InMemoryFileStorage）的 service——人工 &gt; 自动。 */
    private TradingRoundService serviceWithBoundaries(List<TradeRecord> flow, List<Candle> candles) {
        TradingHistoryRepository hist = mock(TradingHistoryRepository.class);
        when(hist.findAll(any())).thenReturn(flow);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(anyString(), any(LocalDate.class), any(LocalDate.class))).thenReturn(candles);
        TradingRuleSettingsPort rules = mock(TradingRuleSettingsPort.class);
        when(rules.findByUser(any())).thenReturn(TradingRuleSettings.defaults());
        return new TradingRoundService(hist, kline, rules,
                new RoundBoundaryRepository(new InMemoryFileStorage()));
    }

    /** 口径 C（用户拍板）：同一交易日内「卖光 → 买回」合并为同一轮，不拆成两笔。 */
    @Test
    void sameDayRebuy_mergesIntoSingleRound() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 6), TradeDirection.SELL, "11.00", 100),
                tr("600000", LocalDate.of(2026, 1, 6), TradeDirection.BUY, "12.00", 100),
                tr("600000", LocalDate.of(2026, 1, 7), TradeDirection.SELL, "13.00", 100));
        TradingRoundService svc = service(flow, List.of(
                c(LocalDate.of(2026, 1, 5), 11, 9.5, 10.5),
                c(LocalDate.of(2026, 1, 6), 13, 10.5, 12.5),
                c(LocalDate.of(2026, 1, 7), 13.5, 12.5, 13)));

        TradingRoundService.RoundsView v = svc.rounds("default", null, 50);

        assertEquals(1, v.total(), "同日「卖光→买回」必须合并为 1 轮（口径 C）");
        assertEquals(2, v.rounds().get(0).buyCount(), "合并后含 2 笔买入");
        assertEquals(2, v.rounds().get(0).sellCount(), "合并后含 2 笔卖出");
    }

    /** 跨日的两段持有 = 两轮（不做 B 档「N 日内买回算同轮」）。 */
    @Test
    void separateHolds_areTwoRounds() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 6), TradeDirection.SELL, "11.00", 100),
                tr("600000", LocalDate.of(2026, 1, 10), TradeDirection.BUY, "12.00", 100),
                tr("600000", LocalDate.of(2026, 1, 12), TradeDirection.SELL, "13.00", 100));
        TradingRoundService svc = service(flow, List.of(
                c(LocalDate.of(2026, 1, 5), 11, 9.5, 10.5),
                c(LocalDate.of(2026, 1, 6), 11.5, 10.5, 11),
                c(LocalDate.of(2026, 1, 10), 12.5, 11.5, 12),
                c(LocalDate.of(2026, 1, 12), 13.5, 12.5, 13)));

        assertEquals(2, svc.rounds("default", null, 50).total(), "跨日两段持有 = 2 轮");
    }

    /** R55 盈转亏：期间峰值浮盈 ≥ 阈值（默认 3%）却亏损收场 → 命中，并带上过程数据。 */
    @Test
    void r55_gaveBackProfit_isFlagged() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 7), TradeDirection.SELL, "9.50", 100));
        TradingRoundService svc = service(flow, List.of(
                c(LocalDate.of(2026, 1, 5), 10.5, 9.8, 10.2),
                c(LocalDate.of(2026, 1, 6), 11.0, 10.0, 10.8),
                c(LocalDate.of(2026, 1, 7), 10.2, 9.4, 9.5)));

        TradingRoundService.TradeRound r = svc.rounds("default", null, 50).rounds().get(0);

        assertTrue(r.hits().stream().anyMatch(h -> "R55".equals(h.rule())),
                "峰值 +10% 却亏损收场 → 必须命中 R55，实际: " + r.hits());
        assertEquals(0, r.peakPct().compareTo(new BigDecimal("10.00")), "峰值浮盈 = +10%，实际: " + r.peakPct());
        assertTrue(r.pnlPct().signum() < 0, "最终应为亏损");
    }

    /** R69/R90 被套时加仓：加仓当日最低价低于当时成本（> 阈值）→ 命中。 */
    @Test
    void r69_addOnWhileTrapped_isFlagged() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 6), TradeDirection.BUY, "9.00", 100),
                tr("600000", LocalDate.of(2026, 1, 10), TradeDirection.SELL, "10.00", 200));
        TradingRoundService svc = service(flow, List.of(
                c(LocalDate.of(2026, 1, 5), 10.2, 9.9, 10.0),
                c(LocalDate.of(2026, 1, 6), 9.5, 8.80, 9.0),
                c(LocalDate.of(2026, 1, 10), 10.3, 9.9, 10.2)));

        TradingRoundService.TradeRound r = svc.rounds("default", null, 50).rounds().get(0);

        assertTrue(r.hits().stream().anyMatch(h -> "R69".equals(h.rule())),
                "浮亏 -12% 时加仓 → 必须命中 R69，实际: " + r.hits());
        assertEquals(1, r.addOnCount());
        assertEquals(1, r.addOnTrappedCount());
    }

    /**
     * P1（独立审查 2026-10-03 修复）：同一交易日内「底仓卖出（无建仓流水）→ 买回」不得整轮消失——
     * 原来合并时继承了 {@code initial=true}，analyze 直接丢弃（连 total 都不计）。
     */
    @Test
    void sameDayRebuyAfterInitialBatch_isNotDropped() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.SELL, "10.00", 100),   // 底仓卖出（流水未覆盖建仓）
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.20", 100),    // 同日买回
                tr("600000", LocalDate.of(2026, 1, 8), TradeDirection.SELL, "11.00", 100));
        TradingRoundService svc = service(flow, List.of(
                c(LocalDate.of(2026, 1, 5), 10.5, 10.0, 10.3),
                c(LocalDate.of(2026, 1, 8), 11.2, 10.8, 11.0)));

        TradingRoundService.RoundsView v = svc.rounds("default", null, 50);

        assertEquals(1, v.total(), "同日「底仓卖出 → 买回」必须留成 1 轮，不得整轮消失");
        assertTrue(v.rounds().get(0).peakPct() != null, "该轮必须有过程数据");
    }

    /**
     * P1-5（独立审查 2026-10-03 修复）：峰值浮盈按**当日成本**算，不用全轮摊薄成本。
     * 场景：第 1 天 10 元买、第 2 天 5 元加仓（摊薄 7.5）、第 3 天 6 元卖光（亏损 −300）。
     * 用摊薄成本 7.5 去比第 1 天的 high 9.5 → **+26.7% 假浮盈** → 误报 R55；
     * 按当日成本 10 → 第 1 天 high 9.5 是 **−5%**，全程从未浮盈（第 2/3 天 high 5.5/6.0 也都低于 7.5）。
     */
    @Test
    void peakPct_usesCostAtThatDay_notWholeRoundAverage() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 6), TradeDirection.BUY, "5.00", 100),
                tr("600000", LocalDate.of(2026, 1, 7), TradeDirection.SELL, "6.00", 200));
        TradingRoundService svc = service(flow, List.of(
                c(LocalDate.of(2026, 1, 5), 9.5, 9.8, 10.0),
                c(LocalDate.of(2026, 1, 6), 5.5, 4.9, 5.2),
                c(LocalDate.of(2026, 1, 7), 6.2, 5.8, 6.0)));

        TradingRoundService.TradeRound r = svc.rounds("default", null, 50).rounds().get(0);

        assertTrue(r.pnlPct().signum() < 0, "本场景最终应亏损，实际: " + r.pnlPct());
        assertTrue(r.peakPct().compareTo(new BigDecimal("0")) < 0,
                "按当日成本，全程从未浮盈 → 峰值应为负，实际: " + r.peakPct());
        assertTrue(r.hits().stream().noneMatch(h -> "R55".equals(h.rule())),
                "从未浮盈不得命中 R55（摊薄成本会误报），实际: " + r.hits());
    }

    /** 未平仓的轮次照常返回（end=null），不参与「已平仓」类规则判定。 */
    @Test
    void openRound_endIsNull() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100));
        TradingRoundService svc = service(flow, List.of(c(LocalDate.of(2026, 1, 5), 10.5, 9.9, 10.2)));

        TradingRoundService.TradeRound r = svc.rounds("default", null, 50).rounds().get(0);

        assertEquals(null, r.end(), "未平仓轮次 end 为 null");
        assertTrue(r.hits().isEmpty(), "未平仓不做已实现盈亏类判定，实际: " + r.hits());
    }

    /**
     * P1（2026-10-03 自查修复）：日线必须覆盖**每一轮**。
     * 原实现「按 symbol 缓存、范围按轮次算」→ 同一只票从第二轮起拿到的是第一轮的范围，
     * 过程指标静默为 null（生产实测 37 只标的做过 ≥2 轮，000776 做过 7 轮）。
     * 本用例的 mock **按请求范围过滤**（与真实 klineRange 同语义），故能真正暴露该缺陷。
     */
    @Test
    void multiRoundSameSymbol_everyRoundHasProcessData() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 7), TradeDirection.SELL, "12.00", 100),
                tr("600000", LocalDate.of(2026, 6, 1), TradeDirection.BUY, "20.00", 100),
                tr("600000", LocalDate.of(2026, 6, 3), TradeDirection.SELL, "19.00", 100));
        List<Candle> candles = List.of(
                c(LocalDate.of(2026, 1, 5), 10.5, 9.9, 10.2),
                c(LocalDate.of(2026, 1, 6), 12.5, 10.5, 12.2),
                c(LocalDate.of(2026, 1, 7), 12.5, 11.8, 12.0),
                c(LocalDate.of(2026, 6, 1), 20.5, 19.9, 20.2),
                c(LocalDate.of(2026, 6, 2), 21.0, 19.5, 19.8),
                c(LocalDate.of(2026, 6, 3), 19.5, 18.8, 19.0));

        TradingHistoryRepository hist = mock(TradingHistoryRepository.class);
        when(hist.findAll(any())).thenReturn(flow);
        KlineService kline = mock(KlineService.class);
        when(kline.klineRange(anyString(), any(LocalDate.class), any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(1);
            LocalDate to = inv.getArgument(2);
            return candles.stream()
                    .filter(x -> !x.date().isBefore(from) && !x.date().isAfter(to)).toList();
        });
        TradingRuleSettingsPort rules = mock(TradingRuleSettingsPort.class);
        when(rules.findByUser(any())).thenReturn(TradingRuleSettings.defaults());
        TradingRoundService svc = new TradingRoundService(hist, kline, rules);

        TradingRoundService.RoundsView v = svc.rounds("default", null, 50);

        assertEquals(2, v.total(), "两段跨日持有 = 2 轮");
        for (TradingRoundService.TradeRound r : v.rounds()) {
            assertTrue(r.peakPct() != null,
                    "每一轮都必须拿到日线（" + r.start() + " 的 peakPct 不得因缓存范围而 null）");
        }
    }

    // ── rounds 批（2026-10-06）：不做底仓特例（unresolved 如实）· 人工边界一等对象（cut/merge/撤销/备注）──

    /** 不做底仓特例（design-final §7）：只有卖出、没有建仓流水 → 如实单列「判不了」，不得丢弃。 */
    @Test
    void sellOnly_isUnresolved_notDropped() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.SELL, "10.00", 100));
        TradingRoundService svc = service(flow, List.of());

        TradingRoundService.RoundsView v = svc.rounds("default", null, 50);

        assertEquals(1, v.total(), "未识别段必须留下（不做底仓特例）");
        TradingRoundService.TradeRound r = v.rounds().get(0);
        assertTrue(r.unresolved(), "unresolved=true");
        assertNull(r.pnl(), "成本/盈亏判不了 → null（不编造 0）");
        assertNull(r.pnlPct());
        assertNull(r.buyAmount());
        assertNotNull(r.reason(), "reason 如实说明判不了");
        assertEquals(0, r.buyCount());
        assertEquals(1, r.sellCount());
        assertTrue(r.hits().isEmpty(), "判不了的段不做规则判定");
    }

    /** 相邻未识别段归并为一条（start=首卖、end=末卖）——如实呈现，不逐笔刷屏。 */
    @Test
    void adjacentSellOnlySegments_areCompactedToOne() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.SELL, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 8), TradeDirection.SELL, "11.00", 100));
        TradingRoundService svc = service(flow, List.of());

        TradingRoundService.RoundsView v = svc.rounds("default", null, 50);

        assertEquals(1, v.total(), "相邻未识别段归并为一条");
        TradingRoundService.TradeRound r = v.rounds().get(0);
        assertEquals(LocalDate.of(2026, 1, 5), r.start());
        assertEquals(LocalDate.of(2026, 1, 8), r.end());
        assertEquals(2, r.sellCount());
        assertTrue(r.unresolved());
    }

    /** 人工 cut：从锚定那笔买入开始算新的一笔（同一持有段内的加仓点切开）。 */
    @Test
    void cut_splitsAtAnchoredBuy() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 8), TradeDirection.BUY, "11.00", 100),
                tr("600000", LocalDate.of(2026, 1, 12), TradeDirection.SELL, "12.00", 200));
        TradingRoundService svc = serviceWithBoundaries(flow, List.of());

        assertEquals(1, svc.rounds("default", null, 50).total(), "自动口径 = 1 笔");

        svc.setBoundary("default", "600000", "t_600000_2026-01-08_BUY", "cut", null);

        TradingRoundService.RoundsView v = svc.rounds("default", null, 50);
        assertEquals(2, v.total(), "cut 后 = 2 笔");
        // 排序按清仓日倒序：P2（1/12 清仓）在前，P1（未平仓）在后
        TradingRoundService.TradeRound p2 = v.rounds().get(0);
        TradingRoundService.TradeRound p1 = v.rounds().get(1);
        assertEquals(LocalDate.of(2026, 1, 8), p2.start());
        assertEquals(LocalDate.of(2026, 1, 12), p2.end());
        assertEquals("manual", p2.boundary().source(), "人工 > 自动");
        assertEquals("cut", p2.boundary().mode());
        assertEquals(LocalDate.of(2026, 1, 5), p1.start());
        assertNull(p1.end(), "P1 只有买入（未平仓）");
    }

    /** 人工 merge：锚定买入所在的笔与上一笔合并。 */
    @Test
    void merge_joinsWithPreviousRound() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 6), TradeDirection.SELL, "11.00", 100),
                tr("600000", LocalDate.of(2026, 1, 10), TradeDirection.BUY, "12.00", 100),
                tr("600000", LocalDate.of(2026, 1, 12), TradeDirection.SELL, "13.00", 100));
        TradingRoundService svc = serviceWithBoundaries(flow, List.of());

        assertEquals(2, svc.rounds("default", null, 50).total(), "自动口径 = 2 笔");

        svc.setBoundary("default", "600000", "t_600000_2026-01-10_BUY", "merge", null);

        TradingRoundService.RoundsView v = svc.rounds("default", null, 50);
        assertEquals(1, v.total(), "merge 后 = 1 笔");
        TradingRoundService.TradeRound r = v.rounds().get(0);
        assertEquals(LocalDate.of(2026, 1, 5), r.start());
        assertEquals(LocalDate.of(2026, 1, 12), r.end());
        assertEquals(2, r.buyCount());
        assertEquals(2, r.sellCount());
        assertEquals("manual", r.boundary().source());
        assertEquals("merge", r.boundary().mode());
    }

    /** 撤销（mode=auto）：人工切分让位，回到自动口径。 */
    @Test
    void autoMode_undoesManualCut() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 8), TradeDirection.BUY, "11.00", 100),
                tr("600000", LocalDate.of(2026, 1, 12), TradeDirection.SELL, "12.00", 200));
        TradingRoundService svc = serviceWithBoundaries(flow, List.of());

        svc.setBoundary("default", "600000", "t_600000_2026-01-08_BUY", "cut", null);
        assertEquals(2, svc.rounds("default", null, 50).total());

        svc.setBoundary("default", "600000", "t_600000_2026-01-08_BUY", "auto", null);

        TradingRoundService.RoundsView v = svc.rounds("default", null, 50);
        assertEquals(1, v.total(), "撤销后自动让位 = 1 笔");
        assertEquals("auto", v.rounds().get(0).boundary().source());
    }

    /** 备注：只动备注、不动切分（source 保持 auto）；锚定该笔首笔买入。 */
    @Test
    void setRoundNote_persists_withoutSplitting() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100),
                tr("600000", LocalDate.of(2026, 1, 7), TradeDirection.SELL, "11.00", 100));
        TradingRoundService svc = serviceWithBoundaries(flow, List.of());

        svc.setRoundNote("default", "600000_2026-01-05", "试探仓，没敢加");

        TradingRoundService.TradeRound r = svc.rounds("default", null, 50).rounds().get(0);
        assertEquals("试探仓，没敢加", r.boundary().note());
        assertEquals("auto", r.boundary().source(), "仅加备注不动切分");
        assertEquals("t_600000_2026-01-05_BUY", r.boundary().anchorBuyId(), "备注锚定该笔首笔买入");
    }

    /** 锚点必须是真实买入：编的 id → 拒绝（400 人话，不静默吞）。 */
    @Test
    void setBoundary_badAnchor_isRejected() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.BUY, "10.00", 100));
        TradingRoundService svc = serviceWithBoundaries(flow, List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.setBoundary("default", "600000", "t_不存在", "cut", null));
        assertTrue(e.getMessage().contains("锚定的买入不存在"), e.getMessage());
    }

    /** 未识别段（只有卖出）加不了备注——如实拒绝（判不了的段不给备注落点）。 */
    @Test
    void setRoundNote_onUnresolved_isRejected() {
        List<TradeRecord> flow = List.of(
                tr("600000", LocalDate.of(2026, 1, 5), TradeDirection.SELL, "10.00", 100));
        TradingRoundService svc = serviceWithBoundaries(flow, List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.setRoundNote("default", "600000_2026-01-05", "x"));
        assertTrue(e.getMessage().contains("判不了"), e.getMessage());
    }

    /**
     * P2-交易98：百分比 base 缺 / 为 0 → null（缺数据一律出 null、不编造 0——契约①/验收 5）。
     * 原实现返回 ZERO，会把「算不了」伪装成「0%」。
     */
    @Test
    void pct_zeroOrNullBase_isNull_notZero() {
        assertNull(TradingRoundService.pct(new BigDecimal("10"), null), "base 缺 → null");
        assertNull(TradingRoundService.pct(new BigDecimal("10"), BigDecimal.ZERO), "base=0 → null（不出 0）");
        assertEquals(new BigDecimal("50.00"), TradingRoundService.pct(new BigDecimal("5"), new BigDecimal("10")));
        assertEquals(new BigDecimal("-20.00"), TradingRoundService.pct(new BigDecimal("-2"), new BigDecimal("10")));
    }
}
