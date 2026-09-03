package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.CommissionCalculator;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
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

    /** 曲线结果：points 按日期升序；stats 附开始/结束/初始投入/最新净投入。 */
    public record EquityCurve(List<EquityPoint> points, int skippedDays,
                              String startDate, String endDate) {}

    private final TradingHistoryRepository historyRepository;
    private final PositionRepository positionRepository;
    private final TransferRepository transferRepository;
    private final AccountSnapshotRepository accountRepository;
    private final KlineService klineService;

    public EquityCurveService(TradingHistoryRepository historyRepository,
                              PositionRepository positionRepository,
                              TransferRepository transferRepository,
                              AccountSnapshotRepository accountRepository,
                              KlineService klineService) {
        this.historyRepository = historyRepository;
        this.positionRepository = positionRepository;
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
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
        record TradeEvent(LocalDate date, String symbol, double cashDelta, int qtyDelta) {}
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
                    return new TradeEvent(t.entryDate(), t.symbol(), cashDelta, qtyDelta);
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

        int ti = 0, ri = 0;
        double peak = Double.MIN_VALUE;
        List<EquityPoint> points = new ArrayList<>();
        int skipped = 0;
        for (LocalDate d : tradingDates) {
            // 当日事件（交易 → 转账，先后无交叉）
            while (ti < tradeEvents.size() && !tradeEvents.get(ti).date().isAfter(d)) {
                TradeEvent e = tradeEvents.get(ti++);
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
                    else c = qtyCost.getOrDefault(en.getKey(), 0.0);
                } else {
                    anyPrice = true;
                    lastClose.put(en.getKey(), c);
                }
                marketValue += en.getValue() * c;
            }
            if (!anyPrice && qty.isEmpty()) marketValue = 0;

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
        log.info("资金曲线生成 | userId={} | 交易日 {} 点（跳过 {}）| 起点 {} | 当前总资产 {}",
                userId, points.size(), skipped,
                points.isEmpty() ? "-" : points.get(0).date(), round2(cash + marketValue(points)));
        return new EquityCurve(points, skipped,
                points.isEmpty() ? "" : points.get(0).date().toString(),
                points.isEmpty() ? "" : points.get(points.size() - 1).date().toString());
    }

    private double marketValue(List<EquityPoint> pts) {
        return pts.isEmpty() ? 0 : pts.get(pts.size() - 1).marketValue().doubleValue();
    }

    private static BigDecimal round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
