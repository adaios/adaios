package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.BuyPointDetector;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * WatchlistBuyPointService — 自选股买点判定（C2，2026-08-16）。
 * <p>
 * 对自选股拉 K 线 → BuyPointDetector 判定 → 命中返回（B1/B2 信号）。
 * 定时收盘后判定 + 推送「到买点了」；web 自选 Tab 显示信号。
 * 判定是提示不是指令。
 * P2-交易2（2026-08-17）：并发拉 K 线 + 按标的异常隔离（单只失败不中断整批，B54）。
 * <p>
 * 第三阶段（用户规则层）：买点参数（回调/缩量/KDJ/放量/窗口）从
 * {@code data/{userId}/trading/rules.yaml} 读取——通用信号原语内建，B1/B2 命名由用户规则包定义。
 */
@Service
public class WatchlistBuyPointService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistBuyPointService.class);

    private final KlineService klineService;
    private final TradingRuleSettingsRepository settingsRepository;
    private final ExecutorService klinePool = Executors.newFixedThreadPool(8);

    public WatchlistBuyPointService(KlineService klineService,
                                    TradingRuleSettingsRepository settingsRepository) {
        this.klineService = klineService;
        this.settingsRepository = settingsRepository;
    }

    /** P2-交易2：应用关闭时优雅关闭线程池。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        klinePool.shutdown();
        try {
            if (!klinePool.awaitTermination(5, TimeUnit.SECONDS)) {
                klinePool.shutdownNow();
            }
        } catch (InterruptedException e) {
            klinePool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 单只自选买点判定结果。 */
    public record WatchBuyPoint(String symbol, String name, String buyPoint,
                                double score, List<String> signals) {}

    /** 批量判定自选股买点（按用户规则参数；无规则 → 默认建议值）。 */
    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist, String userId) {
        return scanWatchlist(watchlist, detectorFor(userId));
    }

    /** 批量判定自选股买点（默认参数，测试/降级用）。 */
    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist) {
        return scanWatchlist(watchlist, detectorFor(null));
    }

    /** 按用户规则构造买点判定器（无规则/损坏 → 默认参数）。 */
    private BuyPointDetector detectorFor(String userId) {
        TradingRuleSettings s = userId != null
                ? settingsRepository.findByUser(userId) : TradingRuleSettings.defaults();
        return new BuyPointDetector(
                s.buyPullbackPct(), s.buyShrinkRatio(), s.buyKdjLow(),
                s.buyVolumeSurge(), s.buyPriorHighDays());
    }

    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist, BuyPointDetector detector) {
        List<WatchBuyPoint> hits = new ArrayList<>();
        List<Future<WatchBuyPoint>> futures = new ArrayList<>();
        for (WatchlistItem item : watchlist) {
            futures.add(klinePool.submit(() -> {
                // P2-交易2：按标的异常隔离——单只失败只跳过该只，不中断整批（B54）
                try {
                    List<Candle> candles = klineService.kline(item.symbol(), 60);
                    BuyPointDetector.BuyPointResult result = detector.detect(candles);
                    if (result.hit()) {
                        return new WatchBuyPoint(item.symbol(), item.name(),
                                result.buyPoint(), result.score(), result.signals());
                    }
                    return null;
                } catch (Exception e) {
                    log.warn("自选买点判定失败 | symbol={} | {}", item.symbol(), e.getMessage());
                    return null;
                }
            }));
        }
        for (Future<WatchBuyPoint> f : futures) {
            try {
                WatchBuyPoint hit = f.get(30, TimeUnit.SECONDS);
                if (hit != null) hits.add(hit);
            } catch (Exception e) {
                log.warn("自选买点扫描单只超时 | {}", e.getMessage());
            }
        }
        log.info("自选买点扫描 | {} 只 → {} 命中", watchlist.size(), hits.size());
        return hits;
    }
}
