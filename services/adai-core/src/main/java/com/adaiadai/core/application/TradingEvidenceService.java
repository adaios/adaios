package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.engine.TradingRuleEngine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TradingEvidenceService — 交易建议的「四要素铁证」底座（RFC `20260922-trading-decision-copilot` A 批）。
 *
 * <p>用户 2026-09-21 亲述：「需要阿呆提醒我，给我意见，**尤其给我铁证，通过我之前的操作**」，
 * 并在 2026-09-22 拍板四要素全要。本类提供其中两条的**数据基础**（推送改写是 B 批的事）：
 *
 * <ul>
 *   <li><b>① 本人历史操作统计</b>（{@link #historyStats}）：把 {@code sold.json} 的清仓回合按
 *       <b>持仓时长 / 盈亏区间 / 清仓判定</b>分桶，给出「N 次 · 胜率 · 平均盈亏 · 平均持天」。
 *       <b>最小样本门槛 N ≥ 5</b>（用户 2026-09-22 拍板 D3）——不足的桶标记 {@code sufficient=false}，
 *       调用方**必须直说「样本还不够」**，不得拿 1-2 次巧合当规律（那正是"假铁证"）。</li>
 *   <li><b>③ 规则依据原文</b>（{@link #ruleText} / {@link #ruleTextOf}）：从只读的
 *       {@code os/trading-engine/knowledge/context/rules.md} 解析出「规则编号 → 标题 + 原文」，
 *       供建议**逐字引用**（不得由 AI 复述改写成"大概是这个意思"）。解析复用
 *       {@link TradingRuleEngine#parseRules}（G-3：口径归引擎），本类只做查询与缓存。</li>
 * </ul>
 *
 * <p><b>降级一律诚实</b>：sold 文件读不到 → 空统计 + note 说明；rules.md 读不到 → 空列表
 * （调用方不给引用，**而不是编一条规则出来**）。文件按 mtime 缓存，避免每条建议都读盘。
 *
 * <p>本类**只读**，不写任何用户数据。
 */
@Slf4j
@Service
public class TradingEvidenceService {

    /** 铁证①的最小样本门槛（用户 2026-09-22 拍板 D3：N ≥ 5，不足则明说"样本还不够"）。 */
    public static final int MIN_SAMPLE = 5;

    private static final List<String> HOLD_ORDER = List.of("≤1 天", "2-3 天", "4-10 天", ">10 天");
    private static final List<String> PNL_ORDER = List.of("≥+10%", "+5~10%", "0~+5%", "-5~0%", "<-5%");

    private final SoldTradeRepository soldTradeRepository;
    private final TradingLotService tradingLotService;
    private final TradingRuleEngine ruleEngine;
    private final Path rulesPath;

    /** rules.md 解析缓存（按 mtime 失效；规则文件极小，缓存一份即可）。 */
    private volatile List<RuleText> rulesCache;
    private volatile long rulesCacheMtime = -1L;

    public TradingEvidenceService(SoldTradeRepository soldTradeRepository,
                                  TradingLotService tradingLotService,
                                  TradingRuleEngine ruleEngine,
                                  @Value("${adai.knowledge.trading-engine-path:../../os/trading-engine/knowledge/context}")
                                  String knowledgeDir) {
        this.soldTradeRepository = soldTradeRepository;
        this.tradingLotService = tradingLotService;
        this.ruleEngine = ruleEngine;
        this.rulesPath = Paths.get(knowledgeDir, "rules.md").toAbsolutePath().normalize();
    }

    // ── ① 本人历史操作统计 ────────────────────────────────────────────────

    /** 统计维度（先做数据里真有的三个；「按形态分组」等 lots 的 buyPoint 覆盖率上来再补）。 */
    public enum Dimension {
        /** 持有多久卖的（用户的习惯是日线级别、早盘买尾盘卖）。 */
        HOLD_DAYS("持仓时长"),
        /** 卖出时赚/亏多少（落在哪个区间）。 */
        PNL_BUCKET("盈亏区间"),
        /** 清仓判定的结论（如 R66 扛单 / R53 短打）。 */
        VERDICT("清仓判定"),
        /** 买点形态（B1/B2/B3…）：来自批次视图的 `buyPoint`（join symbol+buyDate）；对不上 → 未标形态。 */
        BUY_POINT("买点形态");

        private final String label;

        Dimension(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一个分组桶的统计。
     *
     * @param sufficient 样本是否够（{@code count >= MIN_SAMPLE}）——**false 时调用方不得引用这组数字**
     */
    public record HistoryBucket(String label, int count, int wins, double winRate,
                                double avgPnlPct, double avgHoldDays, boolean sufficient) {}

    /**
     * 某维度的历史统计。
     *
     * @param totalRounds   清仓回合总数（说明样本来自多少笔真实了结）
     * @param anySufficient 是否至少有一组够样本（false → 调用方应转成「样本还不够」的人话）
     * @param note          人话说明（含门槛口径），可直接进推送文案
     */
    public record HistoryStats(Dimension dimension, List<HistoryBucket> buckets,
                               int totalRounds, boolean anySufficient, String note) {}

    /** 按维度出历史统计（只读 sold.json；无记录 → 空统计 + 人话 note）。 */
    public HistoryStats historyStats(String userId, Dimension dimension) {
        List<SoldTrade> all = soldTradeRepository.findAll(userId);
        Map<String, String> buyPointOf = dimension == Dimension.BUY_POINT
                ? buyPointIndex(userId) : Map.of();
        Map<String, List<SoldTrade>> grouped = new LinkedHashMap<>();
        for (SoldTrade t : all) {
            grouped.computeIfAbsent(labelOf(t, dimension, buyPointOf), k -> new ArrayList<>()).add(t);
        }
        List<HistoryBucket> buckets = new ArrayList<>();
        for (Map.Entry<String, List<SoldTrade>> e : grouped.entrySet()) {
            buckets.add(bucketOf(e.getKey(), e.getValue()));
        }
        if (dimension == Dimension.VERDICT || dimension == Dimension.BUY_POINT) {
            buckets.sort(Comparator.comparingInt(HistoryBucket::count).reversed());
        } else {
            List<String> order = dimension == Dimension.HOLD_DAYS ? HOLD_ORDER : PNL_ORDER;
            buckets.sort(Comparator.comparingInt(b -> order.indexOf(b.label())));
        }
        boolean anySufficient = buckets.stream().anyMatch(HistoryBucket::sufficient);
        int maxCount = buckets.stream().mapToInt(HistoryBucket::count).max().orElse(0);
        String note;
        if (all.isEmpty()) {
            note = "还没有清仓回合记录——等你卖出几笔之后，我才能拿你自己的操作说话";
        } else if (anySufficient) {
            note = String.format("按「%s」看你自己的 %d 个回合；只报样本 ≥ %d 的组",
                    dimension.label(), all.size(), MIN_SAMPLE);
        } else {
            note = String.format("样本还不够（共 %d 个回合，最多的一组也只有 %d 次）——"
                    + "先不给你统计，免得拿巧合当规律", all.size(), maxCount);
        }
        return new HistoryStats(dimension, buckets, all.size(), anySufficient, note);
    }

    private static HistoryBucket bucketOf(String label, List<SoldTrade> trades) {
        int n = trades.size();
        int wins = (int) trades.stream().filter(t -> t.holdPnlPct() > 0).count();
        double avgPnl = trades.stream().mapToDouble(SoldTrade::holdPnlPct).average().orElse(0);
        double avgHold = trades.stream().mapToInt(SoldTrade::holdDays).average().orElse(0);
        double winRate = n == 0 ? 0 : (double) wins / n;
        return new HistoryBucket(label, n, wins, winRate, avgPnl, avgHold, n >= MIN_SAMPLE);
    }

    private static String labelOf(SoldTrade t, Dimension dim, Map<String, String> buyPointOf) {
        return switch (dim) {
            case HOLD_DAYS -> holdBucket(t.holdDays());
            case PNL_BUCKET -> pnlBucket(t.holdPnlPct());
            case VERDICT -> {
                String v = t.verdict();
                yield (v == null || v.isBlank()) ? "（未判定）" : v;
            }
            case BUY_POINT -> {
                if (t.buyDate() == null) yield "未标形态";
                String bp = buyPointOf.get(t.symbol() + "|" + t.buyDate());
                yield (bp == null || bp.isBlank()) ? "未标形态" : bp;
            }
        };
    }

    /**
     * 盈亏区间分桶（RFC 20260922 B 批：从 {@link #labelOf} 抽出为 public static——尾盘卖点的
     * 四要素①要按「这笔**现在所处**的区间」去找历史，口径必须与统计侧逐字一致，否则会找错桶）。
     */
    public static String pnlBucket(double pnlPct) {
        if (pnlPct >= 10) return "≥+10%";
        if (pnlPct >= 5) return "+5~10%";
        if (pnlPct >= 0) return "0~+5%";
        if (pnlPct >= -5) return "-5~0%";
        return "<-5%";
    }

    /** 持仓时长分桶（口径同 {@link #HOLD_ORDER}；抽出理由同上）。 */
    public static String holdBucket(int holdDays) {
        if (holdDays <= 1) return "≤1 天";
        if (holdDays <= 3) return "2-3 天";
        if (holdDays <= 10) return "4-10 天";
        return ">10 天";
    }

    /**
     * {@code symbol|buyDate → buyPoint} 索引（形态维度用）：来自批次视图（`lots(userId, "all")`）。
     *
     * <p>为什么要 join：清仓回合（`sold.json`）**不带形态**，形态记在**批次**上。同一标的多次买入时，
     * 「首买日 vs 各批次买入日」对不上是常态——**对不上就是「未标形态」，不猜**（宁可少一个维度，
     * 也不给一个编出来的形态）。
     *
     * <p>取批次失败 → 空索引（整个维度落成「未标形态」，不抛错、不阻断建议）。
     */
    private Map<String, String> buyPointIndex(String userId) {
        try {
            Map<String, String> idx = new HashMap<>();
            for (TradingLotService.TradingLotView lot : tradingLotService.lots(userId, "all")) {
                if (lot.buyPoint() == null || lot.buyPoint().isBlank() || lot.buyDate() == null) continue;
                idx.putIfAbsent(lot.symbol() + "|" + lot.buyDate(), lot.buyPoint());
            }
            return idx;
        } catch (RuntimeException e) {
            log.warn("买点形态索引构建失败（形态维度降级为「未标形态」）| userId={} | {}", userId, e.getMessage());
            return Map.of();
        }
    }

    // ── ③ 规则依据原文（逐字引用） ────────────────────────────────────────

    /** 一条规则的原文（{@code number} = 规则编号，如 66 → R66）。 */
    public record RuleText(int number, String title, String detail) {}

    /** 全部规则（读不到 → 空列表；调用方不得编造）。 */
    public List<RuleText> allRules() {
        List<RuleText> c = rulesCache;
        long mtimeNow = mtimeOf();
        if (c != null && mtimeNow != -1L && mtimeNow == rulesCacheMtime) return c;
        if (!Files.isReadable(rulesPath)) {
            log.warn("规则原文不可读（铁证③降级：不给引用，也不编造）| path={}", rulesPath);
            return List.of();
        }
        try {
            String content = Files.readString(rulesPath, StandardCharsets.UTF_8);
            List<RuleText> parsed = ruleEngine.parseRules(content).stream()
                    .map(r -> new RuleText(r.number(), r.title(), r.detail()))
                    .toList();
            rulesCache = parsed;
            rulesCacheMtime = mtimeNow;
            return parsed;
        } catch (IOException e) {
            log.warn("读取规则原文失败（铁证③降级：不给引用）| path={} | {}", rulesPath, e.getMessage());
            return List.of();
        }
    }

    /** 按编号取原文（如 {@code ruleText(66)} → R66 原文）；找不到 → empty。 */
    public Optional<RuleText> ruleText(int number) {
        return allRules().stream().filter(r -> r.number() == number).findFirst();
    }

    /** 按引用串取原文：{@code "R66"} / {@code "r66"} / {@code "66"} 都认；认不出 → empty。 */
    public Optional<RuleText> ruleTextOf(String ruleRef) {
        if (ruleRef == null) return Optional.empty();
        String digits = ruleRef.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return Optional.empty();
        try {
            return ruleText(Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private long mtimeOf() {
        try {
            return Files.getLastModifiedTime(rulesPath).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }
}
