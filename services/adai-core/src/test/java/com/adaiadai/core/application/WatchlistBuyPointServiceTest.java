package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradingCaseFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WatchlistBuyPointServiceTest — 环 4 二期：扫描附案例相似度（开关默认关，向前兼容）。
 * <p>
 * 验证：开关关 → caseMatches 空（行为与现状一致）；开关开 + 案例库有料 →
 * 规则命中项附相似案例 Top 3；规则未命中但案例相似 → 返回 case 类型参考。
 */
class WatchlistBuyPointServiceTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradingCaseRepository caseRepository = new TradingCaseFileRepository(storage);
    private final KlineService klineService = mock(KlineService.class);
    private final TradingRuleSettingsRepository settingsRepository =
            new TradingRuleSettingsRepository(storage);

    private List<Candle> buildCandles() {
        List<Candle> list = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < 60; i++) {
            // 涨后回撤横盘（触发 B1 回调缩量形态）
            double close = i < 30 ? 10 + i * 0.05 : 11.5 - (i - 30) * 0.03;
            list.add(new Candle(start.plusDays(i), close * 0.99, close * 1.02, close * 0.98,
                    close, i >= 55 ? 300 : 1000));
        }
        return list;
    }

    private CaseRecord sampleCase() {
        return new CaseRecord(
                "2026-08-03_000725", "000725", "京东方A", LocalDate.of(2026, 8, 3), "B1",
                null, List.of(), LocalDateTime.now(), new CaseRecord.CaseWindow(60, 30),
                new CaseRecord.CaseFeatures(52.3, 0.62, 8.4, true, -0.31, true,
                        "close_above_ma20_below_ma60", 1.8, "near", false, 5, false),
                new CaseRecord.CaseVerify(18.2, 24.5, -2.1, false),
                CaseRecord.CaseAiInsight.empty());
    }

    private WatchlistBuyPointService service(boolean scanMatchEnabled) {
        return new WatchlistBuyPointService(klineService, settingsRepository, caseRepository, scanMatchEnabled);
    }

    @Test
    void scan_switchOff_caseMatchesEmpty_behaviorUnchanged() {
        when(klineService.kline(anyString(), anyInt())).thenReturn(buildCandles());
        WatchlistBuyPointService svc = service(false);
        List<WatchlistBuyPointService.WatchBuyPoint> hits = svc.scanWatchlist(
                List.of(new WatchlistItem("000725", "京东方A", "电子", "电子元件", 1, 1, 1, "回调", null)), "adai");
        assertTrue(hits.stream().allMatch(h -> h.caseMatches().isEmpty()),
                "开关关 → caseMatches 恒空（行为与现状一致）");
    }

    @Test
    void scan_switchOn_hitIncludesCaseMatches() {
        caseRepository.save("adai", sampleCase());
        when(klineService.kline(anyString(), anyInt())).thenReturn(buildCandles());
        WatchlistBuyPointService svc = service(true);
        List<WatchlistBuyPointService.WatchBuyPoint> hits = svc.scanWatchlist(
                List.of(new WatchlistItem("000725", "京东方A", "电子", "电子元件", 1, 1, 1, "回调", null)), "adai");
        assertEquals(1, hits.size());
        WatchlistBuyPointService.WatchBuyPoint h = hits.get(0);
        assertTrue(!h.caseMatches().isEmpty(), "开关开 → 应附案例相似度参考");
        assertEquals("2026-08-03_000725", h.caseMatches().get(0).caseId());
        assertTrue(h.caseMatches().get(0).similarityPercent() > 50,
                "同形态相似度应较高，实际 " + h.caseMatches().get(0).similarityPercent());
    }

    @Test
    void scan_switchOn_emptyLibrary_noCaseMatches() {
        when(klineService.kline(anyString(), anyInt())).thenReturn(buildCandles());
        WatchlistBuyPointService svc = service(true);
        List<WatchlistBuyPointService.WatchBuyPoint> hits = svc.scanWatchlist(
                List.of(new WatchlistItem("000725", "京东方A", "电子", "电子元件", 1, 1, 1, "回调", null)), "adai");
        assertTrue(hits.stream().allMatch(h -> h.caseMatches().isEmpty()),
                "案例库空 → 不附参考（静默降级）");
    }
}
