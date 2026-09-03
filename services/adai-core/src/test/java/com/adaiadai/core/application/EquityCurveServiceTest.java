package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
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
        return new EquityCurveService(history, positions, transfers, account, kline);
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
}
