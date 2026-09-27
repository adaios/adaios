package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * KlineService — K 线查询（2026-09-28：链路收敛为 {@code tdx → 腾讯（双域名）→ 新浪}，东财出链路）。
 *
 * <p><b>取数链（RFC 20260928 批 2）</b>：{@code TDX 本地 → 腾讯 → 新浪}，全失败返回空。
 * 东财 K 线源（{@code push2his.eastmoney.com}）自 2026-08 起长期不可达：2026-09-28 生产实测
 * **000（连接层，连 HTTP 码都拿不到）**，近 7 天 1711 次失败 / 182 次成功——留着只在熔断期白等超时并刷日志，
 * 故按用户拍板移出链路。**东财的另两个用途不动**：除权因子 {@code datacenter-web.eastmoney.com}
 * 与名称解析 {@code searchapi.eastmoney.com} 同日实测 200、0 失败。
 *
 * <p><b>腾讯双域名</b>：域名列表由 {@code adai.market.tencent-kline-bases} 配置（逗号分隔、按序尝试，
 * 第一个成功即停）。生产 2026-09-28 起配「新域名 + 老域名」两条；当日真链演练（主域名打成不可达）中
 * 23 次失败全部由第二域名顶上，`health` 报 {@code ok=true}。
 *
 * <p><b>可用性可见（RFC 20260923 D 批 + RFC 20260928 批 2）</b>：本类记录「最近一次成功/失败时刻、
 * 连续全失败次数、最后失败的标的、**tdx 最后一根日期**」，经 {@link #health()} 暴露
 * （{@code GET /trading/market-data/health} + 双端交易页横幅）——链路整段挂掉、或本地数据包过期到该导入了，
 * 都不再只留在日志里。
 *
 * <p><b>tdx 按周节奏降噪（RFC 20260928 批 2）</b>：用户实际**一周导入一次**数据包，滞后是预期常态
 * （阈值 3 天，导入后第 4 天起走网络源）。故「tdx 数据滞后」不再逐标的刷 WARN（原先每天 1300～2100 条），
 * 改为**同一滞后日期只记一次 INFO**，滞后事实经 {@link Health#tdxLastDate()} 长期可见。
 * **不做**「滞后即全局跳过本地读」：滞后是**逐标的**的（生产实测同时存在停在 09-04 与 09-18 的两批），
 * 全局跳过会误杀仍然新鲜的标的。
 *
 * <p>历史：2026-08-16 以东财为主源、腾讯兜底；P2-1（2026-08-18 生产）东财连接层被限
 * （{@code header parser received no bytes}，单日 1154 次 WARN）→ 加熔断。2026-08-23 用户确认
 * 「优先以腾讯」：主源对调。2026-09-23 加新浪作最后一层（RFC 20260923 B 批）。
 * 2026-09-28 东财 K 线源删除、新浪升为兜底（RFC 20260928 批 2）。
 */
@Service
public class KlineService {

    private static final Logger log = LoggerFactory.getLogger(KlineService.class);

    /** 连续失败多少次触发熔断（2 次足以识别主源不可用）。 */
    static final int TRIP_THRESHOLD = 3;
    /** 熔断冷却时长：5 分钟（行情日频，收盘后无实时需求）。 */
    static final long COOLDOWN_MS = 5 * 60_000L;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /** 主源（腾讯；域名列表在数据源内部按序尝试：新域名 → 老域名）。 */
    private final KlineSource primary;
    /** 兜底（新浪，RFC 20260928 批 2 起由「最后一层」升为兜底）；可为 null（开关关闭）。 */
    private final KlineSource fallback;
    /** 本地数据包（新鲜时零网络请求）；可为 null（开关关闭）。 */
    private final KlineSource tdx;

    private static final String PRIMARY_NAME = "腾讯";
    private static final String FALLBACK_NAME = "新浪";

    /** 熔断状态（volatile 多线程读；买点扫描并发 16 线程）。 */
    private volatile int consecutiveFailures;
    private volatile long circuitOpenUntil;

    // ── 可用性状态：让「K 线链路整段不可用」从日志走到用户面前 ──
    private volatile long lastSuccessAtMs;
    private volatile String lastSuccessSource;
    private volatile long lastFailureAtMs;
    private volatile int consecutiveAllFailures;
    private volatile String lastFailedSymbol;

    /** tdx 最近一次取到的「最后一根」日期（新鲜度可见；RFC 20260928 批 2 第 6 条）。 */
    private volatile LocalDate tdxLastDate;
    /**
     * 已告过警的滞后日期集合（2026-09-28 修正：原为单个 volatile 变量）。
     * <p>
     * 为什么必须是集合：生产上不同标的的 tdx 末尾日期**有多个在轮转**（实测 2026-09-24 与
     * 2026-09-18 两批并存），单值去重键会被交替覆盖 → 每次都判成「新日期」又记一条
     * （乒乓效应：3 次自选扫描实测 39 条）。集合 + 原子 add 才是真正的「同一滞后日期只记一次」，
     * 顺带根治 16 线程并发重复（REVIEW P2-交易74）。
     */
    private final Set<LocalDate> tdxStaleLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 兜底源同样失败的告警冷却（P2-交易58，2026-09-17 B2 批）。
     * <p>
     * 熔断期内每个标的都会打一次兜底——若兜底也挂了，逐标的记 ERROR 会重现「日志刷屏」
     * （P2-交易1 东财被限时 1154 条 WARN 的教训）。故 ERROR 30 分钟最多一条，其余降 WARN。
     */
    private volatile long fallbackDownAlertedUntil;
    private static final long FALLBACK_ALERT_COOLDOWN_MS = 30 * 60_000L;

    /**
     * @param tdxEnabled  本地数据包开关（{@code adai.market.tdx-enabled}，生产可经 {@code ADAI_TDX_ENABLED} 覆盖）
     * @param sinaEnabled 新浪兜底开关（{@code adai.market.sina-kline-enabled}）；关掉即退回「tdx → 腾讯」两段
     */
    public KlineService(
            @Value("${adai.market.tdx-enabled:true}") boolean tdxEnabled,
            @Value("${adai.market.sina-kline-enabled:true}") boolean sinaEnabled,
            @Qualifier("tencentMarketDataSource") KlineSource tencent,
            @Qualifier("tdxFileKlineSource") KlineSource tdx,
            @Qualifier("sinaKlineDataSource") KlineSource sina) {
        this.primary = tencent;
        this.tdx = tdxEnabled ? tdx : null;
        this.fallback = sinaEnabled ? sina : null;
        log.info("KlineService 初始化 | 链路={} | 兜底={} | TDX本地={}",
                (this.tdx != null ? "tdx → " : "") + PRIMARY_NAME,
                this.fallback != null ? FALLBACK_NAME : "关闭",
                this.tdx != null ? "启用" : "关闭");
    }

    /** 查询日 K：TDX 本地 → 腾讯 → 新浪。熔断开启时 TDX → 直接走新浪。 */
    public List<Candle> kline(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) return List.of();
        if (tdx != null) {
            List<Candle> local = tdx.kline(symbol, limit);
            // 2026-09-16：tdx 数据包滞后时**不能直接返回**——生产实测 tdx 停在 09-04，而
            // 「tdx 有数据就用」让 09-05 起的 K 线全部缺失（资金曲线整段沿用旧价、周期盈亏算成 0、
            // 买点扫描的 dataDate 永远不是当日）。
            // 本地仍新鲜时保持零网络请求。
            if (local != null && !local.isEmpty()) {
                LocalDate last = local.get(local.size() - 1).date();
                tdxLastDate = last;
                if (!tdxStale(last)) {
                    markSuccess("tdx");
                    return local;
                }
                logTdxStaleOnce(last, symbol);
            }
        }
        if (circuitOpen()) {
            List<Candle> fb = fallbackKline(symbol, limit);
            if (!fb.isEmpty()) return fb;
            // P2-交易58：主源熔断 + 兜底也挂 = 双重失效，不能静默返回空
            alertFallbackAlsoDown("kline", symbol);
            markAllFailed(symbol);
            return List.of();
        }
        List<Candle> candles = primary.kline(symbol, limit);
        if (candles != null && !candles.isEmpty()) {
            consecutiveFailures = 0;
            markSuccess(PRIMARY_NAME);
            return candles;
        }
        int failures = ++consecutiveFailures;
        if (failures >= TRIP_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + COOLDOWN_MS;
            log.warn("{} K线连续失败 {} 次，熔断 {} 分钟，直接走{}兜底",
                    PRIMARY_NAME, failures, COOLDOWN_MS / 60_000, FALLBACK_NAME);
        } else {
            log.warn("{} K线空，降级{} | symbol={} | 连续失败 {} 次",
                    PRIMARY_NAME, FALLBACK_NAME, symbol, failures);
        }
        List<Candle> fb = fallbackKline(symbol, limit);
        if (!fb.isEmpty()) return fb;
        alertFallbackAlsoDown("kline", symbol);
        markAllFailed(symbol);
        return List.of();
    }

    /** 按日期范围查询（2026-08-30：案例库历史窗口）；TDX 本地 → 腾讯 → 新浪，熔断同 kline。 */
    public List<Candle> klineRange(String symbol, LocalDate from, LocalDate to) {
        if (symbol == null || symbol.isBlank() || from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        if (tdx != null) {
            List<Candle> local = tdx.klineRange(symbol, from, to);
            if (local != null && !local.isEmpty()) {
                LocalDate lastLocal = local.get(local.size() - 1).date();
                tdxLastDate = lastLocal;
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
                            symbol, lastLocal, to, ChronoUnit.DAYS.between(lastLocal, to));
                    return local;
                }
                List<Candle> merged = new ArrayList<>(local);
                Set<LocalDate> have = new HashSet<>();
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
     * 网络源按区间拉取（腾讯 → 新浪，熔断同 kline）。
     * 2026-09-16：从 {@link #klineRange} 抽出——tdx 滞后补缺口也走这条（含熔断与失败计数）。
     */
    private List<Candle> networkRange(String symbol, LocalDate from, LocalDate to) {
        if (circuitOpen()) {
            List<Candle> fb = fallbackRange(symbol, from, to);
            if (!fb.isEmpty()) return fb;
            alertFallbackAlsoDown("klineRange " + from + "~" + to, symbol);
            markAllFailed(symbol);
            return List.of();
        }
        List<Candle> candles = primary.klineRange(symbol, from, to);
        if (candles != null && !candles.isEmpty()) {
            consecutiveFailures = 0;
            markSuccess(PRIMARY_NAME);
            return candles;
        }
        int failures = ++consecutiveFailures;
        if (failures >= TRIP_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + COOLDOWN_MS;
            log.warn("{} K线范围连续失败 {} 次，熔断 {} 分钟，直接走{}兜底",
                    PRIMARY_NAME, failures, COOLDOWN_MS / 60_000, FALLBACK_NAME);
        } else {
            log.warn("{} K线范围空，降级{} | symbol={} | 连续失败 {} 次",
                    PRIMARY_NAME, FALLBACK_NAME, symbol, failures);
        }
        List<Candle> fb = fallbackRange(symbol, from, to);
        if (!fb.isEmpty()) return fb;
        alertFallbackAlsoDown("klineRange " + from + "~" + to, symbol);
        markAllFailed(symbol);
        return List.of();
    }

    // ── 兜底层（新浪）──

    /** 新浪日 K（关掉返回空；异常按空处理，安全约定同其它源）。 */
    private List<Candle> fallbackKline(String symbol, int limit) {
        if (fallback == null) return List.of();
        try {
            List<Candle> c = fallback.kline(symbol, limit);
            if (c != null && !c.isEmpty()) {
                markSuccess(FALLBACK_NAME);
                return c;
            }
        } catch (Exception e) {
            log.warn("{} K线失败 | symbol={} | {}", FALLBACK_NAME, symbol, e.getMessage());
        }
        return List.of();
    }

    /** 新浪区间 K（默认实现=拉 320 根后按日期过滤）。 */
    private List<Candle> fallbackRange(String symbol, LocalDate from, LocalDate to) {
        if (fallback == null) return List.of();
        try {
            List<Candle> c = fallback.klineRange(symbol, from, to);
            if (c != null && !c.isEmpty()) {
                markSuccess(FALLBACK_NAME);
                return c;
            }
        } catch (Exception e) {
            log.warn("{} K线范围失败 | symbol={} | {}", FALLBACK_NAME, symbol, e.getMessage());
        }
        return List.of();
    }

    // ── 可用性可见 ──

    /**
     * 行情可用性状态（RFC 20260923 D 批；RFC 20260928 批 2 增 {@code tdxLastDate}）。
     *
     * @param ok                  当前是否可用（最近一次成功不早于最近一次失败）
     * @param note                人话说明（可直接进横幅/推送）
     * @param lastSuccessAt       最近一次拿到行情（任一源）的时刻
     * @param lastSuccessSource   那一根来自哪个源（tdx / 腾讯 / 新浪）
     * @param lastFailureAt       最近一次**所有源都拿不到**的时刻
     * @param consecutiveFailures 连续全失败次数（成功即清零）
     * @param lastFailedSymbol    最后失败的那只标的
     * @param sources             当前启用的取数链（按序）
     * @param tdxLastDate         本地数据包最后一根日期（本地关掉 / 还没取过 → null）；
     *                            用户一周导入一次，此值长期停在旧日期即「该导数据包了」
     */
    public record Health(boolean ok, String note, String lastSuccessAt, String lastSuccessSource,
                         String lastFailureAt, int consecutiveFailures, String lastFailedSymbol,
                         List<String> sources, String tdxLastDate) {}

    /** 当前行情可用性（读侧只读快照，无锁）。 */
    public Health health() {
        long okAt = lastSuccessAtMs;
        long failAt = lastFailureAtMs;
        boolean ok = failAt == 0 || okAt >= failAt;
        List<String> sources = new ArrayList<>();
        if (tdx != null) sources.add("tdx");
        sources.add(PRIMARY_NAME);
        if (fallback != null) sources.add(FALLBACK_NAME);

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
        LocalDate td = tdxLastDate;
        return new Health(ok, note, okAt == 0 ? null : ts(okAt), lastSuccessSource,
                failAt == 0 ? null : ts(failAt), consecutiveAllFailures, lastFailedSymbol,
                List.copyOf(sources), td == null ? null : td.toString());
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
            log.warn("{}兜底同样拿不到数据 | op={} | symbol={}", FALLBACK_NAME, op, symbol);
            return;
        }
        fallbackDownAlertedUntil = now + FALLBACK_ALERT_COOLDOWN_MS;
        log.error("主源与{}兜底双双失败 | op={} | symbol={} | 行情缺口未补（调用方会拿到空/旧值）"
                        + "——兜底源可能也已失效，请检查；30 分钟内同类只记这一条",
                FALLBACK_NAME, op, symbol);
    }

    /**
     * tdx 数据滞后只按「最后一根日期」记一次 INFO（RFC 20260928 批 2 第 4 条）。
     * <p>
     * 为什么改：用户实际**一周导入一次**数据包，滞后是预期常态——原实现每个标的、每次取数都记一条 WARN，
     * 生产每天 1300～2100 条，把真正该看的信号淹了。同一滞后日期只记一次即可（日期变化 = 又导了一次包），
     * 「当前本地停在哪天」长期由 {@link Health#tdxLastDate()} 回答。
     * <p>
     * 并发下（买点扫描 16 线程）可能多记一两条，可接受——不值得为此加锁。
     */
    private void logTdxStaleOnce(LocalDate last, String symbol) {
        if (!tdxStaleLogged.add(last)) return;   // add() 原子：同一日期已告过即返回（并发下也只一条）
        log.info("tdx 数据滞后（最后一根 {}），改走网络源 | 首次触发 symbol={}；导一次数据包即恢复",
                last, symbol);
    }

    /**
     * tdx 是否滞后：最后一根距今超过 3 个自然日（覆盖一个周末；长假会多探一次网络源，无害——
     * 那时网络源同样没有新数据，最多多一次请求）。
     * <p>
     * 与「一周导入一次」的关系：导入后第 1～3 天用本地（零网络请求），第 4 天起自动走网络源——
     * 安全优先，不用过期数据冒充当日。
     */
    private boolean tdxStale(LocalDate last) {
        return ChronoUnit.DAYS.between(last, LocalDate.now()) > 3;
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
                log.info("{} 熔断冷却结束，恢复主源探测", PRIMARY_NAME);
            }
        }
        return false;
    }

    /** 测试用：查询当前熔断状态。 */
    boolean isCircuitOpen() {
        return circuitOpenUntil != 0 && System.currentTimeMillis() < circuitOpenUntil;
    }
}
