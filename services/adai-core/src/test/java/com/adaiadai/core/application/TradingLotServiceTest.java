package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingLot;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingLotService — 批次推导与行为标注测试（RFC 20260825 逐笔批次跟踪与行为纠偏）。
 * 覆盖：按日合并加权成本 / 跨日新开 / LIFO 卖出 / 跨批分算已实现盈亏 / 回合 / 初始批次兜底 / 行为标注。
 */
class TradingLotServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 8, 3); // 周一
    private static final LocalDate D2 = LocalDate.of(2026, 8, 4);

    private TradeRecord buy(String symbol, int vol, String price, LocalDate date, String stop, String buyPoint) {
        return TradeRecord.of("t_" + symbol + "_" + vol + "_" + price, symbol, symbol + "名", TradeDirection.BUY,
                new BigDecimal(price), vol, date, LocalTime.of(10, 0),
                stop != null ? new BigDecimal(stop) : null, buyPoint, null, null, null,
                LocalDateTime.of(date, LocalTime.of(10, 0)), null, null);
    }

    private TradeRecord sell(String symbol, int vol, String price, LocalDate date) {
        return TradeRecord.of("t_" + symbol + "_s" + vol, symbol, symbol + "名", TradeDirection.SELL,
                new BigDecimal(price), vol, date, LocalTime.of(10, 0),
                null, null, null, null, null,
                LocalDateTime.of(date, LocalTime.of(10, 0)), null, null);
    }

    private TradingLotService service(List<TradeRecord> trades, List<Position> positions,
                                      Map<String, MarketData> quotes) {
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(trades);
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll("u")).thenReturn(positions);
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(anyList())).thenReturn(quotes != null ? quotes : Map.of());
        KlineService kline = mock(KlineService.class);
        when(kline.kline(anyString(), anyInt())).thenReturn(List.of());
        return new TradingLotService(history, repo, market, kline);
    }

    private static List<TradingLot> lotsOf(TradingLotService service, String symbol) {
        return service.derive("u").getOrDefault(symbol, List.of());
    }

    // ── 批次切分 ──

    @Test
    void sameDayMultipleBuys_mergedIntoSingleLot() {
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", D1, "9.0", "B1"),
                buy("600000", 1000, "12.0", D1, null, null)), List.of(), Map.of());
        List<TradingLot> lots = lotsOf(svc, "600000");
        assertEquals(1, lots.size(), "同日多单合并为一个批次");
        assertEquals(2000, lots.get(0).volume());
        assertEquals(2000, lots.get(0).remaining());
        assertEquals(D1, lots.get(0).buyDate());
        // 加权成本 = (10×1000 + 12×1000 + 费用) / 2000，略高于 11
        assertTrue(lots.get(0).costPrice().compareTo(new BigDecimal("11")) > 0,
                "加权成本略高于 11（含佣金）：" + lots.get(0).costPrice());
        assertTrue(lots.get(0).costPrice().compareTo(new BigDecimal("11.01")) < 0);
        // 合并后止损/买点取最近一次 BUY（null 时保留旧值）
        assertEquals("9.0", lots.get(0).stopLossPrice().toPlainString());
        assertEquals("B1", lots.get(0).buyPoint());
    }

    @Test
    void crossDayBuys_newLotPerDay() {
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", D1, null, null),
                buy("600000", 1000, "12.0", D2, null, null)), List.of(), Map.of());
        List<TradingLot> lots = lotsOf(svc, "600000");
        assertEquals(2, lots.size(), "跨日买入 = 两个批次（用户「连续买入两天」场景）");
        assertEquals(D1, lots.get(0).buyDate());
        assertEquals(D2, lots.get(1).buyDate());
    }

    // ── LIFO 卖出 ──

    @Test
    void sell_lifo_deductsLatestLot_first() {
        // 用户场景：底仓（D1）+ 短线（D2），卖 500 → 先扣 D2（最近买入），底仓不动
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", D1, null, null),
                buy("600000", 1000, "12.0", D2, null, null),
                sell("600000", 500, "13.0", LocalDate.of(2026, 8, 5))), List.of(), Map.of());
        List<TradingLot> lots = lotsOf(svc, "600000");
        assertEquals(2, lots.size());
        TradingLot base = lots.get(0); // D1 底仓
        TradingLot short_ = lots.get(1); // D2 短线
        assertEquals(1000, base.remaining(), "底仓不动");
        assertEquals(500, short_.remaining(), "先卖最近买入的批次（LIFO）");
        assertTrue(short_.realizedPnl().subtract(new BigDecimal("500")).abs().compareTo(new BigDecimal("15")) < 0,
                "D2 批已实现盈亏 ≈ (13−12)×500 = +500（含佣金差值 ≤2 元）：" + short_.realizedPnl());
    }

    @Test
    void sell_acrossLots_splitsRealizedPnlPerLot() {
        // 卖 1500：D2 全扣（回合），D1 扣 500，各批按自己成本分算盈亏
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", D1, null, null),
                buy("600000", 1000, "12.0", D2, null, null),
                sell("600000", 1500, "11.0", LocalDate.of(2026, 8, 5))), List.of(), Map.of());
        List<TradingLot> lots = lotsOf(svc, "600000");
        assertEquals(2, lots.size());
        TradingLot base = lots.get(0);
        TradingLot short_ = lots.get(1);
        assertTrue(short_.closed(), "D2 批次全部卖完 = 关闭（回合）");
        assertTrue(short_.realizedPnl().add(new BigDecimal("1000")).abs().compareTo(new BigDecimal("15")) < 0,
                "D2 批盈亏 ≈ (11−12)×1000 = −1000（含佣金差值 ≤2 元）：" + short_.realizedPnl());
        assertEquals(500, base.remaining(), "D1 批剩 500");
        assertTrue(base.realizedPnl().subtract(new BigDecimal("500")).abs().compareTo(new BigDecimal("15")) < 0,
                "D1 批盈亏 ≈ (11−10)×500 = +500（含佣金差值 ≤2 元）：" + base.realizedPnl());
    }

    // ── 初始批次兜底 ──

    @Test
    void holdingBeyondFlow_initialLotFillsGap() {
        // positions.md 有 1500 股（avgCost 10），流水只有 BUY 500 → 差额 1000 = 初始批次（底仓）
        List<Position> positions = List.of(new Position("600000", "浦发银行", 1500,
                new BigDecimal("10.0"), new BigDecimal("10.5"), LocalDateTime.now()));
        TradingLotService svc = service(List.of(
                buy("600000", 500, "12.0", D1, null, null)), positions, Map.of());
        List<TradingLot> lots = lotsOf(svc, "600000");
        assertEquals(2, lots.size());
        TradingLot init = lots.get(0);
        assertTrue(init.initial(), "流水覆盖不到的底仓 = 初始批次");
        assertTrue(init.lotId().endsWith("_INIT"));
        assertEquals(1000, init.remaining());
        assertEquals(0, init.costPrice().compareTo(new BigDecimal("10.0")), "初始批次成本 = 持仓摊薄成本");
    }

    // ── 行为标注 ──

    @Test
    void behaviors_lossAveragingDown_and_chaseHigh_and_shortNew() {
        // D1 买 1000@10（B1）；D2 卖 1000@9.5（亏着割）；D3 买 1000@10.2（SB1 博一下，高于卖价=追高）
        LocalDate D3 = LocalDate.of(2026, 8, 5);
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", D1, "9.0", "B1"),
                sell("600000", 1000, "9.0", D2),
                buy("600000", 1000, "9.2", D3, "8.5", "SB1")), List.of(), Map.of());
        List<TradingLotService.BehaviorNote> notes = svc.analyzeBehaviors("u", D3);
        assertTrue(notes.stream().anyMatch(n -> "chase-high".equals(n.type())),
                "买价 9.2 高于上一卖出价 9.0 → 追高");
        assertTrue(notes.stream().anyMatch(n -> "short-new".equals(n.type())),
                "买点 SB1 → 短线新开（博一下）");
        // 亏损加仓：D3 买价 9.2 < 上一买批成本 10.0 → 应标注；但本用例 D2 已清仓卖出…
        assertFalse(notes.stream().anyMatch(n -> "loss-avg-down".equals(n.type())),
                "无持仓上下文（已清仓再买）不算亏损加仓——语义是「持仓中越跌越买」");
    }

    @Test
    void behaviors_lossAveragingDown_withHolding() {
        // D1 买 1000@10 未卖；D2 买 1000@9.2（比上一买批成本低）→ 亏损加仓
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", D1, "9.0", "B1"),
                buy("600000", 1000, "9.2", D2, "8.5", "B1")), List.of(), Map.of());
        List<TradingLotService.BehaviorNote> notes = svc.analyzeBehaviors("u", D2);
        assertTrue(notes.stream().anyMatch(n -> "loss-avg-down".equals(n.type())),
                "买价 9.2 < 上一买批成本 10.0 → 亏损加仓（越跌越买）");
    }

    @Test
    void behaviors_stopLossIgnored_whenPriceBelowLotStop() {
        // 状态类行为（破止损未走）只在 date=今天 判定（对抗审查 P1-4：历史日期复盘不注入「今天」状态）
        LocalDate today = LocalDate.now();
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", today, "9.0", "B1")), List.of(), Map.of(
                "600000", new MarketData("600000", "浦发银行", new BigDecimal("8.8"), new BigDecimal("9.2"), new BigDecimal("9.0"), new BigDecimal("9.0"), new BigDecimal("8.8"), new BigDecimal("-5"), 1000)));
        List<TradingLotService.BehaviorNote> notes = svc.analyzeBehaviors("u", today);
        assertTrue(notes.stream().anyMatch(n -> "stop-loss-ignored".equals(n.type())),
                "现价 8.8 < 批次止损 9.0 → 破止损未走");
        assertTrue(notes.stream().anyMatch(n -> n.message().contains("你设的止损")),
                "显式止损 → 纪律级文案（对抗审查 P1-3）");
    }

    @Test
    void defaultStopLoss_applied_whenNull() {
        // 批次止损未设 → 默认 −7% 兜底（成本 10 → 止损 9.3）；现价 9.2 → 破默认止损
        LocalDate today = LocalDate.now();
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", today, null, null)), List.of(), Map.of(
                "600000", new MarketData("600000", "浦发银行", new BigDecimal("9.2"), new BigDecimal("10.0"), new BigDecimal("9.8"), new BigDecimal("9.8"), new BigDecimal("9.2"), new BigDecimal("-8"), 1000)));
        List<TradingLotService.BehaviorNote> notes = svc.analyzeBehaviors("u", today);
        assertTrue(notes.stream().anyMatch(n -> "stop-loss-ignored".equals(n.type())),
                "未设止损按 −7% 兜底：现价 9.2 < 9.3 → 破止损未走");
        assertTrue(notes.stream().anyMatch(n -> n.message().contains("默认 −7% 风控线")),
                "默认兜底止损 → 风控提示文案（不包装成违规批评）");
    }

    @Test
    void behaviors_stateBehaviors_skipped_forHistoricalDate() {
        // 对抗审查 P1-4：历史日期复盘不注入「今天」的状态行为（破止损/浮盈回吐/短线超期）
        LocalDate today = LocalDate.now();
        TradingLotService svc = service(List.of(
                buy("600000", 1000, "10.0", today, "9.0", "B1")), List.of(), Map.of(
                "600000", new MarketData("600000", "浦发银行", new BigDecimal("8.8"), new BigDecimal("9.2"), new BigDecimal("9.0"), new BigDecimal("9.0"), new BigDecimal("8.8"), new BigDecimal("-5"), 1000)));
        List<TradingLotService.BehaviorNote> notes = svc.analyzeBehaviors("u", today.minusDays(3));
        assertTrue(notes.stream().noneMatch(n -> "stop-loss-ignored".equals(n.type())),
                "历史日期复盘：破止损未走（当前状态）不注入");
    }

    // ── 对账 ──

    @Test
    void reconcile_reportsFlowVsHoldingGap() {
        List<Position> positions = List.of(new Position("600000", "浦发银行", 1500,
                new BigDecimal("10.0"), new BigDecimal("10.5"), LocalDateTime.now()));
        TradingLotService svc = service(List.of(
                buy("600000", 500, "12.0", D1, null, null)), positions, Map.of());
        List<TradingAppService.ReconcileLine> lines = svc.reconcile("u");
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).note().contains("≠"), "持仓 1500 ≠ 流水净 500 → 对账提示缺口");
    }
}
