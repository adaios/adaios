package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TradingDecisionNarrator — 把「一个买点 / 一个卖点」渲染成**四要素铁证**的人话（RFC `20260922` B 批）。
 *
 * <p>用户 2026-09-21 原话：「需要阿呆提醒我，给我意见，**尤其给我铁证，通过我之前的操作**」。
 * A 批（v3.82）把证据的**数据**备齐了（{@link TradingEvidenceService} 的历史统计与规则原文、
 * A3 的建议依据快照）；本类负责 B 批的那一半：**在决策时点把证据摆成一段能核对的话**。
 *
 * <p>四要素（RFC §3.4）在本类的落点：
 * <table border="1">
 *   <tr><th>#</th><th>要素</th><th>本类怎么给</th></tr>
 *   <tr><td>①</td><td>本人历史操作统计</td><td>{@link TradingEvidenceService#historyStats} 按形态 / 盈亏区间分桶；
 *       <b>样本 &lt; 5 就直说「样本还不够」</b>，绝不拿一两次巧合当规律</td></tr>
 *   <tr><td>②</td><td>具体数字证据链</td><td>最新收盘价（**带口径与数据日期**——买点块在 09:15，写「昨收」不写「现价」）/ 命中信号（量比、KDJ）/ 用户自己的规则参数阈值</td></tr>
 *   <tr><td>③</td><td>规则依据原文</td><td>{@link TradingEvidenceService#ruleTextOf} **逐字**引用；
 *       取不到原文 → 这一条**不发**（宁可不给，也不复述成「大概是这个意思」）</td></tr>
 *   <tr><td>④</td><td>可回溯可追责</td><td>推送正文给**位置**（成本 / 止损 / 上次了结习惯位）；留痕实体由
 *       {@code AdviceEntry.basis} 承担（A3，落 {@code advice-history/}），两者合起来才是「可追责」</td></tr>
 * </table>
 *
 * <p><b>缺证据就闭嘴</b>（RFC §八 1 的验收红线）：{@link #buyPointBlock} / {@link #sellPointBlock}
 * 返回 {@link Optional#empty()} 时，调用方**不得**自己拼一段话兜底——那正是「假铁证」的来源。
 * 唯一允许的降级是**如实说缺什么**（如「样本还不够」「这只我没有你的历史记录」），
 * 因为「不知道」本身也是诚实的证据状态，而不是编一个数字。
 */
@Slf4j
@Service
public class TradingDecisionNarrator {

    /**
     * 买点形态 → 规则编号（③ 用）。
     *
     * <p>只映射规则库里**真有条目**的形态：B1 → R33（B1三段条件）、B2 → R39（规则库中唯一以
     * B2 为主语的条目）、SB1 → R46（SB1可赌一次但严格止损）。库中没有的形态（如 B3）**不编**，
     * 于是那类命中不会出现在推送里——宁可少说一条，也不给一条没有出处的「规则依据」。
     */
    static final Map<String, Integer> BUY_POINT_RULE = Map.of(
            "B1", 33,
            "B2", 39,
            "SB1", 46);

    /** ④「止损习惯位」的最小样本（比 ① 的正式统计门槛松：这里只是给一个参考位，标注了是习惯）。 */
    static final int HABIT_MIN_SAMPLE = 3;

    private final TradingEvidenceService evidenceService;
    private final SoldTradeRepository soldTradeRepository;
    private final TradingRuleSettingsRepository settingsRepository;

    public TradingDecisionNarrator(TradingEvidenceService evidenceService,
                                   SoldTradeRepository soldTradeRepository,
                                   TradingRuleSettingsRepository settingsRepository) {
        this.evidenceService = evidenceService;
        this.soldTradeRepository = soldTradeRepository;
        this.settingsRepository = settingsRepository;
    }

    /** 渲染结果：{@code text} 是可直接进推送正文的一段（已带缩进与换行），{@code ruleRefs} 是引用的规则号。 */
    public record Block(String text, List<String> ruleRefs) {}

    // ── 买点（RFC §3.1）────────────────────────────────────────────────

    /**
     * 一条买点机会的四要素块。返回 empty 的三种情况（调用方**不要**兜底）：
     * <ul>
     *   <li>行情拿不到（② 无从谈起，B4 显式降级）</li>
     *   <li>该形态在规则库里没有可逐字引用的原文（③ 缺）</li>
     * </ul>
     */
    public Optional<Block> buyPointBlock(String userId, WatchlistBuyPointService.WatchBuyPoint hit,
                                         MarketData quote) {
        if (hit == null || quote == null || quote.price() == null) return Optional.empty();
        Integer ruleNo = BUY_POINT_RULE.get(hit.buyPoint());
        if (ruleNo == null) {
            log.info("买点四要素：形态 {} 在规则库无可引用原文，本条不发 | userId={} | symbol={}",
                    hit.buyPoint(), userId, hit.symbol());
            return Optional.empty();
        }
        Optional<TradingEvidenceService.RuleText> rule = evidenceService.ruleTextOf("R" + ruleNo);
        if (rule.isEmpty()) {
            log.warn("买点四要素：R{} 原文取不到，本条不发（不编规则）| userId={} | symbol={}",
                    ruleNo, userId, hit.symbol());
            return Optional.empty();
        }
        BigDecimal price = quote.price();
        StringBuilder sb = new StringBuilder();
        // 口径（早盘 09:15 未开盘，行情接口给的是**上一交易日收盘**）：写「昨收」而不是「现价」——
        // 与持仓段同一口径；再加数据日期，盘后复用也不会含糊（RFC 20260918「展示必标口径」铁律）。
        sb.append("· ").append(hit.name()).append("（").append(hit.symbol()).append("） 昨收 ")
                .append(fmt(price));
        if (hit.dataDate() != null) sb.append("（数据到 ").append(hit.dataDate()).append("）");
        sb.append("\n");
        sb.append("  ① 你的历史：").append(buyHistoryLine(userId, hit.buyPoint())).append("\n");
        sb.append("  ② 证据：").append(buyNumbersLine(userId, hit)).append("\n");
        sb.append("  ③ 规则：").append(ruleLine(rule.get())).append("\n");
        sb.append("  ④ 位置：").append(stopHabitLine(userId, price));
        return Optional.of(new Block(sb.toString(), List.of("R" + ruleNo)));
    }

    /** ① 按**该形态**取用户自己的历史（样本不足如实说；没有记录也如实说）。 */
    private String buyHistoryLine(String userId, String form) {
        try {
            TradingEvidenceService.HistoryStats stats =
                    evidenceService.historyStats(userId, TradingEvidenceService.Dimension.BUY_POINT);
            Optional<TradingEvidenceService.HistoryBucket> mine = stats.buckets().stream()
                    .filter(b -> form.equals(b.label())).findFirst();
            if (mine.isEmpty() || mine.get().count() == 0) {
                return "你还没在「" + form + "」上买过——这条没有你的历史可对照";
            }
            TradingEvidenceService.HistoryBucket b = mine.get();
            if (!b.sufficient()) {
                return String.format("你在「%s」上的样本还不够（%d 次，满 %d 次我才给统计）",
                        form, b.count(), TradingEvidenceService.MIN_SAMPLE);
            }
            return String.format("你过去 %d 次在「%s」买入，%d 次盈利、平均 %+.1f%%、平均持 %d 天",
                    b.count(), form, b.wins(), b.avgPnlPct(), Math.round(b.avgHoldDays()));
        } catch (RuntimeException e) {
            log.warn("买点四要素①取数失败（如实说取不到，不编）| userId={} | {}", userId, e.getMessage());
            return "这次我没取到你的历史统计";
        }
    }

    /**
     * ② 引擎给出的命中信号 + 用户自己的参数阈值（都是可当场核对的数字）。
     * 价格写在标题行（带口径与数据日期），这里不重复——② 是「证据链」，不是把数字堆第二遍。
     */
    private String buyNumbersLine(String userId, WatchlistBuyPointService.WatchBuyPoint hit) {
        List<String> parts = new ArrayList<>();
        if (hit.signals() != null) parts.addAll(hit.signals());
        try {
            TradingRuleSettings s = settingsRepository.findByUser(userId);
            if ("B2".equals(hit.buyPoint())) {
                parts.add("你的参数：放量 ≥ " + trim(s.buyVolumeSurge()) + " 倍");
            } else {
                parts.add("你的参数：KDJ.J < " + trim(s.buyKdjLow())
                        + " · 缩量 < " + trim(s.buyShrinkRatio()) + " 倍");
            }
        } catch (RuntimeException e) {
            log.warn("买点四要素②规则参数读取失败（省略该段）| userId={} | {}", userId, e.getMessage());
        }
        return String.join(" · ", parts);
    }

    /**
     * ④ 位置（买点票通常还没持仓）＝ 用户自己的**止损习惯位**：
     * 从清仓回合里统计他过去亏损了结的平均幅度——用他自己的习惯说话，而不是我们替他定一个 −7%。
     * 样本不足或从未亏损了结 → 如实说「位置我说不了」，不编。
     */
    private String stopHabitLine(String userId, BigDecimal price) {
        List<SoldTrade> losers = losersOf(userId);
        if (losers.size() < HABIT_MIN_SAMPLE) {
            return "你的亏损了结记录还太少（" + losers.size() + " 次），位置我先不说——"
                    + "进场前把止损位定下来（R38 要求先算盈亏比）";
        }
        double avg = losers.stream().mapToDouble(SoldTrade::holdPnlPct).average().orElse(0);
        BigDecimal stop = price.multiply(BigDecimal.valueOf(1 + avg / 100));
        return String.format("按你自己的止损习惯（%d 次亏损了结、平均 %+.1f%%），这只见位约 %s",
                losers.size(), avg, fmt(stop));
    }

    // ── 卖点（RFC §3.2）────────────────────────────────────────────────

    /**
     * 一条持仓的四要素块（尾盘卖点）。
     *
     * @param action     引擎判定的动作（如「清仓（R66）」）——确定性判定，不由 LLM 生成
     * @param ruleRefs   该动作引用的规则号（③ 逐字引用；一个都取不到 → 本条不发）
     */
    public Optional<Block> sellPointBlock(String userId, Position p, MarketData quote,
                                          BigDecimal positionPercent, String action,
                                          List<String> ruleRefs) {
        BigDecimal price = quote != null && quote.price() != null ? quote.price() : null;
        if (price == null) return Optional.empty(); // B4：没有当日价，绝不拿旧值当今天
        List<TradingEvidenceService.RuleText> rules = new ArrayList<>();
        if (ruleRefs != null) {
            for (String ref : ruleRefs) {
                evidenceService.ruleTextOf(ref).ifPresent(rules::add);
            }
        }
        if (ruleRefs != null && !ruleRefs.isEmpty() && rules.isEmpty()) {
            log.warn("卖点四要素：规则原文全取不到，本条不发（不编规则）| userId={} | symbol={} | refs={}",
                    userId, p.symbol(), ruleRefs);
            return Optional.empty();
        }
        BigDecimal pnlPct = pnlPercent(p, price);
        StringBuilder sb = new StringBuilder();
        sb.append("· ").append(p.name()).append("（").append(p.symbol()).append("） 现价 ").append(fmt(price))
                .append("（今日 ")
                .append(quote.changePercent() != null ? signed(quote.changePercent()) + "%" : "—")
                .append("） → ").append(action).append("\n");
        sb.append("  ① 你的历史：").append(sellHistoryLine(userId, pnlPct)).append("\n");
        sb.append("  ② 证据：").append(sellNumbersLine(p, price, positionPercent)).append("\n");
        if (!rules.isEmpty()) {
            sb.append("  ③ 规则：").append(rules.stream().map(TradingDecisionNarrator::ruleLine)
                    .reduce((a, b) -> a + " ｜ " + b).orElse("")).append("\n");
        }
        sb.append("  ④ 位置：").append(sellPositionLine(p, price, pnlPct));
        return Optional.of(new Block(sb.toString(), ruleRefs == null ? List.of() : ruleRefs));
    }

    /**
     * ① 按这笔**当前所处的盈亏区间**取历史（如 +6.2% → 「+5~10%」桶）：
     * 「你过去 N 次在这个区间了结，平均 …」——贴着他自己的操作说话（RFC §3.2 示例的同一形态）。
     */
    private String sellHistoryLine(String userId, BigDecimal pnlPct) {
        if (pnlPct == null) return "这笔的成本口径不完整（负/零成本），我算不出盈亏区间";
        String label = TradingEvidenceService.pnlBucket(pnlPct.doubleValue());
        try {
            TradingEvidenceService.HistoryStats stats =
                    evidenceService.historyStats(userId, TradingEvidenceService.Dimension.PNL_BUCKET);
            Optional<TradingEvidenceService.HistoryBucket> mine = stats.buckets().stream()
                    .filter(b -> label.equals(b.label())).findFirst();
            if (mine.isEmpty() || mine.get().count() == 0) {
                return "你还没在「" + label + "」这个区间了结过——这条没有你的历史可对照";
            }
            TradingEvidenceService.HistoryBucket b = mine.get();
            if (!b.sufficient()) {
                return String.format("你在「%s」区间的样本还不够（%d 次，满 %d 次我才给统计）",
                        label, b.count(), TradingEvidenceService.MIN_SAMPLE);
            }
            return String.format("你过去 %d 次在「%s」了结，%d 次盈利、平均 %+.1f%%、平均持 %d 天",
                    b.count(), label, b.wins(), b.avgPnlPct(), Math.round(b.avgHoldDays()));
        } catch (RuntimeException e) {
            log.warn("卖点四要素①取数失败（如实说取不到，不编）| userId={} | {}", userId, e.getMessage());
            return "这次我没取到你的历史统计";
        }
    }

    /** ② 成本 / 持仓占比 / 止损位 / 今日涨跌——都是能当场对照的数字。 */
    private String sellNumbersLine(Position p, BigDecimal price, BigDecimal positionPercent) {
        List<String> parts = new ArrayList<>();
        parts.add("成本 " + fmt(p.avgCost()));
        if (positionPercent != null) parts.add("占比 " + fmt(positionPercent) + "%");
        parts.add("止损 " + (p.effectiveStopLoss() != null ? fmt(p.effectiveStopLoss()) : "未设置"));
        if (p.quantity() > 0) parts.add("持有 " + p.quantity() + " 股");
        return String.join(" · ", parts);
    }

    /** ④ 位置：这笔现在了结大约什么结果 + 现价离止损位多远。 */
    private String sellPositionLine(Position p, BigDecimal price, BigDecimal pnlPct) {
        List<String> parts = new ArrayList<>();
        if (pnlPct != null) {
            parts.add("这笔若现在了结，约 " + signed(pnlPct) + "%（未扣手续费）");
        }
        if (p.effectiveStopLoss() != null && p.effectiveStopLoss().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal gap = price.subtract(p.effectiveStopLoss())
                    .divide(p.effectiveStopLoss(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            parts.add("距你的止损位 " + fmt(p.effectiveStopLoss()) + " 还有 " + signed(gap) + "%");
        }
        if (parts.isEmpty()) parts.add("这只没有成本和止损位，位置我说不了");
        return String.join(" · ", parts);
    }

    // ── 公共小工具 ────────────────────────────────────────────────────

    /** ③ 逐字原文：{@code 《标题》R66 原文「……」}——引用的是用户自己规则库里的字，不是我们的复述。 */
    static String ruleLine(TradingEvidenceService.RuleText r) {
        return "《" + r.title() + "》R" + r.number() + " 原文「" + r.detail() + "」";
    }

    /** 亏损了结的回合（④ 止损习惯位的数据源）。 */
    private List<SoldTrade> losersOf(String userId) {
        try {
            return soldTradeRepository.findAll(userId).stream()
                    .filter(t -> t.holdPnlPct() < 0)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("清仓回合读取失败（④ 位置降级）| userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    private static BigDecimal pnlPercent(Position p, BigDecimal price) {
        if (p.avgCost() == null || p.avgCost().compareTo(BigDecimal.ZERO) <= 0) return null;
        return price.subtract(p.avgCost())
                .divide(p.avgCost(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    static String fmt(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "—";
    }

    private static String signed(BigDecimal v) {
        if (v == null) return "—";
        String s = fmt(v);
        return v.compareTo(BigDecimal.ZERO) > 0 ? "+" + s : s;
    }

    private static String trim(double v) {
        return new BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString();
    }
}
