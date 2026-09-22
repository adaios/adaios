package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.BuyPointDetector;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.cases.CaseFeatureExtractor;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.CaseSimilarityEngine;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>
 * 第四阶段环 4 二期（2026-08-30）：可选开关 {@code adai.trading.case.scan-match}（默认关）——
 * 开启时每只自选股附「与完美买点案例库相似度 Top 3」参考（经验增强，不覆盖规则判定）；
 * 默认关 = 行为与现状完全一致（向前兼容，不拖慢扫描）。
 */
@Service
public class WatchlistBuyPointService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistBuyPointService.class);

    private final KlineService klineService;
    private final TradingRuleSettingsRepository settingsRepository;
    private final TradingCaseRepository caseRepository;
    private final boolean scanMatchEnabled;
    private final ExecutorService klinePool = Executors.newFixedThreadPool(8);
    /** 案例库 TTL 缓存毫秒数（P2-案例3，2026-09-03：案例多时免每次全量 index+逐文件读）。 */
    private static final long CASE_CACHE_TTL_MS = 5 * 60 * 1000L;
    /** 案例库 TTL 缓存（key=userId；标注/删除/回填后最多延迟 TTL 生效——15:10 扫描与 15:35 回填错峰可接受，对齐 TradingKnowledgeSource 模式）。 */
    private final Map<String, CaseCacheEntry> caseCache = new ConcurrentHashMap<>();

    private record CaseCacheEntry(List<CaseRecord> cases, long expiresAtMillis) {}

    public WatchlistBuyPointService(KlineService klineService,
                                    TradingRuleSettingsRepository settingsRepository,
                                    TradingCaseRepository caseRepository,
                                    @Value("${adai.trading.case.scan-match:false}") boolean scanMatchEnabled) {
        this.klineService = klineService;
        this.settingsRepository = settingsRepository;
        this.caseRepository = caseRepository;
        this.scanMatchEnabled = scanMatchEnabled;
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

    /** 单只自选买点判定结果（caseMatches 为案例相似度参考，二期开关默认关 → 空；
     *  dataDate = 判定所用 K 线最后一根日期（YYYY-MM-DD，空 = 无数据）——信号新鲜度用，
     *  15:10 推送侧要求 dataDate = 当日才推（P1-交易20 P4，2026-09-04 三重校验）。 */
    public record WatchBuyPoint(String symbol, String name, String buyPoint,
                                double score, List<String> signals,
                                List<CaseMatchLite> caseMatches, String dataDate) {}

    /** 案例相似度参考（轻量：参照案例 id + 相似度 + 后验前验）。 */
    public record CaseMatchLite(String caseId, String buyDate, String buyType,
                                double similarityPercent) {}

    /**
     * 没能判定的标的（RFC `20260922` B 批 B4 / P1-交易60）。
     *
     * <p><b>为什么要把它单独列出来</b>：扫描对每只票「K 线取不到」与「取了但没信号」原先**同样**是
     * 「不在结果里」——用户在自选页看到空列，无法区分「今天没机会」和「今天我没拿到行情」。
     * 生产实据（2026-09-18 10:37）双源失败发生在用户看盘的同一分钟；「不知道」绝不能渲染成「没问题」。
     */
    public record Unavailable(String symbol, String name) {}

    /**
     * 一次扫描的完整结果（B4）：命中 {@code hits} + 没能判定的 {@code unavailable} + 数据日期。
     *
     * @param dataDate 本次判定所用 K 线的末端日期（各标的取最晚的一根；无数据 → null）
     */
    public record ScanResult(List<WatchBuyPoint> hits, List<Unavailable> unavailable, String dataDate) {}

    /** 批量判定自选股买点（按用户规则参数；无规则 → 默认建议值）。 */
    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist, String userId) {
        return scanWatchlistDetailed(watchlist, userId).hits();
    }

    /** 批量判定自选股买点（默认参数，测试/降级用）。 */
    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist) {
        return scanWatchlistDetailed(watchlist, null).hits();
    }

    /**
     * 完整扫描（B4）：调用的**用户可见路径**（自选页 / 早盘推送）应当用它，以便把
     * 「这些标的这次没取到行情」如实说出来，而不是静默少几行。
     */
    public ScanResult scanWatchlistDetailed(List<WatchlistItem> watchlist, String userId) {
        return scanWatchlistInternal(watchlist, detectorFor(userId), userId);
    }

    /** 按用户规则构造买点判定器（无规则/损坏 → 默认参数）。 */
    private BuyPointDetector detectorFor(String userId) {
        TradingRuleSettings s = userId != null
                ? settingsRepository.findByUser(userId) : TradingRuleSettings.defaults();
        return new BuyPointDetector(
                s.buyPullbackPct(), s.buyShrinkRatio(), s.buyKdjLow(),
                s.buyVolumeSurge(), s.buyPriorHighDays());
    }

    /** 兼容重载（只取命中）：需要「没能判定的标的」请用 {@link #scanWatchlistDetailed}。 */
    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist, BuyPointDetector detector) {
        return scanWatchlistInternal(watchlist, detector, null).hits();
    }

    /** 兼容重载（只取命中）：需要「没能判定的标的」请用 {@link #scanWatchlistDetailed}。 */
    public List<WatchBuyPoint> scanWatchlist(List<WatchlistItem> watchlist, BuyPointDetector detector,
                                             String userId) {
        return scanWatchlistInternal(watchlist, detector, userId).hits();
    }

    /**
     * 批量判定（含二期案例相似度：开关开 → 每只附案例库 Top 3 参考）+ **失败可见**（B4）。
     * <p>
     * 结果按自选顺序稳定排列（按下标回填，不按完成先后），以免推送里的逐票顺序每次都不一样。
     */
    private ScanResult scanWatchlistInternal(List<WatchlistItem> watchlist, BuyPointDetector detector,
                                             String userId) {
        int n = watchlist.size();
        WatchBuyPoint[] results = new WatchBuyPoint[n];
        Unavailable[] failed = new Unavailable[n];
        List<String> dataDates = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<CaseRecord> cases = loadCases(userId == null ? "default" : userId);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final WatchlistItem item = watchlist.get(i);
            futures.add(klinePool.submit(() -> {
                // P2-交易2：按标的异常隔离——单只失败只跳过该只，不中断整批（B54）
                try {
                    List<Candle> candles = klineService.kline(item.symbol(), 60);
                    if (candles == null || candles.isEmpty()) {
                        // B4（2026-09-22，收口 P1-交易60）：KlineService 双源都失败时返回空——
                        // 这与「有 K 线但没命中」**不是一回事**，必须分别报出去（「不知道 ≠ 没问题」）
                        failed[idx] = new Unavailable(item.symbol(), item.name());
                        return;
                    }
                    BuyPointDetector.BuyPointResult result = detector.detect(candles);
                    List<CaseMatchLite> matches = matchCases(candles, cases);
                    // 信号新鲜度（P1-交易20 P4）：判定所用 K 线最后一根的日期
                    String dataDate = candles.get(candles.size() - 1).date().toString();
                    dataDates.add(dataDate);
                    if (result.hit()) {
                        results[idx] = new WatchBuyPoint(item.symbol(), item.name(),
                                result.buyPoint(), result.score(), result.signals(), matches, dataDate);
                    } else if (!matches.isEmpty()) {
                        // 未命中规则但案例相似度高 → 仍返回（带 empty buyPoint），供前端提示「形态接近完美买点」
                        results[idx] = new WatchBuyPoint(item.symbol(), item.name(),
                                "case", 0, List.of(), matches, dataDate);
                    }
                } catch (Exception e) {
                    log.warn("自选买点判定失败 | symbol={} | {}", item.symbol(), e.getMessage());
                    failed[idx] = new Unavailable(item.symbol(), item.name());
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("自选买点扫描单只超时 | {}", e.getMessage());
            }
        }
        List<WatchBuyPoint> hits = new ArrayList<>();
        List<Unavailable> unavailable = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (results[i] != null) hits.add(results[i]);
            if (failed[i] != null) unavailable.add(failed[i]);
        }
        String dataDate = dataDates.stream().max(java.util.Comparator.naturalOrder()).orElse(null);
        log.info("自选买点扫描 | {} 只 → {} 命中（案例匹配 {} 只）· {} 只没取到行情 · 数据到 {}",
                n, hits.size(), hits.stream().filter(h -> !h.caseMatches().isEmpty()).count(),
                unavailable.size(), dataDate);
        return new ScanResult(List.copyOf(hits), List.copyOf(unavailable), dataDate);
    }

    /**
     * 案例库读取（P2-案例3，2026-09-03）：TTL 缓存 5 分钟——案例多时
     * scanWatchlist 每跑不再全量 index + 逐文件读（缓存共享不可变快照，调用方只读）。
     * 开关关 → 空列表（行为与现状一致，不触碰缓存）。
     */
    private List<CaseRecord> loadCases(String userId) {
        if (!scanMatchEnabled) return List.of();
        long now = System.currentTimeMillis();
        CaseCacheEntry cached = caseCache.get(userId);
        if (cached != null && cached.expiresAtMillis() > now) return cached.cases();
        List<CaseRecord> snapshot = List.copyOf(caseRepository.list(userId));
        caseCache.put(userId, new CaseCacheEntry(snapshot, now + CASE_CACHE_TTL_MS));
        return snapshot;
    }

    /**
     * 案例相似度 Top 3（2026-08-31 双轨：与当前形态最相似的**同类型**案例——
     * B1/B2 各自参照系，负样本不参与；开关关 / 案例库空 → 空列表，行为与现状一致）。
     */
    private List<CaseMatchLite> matchCases(List<Candle> candles, List<CaseRecord> cases) {
        if (cases.isEmpty() || candles == null || candles.isEmpty()) return List.of();
        CaseRecord.CaseFeatures features = CaseFeatureExtractor.extract(candles, candles.get(candles.size() - 1).date());
        if (features == null) return List.of();
        // 双轨各自 Top3，按相似度合并排序取前 3（正样本；负样本不参与）
        java.util.List<CaseSimilarityEngine.MatchResult> merged = new java.util.ArrayList<>();
        merged.addAll(CaseSimilarityEngine.topN(cases, features, 3, "B1"));
        merged.addAll(CaseSimilarityEngine.topN(cases, features, 3, "B2"));
        merged.sort(java.util.Comparator.comparingDouble(
                CaseSimilarityEngine.MatchResult::similarityPercent).reversed());
        List<CaseSimilarityEngine.MatchResult> top = merged.size() > 3 ? merged.subList(0, 3) : merged;
        return top.stream()
                .map(r -> new CaseMatchLite(
                        r.caseRecord().id(),
                        r.caseRecord().buyDate() == null ? "" : r.caseRecord().buyDate().toString(),
                        r.caseRecord().buyType() == null ? "" : r.caseRecord().buyType(),
                        r.similarityPercent()))
                .toList();
    }
}
