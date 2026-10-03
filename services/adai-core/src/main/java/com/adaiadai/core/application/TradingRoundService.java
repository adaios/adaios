package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import com.adaiadai.core.domain.trading.market.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingRoundService — 「一轮完整交易」的识别与交易规则检查
 * （RFC 20261003-trading-plan-and-review-loop §五，2026-10-03；用户授权「全做」）。
 *
 * <p><b>为什么需要它</b>：通达信「清仓股」表是**标的级总账**（首次介入 → 最后一次清仓，累计全部买卖），
 * **没有轮次概念**——生产实测 160/172 只标的的「买卖次数」等于该标的全部流水笔数（000776 = 33+16，
 * 而流水显示它实际做了 7 轮）。文件给 172 条，流水能还原出 232 轮 → 60 轮原本不可见。
 *
 * <p><b>口径（用户 2026-10-03 拍板 C）</b>：持仓归零 = 一轮边界；**同一交易日内「卖光→买回」合并为同一轮**
 * （实测 6 处，最快 82 秒后买回）。B 档「N 日内买回算同轮」不做（需定 N，易主观）。
 *
 * <p><b>建仓完毕的定义（用户给出）</b>：卖出之前的**最后一次买入** = 建仓完毕，其后 3 个交易日为观察窗
 * （R53「没涨=错」判定用）。
 *
 * <p><b>边界（用户明确要求）</b>：**只陈述事实，不作建议**——输出的是「峰值 +9.4%、最终 −25.9%、
 * 扛了 9 个交易日、命中 R55」这类事实，不是「你应该在 +9.4% 卖出」。
 */
@Service
public class TradingRoundService {

    private static final Logger log = LoggerFactory.getLogger(TradingRoundService.class);

    /** R72 拍板基准（2026-10-03）：止损幅度取 3-5% 中值 = −4%。
     *  为什么不用真实止损位：生产实测 1721 笔流水里带 `stopLossPrice` 的 **0 笔**——
     *  通达信「历史成交查询」与「持仓股」都没有止损位列，只能做「应然 vs 实然」对照。 */
    static final BigDecimal REVIEW_STOP_LOSS_RATIO = new BigDecimal("0.96");

    /** 无规则包时的复盘阈值兜底（真实值走 rules.yaml：reviewPeakMinPct / reviewTrapMinPct）。 */
    private static final double DEFAULT_PEAK_MIN_PCT = 3.0;
    private static final double DEFAULT_TRAP_MIN_PCT = 1.0;

    private final TradingHistoryRepository tradingHistoryRepository;
    private final KlineService klineService;
    private final TradingRuleSettingsPort ruleSettingsRepository;

    public TradingRoundService(TradingHistoryRepository tradingHistoryRepository,
                               KlineService klineService,
                               TradingRuleSettingsPort ruleSettingsRepository) {
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.klineService = klineService;
        this.ruleSettingsRepository = ruleSettingsRepository;
    }

    // ── 对外 ──

    /** 轮次总览（按清仓日倒序）。{@code symbolFilter} 非空时只算该标的；{@code limit} 限制返回条数。 */
    public RoundsView rounds(String userId, String symbolFilter, int limit) {
        TradingRuleSettings rules = safeRules(userId);
        double peakMin = rules.reviewPeakMinPct();
        double trapMin = rules.reviewTrapMinPct();

        List<TradeRecord> all = tradingHistoryRepository.findAll(userId);
        Map<String, List<TradeRecord>> bySymbol = new LinkedHashMap<>();
        for (TradeRecord t : all) {
            if (t.volume() <= 0 || t.entryDate() == null) continue;      // 股息/红利税行（volume=0）不参与轮次
            if (symbolFilter != null && !symbolFilter.isBlank() && !symbolFilter.equals(t.symbol())) continue;
            bySymbol.computeIfAbsent(t.symbol(), k -> new ArrayList<>()).add(t);
        }

        List<TradeRound> rounds = new ArrayList<>();
        for (Map.Entry<String, List<TradeRecord>> e : bySymbol.entrySet()) {
            String symbol = e.getKey();
            List<TradeRecord> ts = new ArrayList<>(e.getValue());
            ts.sort(Comparator.comparing(TradeRecord::entryDate)
                    .thenComparing(t -> t.tradeTime() != null ? t.tradeTime() : LocalTime.MIN));
            List<StrictRound> identified = identify(symbol, ts);
            if (identified.isEmpty()) continue;
            // P1（2026-10-03 自查修复）：日线**按 symbol 一次取全**——原实现「按 symbol 缓存、范围却按轮次算」，
            // 同一只票从第二轮起拿到的是**第一轮的范围** → 峰值浮盈/3 日表现等过程指标静默为 null（规则检查失效）。
            // 影响面：生产实测 37 只标的做过 ≥2 轮（000776 做过 7 轮）。
            LocalDate minStart = null;
            LocalDate maxEnd = null;
            for (StrictRound r : identified) {
                if (minStart == null || r.start().isBefore(minStart)) minStart = r.start();
                LocalDate e2 = r.end() != null ? r.end() : LocalDate.now();
                if (maxEnd == null || e2.isAfter(maxEnd)) maxEnd = e2;
            }
            Map<LocalDate, Candle> days = loadDays(symbol, minStart.minusDays(10), maxEnd.plusDays(10));
            for (StrictRound r : identified) {
                TradeRound tr = analyze(userId, symbol, r, days, peakMin, trapMin);
                if (tr != null) rounds.add(tr);
            }
        }
        rounds.sort(Comparator.comparing(TradeRound::end, Comparator.nullsLast(Comparator.reverseOrder())));
        int total = rounds.size();
        int capped = limit > 0 && total > limit ? limit : total;
        return new RoundsView(total, rounds.subList(0, capped));
    }

    // ── ① 轮次识别（口径 C）──

    private record StrictRound(String symbol, LocalDate start, LocalDate end,
                               List<TradeRecord> buys, List<TradeRecord> sells, boolean initial) {}

    private List<StrictRound> identify(String symbol, List<TradeRecord> ts) {
        List<StrictRound> strict = new ArrayList<>();
        LocalDate start = null;
        List<TradeRecord> buys = new ArrayList<>();
        List<TradeRecord> sells = new ArrayList<>();
        int run = 0;
        for (TradeRecord t : ts) {
            if (t.direction() == TradeDirection.BUY) {
                if (run == 0) {
                    start = t.entryDate();
                    buys = new ArrayList<>();
                    sells = new ArrayList<>();
                }
                run += t.volume();
                buys.add(t);
            } else {
                if (run == 0) {
                    // 卖出但无持仓 = 流水未覆盖的底仓（系统的「初始批次」）→ 单列、不做过程检查
                    strict.add(new StrictRound(symbol, t.entryDate(), t.entryDate(),
                            List.of(), List.of(t), true));
                    continue;
                }
                run -= t.volume();
                sells.add(t);
                if (run <= 0) {
                    run = 0;
                    strict.add(new StrictRound(symbol, start, t.entryDate(), List.copyOf(buys),
                            List.copyOf(sells), false));
                    buys = new ArrayList<>();
                    sells = new ArrayList<>();
                }
            }
        }
        if (!buys.isEmpty()) {
            strict.add(new StrictRound(symbol, start, null, List.copyOf(buys), List.copyOf(sells), false));
        }
        // 口径 C：同一交易日内「卖光 → 买回」合并为同一轮
        List<StrictRound> merged = new ArrayList<>();
        for (StrictRound r : strict) {
            StrictRound last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.end() != null && last.end().equals(r.start()) && !r.buys().isEmpty()) {
                List<TradeRecord> b = new ArrayList<>(last.buys());
                b.addAll(r.buys());
                List<TradeRecord> s = new ArrayList<>(last.sells());
                s.addAll(r.sells());
                // P1（独立审查 2026-10-03 修复）：合并后只要已有建仓流水，就不再是「初始批次」——
                // 原实现继承 last.initial()，导致同日「底仓卖出 → 买回」整轮被判初始而**直接丢弃**
                // （连 total 都不计）。
                merged.set(merged.size() - 1,
                        new StrictRound(symbol, last.start(), r.end(), b, s,
                                last.initial() && b.isEmpty()));
            } else {
                merged.add(r);
            }
        }
        return merged;
    }

    // ── ② 过程指标 + ③ 规则检查 ──

    /** 某交易日的成本 = 该日或之前**最后一个有买入日**的加权成本；早于首笔买入则用首笔成本。 */
    private static BigDecimal dayCostAt(java.util.TreeMap<LocalDate, BigDecimal> costByDay,
                                        LocalDate d, BigDecimal fallback) {
        Map.Entry<LocalDate, BigDecimal> e = costByDay.floorEntry(d);
        return e != null ? e.getValue() : fallback;
    }

    /** 取某标的在 [from, to] 的日线；失败降级为空表（过程指标如实为 null，**不编造**）。 */
    private Map<LocalDate, Candle> loadDays(String symbol, LocalDate from, LocalDate to) {        Map<LocalDate, Candle> m = new LinkedHashMap<>();
        try {
            for (Candle c : klineService.klineRange(symbol, from, to)) m.put(c.date(), c);
        } catch (RuntimeException ex) {
            log.warn("轮次复盘取日线失败（该标本的过程指标降级为 null）| symbol={} | {}", symbol, ex.getMessage());
        }
        return m;
    }

    private TradeRound analyze(String userId, String symbol, StrictRound r,
                               Map<LocalDate, Candle> days,
                               double peakMin, double trapMin) {
        if (r.initial() || r.buys().isEmpty()) return null;   // 初始批次无法做过程检查（无建仓流水）

        BigDecimal buyAmount = BigDecimal.ZERO;
        int buyVolume = 0;
        for (TradeRecord b : r.buys()) {
            BigDecimal fee = b.fee() != null ? b.fee() : BigDecimal.ZERO;
            buyAmount = buyAmount.add(b.price().multiply(BigDecimal.valueOf(b.volume()))).add(fee);
            buyVolume += b.volume();
        }
        if (buyVolume == 0) return null;
        BigDecimal avgCost = buyAmount.divide(BigDecimal.valueOf(buyVolume), 4, RoundingMode.HALF_UP);

        BigDecimal sellAmount = BigDecimal.ZERO;
        for (TradeRecord s : r.sells()) {
            BigDecimal fee = s.fee() != null ? s.fee() : BigDecimal.ZERO;
            sellAmount = sellAmount.add(s.price().multiply(BigDecimal.valueOf(s.volume()))).subtract(fee);
        }
        BigDecimal pnl = sellAmount.subtract(buyAmount);
        BigDecimal pnlPct = pct(pnl, buyAmount);

        String name = r.buys().get(0).name() != null && !r.buys().get(0).name().isBlank()
                ? r.buys().get(0).name() : symbol;
        LocalDate lastBuy = r.buys().get(r.buys().size() - 1).entryDate();

        LocalDate end = r.end();
        List<LocalDate> inRound = new ArrayList<>();
        for (LocalDate d : days.keySet()) {
            if (!d.isBefore(r.start()) && (end == null || !d.isAfter(end))) inRound.add(d);
        }
        inRound.sort(Comparator.naturalOrder());

        BigDecimal peakPct = null;
        LocalDate peakDate = null;
        BigDecimal troughPct = null;
        // P1-5（独立审查 2026-10-03 修复）：**当日成本**逐日推进，不再拿全轮摊薄后的平均成本去比每日 high/low——
        // 摊薄成本会把加仓前的价格算成浮盈 → R55（盈转亏）系统性多报。
        // 口径：某日成本 = 截至该日（含）全部买入的加权均价（含费）；卖出不改成本价（只改数量）。
        java.util.TreeMap<LocalDate, BigDecimal> costByDay = new java.util.TreeMap<>();
        BigDecimal cumAmt0 = BigDecimal.ZERO;
        int cumVol0 = 0;
        for (TradeRecord b : r.buys()) {
            BigDecimal fee = b.fee() != null ? b.fee() : BigDecimal.ZERO;
            cumAmt0 = cumAmt0.add(b.price().multiply(BigDecimal.valueOf(b.volume()))).add(fee);
            cumVol0 += b.volume();
            costByDay.put(b.entryDate(), cumAmt0.divide(BigDecimal.valueOf(cumVol0), 4, RoundingMode.HALF_UP));
        }
        BigDecimal firstCost = costByDay.isEmpty() ? avgCost : costByDay.firstEntry().getValue();
        for (LocalDate d : inRound) {
            Candle c = days.get(d);
            BigDecimal dayCost = dayCostAt(costByDay, d, firstCost);
            BigDecimal up = pct(BigDecimal.valueOf(c.high()).subtract(dayCost), dayCost);
            BigDecimal dn = pct(BigDecimal.valueOf(c.low()).subtract(dayCost), dayCost);
            if (peakPct == null || up.compareTo(peakPct) > 0) { peakPct = up; peakDate = d; }
            if (troughPct == null || dn.compareTo(troughPct) < 0) troughPct = dn;
        }

        // 加仓（第 2 笔起的每次买入）时是否被套：该日最低价 < 当时加权成本 ×(1 − trapMin%)
        int addOn = 0;
        int addOnTrapped = 0;
        BigDecimal cumAmt = BigDecimal.ZERO;
        int cumVol = 0;
        for (int i = 0; i < r.buys().size(); i++) {
            TradeRecord b = r.buys().get(i);
            if (i > 0) {
                addOn++;
                Candle c = days.get(b.entryDate());
                if (c != null && cumVol > 0) {
                    BigDecimal costThen = cumAmt.divide(BigDecimal.valueOf(cumVol), 4, RoundingMode.HALF_UP);
                    BigDecimal trapLine = costThen.multiply(BigDecimal.valueOf(1 - trapMin / 100.0));
                    if (BigDecimal.valueOf(c.low()).compareTo(trapLine) < 0) addOnTrapped++;
                }
            }
            BigDecimal fee = b.fee() != null ? b.fee() : BigDecimal.ZERO;
            cumAmt = cumAmt.add(b.price().multiply(BigDecimal.valueOf(b.volume()))).add(fee);
            cumVol += b.volume();
        }

        // R53：建仓完毕（最后一次买入）后第 3 个交易日相对成本
        // P3（独立审查 2026-10-03 修复）：限定在**轮内**（越过清仓日就是别的轮次的地盘）。
        List<LocalDate> after = new ArrayList<>();
        for (LocalDate d : days.keySet()) {
            if (d.isAfter(lastBuy) && (end == null || !d.isAfter(end))) after.add(d);
        }
        after.sort(Comparator.naturalOrder());
        BigDecimal d3Pct = null;
        if (after.size() >= 3) {
            Candle c3 = days.get(after.get(2));
            BigDecimal cost3 = dayCostAt(costByDay, after.get(2), firstCost);
            d3Pct = pct(BigDecimal.valueOf(c3.close()).subtract(cost3), cost3);
        }

        // R66：收盘跌破 R72 基准止损位（−4%）后是否当日未走
        // P2（独立审查 2026-10-03 修复）：止损线也按**当日成本**算（原来用全轮摊薄成本 → 首次跌破日失真，
        // 与 R69 的「当时成本」口径不一致）。
        LocalDate breachDate = null;
        for (LocalDate d : inRound) {
            Candle c = days.get(d);
            BigDecimal dayStop = dayCostAt(costByDay, d, firstCost)
                    .multiply(REVIEW_STOP_LOSS_RATIO).setScale(4, RoundingMode.HALF_UP);
            if (BigDecimal.valueOf(c.close()).compareTo(dayStop) < 0) { breachDate = d; break; }
        }
        Integer breachHoldDays = null;
        if (breachDate != null && end != null) {
            int n = 0;
            for (LocalDate d : inRound) if (!d.isBefore(breachDate)) n++;
            breachHoldDays = n;
        }

        List<RuleHit> hits = new ArrayList<>();
        if (end != null) {
            if (peakPct != null && peakPct.doubleValue() >= peakMin && pnl.signum() < 0) {
                hits.add(new RuleHit("R55", "盈转亏没走（曾浮盈 " + trim(peakPct) + "% 却亏损收场）",
                        "峰值 " + trim(peakPct) + "%@" + peakDate + " → 最终 " + trim(pnlPct) + "%"));
            }
            if (addOnTrapped > 0) {
                hits.add(new RuleHit("R69", "被套时加仓（R90 短线不加仓同源）",
                        addOnTrapped + " 次加仓发生在浮亏 >" + trim(BigDecimal.valueOf(trapMin)) + "% 时（共加仓 " + addOn + " 次）"));
            }
            if (breachHoldDays != null && breachHoldDays > 1) {
                hits.add(new RuleHit("R66", "收盘跌破止损位后未当日走（按当日成本 −4%，R72 取中值）",
                        "首次跌破 " + breachDate + " → 清仓 " + end + "，多扛 " + breachHoldDays + " 个交易日"));
            }
            if (d3Pct != null && d3Pct.signum() <= 0) {
                hits.add(new RuleHit("R53", "建仓完毕后 3 个交易日仍未脱离成本区",
                        "第 3 日 " + trim(d3Pct) + "%（建仓完毕 " + lastBuy + "）"));
            }
        }

        int holdDays = end != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(r.start(), end) : 0;
        return new TradeRound(symbol, name, r.start(), lastBuy, end, holdDays, inRound.size(),
                r.buys().size(), r.sells().size(), buyAmount.setScale(2, RoundingMode.HALF_UP),
                pnl.setScale(2, RoundingMode.HALF_UP), trim(pnlPct), trim(peakPct), peakDate,
                trim(troughPct), addOn, addOnTrapped, trim(d3Pct), hits);
    }

    private TradingRuleSettings safeRules(String userId) {
        try {
            TradingRuleSettings s = ruleSettingsRepository.findByUser(userId);
            if (s != null) return s;
        } catch (RuntimeException e) {
            log.warn("轮次复盘读规则包失败，用默认阈值 | userId={} | {}", userId, e.getMessage());
        }
        return TradingRuleSettings.defaults();
    }

    private static BigDecimal pct(BigDecimal delta, BigDecimal base) {
        if (base == null || base.signum() == 0) return BigDecimal.ZERO;
        return delta.multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal trim(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().scale() <= 0
                ? v.setScale(0, RoundingMode.HALF_UP) : v;
    }

    // ── 视图对象 ──

    /** 轮次总览：总数（未截断）+ 明细。 */
    public record RoundsView(int total, List<TradeRound> rounds) {}

    /**
     * 一轮完整交易（口径 C）。{@code end} 为 null = 尚未平仓（当前持仓）。
     * 所有百分比字段为百分数（+9.4 = 浮盈 9.4%），null = 数据不足（**不编造 0**）。
     */
    public record TradeRound(
            String symbol, String name, LocalDate start, LocalDate lastBuy, LocalDate end,
            int holdDays, int tradeDays, int buyCount, int sellCount,
            BigDecimal buyAmount, BigDecimal pnl, BigDecimal pnlPct,
            BigDecimal peakPct, LocalDate peakDate, BigDecimal troughPct,
            int addOnCount, int addOnTrappedCount, BigDecimal d3Pct,
            List<RuleHit> hits) {}

    /** 规则命中（只陈述事实：命中了哪条 + 过程数据）。 */
    public record RuleHit(String rule, String text, String data) {}
}
