package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.AccountSnapshotFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交易账本「丢行/丢值必须可见」批（2026-09-14，REVIEW P2-交易43/45/47/48 + P2-工程4）。
 *
 * <p>本批修的是同一族问题：<b>解析或读盘出了问题，系统却按「一切正常」继续</b>——
 * 历史成交里没认出来的行静默消失、资金文件读不到的数值被当 0/空写进账户、损坏的 JSON
 * 被读出前半段后回写。这里逐条钉住「可见 / 拒绝 / 带来源」的行为。
 */
class LedgerFailVisibleTest {

    private static final String USER = "default";

    // ── 服务装配（照 TradingAnchorGuardTest / NegativeCostPositionTest 的 mock 模板）──

    private TradingAppService service(PositionRepository positions,
                                      AccountSnapshotRepository accounts,
                                      TradingHistoryRepository history,
                                      TradingAnchorRepository anchor) {
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        when(ruleRepo.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        return new TradingAppService(positions, mock(RecordRepository.class), history,
                mock(WatchlistRepository.class), mock(SoldTradeRepository.class), accounts,
                mock(TransferRepository.class), mock(MarketDataSource.class),
                mock(TradingLotService.class), ruleRepo, anchor);
    }

    // ── P2-交易45：资金文件「表头命中但数值读不出」必须拒绝导入，绝不用半份数据覆盖账户 ──

    @Test
    void importCashQuery_headerValueUnparsable_rejectsInsteadOfWritingNull() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(USER)).thenReturn(List.of());
        AccountSnapshotRepository accounts = mock(AccountSnapshotRepository.class);
        when(accounts.findLatest(USER)).thenReturn(Optional.empty());
        TradingAppService svc = service(positions, accounts, mock(TradingHistoryRepository.class),
                mock(TradingAnchorRepository.class));

        // 首行正则能命中（1.2.3 落在 [\d,.]+ 内），但 parseNum 失败 → cash = null
        String content = "人民币: 余额:1.2.3  可用:1000.00  可取:500.00  参考市值:2000.00  资产:3000.00  盈亏:10.00\n";

        TradingException ex = assertThrows(TradingException.class, () -> svc.importCashQuery(USER, content));
        assertTrue(ex.getMessage().contains("余额"), "报错要指名哪一项没读成数字：" + ex.getMessage());
        verify(accounts, never()).update(anyString(), any());
    }

    @Test
    void importCashQuery_unparsedDetailRow_countedInResult() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(USER)).thenReturn(List.of());
        AccountSnapshotRepository accounts = mock(AccountSnapshotRepository.class);
        when(accounts.findLatest(USER)).thenReturn(Optional.empty());
        when(accounts.update(anyString(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            return fn.apply(Optional.empty());
        });
        TradingAppService svc = service(positions, accounts, mock(TradingHistoryRepository.class),
                mock(TradingAnchorRepository.class));

        String content = "人民币: 余额:1381.93  可用:1381.93  可取:1381.93  参考市值:77850.00  资产:79231.93  盈亏:100.00\n"
                + "证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏\n"
                + "600206 有研新材 900 46.012 50.0 100.0\n"
                + "这不是明细行 xxx\n";

        TradingAppService.CashImportResult r = svc.importCashQuery(USER, content);
        assertEquals(1, r.unparsedRows(), "没看懂的明细行要计数上报（丢一行 = 该只精确成本不更新）");
        assertEquals(0, r.cash().compareTo(new BigDecimal("1381.93")));
    }

    // ── P2-交易48：当日盈亏来源随值落盘（broker / calc / 未知），不许把券商来源错记成系统计算 ──

    @Test
    void importCashQuery_withTodayPnlColumn_marksSourceBroker() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(USER)).thenReturn(List.of());
        AccountSnapshotRepository accounts = mock(AccountSnapshotRepository.class);
        when(accounts.findLatest(USER)).thenReturn(Optional.empty());
        AtomicReference<AccountSnapshot> written = new AtomicReference<>();
        when(accounts.update(anyString(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            AccountSnapshot next = fn.apply(Optional.empty());
            written.set(next);
            return next;
        });
        TradingAppService svc = service(positions, accounts, mock(TradingHistoryRepository.class),
                mock(TradingAnchorRepository.class));

        String content = "人民币: 余额:1381.93  可用:1381.93  可取:1381.93  参考市值:77850.00  资产:79231.93  盈亏:100.00\n"
                + "证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏 当日盈亏\n"
                + "600206 有研新材 900 46.012 50.0 100.0 -1759.00\n";

        svc.importCashQuery(USER, content, LocalDate.of(2026, 9, 11));
        assertEquals(AccountSnapshot.SOURCE_BROKER, written.get().todayPnlSource(),
                "文件带「当日盈亏」列且明细非空 → 券商权威口径");
        assertEquals(0, written.get().todayPnl().compareTo(new BigDecimal("-1759.00")));
    }

    @Test
    void importCashQuery_withoutTodayPnlColumn_keepsPreviousSource() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(USER)).thenReturn(List.of());
        AccountSnapshotRepository accounts = mock(AccountSnapshotRepository.class);
        AccountSnapshot existing = new AccountSnapshot(new BigDecimal("79231.93"), new BigDecimal("1381.93"),
                new BigDecimal("1381.93"), new BigDecimal("1381.93"), new BigDecimal("77850"),
                new BigDecimal("100"), new BigDecimal("-1759.00"), new BigDecimal("145000"),
                LocalDate.of(2026, 9, 10), AccountSnapshot.SOURCE_CALC);
        when(accounts.findLatest(USER)).thenReturn(Optional.of(existing));
        AtomicReference<AccountSnapshot> written = new AtomicReference<>();
        when(accounts.update(anyString(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            AccountSnapshot next = fn.apply(Optional.of(existing));
            written.set(next);
            return next;
        });
        TradingAppService svc = service(positions, accounts, mock(TradingHistoryRepository.class),
                mock(TradingAnchorRepository.class));

        // 明细缺「当日盈亏」列 → 不清零、不改来源（P2-交易37 + 48）
        String content = "人民币: 余额:1381.93  可用:1381.93  可取:1381.93  参考市值:77850.00  资产:79231.93  盈亏:100.00\n"
                + "证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏\n"
                + "600206 有研新材 900 46.012 50.0 100.0\n";

        svc.importCashQuery(USER, content);
        assertEquals(AccountSnapshot.SOURCE_CALC, written.get().todayPnlSource(),
                "本次导入没动当日盈亏，来源必须原样继承（不得错标成 broker，也不得抹成 null）");
        assertEquals(0, written.get().todayPnl().compareTo(new BigDecimal("-1759.00")), "旧值保留");
    }

    // ── P2-交易43：历史成交导入结果带「没看懂的行」 ──

    @Test
    void importHistoricalTrades_dryRun_carriesUnparsedRows() {
        PositionRepository positions = mock(PositionRepository.class);
        when(positions.findAll(USER)).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(USER)).thenReturn(List.of());
        AccountSnapshotRepository accounts = mock(AccountSnapshotRepository.class);
        when(accounts.findLatest(USER)).thenReturn(Optional.empty());
        TradingAppService svc = service(positions, accounts, history, mock(TradingAnchorRepository.class));

        String old = LocalDate.now().minusDays(30).toString().replace("-", "");
        String content = String.join("\n",
                String.join("\t", "成交日期", "成交时间", "证券代码", "证券名称", "买卖标志",
                        "成交数量", "成交价格", "成交金额", "成交编号", "发生金额", "备注"),
                String.join("\t", old, "14:52:56", "600206", "有研新材", "卖出",
                        "-200.00", "33.12000000", "6624.00", "69351117", "6620.05", "证券卖出"),
                String.join("\t", "2026080X", "14:53:51", "002428", "坏日期", "买入",
                        "400.00", "68.14000000", "27256.00", "0101000075800458", "-27258.33", "证券买入"));

        TradingAppService.HistoricalTradeImportResult r = svc.importHistoricalTrades(
                USER, content, TradingAppService.ImportMode.APPEND, true);
        assertEquals(1, r.imported(), "1 笔可补录");
        assertEquals(1, r.unparsed().size(), "1 行没看懂必须带出去（原来是静默丢）");
        assertTrue(r.unparsed().get(0).describe().contains("成交日期"));
    }

    // ── P2-工程4：JSON 读路径严格性——损坏/截断文件不得被当合法 JSON 读出前半段 ──

    @Test
    void accountSnapshotRepository_trailingGarbage_failsInsteadOfHalfRead() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        // 合法 JSON 后面跟着半行垃圾（写坏/写两遍/写到一半被杀）
        fs.write(USER, "trading/account.json",
                "{\"assets\":100,\"cash\":50,\"snapshotDate\":\"2026-09-11\"}{\"assets\":999");
        AccountSnapshotFileRepository repo = new AccountSnapshotFileRepository(fs);
        assertTrue(repo.findLatest(USER).isEmpty(),
                "尾部垃圾必须让读失败（默认宽容会读出前半段，随后任一写入把半截回写 → 静默丢数据）");
    }

    @Test
    void accountSnapshotRepository_roundTripsTodayPnlSource_oldFileReadsNull() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        AccountSnapshotFileRepository repo = new AccountSnapshotFileRepository(fs);
        repo.save(USER, new AccountSnapshot(new BigDecimal("79231.93"), new BigDecimal("1381.93"),
                new BigDecimal("1381.93"), new BigDecimal("1381.93"), new BigDecimal("77850"),
                new BigDecimal("100"), new BigDecimal("-1759.00"), new BigDecimal("145000"),
                LocalDate.of(2026, 9, 11), AccountSnapshot.SOURCE_BROKER));
        assertEquals(AccountSnapshot.SOURCE_BROKER, repo.findLatest(USER).orElseThrow().todayPnlSource());

        // 存量文件（P2-交易48 之前写的，没有 todayPnlSource 键）→ 读出 null（未知），不编造
        fs.write(USER, "trading/account.json",
                "{\"assets\":100,\"cash\":50,\"principal\":80,\"snapshotDate\":\"2026-09-11\"}");
        assertNull(repo.findLatest(USER).orElseThrow().todayPnlSource(),
                "旧文件没有来源字段 → null（前端据此不标注），不得凭空说是券商口径");
    }
}
