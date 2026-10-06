package com.adaiadai.core.application;

import com.adaiadai.core.application.TradingRoundService.TradeRound;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import com.adaiadai.core.domain.trading.UserRule;
import com.adaiadai.core.infrastructure.storage.UserRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TradingUserRuleService — 规则集三态（候选 / 已认 / 自定义）与「从数据里长出来」的候选生成
 * （R-06 · blueprint §三 · design-final §2/§4.1，rules 批 2026-10-06）。
 *
 * <p><b>核心做法</b>（blueprint §三）：你的历史 → 描述性分析 → <b>候选规则（系统提出、每条带据）</b>
 * → 你勾选确认 / 你改 / 你直接写 → 成为你的规则。规则的第一条来路不是「写」，是「照」。
 *
 * <p><b>红线</b>：候选是<b>描述</b>（你实际在做什么）不是<b>建议</b>（你该怎么做）。五类候选全部
 * 从你自己的数据照出来，样本不足<b>宁可不出</b>（不硬凑）；没有规则的用户也能拿到候选
 * （验收 2）——<b>不套默认值</b>。
 *
 * <p><b>五类候选</b>（原料 = 笔视图，与 analytics 同一上游，口径不复制）：
 * <ol>
 *   <li>{@code cand-stoploss} 止损线：亏损笔的亏损幅度 75% 分位（≥5 笔亏损才有）</li>
 *   <li>{@code cand-short-overdue} 短线超期：平均持有天数向上取整（≥5 笔已平仓）</li>
 *   <li>{@code cand-giveback} 盈转亏：峰值 ≥ 阈值（用户 reviewPeakMinPct）但最终亏损（≥2 笔）</li>
 *   <li>{@code cand-trapped-addon} 被套不补仓：加仓时浮亏次数合计（≥3 次）</li>
 *   <li>{@code cand-loss-hold} 亏损不扛：亏损笔平均持有 &gt; 盈利笔 × 1.5 且差 ≥ 2 天（各 ≥3 笔）</li>
 * </ol>
 *
 * <p><b>刷新语义（永不覆盖）</b>：重新生成时——仍是候选的条目<b>刷新</b>（数据变了数字跟着变）；
 * 已认 / 自定义<b>不动</b>（用户的东西永不覆盖）；新数据不再支持的候选<b>移除</b>；
 * 弃掉的（{@link UserRule.UserRuleState#DISMISSED 墓碑}）<b>不复活</b>——用户划掉的不再回来（P1-交易93）。
 */
@Service
public class TradingUserRuleService {

    private static final Logger log = LoggerFactory.getLogger(TradingUserRuleService.class);

    private final TradingRoundService roundService;
    private final TradingRuleSettingsPort ruleSettings;
    private final UserRuleRepository repository;

    public TradingUserRuleService(TradingRoundService roundService,
                                  TradingRuleSettingsPort ruleSettings,
                                  UserRuleRepository repository) {
        this.roundService = roundService;
        this.ruleSettings = ruleSettings;
        this.repository = repository;
    }

    // ── 读取与三态操作 ──

    /** 规则集全列表（三态分组）。 */
    public RuleListView list(String userId) {
        List<UserRule> all = repository.findByUser(userId);
        List<UserRule> candidates = new ArrayList<>();
        List<UserRule> accepted = new ArrayList<>();
        List<UserRule> custom = new ArrayList<>();
        for (UserRule r : all) {
            switch (r.state()) {
                case CANDIDATE -> candidates.add(r);
                case ACCEPTED -> accepted.add(r);
                case CUSTOM -> custom.add(r);
                // P1-交易93：弃掉的是墓碑——留痕不删，但不再出现在任何分组（total 也不计）
                case DISMISSED -> { }
            }
        }
        return new RuleListView(candidates.size() + accepted.size() + custom.size(),
                candidates, accepted, custom);
    }

    /** 认下（text 非空 = 认下时改，blueprint「你勾选 / 改」都算认下）。 */
    public UserRule accept(String userId, String id, String text) {
        UserRule r = require(userId, id);
        if (r.state() == UserRule.UserRuleState.CUSTOM) {
            throw new IllegalArgumentException("这条是你自己写的，不用认");
        }
        UserRule accepted = r.accepted(text, now());
        repository.upsert(userId, accepted);
        return accepted;
    }

    /** 改文本（仅已认 / 自定义；候选要先认下——认下时可直接带 text 改）。 */
    public UserRule edit(String userId, String id, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 必填（改成什么）");
        }
        UserRule r = require(userId, id);
        if (r.isCandidate()) {
            throw new IllegalArgumentException("候选要先认下再改（认下时可直接带 text 一起改）");
        }
        UserRule edited = r.edited(text, now());
        repository.upsert(userId, edited);
        return edited;
    }

    /** 弃掉一条（<b>墓碑</b>——下次生成不复活；幂等：不存在 / 已弃的也算成功，前端状态漂移不报错）。
     *  <p>P1-交易93（2026-10-06）：原实现直接删条目，而候选按固定 id 全量重生成 ⇒ 划掉的下次刷新又出现。 */
    public void dismiss(String userId, String id) {
        UserRule r = find(repository.findByUser(userId), id);
        if (r == null || r.state() == UserRule.UserRuleState.DISMISSED) return;
        repository.upsert(userId, r.dismissed(now()));
    }

    /** 自己写一条（三态之自定义——R-06 三来源之「自建」）。 */
    public UserRule createCustom(String userId, String text, Map<String, Object> params) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 必填（写一条你自己的规则）");
        }
        UserRule r = UserRule.custom("usr-" + UUID.randomUUID().toString().substring(0, 8),
                text.trim(), params, now());
        repository.upsert(userId, r);
        return r;
    }

    // ── 候选生成（从数据里长出来）──

    /**
     * 重新生成候选（从当前数据照一遍）并落盘，返回刷新后的全列表。
     * 语义见类 javadoc：候选刷新 · 已认/自定义不动 · 不再支持的候选移除。
     */
    public RuleListView generateCandidates(String userId) {
        List<TradeRound> all = roundService.rounds(userId, null, Integer.MAX_VALUE).rounds();
        List<TradeRound> closed = closedOf(all);
        String now = now();

        List<UserRule> generated = new ArrayList<>();
        generateStoploss(closed, now).ifPresent(generated::add);
        generateShortOverdue(closed, now).ifPresent(generated::add);
        generateGiveback(userId, closed, now).ifPresent(generated::add);
        generateTrappedAddon(all, now).ifPresent(generated::add);
        generateLossHold(closed, now).ifPresent(generated::add);

        // 合并：候选刷新 / 已认自定义不动 / 不再支持的老候选移除
        List<UserRule> existing = repository.findByUser(userId);
        List<String> freshIds = new ArrayList<>();
        for (UserRule c : generated) {
            freshIds.add(c.id());
            UserRule ex = find(existing, c.id());
            if (ex == null) {
                repository.upsert(userId, c);
            } else if (ex.isCandidate()) {
                repository.upsert(userId, ex.refreshed(c, now));
            }
            // ex 是 ACCEPTED / CUSTOM → 永不覆盖；DISMISSED → 墓碑，不复活（P1-交易93）
        }
        for (UserRule ex : existing) {
            if (ex.isCandidate() && !freshIds.contains(ex.id())) {
                repository.remove(userId, ex.id());   // 数据不再支持这条候选——如实撤下
            }
        }
        log.info("候选生成 | userId={} | 新生成 {} 条 | 规则集共 {} 条",
                userId, generated.size(), repository.findByUser(userId).size());
        return list(userId);
    }

    /** 1) 止损线：亏损笔的亏损幅度 75% 分位（nearest-rank），0.5 网格、clamp [1,10]；≥5 笔亏损才出。 */
    private java.util.Optional<UserRule> generateStoploss(List<TradeRound> closed, String now) {
        List<TradeRound> losses = new ArrayList<>();
        for (TradeRound r : closed) {
            if (r.pnlPct() != null && r.pnlPct().signum() < 0) losses.add(r);
        }
        if (losses.size() < 5) return java.util.Optional.empty();

        List<Double> abs = new ArrayList<>();
        for (TradeRound r : losses) abs.add(r.pnlPct().abs().doubleValue());
        abs.sort(Double::compareTo);
        int idx = Math.max(0, Math.min(abs.size() - 1, (int) Math.ceil(0.75 * abs.size()) - 1));
        double p75 = Math.round(abs.get(idx) * 2) / 2.0;
        double x = Math.max(1.0, Math.min(10.0, p75));
        long within = abs.stream().filter(v -> v <= x).count();

        TradeRound worst = null;
        for (TradeRound r : losses) {
            if (worst == null || r.pnlPct().compareTo(worst.pnlPct()) < 0) worst = r;
        }
        List<String> facts = new ArrayList<>();
        facts.add("亏损 " + losses.size() + " 笔，其中 " + within + " 笔亏得不超过 " + fmt(x) + "%");
        if (worst != null) {
            facts.add("最狠一笔亏 " + fmt(Math.abs(worst.pnlPct().doubleValue())) + "%（"
                    + worst.symbol() + " " + worst.start() + "）");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pct", -x);
        return java.util.Optional.of(UserRule.candidate("cand-stoploss",
                "止损：亏到 " + fmt(-x) + "% 就走（你 " + losses.size() + " 次亏损里 "
                        + within + " 次亏得不超过 " + fmt(x) + "%）",
                params,
                new UserRule.Evidence(
                        "从你 " + losses.size() + " 笔亏损的亏损幅度统计（75% 分位——你实际多数就当这么办）",
                        facts, ids(losses), spanDates(losses)),
                now));
    }

    /** 2) 短线超期：平均持有天数向上取整；≥5 笔已平仓且平均 ≥1.5 天才出。 */
    private java.util.Optional<UserRule> generateShortOverdue(List<TradeRound> closed, String now) {
        if (closed.size() < 5) return java.util.Optional.empty();
        double avg = 0;
        int maxHold = 0;
        TradeRound longest = null;
        for (TradeRound r : closed) {
            avg += r.holdDays();
            if (r.holdDays() > maxHold) { maxHold = r.holdDays(); longest = r; }
        }
        avg = avg / closed.size();
        if (avg < 1.5) return java.util.Optional.empty();
        int x = (int) Math.ceil(avg);

        List<String> facts = new ArrayList<>();
        facts.add("已平仓 " + closed.size() + " 笔，平均持有 " + fmt1(avg) + " 天");
        if (longest != null) {
            facts.add("持有最长 " + maxHold + " 天（" + longest.symbol() + " " + longest.start() + "）");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("days", x);
        return java.util.Optional.of(UserRule.candidate("cand-short-overdue",
                "短线：持有超过 " + x + " 天算超期（你平均持有 " + fmt1(avg) + " 天）",
                params,
                new UserRule.Evidence(
                        "从你 " + closed.size() + " 笔已平仓的持有天数统计（平均 " + fmt1(avg) + " 天）",
                        facts, ids(closed), spanDates(closed)),
                now));
    }

    /** 3) 盈转亏：峰值 ≥ 阈值（用户 reviewPeakMinPct，默认 3%）但最终亏损；≥2 笔才出。 */
    private java.util.Optional<UserRule> generateGiveback(String userId, List<TradeRound> closed, String now) {
        double peakMin = 3.0;
        try {
            TradingRuleSettings s = ruleSettings.findByUser(userId);
            if (s != null) peakMin = s.reviewPeakMinPct();
        } catch (RuntimeException e) {
            log.warn("规则阈值读取失败（按默认 3% 照候选）| userId={} | {}", userId, e.getMessage());
        }
        List<TradeRound> givebacks = new ArrayList<>();
        for (TradeRound r : closed) {
            if (r.peakPct() != null && r.peakPct().doubleValue() >= peakMin
                    && r.pnl() != null && r.pnl().signum() < 0) {
                givebacks.add(r);
            }
        }
        if (givebacks.size() < 2) return java.util.Optional.empty();

        List<String> facts = new ArrayList<>();
        facts.add(givebacks.size() + " 笔赚过后又扛成亏（峰值 ≥ " + fmt(peakMin) + "% 却没在赚过时走）");
        TradeRound first = givebacks.get(0);
        facts.add("例：" + first.symbol() + " " + first.start() + " 峰值 +"
                + fmt(first.peakPct().doubleValue()) + "% 最终 "
                + fmt(first.pnlPct().doubleValue()) + "%");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("peakPct", peakMin);
        return java.util.Optional.of(UserRule.candidate("cand-giveback",
                "盈转亏：赚过 " + fmt(peakMin) + "% 后回落到成本就走（你有 " + givebacks.size()
                        + " 笔赚过又扛成亏）",
                params,
                new UserRule.Evidence(
                        "从你「峰值 ≥ " + fmt(peakMin) + "%（你设的『算赚过』线）但最终亏损」的笔统计",
                        facts, ids(givebacks), spanDates(givebacks)),
                now));
    }

    /** 4) 被套不补仓：加仓时浮亏总次数 ≥3 才出。 */
    private java.util.Optional<UserRule> generateTrappedAddon(List<TradeRound> all, String now) {
        int trapped = 0;
        List<TradeRound> rounds = new ArrayList<>();
        for (TradeRound r : all) {
            if (r.addOnTrappedCount() > 0) {
                trapped += r.addOnTrappedCount();
                rounds.add(r);
            }
        }
        if (trapped < 3) return java.util.Optional.empty();

        List<String> facts = new ArrayList<>();
        facts.add("共 " + trapped + " 次在浮亏时加仓，涉及 " + rounds.size() + " 笔");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("maxTrappedAddOns", 0);
        return java.util.Optional.of(UserRule.candidate("cand-trapped-addon",
                "被套不补仓（你有 " + trapped + " 次在浮亏时加过仓）",
                params,
                new UserRule.Evidence(
                        "从你全部笔的「加仓时浮亏超过你设的被套线」统计",
                        facts, ids(rounds), spanDates(rounds)),
                now));
    }

    /** 5) 亏损不扛：亏损笔平均持有 > 盈利笔 × 1.5 且差 ≥ 2 天（各 ≥3 笔）；X = 盈利笔平均向上取整。 */
    private java.util.Optional<UserRule> generateLossHold(List<TradeRound> closed, String now) {
        List<TradeRound> losses = new ArrayList<>();
        List<TradeRound> wins = new ArrayList<>();
        for (TradeRound r : closed) {
            if (r.pnlPct() == null) continue;
            if (r.pnlPct().signum() < 0) losses.add(r);
            else if (r.pnlPct().signum() > 0) wins.add(r);
        }
        if (losses.size() < 3 || wins.size() < 3) return java.util.Optional.empty();

        double lossAvg = 0;
        for (TradeRound r : losses) lossAvg += r.holdDays();
        lossAvg = lossAvg / losses.size();
        double winAvg = 0;
        for (TradeRound r : wins) winAvg += r.holdDays();
        winAvg = winAvg / wins.size();

        if (!(lossAvg > winAvg * 1.5) || lossAvg - winAvg < 2) return java.util.Optional.empty();
        int x = Math.max(1, (int) Math.ceil(winAvg));

        List<String> facts = new ArrayList<>();
        facts.add("赚的笔平均 " + fmt1(winAvg) + " 天走 / 亏的笔平均 " + fmt1(lossAvg) + " 天");
        facts.add("赚 " + wins.size() + " 笔 / 亏 " + losses.size() + " 笔");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("days", x);
        return java.util.Optional.of(UserRule.candidate("cand-loss-hold",
                "亏损不扛过 " + x + " 天（你赚的笔平均 " + fmt1(winAvg) + " 天走、亏的笔平均 "
                        + fmt1(lossAvg) + " 天）",
                params,
                new UserRule.Evidence(
                        "从你盈亏笔的持有天数对照（亏的扛得明显更久才照得出这条）",
                        facts, ids(closed), spanDates(closed)),
                now));
    }

    // ── 工具 ──

    private UserRule require(String userId, String id) {
        UserRule r = find(repository.findByUser(userId), id);
        // P1-交易93：墓碑视同不存在——弃掉的不能再认 / 改（否则等于静默复活）
        if (r == null || r.state() == UserRule.UserRuleState.DISMISSED) {
            throw new IllegalArgumentException("找不到这条规则：" + id + "（可能已弃掉）");
        }
        return r;
    }

    private static UserRule find(List<UserRule> rules, String id) {
        for (UserRule r : rules) if (r.id().equals(id)) return r;
        return null;
    }

    private static List<TradeRound> closedOf(List<TradeRound> all) {
        List<TradeRound> out = new ArrayList<>();
        for (TradeRound r : all) {
            if (!r.unresolved() && r.end() != null && r.pnlPct() != null) out.add(r);
        }
        return out;
    }

    private static List<String> ids(List<TradeRound> rs) {
        List<String> out = new ArrayList<>();
        for (TradeRound r : rs) if (r.id() != null) out.add(r.id());
        return out;
    }

    private static List<String> spanDates(List<TradeRound> rs) {
        LocalDate min = null;
        LocalDate max = null;
        for (TradeRound r : rs) {
            if (r.start() != null && (min == null || r.start().isBefore(min))) min = r.start();
            if (r.end() != null && (max == null || r.end().isAfter(max))) max = r.end();
        }
        List<String> out = new ArrayList<>();
        if (min != null) out.add(min.toString());
        if (max != null && !max.equals(min)) out.add(max.toString());
        return out;
    }

    /** 整数去掉小数点（4.0 → "4"），半档保留（3.5 → "3.5"）。 */
    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    /** 1 位小数（6.5 → "6.5"；6.0 → "6"）。 */
    private static String fmt1(double v) {
        double r = Math.round(v * 10) / 10.0;
        return fmt(r);
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }

    // ── 视图对象 ──

    /** 规则集全列表（三态分组；前端一次拿全）。 */
    public record RuleListView(int total, List<UserRule> candidates,
                               List<UserRule> accepted, List<UserRule> custom) {}
}
