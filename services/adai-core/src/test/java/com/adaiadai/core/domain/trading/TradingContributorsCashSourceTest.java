package com.adaiadai.core.domain.trading;

import com.adaiadai.core.domain.trading.market.MarketDataSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-交易35 治本（2026-09-09）：TradingContextContributor / MarketContextContributor 的
 * 「现金余额」改读 AccountSnapshot.cash（S5 真源），不再读 positions.md cashBalance 展示行——
 * 双源不一致（2026-09-07 实测 292.88 vs 1488.75 并存）会让 AI 上下文现金口径 ≠ 账户卡。
 */
class TradingContributorsCashSourceTest {

    private static final String USER = "default";

    private Position pos(String symbol, int qty) {
        return new Position(symbol, symbol + "名", qty, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now());
    }

    private AccountSnapshotRepository accountRepo(BigDecimal cash) {
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(Optional.of(new AccountSnapshot(
                new BigDecimal("81357.16"), cash, cash, cash,
                new BigDecimal("79079.00"), new BigDecimal("16423.25"),
                BigDecimal.ZERO, new BigDecimal("130000"), null)));
        return acc;
    }

    private MarketDataSource emptyMarket() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(anyList())).thenReturn(Map.of());
        when(market.indices()).thenReturn(Map.of());
        return market;
    }

    @Test
    void tradingContextGlobalContext_usesAccountSnapshotCash_notPositionsLine() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        TradingContextContributor contributor = new TradingContextContributor(repo, emptyMarket(),
                accountRepo(new BigDecimal("2278.16")));

        String ctx = contributor.globalContext(USER);
        assertTrue(ctx.contains("现金余额 2278.16"), "现金应来自 account.json（S5 真源），实际: " + ctx);
        // 持仓非空才会走到现金拼接
        assertTrue(ctx.contains("600000"));
    }

    @Test
    void tradingContextGlobalContext_noPositions_returnsEmpty() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        TradingContextContributor contributor = new TradingContextContributor(repo, emptyMarket(),
                accountRepo(new BigDecimal("1")));
        assertTrue(contributor.globalContext(USER).isEmpty());
    }

    @Test
    void marketContextEnrich_usesAccountSnapshotCash() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        MarketContextContributor contributor = new MarketContextContributor(emptyMarket(), repo,
                accountRepo(new BigDecimal("2278.16")));

        String ctx = contributor.enrich(USER, "ref", null);
        assertTrue(ctx.contains("现金余额=2278.16"), "现金应来自 account.json（S5 真源），实际: " + ctx);
    }

    @Test
    void marketContextGlobalContext_withoutPositions_showsNoCashAndNoCrash() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        MarketContextContributor contributor = new MarketContextContributor(emptyMarket(), repo,
                accountRepo(new BigDecimal("2278.16")));
        // globalContext 无持仓分支只输出无持仓提示，不应 NPE
        assertTrue(contributor.globalContext(USER).contains("当前无持仓记录"));
    }
}
