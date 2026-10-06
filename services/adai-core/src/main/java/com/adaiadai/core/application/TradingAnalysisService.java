package com.adaiadai.core.application;

import com.adaiadai.core.application.TradingRoundService.TradeRound;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * TradingAnalysisService — 「分析总结」三粒度（design-final §2.4/§5 · blueprint §八 · R-05，2026-10-06）。
 *
 * <p><b>三粒度 × 两层 × 两维度</b>：全局（我是个什么样的交易者）· 单标的（这只票做了几笔）·
 * 单笔（这一笔到底发生了什么）；两层 = 交易 / 资金；两维度 = 笔 / 批次。
 *
 * <p><b>描述与对照分开</b>（blueprint §八）：描述（客观，人人可用）= 你实际怎么做的、结果如何；
 * 对照（规范）= 拿<b>你自己的规则</b>量你守没守。无规则的人只出描述，如实说
 * 「我还没有你的规则，判不了守没守」——<b>不拿别人的规则替他判</b>（判据：{@code ruleSettings.exists}）。
 *
 * <p><b>四条原则（分析层不能破）</b>：
 * <ol>
 *   <li><b>可回溯</b>——每个数字带 {@link Trace}（哪几笔 / 哪几天算的）</li>
 *   <li><b>不编</b>——数据不全 → {@code value=null} + 说明，<b>不填 0</b></li>
 *   <li><b>带对照</b>——关键数字旁给对照物（你自己的历史平均 / 最好那笔）</li>
 *   <li><b>只陈述</b>——不评价、不建议</li>
 * </ol>
 *
 * <p><b>三段总结</b>（blueprint §八已定）：① 事实 ② 对照（命中哪条规则）③ 一个提问
 * 「要把这条固化成你的规则吗？」（无规则 / 无命中 → 提问为 null，不硬凑）。
 *
 * <p><b>依赖</b>：{@code analytics → rounds + ledger}（设计 §2 依赖约束）——笔的切分复用
 * {@link TradingRoundService}（含人工边界），资金层复用 {@link EquityCurveService}，
 * <b>不复制第二套口径</b>。出口是规则（描述性统计 = 候选规则原料，rules 批承接）。
 */
@Service
public class TradingAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TradingAnalysisService.class);

    private final TradingRoundService roundService;
    private final TradingHistoryRepository tradingHistoryRepository;
    private final EquityCurveService equityCurveService;
    private final TradingRuleSettingsPort ruleSettings;

    public TradingAnalysisService(TradingRoundService roundService,
                                  TradingHistoryRepository tradingHistoryRepository,
                                  EquityCurveService equityCurveService,
                                  TradingRuleSettingsPort ruleSettings) {
        this.roundService = roundService;
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.equityCurveService = equityCurveService;
        this.ruleSettings = ruleSettings;
    }

    // ── 三粒度入口 ──

    /** 全局：我是个什么样的交易者。 */
    public AnalysisView global(String userId) {
        List<TradeRound> all = allRounds(userId);
        List<TradeRound> closed = closedOf(all);
        List<TradeRound> unresolved = unresolvedOf(all);
        List<Fact> desc = new ArrayList<>();

        // 笔维度（描述）
        desc.add(fact("rounds-count", "做完的笔", closed.size(), "笔",
                trace(ids(closed), null, "已平仓的笔（盈利 + 亏损）")));
        if (!closed.isEmpty()) {
            appendRoundStats(desc, closed);
        }
        desc.add(fact("unresolved", "判不了的段", unresolved.size(), "段",
                trace(ids(unresolved), null, unresolved.isEmpty()
                        ? "没有" : "只有卖出、流水未覆盖建仓——如实：判不了（不编 0）")));
        // 批次维度
        int lots = all.stream().mapToInt(TradeRound::buyCount).sum();
        int trapped = all.stream().mapToInt(TradeRound::addOnTrappedCount).sum();
        desc.add(fact("lots-count", "买入批次", lots, "次",
                trace(ids(all), null, "批次 = 每一次买入（笔 ⊇ 批次）")));
        desc.add(fact("addon-trapped", "被套加仓", trapped, "次",
                trace(ids(all.stream().filter(r -> r.addOnTrappedCount() > 0).toList()), null,
                        "加仓时浮亏超过你设的「被套」阈值的次数")));
        // 「按资金量 / 仓位分时期看」（R-05）
        desc.add(fact("by-period", "分时期（按清仓月）", byPeriod(closed), null,
                trace(ids(closed), null, "每月的笔数 / 盈亏——哪段时间做得好，一目了然")));
        desc.add(fact("by-size", "按资金量分三层", bySize(closed), null,
                trace(ids(closed), null, closed.size() < 3
                        ? "笔数不够，分不了层（如实）" : "按每笔投入金额从小到大约三等分")));
        // 资金层（G-07 复用，不重算第二套）
        desc.addAll(capitalFacts(userId));

        Contrast contrast = contrast(userId, closed);
        return new AnalysisView("global", "全局", desc, contrast, globalSummary(closed, contrast));
    }

    /** 单标的：这只票我一共做了几笔、哪笔好哪笔差。 */
    public AnalysisView symbol(String userId, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 必填（看哪只票）");
        }
        List<TradeRound> all = roundService.rounds(userId, symbol, Integer.MAX_VALUE).rounds();
        List<TradeRound> closed = closedOf(all);
        List<TradeRound> unresolved = unresolvedOf(all);
        List<Fact> desc = new ArrayList<>();

        desc.add(fact("symbol-rounds", "这只票做完的笔", closed.size(), "笔", trace(ids(closed), null, symbol)));
        if (!closed.isEmpty()) {
            BigDecimal sumPnl = BigDecimal.ZERO;
            int wins = 0;
            int holdSum = 0;
            for (TradeRound r : closed) {
                if (r.pnl() != null) sumPnl = sumPnl.add(r.pnl());
                if (r.pnl() != null && r.pnl().signum() > 0) wins++;
                holdSum += r.holdDays();
            }
            desc.add(fact("symbol-pnl", "累计盈亏", sumPnl, "元",
                    trace(ids(closed), null, "这只票全部已平仓笔之和")));
            desc.add(fact("symbol-wins", "胜笔", wins + " / " + closed.size(), null,
                    trace(ids(closed.stream().filter(TradingAnalysisService::isWin).toList()), null, "盈利的笔")));
            desc.add(fact("symbol-avg-hold", "平均持有",
                    BigDecimal.valueOf(holdSum).divide(BigDecimal.valueOf(closed.size()), 1, RoundingMode.HALF_UP), "天",
                    trace(ids(closed), datesOf(closed), "笔内首买到清仓的日历天数平均")));
        }
        BigDecimal invested = BigDecimal.ZERO;
        for (TradeRound r : all) {
            if (r.buyAmount() != null) invested = invested.add(r.buyAmount());
        }
        desc.add(fact("symbol-invested", "累计投入", invested, "元",
                trace(ids(all), null, "全部买入金额（含费）之和")));
        desc.add(fact("unresolved", "判不了的段", unresolved.size(), "段",
                trace(ids(unresolved), null, unresolved.isEmpty() ? "没有" : "只有卖出、流水未覆盖建仓——如实：判不了")));
        List<RoundBrief> briefs = new ArrayList<>();
        for (TradeRound r : all) {
            briefs.add(new RoundBrief(r.id(), r.start(), r.end(), r.pnl(), r.pnlPct(), r.holdDays(),
                    r.unresolved(), r.unresolved() ? r.reason() : null));
        }
        desc.add(fact("round-list", "逐笔", briefs, null, trace(ids(all), null, "点进每一笔 = 单笔粒度")));

        Contrast contrast = contrast(userId, closed);
        String label = symbol + " · 做完 " + closed.size() + " 笔";
        return new AnalysisView("symbol", label, desc, contrast, symbolSummary(symbol, closed, contrast));
    }

    /** 单笔：这一笔到底发生了什么。 */
    public AnalysisView round(String userId, String roundId) {
        int sep = roundId == null ? -1 : roundId.lastIndexOf('_');
        if (sep <= 0 || sep == roundId.length() - 1) {
            throw new IllegalArgumentException("笔 id 形状应为 {代码}_{开始日}，如 600206_2026-08-05");
        }
        String symbol = roundId.substring(0, sep);
        List<TradeRound> all = roundService.rounds(userId, symbol, Integer.MAX_VALUE).rounds();
        TradeRound target = null;
        for (TradeRound r : all) {
            if (roundId.equals(r.id())) { target = r; break; }
        }
        if (target == null) {
            throw new IllegalArgumentException("找不到该笔：" + roundId + "（可能流水已变、边界已重切）");
        }
        List<Fact> desc = new ArrayList<>();
        String span = target.start() + " ~ " + (target.end() != null ? target.end() : "持有中");
        desc.add(fact("span", "这一笔的跨度", span, null,
                trace(List.of(target.id()), datesOf(List.of(target)), "从首笔买入到清仓（未清仓则标「持有中」）")));

        if (target.unresolved()) {
            desc.add(fact("unresolved", "判不了", null, null,
                    trace(List.of(target.id()), null, target.reason())));
        } else {
            if (target.lastBuy() != null && target.start() != null) {
                long build = java.time.temporal.ChronoUnit.DAYS.between(target.start(), target.lastBuy());
                desc.add(fact("build-days", "建仓期", build, "天",
                        trace(List.of(target.id()), List.of(target.start(), target.lastBuy()),
                                "首次买入 → 最后一次买入（首次卖出前的建仓过程）")));
            }
            desc.add(fact("hold-days", "持有期", target.holdDays(), "天",
                    trace(List.of(target.id()), datesOf(List.of(target)), "从首笔买入到清仓的日历天数（未清仓 = 至今）")));
            desc.add(fact("peak", "峰值浮盈",
                    target.peakPct() == null ? null : target.peakPct() + "%"
                            + (target.peakDate() != null ? "@" + target.peakDate() : ""),
                    null, trace(List.of(target.id()),
                            target.peakDate() != null ? List.of(target.peakDate()) : List.of(),
                            target.peakPct() == null ? "日线不足，算不出——如实" : "轮内逐日最高价 vs 当日成本")));
            desc.add(fact("trough", "最大浮亏", target.troughPct() == null ? null : target.troughPct() + "%", null,
                    trace(List.of(target.id()), List.of(),
                            target.troughPct() == null ? "日线不足，算不出——如实" : "轮内逐日最低价 vs 当日成本")));
            desc.add(fact("buy-sell", "买入 / 卖出", target.buyCount() + " / " + target.sellCount(), "次",
                    trace(List.of(target.id()), datesOf(List.of(target)), "笔内买入 / 卖出流水次数")));
            List<LocalDate> dayTradeDays = dayTradeDays(userId, target);
            desc.add(fact("t-count", "做 T 次数", dayTradeDays.size(), "次",
                    trace(List.of(target.id()), dayTradeDays,
                            dayTradeDays.isEmpty() ? "没有同日先卖后买" : "同日先卖后买（含清仓后买回）——不切新笔")));
            desc.add(fact("addon", "加仓", target.addOnCount() + " 次（其中被套 " + target.addOnTrappedCount() + " 次）",
                    null, trace(List.of(target.id()), null, "第 2 笔起的每次买入")));
            if (target.end() == null) {
                desc.add(fact("result", "最终结果", null, null,
                        trace(List.of(target.id()), null, "还没清仓，结果未定——如实")));
            } else {
                desc.add(fact("result", "最终结果", target.pnl() + " 元（" + target.pnlPct() + "%）", null,
                        trace(List.of(target.id()), List.of(target.start(), target.end()), "清仓定结果")));
            }
        }
        Contrast contrast = contrast(userId, target.unresolved() ? List.of() : List.of(target));
        Summary summary = roundSummary(target, contrast);
        String label = symbol + " " + span;
        return new AnalysisView("round", label, desc, contrast, summary);
    }

    // ── 描述：通用统计块 ──

    private void appendRoundStats(List<Fact> desc, List<TradeRound> closed) {
        List<TradeRound> wins = closed.stream().filter(TradingAnalysisService::isWin).toList();
        BigDecimal winRate = BigDecimal.valueOf(wins.size() * 100L)
                .divide(BigDecimal.valueOf(closed.size()), 1, RoundingMode.HALF_UP);
        desc.add(fact("win-rate", "胜率", winRate, "%",
                trace(ids(wins), null, wins.size() + " 胜 / " + closed.size() + " 笔已平仓")));

        BigDecimal sumWin = BigDecimal.ZERO;
        BigDecimal sumLoss = BigDecimal.ZERO;
        for (TradeRound r : closed) {
            if (r.pnl() == null) continue;
            if (r.pnl().signum() > 0) sumWin = sumWin.add(r.pnl());
            else if (r.pnl().signum() < 0) sumLoss = sumLoss.add(r.pnl().abs());
        }
        if (sumWin.signum() == 0 || sumLoss.signum() == 0) {
            desc.add(fact("profit-factor", "盈亏比（赚的总 ÷ 亏的总）", null, "倍",
                    trace(ids(closed), null, sumLoss.signum() == 0
                            ? "一笔没亏过，比值算不出（如实，不写 ∞ 当数）"
                            : "还没有盈利笔，比值算不出——如实")));
        } else {
            desc.add(fact("profit-factor", "盈亏比（赚的总 ÷ 亏的总）",
                    sumWin.divide(sumLoss, 2, RoundingMode.HALF_UP), "倍",
                    trace(ids(closed), null, "赚的总 " + sumWin + " 元 ÷ 亏的总 " + sumLoss + " 元")));
        }

        int holdSum = 0;
        for (TradeRound r : closed) holdSum += r.holdDays();
        desc.add(fact("avg-hold", "平均持有",
                BigDecimal.valueOf(holdSum).divide(BigDecimal.valueOf(closed.size()), 1, RoundingMode.HALF_UP), "天",
                trace(ids(closed), null, "对照物：你自己的平均")));

        TradeRound worst = null;
        TradeRound best = null;
        for (TradeRound r : closed) {
            if (r.pnl() == null) continue;
            if (worst == null || r.pnl().compareTo(worst.pnl()) < 0) worst = r;
            if (best == null || r.pnl().compareTo(best.pnl()) > 0) best = r;
        }
        if (worst != null) {
            desc.add(fact("max-loss", "最大单笔亏", worst.pnl(), "元",
                    trace(List.of(worst.id()), null, "亏得最狠的一笔（" + worst.symbol() + " " + worst.start() + "）")));
        }
        if (best != null) {
            desc.add(fact("best-round", "赚最多的一笔", best.pnl(), "元",
                    trace(List.of(best.id()), null, "对照物：你自己的最好一笔（" + best.symbol() + " " + best.start() + "）")));
        }
        desc.add(fact("dist", "盈亏分布（%）", distribution(closed), null,
                trace(ids(closed), null, "每档多少笔——分布比平均值更能说明问题")));
    }

    /** 盈亏分布六档（≤−10 / −10~−3 / −3~0 / 0~+3 / +3~+10 / >+10）。 */
    private static List<Bucket> distribution(List<TradeRound> closed) {
        List<Bucket> out = new ArrayList<>();
        out.add(bucket("≤ −10%", closed, p -> p.compareTo(new BigDecimal("-10")) <= 0));
        out.add(bucket("−10% ~ −3%", closed, p -> p.compareTo(new BigDecimal("-10")) > 0
                && p.compareTo(new BigDecimal("-3")) <= 0));
        out.add(bucket("−3% ~ 0", closed, p -> p.compareTo(new BigDecimal("-3")) > 0 && p.signum() < 0));
        out.add(bucket("0 ~ +3%", closed, p -> p.signum() >= 0 && p.compareTo(new BigDecimal("3")) < 0));
        out.add(bucket("+3% ~ +10%", closed, p -> p.compareTo(new BigDecimal("3")) >= 0
                && p.compareTo(new BigDecimal("10")) <= 0));
        out.add(bucket("> +10%", closed, p -> p.compareTo(new BigDecimal("10")) > 0));
        return out;
    }

    private static Bucket bucket(String label, List<TradeRound> closed, java.util.function.Predicate<BigDecimal> in) {
        List<String> ids = new ArrayList<>();
        for (TradeRound r : closed) {
            if (r.pnlPct() != null && in.test(r.pnlPct())) ids.add(r.id());
        }
        return new Bucket(label, ids.size(), ids);
    }

    /** 分时期：按清仓月分组（倒序）。 */
    private static List<PeriodBucket> byPeriod(List<TradeRound> closed) {
        Map<String, PeriodBucket> byMonth = new TreeMap<>(Comparator.reverseOrder());
        for (TradeRound r : closed) {
            LocalDate d = r.end() != null ? r.end() : r.start();
            if (d == null) continue;
            String key = String.format("%04d-%02d", d.getYear(), d.getMonthValue());
            PeriodBucket b = byMonth.get(key);
            BigDecimal pnl = b == null ? BigDecimal.ZERO : b.pnl();
            List<String> ids = b == null ? new ArrayList<>() : new ArrayList<>(b.roundIds());
            pnl = pnl.add(r.pnl() != null ? r.pnl() : BigDecimal.ZERO);
            ids.add(r.id());
            byMonth.put(key, new PeriodBucket(key, ids.size(), pnl, ids));
        }
        return new ArrayList<>(byMonth.values());
    }

    /** 按资金量分三层（每笔投入金额排序后约三等分；少于 3 笔 → 空，如实说分不了）。 */
    private static List<SizeBucket> bySize(List<TradeRound> closed) {
        List<TradeRound> withAmount = new ArrayList<>();
        for (TradeRound r : closed) if (r.buyAmount() != null) withAmount.add(r);
        if (withAmount.size() < 3) return List.of();
        withAmount.sort(Comparator.comparing(TradeRound::buyAmount));
        int n = withAmount.size();
        int cut1 = n / 3;
        int cut2 = n * 2 / 3;
        List<SizeBucket> out = new ArrayList<>();
        out.add(sizeBucket("小（后 1/3 投入）", withAmount.subList(0, cut1)));
        out.add(sizeBucket("中", withAmount.subList(cut1, cut2)));
        out.add(sizeBucket("大（前 1/3 投入）", withAmount.subList(cut2, n)));
        return out;
    }

    private static SizeBucket sizeBucket(String label, List<TradeRound> rs) {
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        List<String> ids = new ArrayList<>();
        for (TradeRound r : rs) {
            ids.add(r.id());
            if (r.pnlPct() != null) { sum = sum.add(r.pnlPct()); n++; }
        }
        BigDecimal avg = n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        return new SizeBucket(label, rs.size(), avg, ids);
    }

    // ── 资金层（复用 EquityCurveService，不重算第二套）──

    private List<Fact> capitalFacts(String userId) {
        List<Fact> out = new ArrayList<>();
        EquityCurveService.EquityCurve curve;
        try {
            curve = equityCurveService.build(userId);
        } catch (RuntimeException e) {
            log.warn("分析：资金曲线生成失败（资金层如实标出）| userId={} | {}", userId, e.getMessage());
            out.add(fact("capital-net", "净投入", null, "元",
                    trace(List.of(), List.of(), "资金曲线生成失败——如实标出，不编数")));
            return out;
        }
        if (curve.points().isEmpty()) {
            out.add(fact("capital-net", "净投入", null, "元",
                    trace(List.of(), List.of(), "没有账户快照（先导一次持仓股 / 资金股份）——如实：算不出")));
            return out;
        }
        EquityCurveService.EquityPoint last = curve.points().get(curve.points().size() - 1);
        // P1-交易94（2026-10-06）：原文案「从资金流水推出（转存 − 转取），不靠手填（G-06）」与实现
        // 不符——invested 的起点（期初投入缺口）就是快照本金基准（EquityCurveService：
        // investedSoFar = principalNow − 全程转账净额，存量手填值已迁移为调整事件留痕），
        // 只有逐笔转账净额是流水推出的。如实改成「快照基准 + 转账净额」，不再吹「不靠手填」。
        out.add(fact("capital-net", "净投入", last.invested(), "元",
                trace(List.of(), List.of(), "期初投入（快照本金基准）+ 逐笔转账净额（转存 − 转取）推出")));
        out.add(fact("capital-value", "最新总资产", last.totalAssets(), "元",
                trace(List.of(), List.of(), "最近一次快照 + 之后流水推得")));
        out.add(fact("capital-span", "资金曲线跨度", curve.startDate() + " ~ " + curve.endDate(), null,
                trace(List.of(), List.of(),
                        curve.skippedDays() > 0 ? "有 " + curve.skippedDays() + " 天缺数据（已如实标出）" : null)));
        return out;
    }

    // ── 对照（用你自己的规则；无规则 → 只出描述 + 明说判不了）──

    private Contrast contrast(String userId, List<TradeRound> closed) {
        boolean hasRules;
        try {
            hasRules = ruleSettings != null && ruleSettings.exists(userId);
        } catch (RuntimeException e) {
            log.warn("分析：规则存在性读取失败（按「没有规则」处理）| userId={} | {}", userId, e.getMessage());
            hasRules = false;
        }
        if (!hasRules) {
            return new Contrast(false, "我还没有你的规则，判不了守没守", List.of());
        }
        Map<String, RuleAgg> agg = new LinkedHashMap<>();
        for (TradeRound r : closed) {
            for (TradingRoundService.RuleHit h : r.hits()) {
                RuleAgg a = agg.computeIfAbsent(h.rule(), k -> new RuleAgg(h.text()));
                a.count++;
                a.roundIds.add(r.id());
            }
        }
        List<RuleCount> hits = new ArrayList<>();
        agg.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().count, a.getValue().count))
                .forEach(e -> hits.add(new RuleCount(e.getKey(), e.getValue().text,
                        e.getValue().count, e.getValue().roundIds)));
        return new Contrast(true, null, hits);
    }

    private static class RuleAgg {
        final String text;
        int count = 0;
        final List<String> roundIds = new ArrayList<>();
        RuleAgg(String text) { this.text = text; }
    }

    // ── 三段总结（事实 → 对照 → 一个提问）──

    private static Summary globalSummary(List<TradeRound> closed, Contrast contrast) {
        if (closed.isEmpty()) {
            return new Summary("还没有做完整的笔——先把数据交给我，我才有得看",
                    contrast.hasRules() ? null : contrast.reason(), null);
        }
        int wins = 0;
        int losses = 0;
        for (TradeRound r : closed) {
            if (r.pnl() == null) continue;
            if (r.pnl().signum() > 0) wins++;
            else if (r.pnl().signum() < 0) losses++;
        }
        String fact = "共 " + closed.size() + " 笔：" + wins + " 胜 " + losses + " 负";
        String q = contrast.hasRules() && !contrast.ruleHits().isEmpty()
                ? "要把 " + contrast.ruleHits().get(0).rule() + " 固化成你的规则吗？" : null;
        return new Summary(fact, contrastSentence(contrast), q);
    }

    private static Summary symbolSummary(String symbol, List<TradeRound> closed, Contrast contrast) {
        if (closed.isEmpty()) {
            return new Summary("这只票还没有做完的笔", contrast.hasRules() ? null : contrast.reason(), null);
        }
        BigDecimal sum = BigDecimal.ZERO;
        int wins = 0;
        int holdSum = 0;
        for (TradeRound r : closed) {
            if (r.pnl() != null) {
                sum = sum.add(r.pnl());
                if (r.pnl().signum() > 0) wins++;
            }
            holdSum += r.holdDays();
        }
        BigDecimal avg = BigDecimal.valueOf(holdSum).divide(BigDecimal.valueOf(closed.size()), 1, RoundingMode.HALF_UP);
        String fact = symbol + " 共 " + closed.size() + " 笔：" + wins + " 胜，累计 " + sum + " 元，平均持有 " + avg + " 天";
        String q = contrast.hasRules() && !contrast.ruleHits().isEmpty()
                ? "要把 " + contrast.ruleHits().get(0).rule() + " 固化成你的规则吗？" : null;
        return new Summary(fact, contrastSentence(contrast), q);
    }

    private static Summary roundSummary(TradeRound r, Contrast contrast) {
        if (r.unresolved()) {
            return new Summary("这一笔只有卖出、没有建仓流水——成本与盈亏判不了（如实）", null, null);
        }
        StringBuilder fact = new StringBuilder();
        if (r.peakPct() != null) {
            fact.append("峰值 ").append(r.peakPct()).append("%")
                    .append(r.peakDate() != null ? "（" + r.peakDate() + "）" : "").append("、");
        }
        if (r.end() != null) {
            fact.append("最终 ").append(r.pnlPct()).append("%");
        } else {
            fact.append("还没清仓（持有中）");
        }
        fact.append("，从 ").append(r.start()).append(" 到 ").append(r.end() != null ? r.end() : "现在")
                .append("（").append(r.holdDays()).append(" 天），期间买入 ").append(r.buyCount()).append(" 次");
        String c = contrast.hasRules()
                ? (r.hits().isEmpty() ? "按你的规则量：没踩线" : hitSentence(r))
                : contrast.reason();
        String q = contrast.hasRules() && !r.hits().isEmpty()
                ? "要把这条固化成你的规则吗？" : null;
        return new Summary(fact.toString(), c, q);
    }

    private static String hitSentence(TradeRound r) {
        StringBuilder sb = new StringBuilder();
        for (TradingRoundService.RuleHit h : r.hits()) {
            if (sb.length() > 0) sb.append("；");
            sb.append("命中 ").append(h.rule()).append("（").append(h.text()).append("）");
        }
        return sb.toString();
    }

    private static String contrastSentence(Contrast contrast) {
        if (!contrast.hasRules()) return contrast.reason();
        if (contrast.ruleHits().isEmpty()) return "按你的规则量：一笔都没踩线";
        RuleCount top = contrast.ruleHits().get(0);
        return "按你的规则量：" + top.rule() + "（" + top.text() + "）命中 " + top.count() + " 次";
    }

    // ── 做 T 次数（单笔粒度：同日先卖后买）──

    private List<LocalDate> dayTradeDays(String userId, TradeRound r) {
        if (r.symbol() == null || r.start() == null) return List.of();
        LocalDate to = r.end() != null ? r.end() : LocalDate.now();
        Map<LocalDate, List<TradeRecord>> byDay = new TreeMap<>();
        for (TradeRecord t : tradingHistoryRepository.findAll(userId)) {
            if (!r.symbol().equals(t.symbol()) || t.entryDate() == null || t.volume() <= 0) continue;
            if (t.entryDate().isBefore(r.start()) || t.entryDate().isAfter(to)) continue;
            byDay.computeIfAbsent(t.entryDate(), k -> new ArrayList<>()).add(t);
        }
        List<LocalDate> out = new ArrayList<>();
        for (Map.Entry<LocalDate, List<TradeRecord>> e : byDay.entrySet()) {
            List<TradeRecord> day = new ArrayList<>(e.getValue());
            day.sort(Comparator.comparing(t -> t.tradeTime() != null ? t.tradeTime() : java.time.LocalTime.MIN));
            boolean sellSeen = false;
            for (TradeRecord t : day) {
                if (t.direction() == TradeDirection.SELL) sellSeen = true;
                else if (sellSeen) { out.add(e.getKey()); break; }   // 先卖后买 = 做 T
            }
        }
        return out;
    }

    // ── 取数 ──

    private List<TradeRound> allRounds(String userId) {
        return roundService.rounds(userId, null, Integer.MAX_VALUE).rounds();
    }

    private static List<TradeRound> closedOf(List<TradeRound> all) {
        List<TradeRound> out = new ArrayList<>();
        for (TradeRound r : all) if (!r.unresolved() && r.end() != null) out.add(r);
        return out;
    }

    private static List<TradeRound> unresolvedOf(List<TradeRound> all) {
        List<TradeRound> out = new ArrayList<>();
        for (TradeRound r : all) if (r.unresolved()) out.add(r);
        return out;
    }

    private static boolean isWin(TradeRound r) {
        return r.pnl() != null && r.pnl().signum() > 0;
    }

    private static List<String> ids(List<TradeRound> rs) {
        List<String> out = new ArrayList<>();
        for (TradeRound r : rs) if (r.id() != null) out.add(r.id());
        return out;
    }

    private static List<LocalDate> datesOf(List<TradeRound> rs) {
        List<LocalDate> out = new ArrayList<>();
        for (TradeRound r : rs) {
            if (r.start() != null) out.add(r.start());
            if (r.end() != null) out.add(r.end());
        }
        return out;
    }

    private static Fact fact(String key, String label, Object value, String unit, Trace trace) {
        return new Fact(key, label, value, unit, trace);
    }

    private static Trace trace(List<String> roundIds, List<LocalDate> dates, String note) {
        List<String> ds = new ArrayList<>();
        if (dates != null) for (LocalDate d : dates) if (d != null) ds.add(d.toString());
        return new Trace(roundIds, ds, note);
    }

    // ── 视图对象 ──

    /** 一次分析的完整回答：描述（人人可用）· 对照（要有规则）· 三段总结。 */
    public record AnalysisView(String scope, String label, List<Fact> description,
                               Contrast contrast, Summary summary) {}

    /** 一个描述性数字：label + value（null = 判不了 / 缺数据，不编 0）+ 可回溯 trace。 */
    public record Fact(String key, String label, Object value, String unit, Trace trace) {}

    /** 可回溯引用：这个数字是哪几笔 / 哪几天算的（验收 4「每个数字点得进去」）。 */
    public record Trace(List<String> roundIds, List<String> dates, String note) {}

    /** 对照（拿你自己的规则量你守没守）：hasRules=false 时 reason 说明判不了，ruleHits 为空。 */
    public record Contrast(boolean hasRules, String reason, List<RuleCount> ruleHits) {}

    /** 一条规则的命中聚合（count + 哪几笔命中）。 */
    public record RuleCount(String rule, String text, int count, List<String> roundIds) {}

    /** 三段总结：① 事实 ② 对照 ③ 一个提问（无规则 / 无命中 → 提问 null）。 */
    public record Summary(String fact, String contrast, String question) {}

    /** 逐笔简表（单标的粒度的列表原料）。 */
    public record RoundBrief(String id, LocalDate start, LocalDate end, BigDecimal pnl, BigDecimal pnlPct,
                             int holdDays, boolean unresolved, String reason) {}

    /** 盈亏分布一档。 */
    public record Bucket(String label, int count, List<String> roundIds) {}

    /** 分时期一组（按清仓月）。 */
    public record PeriodBucket(String period, int count, BigDecimal pnl, List<String> roundIds) {}

    /** 按资金量一层。 */
    public record SizeBucket(String label, int count, BigDecimal avgPnlPct, List<String> roundIds) {}
}
