package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 券商口径当日盈亏 + 重算两道闸（2026-09-13 用户实测事故）。
 *
 * <p><b>事故</b>：账户卡上的当日盈亏显示 <b>−2837.00</b>。逐层查下来是两件事叠在一起：
 * <ol>
 *   <li><b>券商「持仓股」导出的「当日盈亏」列从来没被读</b>——前端只解析 代码/名称/数量/成本 四列，
 *       后端入参也没有这个字段。而这一列是<b>权威值</b>：文件里 600206 −1116.00 / 002428 −644.00 /
 *       600601 +1.00，Σ = <b>−1759.00</b>，与逐股复算（(45.55−46.79)×900 + (88.43−90.04)×400 +
 *       (14.83−14.82)×100）一字不差，且「去年市值 79609 → 今日 77850」的差也正好 1759。
 *       于是账户卡只能退回系统自算——而自算值会错。</li>
 *   <li><b>{@code refreshTodayPnl} 在周六被历史成交导入触发</b>：用周六的日期 +
 *       周末行情接口给的「最后两个交易日收盘」+ 当时<b>被双计污染</b>的持仓，算出
 *       −2837 并写成「当日盈亏」，在卡片上挂了两天（日志实证
 *       {@code 09-12 11:00:26 当日盈亏随成交流水重算 | todayPnl=-2837.00}）。</li>
 * </ol>
 *
 * <p>本文件守三件事：<b>① 券商值能进账户（且只在同日写、缺列不写）② 非交易日不重算
 * ③ 有实质未计入时不覆盖</b>。
 */
class BrokerTodayPnlTest {

    private static final String USER = "adai";
    /** 现场：账户快照日 = 2026-09-11（周五），券商「持仓股」导出也是 09-11。 */
    private static final LocalDate SNAPSHOT_DAY = LocalDate.of(2026, 9, 11);
    /** 券商「持仓股」09-11 导出「当日盈亏」列之和（权威值）。 */
    private static final BigDecimal BROKER_TODAY_PNL = new BigDecimal("-1759.00");
    /** 事故里挂了两天的错值（周六重算 + 双计持仓的产物）。 */
    private static final BigDecimal WRONG_VALUE = new BigDecimal("-2837.00");

    private AccountSnapshot snapshot(BigDecimal todayPnl, LocalDate date) {
        return new AccountSnapshot(new BigDecimal("79231.93"), new BigDecimal("1381.93"),
                new BigDecimal("1381.93"), new BigDecimal("1381.93"), new BigDecimal("77850.00"),
                new BigDecimal("14298.88"), todayPnl, new BigDecimal("130000"), date);
    }

    /** 捕获账户写入（并在写入时提供 findLatest —— 服务内是「读最新→改一个字段→写回」）。 */
    private AccountSnapshotRepository accountRepo(AccountSnapshot initial, AtomicReference<AccountSnapshot> saved) {
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(anyString())).thenReturn(Optional.ofNullable(initial));
        when(acc.update(anyString(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<Optional<AccountSnapshot>, AccountSnapshot> fn = inv.getArgument(1);
            AccountSnapshot next = fn.apply(acc.findLatest(inv.getArgument(0)));
            saved.set(next);
            return next;
        });
        return acc;
    }

    private TradingAppService service(PositionRepository repo, AccountSnapshotRepository acc,
                                      MarketDataSource market) {
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        when(ruleRepo.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(anyString())).thenReturn(List.of());
        return new TradingAppService(repo, mock(RecordRepository.class), history,
                mock(WatchlistRepository.class), mock(SoldTradeRepository.class), acc,
                mock(TransferRepository.class), market, mock(TradingLotService.class),
                ruleRepo, mock(TradingAnchorRepository.class));
    }

    private TradingAppService.PositionImportItem item(String symbol, int qty, String cost) {
        return new TradingAppService.PositionImportItem(symbol, symbol + "名", qty,
                new BigDecimal(cost), null, null, null, null);
    }

    // ── ① 券商「当日盈亏」列入账（持仓股导出）──

    @Test
    void importPositions_sameDate_writesBrokerTodayPnl() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository acc = accountRepo(snapshot(WRONG_VALUE, SNAPSHOT_DAY), saved);
        TradingAppService service = service(repo, acc, mock(MarketDataSource.class));

        service.importPositions(USER, List.of(item("600206", 900, "46.012")), true,
                SNAPSHOT_DAY, BROKER_TODAY_PNL);

        assertEquals(0, saved.get().todayPnl().compareTo(BROKER_TODAY_PNL),
                "同一份 09-11 文件的「当日盈亏」列（Σ −1759.00）应覆盖系统自算的 −2837.00");
        // 其余字段一个都不能动（持仓股文件不给现金/资产/市值）
        assertEquals(0, saved.get().cash().compareTo(new BigDecimal("1381.93")));
        assertEquals(0, saved.get().marketValue().compareTo(new BigDecimal("77850.00")));
        assertEquals(SNAPSHOT_DAY, saved.get().snapshotDate());
    }

    @Test
    void importPositions_differentDate_doesNotWrite() {
        // 文件日 ≠ 快照日 → 「当日」不是同一天，写进去就是混日期（本批要根除的病）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository acc = accountRepo(snapshot(WRONG_VALUE, SNAPSHOT_DAY), saved);
        TradingAppService service = service(repo, acc, mock(MarketDataSource.class));

        service.importPositions(USER, List.of(item("600206", 900, "46.012")), true,
                LocalDate.of(2026, 9, 9), BROKER_TODAY_PNL);

        verify(acc, never()).update(anyString(), any());
        assertNull(saved.get(), "日期不一致时不得写账户");
    }

    @Test
    void importPositions_nullBrokerTodayPnl_keepsOldValue() {
        // 文件没这一列（老版本导出/别的券商）→ 保留旧值，绝不落零（P2-交易37 约定）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository acc = accountRepo(snapshot(new BigDecimal("123.00"), SNAPSHOT_DAY), saved);
        TradingAppService service = service(repo, acc, mock(MarketDataSource.class));

        service.importPositions(USER, List.of(item("600206", 900, "46.012")), true, SNAPSHOT_DAY, null);

        verify(acc, never()).update(anyString(), any());
        assertNull(saved.get(), "缺列 → 账户原值保留");
    }

    @Test
    void importPositions_noSnapshot_doesNotInitialize() {
        // 没导过资金股份（无账户快照）→ 不凭空初始化账户
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository acc = accountRepo(null, saved);
        TradingAppService service = service(repo, acc, mock(MarketDataSource.class));

        service.importPositions(USER, List.of(item("600206", 900, "46.012")), true,
                SNAPSHOT_DAY, BROKER_TODAY_PNL);

        verify(acc, never()).update(anyString(), any());
        assertNull(saved.get());
    }

    @Test
    void importPositions_sameValue_doesNotRewrite() {
        // 券商值与账户一致 → 不写（避免无意义写盘）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository acc = accountRepo(snapshot(BROKER_TODAY_PNL, SNAPSHOT_DAY), saved);
        TradingAppService service = service(repo, acc, mock(MarketDataSource.class));

        service.importPositions(USER, List.of(item("600206", 900, "46.012")), true,
                SNAPSHOT_DAY, BROKER_TODAY_PNL);

        verify(acc, never()).update(anyString(), any());
    }

    // ── ② 非交易日不重算（事故根因）──

    @Test
    void isTradingDayStrict_excludesWeekend() {
        // 09-11 周五 = 交易日；09-12 周六 / 09-13 周日 = 不是。
        // 事故根因：refreshTodayPnl 复用了「只查节假日表」的 isTradingDay（它的调用前提是
        // 「周末由 cron 排除」），于是周六被当成交易日。非 cron 路径必须用自证版。
        assertTrue(TradingSessionPushService.isTradingDayStrict(LocalDate.of(2026, 9, 11)), "周五应为交易日");
        assertFalse(TradingSessionPushService.isTradingDayStrict(LocalDate.of(2026, 9, 12)), "周六不是交易日");
        assertFalse(TradingSessionPushService.isTradingDayStrict(LocalDate.of(2026, 9, 13)), "周日不是交易日");
        // 两者对工作日的判断必须一致（避免「两个日历」打架）
        for (LocalDate d = LocalDate.of(2026, 9, 7); d.isBefore(LocalDate.of(2026, 9, 14)); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                assertEquals(TradingSessionPushService.isTradingDay(d),
                        TradingSessionPushService.isTradingDayStrict(d),
                        "工作日上两个判定应一致: " + d);
            }
        }
    }

    @Test
    void refreshTodayPnl_nonTradingDay_doesNotRecompute() {
        // 事故现场：09-12（周六）11:00 导入历史成交 → 触发重算 → −2837 挂了两天
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository acc = accountRepo(snapshot(WRONG_VALUE, SNAPSHOT_DAY), saved);
        TradingAppService service = service(repo, acc, mock(MarketDataSource.class));

        service.refreshTodayPnl(USER, LocalDate.of(2026, 9, 12));

        verify(acc, never()).update(anyString(), any());
        assertNull(saved.get(), "周六不得重算当日盈亏");
    }

    // ── ③ 有实质未计入时不覆盖（P2-交易46）──

    @Test
    void refreshTodayPnl_actionableNotes_doesNotOverwrite() {
        // 缺昨收 → 该票浮动未计入 → 算出的值偏小；写回去比保留旧值更糟（它看起来像真的）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(new Position("600206", "有研新材", 900,
                new BigDecimal("46.012"), new BigDecimal("45.55"), LocalDateTime.now(),
                LocalDate.of(2026, 9, 9), null, null, null)));
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository acc = accountRepo(snapshot(BROKER_TODAY_PNL, SNAPSHOT_DAY), saved);
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600206", new MarketData("600206", "有研新材",
                new BigDecimal("45.55"), null, null, null, null, null, 0)));
        TradingAppService service = service(repo, acc, market);

        service.refreshTodayPnl(USER, LocalDate.of(2026, 9, 11));

        verify(acc, never()).update(anyString(), any());
        assertNull(saved.get(), "有实质未计入 → 保留原值，不写偏小的假数");
    }

    @Test
    void refreshTodayPnl_cleanQuotes_writesComputedValue() {
        // 行情齐备的纯持有日（notes 只有「今日无成交记录」，不算实质缺失）→ 正常重算写回
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(new Position("600206", "有研新材", 900,
                new BigDecimal("46.012"), new BigDecimal("45.55"), LocalDateTime.now(),
                LocalDate.of(2026, 9, 9), null, null, null)));
        AtomicReference<AccountSnapshot> saved = new AtomicReference<>();
        AccountSnapshotRepository acc = accountRepo(snapshot(WRONG_VALUE, SNAPSHOT_DAY), saved);
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of("600206", new MarketData("600206", "有研新材",
                new BigDecimal("45.55"), new BigDecimal("46.79"), null, null, null,
                new BigDecimal("-2.65"), 0)));
        TradingAppService service = service(repo, acc, market);

        service.refreshTodayPnl(USER, LocalDate.of(2026, 9, 11));

        // (45.55 − 46.79) × 900 = −1116.00 —— 与券商该行「当日盈亏」一致
        assertEquals(0, saved.get().todayPnl().compareTo(new BigDecimal("-1116.00")),
                "行情齐备时按口径①重算，且与券商同行口径一致");
    }
}
