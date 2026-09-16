package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.SnapshotHolding;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** EquityCurveService — 资金曲线聚合测试（2026-09-04 决策方案 A）。 */
class EquityCurveServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 8, 3);

    private EquityCurveService service(TradingHistoryRepository history, PositionRepository positions,
                                       TransferRepository transfers, AccountSnapshotRepository account,
                                       KlineService kline) {
        return service(history, positions, transfers, account, noAnchor(), kline);
    }

    private EquityCurveService service(TradingHistoryRepository history, PositionRepository positions,
                                       TransferRepository transfers, AccountSnapshotRepository account,
                                       TradingAnchorRepository anchor, KlineService kline) {
        return new EquityCurveService(history, positions, transfers, account, anchor, kline);
    }

    /** 默认锚定：未知（不做持仓重置，保持旧回放行为）。 */
    private TradingAnchorRepository noAnchor() {
        TradingAnchorRepository a = mock(TradingAnchorRepository.class);
        when(a.find(anyString())).thenReturn(SnapshotAnchor.empty());
        when(a.holdings(anyString())).thenReturn(List.of());
        when(a.holdingsRecorded(anyString())).thenReturn(false);
        return a;
    }

    private KlineService klineFrom(LocalDate start, double... closes) {
        KlineService k = mock(KlineService.class);
        List<Candle> cs = new java.util.ArrayList<>();
        LocalDate d = start;
        for (double c : closes) {
            cs.add(new Candle(d, c - 0.2, c + 0.3, c - 0.4, c, 1000));
            d = d.plusDays(1);
        }
        when(k.klineRange(anyString(), any(), any())).thenReturn(cs);
        return k;
    }

    private AccountSnapshotRepository account(double cash, double principal) {
        AccountSnapshotRepository repo = mock(AccountSnapshotRepository.class);
        when(repo.findLatest(anyString())).thenReturn(Optional.of(new AccountSnapshot(
                BigDecimal.valueOf(cash), BigDecimal.valueOf(cash), BigDecimal.valueOf(cash),
                BigDecimal.valueOf(cash), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(principal), LocalDate.now())));
        return repo;
    }

    private KlineService kline(double... closes) {
        KlineService k = mock(KlineService.class);
        List<Candle> cs = new java.util.ArrayList<>();
        LocalDate d = D1;
        for (double c : closes) {
            cs.add(new Candle(d, c - 0.2, c + 0.3, c - 0.4, c, 1000));
            d = d.plusDays(1);
        }
        when(k.klineRange(anyString(), any(), any())).thenReturn(cs);
        return k;
    }

    private TradeRecord buy(String symbol, int vol, String price, LocalDate date) {
        return TradeRecord.of("t_" + symbol + "_" + vol, symbol, symbol + "名", TradeDirection.BUY,
                new BigDecimal(price), vol, date, LocalTime.of(10, 0),
                null, null, null, null, null,
                LocalDateTime.of(date, LocalTime.of(10, 0)), null, null);
    }

    @Test
    void buyAndRise_totalFollowsMarket() {
        // 账户现值：现金 0、本金 10000；8/3 买 1000 股 @10，K 收盘 10 → 11 → 12
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(List.of(buy("600000", 1000, "10.0", D1)));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll("u")).thenReturn(List.of());
        TransferRepository transfers = mock(TransferRepository.class);
        when(transfers.findAll("u")).thenReturn(List.of());

        EquityCurveService svc = service(history, positions, transfers, account(0, 10000),
                kline(10, 11, 12));
        var curve = svc.build("u");

        assertTrue(curve.points().size() >= 3, "K 线 3 日 + 事件日均应有曲线点");
        EquityCurveService.EquityPoint last = curve.points().get(curve.points().size() - 1);
        // 买入后现金 ≈0，市值 = 1000 × 12（close 12 的交易日）
        assertEquals(0, last.cash().compareTo(BigDecimal.ZERO.setScale(2)), "买入后现金应≈0：" + last.cash());
        assertEquals(0, last.marketValue().compareTo(new BigDecimal("12000.00")), "市值=1000×12：" + last.marketValue());
        assertEquals(0, last.totalAssets().compareTo(new BigDecimal("12000.00")));
        assertNotNull(last.netValue(), "invested=10000 → 净值应可算");
        assertEquals(0, last.netValue().compareTo(new BigDecimal("1.2000")), "净值 12000/10000=1.2");
        assertTrue(last.drawdown().signum() >= 0);
        // 起点（首个点）净值应 <1（含买入费用的轻微损耗）或 =1（首点即买入日现金转市值）
        assertTrue(curve.startDate().equals(D1.toString()) || curve.startDate().isEmpty());
    }

    @Test
    void initialLot_noFlow_marketValueFlat() {
        // 无流水，只有持仓快照 500 股（底仓恒持）→ 全程总资产 = 500 × 10
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(List.of());
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll("u")).thenReturn(List.of(
                new Position("600000", "名", 500, new BigDecimal("10"),
                        new BigDecimal("10"), null, null, null, null, null, null)));
        TransferRepository transfers = mock(TransferRepository.class);
        when(transfers.findAll("u")).thenReturn(List.of());

        var curve = service(history, positions, transfers, account(0, 5000),
                kline(10, 10, 10)).build("u");

        assertTrue(curve.points().size() >= 3);
        for (EquityCurveService.EquityPoint p : curve.points()) {
            assertEquals(0, p.totalAssets().compareTo(new BigDecimal("5000.00")), "底仓全程 5000：" + p);
        }
    }

    @Test
    void noAccountSnapshot_emptyCurve() {
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(List.of(buy("600000", 100, "10", D1)));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll("u")).thenReturn(List.of());
        TransferRepository transfers = mock(TransferRepository.class);
        when(transfers.findAll("u")).thenReturn(List.of());
        AccountSnapshotRepository account = mock(AccountSnapshotRepository.class);
        when(account.findLatest(anyString())).thenReturn(Optional.empty());

        var curve = service(history, positions, transfers, account, kline(10)).build("u");
        assertTrue(curve.points().isEmpty(), "无账户快照（从未导入资金）→ 空曲线不抛错");
    }

    @Test
    void principalZero_netValueNull_noMisleading() {
        // 本金未设（0）→ 净值 null，不给误导数值（P2-交易31 同口径）
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(List.of(buy("600000", 100, "10", D1)));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll("u")).thenReturn(List.of());
        TransferRepository transfers = mock(TransferRepository.class);
        when(transfers.findAll("u")).thenReturn(List.of());

        var curve = service(history, positions, transfers, account(1000, 0),
                kline(10)).build("u");
        assertTrue(curve.points().size() >= 1);
        assertNull(curve.points().get(curve.points().size() - 1).netValue(), "principal=0 → 净值 null");
    }

    @Test
    void anchorReset_dropsPhantomPositions() {
        // 2026-09-15 生产事故复现：历史成交只补了买入没补卖出 → 纯回放凭空多出 000776 持仓，
        // 资金曲线市值虚高。锚定日到达后用券商快照基线重置 → 虚假标的消失。
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(List.of(
                buy("000776", 1000, "10.0", D1),   // 只买没卖（虚假持仓）
                buy("600000", 500, "10.0", D1)));  // 真实持仓
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll("u")).thenReturn(List.of(
                new Position("600000", "名", 500, new BigDecimal("10"),
                        new BigDecimal("10"), null, null, null, null, null, null)));

        TradingAnchorRepository anchor = mock(TradingAnchorRepository.class);
        when(anchor.find("u")).thenReturn(new SnapshotAnchor(D1, D1));
        when(anchor.holdings("u")).thenReturn(List.of(new SnapshotHolding("600000", "名", 500)));
        when(anchor.holdingsRecorded("u")).thenReturn(true);

        // 无锚定：000776 1000 + 600000 500 = 15000
        var noAnchorCurve = service(history, positions, mock(TransferRepository.class),
                account(0, 15000), kline(10, 10, 10)).build("u");
        assertEquals(0, noAnchorCurve.points().get(noAnchorCurve.points().size() - 1)
                .marketValue().compareTo(new BigDecimal("15000.00")), "无锚定 → 含虚假持仓");

        // 有锚定：只剩快照基线的 600000 × 500 = 5000
        var anchored = service(history, positions, mock(TransferRepository.class),
                account(0, 15000), anchor, kline(10, 10, 10)).build("u");
        for (EquityCurveService.EquityPoint p : anchored.points()) {
            if (p.date().isBefore(D1)) continue;
            assertEquals(0, p.marketValue().compareTo(new BigDecimal("5000.00")),
                    "锚定日之后不应再出现虚假持仓：" + p);
        }
    }

    @Test
    void periods_todayPnlFromAssetDelta() {
        // 今日盈亏 = 今日总资产 − 昨日总资产（无转账）；10 → 11 → 12，1000 股
        LocalDate today = LocalDate.now();
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(List.of(buy("600000", 1000, "10.0", today.minusDays(2))));
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll("u")).thenReturn(List.of());
        TransferRepository transfers = mock(TransferRepository.class);
        when(transfers.findAll("u")).thenReturn(List.of());

        var periods = service(history, positions, transfers, account(0, 10000),
                klineFrom(today.minusDays(2), 10, 11, 12)).periods("u");

        assertNotNull(periods.today(), "应有今日盈亏");
        assertEquals(0, periods.today().pnl().compareTo(new BigDecimal("1000.00")),
                "今日 +1000：" + periods.today().pnl());
        assertNotNull(periods.today().pct(), "有前一日基线 → 比例可算");
        assertEquals(0, periods.today().pct().compareTo(new BigDecimal("9.09")),
                "1000/11000 = 9.09%：" + periods.today().pct());
        // 周/月包含今日 → 绝对值不小于今日
        assertNotNull(periods.week());
        assertNotNull(periods.month());
        assertTrue(periods.month().pnl().abs().compareTo(periods.today().pnl().abs()) >= 0,
                "本月应至少包含今日");
        assertEquals(today.toString(), periods.asOf());
    }

    @Test
    void dailyPnl_brokerSemantics_sellUsesPrevCloseNotCost() {
        // 2026-09-16 口径回归：卖出部分必须按**昨收**算，不是建仓成本（那是「从建仓赚了多少」，
        // 会把过去累积的浮盈记进当天）。生产复算：09-14 云南锗业/有研新材/方正科技 + 手续费
        // = 2225.59，与券商 App 一字不差。
        LocalDate d0 = LocalDate.now().minusDays(3);
        LocalDate d1 = LocalDate.now().minusDays(2);
        TradeRecord sell = new TradeRecord("t_sell", "600000", "名", TradeDirection.SELL,
                new BigDecimal("12"), 400, new BigDecimal("4800.00"), d1,
                LocalTime.of(10, 0), null, null, null, null, null,
                LocalDateTime.of(d1, LocalTime.of(10, 0)), null, null);
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(List.of(sell));
        PositionRepository positions = mock(PositionRepository.class);
        // 当前持仓 600（卖后），成本 8 → 回放推出底仓 1000（昨日持仓）
        when(positions.findAll("u")).thenReturn(List.of(
                new Position("600000", "名", 600, new BigDecimal("8"),
                        new BigDecimal("8"), null, null, null, null, null, null)));

        var curve = service(history, positions, mock(TransferRepository.class), account(0, 10000),
                klineFrom(d0, 10, 11, 12)).build("u");

        // 卖出：净额(含费) − 昨收 10 × 400；旧仓：(11 − 10) × 600
        BigDecimal expect = com.adaiadai.core.domain.trading.CommissionCalculator
                .sellProceeds("600000", new BigDecimal("12"), 400)
                .subtract(new BigDecimal("10").multiply(BigDecimal.valueOf(400)))
                .add(new BigDecimal("600"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal actual = curve.dailyPnl().get(d1.toString());
        assertEquals(0, actual.compareTo(expect),
                "卖出按昨收（不是成本 8）→ 旧口径会算出 +2400：" + actual);
        assertTrue(actual.compareTo(new BigDecimal("2200")) < 0,
                "按成本口径会明显偏大（+2400 量级），券商口径应 ≈2200 以下：" + actual);
    }

    @Test
    void periods_emptyCurve_noFabricatedZero() {
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll("u")).thenReturn(List.of());
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll("u")).thenReturn(List.of());
        TransferRepository transfers = mock(TransferRepository.class);
        when(transfers.findAll("u")).thenReturn(List.of());
        AccountSnapshotRepository account = mock(AccountSnapshotRepository.class);
        when(account.findLatest(anyString())).thenReturn(Optional.empty());

        var p = service(history, positions, transfers, account, kline(10)).periods("u");
        assertNull(p.today(), "无数据 → 不给 0 冒充");
        assertTrue(p.note() != null && !p.note().isBlank(), "应有人话说明");
    }
}
