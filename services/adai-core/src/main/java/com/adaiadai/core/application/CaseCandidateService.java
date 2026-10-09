package com.adaiadai.core.application;

import com.adaiadai.core.application.TradingRoundService.RuleHit;
import com.adaiadai.core.application.TradingRoundService.TradeRound;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.UserRule;
import com.adaiadai.core.domain.trading.cases.CaseCandidate;
import com.adaiadai.core.domain.trading.cases.CaseCandidateRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.UserRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * CaseCandidateService — 案例候选（2026-10-08，交易插件 UI/UX 重做批 ③「案例候选」）。
 *
 * <p><b>回答什么问题</b>：案例区的上半区——「<b>从你的记录里长出来的</b> —— 我不替你定，
 * 你认了才算」（设计稿 uiux-discovery §十一；原型 mockups/trading-web-full.html 案例屏）。
 * 下半区「已经收下的」= {@link CaseCandidateRepository} 里你认过的条目。
 *
 * <p><b>候选池（只从数据里照 —— 与 R-06 候选规则同一条线，不硬凑）</b>：
 * <ul>
 *   <li><b>卖点类</b>（从清仓长）：清仓笔「卖掉之后到现在」——口径复用
 *       {@link SoldAfterCloseService}（卖出日收盘 → 最新收盘），本类不重算。</li>
 *   <li><b>买点类</b>（从轮长）：轮「买入之后到现在」——基准 = 买点日（或后第一根）
 *       K 线收盘，最新 = 区间最后一根收盘（收盘对收盘，同卖点类口径；不用流水成交价——
 *       import/flow 双轨，收盘是统一的、每笔都拿得到的基准）。</li>
 *   <li><b>显著性</b>：|变化| ≥ {@value #SIGNIFICANCE_PCT}% 才算（不硬凑）；四个结果方向
 *       （买成功 / 买失败 / 卖走早 / 卖对）各取幅度最大的 {@value #PER_OUTCOME_MAX} 条，
 *       一次最多 8 条。</li>
 *   <li>买点类先按轮盈亏粗筛每方向 {@value #BUY_PRESELECT} 条再拉 K 线（控成本——
 *       拉 K 线只为算 MA5 与买后涨跌；最终仍按买后涨跌重排取 top）。</li>
 * </ul>
 *
 * <p><b>规则对照（第二层——能机械对照才贴，贴不上就只摆事实）</b>：
 * <ul>
 *   <li>来源①<b>你写的规则</b>（user-rules 的已认 / 自定义，按参数形状嗅探）——
 *       止损（{@code pct}）/ 盈转亏（{@code peakPct}）/ 被套不补仓（{@code maxTrappedAddOns}）/
 *       超期类（{@code days}；{@code cand-loss-hold} 只判亏损笔）。<b>SUPPORT 只出现在
 *       止损规则上</b>（「亏在线内平掉」是唯一机械可判的「做到了」）；其余全是对照出「没做到」。</li>
 *   <li>来源②<b>轮自带的引擎命中</b>（R55 / R69 / R66 / R53，由 {@link TradingRoundService}
 *       算好的「没做到」型事实）——比「你写的规则」次要（先看你认过的条文）。</li>
 *   <li>对照不上 → {@code ruleRel=null}：卡片只摆走势事实，<b>不硬编一条规则出来</b>。</li>
 * </ul>
 *
 * <p><b>三态与幂等</b>（{@link CaseCandidate}）：候选每次<b>现算</b>（读不落盘）；只有你的
 * 决定（收下 / 不要）落盘。<b>幂等</b>：已收下的再收 → 原样返回（双击 / 重复请求无害）；
 * 已弃的再弃 → 静默成功；弃过的再收 → 400（墓碑不复活——与规则集
 * {@code TradingUserRuleService.accept} 对 DISMISSED 的口径一致）。
 *
 * <p><b>内存快照</b>：accept / dismiss 需要「用户看到的那个候选」的详情（标题 / 事实句 /
 * 对照）——GET 时把当次算出的候选存 per-user 快照（TTL {@value #SNAPSHOT_TTL_MIN} 分钟）；
 * 决定时快照命中直接用，miss（过期 / 重启）→ 重算一次兜底。文件只记决定，快照只是会话内
 * 的「所见即所认」缓存，丢了不影响一致性（重算兜底）。
 */
@Service
public class CaseCandidateService {

    private static final Logger log = LoggerFactory.getLogger(CaseCandidateService.class);

    /** 显著性阈值：|变化| ≥ 8% 才算候选（买后 / 卖后到现在）——不硬凑。 */
    static final double SIGNIFICANCE_PCT = 8.0;

    /** 每个结果方向最多几条（买成功 / 买失败 / 卖走早 / 卖对 各 2 → 一次最多 8 条）。 */
    static final int PER_OUTCOME_MAX = 2;

    /** 买点类粗筛：按轮盈亏每方向先取前 8 名去拉 K 线（控制成本）。 */
    static final int BUY_PRESELECT = 8;

    /** 快照 TTL（分钟）：accept / dismiss 取「你看到的那个候选」详情用；过期重算兜底。 */
    static final int SNAPSHOT_TTL_MIN = 10;

    /** 买点日与基准 K 线的最大容差（自然日）——与 {@link SoldAfterCloseService} 同口径。 */
    static final int BASE_TOLERANCE_DAYS = 10;

    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd");

    private final TradingRoundService roundService;
    private final TradingAppService tradingAppService;
    private final SoldAfterCloseService soldAfterCloseService;
    private final KlineService klineService;
    private final UserRuleRepository userRuleRepository;
    private final CaseCandidateRepository repository;

    /** per-user 候选快照（GET 时写入；accept / dismiss 取详情用）。 */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public CaseCandidateService(TradingRoundService roundService,
                                TradingAppService tradingAppService,
                                SoldAfterCloseService soldAfterCloseService,
                                KlineService klineService,
                                UserRuleRepository userRuleRepository,
                                CaseCandidateRepository repository) {
        this.roundService = roundService;
        this.tradingAppService = tradingAppService;
        this.soldAfterCloseService = soldAfterCloseService;
        this.klineService = klineService;
        this.userRuleRepository = userRuleRepository;
        this.repository = repository;
    }

    // ── 读（现算 + 合并你的决定）──

    /**
     * 案例区全量视图：{@code pending} = 这次从数据里照出来、你还没做过决定的候选；
     * {@code accepted} = 你已经收下的（最新收下在前，用于「已经收下的」列表）。
     * <p>候选每次现算（数据变了候选跟着变）；你的决定从文件读、原样合并。
     */
    public CandidatesView list(String userId) {
        List<CaseCandidate> computed = compute(userId);
        snapshots.put(userId, new Snapshot(Instant.now(), computed));
        Set<String> decidedIds = new HashSet<>();
        List<CaseCandidate> accepted = new ArrayList<>();
        for (CaseCandidate d : repository.findByUser(userId)) {
            decidedIds.add(d.id());
            if (d.state() == CaseCandidate.State.ACCEPTED) accepted.add(d);
        }
        accepted.sort((a, b) -> nullSafe(b.updatedAt()).compareTo(nullSafe(a.updatedAt())));
        List<CaseCandidate> pending = new ArrayList<>();
        for (CaseCandidate c : computed) {
            if (!decidedIds.contains(c.id())) pending.add(c);
        }
        return new CandidatesView(pending, accepted);
    }

    // ── 决定（只有决定落盘）──

    /**
     * 收下（title 非空 = 「改一改」：收下时顺手改名）。
     * <p>幂等：已收下的同 id 直接原样返回（双击 / 重复请求无害；不再改 title——第一次决定为准）。
     */
    public CaseCandidate accept(String userId, String id, String title) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("候选 id 不能为空");
        }
        CaseCandidate existing = find(repository.findByUser(userId), id);
        if (existing != null) {
            if (existing.state() == CaseCandidate.State.ACCEPTED) return existing;
            // 已弃 = 墓碑：弃掉的不再回来（同 TradingUserRuleService.require 对 DISMISSED 的口径）
            throw new IllegalArgumentException("找不到这条候选（可能已弃掉）");
        }
        CaseCandidate detail = detail(userId, id);
        if (detail == null) {
            throw new IllegalArgumentException("这个候选已经不在列表里了（数据变了，刷新看看）");
        }
        CaseCandidate accepted = detail.accepted(title, now());
        repository.upsert(userId, accepted);
        return accepted;
    }

    /**
     * 不要（<b>墓碑</b>——下次生成不复活；幂等：已弃的再弃、数据变了候选没了都算成功）。
     * <p>已收下的反悔也走这里（收下 → 弃掉 = 转墓碑，不再展示）。
     */
    public void dismiss(String userId, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("候选 id 不能为空");
        }
        CaseCandidate existing = find(repository.findByUser(userId), id);
        if (existing != null && existing.state() == CaseCandidate.State.DISMISSED) return;   // 幂等
        if (existing != null) {
            repository.upsert(userId, existing.dismissed(now()));
            return;
        }
        CaseCandidate detail = detail(userId, id);
        CaseCandidate tomb = detail != null ? detail.dismissed(now()) : tombFromId(id, now());
        if (tomb == null) {
            throw new IllegalArgumentException("候选 id 不合法：" + id);
        }
        repository.upsert(userId, tomb);
    }

    // ── 现算（读侧核心）──

    /** 这次从数据里照一遍（买点类 + 卖点类；顺序 = 买成功 → 买失败 → 卖走早 → 卖对）。 */
    private List<CaseCandidate> compute(String userId) {
        String now = now();
        List<UserRule> rules = activeRules(userId);
        List<CaseCandidate> out = new ArrayList<>();
        out.addAll(buyCandidates(userId, rules, now));
        out.addAll(sellCandidates(userId, rules, now));
        return out;
    }

    /** 可用于对照的「你写的规则」= 已认 / 自定义（候选没认、弃掉的墓碑都不算）。 */
    private List<UserRule> activeRules(String userId) {
        List<UserRule> out = new ArrayList<>();
        for (UserRule r : userRuleRepository.findByUser(userId)) {
            if (r.state() == UserRule.UserRuleState.ACCEPTED
                    || r.state() == UserRule.UserRuleState.CUSTOM) {
                out.add(r);
            }
        }
        return out;
    }

    // 买点类：轮「买入之后到现在」涨 = 买点成功 / 跌 = 买点失败

    private List<CaseCandidate> buyCandidates(String userId, List<UserRule> rules, String now) {
        List<TradeRound> all = roundService.rounds(userId, null, Integer.MAX_VALUE).rounds();
        List<TradeRound> ups = new ArrayList<>();
        List<TradeRound> downs = new ArrayList<>();
        for (TradeRound r : all) {
            if (r.unresolved() || r.pnlPct() == null || r.start() == null || r.symbol() == null) continue;
            if (r.pnlPct().signum() > 0) ups.add(r);
            else if (r.pnlPct().signum() < 0) downs.add(r);
        }
        ups.sort((a, b) -> b.pnlPct().compareTo(a.pnlPct()));
        downs.sort((a, b) -> a.pnlPct().compareTo(b.pnlPct()));

        List<CaseCandidate> success = new ArrayList<>();
        List<CaseCandidate> failed = new ArrayList<>();
        for (TradeRound r : concat(pick(ups, BUY_PRESELECT), pick(downs, BUY_PRESELECT))) {
            CaseCandidate c = buyCandidate(r, rules, now);
            if (c == null) continue;
            if (c.outcome() == CaseCandidate.Outcome.SUCCESS) success.add(c);
            else failed.add(c);
        }
        List<CaseCandidate> out = new ArrayList<>();
        out.addAll(pick(magnitudeDesc(success), PER_OUTCOME_MAX));
        out.addAll(pick(magnitudeDesc(failed), PER_OUTCOME_MAX));
        return out;
    }

    /** 单个买点候选（买点日 K 线 + 买后到现在涨跌 + MA5 标题 + 规则对照）；够不上显著性 → null。 */
    private CaseCandidate buyCandidate(TradeRound r, List<UserRule> rules, String now) {
        List<Candle> candles;
        try {
            // 前 15 天 = 给 MA5 留前史；单笔异常不炸整批（同 SoldAfterCloseService 口径）
            candles = klineService.klineRange(r.symbol(), r.start().minusDays(15), LocalDate.now());
        } catch (RuntimeException e) {
            log.warn("案例候选买点 K 线取不到 | symbol={} | {}", r.symbol(), e.getMessage());
            return null;
        }
        if (candles == null || candles.isEmpty()) return null;
        int buyIdx = -1;
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            if (c.date() == null || c.date().isBefore(r.start())) continue;
            if (ChronoUnit.DAYS.between(r.start(), c.date()) > BASE_TOLERANCE_DAYS) return null;
            buyIdx = i;
            break;
        }
        if (buyIdx < 0) return null;
        Candle buyBar = candles.get(buyIdx);
        Candle latest = candles.get(candles.size() - 1);
        if (buyBar.close() <= 0 || latest.close() <= 0) return null;
        double change = (latest.close() - buyBar.close()) / buyBar.close() * 100.0;
        if (Math.abs(change) < SIGNIFICANCE_PCT) return null;
        CaseCandidate.Outcome outcome = change > 0
                ? CaseCandidate.Outcome.SUCCESS : CaseCandidate.Outcome.FAILED;

        Double ma5 = null;
        if (buyIdx >= 4) {
            double sum = 0;
            for (int i = buyIdx - 4; i <= buyIdx; i++) sum += candles.get(i).close();
            ma5 = sum / 5.0;
        }
        String name = displayName(r.name(), r.symbol());
        String mmdd = r.start().format(MM_DD);
        String title = ma5 != null
                ? name + " " + mmdd + (buyBar.close() >= ma5 ? " 买在 5 日线上方" : " 买在 5 日线下方")
                : name + " " + mmdd + " 买入";
        List<String> notes = new ArrayList<>();
        RuleLink link = linkRule(r, rules);
        if (link != null) notes.add(link.line());
        notes.add("买了之后到现在 " + signed(change) + "%。");
        return CaseCandidate.candidate(CaseCandidate.idOf(CaseCandidate.Kind.BUY, r.symbol(),
                        r.start().toString()),
                CaseCandidate.Kind.BUY, outcome, r.symbol(), name, r.start().toString(), title,
                round2(change),
                link == null ? null : link.rel(),
                link == null ? null : link.ruleId(),
                link == null ? null : link.ruleText(),
                notes, now);
    }

    // 卖点类：清仓「卖掉之后到现在」涨 = 走早了 / 跌 = 走对了

    private List<CaseCandidate> sellCandidates(String userId, List<UserRule> rules, String now) {
        List<SoldTrade> sold = tradingAppService.soldList(userId);
        // 与清仓列表同源同序（compute 按输入顺序返回）——索引配对取回原笔（对照要 holdPnlPct / holdDays）
        List<SoldAfterCloseService.AfterClose> acs = soldAfterCloseService.compute(sold);
        List<CaseCandidate> early = new ArrayList<>();
        List<CaseCandidate> right = new ArrayList<>();
        int n = Math.min(sold.size(), acs.size());
        for (int i = 0; i < n; i++) {
            SoldTrade t = sold.get(i);
            SoldAfterCloseService.AfterClose ac = acs.get(i);
            if (t.sellDate() == null || ac.pct() == null) continue;
            double pct = ac.pct();
            if (Math.abs(pct) < SIGNIFICANCE_PCT) continue;
            boolean up = pct > 0;
            String name = displayName(t.name(), t.symbol());
            String title = name + " " + t.sellDate().format(MM_DD)
                    + (up ? " 卖了之后又涨 " : " 卖了之后又跌 ") + fmt1(Math.abs(pct)) + "%";
            List<String> notes = new ArrayList<>();
            RuleLink link = linkRuleSell(t, rules);
            if (link != null) notes.add(link.line());
            notes.add("卖掉之后到现在 " + signed(pct) + "%。");
            CaseCandidate c = CaseCandidate.candidate(
                    CaseCandidate.idOf(CaseCandidate.Kind.SELL, t.symbol(), t.sellDate().toString()),
                    CaseCandidate.Kind.SELL,
                    up ? CaseCandidate.Outcome.EARLY : CaseCandidate.Outcome.RIGHT,
                    t.symbol(), name, t.sellDate().toString(), title, round2(pct),
                    link == null ? null : link.rel(),
                    link == null ? null : link.ruleId(),
                    link == null ? null : link.ruleText(),
                    notes, now);
            (up ? early : right).add(c);
        }
        List<CaseCandidate> out = new ArrayList<>();
        out.addAll(pick(magnitudeDesc(early), PER_OUTCOME_MAX));
        out.addAll(pick(magnitudeDesc(right), PER_OUTCOME_MAX));
        return out;
    }

    // ── 规则对照（能机械对照才贴）──

    /** 一轮买点案例 vs 规则：你写的规则优先（止损 → 盈转亏 → 被套 → 超期），其次轮自带引擎命中。 */
    private RuleLink linkRule(TradeRound r, List<UserRule> rules) {
        for (UserRule rule : rules) {
            Double pct = num(rule.params().get("pct"));
            if (pct == null || pct >= 0) continue;
            if (r.pnlPct() == null || r.pnlPct().signum() >= 0) continue;   // 止损只对照亏损笔
            double loss = Math.abs(r.pnlPct().doubleValue());
            String head = head(rule.text());
            if (loss > Math.abs(pct)) {
                return new RuleLink(CaseCandidate.RuleRel.AGAINST, rule.id(), head,
                        "和你写的「" + head + "」相反 —— 这一笔亏了 " + fmt1(loss) + "%。");
            }
            return new RuleLink(CaseCandidate.RuleRel.SUPPORT, rule.id(), head,
                    "支持你写的「" + head + "」—— 这一笔亏到 " + fmt1(loss) + "% 就平了。");
        }
        for (UserRule rule : rules) {
            Double peak = num(rule.params().get("peakPct"));
            if (peak == null || peak <= 0) continue;
            if (r.peakPct() == null || r.peakPct().doubleValue() < peak) continue;
            if (r.pnl() == null || r.pnl().signum() >= 0) continue;
            String head = head(rule.text());
            return new RuleLink(CaseCandidate.RuleRel.AGAINST, rule.id(), head,
                    "和你写的「" + head + "」相反 —— 这一笔赚到过 +"
                            + fmt1(r.peakPct().doubleValue()) + "%，最后亏了。");
        }
        for (UserRule rule : rules) {
            Double maxAddOn = num(rule.params().get("maxTrappedAddOns"));
            if (maxAddOn == null || maxAddOn != 0) continue;
            if (r.addOnTrappedCount() <= 0) continue;
            String head = head(rule.text());
            return new RuleLink(CaseCandidate.RuleRel.AGAINST, rule.id(), head,
                    "和你写的「" + head + "」相反 —— 这一笔有 " + r.addOnTrappedCount()
                            + " 次在浮亏时补了仓。");
        }
        for (UserRule rule : rules) {
            Double days = num(rule.params().get("days"));
            if (days == null || days <= 0 || r.holdDays() <= days) continue;
            // 「亏损不扛」只判亏损笔（盈利笔拿久了不算违反这条）
            if ("cand-loss-hold".equals(rule.id())
                    && (r.pnlPct() == null || r.pnlPct().signum() >= 0)) continue;
            String head = head(rule.text());
            return new RuleLink(CaseCandidate.RuleRel.AGAINST, rule.id(), head,
                    "和你写的「" + head + "」相反 —— 这一笔拿了 " + r.holdDays() + " 天。");
        }
        // 轮自带引擎命中（R55 / R69 / R66 / R53）：已是「没做到」型事实，如实贴
        for (RuleHit h : r.hits()) {
            String text = head(h.text());
            return new RuleLink(CaseCandidate.RuleRel.AGAINST, h.rule(), text,
                    "命中 " + h.rule() + "：" + text);
        }
        return null;
    }

    /** 一笔清仓 vs 规则：止损（亏着卖的）→ 超期类（拿超了）。清仓笔拿不到峰值/补仓数据，不硬编。 */
    private RuleLink linkRuleSell(SoldTrade t, List<UserRule> rules) {
        for (UserRule rule : rules) {
            Double pct = num(rule.params().get("pct"));
            if (pct == null || pct >= 0) continue;
            if (t.holdPnlPct() >= 0) continue;
            double loss = Math.abs(t.holdPnlPct());
            String head = head(rule.text());
            if (loss > Math.abs(pct)) {
                return new RuleLink(CaseCandidate.RuleRel.AGAINST, rule.id(), head,
                        "和你写的「" + head + "」相反 —— 这一笔亏了 " + fmt1(loss) + "%。");
            }
            return new RuleLink(CaseCandidate.RuleRel.SUPPORT, rule.id(), head,
                    "支持你写的「" + head + "」—— 这一笔亏到 " + fmt1(loss) + "% 就平了。");
        }
        for (UserRule rule : rules) {
            Double days = num(rule.params().get("days"));
            if (days == null || days <= 0 || t.holdDays() <= days) continue;
            if ("cand-loss-hold".equals(rule.id()) && t.holdPnlPct() >= 0) continue;
            String head = head(rule.text());
            return new RuleLink(CaseCandidate.RuleRel.AGAINST, rule.id(), head,
                    "和你写的「" + head + "」相反 —— 这一笔拿了 " + t.holdDays() + " 天。");
        }
        return null;
    }

    // ── 快照与详情 ──

    /**
     * 取「用户看到的那个候选」详情：快照命中（TTL 内）→ 直接用；miss → 重算一次并刷新快照；
     * 还没有（数据变了真正消失）→ null（accept 400 / dismiss 走 id 还原的最小墓碑）。
     */
    private CaseCandidate detail(String userId, String id) {
        Snapshot s = snapshots.get(userId);
        if (s != null && Instant.now().isBefore(s.at().plus(Duration.ofMinutes(SNAPSHOT_TTL_MIN)))) {
            CaseCandidate hit = find(s.computed(), id);
            if (hit != null) return hit;
        }
        List<CaseCandidate> fresh = compute(userId);
        snapshots.put(userId, new Snapshot(Instant.now(), fresh));
        return find(fresh, id);
    }

    /**
     * 从 id（{@code buy|sell-{symbol}-{date}}）还原最小墓碑：数据变了候选没了，
     * 你的「不要」照记（P1-交易93：用户操作不能被系统无视）。
     * <p>占位字段（outcome / title）只为让条目形状合法——DISMISSED 不进任何视图，永不可见。
     */
    private static CaseCandidate tombFromId(String id, String now) {
        CaseCandidate.Kind kind;
        String rest;
        if (id.startsWith("sell-")) {
            kind = CaseCandidate.Kind.SELL;
            rest = id.substring(5);
        } else if (id.startsWith("buy-")) {
            kind = CaseCandidate.Kind.BUY;
            rest = id.substring(4);
        } else {
            return null;
        }
        int cut = rest.indexOf('-');
        if (cut <= 0 || cut >= rest.length() - 1) return null;
        String symbol = rest.substring(0, cut);
        String date = rest.substring(cut + 1);
        CaseCandidate.Outcome placeholder = kind == CaseCandidate.Kind.BUY
                ? CaseCandidate.Outcome.FAILED : CaseCandidate.Outcome.RIGHT;
        return CaseCandidate.candidate(id, kind, placeholder, symbol, symbol, date, null,
                        null, null, null, null, List.of(), now)
                .dismissed(now);
    }

    // ── 工具 ──

    /** 幅度（|变化|）降序——各组取 top 用。 */
    private static List<CaseCandidate> magnitudeDesc(List<CaseCandidate> list) {
        list.sort((a, b) -> Double.compare(Math.abs(b.changePct()), Math.abs(a.changePct())));
        return list;
    }

    private static <T> List<T> pick(List<T> list, int n) {
        return list.size() <= n ? list : new ArrayList<>(list.subList(0, n));
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static CaseCandidate find(List<CaseCandidate> list, String id) {
        for (CaseCandidate c : list) {
            if (c.id().equals(id)) return c;
        }
        return null;
    }

    private static Double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /** 规则主句：截掉「（…）」尾巴（「止损：亏到 5% 就走（你 …）」→「止损：亏到 5% 就走」）。 */
    private static String head(String text) {
        if (text == null) return "";
        int full = text.indexOf('（');
        int half = text.indexOf('(');
        int i = full < 0 ? half : (half < 0 ? full : Math.min(full, half));
        return (i > 0 ? text.substring(0, i) : text).trim();
    }

    private static String displayName(String name, String symbol) {
        return name != null && !name.isBlank() ? name : symbol;
    }

    /** 走势数字（1 位小数、带正负号）：「+18.4」/「-6.3」。 */
    private static String signed(double v) {
        double r = Math.round(v * 10) / 10.0;
        String s = r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
        return r > 0 ? "+" + s : s;
    }

    /** 1 位小数（去尾 0）。 */
    private static String fmt1(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }

    // ── 视图对象 ──

    /** 案例区全量视图：等你认 + 已收下（前端一次拿全）。 */
    public record CandidatesView(List<CaseCandidate> pending, List<CaseCandidate> accepted) {}

    /** 候选快照（GET 那次算出的候选——accept / dismiss 取详情用）。 */
    private record Snapshot(Instant at, List<CaseCandidate> computed) {}

    /** 规则对照结果（rel + 规则 id / 主句 + 整句文案——notes 第一行）。 */
    private record RuleLink(CaseCandidate.RuleRel rel, String ruleId, String ruleText, String line) {}
}
