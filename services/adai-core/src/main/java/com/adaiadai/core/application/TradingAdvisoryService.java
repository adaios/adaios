package com.adaiadai.core.application;

import com.adaiadai.core.application.TradingRoundService.TradeRound;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingLot;
import com.adaiadai.core.domain.trading.TradingPlan;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TradingRuleSettingsPort;
import com.adaiadai.core.domain.trading.UserRule;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingAdvisoryService — 三环（买 / 持 / 卖）的**对照**与**陈述式**输出（批6 advisory，2026-10-06）。
 *
 * <p>依据：design-final §4「买卖三环的行为」· §5 端点表 · requirement R-07 / 验收 7 / 10 ·
 * blueprint §三「描述不是建议」。
 *
 * <p><b>接口契约三条</b>（design §5）：
 * <ol>
 *   <li>「缺数据」一律出 {@code null} + 说明（basis.trace / missing），<b>不出 0</b>（验收 5）；</li>
 *   <li>每个数字带可回溯引用（每条 basis 带 trace——哪几笔 / 哪天 / 哪个落点算的，验收 4）；</li>
 *   <li>三环响应<b>只带 statement + basis[] + ruleRef</b>，<b>契约层不含建议字段</b>（验收 10）——
 *       没有 buy/hold/reduce/clear 这类方向词，只有「事实 + 用你的规则做的对照」。</li>
 * </ol>
 *
 * <p><b>四要素闸门</b>（design §4 · 需求「先说清情况再开口」）——成本 / 批次 · 我的线（止损价 / 放飞线）·
 * 行情（现价 / 峰值 / 回落）· 持有时间（从建仓完毕算）：<b>缺一样就别开口</b>——
 * 持仓环（hold）严格执行：四要素只要缺一样，{@code statement=null}，{@code missing} 逐项说清缺什么
 * （「我缺 X，说不了」），绝不给半截话。买入环 / 卖出环按各自的最小事实集开口（见各方法 javadoc），
 * 缺的项同样进 {@code missing} 如实标注。
 *
 * <p><b>说话口径</b>（需求「提醒口径」）：提醒只用<b>你定的线</b>、只陈述——不说「该止损了」；
 * 「要动吗？」只用于「你定的线到了」这一个场景；止损 / 清仓分开说（放飞 = 减一部分 ≠ 清仓）。
 *
 * <p>本服务是<b>确定性</b>的（input.md D4：能用引擎判定的不走 LLM）——只读持仓 / 流水 / 行情 /
 * K 线 / 规则，不调 LLM、不写任何数据。
 */
@Service
public class TradingAdvisoryService {

    private static final Logger log = LoggerFactory.getLogger(TradingAdvisoryService.class);

    /** 环名（端点 path 取值）。 */
    public static final String RING_BUY = "buy";
    public static final String RING_HOLD = "hold";
    public static final String RING_SELL = "sell";

    /** 卖出环一次最多评价的笔数（最近的在前）。 */
    private static final int SELL_LIMIT = 10;

    /** 卖出环「清仓后走势」观察窗（自然日上限）。 */
    private static final int AFTER_CLOSE_DAYS = 30;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PositionRepository positionRepository;
    private final MarketDataSource marketDataSource;
    private final KlineService klineService;
    private final TradingLotService lotService;
    private final TradingHistoryRepository historyRepository;
    private final TradingRoundService roundService;
    private final TradingUserRuleService userRuleService;
    private final TradingRuleSettingsPort ruleSettingsPort;
    private final TradingPlanService planService;

    public TradingAdvisoryService(PositionRepository positionRepository,
                                  MarketDataSource marketDataSource,
                                  KlineService klineService,
                                  TradingLotService lotService,
                                  TradingHistoryRepository historyRepository,
                                  TradingRoundService roundService,
                                  TradingUserRuleService userRuleService,
                                  TradingRuleSettingsPort ruleSettingsPort,
                                  TradingPlanService planService) {
        this.positionRepository = positionRepository;
        this.marketDataSource = marketDataSource;
        this.klineService = klineService;
        this.lotService = lotService;
        this.historyRepository = historyRepository;
        this.roundService = roundService;
        this.userRuleService = userRuleService;
        this.ruleSettingsPort = ruleSettingsPort;
        this.planService = planService;
    }

    // ══════════════════════ 买入环（对照你写的计划）══════════════════════

    /**
     * 买入环：按你写的计划照（被动——它靠你的「明日计划」，不主动出主意）。
     *
     * <p>最小事实集：<b>计划原话（你的线）+ 行情（现价）</b>——计划里的条件价就是「你定的线」，
     * 只对照「现价 vs 你写的条件」，不替你说该不该买（需求：买入环靠计划，不猜）。
     *
     * @param date 计划管的那一天（缺省 = 今天）；那天没写计划 → 空 items + note 如实说
     */
    public AdvisoryView buy(String userId, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        TradingPlan plan = null;
        try {
            plan = planService.find(userId, day).orElse(null);
        } catch (RuntimeException e) {
            log.warn("买入环：读计划失败 | userId={} | date={} | {}", userId, day, e.getMessage());
            return view(RING_BUY, day, List.of(), "读这一天（" + day + "）的计划失败：" + e.getMessage() + "——如实说，不给半截话");
        }
        if (plan == null || plan.items().isEmpty()) {
            return view(RING_BUY, day, List.of(),
                    day + " 你没有写计划——买入环靠你写的计划，它不主动出主意（写一句「明日计划」它就照）");
        }
        List<TradingPlan.PlanItem> buys = plan.items().stream()
                .filter(i -> "BUY".equals(i.action()))
                .toList();
        if (buys.isEmpty()) {
            return view(RING_BUY, day, List.of(), "这一天（" + day + "）的计划里没有买入条目——买入环没话可说");
        }

        List<String> symbols = buys.stream().map(TradingPlan.PlanItem::symbol)
                .filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, MarketData> quotes = quotes(symbols);

        List<AdvisoryItem> items = new ArrayList<>();
        for (TradingPlan.PlanItem item : buys) {
            MarketData md = item.symbol() != null ? quotes.get(item.symbol()) : null;
            BigDecimal price = md != null ? md.price() : null;
            if (price != null && price.signum() <= 0) price = null;

            String quoteText = planQuote(item);
            List<Basis> basis = new ArrayList<>();
            basis.add(new Basis("plan", "计划（你的原话）", quoteText,
                    "trading/plans/" + day + ".json"));
            String condLine = condLine(item);
            basis.add(new Basis("line", "我的线", condLine,
                    condLine != null && condLine.startsWith("条件价")
                            ? "计划里的条件价——到了它才提醒（你的原话不改写）"
                            : "你在计划里写的是无条件（开盘即做）"));
            basis.add(new Basis("market", "行情",
                    price != null ? "现价 " + fmt(price) : null,
                    price != null ? "行情源现价" : "行情取不到（不用旧价冒充、不编数）"));
            List<String> missing = missingOf(basis);

            String statement = null;
            if (price != null) {
                statement = buyStatement(item, quoteText, price);
            }
            items.add(new AdvisoryItem(item.symbol(), item.name(), statement, basis, null, missing));
        }
        String note = items.stream().anyMatch(i -> i.statement() == null)
                ? "有条目凑不齐（计划 + 现价）——它如实标了缺什么，不说半截话" : null;
        return view(RING_BUY, day, items, note);
    }

    private String buyStatement(TradingPlan.PlanItem item, String quoteText, BigDecimal price) {
        BigDecimal cond = item.condPrice();
        String op = item.condOp() != null ? item.condOp() : "";
        if (cond != null && cond.signum() > 0 && ("LT".equals(op) || "GE".equals(op))) {
            boolean hit = "LT".equals(op)
                    ? price.compareTo(cond) <= 0
                    : price.compareTo(cond) >= 0;
            if (hit) {
                return "你计划「" + quoteText + "」——现价 " + fmt(price) + "，" + ("LT".equals(op)
                        ? "已触及你写的下方价 " + fmt(cond)
                        : "已触及你写的上方价 " + fmt(cond));
            }
            BigDecimal gap = price.subtract(cond).abs().multiply(HUNDRED)
                    .divide(price, 2, RoundingMode.HALF_UP);
            return "你计划「" + quoteText + "」——现价 " + fmt(price) + "，"
                    + ("LT".equals(op) ? "还没到你写的下方价 " : "还没到你写的上方价 ")
                    + fmt(cond) + "（差 " + fmt(gap) + "%）";
        }
        return "你计划「" + quoteText + "」——现价 " + fmt(price) + "（你写的是无条件，开盘即做）";
    }

    /** 计划原话：优先用户写的那句（不得改写）；没有就用条件文本兜底。 */
    private static String planQuote(TradingPlan.PlanItem item) {
        String text = item.text() != null && !item.text().isBlank() ? item.text() : item.condition();
        if (text == null || text.isBlank()) text = "（条目里没写细节）";
        if (item.done()) text += "（已标记执行）";
        return text;
    }

    private static String condLine(TradingPlan.PlanItem item) {
        BigDecimal cond = item.condPrice();
        String op = item.condOp() != null ? item.condOp() : "";
        if (cond == null || cond.signum() <= 0 || op.isBlank()) return null;
        return "条件价 " + fmt(cond) + ("LT".equals(op) ? "（触及下方）" : "（触及上方）");
    }

    // ══════════════════════ 持仓环（用你的线说话）══════════════════════

    /**
     * 持仓环：从数据推 + 行情——**四要素闸门严格执行**（成本/批次 · 我的线 · 行情 · 持有时间，
     * 缺一样就 {@code statement=null} + missing 说明）。
     *
     * <p>陈述优先级：① 现价 ≤ 你定的止损线 →「你定的 XX 到了（现价 Y、持 N 天），要动吗？」；
     * ② 峰值浮盈 ≥ 你设的回吐参考（峰值阈值 + 回落比例）→「涨到过 +X%（峰值 +Y%，回落 Z 个点）…
     * 要减吗（减一部分 ≠ 清仓）？」；③ 其余 → 事实陈述（现价 / 成本 / 盈亏 / 持几天 / 距你的线还差多少）。
     */
    public AdvisoryView hold(String userId) {
        LocalDate today = LocalDate.now();
        List<Position> positions;
        try {
            positions = positionRepository.findAll(userId);
        } catch (RuntimeException e) {
            log.warn("持仓环：读持仓失败 | userId={} | {}", userId, e.getMessage());
            return view(RING_HOLD, today, List.of(), "读持仓失败：" + e.getMessage() + "——如实说，不给半截话");
        }
        if (positions == null || positions.isEmpty()) {
            return view(RING_HOLD, today, List.of(), "当前空仓——持仓环没话可说（等你有持仓它才开口）");
        }

        Map<String, MarketData> quotes = quotes(positions.stream().map(Position::symbol).toList());
        Map<String, List<TradingLot>> lotsBySymbol = safeLots(userId);
        Map<String, LocalDate> buildEnds = buildEndBySymbol(userId);
        TradingRuleSettings settings = safeSettings(userId);
        RuleFacts facts = ruleFacts(userId);

        List<AdvisoryItem> items = new ArrayList<>();
        for (Position p : positions) {
            String symbol = p.symbol();
            MarketData md = quotes.get(symbol);
            BigDecimal price = md != null ? md.price() : null;
            if (price != null && price.signum() <= 0) price = null;
            BigDecimal cost = p.avgCost();
            BigDecimal stop = p.effectiveStopLoss();
            LocalDate buildEnd = buildEnds.get(symbol);
            Long days = buildEnd != null ? ChronoUnit.DAYS.between(buildEnd, today) : null;
            if (days != null && days < 0) days = 0L;
            List<TradingLot> openLots = lotsBySymbol.getOrDefault(symbol, List.of()).stream()
                    .filter(l -> !l.closed()).toList();
            Peak peak = (price != null && cost != null && cost.signum() > 0)
                    ? peakSince(symbol, buildEnd, cost, price, today) : null;

            List<Basis> basis = new ArrayList<>();
            basis.add(costBasis(cost, openLots));
            basis.add(lineBasis(stop, cost, facts.stripRule()));
            basis.add(marketBasis(price, md, peak));
            basis.add(timeBasis(buildEnd, days));
            List<String> missing = missingOf(basis);

            String statement = null;
            String ruleRef = null;
            if (missing.isEmpty()) {
                BigDecimal pnlPct = cost.signum() > 0 ? pct(price.subtract(cost), cost) : null;
                if (price.compareTo(stop) <= 0) {
                    statement = "你定的止损线 " + fmt(stop) + " 到了（现价 " + fmt(price) + "、持 " + days
                            + " 天，从建仓完毕算），要动吗？";
                    ruleRef = facts.stripRule() != null ? facts.stripRule().id() : null;
                } else if (peak != null && peak.peakPct().compareTo(settings.givebackPeakPct()) >= 0
                        && peak.giveback() != null && peak.giveback().compareTo(peak.peakPct()
                                .multiply(settings.givebackRatioPct())
                                .divide(HUNDRED, 2, RoundingMode.HALF_UP)) >= 0) {
                    statement = "涨到过 +" + fmt(peak.peakPct()) + "%（" + peak.peakDate() + " 峰值），现回落到 +"
                            + fmt(peak.curPct()) + "%（回落 " + fmt(peak.giveback()) + " 个点）——按你设的浮盈回吐参考"
                            + "（峰值 ≥ " + trim(settings.givebackPeakPct()) + "% 且回落 ≥ 其 "
                            + trim(settings.givebackRatioPct()) + "%），要减吗（减一部分 ≠ 清仓）？";
                    ruleRef = facts.givebackRule() != null ? facts.givebackRule().id() : null;
                } else {
                    StringBuilder sb = new StringBuilder("现价 " + fmt(price));
                    if (pnlPct != null) {
                        sb.append("，成本 ").append(fmt(cost)).append("（").append(signed(pnlPct)).append("%）");
                    } else {
                        sb.append("，成本 ").append(fmt(cost)).append("（非正成本，盈亏% 算不了）");
                    }
                    sb.append("，持 ").append(days).append(" 天；你定的止损线 ").append(fmt(stop)).append(" 还差 ")
                            .append(fmt(pct(price.subtract(stop), price))).append("%");
                    statement = sb.toString();
                }
            }
            items.add(new AdvisoryItem(symbol, p.name(), statement, basis, ruleRef, missing));
        }
        String note = items.stream().anyMatch(i -> i.statement() == null)
                ? "有持仓凑不齐四要素（成本/批次 · 我的线 · 行情 · 持有时间）——逐行如实标了缺什么，它不说半截话"
                : null;
        return view(RING_HOLD, today, items, note);
    }

    private Basis costBasis(BigDecimal cost, List<TradingLot> openLots) {
        if (cost == null) {
            return new Basis("cost", "成本 / 批次", null,
                    "持仓没有成本价（数据缺）——成本这一样凑不齐");
        }
        StringBuilder sb = new StringBuilder("成本 " + fmt(cost));
        String trace = "持仓（trading/positions）";
        if (!openLots.isEmpty()) {
            TradingLot recent = openLots.get(openLots.size() - 1);
            sb.append("；未平批次 ").append(openLots.size()).append(" 批（最近 ").append(recent.buyDate()).append("）");
            trace = "持仓 + 流水重放（批次如 " + openLots.get(0).lotId() + "）";
        } else {
            sb.append("；批次明细判不了（推导不可用）");
        }
        return new Basis("cost", "成本 / 批次", sb.toString(), trace);
    }

    private Basis lineBasis(BigDecimal stop, BigDecimal cost, UserRule stopRule) {
        if (stop == null) {
            return new Basis("line", "我的线", null,
                    "你还没设止损位（持仓级与批次级都是空的）——「到没到」判不了");
        }
        StringBuilder sb = new StringBuilder("止损线 " + fmt(stop));
        if (cost != null && cost.signum() > 0) {
            sb.append("（≈ 成本的 ").append(signed(pct(stop.subtract(cost), cost))).append("%）");
        }
        if (stopRule != null) {
            sb.append("；你也认过一条止损规则：「").append(stopRule.text()).append("」");
        }
        return new Basis("line", "我的线", sb.toString(),
                stopRule != null ? "持仓止损位（你填的）+ 规则 " + stopRule.id() : "持仓止损位（你填的）");
    }

    private Basis marketBasis(BigDecimal price, MarketData md, Peak peak) {
        if (price == null) {
            return new Basis("market", "行情", null,
                    "行情取不到（不用旧价冒充、不编数）——现价这一样凑不齐");
        }
        StringBuilder sb = new StringBuilder("现价 " + fmt(price));
        if (md != null && md.changePercent() != null) {
            sb.append("（当日 ").append(signed(md.changePercent())).append("%）");
        }
        String trace;
        if (peak != null) {
            sb.append("；峰值 +").append(fmt(peak.peakPct())).append("%（").append(peak.peakDate()).append("）");
            if (peak.giveback() != null) {
                sb.append("，从峰值回落 ").append(fmt(peak.giveback())).append(" 个点");
            }
            trace = "行情源现价 + 日K（建仓完毕起）";
        } else {
            sb.append("；峰值 / 回落算不了（K 线取不到或没有成本基准）");
            trace = "行情源现价；K 线取不到 → 峰值/回落如实缺";
        }
        return new Basis("market", "行情", sb.toString(), trace);
    }

    private Basis timeBasis(LocalDate buildEnd, Long days) {
        if (buildEnd == null || days == null) {
            return new Basis("hold", "持有时间", null,
                    "没有建仓流水（疑似底仓）——从建仓完毕算不起来，判不了持了几天");
        }
        return new Basis("hold", "持有时间", "建仓完毕 " + buildEnd + " 起 " + days + " 天（自然日）",
                "建仓完毕 = 首次卖出前的最后一次买入（口径 C）");
    }

    // ══════════════════════ 卖出环（卖点评价）══════════════════════

    /**
     * 卖出环：最近清仓的笔的**卖点评价**——按你的尺子对照 + 卖飞了没（只陈述，不评价对错）。
     *
     * <p>规则对照两个来源：① 引擎 R 命中（R55/R66/R53/R69，阈值来自你的规则参数）；
     * ② 你认下 / 自己写的可判规则（止损 pct / 短线超期 days / 亏损不扛 days / 曾赚过 peakPct）。
     * 没有规则 → <b>只说事实、明说判不了</b>（R-06：不套默认值）。
     */
    public AdvisoryView sell(String userId) {
        LocalDate today = LocalDate.now();
        TradingRoundService.RoundsView rv;
        try {
            rv = roundService.rounds(userId, null, 0);
        } catch (RuntimeException e) {
            log.warn("卖出环：笔推导失败 | userId={} | {}", userId, e.getMessage());
            return view(RING_SELL, today, List.of(), "笔的推导失败：" + e.getMessage() + "——如实说，不给半截话");
        }
        List<TradeRound> closed = rv.rounds().stream()
                .filter(r -> r.end() != null && !r.unresolved() && r.pnl() != null)
                .sorted(Comparator.comparing(TradeRound::end, Comparator.reverseOrder()))
                .limit(SELL_LIMIT)
                .toList();
        if (closed.isEmpty()) {
            return view(RING_SELL, today, List.of(), "还没有清仓的笔——卖点评价要等你卖完一笔才有话");
        }

        Map<String, List<TradeRecord>> flow = flowBySymbol(userId);
        RuleFacts facts = ruleFacts(userId);

        List<AdvisoryItem> items = new ArrayList<>();
        for (TradeRound r : closed) {
            BigDecimal sellPrice = lastSellPrice(flow.getOrDefault(r.symbol(), List.of()), r.start(), r.end());
            After after = sellPrice != null ? afterClose(r.symbol(), r.end(), sellPrice, today) : null;
            List<String> checks = roundChecks(r, facts);
            String ruleRef = joinRefs(r, facts);

            List<Basis> basis = new ArrayList<>();
            basis.add(new Basis("cost", "成本 / 盈亏",
                    "买入合计 " + fmt(r.buyAmount()) + " 元（含费）；这一笔 " + signed(r.pnl())
                            + " 元（" + signed(r.pnlPct()) + "%）",
                    "笔 " + r.id()));
            basis.add(new Basis("line", "我的尺子", checks.isEmpty() ? null : String.join("；", checks),
                    checks.isEmpty()
                            ? "没有命中引擎规则，也没有可对照的止损 / 超期规则——判不了守没守（不套默认值）"
                            : "引擎规则命中 + 你认下的规则对照"));
            StringBuilder mk = new StringBuilder();
            if (sellPrice != null) {
                mk.append("清仓价 ").append(fmt(sellPrice)).append("（").append(r.end()).append(" 的最后一笔卖出）");
                if (after != null) {
                    mk.append("；清仓后最高 ").append(fmt(after.high())).append("（").append(after.date())
                            .append("，较清仓价 ").append(signed(after.pct())).append("%）");
                } else {
                    mk.append("；清仓后走势：K 线取不到或还没走（如实）");
                }
            }
            basis.add(new Basis("market", "行情", sellPrice != null ? mk.toString() : null,
                    sellPrice != null ? "流水最后一笔卖出 + 日K（清仓后观察窗）"
                            : "流水里找不到这一笔的卖出记录——清仓价取不到（如实）"));
            basis.add(new Basis("hold", "持有时间",
                    r.start() + " → " + r.end() + "（" + r.holdDays() + " 天；交易日 " + r.tradeDays() + " 天）",
                    "笔 " + r.id()));
            List<String> missing = missingOf(basis);

            String statement = sellStatement(r, checks, sellPrice, after);
            items.add(new AdvisoryItem(r.symbol(), r.name(), statement, basis, ruleRef, missing));
        }
        return view(RING_SELL, today, items, null);
    }

    private String sellStatement(TradeRound r, List<String> checks, BigDecimal sellPrice, After after) {
        StringBuilder sb = new StringBuilder("这一笔 ").append(r.start()).append(" → ").append(r.end())
                .append("（持 ").append(r.holdDays()).append(" 天）：买入合计 ").append(fmt(r.buyAmount()))
                .append(" 元，盈亏 ").append(signed(r.pnl())).append(" 元（").append(signed(r.pnlPct())).append("%）。");
        if (!checks.isEmpty()) {
            sb.append("对照你的尺子：").append(String.join("；", checks)).append("。");
        } else {
            sb.append("你还没有可对照的卖出规则——这一笔只摆事实（不套默认值）。");
        }
        if (sellPrice != null && after != null) {
            sb.append("清仓后最高 ").append(fmt(after.high())).append("（").append(after.date())
                    .append("，较清仓价 ").append(signed(after.pct())).append("%）。");
        }
        return sb.toString();
    }

    /** 规则对照逐条（引擎 R 命中 + 你认下的可判规则）；只陈述关系，不评价。 */
    private List<String> roundChecks(TradeRound r, RuleFacts facts) {
        List<String> lines = new ArrayList<>();
        for (TradingRoundService.RuleHit h : r.hits()) {
            lines.add("按 " + h.rule() + "「" + h.text() + "」——" + h.data());
        }
        if (facts.stripRule() != null && r.pnlPct() != null) {
            BigDecimal line = num(facts.stripRule().params().get("pct"));
            if (line != null) {
                lines.add("你认下的「" + facts.stripRule().text() + "」：卖出时 " + signed(r.pnlPct())
                        + "%（你的线 " + signed(line) + "%）——"
                        + (r.pnlPct().compareTo(line) <= 0 ? "卖在了你的线之下" : "没到你的线"));
            }
        }
        if (facts.shortRule() != null) {
            BigDecimal d = num(facts.shortRule().params().get("days"));
            if (d != null && r.holdDays() > d.intValue()) {
                lines.add("你认下的「" + facts.shortRule().text() + "」：这一笔持了 " + r.holdDays()
                        + " 天（你的线 " + trim(d) + " 天）——超了");
            }
        }
        if (facts.lossHoldRule() != null && r.pnl().signum() < 0) {
            BigDecimal d = num(facts.lossHoldRule().params().get("days"));
            if (d != null && r.holdDays() > d.intValue()) {
                lines.add("你认下的「" + facts.lossHoldRule().text() + "」：这一笔亏着扛了 " + r.holdDays()
                        + " 天（你的线 " + trim(d) + " 天）");
            }
        }
        if (facts.givebackRule() != null && r.peakPct() != null && r.pnl().signum() < 0) {
            BigDecimal peak = num(facts.givebackRule().params().get("peakPct"));
            if (peak != null && r.peakPct().compareTo(peak) >= 0) {
                lines.add("你认下的「" + facts.givebackRule().text() + "」：这一笔曾浮盈 " + fmt(r.peakPct())
                        + "% 却亏着走（你定的「赚过」线是 " + trim(peak) + "%）");
            }
        }
        return lines;
    }

    private String joinRefs(TradeRound r, RuleFacts facts) {
        List<String> refs = new ArrayList<>();
        for (TradingRoundService.RuleHit h : r.hits()) {
            if (!refs.contains(h.rule())) refs.add(h.rule());
        }
        if (facts.stripRule() != null) refs.add(facts.stripRule().id());
        if (facts.shortRule() != null) refs.add(facts.shortRule().id());
        if (facts.lossHoldRule() != null) refs.add(facts.lossHoldRule().id());
        if (facts.givebackRule() != null) refs.add(facts.givebackRule().id());
        return refs.isEmpty() ? null : String.join(",", refs);
    }

    // ── 卖出环的数据件 ──

    /** 清仓后走势（只陈述；K 线取不到 → null 不编）。 */
    private record After(BigDecimal high, LocalDate date, BigDecimal pct) {}

    private After afterClose(String symbol, LocalDate end, BigDecimal sellPrice, LocalDate today) {
        LocalDate from = end.plusDays(1);
        LocalDate to = end.plusDays(AFTER_CLOSE_DAYS);
        if (to.isAfter(today)) to = today;
        if (from.isAfter(to)) return null;
        try {
            List<Candle> candles = klineService.klineRange(symbol, from, to);
            if (candles == null || candles.isEmpty()) return null;
            Candle best = null;
            for (Candle c : candles) if (best == null || c.high() > best.high()) best = c;
            return new After(BigDecimal.valueOf(best.high()), best.date(),
                    pct(BigDecimal.valueOf(best.high()).subtract(sellPrice), sellPrice));
        } catch (RuntimeException e) {
            log.warn("卖出环：清仓后 K 线取不到 | symbol={} | {}", symbol, e.getMessage());
            return null;
        }
    }

    /** 该笔的最后一笔卖出价（清仓价；找不到 → null 如实）。 */
    private static BigDecimal lastSellPrice(List<TradeRecord> records, LocalDate start, LocalDate end) {
        BigDecimal price = null;
        for (TradeRecord t : records) {
            if (t.direction() != TradeDirection.SELL || t.entryDate() == null || t.price() == null) continue;
            if (start != null && t.entryDate().isBefore(start)) continue;
            if (end != null && t.entryDate().isAfter(end)) continue;
            price = t.price();
        }
        return price;
    }

    /** 峰值 / 回落（自建仓完毕起，日K最高价；K 线取不到 → null 不编）。 */
    private record Peak(BigDecimal peakPct, LocalDate peakDate, BigDecimal curPct, BigDecimal giveback) {}

    private Peak peakSince(String symbol, LocalDate since, BigDecimal cost, BigDecimal price, LocalDate today) {
        if (since == null || since.isAfter(today)) return null;
        try {
            List<Candle> candles = klineService.klineRange(symbol, since, today);
            if (candles == null || candles.isEmpty()) return null;
            Candle best = null;
            for (Candle c : candles) if (best == null || c.high() > best.high()) best = c;
            BigDecimal peakPct = pct(BigDecimal.valueOf(best.high()).subtract(cost), cost);
            BigDecimal curPct = pct(price.subtract(cost), cost);
            return new Peak(peakPct, best.date(), curPct, peakPct.subtract(curPct));
        } catch (RuntimeException e) {
            log.warn("持仓环：K 线取不到，峰值/回落如实缺 | symbol={} | {}", symbol, e.getMessage());
            return null;
        }
    }

    // ── 规则面（你认下 / 自己写的可判规则）──

    /**
     * 可判规则（params 里有机器可判的参数）：
     * 止损 pct（负值）· 短线超期 days · 亏损不扛 days · 曾赚过 peakPct。
     * <b>只取 accepted + custom</b>——候选（CANDIDATE）还没认，不算「你的规则」。
     */
    private record RuleFacts(UserRule stripRule, UserRule shortRule, UserRule lossHoldRule, UserRule givebackRule) {}

    private RuleFacts ruleFacts(String userId) {
        List<UserRule> mine = new ArrayList<>();
        try {
            TradingUserRuleService.RuleListView v = userRuleService.list(userId);
            mine.addAll(v.accepted());
            mine.addAll(v.custom());
        } catch (RuntimeException e) {
            log.warn("读你的规则失败（该面不给规则对照，不编）| userId={} | {}", userId, e.getMessage());
        }
        UserRule strip = null;
        UserRule shortRule = null;
        UserRule lossHold = null;
        UserRule giveback = null;
        for (UserRule r : mine) {
            if (r.params() == null) continue;
            if (strip == null && num(r.params().get("pct")) != null
                    && num(r.params().get("pct")).signum() < 0) {
                strip = r;
            } else if (shortRule == null && "cand-short-overdue".equals(r.id())) {
                shortRule = r;
            } else if (lossHold == null && "cand-loss-hold".equals(r.id())) {
                lossHold = r;
            } else if (giveback == null && num(r.params().get("peakPct")) != null) {
                giveback = r;
            }
        }
        return new RuleFacts(strip, shortRule, lossHold, giveback);
    }

    private TradingRuleSettings safeSettings(String userId) {
        try {
            TradingRuleSettings s = ruleSettingsPort.findByUser(userId);
            if (s != null) return s;
        } catch (RuntimeException e) {
            log.warn("读规则参数失败，用默认 | userId={} | {}", userId, e.getMessage());
        }
        return TradingRuleSettings.defaults();
    }

    // ── 共用数据件 ──

    /** 建仓完毕 = 首次卖出前的最后一次买入（口径 C）；底仓（首次卖出前没有买入）→ 不出现在结果里。 */
    private Map<String, LocalDate> buildEndBySymbol(String userId) {
        Map<String, LocalDate> out = new LinkedHashMap<>();
        Map<String, List<TradeRecord>> bySymbol = new LinkedHashMap<>();
        try {
            for (TradeRecord t : historyRepository.findAll(userId)) {
                if (t.volume() <= 0 || t.entryDate() == null) continue;
                bySymbol.computeIfAbsent(t.symbol(), k -> new ArrayList<>()).add(t);
            }
        } catch (RuntimeException e) {
            log.warn("读流水失败（持有时间判不了，如实）| userId={} | {}", userId, e.getMessage());
            return out;
        }
        for (Map.Entry<String, List<TradeRecord>> e : bySymbol.entrySet()) {
            List<TradeRecord> recs = e.getValue();
            recs.sort(Comparator.comparing(TradeRecord::entryDate)
                    .thenComparing(t -> t.tradeTime() != null ? t.tradeTime() : LocalTime.MIN));
            int firstSell = -1;
            for (int i = 0; i < recs.size(); i++) {
                if (recs.get(i).direction() == TradeDirection.SELL) {
                    firstSell = i;
                    break;
                }
            }
            int limit = firstSell >= 0 ? firstSell : recs.size();
            LocalDate buildEnd = null;
            for (int i = 0; i < limit; i++) {
                TradeRecord t = recs.get(i);
                if (t.direction() == TradeDirection.BUY) buildEnd = t.entryDate();
            }
            if (buildEnd != null) out.put(e.getKey(), buildEnd);
        }
        return out;
    }

    private Map<String, List<TradeRecord>> flowBySymbol(String userId) {
        Map<String, List<TradeRecord>> bySymbol = new LinkedHashMap<>();
        try {
            for (TradeRecord t : historyRepository.findAll(userId)) {
                if (t.volume() <= 0 || t.entryDate() == null) continue;
                bySymbol.computeIfAbsent(t.symbol(), k -> new ArrayList<>()).add(t);
            }
        } catch (RuntimeException e) {
            log.warn("读流水失败（清仓价判不了，如实）| userId={} | {}", userId, e.getMessage());
        }
        return bySymbol;
    }

    private Map<String, List<TradingLot>> safeLots(String userId) {
        try {
            Map<String, List<TradingLot>> m = lotService.derive(userId);
            return m != null ? m : Map.of();
        } catch (RuntimeException e) {
            log.warn("批次推导失败（批次明细如实缺）| userId={} | {}", userId, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, MarketData> quotes(List<String> symbols) {
        if (symbols.isEmpty()) return Map.of();
        try {
            Map<String, MarketData> q = marketDataSource.quote(symbols);
            return q != null ? q : Map.of();
        } catch (RuntimeException e) {
            log.warn("行情查询失败（现价如实缺，不用旧价冒充）| {}", e.getMessage());
            return Map.of();
        }
    }

    /** 四要素缺项（basis.value == null 的项；人话说明「缺什么」）。 */
    private static List<String> missingOf(List<Basis> basis) {
        Map<String, String> missingLabels = Map.of(
                "cost", "成本（持仓没有成本价）",
                "line", "我的线（你还没设止损位）",
                "market", "行情（现价取不到）",
                "hold", "持有时间（没有建仓流水，底仓判不了）",
                "plan", "计划（条目缺原话）");
        List<String> missing = new ArrayList<>();
        for (Basis b : basis) {
            if (b.value() == null) {
                missing.add(missingLabels.getOrDefault(b.key(), b.label()));
            }
        }
        return missing;
    }

    private static AdvisoryView view(String ring, LocalDate asOf, List<AdvisoryItem> items, String note) {
        String label = switch (ring) {
            case RING_BUY -> "买入环 · 对照你写的计划";
            case RING_HOLD -> "持仓环 · 用你的线说话";
            default -> "卖出环 · 卖点评价";
        };
        return new AdvisoryView(ring, label, asOf.toString(), items, note);
    }

    // ── 数值件 ──

    private static BigDecimal pct(BigDecimal delta, BigDecimal base) {
        if (base == null || base.signum() == 0) return BigDecimal.ZERO;
        return delta.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static String fmt(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "—";
    }

    private static String signed(BigDecimal v) {
        if (v == null) return "—";
        String s = fmt(v);
        return v.compareTo(BigDecimal.ZERO) > 0 ? "+" + s : s;
    }

    private static String trim(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().scale() <= 0
                ? v.setScale(0, RoundingMode.HALF_UP).toPlainString() : v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal num(Object v) {
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ── DTO（对外契约：只带 statement + basis[] + ruleRef；design §5 契约③）──

    /** 三环响应：环名 + 中文标签 + asOf + 逐条 + 整体说明（可为 null）。 */
    public record AdvisoryView(String ring, String label, String asOf, List<AdvisoryItem> items, String note) {}

    /**
     * 一条（一只票 / 一个计划条目 / 一笔）。
     *
     * @param statement 陈述句（非空 ⇒ 该环最小事实集齐）；null ⇒ missing 非空（「我缺 X，说不了」）
     * @param basis     依据（四要素 / 计划 / 尺子；每条带 trace 可回溯；value=null 表示该项缺）
     * @param ruleRef   用到了你的哪条规则（规则 id / R 号，逗号分隔；没有 → null）
     * @param missing   缺的项（人话；statement 非空时为空表）
     */
    public record AdvisoryItem(String symbol, String name, String statement, List<Basis> basis,
                               String ruleRef, List<String> missing) {}

    /** 一条依据：key（cost/line/market/hold/plan）· label · value（null=缺）· trace（可回溯说明）。 */
    public record Basis(String key, String label, String value, String trace) {}
}
