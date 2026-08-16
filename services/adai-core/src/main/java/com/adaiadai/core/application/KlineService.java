package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * KlineService — K 线查询（2026-08-16：主源东方财富，失败降级腾讯）。
 * <p>
 * 供买点判定 / 完美图匹配 / 复盘回顾使用；安全约定：主源失败切兜底，都失败返回空。
 */
@Service
public class KlineService {

    private static final Logger log = LoggerFactory.getLogger(KlineService.class);

    private final KlineSource primary;
    private final MarketDataSource fallback;

    public KlineService(KlineSource primary, MarketDataSource fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /** 查询日 K：东财主源 → 腾讯兜底。 */
    public List<Candle> kline(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        List<Candle> candles = primary.kline(symbol, limit);
        if (!candles.isEmpty()) return candles;
        log.debug("东财 K线空，降级腾讯 | symbol={}", symbol);
        List<Candle> tencent = fallback.kline(symbol, limit);
        return tencent != null ? tencent : List.of();
    }
}
