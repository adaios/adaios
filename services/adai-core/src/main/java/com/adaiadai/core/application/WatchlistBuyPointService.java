package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.BuyPointDetector;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.market.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * WatchlistBuyPointService — 自选股买点判定（C2，2026-08-16）。
 * <p>
 * 对自选股拉 K 线 → BuyPointDetector 判定 → 命中返回（B1/B2 信号）。
 * 定时收盘后判定 + 推送「到买点了」；web 自选 Tab 显示信号。
 * 判定是提示不是指令。
 */
@Service
public class WatchlistBuyPointService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistBuyPointService.class);

    private final KlineService klineService;

    public WatchlistBuyPointService(KlineService klineService) {
        this.klineService = klineService;
    }

    /** 单只自选买点判定结果。 */
    public record WatchBuyPoint(String symbol, String name, String buyPoint,
                                double score, List<String> signals) {}

    /** 批量判定自选股买点（参数默认建议值，可配）。 */
    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist) {
        return scanWatchlist(watchlist, new BuyPointDetector(0.5, 0.7, 20, 1.5, 20));
    }

    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist, BuyPointDetector detector) {
        List<WatchBuyPoint> hits = new ArrayList<>();
        for (WatchlistItem item : watchlist) {
            List<Candle> candles = klineService.kline(item.symbol(), 60);
            BuyPointDetector.BuyPointResult result = detector.detect(candles);
            if (result.hit()) {
                hits.add(new WatchBuyPoint(item.symbol(), item.name(),
                        result.buyPoint(), result.score(), result.signals()));
            }
        }
        log.info("自选买点扫描 | {} 只 → {} 命中", watchlist.size(), hits.size());
        return hits;
    }
}
