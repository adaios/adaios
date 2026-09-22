package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * KlineService — K 线查询（2026-08-23：腾讯主源，东财探测兜底；配置 {@code adai.market.kline-primary} 可切回东财）。
 *
 * <p><b>取数链（RFC 20260923 A/B 批后的四层）</b>：
 * {@code TDX 本地 → 主源（腾讯）→ 兜底（东财）→ 最后兜底（新浪）}，全失败返回空。
 * 2026-09-22 深夜实测三条源**同时失效**（腾讯 K 线域名被 WAF 拦 501 · 东财长期被限 · tdx 包滞后），
 * 才补上新浪这层——**两个网络源同属被风控对象，「同时挂」不是小概率**。
 *
 * <p><b>可用性可见（RFC 20260923 D 批）</b>：本类记录「最近一次成功/失败时刻、连续全失败次数、最后失败的标的」，
 * 经 {@link #health()} 暴露（{@code GET /trading/market-data/health} + 双端交易页横幅）——
 * 链路整段挂掉不再只留在日志里：用户那边看到的曲线平了/信号没了，会有一条「阿呆最近拿不到行情」的交代。
 *
 * <p>历史：2026-08-16 以东财为主源、腾讯兜底；P2-1（2026-08-18 生产）东财连接层被限（
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

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final KlineSource primary;
    private final KlineSource fallback;
    private final KlineSource tdx;
    /** 最后一层兜底（新浪；RFC 20260923 B 批）——可为 null（开关关闭）。 */
    private final KlineSource lastResort;
    private final String primaryName;
    private final String fallbackName;
    private final String lastResortName = "新浪";

    /** 熔断状态（volatile 多线程读；买点扫描并发 16 线程）。 */
    private volatile int consecutiveFailures;
    private volatile long circuitOpenUntil;

    // ── 可用性状态（RFC 20260923 D 批）：让「K 线链路整段不可用」从日志走到用户面前 ──
    private volatile long lastSuccessAtMs;
    private volatile String lastSuccessSource;
    private volatile long lastFailureAtMs;
    private volatile int consecutiveAllFailures;
    private volatile String lastFailedSymbol;

    /**
     * 兜底源同样失败的告警冷却（P2-交易58，2026-09-17 B2 批）。
     * <p>
     * 熔断期内每个标的都会打一次兜底——若兜底也挂了，逐标的记 ERROR 会重现「日志刷屏」
     * （P2-交易1 东财被限时 1154 条 WARN 的教训）。故 ERROR 30 分钟最多一条，其余降 WARN。
     */
    private volatile long fallbackDownAlertedUntil;
    private static final long FALLBACK_ALERT_COOLDOWN_MS = 30 * 60_000L;

    public KlineService(
            @Value("${adai.market.kline-primary:tencent}") String primaryName,
            @Value("${adai.market.tdx-enabled:true}") boolean tdxEnabled,
            @Value("${adai.market.sina-kline-enabled:true}") boolean sinaEnabled,
            @Qualifier("eastMoneyKlineDataSource") KlineSource eastMoney,
            @Qualifier("tencentMarketDataSource") KlineSource tencent,
            @Qualifier("tdxFileKlineSource") KlineSource tdx,
            @Qualifier("sinaKlineDataSource") KlineSource sina) {
        boolean tencentFirst = "tencent".equalsIgnoreCase(primaryName);
        this.primaryName = tencentFirst ? "腾讯" : "东财";
        this.fallbackName = tencentFirst ? "东财" : "腾讯";
        this.primary = tencentFirst ? tencent : eastMoney;
        this.fallback = tencentFirst ? eastMoney : tencent;
        // 2026-08-30：通达信本地数据第一优先（全 A 历史、免风控）——tdx 无数据（未同步/缺标的）不算失败，走网络源
        this.tdx = tdxEnabled ? tdx : null;
        // 2026-09-23（B 批）：新浪作为最后一层；关掉即行为退回改动前（tdx → 主源 → 兜底）
        this.lastResort = sinaEnabled ? sina : null;
        log.info("KlineService 初始化 | 主源={} | 兜底={} | TDX本地={} | 最后兜底={}",
                this.primaryName, this.fallbackName, tdxEnabled ? "启用" : "关闭",
                this.lastResort != null ? this.lastResortName : "关闭");
    }

    /** 查询日 K：TDX 本地 → 主源 → 兜底 → 新浪。熔断开启时 TDX → 直接走兜底。 */
    public List<Candle> kline(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        if (tdx != null) {
            List<Candle> local = tdx.kline(symbol, limit);
            // 2026-09-16：tdx 数据包滞后时**不能直接返回**——生产实测 tdx 停在 09-04，而
            // 「tdx 有数据就用」让 09-05 起的 K 线全部缺失：资金曲线整段沿用旧价（09-05 后全错）、
            // 周期盈亏算成 0、买点扫描的 dataDate 永远不是当日（15:10 推送静默失效）。
            // 本地仍新鲜时保持零网络请求。
            if (!local.isEmpty()) {
                java.time.LocalDate last = local.get(local.size() - 1).date();
                if (!tdxStale(last)) {
                    markSuccess("tdx");
                    return local;
                }
                log.warn("tdx 数据滞后（最后一根 {}），改走网络源 | symbol={}", last, symbol);
            }
        }
        if (circuitOpen()) {
            List<Candle> fb = fallback.kline(symbol, limit);
            if (fb != null && !fb.isEmpty()) {
                markSuccess(fallbackName);
                return fb;
            }
            List<Candle> lr = lastResortKline(symbol, limit);
            if (!lr.isEmpty()) return lr;
            // P2-交易58：原先这里静默返回空——主源熔断 + 兜底也挂 = 双重失效，无人知道
            alertFallbackAlsoDown("kline", symbol);
            markAllFailed(symbol);
            return List.of();
        }
        List<Candle> candles = primary.kline(symbol, limit);
        if (!candles.isEmpty()) {
            consecutiveFailures = 0;
            markSuccess(primaryName);
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
        if (fb != null && !fb.isEmpty()) {
            markSuccess(fallbackName);
            return fb;
        }
        List<Candle> lr = lastResortKline(symbol, limit);
        if (!lr.isEmpty()) return lr;
        alertFallbackAlsoDown("kline", symbol);
        markAllFailed(symbol);
        return List.of();
    }

    /** 按日期范围查询（2026-08-30：案例库历史窗口）；TDX 本地 → 主源 → 兜底 → 新浪，熔断同 kline。 */
    public List<Candle> klineRange(String symbol, java.time.LocalDate from, java.time.LocalDate to) {
        if (symbol == null || symbol.isBlank()) return List.of();
        if (tdx != null) {
            List<Candle> local = tdx.klineRange(symbol, from, to);
            if (!local.isEmpty()) {
                java.time.LocalDate lastLocal = local.get(local.size() - 1).date();
                if (!lastLocal.isBefore(to)) {
                    markSuccess("tdx");
                    return local; // 本地已覆盖到区间末端 → 零网络请求
                }
                // 2026-09-16：本地滞后 → **缺口用网络源补齐**（tdx 补长历史、网络源补最近），
                // 而不是「有本地数据就整段用本地」——那会让区间末端的 K 线凭空消失。
                List<Candle> tail = networkRange(symbol, lastLocal.plusDays(1), to);
                if (tail.isEmpty()) {
                    // P2-交易58（2026-09-17 B2 批）：缺口没补上时**如实说**——这里返回的是**滞后**的
                    // 本地数据（生产实据：tdx 停在 09-04，案例库历史窗口末端整段缺失），
                    // 而原先静默 return local，调用方无从知道末端缺了多少天。
                    log.warn("tdx 缺口补齐失败，回退滞后的本地数据 | symbol={} | 本地止于 {} | 目标末端 {} | 缺口 {} 天未补",
                            symbol, lastLocal, to,
                            java.time.temporal.ChronoUnit.DAYS.between(lastLocal, to));
                    return local;
                }
                List<Candle> merged = new ArrayList<>(local);
                java.util.Set<java.time.LocalDate> have = new java.util.HashSet<>();
                for (Candle c : local) have.add(c.date());
                for (Candle c : tail) if (have.add(c.date())) merged.add(c);
                merged.sort(java.util.Comparator.comparing(Candle::date));
                log.info("tdx 数据补齐 | symbol={} | 本地止于 {} | 网络补 {} 根 | 合计 {} 根",
                        symbol, lastLocal, tail.size(), merged.size());
                return merged;
            }
        }
        return networkRange(symbol, from, to);
    }

    /**
     * 网络源按区间拉取（主源 → 兜底 → 新浪，熔断同 kline）。
     * 2026-09-16：从 {@link #klineRange} 抽出——tdx 滞后补缺口也走这条（含熔断与失败计数）。
     */
    private List<Candle> networkRange(String symbol, java.time.LocalDate from, java.time.LocalDate to) {
        if (circuitOpen()) {
            List<Candle> fb = fallback.klineRange(symbol, from, to);
            if (fb != null && !fb.isEmpty()) {
                markSuccess(fallbackName);
                return fb;
            }
            List<Candle> lr = lastResortRange(symbol, from, to);
            if (!lr.isEmpty()) return lr;
            alertFallbackAlsoDown("klineRange " + from + "~" + to, symbol);
            markAllFailed(symbol);
            return List.of();
        }
        List<Candle> candles = primary.klineRange(symbol, from, to);
        if (!candles.isEmpty()) {
            consecutiveFailures = 0;
            markSuccess(primaryName);
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
        if (fb != null && !fb.isEmpty()) {
            markSuccess(fallbackName);
            return fb;
        }
        List<Candle> lr = lastResortRange(symbol, from, to);
        if (!lr.isEmpty()) return lr;
        alertFallbackAlsoDown("klineRange " + from + "~" + to, symbol);
        markAllFailed(symbol);
        return List.of();
    }

    // ── 最后一层兜底（新浪，RFC 20260923 B 批）──

    /** 新浪日 K（空 = 也拿不到；异常按空处理，安全约定同其它源）。 */
    private List<Candle> lastResortKline(String symbol, int limit) {
        if (lastResort == null) return List.of();
        try {
            List<Candle> c = lastResort.kline(symbol, limit);
            if (c != null && !c.isEmpty()) {
                markSuccess(lastResortName);
                return c;
            }
        } catch (Exception e) {
            log.warn("{} K线失败 | symbol={} | {}", lastResortName, symbol, e.getMessage());
        }
        return List.of();
    }

    /** 新浪区间 K（默认实现=拉 320 根后按日期过滤）。 */
    private List<Candle> lastResortRange(String symbol, java.time.LocalDate from, java.time.LocalDate to) {
        if (lastResort == null) return List.of();
        try {
            List<Candle> c = lastResort.klineRange(symbol, from, to);
            if (c != null && !c.isEmpty()) {
                markSuccess(lastResortName);
                return c;
            }
        } catch (Exception e) {
            log.warn("{} K线范围失败 | symbol={} | {}", lastResortName, symbol, e.getMessage());
        }
        return List.of();
    }

    // ── 可用性可见（RFC 20260923 D 批）──

    /**
     * 行情可用性状态（RFC 20260923 D 批）。
     *
     * @param ok                  当前是否可用（最近一次成功不早于最近一次失败）
     * @param note                人话说明（可直接进横幅/推送）
     * @param lastSuccessAt       最近一次拿到行情（任一源）的时刻
     * @param lastSuccessSource   那一根来自哪个源（tdx / 腾讯 / 东财 / 新浪）
     * @param lastFailureAt       最近一次**所有源都拿不到**的时刻
     * @param consecutiveFailures 连续全失败次数（成功即清零）
     * @param lastFailedSymbol    最后失败的那只标的
     * @param sources             当前启用的取数链（按序）
     */
    public record Health(boolean ok, String note, String lastSuccessAt, String lastSuccessSource,
                         String lastFailureAt, int consecutiveFailures, String lastFailedSymbol,
                         List<String> sources) {}

    /** 当前行情可用性（读侧只读快照，无锁）。 */
    public Health health() {
        long okAt = lastSuccessAtMs;
        long failAt = lastFailureAtMs;
        boolean ok = failAt == 0 || okAt >= failAt;
        List<String> sources = new ArrayList<>();
        if (tdx != null) sources.add("tdx");
        sources.add(primaryName);
        sources.add(fallbackName);
        if (lastResort != null) sources.add(lastResortName);

        String note;
        if (okAt == 0 && failAt == 0) {
            note = "还没查过行情";
        } else if (ok) {
            note = "行情正常（最近一次 " + ts(okAt) + " · " + (lastSuccessSource == null ? "—" : lastSuccessSource) + "）";
        } else {
            note = "行情取数连续 " + consecutiveAllFailures + " 次都没拿到（最近一次失败 " + ts(failAt)
                    + (lastFailedSymbol != null ? " · " + lastFailedSymbol : "")
                    + "）——资金曲线、自选信号、案例匹配可能不全，我在自动重试";
        }
        return new Health(ok, note, okAt == 0 ? null : ts(okAt), lastSuccessSource,
                failAt == 0 ? null : ts(failAt), consecutiveAllFailures, lastFailedSymbol, List.copyOf(sources));
    }

    private void markSuccess(String source) {
        lastSuccessAtMs = System.currentTimeMillis();
        lastSuccessSource = source;
        consecutiveAllFailures = 0;
    }

    private void markAllFailed(String symbol) {
        lastFailureAtMs = System.currentTimeMillis();
        lastFailedSymbol = symbol;
        consecutiveAllFailures++;
    }

    private static String ts(long epochMs) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
                .format(TS);
    }

    /**
     * 兜底源也拿不到数据时的告警（P2-交易58，2026-09-17 B2 批）。
     * <p>
     * 链路是 {@code TDX 本地 → 主源 → 兜底}，而兜底平时零调用、零体检——只在主源熔断时才被
     * 批量打过去，那恰恰是它最可能也挂的时刻。原实现在这里**完全静默**（直接返回空列表），
     * 于是「兜底形同虚设」要等用户发现数据缺失才暴露。
     * <p>
     * ERROR 带 30 分钟冷却（熔断期内逐标的刷屏会淹没日志），冷却期内的同类降 WARN。
     */
    private void alertFallbackAlsoDown(String op, String symbol) {
        long now = System.currentTimeMillis();
        if (now < fallbackDownAlertedUntil) {
            log.warn("{}兜底同样拿不到数据 | op={} | symbol={}", fallbackName, op, symbol);
            return;
        }
        fallbackDownAlertedUntil = now + FALLBACK_ALERT_COOLDOWN_MS;
        log.error("主源与{}兜底双双失败 | op={} | symbol={} | 行情缺口未补（调用方会拿到空/旧值）"
                        + "——兜底源可能也已失效，请检查；30 分钟内同类只记这一条",
                fallbackName, op, symbol);
    }

    /**
     * tdx 是否滞后：最后一根距今超过 3 个自然日（覆盖一个周末；长假会多探一次网络源，无害——
     * 那时网络源同样没有新数据，最多多一次请求）。
     */
    private boolean tdxStale(java.time.LocalDate last) {
        return java.time.temporal.ChronoUnit.DAYS.between(last, java.time.LocalDate.now()) > 3;
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
