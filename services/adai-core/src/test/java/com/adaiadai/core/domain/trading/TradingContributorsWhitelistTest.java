package com.adaiadai.core.domain.trading;

import com.adaiadai.core.domain.trading.market.MarketDataSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-06 §8.2 白名单断言（design-final）：
 * 构造持仓（含量 / 成本 / 市值 / 现金）→ 断言注入文本**不含**该批数字、**含**代码与盈亏%。
 *
 * <p>白名单①：{@link TradingContextContributor} 只出结构 + 比例 + 现价（标题「持仓结构（不含规模）」）；
 * 白名单②：{@link MarketContextContributor} 只出代码/名称/现价/盈亏%（删汇总行）。
 *
 * <p>前身 `TradingContributorsCashSourceTest`（P2-交易35 治本）断言的「现金余额注入 S5 真源」，
 * 已被 §8.2 白名单否决——现金余额属「规模」不再进 AI 上下文；本类接管并改造其使命。
 */
class TradingContributorsWhitelistTest {

    private static final String USER = "default";

    /** 成本 10.0 / 现价 10.5（mock 行情为空时回落持仓现价）。 */
    private Position pos(String symbol, int qty) {
        return new Position(symbol, symbol + "名", qty, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now());
    }

    private MarketDataSource emptyMarket() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(anyList())).thenReturn(Map.of());
        when(market.indices()).thenReturn(Map.of());
        return market;
    }

    private AccountSnapshotRepository accountRepo(BigDecimal cash) {
        AccountSnapshotRepository acc = mock(AccountSnapshotRepository.class);
        when(acc.findLatest(any())).thenReturn(Optional.of(new AccountSnapshot(
                new BigDecimal("81357.16"), cash, cash, cash,
                new BigDecimal("79079.00"), new BigDecimal("16423.25"),
                BigDecimal.ZERO, new BigDecimal("130000"), null)));
        return acc;
    }

    // ── 白名单①：TradingContextContributor ──

    @Test
    void tradingContext_whitelist_structureProportionPriceOnly() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100), pos("600001", 300)));
        TradingContextContributor contributor = new TradingContextContributor(repo, emptyMarket(),
                accountRepo(new BigDecimal("2278.16")));

        String ctx = contributor.globalContext(USER);
        // 出：结构 + 标的 + 现价 + 盈亏% + 仓位占比
        assertTrue(ctx.contains("## 持仓结构（不含规模）"), "标题应为「持仓结构（不含规模）」: " + ctx);
        assertTrue(ctx.contains("当前持有 2 个仓位"), ctx);
        assertTrue(ctx.contains("600000"), ctx);
        assertTrue(ctx.contains("现价10.5"), ctx);
        assertTrue(ctx.contains("+5.00%"), "盈亏% 应出: " + ctx);
        assertTrue(ctx.contains("仓位占比 25.0%"), "仓位占比（比例）应出: " + ctx);
        // 不出规模：股数 / 成本价 / 市值 / 浮动盈亏金额 / 现金余额
        assertFalse(ctx.contains("成本"), "成本价属规模，不出: " + ctx);
        assertFalse(ctx.contains("股"), "股数属规模，不出: " + ctx);
        assertFalse(ctx.contains("100"), "股数属规模，不出: " + ctx);
        assertFalse(ctx.contains("300"), "股数属规模，不出: " + ctx);
        assertFalse(ctx.contains("2278.16"), "现金余额属规模，不出: " + ctx);
        assertFalse(ctx.contains("81357.16"), "总资产属规模，不出: " + ctx);
        assertFalse(ctx.contains("总市值"), "汇总行应删除: " + ctx);
        assertFalse(ctx.contains("浮动盈亏"), "汇总行应删除: " + ctx);
        assertFalse(ctx.contains("现金余额"), "汇总行应删除: " + ctx);
    }

    @Test
    void tradingContext_whitelist_noPositions_returnsEmpty() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        TradingContextContributor contributor = new TradingContextContributor(repo, emptyMarket(),
                accountRepo(new BigDecimal("1")));
        assertTrue(contributor.globalContext(USER).isEmpty());
    }

    // ── 白名单②：MarketContextContributor ──

    @Test
    void marketContextEnrich_whitelist_fourColumnsNoSummary() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100), pos("600001", 300)));
        MarketContextContributor contributor = new MarketContextContributor(emptyMarket(), repo,
                accountRepo(new BigDecimal("2278.16")));

        String ctx = contributor.enrich(USER, "ref", null);
        assertTrue(ctx.contains("## 大盘与持仓行情"), ctx);
        assertTrue(ctx.contains("| 代码 | 名称 | 现价 | 盈亏% |"), "表头只出 4 列: " + ctx);
        assertTrue(ctx.contains("600000"), ctx);
        assertTrue(ctx.contains("+5.00%"), "盈亏% 应出: " + ctx);
        // 旧列头（数量/成本价/市值/盈亏金额）与汇总行不再出现
        assertFalse(ctx.contains("数量"), "数量列不出: " + ctx);
        assertFalse(ctx.contains("成本价"), "成本价列不出: " + ctx);
        assertFalse(ctx.contains("市值"), "市值列/汇总不出: " + ctx);
        assertFalse(ctx.contains("汇总"), "汇总行应删除: " + ctx);
        assertFalse(ctx.contains("现金余额"), "现金余额不出: " + ctx);
        assertFalse(ctx.contains("2278.16"), "现金余额属规模，不出: " + ctx);
    }

    @Test
    void marketContextGlobalContext_withoutPositions_noScaleNoCrash() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        MarketContextContributor contributor = new MarketContextContributor(emptyMarket(), repo,
                accountRepo(new BigDecimal("2278.16")));
        String ctx = contributor.globalContext(USER);
        assertTrue(ctx.contains("## 大盘与持仓行情"), ctx);
        assertTrue(ctx.contains("当前无持仓记录"), ctx);
        assertFalse(ctx.contains("现金余额"), "现金余额不出: " + ctx);
        assertFalse(ctx.contains("2278.16"), "现金余额属规模，不出: " + ctx);
    }
}
