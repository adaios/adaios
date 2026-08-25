package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.CommissionCalculator;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingLot;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TradingLotService — 交易批次推导与行为标注（RFC 20260825 逐笔批次跟踪与行为纠偏）。
 * <p>
 * 批次视图 = 从逐笔流水（{@link TradingHistoryRepository}）**重放推导**的投影，不落盘：
 * <ul>
 *   <li>买入：同标的 + 同方向 + **同日** 合并为一个批次（一天最多一个买批），成本 = 加权平均（含费）</li>
 *   <li>卖出：不建批次，按 **LIFO** 先扣最近买入批次，数量不够往前扣；跨批按各自成本分算已实现盈亏</li>
 *   <li>批次剩余 0 = 关闭（形成回合，realizedPnl = 整批已实现盈亏）</li>
 *   <li>初始批次：positions.md 有持仓但流水覆盖不到的底仓 → {@code {symbol}_INIT}（最早建仓，LIFO 最后扣）</li>
 * </ul>
 * 行为标注（记录即标注，进当日操作总结/复盘）：亏损加仓 / 追高 / 短线新开 / 破止损未走 / 浮盈回吐 / 短线超期。
 */
@Service
public class TradingLotService {

    private static final Logger log = LoggerFactory.getLogger(TradingLotService.class);

    /** 默认止损：买入价 −7%（RFC 20260825 用户确认：批次止损未设按 −7% 兜底，可后改）。 */
    public static final BigDecimal DEFAULT_STOP_LOSS_RATIO = BigDecimal.valueOf(0.93);

    /** 短线买点类型（博一下语义）。 */
    private static final Set<String> SHORT_TERM_BUY_POINTS = Set.of("SB1", "暴力特噗", "深水炸弹", "单针");

    /** 浮盈回吐判定：峰值浮盈至少 ≥ 20% 才判（没浮盈过谈不上回吐）。 */
    private static final BigDecimal GIVEBACK_MIN_PEAK_PCT = BigDecimal.valueOf(20);

    /** 浮盈回吐判定：从峰值回吐 ≥ 50% 触发。 */
    private static final BigDecimal GIVEBACK_RATIO_PCT = BigDecimal.valueOf(50);

    /** 短线超期：持有超过 5 个交易日（周末近似，法定节假日不追——P2 接节假日表）。 */
    private static final int SHORT_OVERDUE_TRADING_DAYS = 5;

    private final TradingHistoryRepository tradingHistoryRepository;
    private final PositionRepository positionRepository;
    private final MarketDataSource marketDataSource;
    private final KlineService klineService;

    public TradingLotService(TradingHistoryRepository tradingHistoryRepository,
                             PositionRepository positionRepository,
                             MarketDataSource marketDataSource,
                             KlineService klineService) {
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.positionRepository = positionRepository;
        this.marketDataSource = marketDataSource;
        this.klineService = klineService;
    }

    // ── 批次推导 ──

    /**
     * 批次重放推导：按 symbol 返回全部批次（含已关闭回合），每 symbol 内按买入日期升序。
     * 失败兜底：流水/持仓任一为空 → 返回空结构，不抛错（展示降级）。
     */
    public Map<String, List<TradingLot>> derive(String userId) {
        List<TradeRecord> all = tradingHistoryRepository.findAll(userId);
        Map<String, List<TradeRecord>> bySymbol = new LinkedHashMap<>();
        for (TradeRecord t : all) {
            bySymbol.computeIfAbsent(t.symbol(), k -> new ArrayList<>()).add(t);
        }
        Map<String, Position> holdings = positionRepository.findAll(userId).stream()
                .collect(Collectors.toMap(Position::symbol, p -> p, (a, b) -> a));
        // 遍历 symbol = 流水 ∪ 持仓（持仓有但流水空的 symbol 也要生成初始批次兜底）
        Set<String> symbols = new java.util.LinkedHashSet<>(bySymbol.keySet());
        symbols.addAll(holdings.keySet());
        Map<String, List<TradingLot>> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            result.put(symbol, replaySymbol(symbol, bySymbol.getOrDefault(symbol, List.of()), holdings.get(symbol)));
        }
        return result;
    }

    /** 单标的流水重放 → 批次列表（含初始批次兜底与回合）。 */
    private List<TradingLot> replaySymbol(String symbol, List<TradeRecord> trades, Position holding) {
        List<TradeRecord> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator
                .comparing((TradeRecord t) -> effectiveDate(t))
                .thenComparing(t -> t.tradeTime() != null ? t.tradeTime() : LocalTime.MIN)
                .thenComparing(t -> t.timestamp() != null ? t.timestamp() : LocalDateTime.MIN));

        List<MutableLot> open = new ArrayList<>();   // 未关闭买批（尾部 = 最新买入）
        List<MutableLot> closed = new ArrayList<>(); // 已关闭（回合）

        // 初始批次**前置**（后端审查 P1-1）：流水覆盖不到的底仓先建（最早建仓，LIFO 最后扣）——
        // 否则「底仓快照 + 只有卖出的流水」时卖单无批次可扣、差额丢失、初始批次虚增。
        if (holding != null && holding.quantity() > 0) {
            int flowNet = sorted.stream().filter(t -> t.direction() == TradeDirection.BUY)
                    .mapToInt(TradeRecord::volume).sum()
                    - sorted.stream().filter(t -> t.direction() == TradeDirection.SELL)
                    .mapToInt(TradeRecord::volume).sum();
            int gap = holding.quantity() - flowNet;
            if (gap > 0) {
                MutableLot init = new MutableLot(new TradingLot(
                        symbol + "_INIT", symbol,
                        holding.name() != null && !holding.name().isBlank() ? holding.name() : symbol,
                        holding.entryDate(), gap, gap, holding.avgCost(),
                        holding.stopLossPrice(), holding.buyPoint(), holding.role(), true, BigDecimal.ZERO));
                open.add(0, init);
            }
        }

        for (TradeRecord t : sorted) {
            if (t.direction() == TradeDirection.BUY) {
                MutableLot last = open.isEmpty() ? null : open.get(open.size() - 1);
                LocalDate d = effectiveDate(t);
                if (last != null && !last.lot.initial()
                        && last.lot.buyDate() != null && last.lot.buyDate().equals(d)) {
                    // 按日合并：加权成本（含费）；止损/买点取最近一次 BUY（与持仓口径一致）
                    int newVol = last.lot.volume() + t.volume();
                    BigDecimal totalCost = last.lot.costPrice()
                            .multiply(BigDecimal.valueOf(last.lot.volume()))
                            .add(buyTotalCost(t));
                    BigDecimal newCost = totalCost.divide(BigDecimal.valueOf(newVol), 4, RoundingMode.HALF_UP);
                    BigDecimal stop = t.stopLossPrice() != null ? t.stopLossPrice() : last.lot.stopLossPrice();
                    String bp = t.buyPoint() != null ? t.buyPoint() : last.lot.buyPoint();
                    last.lot = new TradingLot(last.lot.lotId(), symbol, last.lot.name(), d, newVol, newVol,
                            newCost, stop, bp, last.lot.role(), false, BigDecimal.ZERO);
                } else {
                    BigDecimal unit = buyTotalCost(t).divide(BigDecimal.valueOf(t.volume()), 4, RoundingMode.HALF_UP);
                    String name = t.name() != null && !t.name().isBlank() ? t.name() : symbol;
                    open.add(new MutableLot(new TradingLot(
                            symbol + "_" + d + "_B", symbol, name, d, t.volume(), t.volume(),
                            unit, t.stopLossPrice(), t.buyPoint(), null, false, BigDecimal.ZERO)));
                }
            } else { // SELL：LIFO 扣最近买批
                int remaining = t.volume();
                BigDecimal sellFee = sellFeeOf(t); // 后端审查 P2-2：回合盈亏扣卖出费用（佣金/印花税/过户费）
                for (int i = open.size() - 1; i >= 0 && remaining > 0; i--) {
                    MutableLot m = open.get(i);
                    int sellQty = Math.min(m.lot.remaining(), remaining);
                    BigDecimal thisRealized = t.price().subtract(m.lot.costPrice())
                            .multiply(BigDecimal.valueOf(sellQty))
                            // 卖出费用按卖出数量分摊到被扣批次
                            .subtract(sellFee.multiply(BigDecimal.valueOf(sellQty))
                                    .divide(BigDecimal.valueOf(t.volume()), 4, RoundingMode.HALF_UP));
                    int newRemaining = m.lot.remaining() - sellQty;
                    BigDecimal newRealized = m.lot.realizedPnl().add(thisRealized);
                    if (newRemaining <= 0) {
                        m.lot = withRemaining(m.lot, 0, newRealized);
                        open.remove(i);
                        closed.add(m);
                    } else {
                        m.lot = withRemaining(m.lot, newRemaining, newRealized);
                    }
                    remaining -= sellQty;
                }
                if (remaining > 0) {
                    log.warn("流水卖出超过买入批次（补录流水/快照口径差异，差额无批次可扣）| symbol={} | 差额 {} 股",
                            symbol, remaining);
                }
            }
        }

        List<TradingLot> lots = new ArrayList<>();
        for (MutableLot m : open) lots.add(m.lot);
        for (MutableLot m : closed) lots.add(m.lot);
        // 初始批次（buyDate 可 null）排最前（最早建仓）；其余按买入日期升序
        lots.sort(Comparator.comparing(TradingLot::buyDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(TradingLot::lotId));
        return lots;
    }

    /** 单笔卖出费用：导入流水用券商实际费用（fee）；手动记录用模型模拟（卖出净得差）。 */
    private BigDecimal sellFeeOf(TradeRecord t) {
        if (t.fee() != null) return t.fee();
        BigDecimal gross = t.price().multiply(BigDecimal.valueOf(t.volume()));
        return gross.subtract(CommissionCalculator.sellProceeds(t.symbol(), t.price(), t.volume()));
    }

    // ── 对账 ──

    /**
     * 流水重放持仓 vs positions.md 快照对账（每 symbol 一行，只报告不改数据）。
     * 防「漏导一天成交 → 批次/持仓静默错下去」。
     */
    public List<TradingAppService.ReconcileLine> reconcile(String userId) {
        List<TradeRecord> all = tradingHistoryRepository.findAll(userId);
        Map<String, int[]> acc = new LinkedHashMap<>(); // symbol → {count, netVolume}
        Map<String, String> names = new HashMap<>();
        for (TradeRecord t : all) {
            int[] a = acc.computeIfAbsent(t.symbol(), k -> new int[2]);
            a[0]++;
            a[1] += t.direction() == TradeDirection.BUY ? t.volume() : -t.volume();
            names.putIfAbsent(t.symbol(), t.name());
        }
        Map<String, Position> holdings = positionRepository.findAll(userId).stream()
                .collect(Collectors.toMap(Position::symbol, p -> p, (a, b) -> a));
        // 后端审查 P2-1：纯持仓 symbol（无流水）也要进对账——底仓快照导入、从不 recordTrade 的股票
        for (Position h : holdings.values()) {
            if (!acc.containsKey(h.symbol())) {
                acc.put(h.symbol(), new int[2]);
                names.put(h.symbol(), h.name());
            }
        }
        List<TradingAppService.ReconcileLine> lines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : acc.entrySet()) {
            String symbol = e.getKey();
            int[] a = e.getValue();
            Position h = holdings.get(symbol);
            String note;
            if (a[0] == 0) {
                note = "无流水记录——底仓快照导入后未记录任何成交（以持仓快照为准）";
            } else if (h == null) {
                note = "当前无持仓——已清仓或快照未含（流水净 " + signed(a[1]) + " 股）";
            } else if (h.quantity() == a[1]) {
                note = "流水重放与持仓一致（窗口内成交完整）";
            } else {
                note = "当前持仓 " + h.quantity() + " ≠ 流水净 " + signed(a[1])
                        + "——存在窗口前基线或未导入成交（持仓快照为准，差额已按初始批次兜底）";
            }
            lines.add(new TradingAppService.ReconcileLine(symbol, names.getOrDefault(symbol, symbol),
                    a[0], a[1], h != null ? h.quantity() : null, note));
        }
        return lines;
    }

    // ── 批次视图 ──

    /** 批次视图（注入现价）：state = open（持有中）/ closed（回合）/ all（默认）。 */
    public List<TradingLotView> lots(String userId, String state) {
        Map<String, List<TradingLot>> bySymbol = derive(userId);
        List<String> symbols = bySymbol.keySet().stream().toList();
        Map<String, MarketData> quotes = Map.of();
        if (!symbols.isEmpty()) {
            try {
                quotes = marketDataSource.quote(symbols);
            } catch (Exception e) {
                log.warn("批次行情注入失败，使用成本价 | userId={} | {}", userId, e.getMessage());
            }
        }
        List<TradingLotView> views = new ArrayList<>();
        for (Map.Entry<String, List<TradingLot>> e : bySymbol.entrySet()) {
            MarketData md = quotes.get(e.getKey());
            BigDecimal price = md != null ? md.price() : null;
            for (TradingLot lot : e.getValue()) {
                if ("open".equals(state) && lot.closed()) continue;
                if ("closed".equals(state) && !lot.closed()) continue;
                views.add(toView(lot, price));
            }
        }
        views.sort(Comparator.comparing(TradingLotView::buyDate,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TradingLotView::symbol));
        return views;
    }

    /** 批次有效止损：显式止损优先，否则成本价 × 0.93（−7% 默认，RFC 20260825）。 */
    public BigDecimal effectiveStopLoss(TradingLot lot) {
        if (lot.stopLossPrice() != null) return lot.stopLossPrice();
        return lot.costPrice().multiply(DEFAULT_STOP_LOSS_RATIO).setScale(4, RoundingMode.HALF_UP);
    }

    // ── 行为标注（记录即标注，进当日操作总结/复盘）──

    /**
     * 当日成交行为 + 当前持仓状态行为（六类，RFC 20260825 §4）：
     * 亏损加仓 / 追高 / 短线新开 / 破止损未走 / 浮盈回吐 / 短线超期。
     * 数据不足的维度自然跳过（如导入流水无买点 → 短线新开不判），不误报。
     */
    public List<BehaviorNote> analyzeBehaviors(String userId, LocalDate date) {
        List<BehaviorNote> notes = new ArrayList<>();
        List<TradeRecord> all = tradingHistoryRepository.findAll(userId);
        Map<String, List<TradeRecord>> bySymbol = new LinkedHashMap<>();
        for (TradeRecord t : all) {
            bySymbol.computeIfAbsent(t.symbol(), k -> new ArrayList<>()).add(t);
        }
        Map<String, Position> holdings = positionRepository.findAll(userId).stream()
                .collect(Collectors.toMap(Position::symbol, p -> p, (a, b) -> a));

        // 1) 当天成交行为：逐标的按时间序重放，维护上一买批成本 / 上一卖出价 上下文
        for (Map.Entry<String, List<TradeRecord>> e : bySymbol.entrySet()) {
            String symbol = e.getKey();
            List<TradeRecord> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator
                    .comparing((TradeRecord t) -> effectiveDate(t))
                    .thenComparing(t -> t.tradeTime() != null ? t.tradeTime() : LocalTime.MIN)
                    .thenComparing(t -> t.timestamp() != null ? t.timestamp() : LocalDateTime.MIN));
            // 初始上下文：持仓底仓（流水覆盖不到的初始批次）成本作为「上一买批成本」基线，
            // 让「底仓 10 元，9.2 补仓」这种亏损加仓可判（与 derive 初始批次兜底同口径）。
            // 注意：底仓 entryDate 可能被 recordTrade 加仓覆盖为当天——有初始批次时一律视为「更早建仓」。
            BigDecimal lastBuyLotCost = null;
            LocalDate lastBuyLotDate = null;
            int lastBuyLotVol = 0;
            BigDecimal lastBuyLotTotal = BigDecimal.ZERO;
            BigDecimal lastSellPrice = null;
            LocalDate lastSellDate = null; // 对抗审查 P1-2：追高判定加时间窗口（10 日内卖出才参与）
            int holding = 0; // 当前持仓数量（买入前 > 0 才判「亏损加仓」——清仓后重新建仓不算补仓）
            Position holdingPos = holdings.get(symbol);
            if (holdingPos != null && holdingPos.quantity() > 0) {
                int flowNet = sorted.stream().filter(t -> t.direction() == TradeDirection.BUY)
                        .mapToInt(TradeRecord::volume).sum()
                        - sorted.stream().filter(t -> t.direction() == TradeDirection.SELL)
                        .mapToInt(TradeRecord::volume).sum();
                // 后端审查 P2-3：持仓基线 = 快照 − 流水净量（流水覆盖不到的底仓数量），
                // 不是快照终态——否则「卖清后重新建仓」（flow 含卖清+重买）会把重买误判为亏损加仓
                int initialQty = holdingPos.quantity() - flowNet;
                boolean hasInitial = initialQty > 0;
                if (hasInitial) {
                    lastBuyLotCost = holdingPos.avgCost();
                    lastBuyLotDate = LocalDate.MIN; // 有更早的底仓 → 视为更早建仓
                    holding = initialQty;
                }
                // 无初始底仓（流水覆盖全部持仓）：不初始化上下文——首笔 BUY 是新建仓，不判亏损加仓
            }
            for (TradeRecord t : sorted) {
                LocalDate d = effectiveDate(t);
                boolean today = date.equals(d);
                if (t.direction() == TradeDirection.BUY) {
                    if (today) {
                        // 亏损加仓：持仓中跨日买入，买价低于上一买批成本（同日多单=一次决策分批，不判）
                        if (holding > 0 && lastBuyLotDate != null && !lastBuyLotDate.equals(d)
                                && lastBuyLotCost != null && t.price().compareTo(lastBuyLotCost) < 0) {
                            notes.add(behavior("loss-avg-down", "亏损加仓", symbol, t.name(), d,
                                    "买价 " + fmt(t.price()) + " 低于上一买批成本 " + fmt(lastBuyLotCost)
                                            + "——越跌越买/补仓摊薄，注意别把短线补成死扛"));
                        }
                        // 追高：最近 10 个自然日内有卖出、且买价高于该卖出价（低卖高买）。
                        // 对抗审查 P1-2：加时间窗口——3 个月前的割肉价不参与「卖飞买回」误判
                        if (lastSellPrice != null && lastSellDate != null
                                && !lastSellDate.isBefore(date.minusDays(10))
                                && t.price().compareTo(lastSellPrice) > 0) {
                            notes.add(behavior("chase-high", "追高", symbol, t.name(), d,
                                    "买价 " + fmt(t.price()) + " 高于最近卖出价 " + fmt(lastSellPrice)
                                            + "（" + lastSellDate + " 卖出）——低卖高买，追在了别人卖出的位置"));
                        }
                        // 短线新开：买点为短线战法（导入流水无买点 → 不判，不误报）
                        if (t.buyPoint() != null && SHORT_TERM_BUY_POINTS.contains(t.buyPoint())) {
                            notes.add(behavior("short-new", "短线新开", symbol, t.name(), d,
                                    "买点 " + t.buyPoint() + "——博一下的短线仓位，设好批次止损、到点就走"));
                        }
                    }
                    // 维护上一买批成本（与推导同一合并口径：同日合并加权）
                    if (lastBuyLotDate != null && lastBuyLotDate.equals(d)) {
                        lastBuyLotVol += t.volume();
                        lastBuyLotTotal = lastBuyLotTotal.add(buyTotalCost(t));
                        lastBuyLotCost = lastBuyLotTotal.divide(BigDecimal.valueOf(lastBuyLotVol), 4, RoundingMode.HALF_UP);
                    } else {
                        lastBuyLotVol = t.volume();
                        lastBuyLotTotal = buyTotalCost(t);
                        lastBuyLotCost = lastBuyLotTotal.divide(BigDecimal.valueOf(lastBuyLotVol), 4, RoundingMode.HALF_UP);
                        lastBuyLotDate = d;
                    }
                    holding += t.volume();
                } else {
                    lastSellPrice = t.price();
                    lastSellDate = d;
                    holding -= t.volume();
                    if (holding < 0) holding = 0; // 补录流水可超出持仓，不误判后续补仓
                }
            }
        }

        // 2) 当前状态行为：破止损未走 / 浮盈回吐 / 短线超期（对持有中批次）
        Map<String, List<TradingLot>> derived = derive(userId);
        List<String> symbols = derived.keySet().stream().toList();
        Map<String, MarketData> quotes = Map.of();
        if (!symbols.isEmpty()) {
            try {
                quotes = marketDataSource.quote(symbols);
            } catch (Exception e) {
                log.warn("行为标注行情注入失败 | userId={} | {}", userId, e.getMessage());
            }
        }
        LocalDate today = LocalDate.now();
        // 对抗审查 P1-4：状态类行为（破止损/浮盈回吐/短线超期）是「当前状态」判定——
        // 只在复盘/总结日期 = 今天时注入（历史日期复盘没有「今天」的现价语义，跳过防时间错位）
        boolean stateBehaviorsApplicable = date.equals(today);
        for (Map.Entry<String, List<TradingLot>> e : derived.entrySet()) {
            for (TradingLot lot : e.getValue()) {
                if (lot.closed()) continue;
                MarketData md = quotes.get(e.getKey());
                BigDecimal price = md != null ? md.price() : null;
                if (!stateBehaviorsApplicable) continue;
                // 破止损未走：现价 < 批次止损（含默认 −7% 兜底）且仍持有
                if (price != null) {
                    BigDecimal stop = effectiveStopLoss(lot);
                    if (price.compareTo(stop) < 0) {
                        // 对抗审查 P1-3：显式止损（用户自己设的）→ 纪律级文案；默认 −7% 兜底 → 风控提示文案，
                        // 不把系统强加的默认线包装成「违反纪律 R66」的批评
                        String lotLabel = lot.initial() ? "底仓" : lot.buyDate() + " 批次";
                        String msg = lot.stopLossPrice() != null
                                ? lotLabel + "现价 " + fmt(price) + " 已破你设的止损 " + fmt(stop)
                                        + "——该走没走（R66），想清楚是纪律还是侥幸"
                                : lotLabel + "现价 " + fmt(price) + " 已跌破默认 −7% 风控线 " + fmt(stop)
                                        + "（你还没设这批止损）——先想好这批复盘怎么走";
                        notes.add(behavior("stop-loss-ignored", "破止损未走", lot.symbol(), lot.name(), today, msg));
                    }
                    // 浮盈回吐：峰值浮盈 ≥ 20% 且回吐 ≥ 峰值 50%（K 线尽力而为，拉不到跳过）
                    BigDecimal peakHigh = peakHighSince(lot.symbol(), lot.buyDate());
                    if (peakHigh != null && peakHigh.compareTo(lot.costPrice()) > 0) {
                        BigDecimal peakPct = peakHigh.subtract(lot.costPrice())
                                .multiply(BigDecimal.valueOf(100)).divide(lot.costPrice(), 2, RoundingMode.HALF_UP);
                        BigDecimal curPct = price.subtract(lot.costPrice())
                                .multiply(BigDecimal.valueOf(100)).divide(lot.costPrice(), 2, RoundingMode.HALF_UP);
                        BigDecimal giveback = peakPct.subtract(curPct);
                        BigDecimal threshold = peakPct.multiply(GIVEBACK_RATIO_PCT)
                                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                        if (peakPct.compareTo(GIVEBACK_MIN_PEAK_PCT) >= 0
                                && giveback.compareTo(threshold) >= 0) {
                            notes.add(behavior("giveback", "浮盈回吐", lot.symbol(), lot.name(), today,
                                    (lot.initial() ? "底仓" : lot.buyDate() + " 批次") + "峰值浮盈 " + fmt(peakPct)
                                            + "% 现只剩 " + fmt(curPct) + "%——说好的浮盈没走，别让利润坐过山车"));
                        }
                    }
                }
                // 短线超期：短线角色/短线买点批次持有超 N 交易日（后端审查 P3：role 流水缺失，
                // 补 buyPoint 短线战法判定，手动记录带买点时可用）
                if (isShortRole(lot.role(), lot.buyPoint())
                        && lot.buyDate() != null
                        && tradingDaysBetween(lot.buyDate(), today) > SHORT_OVERDUE_TRADING_DAYS) {
                    notes.add(behavior("short-overdue", "短线超期", lot.symbol(), lot.name(), today,
                            lot.buyDate() + " 买入的短线批次已持有 "
                                    + tradingDaysBetween(lot.buyDate(), today)
                                    + " 个交易日——博一下博成中长线了，重新评估还值不值得拿"));
                }
            }
        }
        return notes;
    }

    // ── 内部方法 ──

    /** 买入批次总成本：导入流水用券商实际费用（fee），手动记录用模型模拟（与 recordTrade 同口径）。 */
    private BigDecimal buyTotalCost(TradeRecord t) {
        BigDecimal amount = t.price().multiply(BigDecimal.valueOf(t.volume()));
        if (t.fee() != null) return amount.add(t.fee());
        return CommissionCalculator.buyCost(t.symbol(), t.price(), t.volume());
    }

    private LocalDate effectiveDate(TradeRecord t) {
        if (t.entryDate() != null) return t.entryDate();
        return t.timestamp() != null ? t.timestamp().toLocalDate() : LocalDate.MIN;
    }

    private TradingLotView toView(TradingLot lot, BigDecimal currentPrice) {
        BigDecimal price = currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) > 0
                ? currentPrice : lot.costPrice();
        BigDecimal marketValue = price.multiply(BigDecimal.valueOf(lot.remaining()));
        BigDecimal pnl = price.subtract(lot.costPrice()).multiply(BigDecimal.valueOf(lot.remaining()));
        BigDecimal pnlPct = BigDecimal.ZERO;
        if (lot.costPrice().compareTo(BigDecimal.ZERO) > 0) {
            pnlPct = price.subtract(lot.costPrice()).divide(lot.costPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        BigDecimal stop = effectiveStopLoss(lot);
        BigDecimal stopDist = null;
        if (price.compareTo(BigDecimal.ZERO) > 0) {
            stopDist = price.subtract(stop).multiply(BigDecimal.valueOf(100))
                    .divide(price, 2, RoundingMode.HALF_UP);
        }
        return new TradingLotView(
                lot.lotId(), lot.symbol(), lot.name(), lot.buyDate(),
                lot.volume(), lot.remaining(), lot.costPrice(), price, marketValue,
                pnl, pnlPct, stop, stopDist, lot.buyPoint(), lot.role(),
                lot.initial(), lot.closed(), lot.realizedPnl());
    }

    /** 峰值最高价（K 线尽力而为：拉取失败/无数据 → null，调用方跳过）。 */
    private BigDecimal peakHighSince(String symbol, LocalDate since) {
        try {
            List<Candle> candles = klineService.kline(symbol, 90);
            return candles.stream()
                    .filter(c -> since == null || !c.date().isBefore(since))
                    .map(Candle::high)
                    .max(Double::compare)
                    .map(BigDecimal::valueOf)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("浮盈回吐判定 K 线不可用 | symbol={} | {}", symbol, e.getMessage());
            return null;
        }
    }

    private boolean isShortRole(String role, String buyPoint) {
        return (role != null && (role.contains("机动") || role.contains("短线")))
                || (buyPoint != null && SHORT_TERM_BUY_POINTS.contains(buyPoint));
    }

    /** 交易日近似（跳过周末；法定节假日不追，P2 接节假日表）。 */
    private long tradingDaysBetween(LocalDate from, LocalDate to) {
        long count = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY) count++;
        }
        return count;
    }

    private static BehaviorNote behavior(String type, String label, String symbol, String name,
                                         LocalDate date, String message) {
        return new BehaviorNote(type, label, symbol, name, date, message);
    }

    private static String fmt(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "-";
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    private static TradingLot withRemaining(TradingLot lot, int remaining, BigDecimal realizedPnl) {
        return new TradingLot(lot.lotId(), lot.symbol(), lot.name(), lot.buyDate(), lot.volume(),
                remaining, lot.costPrice(), lot.stopLossPrice(), lot.buyPoint(), lot.role(),
                lot.initial(), realizedPnl);
    }

    /** 可变批次中间体（TradingLot 不可变，扣减/合并时重建）。 */
    private static final class MutableLot {
        TradingLot lot;

        MutableLot(TradingLot lot) {
            this.lot = lot;
        }
    }

    // ── DTO ──

    /** 批次视图行（注入现价后的展示模型，含回合已实现盈亏）。 */
    public record TradingLotView(
            String lotId,
            String symbol,
            String name,
            LocalDate buyDate,
            int volume,
            int remaining,
            BigDecimal costPrice,
            BigDecimal currentPrice,
            BigDecimal marketValue,
            BigDecimal pnl,
            BigDecimal pnlPct,
            BigDecimal stopLossPrice,
            BigDecimal stopLossDistancePct,
            String buyPoint,
            String role,
            boolean initial,
            boolean closed,
            BigDecimal realizedPnl
    ) {}

    /** 行为标注（type 语义：loss-avg-down 亏损加仓 / chase-high 追高 / short-new 短线新开 /
     *  stop-loss-ignored 破止损未走 / giveback 浮盈回吐 / short-overdue 短线超期）。 */
    public record BehaviorNote(
            String type,
            String label,
            String symbol,
            String name,
            LocalDate date,
            String message
    ) {}
}
