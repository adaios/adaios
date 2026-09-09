package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-交易37（2026-09-09，用户拍板口径①）——当日盈亏精确计算测试：
 * 已实现（卖净额−成本） + 持仓日浮动 + 股息/红利税；诚实降级（缺昨收/无成本基线进 notes）；
 * 资金股份导入缺「当日盈亏」列 → 保留既有 todayPnl 不清零。
 */
class DailyPnlComputeTest {

    private static final String USER = "default";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate PREV = LocalDate.of(2026, 9, 8);

    private TradingAppService service(PositionRepository positions, TradingHistoryRepository history,
                                      AccountSnapshotRepository account, MarketDataSource market) {
        TradingRuleSettingsRepository rules = mock(TradingRuleSettingsRepository.class);
        when(rules.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        return new TradingAppService(positions, mock(RecordRepository.class), history,
                mock(WatchlistRepository.class), mock(SoldTradeRepository.class), account,
                mock(TransferRepository.class), market, mock(TradingLotService.class), rules);
    }

    private Position pos(String symbol, int qty, String avg) {
        return new Position(symbol, symbol + "名", qty, new BigDecimal(avg), new BigDecimal(avg),
                LocalDateTime.of(DAY, LocalTime.of(9, 0)));
    }

    private MarketData md(String symbol, String price, String yesterdayClose) {
        return new MarketData(symbol, symbol + "名", new BigDecimal(price),
                yesterdayClose == null ? null : new BigDecimal(yesterdayClose),
                null, null, null, null, 0);
    }

    private TradeRecord trade(String symbol, TradeDirection dir, int volume, String price,
                              String fee, LocalDate date, String amountOverride) {
        return tradeAt(symbol, dir, volume, price, fee, date, LocalTime.of(10, 0), amountOverride);
    }

    private TradeRecord tradeAt(String symbol, TradeDirection dir, int volume, String price,
                                String fee, LocalDate date, LocalTime time, String amountOverride) {
        return new TradeRecord("t_" + symbol + "_" + dir + "_" + volume + "_" + date + "_" + time,
                symbol, symbol + "名", dir, new BigDecimal(price), volume,
                amountOverride != null ? new BigDecimal(amountOverride)
                        : new BigDecimal(price).multiply(BigDecimal.valueOf(volume)),
                date, time, null, null, null, null,
                fee != null ? new BigDecimal(fee) : null,
                LocalDateTime.of(date, time), null, null);
    }

    @Test
    void computeDailyPnl_sellOldHoldingPlusFloat() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600000", 60, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.SELL, 40, "12.0", "1.0", DAY, null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600000", md("600000", "12.0", "10.0")));
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // 已实现 = (480−1) − 40×10 = 79；浮动 = (12−10)×60 = 120 → 199
        assertEquals(0, new BigDecimal("199.00").compareTo(r.todayPnl()), "实际 " + r.todayPnl());
        assertTrue(r.notes().isEmpty(), "行情与成本齐备不应有附注: " + r.notes());
    }

    @Test
    void computeDailyPnl_withDividendCashEvent() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600000", 60, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.SELL, 40, "12.0", "1.0", DAY, null),
                // 股息入账（volume=0，amount=发生金额绝对值，BUY 方向=现金+）
                trade("600000", TradeDirection.BUY, 0, "0.0", null, DAY, "100")));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600000", md("600000", "12.0", "10.0")));
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // 199 + 股息 100 = 299
        assertEquals(0, new BigDecimal("299.00").compareTo(r.todayPnl()), "实际 " + r.todayPnl());
    }

    @Test
    void computeDailyPnl_sellCleared_noBaseline_noteNotSilent() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of()); // 已清仓
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.SELL, 100, "12.0", null, DAY, null))); // 历史无 BUY 基线
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        assertTrue(r.notes().stream().anyMatch(n -> n.contains("无成本基线")), "应诚实说明无成本基线: " + r.notes());
    }

    @Test
    void computeDailyPnl_sellCleared_usesHistoricalBuyAverage() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.SELL, 100, "12.0", "0.0", DAY, null),
                trade("600000", TradeDirection.BUY, 100, "10.0", "0.0", PREV, null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // 已实现 = 1200 − 100×10 = 200（历史买入加权成本）
        assertEquals(0, new BigDecimal("200.00").compareTo(r.todayPnl()), "实际 " + r.todayPnl());
    }

    @Test
    void importCashQuery_missingTodayPnlColumn_keepsExistingValue() {
        // 明细无「当日盈亏」列 → 不得清零（2026-09-09 生产 428→0 根因回归锁）
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository account = mock(AccountSnapshotRepository.class);
        when(account.findLatest(anyString())).thenReturn(Optional.of(
                new AccountSnapshot(new BigDecimal("81357.16"), new BigDecimal("2278.16"),
                        new BigDecimal("2278.16"), new BigDecimal("2278.16"),
                        new BigDecimal("79079.00"), new BigDecimal("16423.25"),
                        new BigDecimal("428.00"), new BigDecimal("130000"), LocalDate.of(2026, 9, 9))));
        when(account.update(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            AccountSnapshot next = fn.apply(account.findLatest(inv.getArgument(0)));
            saved.set(next);
            return next;
        });
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(mock(PositionRepository.class), history, account,
                mock(MarketDataSource.class));
        String cashText = """
                人民币: 余额:2278.16  可用:2278.16  可取:2278.16  参考市值:79079.00  资产:81357.16  盈亏:16423.25
                -------------------------------------------------------------------------------------------------------
                编号        证券代码        证券名称        证券数量        可卖数量        成本价          当前价          最新市值        今买数量        今卖数量        浮动盈亏        盈亏比例(%)        股东代码
                1           600206          有研新材        600.00          600.00          46.8091        46.4200        27852.00        0.00            0.00            -233.33         -0.831             A511358384
                """;
        service.importCashQuery(USER, cashText);

        assertEquals(0, new BigDecimal("428.00").compareTo(saved.get().todayPnl()),
                "文件缺「当日盈亏」列 → 应保留既有 428，不清零（实际 " + saved.get().todayPnl() + "）");
    }

    @Test
    void importCashQuery_withTodayPnlColumn_overrides() {
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository account = mock(AccountSnapshotRepository.class);
        when(account.findLatest(anyString())).thenReturn(Optional.of(
                new AccountSnapshot(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("428.00"), BigDecimal.ONE,
                        LocalDate.of(2026, 9, 9))));
        when(account.update(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            AccountSnapshot next = fn.apply(account.findLatest(inv.getArgument(0)));
            saved.set(next);
            return next;
        });
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(mock(PositionRepository.class), history, account,
                mock(MarketDataSource.class));
        String cashText = """
                人民币: 余额:2278.16  可用:2278.16  可取:2278.16  参考市值:79079.00  资产:81357.16  盈亏:16423.25
                -------------------------------------------------------------------------------------------------------
                编号        证券代码        证券名称        证券数量        可卖数量        成本价          当前价          最新市值        今买数量        今卖数量        浮动盈亏        盈亏比例(%)        当日盈亏      股东代码
                1           600206          有研新材        600.00          600.00          46.8091        46.4200        27852.00        0.00            0.00            -233.33         -0.831            -258.00       A511358384
                """;
        service.importCashQuery(USER, cashText);

        assertEquals(0, new BigDecimal("-258.00").compareTo(saved.get().todayPnl()),
                "文件带「当日盈亏」列 → 以券商真源覆盖（实际 " + saved.get().todayPnl() + "）");
    }

    // ── 三官深审修复（2026-09-09）：T+1 旧仓成本配比 / T+0 边界 / 空明细保留 / 随流水重算 ──

    @Test
    void computeDailyPnl_sellClearedThenRebuy_T1OldCostNotSymbolFlip() {
        // backend 深审可复现示例：q0=1000@10，SELL 1000@12（09:30）后 BUY 1000@12.5（10:00），收盘 12.6
        // → 卖出为旧仓（T+1），成本 = 盘前历史买入 10，不得按当日买入价冲抵（否则符号翻转）
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600000", 1000, "11.25")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.BUY, 1000, "10.0", "0.0", PREV, null),
                tradeAt("600000", TradeDirection.SELL, 1000, "12.0", "0.0", DAY,
                        LocalTime.of(9, 30), null),
                tradeAt("600000", TradeDirection.BUY, 1000, "12.5", "0.0", DAY,
                        LocalTime.of(10, 0), null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600000", md("600000", "12.6", "10.0")));
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // 已实现 = 12000 − 1000×10 = 2000；浮动（今日新买 1000 @12.5→12.6）= 100 → 2100（真实口径）
        assertEquals(0, new BigDecimal("2100.00").compareTo(r.todayPnl()), "实际 " + r.todayPnl());
    }

    @Test
    void computeDailyPnl_t0MixedBuySell_matchingWithHonestNote() {
        // T+0 边界（卖出晚于当日最早买入，可转债/异常数据）：卖量内冲抵当日买入 + 超出按历史旧仓成本
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600000", 300, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.BUY, 500, "10.0", "0.0", PREV, null),
                tradeAt("600000", TradeDirection.BUY, 800, "10.0", "0.0", DAY,
                        LocalTime.of(9, 31), null),
                tradeAt("600000", TradeDirection.SELL, 1000, "12.0", "0.0", DAY,
                        LocalTime.of(10, 0), null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600000", md("600000", "12.0", "10.0")));
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // matched 800@10 + 旧仓 200@10 = 成本 10000 → 已实现 2000；持仓浮动（旧仓 300@昨收差 2）= 600 → 2600
        assertEquals(0, new BigDecimal("2600.00").compareTo(r.todayPnl()), "实际 " + r.todayPnl());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("T+0")),
                "T+0 边界应诚实附注: " + r.notes());
    }

    @Test
    void importCashQuery_emptyDetails_keepsExistingTodayPnl() {
        // 三官深审 P1-2：当日清仓后导出的资金文件明细为空 → 不得用 sum=0 覆盖精确当日盈亏（428→0 变体）
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository account = mock(AccountSnapshotRepository.class);
        when(account.findLatest(anyString())).thenReturn(Optional.of(
                new AccountSnapshot(new BigDecimal("20000.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), new BigDecimal("0.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("428.00"), new BigDecimal("130000"), LocalDate.of(2026, 9, 9))));
        when(account.update(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            AccountSnapshot next = fn.apply(account.findLatest(inv.getArgument(0)));
            saved.set(next);
            return next;
        });
        TradingAppService service = service(mock(PositionRepository.class),
                mock(TradingHistoryRepository.class), account, mock(MarketDataSource.class));
        String cashText = """
                人民币: 余额:0.00  可用:0.00  可取:0.00  参考市值:0.00  资产:20000.00  盈亏:0.00
                -------------------------------------------------------------------------------------------------------
                编号        证券代码        证券名称        证券数量        可卖数量        成本价          当前价          最新市值        今买数量        今卖数量        浮动盈亏        盈亏比例(%)        当日盈亏      股东代码
                """;
        service.importCashQuery(USER, cashText);

        assertEquals(0, new BigDecimal("428.00").compareTo(saved.get().todayPnl()),
                "明细为空 → 保留既有当日盈亏（实际 " + saved.get().todayPnl() + "）");
    }

    @Test
    void refreshTodayPnl_recomputesAndWritesOnlyTodayPnl() {
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository account = mock(AccountSnapshotRepository.class);
        AccountSnapshot base = new AccountSnapshot(new BigDecimal("81357.16"), new BigDecimal("2278.16"),
                new BigDecimal("2278.16"), new BigDecimal("2278.16"),
                new BigDecimal("79079.00"), new BigDecimal("16423.25"),
                BigDecimal.ZERO, new BigDecimal("130000"), LocalDate.of(2026, 9, 9));
        when(account.findLatest(anyString())).thenReturn(Optional.of(base));
        when(account.update(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            AccountSnapshot next = fn.apply(account.findLatest(inv.getArgument(0)));
            saved.set(next);
            return next;
        });
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600000", 60, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600000", md("600000", "12.0", "10.0")));
        TradingAppService service = service(positions, history, account, market);

        service.refreshTodayPnl(USER);

        // 持仓 60 × (12−10) = 120 → todayPnl 更新为 120，其余字段原样保留
        assertEquals(0, new BigDecimal("120.00").compareTo(saved.get().todayPnl()),
                "实际 " + saved.get().todayPnl());
        assertEquals(0, new BigDecimal("81357.16").compareTo(saved.get().assets()), "assets 不应被改动");
        assertEquals(0, new BigDecimal("2278.16").compareTo(saved.get().cash()), "cash 不应被改动");
    }

    @Test
    void computeDailyPnl_manualSellWithoutFee_estimatesSellFees() {
        // 三官深审 backend P2（2026-09-09）：手动记录 fee=null → 卖出净额按系统费率估算扣费
        //（佣金+印花税+沪过户），历史买入成本同样含估算费——与持仓 avgCost 摊薄口径一致
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of()); // 当日清仓
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.BUY, 100, "10.0", null, PREV, null),
                tradeAt("600000", TradeDirection.SELL, 100, "12.0", null, DAY,
                        LocalTime.of(9, 30), null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // 卖净额 ≈ 1200 − 0.10(佣) − 0.60(印花) − 0.01(沪过户) = 1199.29；历史含费成本 ≈ 1000.10 → ≈ 199.19
        assertTrue(Math.abs(r.todayPnl().doubleValue() - 199.19) < 0.02,
                "手动卖出无 fee 应按估算费扣除，实际 " + r.todayPnl());
    }
}
