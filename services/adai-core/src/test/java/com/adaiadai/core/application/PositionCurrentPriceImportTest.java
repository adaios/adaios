package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 持仓导入保留券商「现价」（P2-交易65，2026-09-23 生产对账发现）。
 *
 * <p>现场：券商「持仓股」导出<b>带「现价」列</b>（生产 9-20 文件：002428 现价 93.50 / 成本 41.58），
 * 但 {@code importPositions} 两处构造都把 {@code currentPrice} 写成 {@code avgCost}
 * （入参 {@code PositionImportItem} 当时根本没有现价字段）→ 生产 {@code positions.md} 5 只票的
 * 「现价」全部等于成本价。
 *
 * <p><b>为什么平时看不出来</b>：运行时 {@code getPositions} 用 {@code marketDataSource.quote()}
 * 实时覆盖存储价（实测 API 返回 002428 = 94.74 为真实行情，市值与券商快照分毫不差）。
 * 但行情源一旦不可用（P1-交易62 的 K 线链路整段失效即同族风险），代码回退存储价 →
 * 持仓页把 5 只票显示成「0 盈亏 + 市值退回成本」的<b>假象</b>，而不是空白。
 *
 * <p>本文件守住三条不许回退的行为：
 * <ol>
 *   <li>券商给了现价 → 落库就是券商现价，<b>不是成本价</b>；</li>
 *   <li>文件没这一列（旧导出 / 旧前端不带该字段）→ <b>保留原有存储价</b>，绝不写回成本价；</li>
 *   <li>只有「新持仓且无任何现价来源」才回退成本价（无据可依时的诚实兜底）。</li>
 * </ol>
 */
class PositionCurrentPriceImportTest {

    private static final String USER = "default";
    /** 生产现场：002428 云南锗业 成本 41.580 / 券商现价 93.50 / 300 股。 */
    private static final BigDecimal COST = new BigDecimal("41.580");
    private static final BigDecimal BROKER_PRICE = new BigDecimal("93.50");

    @Test
    void importPositions_withBrokerPrice_storesBrokerPriceNotCost() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo);

        service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("002428", "云南锗业", 300, COST,
                        null, null, null, null, BROKER_PRICE)
        ), true, null);

        Position saved = saved(repo, "002428");
        assertEquals(0, BROKER_PRICE.compareTo(saved.currentPrice()),
                "落库现价必须是券商给的 93.50，而不是被成本价 41.58 顶替；实际: " + saved.currentPrice());
        assertEquals(0, COST.compareTo(saved.avgCost()), "成本价原样保留");
    }

    @Test
    void importPositions_withoutBrokerPrice_keepsExistingStoredPrice() {
        PositionRepository repo = mock(PositionRepository.class);
        // 既有持仓：存储价是上一次真实行情（94.74），成本 41.58
        when(repo.findAll(anyString())).thenReturn(List.of(new Position(
                "002428", "云南锗业", 300, COST, new BigDecimal("94.74"), LocalDateTime.now())));
        TradingAppService service = service(repo);

        // 旧前端 / 无「现价」列的导出 → currentPrice 为 null
        service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("002428", "云南锗业", 300, COST, null, null, null, null)
        ), true, null);

        Position saved = saved(repo, "002428");
        assertEquals(0, new BigDecimal("94.74").compareTo(saved.currentPrice()),
                "没有现价来源时必须保留原有存储价 94.74——写回成本价会让持仓页显示成 0 盈亏（原缺陷）；"
                        + "实际: " + saved.currentPrice());
    }

    @Test
    void importPositions_newHoldingWithoutPrice_fallsBackToCost() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo);

        service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("600206", "有研新材", 600, new BigDecimal("45.306"),
                        null, null, null, null)
        ), true, null);

        Position saved = saved(repo, "600206");
        assertEquals(0, new BigDecimal("45.306").compareTo(saved.currentPrice()),
                "新持仓且无任何现价来源 → 才回退成本价（有据可依的诚实兜底）");
    }

    @Test
    void importPositions_brokerPriceOverwritesStaleStoredPrice() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(new Position(
                "002428", "云南锗业", 300, COST, COST, LocalDateTime.now())));
        TradingAppService service = service(repo);

        service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("002428", "云南锗业", 300, COST,
                        null, null, null, null, BROKER_PRICE)
        ), true, null);

        Position saved = saved(repo, "002428");
        assertEquals(0, BROKER_PRICE.compareTo(saved.currentPrice()),
                "券商现价优先于旧存储价（含历史脏值）——这正是把存量成本价救回来的路径");
    }

    @SuppressWarnings("unchecked")
    private Position saved(PositionRepository repo, String symbol) {
        ArgumentCaptor<List<Position>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(anyString(), captor.capture());
        return captor.getValue().stream()
                .filter(p -> p.symbol().equals(symbol))
                .findFirst()
                .orElseThrow(() -> new AssertionError("落盘持仓里没有 " + symbol));
    }

    private TradingAppService service(PositionRepository repo) {
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        when(ruleRepo.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        return new TradingAppService(repo, mock(RecordRepository.class),
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class), mock(MarketDataSource.class),
                mock(TradingLotService.class), ruleRepo, mock(TradingAnchorRepository.class));
    }
}
