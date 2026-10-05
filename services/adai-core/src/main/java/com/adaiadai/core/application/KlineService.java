package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KlineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p><b>兜底源主动体检（REVIEW P2-交易58 收口，2026-10-04）</b>：兜底（新浪）原来只在主源熔断时才被
 * 逐标的批量打过去——零调用、零体检，等发现它也是挂的就已经没源可顶了。现在每 30 分钟（**仅交易时段**）
 * 主动探一次，结果进 {@link Health#fallbackHealthy()} / {@link Health#fallbackLastProbeAt()}。
 * 探测轻量（10 根、5s 超时）、失败只记日志（ERROR + 1 小时冷却），**不影响主链路**。
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

    /**
     * 兜底源主动探测的默认频率：**交易时段内每 30 分钟**（2026-10-04，REVIEW P2-交易58 收口）。
     * <p>
     * 触发点 09:00/09:30/…/11:30、13:00/…/15:00，再由 {@link #inTradingSession(LocalTime)} 收口到
     * 09:30–11:30 / 13:00–15:00（09:00 那次会被过滤——那时行情还是上一交易日的）。
     * 非交易时段**不探**：日 K 一天一变，盘后重复探没有信息增量，只是白打网络。
     * <p>
     * <b>为什么下午段必须是 {@code 13-15}（REVIEW P2-交易86，2026-10-05）</b>：窗口判据
     * {@link #inTradingSession(LocalTime)} 把 <b>15:00 这一刻算作盘中</b>（收盘价落定、仍是行情时段的边界），
     * 而原来的 {@code 13-14} 最后一次触发停在 **14:30** ⇒ <b>14:30–15:00 整段无探测</b>，
     * 与窗口语义不自洽（兜底若恰好在这半小时里挂掉，当天就体检不到）。补上 {@code 15} 后
     * 每交易日实际探测 **10 次**：09:30 / 10:00 / 10:30 / 11:00 / 11:30 / 13:00 / 13:30 / 14:00 / 14:30 / 15:00，
     * 09:00 与 15:30（若配到）那两次由窗口过滤。该口径由
     * {@code KlineServiceTest.probeFallback_cronTicks_coverCloseAt1500_andYieldTenProbesPerTradingDay} 用
     * Spring {@code CronExpression} 真算触发点钉住（不是只在注释里承诺）。
     * <p>
     * 覆盖方式：{@code adai.trading.kline.fallback-probe-cron}（env {@code ADAI_TRADING_KLINE_FALLBACK_PROBE_CRON}）。
     */
    static final String CRON_FALLBACK_PROBE = "0 0/30 9-11,13-15 * * MON-FRI";

    /** 探测标的：贵州茅台（流动性最好、常年有数据，不存在停牌误报）。 */
    static final String PROBE_SYMBOL = "600519";

    /**
     * 探测失败的告警冷却（1 小时，比探测间隔 30 分钟长）。
     * <p>
     * 与链路侧 {@link #FALLBACK_ALERT_COOLDOWN_MS}（30 分钟）分开：那条讲「主源+兜底同时挂了、
     * 这次取数拿的是空」，这条讲「兜底的常态化体检没过，主源一旦熔断将无源可顶」——不同事实，
     * 不该互相吞掉告警名额。冷却期内的同类失败降 WARN。
     */
    private static final long FALLBACK_PROBE_ALERT_COOLDOWN_MS = 60 * 60_000L;

    /** 熔断状态（volatile 多线程读；买点扫描 8 线程 / 清仓打分 16 线程）。 */
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
     * 顺带根治多线程并发重复（买点扫描 8 / 清仓打分 16）（REVIEW P2-交易74）。
     */
    private final Set<LocalDate> tdxStaleLogged = ConcurrentHashMap.newKeySet();
    /**
     * 已就哪一天（本地止于）告过「缺口补齐失败」——同型封堵（2026-09-28）。
     * <p>
     * 与 {@link #tdxStaleLogged} 分开：两条路径语义不同（那条是「改走网络源」，这条是「网络也没补上、回退滞后数据」），
     * 不该互相吞掉。生产定量：该告警近 7 天 **1192 条 / 172 只标的**，而「本地止于」只有 4 种日期 → 按日期去重后 4 条。
     */
    private final Set<LocalDate> tdxGapLogged = ConcurrentHashMap.newKeySet();

    /**
     * 兜底源同样失败的告警冷却（P2-交易58，2026-09-17 B2 批）。
     * <p>
     * 熔断期内每个标的都会打一次兜底——若兜底也挂了，逐标的记 ERROR 会重现「日志刷屏」
     * （P2-交易1 东财被限时 1154 条 WARN 的教训）。故 ERROR 30 分钟最多一条，其余降 WARN。
     */
    private volatile long fallbackDownAlertedUntil;
    private static final long FALLBACK_ALERT_COOLDOWN_MS = 30 * 60_000L;

    // ── 兜底源主动体检（REVIEW P2-交易58 收口，2026-10-04）──
    // 链路侧那条告警只在「主源熔断 + 兜底也拿不到」时才响——那时兜底已经是被打挂的状态；
    // 这里补的是**平时**的低频体检：每 30 分钟（仅交易时段）真发一次轻量请求，把「兜底是否活着」
    // 变成 health() 里的一个字段，而不是等主源熔断那天才发现它也没了。
    /**
     * 兜底源最近一次主动探测的**一致快照**（healthy + 探测时刻；null = 尚未探测过 / 兜底已关闭）。
     * <p>
     * <b>为什么是一个引用而不是两个 volatile 字段（REVIEW P2-交易86 第 4 条）</b>：{@link #health()}
     * 原先分两次读 {@code fallbackHealthy} 与 {@code fallbackLastProbeAtMs}，两次读之间若恰好插进一次探测，
     * 端点就会输出「上一轮的健康 + 这一轮的时刻」这种**从未真实存在过**的组合。
     * 一个 record 引用天然是一次一致快照，写入侧也只是换引用，成本为零。
     */
    private volatile ProbeSnapshot fallbackProbe;
    /** 探测失败的告警冷却（见 {@link #FALLBACK_PROBE_ALERT_COOLDOWN_MS}）。 */
    private volatile long fallbackProbeAlertedUntil;
    /**
     * 连续探测通过次数（REVIEW P2-交易86 第 3 条：**确认恢复**才清零冷却）。
     * <p>
     * 为什么不能「一成功就清零」：抖动源（fail→ok→fail）每 30 分钟就能把冷却洗掉一次，
     * 于是每 30 分钟一条 ERROR，与设计的「1 小时最多一条」不符。现在只有**连续
     * {@value #RECOVERY_CONFIRM_STREAK} 次**通过才认定真恢复并清零冷却；单次 ok 只把连续计数 +1
     * （并留一条「从失败回到成功」的 INFO，供人看转折），一旦失败立即归零。
     */
    private volatile int fallbackProbeSuccessStreak;

    /** 确认恢复所需的连续成功次数（2 次 = 跨过一次失败之后的下一次探测再通过，约 30 分钟）。 */
    static final int RECOVERY_CONFIRM_STREAK = 2;

    /** 兜底探测快照：healthy 与探测时刻必须同时可见，不能拆成两次 volatile 读。 */
    private record ProbeSnapshot(Boolean healthy, long lastProbeAtMs) {}

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
                    logTdxGapOnce(lastLocal, symbol, to);
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

    // ── 兜底源主动体检（REVIEW P2-交易58 收口，2026-10-04）──

    /**
     * 低频主动探测兜底源（新浪）：**交易时段内每 30 分钟一次**，结果进 {@link Health#fallbackHealthy()}。
     * <p>
     * <b>解决什么</b>：链路是 {@code TDX → 主源 → 兜底}，而兜底平时零调用、零体检——只在主源熔断时
     * 才被逐标的批量打过去，那恰恰是它最可能也挂的时刻。原来只有走到那一步才知道兜底是否可用
     * （{@link #alertFallbackAlsoDown} 是**事后**告警，且那时取数已经失败）。本方法把它变成**事前**的
     * 常态化体检：探到了就写进 health，人在端点上一眼能看到「兜底还活着吗」。
     * <p>
     * <b>轻量与无害</b>：只拉 {@link KlineSource#PROBE_LIMIT} 根（新浪侧 5s 超时、真实请求、不走缓存）；
     * 非交易日 / 非交易时段直接返回（日 K 一天一变，盘后重复探没有信息增量）；
     * 异常一律吞掉只记日志——探测失败**绝不**影响 {@link #kline} 主链路，也不动熔断计数与
     * {@code ok/lastSuccessSource}（那是链路级状态，探测只反映兜底源自己）。
     * <p>
     * <b>异常一律不外逃（REVIEW P2-交易86 第 2 条，2026-10-05）</b>：连窗口判定 {@link #isProbeWindow()}
     * 也在最外层 try 之内——当前判据只是 DayOfWeek + 静态节假日 Set、不会抛，但一旦将来改得会抛，
     * 也绝不允许从 {@code @Scheduled} 入口逃逸（逃逸会污染调度日志、让人误以为体检在跑），
     * 且必须**如实记 ERROR**而不是静默吞掉。
     * <p>
     * <b>冷却只在确认恢复后才清零（第 3 条）</b>：判据见 {@link #RECOVERY_CONFIRM_STREAK}，抖动源
     * （fail→ok→fail）不再能靠单次 ok 洗掉冷却而每 30 分钟刷一条 ERROR。
     * <p>
     * 频率走 {@code adai.trading.kline.fallback-probe-cron}（env {@code ADAI_TRADING_KLINE_FALLBACK_PROBE_CRON}），
     * 默认 {@link #CRON_FALLBACK_PROBE}。
     */
    @Scheduled(cron = "${adai.trading.kline.fallback-probe-cron:" + CRON_FALLBACK_PROBE + "}")
    public void probeFallbackSource() {
        // 最外层兜底（P2-交易86 第 2 条）：探测体 + 窗口判定 + 状态写入 + 告警全收进一个 try，
        // 任何异常都不得从 @Scheduled 入口逃逸。
        try {
            if (fallback == null) return;   // 兜底关闭 → 没有体检对象（health 该字段恒 null）
            if (!isProbeWindow()) return;   // 非交易日 / 非交易时段 → 不白打网络

            boolean healthy;
            try {
                healthy = fallback.probe(PROBE_SYMBOL);
            } catch (Exception e) {
                // probe() 的约定是不抛；这里再兜一层，保证调度线程不会因一次探测炸掉
                healthy = false;
                log.warn("{}兜底探测抛异常（已吞掉，不影响主链路） | symbol={} | {}",
                        FALLBACK_NAME, PROBE_SYMBOL, e.getMessage());
            }

            ProbeSnapshot before = fallbackProbe;   // 一次读 → 拿到上一轮的一致快照
            fallbackProbe = new ProbeSnapshot(healthy, System.currentTimeMillis());

            if (healthy) {
                int streak = ++fallbackProbeSuccessStreak;
                if (before != null && Boolean.FALSE.equals(before.healthy())) {
                    // 失败 → 成功：这是人关心的转折，留一条 INFO（口径不变）
                    log.info("{}兜底源探测恢复（此前未通过；连续 {} 次通过才解除告警冷却） | symbol={}",
                            FALLBACK_NAME, RECOVERY_CONFIRM_STREAK, PROBE_SYMBOL);
                } else {
                    log.debug("{}兜底源探测通过 | symbol={}", FALLBACK_NAME, PROBE_SYMBOL); // 常态 → 不刷 INFO
                }
                // 只有「确认恢复」（连续 N 次通过）才清零冷却：
                // 抖动源的单次 ok（fail→ok→fail）洗不掉冷却，第二次失败仍只降 WARN。
                if (streak >= RECOVERY_CONFIRM_STREAK) {
                    fallbackProbeAlertedUntil = 0;
                }
                return;
            }
            fallbackProbeSuccessStreak = 0;   // 失败 → 「确认恢复」进度归零
            alertFallbackProbeFailed();
        } catch (Exception e) {
            log.error("{}兜底源探测体异常（已吞掉，不影响 @Scheduled 调度与主链路） | symbol={}",
                    FALLBACK_NAME, PROBE_SYMBOL, e);
        }
    }

    /** 现在是否该探兜底：交易日 且 在交易时段内（可覆写——测试固定它，免得吃真实日历）。 */
    boolean isProbeWindow() {
        return isTradingDayToday() && inTradingSession(LocalTime.now());
    }

    /** 今天是否交易日（自证版：周末 + 法定节假日都不算；与交易推送共用同一份日历）。 */
    boolean isTradingDayToday() {
        return TradingSessionPushService.isTradingDayStrict(LocalDate.now());
    }

    /** 交易时段：上午 09:30–11:30、下午 13:00–15:00（含边界）。 */
    boolean inTradingSession(LocalTime time) {
        return inRange(time, LocalTime.of(9, 30), LocalTime.of(11, 30))
                || inRange(time, LocalTime.of(13, 0), LocalTime.of(15, 0));
    }

    private static boolean inRange(LocalTime t, LocalTime from, LocalTime to) {
        return !t.isBefore(from) && !t.isAfter(to);
    }

    /**
     * 兜底源探测没通过的告警：ERROR + 1 小时冷却（冷却期内降 WARN）。
     * <p>
     * 冷却独立于 {@link #alertFallbackAlsoDown}：同一次探测失败**不代表**主源也挂了，
     * 两条告警讲的是不同事实，不共用冷却名额。
     * <p>
     * <b>清零条件（REVIEW P2-交易86 第 3 条）</b>：冷却**不再**被单次成功清零，只有
     * {@link #RECOVERY_CONFIRM_STREAK} 次连续通过（确认恢复）才清零——抖动源因此稳定在
     * 「1 小时最多一条 ERROR」而不是「每 30 分钟一条」。
     */
    private void alertFallbackProbeFailed() {
        long now = System.currentTimeMillis();
        String detail = FALLBACK_NAME + "兜底源主动探测未通过 | symbol=" + PROBE_SYMBOL
                + " | 它平时零调用、只在主源熔断时才被批量打过去，那时若它也是挂的 → 行情整段缺口";
        if (now < fallbackProbeAlertedUntil) {
            log.warn("{} | 冷却中（1 小时内同类只记一条 ERROR）", detail);
            return;
        }
        fallbackProbeAlertedUntil = now + FALLBACK_PROBE_ALERT_COOLDOWN_MS;
        log.error("{} | 请检查新浪 K 线接口；（探测每 30 分钟一次、仅交易时段）1 小时内同类只记这一条", detail);
    }

    // ── 可用性可见 ──

    /**
     * 行情可用性状态（RFC 20260923 D 批；RFC 20260928 批 2 增 {@code tdxLastDate}；
     * REVIEW P2-交易58 收口增 {@code fallbackHealthy} / {@code fallbackLastProbeAt}）。
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
     * @param fallbackHealthy     兜底源（新浪）最近一次**主动探测**是否通过；
     *                            {@code null} = 还没探过或兜底已关闭（是否启用看 {@code sources}）。
     *                            它是**事前**体检，与 {@code ok} 不同源：主源正常时兜底也可能已经挂了
     * @param fallbackLastProbeAt 最近一次主动探测的时刻（没探过 → null）
     */
    public record Health(boolean ok, String note, String lastSuccessAt, String lastSuccessSource,
                         String lastFailureAt, int consecutiveFailures, String lastFailedSymbol,
                         List<String> sources, String tdxLastDate,
                         Boolean fallbackHealthy, String fallbackLastProbeAt) {}

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
        ProbeSnapshot probe = fallbackProbe;   // 一次读：healthy 与时刻必来自同一轮探测（P2-交易86 第 4 条）
        Boolean fbHealthy = fallback == null || probe == null ? null : probe.healthy();
        long probeAt = probe == null ? 0 : probe.lastProbeAtMs();
        return new Health(ok, note, okAt == 0 ? null : ts(okAt), lastSuccessSource,
                failAt == 0 ? null : ts(failAt), consecutiveAllFailures, lastFailedSymbol,
                List.copyOf(sources), td == null ? null : td.toString(),
                fbHealthy, probeAt == 0 ? null : ts(probeAt));
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
     * tdx「缺口补齐失败」的告警也按「本地止于日期」去重（2026-09-28，同型封堵；REVIEW P2-交易75）。
     * <p>
     * 原实现**逐标的、每次调用**都记：生产近 7 天 **1192 条**（172 只标的），而「本地止于」只有
     * 4 种日期（`09-04` / `09-18` / `09-24` / `09-14`）→ 按日期去重后应为 **4 条**（降 99.7%）。
     * 与 {@link #logTdxStaleOnce} 各自独立：那条讲「改走网络源」，这条讲「网络也没补上、回退滞后数据」，不互相吞。
     */
    private void logTdxGapOnce(LocalDate lastLocal, String symbol, LocalDate to) {
        if (!tdxGapLogged.add(lastLocal)) return;   // 同一「本地止于」只记一次（并发下 add 原子）
        log.warn("tdx 缺口补齐失败，回退滞后的本地数据 | symbol={} | 本地止于 {} | 目标末端 {} | 缺口 {} 天未补"
                        + "（同一日期只记这一条，其余标的的同类缺口不再逐条刷）",
                symbol, lastLocal, to, ChronoUnit.DAYS.between(lastLocal, to));
    }

    /**
     * tdx 数据滞后只按「最后一根日期」记一次 INFO（RFC 20260928 批 2 第 4 条）。
     * <p>
     * 为什么改：用户实际**一周导入一次**数据包，滞后是预期常态——原实现每个标的、每次取数都记一条 WARN，
     * 生产每天 1300～2100 条，把真正该看的信号淹了。同一滞后日期只记一次即可（日期变化 = 又导了一次包），
     * 「当前本地停在哪天」长期由 {@link Health#tdxLastDate()} 回答。
     * <p>
     * 并发下（买点扫描 8 线程 / 清仓打分 16 线程）**也只会记一条**——`add()` 是原子的；独立审查实测：16 线程、两个滞后日期各 8 线程
     * → 集合实现恰好 **2 条**，而旧的单值实现同场景为 **12 / 10 / 11 条**（2026-09-28）。
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
