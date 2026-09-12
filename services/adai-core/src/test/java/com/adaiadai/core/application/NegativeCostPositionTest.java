package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 负成本持仓批（2026-09-13）——用户实测事故回归。
 *
 * <p>现场：用户导入通达信「持仓股」快照，文件里 3 只有持仓（另有 1 行 0 股残留），
 * 结果只进来 2 只，且界面无任何提示。被丢的是 <b>600601 方正科技 100 股 / 成本 −5.078</b>：
 * 反复做 T / 分红把持仓成本摊到 0 以下是<b>真实且合法</b>的，券商就是这么记的
 * （该行盈亏 1990.77 / 市值 1483.00 = 134%，正说明成本在 0 以下）。
 *
 * <p>本文件守住后端侧的两件事：
 * <ol>
 *   <li><b>导入不能因负成本拒整批</b>——原实现 {@code avgCost.signum() <= 0} 抛 TradingException，
 *       只修前端的话行为会从「静默少一只」恶化成「整批 400、一只都进不去」；</li>
 *   <li><b>百分比不能撒谎</b>——负/零成本下「(现价−成本)/成本」语义翻转（实测算出 −392%，
 *       券商口径 +134%），必须给「—」（JSON null），而不是 0%（会被读成「不赚不亏」）。</li>
 * </ol>
 */
class NegativeCostPositionTest {

    private static final String USER = "default";
    /** 600601 现场数据：成本 −5.078 / 现价 14.83 / 100 股。 */
    private static final BigDecimal REAL_COST = new BigDecimal("-5.078");
    private static final BigDecimal REAL_PRICE = new BigDecimal("14.83");

    private Position pos(BigDecimal avgCost, BigDecimal price, int qty) {
        return new Position("600601", "方正科技", qty, avgCost, price, LocalDateTime.now());
    }

    // ── ① 百分比：负/零成本 → null（不撒谎） ──

    @Test
    void pnlPercent_negativeCost_returnsNullNotMisleadingNumber() {
        assertNull(pos(REAL_COST, REAL_PRICE, 100).pnlPercent(),
                "负成本下百分比语义翻转（原实现算出 −392%），必须给 null 让前端显示「—」");
    }

    @Test
    void pnlPercent_zeroCost_returnsNull() {
        assertNull(pos(BigDecimal.ZERO, REAL_PRICE, 100).pnlPercent(),
                "零成本是分母为 0，同样无意义 → null（原实现返回 0 = 谎报「不赚不亏」）");
    }

    @Test
    void pnlPercent_positiveCost_unchanged() {
        Position p = pos(new BigDecimal("25.30"), new BigDecimal("26.60"), 200);
        // 既有口径：先 4 位取整再 ×100 → 1.30/25.30 = 0.0514 → 5.14%（与 PositionSerializationTest 同值）
        assertEquals(0, p.pnlPercent().compareTo(new BigDecimal("5.14")),
                "正成本行为必须不变（回归）");
    }

    /** 盈亏**金额**不受影响，且与券商口径一致（成本额为负 → 浮盈更高）。 */
    @Test
    void pnl_amount_matchesBrokerForNegativeCost() {
        Position p = pos(REAL_COST, REAL_PRICE, 100);
        // 市值 1483.00 − 成本额(−507.80) = 1990.80（用户文件里券商显示 1990.77，差值为成本 5 位精度）
        assertEquals(0, p.pnl().compareTo(new BigDecimal("1990.80")),
                "负成本下盈亏金额照常算（用户文件券商值 1990.77）");
        assertEquals(0, p.marketValue().compareTo(new BigDecimal("1483.00")), "市值不受成本符号影响");
    }

    @Test
    void pnlPercent_serializesAsNull_notZero() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = mapper.writeValueAsString(pos(REAL_COST, REAL_PRICE, 100));

        assertEquals(true, json.contains("\"pnlPercent\":null"),
                "负成本应序列化成 null（前端据此显示「—」），实际: " + json);
    }

    /** 计算止损本就不对非正成本推导（既有守卫回归）：负成本算出负止损价会毁掉破止损判定。 */
    @Test
    void computedStopLoss_notDerivedFromNegativeCost() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of(pos(REAL_COST, REAL_PRICE, 100)));
        TradingAppService service = service(repo);

        List<Position> result = service.getPositions(USER);

        assertEquals(1, result.size());
        assertNull(result.get(0).computedStopLossPrice(),
                "负成本不得推导抄底止损价（否则止损价变负，破止损判定失去意义）");
    }

    // ── ② 导入：负成本放行（不再整批 400），缺失仍拒 ──

    @Test
    void importPositions_negativeCost_acceptedNotRejected() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo);

        TradingAppService.PositionImportResult result = service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("600206", "有研新材", 900, new BigDecimal("46.012"), null, null, null, null),
                new TradingAppService.PositionImportItem("002428", "云南锗业", 400, new BigDecimal("53.765"), null, null, null, null),
                new TradingAppService.PositionImportItem("600601", "方正科技", 100, REAL_COST, null, null, null, null)
        ), true, null);

        assertEquals(3, result.imported(),
                "现场 3 只有持仓必须全部导入（原实现因 −5.078 抛异常 → 整批失败）");
        verify(repo, times(1)).saveAll(anyString(), any());
    }

    @Test
    void importPositions_zeroCost_accepted() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo);

        TradingAppService.PositionImportResult result = service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("600601", "方正科技", 100, BigDecimal.ZERO, null, null, null, null)
        ), true, null);

        assertEquals(1, result.imported(), "成本恰为 0 也放行（百分比语义由 pnlPercent 兜为「—」）");
    }

    @Test
    void importPositions_nullCost_stillRejected() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo);

        TradingException ex = assertThrows(TradingException.class, () -> service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("600601", "方正科技", 100, null, null, null, null, null)
        ), true, null));
        assertEquals(true, ex.getMessage().contains("成本价缺失"),
                "缺失（真脏数据）仍必须拒：原校验的存在意义是防下游 NPE 500。实际: " + ex.getMessage());
    }

    @Test
    void importPositions_zeroQuantity_stillRejected() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAppService service = service(repo);

        assertEquals(true, assertThrows(TradingException.class, () -> service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("603113", "金能科技", 0, new BigDecimal("5.569"), null, null, null, null)
        ), true, null)).getMessage().contains("数量需 > 0"),
                "0 股不是持仓（券商文件里的残留行），必须拒（前端应先行过滤为「已清空跳过」）");
    }

    /** 服务构造照 TradingAnchorGuardTest 的 11 参 mock 模板。 */
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
