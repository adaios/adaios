package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingPlan;
import com.adaiadai.core.domain.trading.TradingPlanRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.kernel.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TradingPlanService — 次日操作计划的「写 · 守 · 对账」
 * （RFC 20261003-trading-plan-and-review-loop §二~四，2026-10-03；用户授权「全做」）。
 *
 * <p><b>定位</b>：把这个模块从「系统提醒你」（早盘计划由系统按持仓/买点算出来，近似建议）
 * 反转为「**你承诺、系统守**」——系统只做三件事：**记你的话 · 到点提醒 · 收盘对账**。
 * 因此这里的文案一律**引用用户的原话**（{@code PlanItem.text}），系统不改写、不评价、不预测。
 *
 * <p><b>一句话写</b>：复用交易解析的既有心智（自然语言 → 结构化），但**不接 LLM**——
 * 计划是纪律承诺，解析错了比解析慢更糟；这里用确定性正则，解析不出就**不猜**（condOp 留空、
 * 由用户改精确表单）。
 */
@Service
public class TradingPlanService {

    private static final Logger log = LoggerFactory.getLogger(TradingPlanService.class);

    /** 标的需要 6 位代码（名称→代码的解析留给前端选择，避免这里猜错）。 */
    private static final Pattern SYMBOL = Pattern.compile("(\\d{6})");
    /** 条件价格：跟在条件词后面的数字。 */
    private static final Pattern COND_PRICE = Pattern.compile(
            "(?:跌破|低于|以下|回到|回踩|涨到|到|高于|以上|突破|上破|破)\\s*(\\d+(?:\\.\\d+)?)");
    /** 数量（股 / 手）。 */
    private static final Pattern QTY = Pattern.compile("(\\d+)\\s*(?:股|手)");

    private final TradingPlanRepository planRepository;
    private final TradingHistoryRepository tradingHistoryRepository;
    private final KlineService klineService;

    public TradingPlanService(TradingPlanRepository planRepository,
                              TradingHistoryRepository tradingHistoryRepository,
                              KlineService klineService) {
        this.planRepository = planRepository;
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.klineService = klineService;
    }

    /**
     * P2-交易72：同一天「读-改-写」必须串行——否则「App 点今天没动」与「web 保存计划」
     * 并发时，后写者会用自己读到的旧快照覆盖对方刚写下的东西（**计划条目会被静默抹掉**）。
     * 与 {@code TradingPlanFileRepository} 的 stripe lock 同模式；方向单向（service → repo），无环。
     */
    private static final int LOCK_STRIPES = 16;
    private final Object[] locks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private Object lockFor(String userId, LocalDate date) {
        String key = (userId != null ? userId : "default") + "|" + date;
        return locks[key.hashCode() & (LOCK_STRIPES - 1)];
    }

    // ── 读 ──

    public Optional<TradingPlan> find(String userId, LocalDate date) {
        return planRepository.find(userId, date);
    }

    public List<LocalDate> dates(String userId) {
        return planRepository.dates(userId);
    }

    // ── 写（一句话 / 结构化都走这里）──

    /** 用一组「一句话」建立某天的计划（覆盖写）。{@code note} 是用户的自我约束。 */
    public TradingPlan saveFromLines(String userId, LocalDate date, List<String> lines, String note) {
        List<TradingPlan.PlanItem> items = new ArrayList<>();
        if (lines != null) {
            for (String raw : lines) {
                if (raw == null || raw.isBlank()) continue;
                items.add(parseItem(raw));
            }
        }
        synchronized (lockFor(userId, date)) {
            // P2-交易72：覆盖写**保留**当天已回填的「今日状态」——用户写计划不该抹掉他刚说的「今天没动」；
            // 反过来回填状态也不碰 items（见 recordDayStatus）。两条写路径互不吞掉对方的字段。
            String kept = planRepository.find(userId, date)
                    .map(TradingPlan::dayStatus).orElse(TradingPlan.DAY_STATUS_NONE);
            TradingPlan plan = new TradingPlan(date, items, note != null ? note : "", kept, LocalDateTime.now());
            planRepository.save(userId, plan);
            log.info("操作计划已落盘 | userId={} | date={} | 条目 {} 条", userId, date, items.size());
            return plan;
        }
    }

    // ── P2-交易72：当天事后的「今日状态」回填（今天没动 / 想动没动）──

    /**
     * 回填某天的状态。**不改动 items / note**（与用户已写的计划不冲突），同日重复提交**幂等**：
     * 值没变就**不重写盘**（{@code recorded=false}），如实告诉调用方「早就记着了」。
     *
     * <p>为什么这条入口必须存在：用户 2026-09-23 原话「那我今天没有买卖 怎么告诉你呢 你还在等我的数据」——
     * 系统在等一个他**没有地方填**的状态。「没动」本身是完整信息（R119「零仓位也是交易」），
     * 不是数据缺失，所以这里既不发问、也不催，只给他一个落点。
     *
     * @throws com.adaiadai.core.domain.trading.TradingException 状态值不在已知集合内（不猜、不静默回落）
     */
    public DayStatusResult recordDayStatus(String userId, LocalDate date, String status) {
        if (!TradingPlan.isKnownDayStatus(status)) {
            throw new TradingException("这天只能记「没动」或「想动没动」两种，我认不出「" + status + "」");
        }
        synchronized (lockFor(userId, date)) {
            TradingPlan existing = planRepository.find(userId, date).orElse(null);
            if (existing != null && status.equals(existing.dayStatus())) {
                log.info("今日状态与已记一致，不重复落盘 | userId={} | date={} | status={}", userId, date, status);
                return new DayStatusResult(existing, false);
            }
            TradingPlan plan = existing == null
                    ? new TradingPlan(date, List.of(), "", status, LocalDateTime.now())
                    : new TradingPlan(existing.date(), existing.items(), existing.note(), status,
                            existing.createdAt() != null ? existing.createdAt() : LocalDateTime.now());
            planRepository.save(userId, plan);
            log.info("今日状态已落盘 | userId={} | date={} | status={} | 保留既有条目 {} 条",
                    userId, date, status, plan.items().size());
            return new DayStatusResult(plan, true);
        }
    }

    /** 回填结果：{@code recorded=false} = 这天早就记着同一个状态了（这次没写盘）。 */
    public record DayStatusResult(TradingPlan plan, boolean recorded) {}

    /**
     * 一句话 → 计划条目。**解析不出就不猜**：标的取不到 6 位代码时 symbol 留空（由用户补），
     * 条件词缺失时 condOp 留空（= 无条件，开盘即做）。
     */
    public TradingPlan.PlanItem parseItem(String raw) {
        String text = raw.trim();
        String symbol = first(SYMBOL, text);
        String action = detectAction(text);
        String condition = "";
        String condOp = "";
        BigDecimal condPrice = null;

        Matcher m = COND_PRICE.matcher(text);
        if (m.find()) {
            condPrice = new BigDecimal(m.group(1));
            String word = m.group().replaceAll("\\s*\\d+(?:\\.\\d+)?$", "").trim();
            condition = word + " " + m.group(1);
            condOp = isDownside(word, text) ? "LT" : "GE";
        }
        Integer qty = null;
        Matcher q = QTY.matcher(text);
        if (q.find()) {
            int n = Integer.parseInt(q.group(1));
            qty = q.group().contains("手") ? n * 100 : n;
        }
        return new TradingPlan.PlanItem(IdGenerator.monotonic("plan_"), action, symbol, "", condition,
                condOp, condPrice, qty, text, false);
    }

    /** 方向识别（买 / 卖 / 明确不动 / **没听懂**）——先判「不动」，再判卖，最后判买。
     *  注意单字「买 / 卖」也要认（用户原话常是「…以下买 500 股」「…清仓卖」）。
     *  <p>P1-7（独立审查 2026-10-03 修复）：**解析不出方向时返回 {@code UNKNOWN}，不再回落 HOLD**——
     *  原来「没听懂」被当成用户明确写的「明天不动」，review 里静默不参与判定（用户永远不知道这条没被理解）。 */
    static String detectAction(String text) {
        if (text.contains("不动") || text.contains("空仓") || text.contains("不操作")) return "HOLD";
        // P3（独立审查 2026-10-03 修复）：否定词优先——「不加仓 / 不卖出 / 别买」不是买入或卖出计划
        if (text.matches(".*(不加仓|不补仓|不买|别买|不卖|别卖|不减仓).*")) return "HOLD";
        if (text.matches(".*(卖出|卖了|卖|清仓|清掉|减仓|减半|止盈|止损|走了|拍掉|离场).*")) return "SELL";
        if (text.matches(".*(买入|买了|买|建仓|加仓|补仓|进场|低吸).*")) return "BUY";
        return "UNKNOWN";
    }

    private static boolean isDownside(String word, String text) {
        // P1-6（独立审查 2026-10-03 修复）：加「跌到 / 跌至 / 回落到 / 回调到」——
        // 原来只认「跌破/低于/以下/回到/回踩/破」，于是「跌到 5.5 我买」被误判为**上方**条件（触发反转）。
        if (word.contains("跌破") || word.contains("跌到") || word.contains("跌至")
                || word.contains("低于") || word.contains("以下") || word.contains("回落到")
                || word.contains("回调到") || word.contains("回到") || word.contains("回踩")
                || word.contains("破")) {
            return true;
        }
        if (word.contains("涨到") || word.contains("升至") || word.contains("高于")
                || word.contains("以上") || word.contains("突破") || word.contains("上破")) {
            return false;
        }
        // 兜底：只剩一个「到」——看它前面紧邻的动词定方向（有「跌/落/下/回调」→ 下方；有「涨/升/上」→ 上方）
        boolean down = text.matches(".*(跌|落|下|回调|回踩|破).{0,3}到.*");
        boolean up = text.matches(".*(涨|升|上|突破).{0,3}到.*");
        return down && !up;
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    // ── 收盘对账（只陈述事实）──

    /**
     * 计划 vs 实际：每条计划是否**触发**（当日行情是否触及条件）、是否**执行**（当日流水里有同标的同方向），
     * 以及当日**计划外的成交**（R96 四不原则的正面检查）。
     */
    public PlanReview review(String userId, LocalDate date) {
        Optional<TradingPlan> opt = planRepository.find(userId, date);
        List<TradeRecord> dayTrades = tradingHistoryRepository.findAll(userId).stream()
                .filter(t -> date.equals(t.entryDate()) && t.volume() > 0)
                .toList();
        List<ItemReview> items = new ArrayList<>();
        Set<String> plannedSymbols = new LinkedHashSet<>();
        int triggeredCount = 0;
        int executedCount = 0;
        if (opt.isPresent()) {
            for (TradingPlan.PlanItem it : opt.get().items()) {
                if (!it.symbol().isBlank()) plannedSymbols.add(it.symbol());
                if (!it.tradable()) {
                    // HOLD = 用户明确写的「不动」（合法计划）；UNKNOWN = **系统没听懂方向**——
                    // P1-7（独立审查 2026-10-03）：两者不再混为一谈，后者在 evidence 里如实说清。
                    items.add(new ItemReview(it.symbol(), it.name(), it.action(), it.condition(),
                            it.text(), null, null,
                            "UNKNOWN".equals(it.action()) ? "没听懂方向——请改写这一条" : null));
                    continue;
                }
                Boolean triggered = null;
                String evidence = null;
                if (!it.condOp().isBlank() && it.condPrice() != null && !it.symbol().isBlank()) {
                    Candle c = todayCandle(it.symbol(), date);
                    if (c != null) {
                        boolean hit = "LT".equals(it.condOp())
                                ? BigDecimal.valueOf(c.low()).compareTo(it.condPrice()) <= 0
                                : BigDecimal.valueOf(c.high()).compareTo(it.condPrice()) >= 0;
                        triggered = hit;
                        evidence = "当日 高 " + trim(c.high()) + " / 低 " + trim(c.low());
                        if (hit) triggeredCount++;
                    }
                }
                boolean executed = dayTrades.stream().anyMatch(t ->
                        t.symbol().equals(it.symbol()) && t.direction().name().equals(it.action()));
                if (executed) executedCount++;
                items.add(new ItemReview(it.symbol(), it.name(), it.action(), it.condition(),
                        it.text(), triggered, executed, evidence));
            }
        }
        // 计划外成交：P2（独立审查 2026-10-03 修复）——不再只按 symbol 判：
        // ① 标的根本不在计划里 → 报；② 标的在计划里但**方向做反了** → 也要报（原来被算作「计划内」静默放过）。
        Set<String> plannedDir = new LinkedHashSet<>();
        if (opt.isPresent()) {
            for (TradingPlan.PlanItem it : opt.get().items()) {
                if (it.tradable() && !it.symbol().isBlank()) plannedDir.add(it.symbol() + "|" + it.action());
            }
        }
        List<String> unplanned = new ArrayList<>();
        for (TradeRecord t : dayTrades) {
            if (plannedDir.contains(t.symbol() + "|" + t.direction().name())) continue;
            boolean symbolPlanned = plannedSymbols.contains(t.symbol());
            unplanned.add(t.symbol() + " " + t.name() + " " + t.direction() + " " + t.volume() + "股"
                    + (symbolPlanned ? "（方向与计划相反）" : ""));
        }
        return new PlanReview(date, opt.isPresent(), items, unplanned, triggeredCount, executedCount,
                opt.map(TradingPlan::dayStatus).orElse(TradingPlan.DAY_STATUS_NONE));
    }

    private Candle todayCandle(String symbol, LocalDate date) {
        try {
            List<Candle> cs = klineService.klineRange(symbol, date, date);
            return cs.isEmpty() ? null : cs.get(0);
        } catch (RuntimeException e) {
            log.warn("对账取日线失败（触发判定降级为 null）| symbol={} | {}", symbol, e.getMessage());
            return null;
        }
    }

    private static BigDecimal trim(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros();
    }

    // ── 视图对象 ──

    /** 收盘对账结果：**只陈述事实**，不含判断与建议。 */
    public record PlanReview(LocalDate date, boolean hasPlan, List<ItemReview> items,
                             List<String> unplanned, int triggeredCount, int executedCount,
                             // P2-交易72（2026-10-05）：这天用户事后回填的状态（"" = 没填）。
                             // 参与对账是为了**不把「没动」当成缺数据**——它与「今天没有成交记录」是互相印证的两句话。
                             String dayStatus) {}

    /**
     * 一条计划的对账。
     *
     * @param triggered 条件是否被当日行情触及（null = 无法判定：无条件 / 无行情 / 无标的）
     * @param executed  当日是否有同标的同方向的成交（null = HOLD 类不适用）
     */
    public record ItemReview(String symbol, String name, String action, String condition,
                             String text, Boolean triggered, Boolean executed, String evidence) {}
}
