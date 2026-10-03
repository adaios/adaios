package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
