package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.CommissionCalculator;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.SnapshotHolding;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TransferRecord;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * EquityCurveService — 资金曲线聚合（2026-09-04 晚间自主批 IV，决策文档方案 A）。
 * <p>
 * 数据口径：逐笔流水 + 转账 + 当前持仓快照（account.json 现金/本金锚定现值）。
 * 曲线点 = 每个有行情的交易日 t 的收盘净资产：现金 + 持仓市值。
 * <ul>
 *   <li><b>现金</b>：以 account.json 当前现金反向锚定（起点现金 = 现值 − 全程交易/转账净变化）——
 *       历史导入资金基准不可逐日追溯，锚定保证末端 = 券商真实现金，中间路径由事件驱动</li>
 *   <li><b>持仓数量</b>：正向回放流水（买入 +、卖出 −），<b>底仓</b>（positions 有但流水解释不了的
 *       部分，即批次 INIT）作为期初恒持注入——已清仓又复购/已清仓股的历史持有期都能还原</li>
 *   <li><b>收盘价</b>：K 线（tdx 本地优先 → 网络兜底）按日取；当日缺 K（停牌/新股）沿用前收盘，
 *       仍缺 → 用成本价近似（不低估在持资产）；纯现金日无 close 也可成点</li>
 *   <li><b>净值</b> netValue = totalAssets / invested(t)（invested = 期初投入缺口 + 逐笔转账累计净投入）；
 *       invested ≤0 时净值 null（本金未设不给误导数值，P2-交易31 同口径）</li>
 *   <li><b>回撤</b> drawdown = 历史峰值到当日的回落比例（0 = 新高）</li>
 * </ul>
 * 交易现金按 {@link CommissionCalculator} 统一口径（买入含费、卖出净得），与记录/快照推导一致。
 */
@Service
public class EquityCurveService {

    private static final Logger log = LoggerFactory.getLogger(EquityCurveService.class);

    /** 曲线点：date 收盘净资产。netValue/drawdown 可空（invested ≤0 / 单点）。 */
    public record EquityPoint(LocalDate date, BigDecimal totalAssets, BigDecimal cash,
                              BigDecimal marketValue, BigDecimal invested,
                              BigDecimal netValue, BigDecimal drawdown) {}

    /**
     * 交易事件（归日）：现金流 + 数量变化 + 单笔价格/量/方向。
     * 2026-09-16 增后三项——逐日「当日盈亏」（券商口径）需要按笔还原，只用现金流算不出来。
     */
    private record TradeEvent(LocalDate date, String symbol, double cashDelta, int qtyDelta,
                              double price, int volume, boolean buy) {}

    /**
     * 曲线结果：points 按日期升序；stats 附开始/结束/初始投入/最新净投入。
     * <p>
     * 2026-09-16：新增 {@code dailyPnl}（交易日期 → 当日盈亏，券商口径）——周期盈亏（日/周/月）改用它
     * **逐日累加**，不再拿总资产做差分（差分会把「曲线本身的估值偏差」当成盈亏：
     * 实测 2026-09-14 差分口径 −1102.40 vs 券商 App +2225.59，差 3300+，根因是曲线在锚定日
     * 总资产就偏高 4330）。保留 4 参构造器：既有调用方与测试不必改。
     */
    public record EquityCurve(List<EquityPoint> points, int skippedDays,
                              String startDate, String endDate,
                              Map<String, BigDecimal> dailyPnl) {
        public EquityCurve(List<EquityPoint> points, int skippedDays,
                           String startDate, String endDate) {
            this(points, skippedDays, startDate, endDate, Map.of());
        }
    }

    private final TradingHistoryRepository historyRepository;
    private final PositionRepository positionRepository;
    private final TransferRepository transferRepository;
    private final AccountSnapshotRepository accountRepository;
    private final TradingAnchorRepository anchorRepository;
    private final KlineService klineService;

    public EquityCurveService(TradingHistoryRepository historyRepository,
                              PositionRepository positionRepository,
                              TransferRepository transferRepository,
                              AccountSnapshotRepository accountRepository,
                              TradingAnchorRepository anchorRepository,
                              KlineService klineService) {
        this.historyRepository = historyRepository;
        this.positionRepository = positionRepository;
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.anchorRepository = anchorRepository;
        this.klineService = klineService;
    }

    /** 生成资金曲线；无账户快照（从未导入/记录）→ 空曲线（不抛错）。 */
    public EquityCurve build(String userId) {
        List<TradeRecord> trades = historyRepository.findAll(userId);
        List<TransferRecord> transfers = transferRepository.findAll(userId);
        List<Position> holdings = positionRepository.findAll(userId);
        AccountSnapshot snap = accountRepository.findLatest(userId).orElse(null);
        if (snap == null) {
            log.info("资金曲线：无账户快照，返回空 | userId={}", userId);
            return new EquityCurve(List.of(), 0, "", "");
        }
        if (trades.isEmpty() && holdings.isEmpty() && transfers.isEmpty()) {
            return new EquityCurve(List.of(), 0, "", "");
        }

        double cashNow = snap.cash().doubleValue();
        double principalNow = snap.principal().doubleValue();

        // ── 底仓（positions 有但流水解释不了）→ 期初恒持注入 ──
        Map<String, Integer> baseQty = new LinkedHashMap<>();
        Map<String, Double> baseCost = new LinkedHashMap<>();
        Map<String, Integer> currentQty = new LinkedHashMap<>();
        for (Position p : holdings) {
            currentQty.put(p.symbol(), p.quantity());
            int netFlow = 0;
            double avgCost = p.avgCost() != null ? p.avgCost().doubleValue() : 0;
            for (TradeRecord t : trades) {
                if (t.symbol().equals(p.symbol())) {
                    netFlow += t.direction() == TradeDirection.BUY ? t.volume() : -t.volume();
                }
            }
            int gap = p.quantity() - netFlow;
            if (gap > 0) {
                baseQty.put(p.symbol(), gap);
                baseCost.put(p.symbol(), avgCost);
            }
        }

        // 涉及的全部标的
        Set<String> symbols = new LinkedHashSet<>(currentQty.keySet());
        for (TradeRecord t : trades) symbols.add(t.symbol());

        // ── 事件归日（交易现金流 + 数量变化 + 转账净投入）──
        // 2026-09-16：TradeEvent 提升为类级 record（逐日盈亏计算要用它的 price/volume/buy）。
        record TransferEvent(LocalDate date, double netDelta) {}
        List<TradeEvent> tradeEvents = trades.stream()
                .filter(t -> t.entryDate() != null)
                .map(t -> {
                    double cashDelta;
                    if (t.direction() == TradeDirection.BUY) {
                        cashDelta = -CommissionCalculator.buyCost(
                                t.symbol(), t.price(), t.volume()).doubleValue();
                    } else {
                        cashDelta = CommissionCalculator.sellProceeds(
                                t.symbol(), t.price(), t.volume()).doubleValue();
                    }
                    int qtyDelta = t.direction() == TradeDirection.BUY ? t.volume() : -t.volume();
                    return new TradeEvent(t.entryDate(), t.symbol(), cashDelta, qtyDelta,
                            t.price() != null ? t.price().doubleValue() : 0.0, t.volume(),
                            t.direction() == TradeDirection.BUY);
                })
                .sorted(Comparator.comparing(TradeEvent::date))
                .toList();
        List<TransferEvent> transferEvents = transfers.stream()
                .map(t -> new TransferEvent(t.date(), t.isIn() ? t.amount().doubleValue()
                        : -t.amount().doubleValue()))
                .sorted(Comparator.comparing(TransferEvent::date))
                .toList();

        // 日期边界
        if (tradeEvents.isEmpty() && transferEvents.isEmpty() && baseQty.isEmpty()) {
            return new EquityCurve(List.of(), 0, "", "");
        }
        LocalDate minDate = LocalDate.now();
        for (TradeEvent e : tradeEvents) if (e.date().isBefore(minDate)) minDate = e.date();
        for (TransferEvent e : transferEvents) if (e.date().isBefore(minDate)) minDate = e.date();
        LocalDate maxDate = LocalDate.now();

        // ── 收盘价（K 线直查区间；缺失沿用前收，再缺成本价兜底）──
        Map<String, Map<LocalDate, Double>> closes = new HashMap<>();
        for (String symbol : symbols) {
            try {
                List<Candle> cs = klineService.klineRange(symbol, minDate, maxDate);
                Map<LocalDate, Double> byDate = new TreeMap<>();
                for (Candle c : cs) byDate.put(c.date(), c.close());
                closes.put(symbol, byDate);
            } catch (Exception e) {
                log.warn("资金曲线：K 线获取失败 | symbol={} | {}", symbol, e.getMessage());
                closes.put(symbol, Map.of());
            }
        }

        // 交易日集合 = 任一标的 K 线有值的日期 ∪ 事件日（纯现金日/停牌日也出点，防曲线跳空）
        Set<LocalDate> tradingDates = new TreeSet<>();
        for (Map<LocalDate, Double> m : closes.values()) tradingDates.addAll(m.keySet());
        for (TradeEvent e : tradeEvents) tradingDates.add(e.date());
        for (TransferEvent e : transferEvents) tradingDates.add(e.date());

        // ── 状态推进：现金反向锚定起点；数量从底仓起正向回放 ──
        double totalCashDelta = 0;
        for (TradeEvent e : tradeEvents) totalCashDelta += e.cashDelta();
        double totalTransferDelta = 0;
        for (TransferEvent e : transferEvents) totalTransferDelta += e.netDelta();
        double cash = cashNow - totalCashDelta - totalTransferDelta; // 起点现金（锚定现值反推）
        double investedSoFar = principalNow - totalTransferDelta;    // 起点前投入（含历史 imports/cash 基准）
        if (investedSoFar < 0) investedSoFar = 0;

        Map<String, Integer> qty = new HashMap<>(baseQty);
        Map<String, Double> qtyCost = new HashMap<>(baseCost);
        Map<String, Double> lastClose = new HashMap<>();

        // ── 锚定重置（2026-09-15 账实口径修正）──
        // 券商快照（持仓 replace 导入）是锚定日的**真实持仓**，锚定日及更早的成交已包含在内。
        // 纯流水回放会在「历史成交只补了买入、卖出没导全」的标的上凭空多出持仓——生产实测
        // 000776/600487 各多 600/400 股，资金曲线与周期盈亏的市值整体虚高。
        // 因此日期走到锚定日时，把持仓数量重置为快照基线，之后只叠加锚定日**之后**的流水。
        SnapshotAnchor anchor = anchorRepository.find(userId);
        // P2-交易70（2026-09-23）：持仓重置必须用**持仓快照的基准日**（positionsReplace），
        // 不能用 latest()——后者会对 cashImport 取最大值，于是「今天刚导的资金股份查询」会把
        // 持仓锚定日一起往后拽，持仓重置被推迟、锚定日到今天的这一段退回「底仓 + 流水回放」。
        // 生产实据：2026-09-23 用户只导了资金（cashImport=09-23，positionsReplace 仍 09-18），
        // 曲线 09-21/09-22 的持仓市值被回放成 125,925 / 125,364（真实 09-22 只有 105,488，虚高约 2 万），
        // 并让周期盈亏的百分比分母（base）跟着虚高（金额不受影响——它逐日累加）。
        // 「账的日期」与「现金/持仓的日期」本就是三件事，混用一个日期就会出这种错（见 P2-交易69）。
        LocalDate anchorDate = anchor != null ? anchor.positionsReplace() : null;
        List<SnapshotHolding> anchorHoldings = anchorRepository.holdings(userId);
        boolean anchorHoldingsKnown = anchorRepository.holdingsRecorded(userId);
        boolean anchorApplied = false;
        // 快照基线只有数量、无成本 → 成本取当前持仓（positions）的成本价，缺则用当时收盘/0 兜底。
        Map<String, Double> anchorCost = new HashMap<>();
        for (SnapshotHolding h : anchorHoldings) {
            Position p = holdings.stream().filter(x -> x.symbol().equals(h.symbol())).findFirst().orElse(null);
            anchorCost.put(h.symbol(),
                    p != null && p.avgCost() != null ? p.avgCost().doubleValue() : 0.0);
        }

        int ti = 0, ri = 0;
        double peak = Double.MIN_VALUE;
        List<EquityPoint> points = new ArrayList<>();
        Map<String, BigDecimal> dailyPnl = new LinkedHashMap<>();
        List<String> pnlNotes = new ArrayList<>();
        int skipped = 0;
        for (LocalDate d : tradingDates) {
            // 2026-09-16 逐日盈亏：事件处理**前**快照「昨日持仓 / 昨收」，并收集当日全部成交
            // （昨收必须现在取——下面市值循环会把 lastClose 更新成今收）
            Map<String, Integer> qtyBefore = new HashMap<>(qty);
            Map<String, Double> prevClose = new HashMap<>(lastClose);
            List<TradeEvent> dayEvents = new ArrayList<>();
            // 当日事件（交易 → 转账，先后无交叉）
            while (ti < tradeEvents.size() && !tradeEvents.get(ti).date().isAfter(d)) {
                TradeEvent e = tradeEvents.get(ti++);
                dayEvents.add(e);
                cash += e.cashDelta();
                int newQty = qty.getOrDefault(e.symbol(), 0) + e.qtyDelta();
                qty.put(e.symbol(), newQty);
                if (!qtyCost.containsKey(e.symbol()) && e.qtyDelta() > 0) {
                    // 底仓未覆盖的买入 → 成本（事件内近似：该股成本价此时可用 avgCost 现值，仅 fallback 用）
                    Position p = holdings.stream().filter(h -> h.symbol().equals(e.symbol())).findFirst().orElse(null);
                    qtyCost.put(e.symbol(), p != null && p.avgCost() != null ? p.avgCost().doubleValue() : 0);
                }
                if (newQty <= 0) qtyCost.remove(e.symbol());
            }
            while (ri < transferEvents.size() && !transferEvents.get(ri).date().isAfter(d)) {
                cash += transferEvents.get(ri).netDelta();
                investedSoFar += transferEvents.get(ri).netDelta();
                if (investedSoFar < 0) investedSoFar = 0;
                ri++;
            }

            // ── 锚定重置：到达锚定日 → 持仓以券商快照基线为准（丢弃流水回放出的虚假持仓）──
            if (!anchorApplied && anchorDate != null && anchorHoldingsKnown && !d.isBefore(anchorDate)) {
                qty.clear();
                qtyCost.clear();
                for (SnapshotHolding h : anchorHoldings) {
                    if (h.quantity() > 0) {
                        qty.put(h.symbol(), h.quantity());
                        qtyCost.put(h.symbol(), anchorCost.getOrDefault(h.symbol(), 0.0));
                    }
                }
                anchorApplied = true;
            }

            // 当日市值
            double marketValue = 0;
            boolean anyPrice = false;
            for (Map.Entry<String, Integer> en : qty.entrySet()) {
                if (en.getValue() <= 0) continue;
                Double c = closes.getOrDefault(en.getKey(), Map.of()).get(d);
                if (c == null) {
                    // 停牌/缺 K：沿用前收；仍无 → 成本价近似（不低估在持资产）
                    Double prev = lastClose.get(en.getKey());
                    if (prev != null) c = prev;
                    // P2-交易42（2026-09-14 核对）：成本价近似对**负成本**持仓会算出**负市值**——
                    // 负成本（反复做 T / 分红摊到 0 下，实测 600601 −5.078）在券商口径里表示
                    // 「已回本还有富余」，但资产绝不会是负数。这里以 0 兜底（宁可保守低估，
                    // 也不能让缺 K 的那天把总资产/净值/回撤拖成负值）。
                    else c = Math.max(0.0, qtyCost.getOrDefault(en.getKey(), 0.0));
                } else {
                    anyPrice = true;
                    lastClose.put(en.getKey(), c);
                }
                marketValue += en.getValue() * c;
            }
            if (!anyPrice && qty.isEmpty()) marketValue = 0;

            // 2026-09-16：当日盈亏（券商口径）——周/月由它逐日累加得出，不再对总资产做差分
            dailyPnl.put(d.toString(), dayPnlOf(d, dayEvents, qtyBefore, prevClose, closes, pnlNotes));

            double total = cash + marketValue;
            if (total > peak) peak = total;
            double drawdown = peak > 0 ? (peak - total) / peak : 0;
            BigDecimal invested = BigDecimal.valueOf(investedSoFar);
            BigDecimal netValue = investedSoFar > 0
                    ? BigDecimal.valueOf(total).divide(BigDecimal.valueOf(investedSoFar), 4, RoundingMode.HALF_UP)
                    : null;
            points.add(new EquityPoint(d,
                    round2(total), round2(cash), round2(marketValue), invested,
                    netValue, round4(drawdown)));
        }
        if (points.isEmpty()) skipped = 1;
        if (!pnlNotes.isEmpty()) {
            log.warn("资金曲线：部分标的当日盈亏未计入（缺行情/昨收）| userId={} | {}", userId, pnlNotes);
        }
        log.info("资金曲线生成 | userId={} | 交易日 {} 点（跳过 {}）| 起点 {} | 当前总资产 {}",
                userId, points.size(), skipped,
                points.isEmpty() ? "-" : points.get(0).date(), round2(cash + marketValue(points)));
        return new EquityCurve(points, skipped,
                points.isEmpty() ? "" : points.get(0).date().toString(),
                points.isEmpty() ? "" : points.get(points.size() - 1).date().toString(),
                dailyPnl);
    }

    private double marketValue(List<EquityPoint> pts) {
        return pts.isEmpty() ? 0 : pts.get(pts.size() - 1).marketValue().doubleValue();
    }

    /**
     * 区间盈亏（2026-09-15 用户要求：像券商 App 一样给出每日/每周/每月的盈亏金额与比例）。
     * <p>
     * 口径（券商「当日参考盈亏」逐日累加，2026-09-16 修正）：
     * <ul>
     *   <li><b>逐日盈亏</b> = 买卖逐笔还原的当日盈亏（卖出净额−昨收×卖量 / 旧仓 今收−昨收 /
     *       当日买入 今收−含费买入均价），与账户卡「当日盈亏」同源语义</li>
     *   <li><b>区间盈亏</b> = 区间内逐日盈亏之和（券商 App 的周/月就是这么来的）；
     *       <b>区间收益率</b> = 区间盈亏 ÷ 区间前一日总资产</li>
     *   <li>区间起点前没有曲线点（如月初，或锚定日之前不可追溯）→ 比例给 null 且 {@code partial=true}，
     *       不编造一个看起来精确的百分比</li>
     * </ul>
     * <p>
     * 为什么不再用总资产差分：差分会把「曲线自身的估值偏差」当成盈亏——实测 2026-09-14 差分口径
     * −1102.40 vs 券商 App 真实 **+2225.59**（差 3300+），根因是曲线在锚定日的总资产就偏高
     * （券商快照真值 79,231.93 vs 曲线 83,561.93）。改为逐笔口径后同日复算 2225.59，与券商一字不差。
     */
    public record PeriodPnl(String key, BigDecimal pnl, BigDecimal pct, BigDecimal base,
                            LocalDate from, boolean partial) {}

    /** 日/周/月区间盈亏（券商口径的三档展示）。asOf = 曲线最后一个交易日。 */
    public record PnlPeriods(PeriodPnl today, PeriodPnl week, PeriodPnl month,
                             String asOf, LocalDate anchorDate, String note) {}

    /** 计算今日 / 本周 / 本月盈亏（金额 + 比例）。 */
    public PnlPeriods periods(String userId) {
        EquityCurve curve = build(userId);
        List<EquityPoint> pts = curve.points();
        SnapshotAnchor anchor = anchorRepository.find(userId);
        // P2-交易70：与曲线持仓重置同口径——可信起点 = **持仓快照基准日**（不是 latest()）。
        // 用 latest() 会把区间起点判得比实际更靠后，于是「本周」被无谓标成 partial（数据其实可信）。
        LocalDate anchorDate = anchor != null ? anchor.positionsReplace() : null;
        if (pts.isEmpty()) {
            return new PnlPeriods(null, null, null, "", anchorDate, "还没有资金或成交记录，算不出盈亏");
        }
        // 2026-09-16：区间盈亏 = 逐日「当日盈亏」（券商口径）累加，不再对总资产做差分
        Map<LocalDate, BigDecimal> dailyPnl = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : curve.dailyPnl().entrySet()) {
            dailyPnl.put(LocalDate.parse(e.getKey()), e.getValue());
        }
        LocalDate today = LocalDate.now();
        PeriodPnl t = window(pts, dailyPnl, today, today, anchorDate, "today");
        PeriodPnl w = window(pts, dailyPnl, today.with(java.time.DayOfWeek.MONDAY), today, anchorDate, "week");
        PeriodPnl m = window(pts, dailyPnl, today.withDayOfMonth(1), today, anchorDate, "month");
        log.info("区间盈亏 | userId={} | 日 {} | 周 {} | 月 {} | 锚定日 {}",
                userId, t.pnl(), w.pnl(), m.pnl(), anchorDate);
        return new PnlPeriods(t, w, m, pts.get(pts.size() - 1).date().toString(), anchorDate, "");
    }

    /** 单区间汇总：逐日盈亏按 [from, to] 求和；base = 区间前最后一个曲线点的总资产（算比例用）。 */
    private PeriodPnl window(List<EquityPoint> pts, Map<LocalDate, BigDecimal> dailyPnl,
                             LocalDate from, LocalDate to, LocalDate anchorDate, String key) {
        BigDecimal base = null;
        for (EquityPoint p : pts) {
            if (p.date().isBefore(from)) base = p.totalAssets();
        }
        BigDecimal pnl = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, BigDecimal> e : dailyPnl.entrySet()) {
            if (!e.getKey().isBefore(from) && !e.getKey().isAfter(to)) pnl = pnl.add(e.getValue());
        }
        pnl = pnl.setScale(2, RoundingMode.HALF_UP);
        BigDecimal pct = (base != null && base.signum() > 0)
                ? pnl.divide(base, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                : null;
        boolean partial = base == null || (anchorDate != null && from.isBefore(anchorDate));
        return new PeriodPnl(key, pnl, pct, base, from, partial);
    }

    /**
     * 单日盈亏（券商「当日参考盈亏」口径，2026-09-16）——与 {@code TradingAppService.dailyPnlDetail}
     * 同源语义，逐日算出后可累加成周/月：
     * <ul>
     *   <li>卖出部分 = 卖出净额（已扣费）− 昨收 × 卖量</li>
     *   <li>旧仓剩余 = (今收 − 昨收) × (昨日持仓 − 卖量)</li>
     *   <li>当日买入 = (今收 − 含费买入均价) × 买量</li>
     * </ul>
     * 缺今收或昨收 → 该标的当日不计入并记 note（不猜、不用 0 冒充）。
     * <p>
     * 口径验证（生产 2026-09-14）：云南锗业 +735 + 有研新材 +1510 + 方正科技 −2 − 卖出手续费 17.41
     * = 2225.59，与券商 App 显示一字不差。
     */
    private BigDecimal dayPnlOf(LocalDate d, List<TradeEvent> dayEvents,
                                Map<String, Integer> qtyBefore, Map<String, Double> prevClose,
                                Map<String, Map<LocalDate, Double>> closes,
                                List<String> notes) {
        double pnl = 0;
        Set<String> touched = new LinkedHashSet<>(qtyBefore.keySet());
        for (TradeEvent e : dayEvents) touched.add(e.symbol());
        for (String sym : touched) {
            Double cToday = closes.getOrDefault(sym, Map.of()).get(d);
            if (cToday == null) {
                // 当日无价（停牌 / 数据源缺该日）→ 该标的当日盈亏记 0，**但必须说出来**：
                // 静默 0 会让「周/月偏小」看不出来（本批就是踩了这个才发现路径）
                if (qtyBefore.getOrDefault(sym, 0) > 0 || dayEvents.stream().anyMatch(e -> e.symbol().equals(sym))) {
                    notes.add(sym + "：当日（" + d + "）缺收盘价，当日盈亏未计入");
                }
                continue;
            }
            Double cPrev = prevClose.get(sym);
            double sellNet = 0, buyCost = 0;
            int sellVol = 0, buyVol = 0;
            for (TradeEvent e : dayEvents) {
                if (!e.symbol().equals(sym)) continue;
                if (e.buy()) { buyVol += e.volume(); buyCost += -e.cashDelta(); }
                else { sellVol += e.volume(); sellNet += e.cashDelta(); }
            }
            if (sellVol > 0) {
                if (cPrev == null) {
                    notes.add(sym + "：缺昨收，当日卖出 " + sellVol + " 股的盈亏未计入");
                } else {
                    pnl += sellNet - cPrev * sellVol;
                }
            }
            int rest = qtyBefore.getOrDefault(sym, 0) - sellVol;
            if (rest > 0) {
                if (cPrev == null) {
                    notes.add(sym + "：缺昨收，旧仓 " + rest + " 股日浮动未计入");
                } else {
                    pnl += (cToday - cPrev) * rest;
                }
            }
            if (buyVol > 0) {
                // A 股 T+1：当日买入不会当日卖出，故不与上面两段重叠
                double buyAvg = buyCost / buyVol; // 含费买入均价（cashDelta 已含费）
                pnl += (cToday - buyAvg) * buyVol;
            }
        }
        return BigDecimal.valueOf(pnl).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
