package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradingCaseFileRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CaseVerifyBackfillSchedulerTest — 后验回填（2026-08-31 方案第 3 层）。
 * <p>
 * 验证：verify 缺失 → 重拉 K 线回填落盘；窗口仍不足 → 跳过（不落 null 覆盖）；
 * 清单顺带重建（类型补标后 index 摘要同步）。
 */
class CaseVerifyBackfillSchedulerTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradingCaseRepository caseRepository = new TradingCaseFileRepository(storage);
    private final KlineService klineService = mock(KlineService.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PluginService pluginService = mock(PluginService.class);

    private CaseVerifyBackfillScheduler scheduler() {
        return new CaseVerifyBackfillScheduler(caseRepository, klineService, accountRepository, pluginService);
    }

    /** 构造 buyDate 后 N 根日 K（连续收盘价，用于 verify 计算）。 */
    private List<Candle> candlesFrom(LocalDate buyDate, int after, double startClose) {
        List<Candle> list = new ArrayList<>();
        LocalDate d = buyDate.minusDays(3);
        // 买点前 3 根
        for (int i = 0; i < 3; i++) {
            list.add(new Candle(d, 10, 10.5, 9.8, 10, 1000));
            d = d.plusDays(1);
        }
        // 买点日
        list.add(new Candle(buyDate, 10, 10.5, 9.8, startClose, 1000));
        // 买点后 after 根（逐日 +3% 上涨）
        d = buyDate.plusDays(1);
        for (int i = 0; i < after; i++) {
            double close = startClose * Math.pow(1.03, i + 1);
            list.add(new Candle(d, close * 0.99, close * 1.02, close * 0.98, close, 1000));
            d = d.plusDays(1);
        }
        return list;
    }

    private CaseRecord caseWith(String id, String type, LocalDate buyDate,
                                CaseRecord.CaseVerify verify) {
        return new CaseRecord(id, "000725", "京东方A", buyDate, type,
                null, List.of(), LocalDateTime.now(), new CaseRecord.CaseWindow(60, 30),
                new CaseRecord.CaseFeatures(9.0, 0.9, 3.0, false, -0.2, false,
                        "above_all", 4.5, "near", true, 4, false),
                verify, CaseRecord.CaseAiInsight.empty());
    }

    @Test
    void backfill_fillsMissingVerify() {
        // 标注时 +5d/+10d 缺失（窗口不足），回填时 K 线已够 15 根 → 回填
        CaseRecord c = caseWith("2026-08-01_000725", "B1", LocalDate.of(2026, 8, 1),
                new CaseRecord.CaseVerify(null, null, -0.5, false));
        caseRepository.save("adai", c);

        when(accountRepository.findAll()).thenReturn(List.of(
                new Account("adai", "user", true, LocalDate.now(), List.of("trading"))));
        when(pluginService.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(klineService.klineRange(anyString(),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(candlesFrom(LocalDate.of(2026, 8, 1), 15, 10.0));

        scheduler().backfill();

        CaseRecord updated = caseRepository.findById("adai", "2026-08-01_000725").orElseThrow();
        assertNotNull(updated.verify().plus5dReturnPct(), "+5d 应已回填");
        assertNotNull(updated.verify().plus10dReturnPct(), "+10d 应已回填");
        assertTrue(updated.verify().plus5dReturnPct() > 0, "上涨序列 +5d 应为正");
    }

    @Test
    void backfill_windowStillShort_skips() {
        // 买点后只有 3 根 K 线（+5d 仍不足）→ 不覆盖（verify 保持原样）
        CaseRecord c = caseWith("2026-08-20_000725", "B1", LocalDate.of(2026, 8, 20),
                new CaseRecord.CaseVerify(null, null, -0.5, false));
        caseRepository.save("adai", c);

        when(accountRepository.findAll()).thenReturn(List.of(
                new Account("adai", "user", true, LocalDate.now(), List.of("trading"))));
        when(pluginService.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(klineService.klineRange(anyString(),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(candlesFrom(LocalDate.of(2026, 8, 20), 3, 10.0));

        scheduler().backfill();

        CaseRecord updated = caseRepository.findById("adai", "2026-08-20_000725").orElseThrow();
        assertNull(updated.verify().plus5dReturnPct(), "窗口不足不应回填 +5d");
        // 有后续 K 线即可重算最大回撤（上涨序列 → 0.0，覆盖旧的 -0.5 是重算口径的正确行为）
        assertEquals(0.0, updated.verify().maxDrawdownAfterBuyPct(), 0.001);
    }

    @Test
    void backfill_rebuildsIndex_afterTypeBackfill() {
        // 模拟「文件类型已补标但 index 摘要过期」：save 后直接改文件类型，index 摘要未同步
        CaseRecord c = caseWith("2026-08-01_000725", "B1", LocalDate.of(2026, 8, 1),
                new CaseRecord.CaseVerify(null, null, -0.5, false));
        caseRepository.save("adai", c);
        // 直接改文件类型为 B2（绕过 save 的 upsertIndex → 摘要不同步）
        CaseRecord relabeled = caseWith("2026-08-01_000725", "B2", LocalDate.of(2026, 8, 1),
                new CaseRecord.CaseVerify(null, null, -0.5, false));
        storage.write("adai", "trading/cases/2026-08-01_000725.json",
                "{\"id\":\"2026-08-01_000725\",\"symbol\":\"000725\",\"name\":\"京东方A\","
                        + "\"buyDate\":\"2026-08-01\",\"buyType\":\"B2\",\"description\":null,"
                        + "\"labels\":[],\"labeledAt\":\"2026-08-31T00:00:00\","
                        + "\"window\":{\"beforeDays\":60,\"afterDays\":30},"
                        + "\"features\":{\"drawdownFromHighPct\":9.0,\"volumeShrinkRatio\":0.9,"
                        + "\"kdjJ\":3.0,\"kdjGoldenCross\":false,\"macdHist\":-0.2,"
                        + "\"macdCrossUp\":false,\"maRelation\":\"above_all\","
                        + "\"distToMa60Pct\":4.5,\"yellowLineState\":\"near\","
                        + "\"whiteAboveYellow\":true,\"sidewaysDays\":4,\"breakoutFromHigh\":false},"
                        + "\"verify\":{\"+5dReturnPct\":null,\"+10dReturnPct\":null,"
                        + "\"maxDrawdownAfterBuyPct\":-0.5,\"stopLossHit\":false},"
                        + "\"aiInsight\":{\"summary\":\"\",\"keyFeatures\":[],"
                        + "\"confidence\":0.0,\"reviewed\":false}}");

        when(accountRepository.findAll()).thenReturn(List.of(
                new Account("adai", "user", true, LocalDate.now(), List.of("trading"))));
        when(pluginService.hasPlugin(anyString(), anyString())).thenReturn(true);
        when(klineService.klineRange(anyString(),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(candlesFrom(LocalDate.of(2026, 8, 1), 15, 10.0));

        scheduler().backfill();

        // index 摘要应同步为 B2（文件真相源）
        String index = storage.read("adai", "trading/cases/_index.json");
        assertNotNull(index);
        assertTrue(index.contains("\"buyType\" : \"B2\""), "index 摘要应重建为文件类型 B2，实际: " + index);
    }
}
