package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * KlineService — K 线查询（2026-08-23：腾讯主源，东财探测兜底；配置 {@code adai.market.kline-primary} 可切回东财）。
 * <p>
 * 供买点判定 / 完美图匹配 / 复盘回顾使用；安全约定：主源失败切兜底，都失败返回空。
 * <p>
 * 历史：2026-08-16 以东财为主源、腾讯兜底；P2-1（2026-08-18 生产）东财连接层被限（
 * {@code header parser received no bytes}，单日 1154 次 WARN）→ 加熔断。2026-08-23 用户确认
 * 「优先以腾讯」：生产东财被限是常态，腾讯稳定，主源对调后东财只在腾讯失败时探测（熔断逻辑保留）。
 */
@Service
public class KlineService {

    private static final Logger log = LoggerFactory.getLogger(KlineService.class);

    /** 连续失败多少次触发熔断（生产东财连接层封禁，2 次足以识别）。 */
    static final int TRIP_THRESHOLD = 3;
    /** 熔断冷却时长：5 分钟（行情日频，收盘后无实时需求）。 */
    static final long COOLDOWN_MS = 5 * 60_000L;

    private final KlineSource primary;
    private final KlineSource fallback;
    private final KlineSource tdx;
    private final String primaryName;
    private final String fallbackName;

    /** 熔断状态（volatile 多线程读；买点扫描并发 16 线程）。 */
    private volatile int consecutiveFailures;
    private volatile long circuitOpenUntil;

    public KlineService(
            @Value("${adai.market.kline-primary:tencent}") String primaryName,
            @Value("${adai.market.tdx-enabled:true}") boolean tdxEnabled,
            @Qualifier("eastMoneyKlineDataSource") KlineSource eastMoney,
            @Qualifier("tencentMarketDataSource") KlineSource tencent,
            @Qualifier("tdxFileKlineSource") KlineSource tdx) {
        boolean tencentFirst = "tencent".equalsIgnoreCase(primaryName);
        this.primaryName = tencentFirst ? "腾讯" : "东财";
        this.fallbackName = tencentFirst ? "东财" : "腾讯";
        this.primary = tencentFirst ? tencent : eastMoney;
        this.fallback = tencentFirst ? eastMoney : tencent;
        // 2026-08-30：通达信本地数据第一优先（全 A 历史、免风控）——tdx 无数据（未同步/缺标的）不算失败，走网络源
        this.tdx = tdxEnabled ? tdx : null;
        log.info("KlineService 初始化 | 主源={} | 兜底={} | TDX本地={}",
                this.primaryName, this.fallbackName, tdxEnabled ? "启用" : "关闭");
    }

    /** 查询日 K：TDX 本地 → 主源 → 兜底。熔断开启时 TDX → 直接走兜底。 */
    public List<Candle> kline(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        if (tdx != null) {
            List<Candle> local = tdx.kline(symbol, limit);
            if (!local.isEmpty()) return local;
        }
        if (circuitOpen()) {
            List<Candle> fb = fallback.kline(symbol, limit);
            return fb != null ? fb : List.of();
        }
        List<Candle> candles = primary.kline(symbol, limit);
        if (!candles.isEmpty()) {
            consecutiveFailures = 0;
            return candles;
        }
        int failures = ++consecutiveFailures;
        if (failures >= TRIP_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + COOLDOWN_MS;
            log.warn("{} K线连续失败 {} 次，熔断 {} 分钟，直接走{}兜底",
                    primaryName, failures, COOLDOWN_MS / 60_000, fallbackName);
        } else {
            log.warn("{} K线空，降级{} | symbol={} | 连续失败 {} 次",
                    primaryName, fallbackName, symbol, failures);
        }
        List<Candle> fb = fallback.kline(symbol, limit);
        return fb != null ? fb : List.of();
    }

    /** 按日期范围查询（2026-08-30：案例库历史窗口）；TDX 本地 → 主源 → 兜底，熔断同 kline。 */
    public List<Candle> klineRange(String symbol, java.time.LocalDate from, java.time.LocalDate to) {
        if (symbol == null || symbol.isBlank()) return List.of();
        if (tdx != null) {
            List<Candle> local = tdx.klineRange(symbol, from, to);
            if (!local.isEmpty()) return local;
        }
        if (circuitOpen()) {
            List<Candle> fb = fallback.klineRange(symbol, from, to);
            return fb != null ? fb : List.of();
        }
        List<Candle> candles = primary.klineRange(symbol, from, to);
        if (!candles.isEmpty()) {
            consecutiveFailures = 0;
            return candles;
        }
        int failures = ++consecutiveFailures;
        if (failures >= TRIP_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + COOLDOWN_MS;
            log.warn("{} K线范围连续失败 {} 次，熔断 {} 分钟，直接走{}兜底",
                    primaryName, failures, COOLDOWN_MS / 60_000, fallbackName);
        } else {
            log.warn("{} K线范围空，降级{} | symbol={} | 连续失败 {} 次",
                    primaryName, fallbackName, symbol, failures);
        }
        List<Candle> fb = fallback.klineRange(symbol, from, to);
        return fb != null ? fb : List.of();
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
                log.info("{} 熔断冷却结束，恢复主源探测", primaryName);
            }
        }
        return false;
    }

    /** 测试用：查询当前熔断状态。 */
    boolean isCircuitOpen() {
        return circuitOpenUntil != 0 && System.currentTimeMillis() < circuitOpenUntil;
    }
}
