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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * **A 方案核心（2026-09-14 用户拍板）**：卖出部分的当日基准是**昨收**，不是建仓成本。
     * <p>
     * 现场（真实数据）：云南锗业建仓成本 53.765、昨收 88.43、今日卖 100@90.32。
     * 旧口径算出 (9032−5376.53)=3655.47 当成「当日盈亏」，把过去累积的浮盈记进了今天；
     * 券商口径只有 100×(90.32−88.43)=189。当日盈亏 5826 vs 券商 2245（差 3587）就是这么来的。
     */
    @Test
    void computeDailyPnl_sellUsesYesterdayClose_notBuildCost() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of()); // 当日清仓
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("002428", TradeDirection.SELL, 100, "90.32", "0.0", DAY, null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("002428", md("002428", "90.32", "88.43")));
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // 9032 − 88.43×100 = 189（券商口径）；若按建仓成本 53.765 会得 3655.47
        assertEquals(0, new BigDecimal("189.00").compareTo(r.todayPnl()), "实际 " + r.todayPnl());
        assertTrue(r.notes().isEmpty(), "昨收齐备不应有附注: " + r.notes());
    }

    /**
     * 今日**卖光**的票已不在持仓里，但它的昨收仍必须拿得到——否则那笔卖出的当日盈亏算不出来。
     * 本用例按「入参符号集合」返回行情：只查持仓（漏掉今日成交票）时会拿不到 600601 的昨收。
     */
    @Test
    void computeDailyPnl_sellClearedHolding_quoteCoversTradedSymbols() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(pos("600000", 100, "10.0")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600601", TradeDirection.SELL, 100, "14.81", "0.0", DAY, null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenAnswer(inv -> {
            List<String> asked = inv.getArgument(0);
            Map<String, MarketData> m = new java.util.HashMap<>();
            if (asked.contains("600000")) m.put("600000", md("600000", "10.0", "10.0"));
            if (asked.contains("600601")) m.put("600601", md("600601", "14.81", "14.83"));
            return m;
        });
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // 600601：1481 − 14.83×100 = −2（今日微亏 2 元）；600000 浮动 0 → −2
        assertEquals(0, new BigDecimal("-2.00").compareTo(r.todayPnl()), "实际 " + r.todayPnl());
        assertTrue(r.notes().stream().noneMatch(n -> n.contains("缺昨收")),
                "今日有成交的票也必须进行情查询，否则漏算: " + r.notes());
    }

    /** 逐股拆分（持仓列表消费）：bySymbol 之和必须等于总数，且按股各归各家。 */
    @Test
    void dailyPnlDetail_splitsBySymbol() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(
                pos("002428", 300, "53.765"), pos("600206", 500, "46.012")));
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("002428", TradeDirection.SELL, 100, "90.32", "0.0", DAY, null),
                trade("600206", TradeDirection.SELL, 400, "47.2", "0.0", DAY, null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of(
                "002428", md("002428", "90.32", "88.43"),
                "600206", md("600206", "47.2", "45.55")));
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlDetail d = service.dailyPnlDetail(USER, DAY);

        // 002428：卖 100×(90.32−88.43)=189 + 留仓 300×1.89=567 → 756
        // 600206：卖 400×(47.2−45.55)=660 + 留仓 500×1.65=825 → 1485
        assertEquals(0, new BigDecimal("756.00").compareTo(d.bySymbol().get("002428")),
                "002428 实际 " + d.bySymbol().get("002428"));
        assertEquals(0, new BigDecimal("1485.00").compareTo(d.bySymbol().get("600206")),
                "600206 实际 " + d.bySymbol().get("600206"));
        assertEquals(0, d.todayPnl().compareTo(
                        d.bySymbol().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)),
                "逐股之和必须等于总数（否则列表与账户卡会不一致）");
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

    /**
     * 新口径下同类风险从「无成本基线」变成「**缺昨收**」：算不出来必须如实附注，
     * 不得静默按 0 计（静默会让当日盈亏偏小且看不出原因）。
     */
    @Test
    void computeDailyPnl_missingYesterdayClose_notesItAndExcludes() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of()); // 当日清仓
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("600000", TradeDirection.SELL, 100, "12.0", "0.0", DAY, null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of()); // 行情整体拉不到 → 无昨收
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        assertTrue(r.notes().stream().anyMatch(n -> n.contains("缺昨收")),
                "缺昨收必须诚实附注（refreshTodayPnl 见实质缺失会拒绝写回）: " + r.notes());
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
                1           600206          有研新材        600.00          600.00          46.8091        46.4200        27852.00        0.00            0.00            -233.33         -0.831             A000000001
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
                1           600206          有研新材        600.00          600.00          46.8091        46.4200        27852.00        0.00            0.00            -233.33         -0.831            -258.00       A000000001
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

        // 2026-09-13：改用日期重载并显式传一个**交易日**——refreshTodayPnl 新增了「非交易日不重算」闸
        // （事故：周六重算把周五的涨跌写成当日盈亏），而本用例测的是重算本身，
        // 若走 now() 就会在周末运行时早退（测试结果依赖「今天是不是交易日」＝不稳定用例）。
        service.refreshTodayPnl(USER, LocalDate.of(2026, 9, 11));

        // 持仓 60 × (12−10) = 120 → todayPnl 更新为 120，其余字段原样保留
        assertEquals(0, new BigDecimal("120.00").compareTo(saved.get().todayPnl()),
                "实际 " + saved.get().todayPnl());
        assertEquals(0, new BigDecimal("81357.16").compareTo(saved.get().assets()), "assets 不应被改动");
        assertEquals(0, new BigDecimal("2278.16").compareTo(saved.get().cash()), "cash 不应被改动");
    }

    @Test
    void computeDailyPnl_manualSellWithoutFee_estimatesSellFees() {
        // fee=null（截图/手动记录）→ 卖出净额按系统费率估算扣费（佣金+印花税+过户）；
        // 2026-09-14 A 方案后基准改为昨收：这里昨收 11.0 → ≈ 1199.29 − 1100 = 99.29。
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of()); // 当日清仓
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                tradeAt("600000", TradeDirection.SELL, 100, "12.0", null, DAY,
                        LocalTime.of(9, 30), null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600000", md("600000", "12.0", "11.0")));
        TradingAppService service = service(positions, history, mock(AccountSnapshotRepository.class), market);

        TradingAppService.DailyPnlResult r = service.computeDailyPnl(USER, DAY);

        // 卖净额 ≈ 1200 − 0.10(佣) − 0.60(印花) − 0.01(沪过户) = 1199.29；基准 = 昨收 11.0×100 = 1100
        assertTrue(Math.abs(r.todayPnl().doubleValue() - 99.29) < 0.02,
                "手动卖出无 fee 应按估算费扣除、且以昨收为基准，实际 " + r.todayPnl());
    }

    // ── 持仓列表视图（2026-09-14 用户拍板：逐股当日盈亏 + 今日涨跌幅 + 仓位比例）──

    /** 今日涨跌幅 =（现价−昨收）/昨收；仓位比例 = 该股市值/总资产；顶部总仓位/现金比例同源。 */
    @Test
    void getPositionsDailyView_dayChangeRatioAndPositionRatio() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of(
                pos("002428", 300, "53.765"), pos("600206", 500, "46.012")));
        AccountSnapshotRepository account = mock(AccountSnapshotRepository.class);
        // 现金 30757.53 → 总资产 = 27096 + 23600 + 30757.53 = 81453.53
        when(account.findLatest(anyString())).thenReturn(Optional.of(new AccountSnapshot(
                new BigDecimal("81453.53"), new BigDecimal("30757.53"),
                new BigDecimal("30757.53"), new BigDecimal("30757.53"),
                new BigDecimal("50696.00"), new BigDecimal("17698.00"), BigDecimal.ZERO,
                new BigDecimal("130000"), LocalDate.of(2026, 9, 14))));
        // P2-交易51（2026-09-17）：成交要落在**实现认定的「当日」**上——盘前 / 非交易日时「当日」
        // 是上一交易日（那时行情接口给的就是上一交易日的收盘价）。测试若写死 LocalDate.now()，
        // 凌晨跑必红（真实踩到：00:06 全量跑时这条挂了）——那不是实现错，是测试没跟口径。
        LocalDate effectiveDay = LocalDate.now();
        if (TradingAppService.quoteIsFromPreviousDay(effectiveDay)) {
            LocalDate prev = TradingAppService.previousTradingDay(effectiveDay);
            if (prev != null) effectiveDay = prev;
        }
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of(
                trade("002428", TradeDirection.SELL, 100, "90.32", "0.0", effectiveDay, null),
                trade("600206", TradeDirection.SELL, 400, "47.2", "0.0", effectiveDay, null)));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of(
                "002428", md("002428", "90.32", "88.43"),
                "600206", md("600206", "47.2", "45.55")));
        TradingAppService service = service(positions, history, account, market);

        TradingAppService.PositionsDailyView v = service.getPositionsDailyView(USER);

        TradingAppService.StockDaily yn = v.daily().get("002428");
        // 涨跌幅 = (90.32−88.43)/88.43 = 2.14%
        assertEquals(0, new BigDecimal("2.14").compareTo(yn.dayChangePct()), "涨跌幅 实际 " + yn.dayChangePct());
        assertEquals(0, new BigDecimal("756.00").compareTo(yn.todayPnl()), "当日盈亏 实际 " + yn.todayPnl());
        // 仓位 = 27096 / 81453.53 = 33.27%
        assertEquals(0, new BigDecimal("33.27").compareTo(yn.positionRatio()), "仓位 实际 " + yn.positionRatio());
        // 总仓位 = 50696 / 81453.53 = 62.24%；现金比例 = 30757.53 / 81453.53 = 37.76%
        assertEquals(0, new BigDecimal("62.24").compareTo(v.totalPositionRatio()),
                "总仓位 实际 " + v.totalPositionRatio());
        assertEquals(0, new BigDecimal("37.76").compareTo(v.cashRatio()), "现金比例 实际 " + v.cashRatio());
    }

    /** 总资产为 0（未导资金快照）→ 仓位比例 null，不得编造 0%。 */
    @Test
    void getPositionsDailyView_zeroAssets_ratioNullNotZero() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(positions, mock(TradingHistoryRepository.class),
                mock(AccountSnapshotRepository.class), mock(MarketDataSource.class));

        TradingAppService.PositionsDailyView v = service.getPositionsDailyView(USER);

        assertEquals(null, v.totalPositionRatio(), "总资产为 0 时不得编造 0%");
        assertEquals(null, v.cashRatio());
    }

    // ── P2-交易51（2026-09-16）：盘前 / 非交易日不得把「上一交易日的当日盈亏」当成今天 ──

    @Test
    void quoteIsFromPreviousDay_onlyWhenAskingAboutToday() {
        LocalDate today = LocalDate.of(2026, 9, 16);        // 周三（交易日）
        assertFalse(TradingAppService.quoteIsFromPreviousDay(today.minusDays(1), today, LocalTime.of(10, 0)),
                "问的是历史日期 → 与「现在几点」无关，照该日期算");
        assertTrue(TradingAppService.quoteIsFromPreviousDay(today, today, LocalTime.of(9, 0)),
                "交易日但开盘前 → 行情还是上一交易日的");
        assertFalse(TradingAppService.quoteIsFromPreviousDay(today, today, LocalTime.of(9, 30)),
                "开盘后 → 行情已是今天的");
    }

    @Test
    void quoteIsFromPreviousDay_trueOnNonTradingDay() {
        LocalDate saturday = LocalDate.of(2026, 9, 19);
        assertTrue(TradingAppService.quoteIsFromPreviousDay(saturday, saturday, LocalTime.of(10, 0)),
                "非交易日：行情接口给的「现价」是上一交易日收盘");
    }

    @Test
    void previousTradingDay_skipsWeekend() {
        assertEquals(LocalDate.of(2026, 9, 18),
                TradingAppService.previousTradingDay(LocalDate.of(2026, 9, 21)),
                "周一的上一个交易日 = 上周五");
    }
}
