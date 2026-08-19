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
 * <p>
 * P2-1（2026-08-18 生产）：东财被限（连接层封禁，`header parser received no bytes`）时
 * 每次查询都先打东财再降级 → 浪费请求 + 日志噪音（单日 1154 次 WARN）。加熔断：
 * 连续失败 {@link #TRIP_THRESHOLD} 次 → 熔断 {@link #COOLDOWN_MS} 内直接走腾讯（不再打东财），
 * 冷却后半开探测一次（东财恢复则重置计数）。
 */
@Service
public class KlineService {

    private static final Logger log = LoggerFactory.getLogger(KlineService.class);

    /** 连续失败多少次触发熔断（生产东财连接层封禁，2 次足以识别）。 */
    static final int TRIP_THRESHOLD = 3;
    /** 熔断冷却时长：5 分钟（行情日频，收盘后无实时需求）。 */
    static final long COOLDOWN_MS = 5 * 60_000L;

    private final KlineSource primary;
    private final MarketDataSource fallback;

    /** 熔断状态（volatile 多线程读；买点扫描并发 16 线程）。 */
    private volatile int consecutiveFailures;
    private volatile long circuitOpenUntil;

    public KlineService(KlineSource primary, MarketDataSource fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /** 查询日 K：东财主源 → 腾讯兜底。熔断开启时直接走腾讯。 */
    public List<Candle> kline(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        if (circuitOpen()) {
            List<Candle> tencent = fallback.kline(symbol, limit);
            return tencent != null ? tencent : List.of();
        }
        List<Candle> candles = primary.kline(symbol, limit);
        if (!candles.isEmpty()) {
            consecutiveFailures = 0;
            return candles;
        }
        int failures = ++consecutiveFailures;
        if (failures >= TRIP_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + COOLDOWN_MS;
            log.warn("东财 K线连续失败 {} 次，熔断 {} 分钟，直接走腾讯兜底", failures, COOLDOWN_MS / 60_000);
        } else {
            log.warn("东财 K线空，降级腾讯 | symbol={} | 连续失败 {} 次", symbol, failures);
        }
        List<Candle> tencent = fallback.kline(symbol, limit);
        return tencent != null ? tencent : List.of();
    }

    private boolean circuitOpen() {
        long until = circuitOpenUntil;
        if (until == 0) return false;
        if (System.currentTimeMillis() < until) return true;
        // 冷却结束 → 半开：清零，下一次走主源探测（失败将再次熔断）
        synchronized (this) {
            if (circuitOpenUntil == until) {
                circuitOpenUntil = 0;
                consecutiveFailures = 0;
                log.info("东财熔断冷却结束，恢复主源探测");
            }
        }
        return false;
    }

    /** 测试用：查询当前熔断状态。 */
    boolean isCircuitOpen() {
        return circuitOpenUntil != 0 && System.currentTimeMillis() < circuitOpenUntil;
    }
}
