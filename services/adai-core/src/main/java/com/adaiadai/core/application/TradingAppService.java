package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.*;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.IdGenerator;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TradingAppService — 交易领域应用服务。
 * <p>
 * 编排交易记录的完整流程：结构化交易输入 → Record → 更新持仓。
 * 独立的交易业务编排，不同于 RecordFlowAppService 的通用 MVP 流程。
 */
@Service
public class TradingAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingAppService.class);

    /** 交易记录去重窗口：同一标题的记录在窗口内视为重试，不重复写入（防重试重复进时间线/复盘提醒）。 */
    private static final Duration RECORD_DEDUP_WINDOW = Duration.ofMinutes(5);

    /** 每用户读写锁：同一 userId 的持仓读-改-写全串行，防并发交易互相覆盖（REVIEW #147）。 */
    private final ConcurrentHashMap<String, Object> userTradeLocks = new ConcurrentHashMap<>();

    private final PositionRepository positionRepository;
    private final RecordRepository recordRepository;
    private final TradingHistoryRepository tradingHistoryRepository;
    private final WatchlistRepository watchlistRepository;
    private final SoldTradeRepository soldTradeRepository;
    private final AccountSnapshotRepository accountSnapshotRepository;
    private final TransferRepository transferRepository;
    private final MarketDataSource marketDataSource;
    /** RFC 20260825：批次推导与行为标注（当日成交同步模式 / 每日操作总结依赖）。 */
    private final TradingLotService tradingLotService;
    /** 第三阶段：用户规则参数配置（清仓 verdict 阈值按用户隔离）。 */
    private final TradingRuleSettingsRepository tradingRuleSettingsRepository;

    public TradingAppService(PositionRepository positionRepository,
                             RecordRepository recordRepository,
                             TradingHistoryRepository tradingHistoryRepository,
                             WatchlistRepository watchlistRepository,
                             SoldTradeRepository soldTradeRepository,
                             AccountSnapshotRepository accountSnapshotRepository,
                             TransferRepository transferRepository,
                             MarketDataSource marketDataSource,
                             TradingLotService tradingLotService,
                             TradingRuleSettingsRepository tradingRuleSettingsRepository) {
        this.positionRepository = positionRepository;
        this.recordRepository = recordRepository;
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.watchlistRepository = watchlistRepository;
        this.soldTradeRepository = soldTradeRepository;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.transferRepository = transferRepository;
        this.marketDataSource = marketDataSource;
        this.tradingLotService = tradingLotService;
        this.tradingRuleSettingsRepository = tradingRuleSettingsRepository;
    }

    private Object tradeLock(String userId) {
        return userTradeLocks.computeIfAbsent(userId != null ? userId : "default", k -> new Object());
    }

    /**
     * 记录一笔交易并更新持仓（RFC 20260816：逐笔流水 + 持仓新字段）。
     *
     * @param symbol        股票代码
     * @param name          股票名称
     * @param direction     交易方向
     * @param price         成交单价
     * @param volume        成交数量
     * @param entryDate     交易日期（可空，缺省今天；首买日持久化，加仓不覆盖）
     * @param stopLossPrice 止损位（BUY 必填；SELL 可空）
     * @param buyPoint      买点类型（BUY 必填；SELL 可空）
     * @param targetPrice   目标价（可空）
     * @param reason        交易原因/预期（可空）
     * @return 更新后的持仓列表
     */
    public List<Position> recordTrade(String userId, String symbol, String name,
                                      TradeDirection direction,
                                      BigDecimal price, int volume,
                                      LocalDate entryDate, LocalTime tradeTime,
                                      BigDecimal stopLossPrice, String buyPoint,
                                      BigDecimal targetPrice, String reason) {
        return recordTradeInternal(userId, symbol, name, direction, price, volume,
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, null, null);
    }

    /**
     * 带券商成交编号与实扣费用的交易记录（RFC 20260825 §5 当日成交同步专用）：
     * orderId 透传流水落盘（导入幂等键）、fee 透传券商实扣（后端审查 P1-2——不丢实际手续费，
     * 与 append 补录模式同口径）。其余语义与 {@link #recordTrade} 完全一致。
     */
    public List<Position> recordTradeWithOrderId(String userId, String symbol, String name,
                                                 TradeDirection direction,
                                                 BigDecimal price, int volume,
                                                 LocalDate entryDate, LocalTime tradeTime,
                                                 BigDecimal stopLossPrice, String buyPoint,
                                                 BigDecimal targetPrice, String reason,
                                                 String orderId, BigDecimal fee) {
        return recordTradeInternal(userId, symbol, name, direction, price, volume,
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, orderId, fee);
    }

    private List<Position> recordTradeInternal(String userId, String symbol, String name,
                                               TradeDirection direction,
                                               BigDecimal price, int volume,
                                               LocalDate entryDate, LocalTime tradeTime,
                                               BigDecimal stopLossPrice, String buyPoint,
                                               BigDecimal targetPrice, String reason,
                                               String orderId, BigDecimal fee) {
        // #147：读-改-写加每用户锁，防并发交易互相覆盖丢持仓
        synchronized (tradeLock(userId)) {
            // RFC 20260815：name 可空（web 标注"可选"），缺名时以 symbol 兜底（简单方案：symbol 即名）
            String effectiveName = (name == null || name.isBlank()) ? symbol : name;
            // RFC 20260816：入场日期缺省今天（用户可补录）
            LocalDate effectiveEntryDate = entryDate != null ? entryDate : LocalDate.now();
            // RFC 20260822：成交时刻缺省 = 落盘时刻时分（客观真实，前端可不传）
            LocalTime effectiveTradeTime = tradeTime != null
                    ? tradeTime
                    : LocalDateTime.now().toLocalTime();

            List<Position> currentPositions = new ArrayList<>(positionRepository.findAll(userId));
            boolean found = false;

            for (int i = 0; i < currentPositions.size(); i++) {
                Position p = currentPositions.get(i);
                if (p.symbol().equals(symbol)) {
                    // #147：卖出数量超过持仓 → 明确报错，防静默清仓失真
                    if (direction == TradeDirection.SELL && volume > p.quantity()) {
                        throw new TradingException(
                                "卖出数量超过持仓: " + symbol + "（持有 " + p.quantity() + " 股）");
                    }
                    Position updated = updatePosition(p.symbol(), p, direction, price, volume,
                            effectiveEntryDate, stopLossPrice, buyPoint);
                    currentPositions.set(i, updated);
                    found = true;
                    break;
                }
            }

            // #147：SELL 未持有 symbol 不再是静默 no-op，明确报错防数据静默丢失
            if (!found && direction == TradeDirection.SELL) {
                throw new TradingException("未持有 " + symbol + "，无法卖出");
            }

            if (!found && direction == TradeDirection.BUY) {
                // 首次买入：新建持仓（2026-08-16 手续费：avgCost = 摊薄成本价含佣金/过户费）
                BigDecimal unit = CommissionCalculator.unitCost(symbol, price, volume);
                Position newPos = new Position(symbol, effectiveName, volume, unit, price, LocalDateTime.now(),
                        effectiveEntryDate, stopLossPrice, buyPoint, null);
                currentPositions.add(newPos);
            }

            // 清仓后的 0 持仓行不落盘（findAll 读取时本就过滤，保持文件干净）
            currentPositions.removeIf(p -> p.quantity() <= 0);

            positionRepository.saveAll(userId, currentPositions);

            // P1-交易2（2026-08-17）：买卖本质是现金↔市值转移，总资产只变手续费。
            // 旧实现只动现金不动市值 → BUY 少计成交额、SELL 多计成交额（账户卡 15:05 前账目错误）。
            // 修：现金 ± 成交额（含费），市值 ∓ 价×量，总资产 = 现金 + 市值（不变式）。
            BigDecimal tradeCashDelta = direction == TradeDirection.BUY
                    ? CommissionCalculator.buyCost(symbol, price, volume).negate()
                    : CommissionCalculator.sellProceeds(symbol, price, volume);
            BigDecimal tradeValueDelta = direction == TradeDirection.BUY
                    ? price.multiply(BigDecimal.valueOf(volume))
                    : price.multiply(BigDecimal.valueOf(volume)).negate();
            try {
                accountSnapshotRepository.update(userId, current -> current.map(c -> {
                    BigDecimal newCash = c.cash().add(tradeCashDelta);
                    BigDecimal newMarketValue = c.marketValue().add(tradeValueDelta);
                    return new AccountSnapshot(
                            newCash.add(newMarketValue), // 总资产 = 现金 + 市值（只差手续费）
                            newCash,
                            c.available().add(tradeCashDelta),
                            c.withdrawable().add(tradeCashDelta),
                            newMarketValue, c.pnl(), c.todayPnl(),
                            c.principal(), c.snapshotDate());
                }).orElse(null)); // P0-2：无快照（首次交易未导入资金）不初始化，保持既有语义
            } catch (RuntimeException e) {
                // B6-4（2026-08-23，P1-交易11）：账目快照写失败——持仓/流水已落库（跨文件无原子回滚），
                // 明确告警不静默（用户看到的账目可能滞后于持仓），交易本身不中断
                log.error("交易已落库但账户快照更新失败——账目未落盘 | userId={} | {} {} {}股@{} | {}",
                        userId, direction, symbol, volume, price, e.getMessage());
            }

            // RFC 20260815 §6：交易成功后同步写一条 domain=trading 记录（复盘提醒 + 时间线闭环）。
            // 位置在 saveAll 成功之后：recordTrade 失败（校验/存储异常）路径不会留下记录；
            // 窗口内同标题（重试）不重复写（幂等）。返回时间线 Record ID 作为流水 sourceRecordId。
            String recordId = writeTradingRecord(userId, direction, effectiveName, symbol, price, volume);

            // RFC 20260816 §2.1：逐笔流水真相源（BUY/SELL 都写）。best-effort：
            // 持仓已落库，流水写入失败不阻塞交易本身（与 writeTradingRecord 同口径），只告警。
            appendTradeRecord(userId, symbol, effectiveName, direction, price, volume,
                    effectiveEntryDate, effectiveTradeTime, stopLossPrice, buyPoint, targetPrice, reason,
                    recordId, orderId, fee);

            log.info("交易已记录 | {} {} {}股@{}元 | 持仓数={} | entryDate={} | 止损={}",
                    direction, symbol, volume, price, currentPositions.size(),
                    effectiveEntryDate, stopLossPrice);

            return currentPositions;
        }
    }

    /**
     * 交易成功后写 domain=trading 记录（标题如「买入 京东方A 1000股@5.20」）。
     * <p>
     * 目的：交易进 timeline/记忆 + {@code hasTradingActivity} 关键词（买/卖/股/交易…）命中，闭环复盘提醒。
     * 附加动作 best-effort：记录写入失败不阻塞交易本身（持仓已落库），只告警。
     * 幂等：5 分钟窗口内存在同标题记录（重试）→ 跳过，防重复进时间线。
     *
     * @return 时间线 Record ID（写入成功）；重复/失败返回 null
     */
    private String writeTradingRecord(String userId, TradeDirection direction,
                                      String name, String symbol,
                                      BigDecimal price, int volume) {
        try {
            String directionLabel = direction == TradeDirection.BUY ? "买入" : "卖出";
            String title = "%s %s %d股@%s".formatted(directionLabel, name, volume, price.toPlainString());

            LocalDateTime cutoff = LocalDateTime.now().minus(RECORD_DEDUP_WINDOW);
            List<ContentRecord> existing = recordRepository.findAll(userId);
            boolean duplicated = existing != null && existing.stream()
                    .anyMatch(r -> title.equals(r.title())
                            && r.createdAt() != null && r.createdAt().isAfter(cutoff));
            if (duplicated) {
                log.debug("交易记录已存在（窗口内重试），跳过写记录 | title={}", title);
                return null;
            }

            String content = "%s %s（%s）%d股@%s，成交金额 %s 元".formatted(
                    directionLabel, name, symbol, volume, price.toPlainString(),
                    price.multiply(BigDecimal.valueOf(volume)).setScale(2).toPlainString());
            ContentRecord record = new ContentRecord(
                    RecordFileRepository.generateId(), "trade", "auto_collect",
                    title, content, List.of("trading", "交易"), LocalDateTime.now(),
                    null, null, "trading");
            recordRepository.save(userId, record);
            log.info("交易记录已写入时间线 | id={} | title={}", record.id(), title);
            return record.id();
        } catch (Exception e) {
            log.warn("交易记录写入失败（不影响交易落库）| symbol={} | {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 逐笔流水落盘（RFC 20260816 §2.1）：BUY/SELL 都写 {@code data/{userId}/trading/trades/{yyyy-MM}.json}。
     * <p>
     * best-effort：流水写入失败不阻塞交易本身（持仓已落库），只告警——与 writeTradingRecord 同口径。
     */
    private void appendTradeRecord(String userId, String symbol, String name, TradeDirection direction,
                                   BigDecimal price, int volume, LocalDate entryDate, LocalTime tradeTime,
                                   BigDecimal stopLossPrice, String buyPoint,
                                   BigDecimal targetPrice, String reason, String sourceRecordId,
                                   String orderId, BigDecimal fee) {
        try {
            TradeRecord trade = TradeRecord.of(
                    IdGenerator.monotonic("trade_"),
                    symbol, name, direction, price, volume,
                    entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason,
                    fee, LocalDateTime.now(), sourceRecordId, orderId);
            tradingHistoryRepository.append(userId, trade);
        } catch (Exception e) {
            log.warn("交易流水写入失败（不影响交易落库）| symbol={} | {}", symbol, e.getMessage());
        }
    }

    /**
     * 获取当前投资组合快照。
     */
    public PortfolioSnapshot getPortfolioSnapshot(String userId) {
        // 2026-08-16 修复：组合快照用行情注入后的持仓（getPositions），否则 currentPrice=存储价
        // （=成本），盈亏/市值全错（此前 totalPnl 恒 ≈0，用户"资金导入不起作用"实为此因）
        List<Position> injected = getPositions(userId);
        // S5（2026-08-17）：现金唯一真源 = account.json 的 AccountSnapshot.cash（不再读 positions.md cashBalance）
        java.math.BigDecimal cash = accountSnapshotRepository.findLatest(userId)
                .map(AccountSnapshot::cash)
                .orElse(java.math.BigDecimal.ZERO);
        return PortfolioSnapshot.of(injected, cash);
    }

    /**
     * 获取所有持仓（注入实时行情：currentPrice=现价 → 盈亏/盈亏% 展示正确；
     * 同时注入系统计算止损位 computedStopLossPrice——风险预算公式，不落盘）。
     * <p>
     * 行情拉取失败/单票无行情 → 用存储价（=成本价，盈亏 0），降级不报错；
     * 行情整体失败仍返回计算止损（computed 与行情无关）。
     */
    public List<Position> getPositions(String userId) {
        List<Position> stored = positionRepository.findAll(userId);
        if (stored.isEmpty()) return stored;
        BigDecimal principal = accountSnapshotRepository.findLatest(userId)
                .map(AccountSnapshot::principal).orElse(null);
        Map<String, MarketData> quotes = Map.of();
        try {
            quotes = marketDataSource.quote(stored.stream().map(Position::symbol).toList());
        } catch (Exception e) {
            log.warn("持仓行情注入失败，使用存储价 | userId={} | {}", userId, e.getMessage());
        }
        if (quotes.isEmpty()) {
            return stored.stream().map(p -> withComputedStopLoss(p, principal)).toList();
        }
        List<Position> result = new ArrayList<>();
        for (Position p : stored) {
            MarketData md = quotes.get(p.symbol());
            if (md != null && md.price() != null && md.price().compareTo(BigDecimal.ZERO) > 0) {
                Position withQuote = new Position(p.symbol(), p.name(), p.quantity(), p.avgCost(), md.price(),
                        p.lastUpdated(), p.entryDate(), p.stopLossPrice(), p.buyPoint(), p.role());
                result.add(withComputedStopLoss(withQuote, principal));
            } else {
                result.add(withComputedStopLoss(p, principal));
            }
        }
        return result;
    }

    /** 风险预算单笔风险比例（本金 × 1%，docs/reference/trading-risk-plan.md 校准参数表）。 */
    private static final BigDecimal RISK_BUDGET_PCT = new BigDecimal("0.01");
    /** 止损距离上限（min(R÷单仓市值, 5%)——5% 与 R66 清仓阈值闭合）。 */
    private static final BigDecimal MAX_STOP_DISTANCE_PCT = new BigDecimal("0.05");

    /**
     * 系统计算止损位（风险预算公式，动态算、不落盘）：
     * R = 本金 × 1%；止损距离 = min(R ÷ 单仓市值, 5%)；止损价 = 成本 × (1 − 距离)，两位小数。
     * 本金缺失/数量成本异常 → null（无据可算，判定走人工止损或跳过）。
     */
    private Position withComputedStopLoss(Position p, BigDecimal principal) {
        BigDecimal computed = null;
        if (principal != null && principal.signum() > 0
                && p.quantity() > 0 && p.avgCost() != null && p.avgCost().signum() > 0) {
            BigDecimal marketValue = p.avgCost().multiply(BigDecimal.valueOf(p.quantity()));
            BigDecimal distance = principal.multiply(RISK_BUDGET_PCT)
                    .divide(marketValue, 4, java.math.RoundingMode.HALF_UP);
            if (distance.compareTo(MAX_STOP_DISTANCE_PCT) > 0) {
                distance = MAX_STOP_DISTANCE_PCT;
            }
            computed = p.avgCost().multiply(BigDecimal.ONE.subtract(distance))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return new Position(p.symbol(), p.name(), p.quantity(), p.avgCost(), p.currentPrice(),
                p.lastUpdated(), p.entryDate(), p.stopLossPrice(), p.buyPoint(), p.role(), computed);
    }

    /**
     * 一键按流水重建持仓（2026-08-25 用户场景：导入历史成交后持仓快照过期，
     * 中电电机已清仓但快照残留 1000 股被当初始底仓）。
     * <p>
     * 语义：以流水为准（结合 INIT 兜底）重放每个 symbol 的开放批次 → 覆盖 positions：
     * <ul>
     *   <li>有开放批次 → 持仓 = 批次 Σ（数量/加权成本），保留快照元信息（entryDate/止损/买点/角色）</li>
     *   <li>无开放批次（流水已全部卖出）→ 快照里该 symbol 移除（removed 报告）</li>
     *   <li>保留 INIT 底仓的 symbol → keptInitial 报告（快照早于流水的真底仓）</li>
     * </ul>
     * 与「每日导当天成交 sync 模式」互补：sync 处理增量，本端点一次性对齐存量账本。
     */
    public SyncResult syncPositionsFromFlow(String userId) {
        Map<String, List<TradingLot>> bySymbol = tradingLotService.derive(userId);
        Map<String, Position> oldHoldings = positionRepository.findAll(userId).stream()
                .collect(java.util.stream.Collectors.toMap(Position::symbol, p -> p, (a, b) -> a));
        List<Position> newPositions = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> keptInitial = new ArrayList<>();
        synchronized (tradeLock(userId)) {
            for (Map.Entry<String, List<TradingLot>> e : bySymbol.entrySet()) {
                String symbol = e.getKey();
                List<TradingLot> open = e.getValue().stream().filter(l -> !l.closed()).toList();
                if (open.isEmpty()) {
                    if (oldHoldings.containsKey(symbol)) removed.add(symbol); // 流水已清仓 → 快照残留移除
                    continue;
                }
                int qty = open.stream().mapToInt(TradingLot::remaining).sum();
                BigDecimal totalCost = BigDecimal.ZERO;
                for (TradingLot l : open) {
                    totalCost = totalCost.add(l.costPrice().multiply(BigDecimal.valueOf(l.remaining())));
                }
                BigDecimal cost = totalCost.divide(BigDecimal.valueOf(qty), 4, java.math.RoundingMode.HALF_UP);
                Position old = oldHoldings.get(symbol);
                String name = (open.get(0).name() != null && !open.get(0).name().isBlank())
                        ? open.get(0).name() : (old != null ? old.name() : symbol);
                BigDecimal stop = old != null ? old.stopLossPrice() : null;
                String bp = old != null ? old.buyPoint() : null;
                String role = old != null ? old.role() : null;
                LocalDate entryDate = old != null ? old.entryDate() : null;
                if (open.stream().anyMatch(TradingLot::initial)) keptInitial.add(symbol);
                newPositions.add(new Position(symbol, name, qty, cost, cost, LocalDateTime.now(),
                        entryDate, stop, bp, role));
            }
            positionRepository.saveAll(userId, newPositions);
        }
        log.info("一键同步持仓 | userId={} | 持仓 {} 只 | 移除已清仓残留 {} | 保留底仓 {}",
                userId, newPositions.size(), removed, keptInitial);
        return new SyncResult(newPositions.size(), removed, keptInitial);
    }

    /**
     * 获取交易逐笔流水（RFC 20260816：web 交易历史）。
     * 可按日期范围过滤（from/to 均为 null 时返回全部）。
     */
    public List<TradeRecord> getTradeHistory(String userId, java.time.LocalDate from, java.time.LocalDate to) {
        List<TradeRecord> all = tradingHistoryRepository.findAll(userId);
        if ((from == null) && (to == null)) return all;
        return all.stream()
                .filter(tr -> {
                    java.time.LocalDate d = tr.entryDate() != null ? tr.entryDate()
                            : (tr.timestamp() != null ? tr.timestamp().toLocalDate() : null);
                    if (d == null) return false;
                    if (from != null && d.isBefore(from)) return false;
                    if (to != null && d.isAfter(to)) return false;
                    return true;
                })
                .toList();
    }

    /**
     * 当日交易复盘聚合（RFC 20260822，纯客观数据）：指定日期成交的时段分桶/买卖分布/节奏。
     * <p>
     * 时段口径（2026-08-22 用户确认）：早盘 09:30-11:30 / 午盘 13:00-14:30 / 尾盘 14:30-15:00。
     * tradeTime 为 null 的历史流水：计入 count/金额，不计入 sessions（无时间不误判时段）。
     */
    public DailyTradeSummary getDailyTradeSummary(String userId, java.time.LocalDate date) {
        List<TradeRecord> dayTrades = tradingHistoryRepository.findAll(userId).stream()
                .filter(tr -> {
                    java.time.LocalDate d = tr.entryDate() != null ? tr.entryDate()
                            : (tr.timestamp() != null ? tr.timestamp().toLocalDate() : null);
                    return date.equals(d);
                })
                .toList();
        int buyCount = 0, sellCount = 0;
        double buyAmount = 0, sellAmount = 0;
        java.time.LocalTime first = null, last = null;
        List<DailySession> sessions = new ArrayList<>();
        // 三个时段桶（早盘/午盘/尾盘）
        List<int[]> buckets = List.of(
                new int[]{9, 30, 11, 30},
                new int[]{13, 0, 14, 30},
                new int[]{14, 30, 15, 0});
        String[] names = {"早盘", "午盘", "尾盘"};
        int[] counts = new int[3];
        for (TradeRecord tr : dayTrades) {
            boolean buy = tr.direction() == TradeDirection.BUY;
            double amt = tr.amount() != null ? tr.amount().doubleValue() : 0;
            if (buy) { buyCount++; buyAmount += amt; } else { sellCount++; sellAmount += amt; }
            java.time.LocalTime t = tr.tradeTime();
            if (t != null) {
                if (first == null || t.isBefore(first)) first = t;
                if (last == null || t.isAfter(last)) last = t;
                for (int i = 0; i < buckets.size(); i++) {
                    int[] b = buckets.get(i);
                    java.time.LocalTime start = java.time.LocalTime.of(b[0], b[1]);
                    java.time.LocalTime end = java.time.LocalTime.of(b[2], b[3]);
                    if (!t.isBefore(start) && t.isBefore(end)) counts[i]++;
                }
            }
        }
        for (int i = 0; i < names.length; i++) {
            sessions.add(new DailySession(names[i],
                    "%02d:%02d-%02d:%02d".formatted(buckets.get(i)[0], buckets.get(i)[1],
                            buckets.get(i)[2], buckets.get(i)[3]),
                    counts[i]));
        }
        return new DailyTradeSummary(date.toString(), dayTrades.size(), buyCount, sellCount,
                round2(buyAmount), round2(sellAmount), sessions, first, last);
    }

    private static double round2(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** 当日复盘聚合结果（RFC 20260822，纯客观数字）。 */
    public record DailyTradeSummary(
            String date,
            int count,
            int buyCount,
            int sellCount,
            double buyAmount,
            double sellAmount,
            List<DailySession> sessions,
            java.time.LocalTime firstTradeTime,
            java.time.LocalTime lastTradeTime
    ) {}

    /** 时段桶：名称 / 时间范围文案 / 笔数。 */
    public record DailySession(String name, String range, int count) {}

    /**
     * 按股票代码查询名称（GET /trading/lookup，代码输入带出名称 + 二次确认）。
     * <p>
     * 走行情数据源（腾讯）单码查询；失败/无结果返回 null（前端让用户手填或留空）。
     */
    public String lookupName(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        try {
            Map<String, MarketData> quotes = marketDataSource.quote(List.of(symbol));
            MarketData md = quotes.get(symbol);
            if (md != null && md.name() != null && !md.name().isBlank()) {
                return md.name();
            }
        } catch (Exception e) {
            log.warn("代码查名失败 | symbol={} | {}", symbol, e.getMessage());
        }
        return null;
    }

    /**
     * 持仓初始化导入（通达信导出 → 持仓快照，RFC 20260816 用户需求）。
     * <p>
     * 按 symbol upsert（已存在更新数量/成本，不存在新增）；name 缺失时用行情补全；
     * 返回导入统计（导入数 + 未设止损列表——R68 提示补设，建议引擎/推送才按纪律工作）。
     * <p>
     * 全量覆盖（2026-08-18 确认批次）：{@code replace=true} 时「以文件为准」——
     * 导入后移除文件里不存在的持仓（含 0 股残留），解决 upsert 无删除语义导致的漂移；
     * 通达信持仓导出是当日券商口径快照，与资金股份查询配套使用。
     *
     * @param items   导入项（代码/名称/数量/成本 + 可选止损/买点/角色/入场日期）
     * @param replace true = 全量覆盖（文件为准，缺失删除）；false = upsert（默认）
     */
    public PositionImportResult importPositions(String userId, List<PositionImportItem> items, boolean replace) {
        if (items == null || items.isEmpty()) {
            return new PositionImportResult(0, List.of());
        }
        synchronized (tradeLock(userId)) {
            List<Position> current = new ArrayList<>(positionRepository.findAll(userId));
            List<String> missingStopLoss = new ArrayList<>();
            int imported = 0;
            Set<String> importedSymbols = new java.util.HashSet<>();

            for (PositionImportItem item : items) {
                String symbol = item.symbol();
                if (symbol == null || symbol.isBlank()) continue;
                // P2-交易22（2026-08-17）：avgCost/quantity 校验——缺失/非法会让下游 NPE 500
                if (item.avgCost() == null || item.avgCost().signum() <= 0) {
                    throw new TradingException("持仓导入：股票 " + symbol + " 的成本价缺失或非法（需 > 0）");
                }
                if (item.quantity() <= 0) {
                    throw new TradingException("持仓导入：股票 " + symbol + " 的数量需 > 0");
                }
                // name 缺失 → 行情补全（P3：lookupName 只调一次，避免双网络请求）
                String name = item.name();
                if (name == null || name.isBlank()) {
                    String looked = lookupName(symbol);
                    name = looked != null ? looked : symbol;
                }

                boolean found = false;
                boolean effectiveStopLoss = item.stopLossPrice() != null; // 导入项带止损
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).symbol().equals(symbol)) {
                        Position p = current.get(i);
                        effectiveStopLoss = item.stopLossPrice() != null || p.stopLossPrice() != null;
                        current.set(i, new Position(symbol, name, item.quantity(), item.avgCost(), item.avgCost(),
                                LocalDateTime.now(),
                                item.entryDate() != null ? item.entryDate() : p.entryDate(),
                                item.stopLossPrice() != null ? item.stopLossPrice() : p.stopLossPrice(),
                                item.buyPoint() != null ? item.buyPoint() : p.buyPoint(),
                                item.role() != null ? item.role() : p.role()));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    current.add(new Position(symbol, name, item.quantity(), item.avgCost(), item.avgCost(),
                            LocalDateTime.now(),
                            item.entryDate() != null ? item.entryDate() : LocalDate.now(),
                            item.stopLossPrice(), item.buyPoint(), item.role()));
                }
                // P3（2026-08-17）：已存在持仓且保留旧止损 → 不进 missingStopLoss（提示失真）
                if (!effectiveStopLoss) {
                    missingStopLoss.add(symbol + " " + name);
                }
                importedSymbols.add(symbol);
                imported++;
            }

            // 全量覆盖：以文件为准——文件里没有的持仓 = 已清仓/不在券商口径，移除（含 0 股残留）
            if (replace) {
                current.removeIf(p -> !importedSymbols.contains(p.symbol()));
            }
            current.removeIf(p -> p.quantity() <= 0);
            positionRepository.saveAll(userId, current);
            log.info("持仓初始化导入 | userId={} | 导入 {} 只 | 未设止损 {} 只 | replace={} | 落盘 {} 只",
                    userId, imported, missingStopLoss.size(), replace, current.size());
            return new PositionImportResult(imported, missingStopLoss);
        }
    }

    /** 持仓导入项（通达信/批量，symbol 必填；name 缺失行情补全；止损/买点可选——缺失提示补设）。 */
    public record PositionImportItem(
            String symbol,
            String name,
            int quantity,
            BigDecimal avgCost,
            BigDecimal stopLossPrice,
            String buyPoint,
            String role,
            LocalDate entryDate
    ) {}

    /** 导入结果：导入数量 + 未设止损列表（R68 提示）。 */
    public record PositionImportResult(int imported, List<String> missingStopLoss) {}

    /**
     * 更新持仓元信息（web 持仓编辑，2026-08-17 补端点：role/止损位，只更新非空字段）。
     * <p>
     * 之前前端与测试都在调 PUT /positions/{symbol} 但后端从未实现（持仓编辑一直 404）。
     * targetPrice 后端 Position 无字段落盘（前端编辑目标价是既有无效功能，另记 P3）。
     *
     * @return 更新后的持仓；symbol 不存在返回 null
     */
    public Position updatePositionMeta(String userId, String symbol,
                                       String role, BigDecimal stopLossPrice) {
        // P2-6（2026-08-17 走查）：与同文件其余 RMW 一致进 tradeLock——此前裸跑与并发交易互覆持仓
        synchronized (tradeLock(userId)) {
        List<Position> current = new ArrayList<>(positionRepository.findAll(userId));
        for (int i = 0; i < current.size(); i++) {
            Position p = current.get(i);
            if (!p.symbol().equals(symbol)) continue;
            Position updated = new Position(p.symbol(), p.name(), p.quantity(), p.avgCost(),
                    p.currentPrice(), p.lastUpdated(), p.entryDate(),
                    stopLossPrice != null ? stopLossPrice : p.stopLossPrice(),
                    p.buyPoint(),
                    role != null ? role : p.role());
            current.set(i, updated);
            positionRepository.saveAll(userId, current);
            log.info("持仓元信息更新 | userId={} | {} | 止损 {} | 角色 {}",
                    userId, symbol, updated.stopLossPrice(), updated.role());
            return updated;
        }
        return null;
        }
    }

    /**
     * 保存导入文件（上传留存 + 编码转码，2026-08-16）。
     * <p>
     * 留存：原始文件存 {@code data/{userId}/trading/imports/{yyyy-MM}/{ts}_{filename}}（UTF-8 转码后可追溯）。
     * 编码：通达信导出为 GBK——UTF-8 严格解码失败则按 GBK 转码，前端/解析器拿到 UTF-8 文本。
     *
     * @return {path, content}——content 为转码后的 UTF-8 文本（前端填充解析）
     */
    public ImportFileResult saveImportFile(String userId, String filename, byte[] bytes) {
        String safeName = filename != null ? filename.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_") : "import.txt";
        String content = decodeText(bytes);
        String monthDir = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String path = "trading/imports/" + monthDir + "/" + ts + "_" + safeName;
        positionRepository.saveImportFile(userId, path, content);
        log.info("导入文件已留存 | userId={} | path={} | {} 字节", userId, path, bytes.length);
        return new ImportFileResult(path, content);
    }

    /** 编码识别 + 转码：UTF-8 严格解码优先，失败按 GBK（通达信导出默认编码）。 */
    private String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("GBK"));
        }
    }


    /** 导入文件留存结果。 */
    public record ImportFileResult(String path, String content) {}

    // ── 自选股（RFC 20260816：盯盘买点原料）──

    /** 读取自选股列表。 */
    public List<WatchlistItem> watchlistList(String userId) {
        return watchlistRepository.findAll(userId);
    }

    /** 导入自选股（通达信导出文本；以文件为准全量替换）。
     *  <p>2026-08-27 策略变更（用户拍板，覆盖+归档）：原「按 symbol 合并 upsert」→「覆盖」——
     *  自选列表 = 最后一次导入的镜像，通达信里删除的自选随之消失；导入前旧列表自动归档
     *  （{@code watchlist.json.bak-<ts>}）供回滚（当日清仓股文件误导入 → 170 只污染事故的直接诱因）。
     *  同名条目保留原 addedAt（首次加入日），仅真正新增记今天。</p>
     *  <p>格式校验：缺形态列（非自选导出，如清仓股文件）→ 解析为空 → 抛业务异常 400 + 人话提示，
     *  不再静默 no-op（REVIEW #147 风格；前端导入对话框 toast 透出）。</p>
     */
    public WatchlistImportResult watchlistImport(String userId, String content) {
        List<WatchlistItem> parsed = TradingImportParser.parseWatchlist(content);
        if (parsed.isEmpty()) {
            throw new TradingException("无法识别为自选股导出：缺少形态列（长期/中期/短期形态）——是否选错了文件（如清仓股/资金股份/历史成交导出）？");
        }
        synchronized (tradeLock(userId)) {
            List<WatchlistItem> current = new ArrayList<>(watchlistRepository.findAll(userId));
            // 覆盖前归档旧列表（撤销保险；失败不阻塞导入）
            try {
                watchlistRepository.archive(userId, LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            } catch (Exception e) {
                log.warn("自选归档失败（不阻塞导入）| userId={} | {}", userId, e.getMessage());
            }
            Map<String, LocalDate> existingAddedAt = new HashMap<>();
            for (WatchlistItem it : current) existingAddedAt.put(it.symbol(), it.addedAt());
            List<WatchlistItem> next = new ArrayList<>(parsed.size());
            for (WatchlistItem item : parsed) {
                LocalDate addedAt = existingAddedAt.getOrDefault(item.symbol(), LocalDate.now());
                next.add(new WatchlistItem(item.symbol(), item.name(), item.industry(), item.industry2(),
                        item.longForm(), item.midForm(), item.shortForm(), item.signal(), addedAt));
            }
            watchlistRepository.saveAll(userId, next);
        }
        log.info("自选股导入（覆盖）| userId={} | {} 只", userId, parsed.size());
        return new WatchlistImportResult(parsed.size());
    }

    /** 删除自选股。 */
    public boolean watchlistRemove(String userId, String symbol) {
        synchronized (tradeLock(userId)) {
            List<WatchlistItem> current = new ArrayList<>(watchlistRepository.findAll(userId));
            boolean removed = current.removeIf(it -> it.symbol().equals(symbol));
            if (removed) watchlistRepository.saveAll(userId, current);
            return removed;
        }
    }

    // ── 清仓股（RFC 20260816：复盘闭环）──

    /** 读取清仓股列表。 */
    public List<SoldTrade> soldList(String userId) {
        return soldTradeRepository.findAll(userId);
    }

    /** 导入清仓股（通达信导出文本；按 symbol upsert，保留已有 verdict/psychology）。 */
    public SoldImportResult soldImport(String userId, String content) {
        List<SoldTrade> parsed = TradingImportParser.parseSold(content);
        if (parsed.isEmpty()) return new SoldImportResult(0);
        synchronized (tradeLock(userId)) {
            List<SoldTrade> current = new ArrayList<>(soldTradeRepository.findAll(userId));
            for (SoldTrade t : parsed) {
                boolean found = false;
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).symbol().equals(t.symbol())) {
                        SoldTrade old = current.get(i);
                        // 保留已有心理标注，刷新日期/涨幅（P3 2026-08-17：verdict 下方统一重算，
                        // 此处不再声称「保留 verdict」——确定性覆盖，避免注释误导）
                        current.set(i, new SoldTrade(t.symbol(), t.name(), t.buyDate(), t.sellDate(),
                                t.holdDays(), t.tradeCount(), t.holdPnlPct(),
                                old.verdict(), old.psychology()));
                        found = true;
                        break;
                    }
                }
                if (!found) current.add(t);
            }
            // D1（2026-08-16）：规则对照生成 verdict（R53/R66），保留已有心理
            // 第三阶段：阈值按用户规则配置（rules.yaml params；无规则 → 默认 -5%/5 天，R66/R53 语义）
            TradingRuleSettings ruleSettings = tradingRuleSettingsRepository.findByUser(userId);
            double stopLossPct = ruleSettings.soldStopLossPct();
            int shortHoldDays = ruleSettings.soldShortHoldDays();
            for (int i = 0; i < current.size(); i++) {
                SoldTrade t = current.get(i);
                String verdict = SoldTradeVerdict.compute(t.holdPnlPct(), t.holdDays(),
                        stopLossPct, shortHoldDays, "R66", "R53");
                current.set(i, new SoldTrade(t.symbol(), t.name(), t.buyDate(), t.sellDate(),
                        t.holdDays(), t.tradeCount(), t.holdPnlPct(), verdict, t.psychology()));
            }
            soldTradeRepository.saveAll(userId, current);
        }
        log.info("清仓股导入 | userId={} | {} 笔（含规则对照 verdict）", userId, parsed.size());
        return new SoldImportResult(parsed.size());
    }

    /** 补/改心理标注（用户复盘素材）。 */
    public boolean soldUpdatePsychology(String userId, String symbol, String psychology) {
        synchronized (tradeLock(userId)) {
            List<SoldTrade> current = new ArrayList<>(soldTradeRepository.findAll(userId));
            for (int i = 0; i < current.size(); i++) {
                SoldTrade t = current.get(i);
                if (t.symbol().equals(symbol)) {
                    current.set(i, new SoldTrade(t.symbol(), t.name(), t.buyDate(), t.sellDate(),
                            t.holdDays(), t.tradeCount(), t.holdPnlPct(), t.verdict(), psychology));
                    soldTradeRepository.saveAll(userId, current);
                    return true;
                }
            }
            return false;
        }
    }

    // ── 资金股份查询（cashBalance + 精确成本）──

    /** 导入资金股份查询：存账户快照（资产/可用/可取/市值/盈亏/当日盈亏）+ 更新 cashBalance + 精确成本。 */
    public CashImportResult importCashQuery(String userId, String content) {
        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);
        // 2026-08-17（P1-交易5 修复）：解析失败（首行「余额/可用/可取/参考市值/资产/盈亏」未命中）
        // 禁止落零覆盖——此前会把 account.json 资产/现金清零、cashBalance 置零且无提示（B51 检查点）
        if (!q.headerMatched()) {
            throw new TradingException("无法识别资金股份查询格式——请确认首行是「余额:… 可用:… 可取:… 参考市值:… 资产:… 盈亏:…」，且是通达信资金股份导出");
        }
        // 账户总体快照（券商口径，顶层账户卡数据源）——当日盈亏 = 明细当日盈亏和
        double todayPnl = q.positions().stream().mapToDouble(TradingImportParser.CashPosition::todayPnl).sum();
        // P0-2（2026-08-23）：account.json 写统一走 update（per-user 锁内原子 RMW），
        // 原 save 在 tradeLock 外 → 与 recordTrade/转账/收盘更新并发互相覆盖
        // B6-4（2026-08-23，P1-交易11）：写失败上抛（不再静默）——资金导入是用户主动修正账目的动作，
        // 必须让用户知道没生效（controller → 400 人话）
        accountSnapshotRepository.update(userId, cur -> new AccountSnapshot(
                q.assets(), q.cash(), q.available(), q.withdrawable(),
                q.marketValue(), q.pnl(), BigDecimal.valueOf(todayPnl),
                cur.map(AccountSnapshot::principal).orElse(BigDecimal.ZERO), LocalDate.now()));
        synchronized (tradeLock(userId)) {
            // 1. cashBalance 更新
            java.math.BigDecimal cash = q.cash();
            List<Position> positions = new ArrayList<>(positionRepository.findAll(userId));
            int updated = 0;
            // 2. 精确成本价更新（资金查询 4 位 > 持仓导出 2-3 位）
            for (Position p : positions) {
                for (TradingImportParser.CashPosition cp : q.positions()) {
                    if (cp.symbol().equals(p.symbol()) && cp.costPrice() > 0) {
                        java.math.BigDecimal precise = java.math.BigDecimal.valueOf(cp.costPrice());
                        if (precise.compareTo(p.avgCost()) != 0) {
                            positions.set(positions.indexOf(p),
                                    new Position(p.symbol(), p.name(), p.quantity(), precise, p.currentPrice(),
                                            p.lastUpdated(), p.entryDate(), p.stopLossPrice(), p.buyPoint(), p.role()));
                            updated++;
                        }
                        break;
                    }
                }
            }
            if (!positions.isEmpty()) positionRepository.saveAll(userId, positions);
            // S5（2026-08-17）：现金唯一真源 = account.json（上方已保存）——不再写 positions.md cashBalance
            log.info("资金查询导入 | userId={} | 现金={} 资产={} | 成本更新 {} 只",
                    userId, cash, q.assets(), updated);
            return new CashImportResult(cash, q.assets(), updated);
        }
    }

    /**
     * 银证转账（2026-08-16 净投入跟踪）：转入/转出 → 更新本金（净投入）+ 现金 + 资产，
     * 追加流水。总盈亏 = 资产 - 本金：转账本身不变盈亏（转钱不算赚亏），后续行情/买卖推导。
     */
    public TransferRecord recordTransfer(String userId, String type, BigDecimal amount,
                                         LocalDate date, String note) {
        TransferRecord record = new TransferRecord(IdGenerator.monotonic("transfer_"),
                type, amount, date, note);
        synchronized (tradeLock(userId)) {
            // P0-2（2026-08-23）：account.json 写统一走 update（per-user 锁原子 RMW）
            AccountSnapshot updated = accountSnapshotRepository.update(userId, cur -> {
                AccountSnapshot current = cur.orElse(new AccountSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, LocalDate.now()));
                BigDecimal delta = record.isIn() ? amount : amount.negate();
                return new AccountSnapshot(
                        current.assets().add(delta),
                        current.cash().add(delta),
                        current.available().add(delta),
                        current.withdrawable().add(delta),
                        current.marketValue(),
                        current.pnl(),
                        current.todayPnl(),
                        // 净投入 += 转入 - 转出（用户确认：本金 = 净投入累计）
                        current.principal().add(delta),
                        LocalDate.now());
            });
            transferRepository.append(userId, record);
            log.info("银证转账 | userId={} | {} {} | 本金净投入 → {}",
                    userId, record.isIn() ? "转入" : "转出", amount,
                    updated != null ? updated.principal() : amount);
            return record;
        }
    }

    /** 转账流水（web 展示用）。 */
    public List<TransferRecord> transferList(String userId) {
        return transferRepository.findAll(userId);
    }

    /** 读取最近账户快照（顶层账户卡数据源）。 */
    public AccountSnapshot accountSnapshot(String userId) {
        return accountSnapshotRepository.findLatest(userId)
                .orElse(new AccountSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, null));
    }

    /**
     * 设置本金（累计净投入，2026-08-18 确认批次）。
     * <p>
     * 背景：总盈亏 = 资产 − 本金；资金股份查询导入/转账推导都不覆盖本金，新建账号 principal=0
     * → 总盈亏失真。本金是「累计净投入」的历史事实，不是当前资金变动——
     * <b>只改 principal 字段，不动现金/资产/市值</b>（转账会动现金，不能用来初始化本金）。
     */
    public AccountSnapshot setPrincipal(String userId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new TradingException("本金必须是大于 0 的金额");
        }
        synchronized (tradeLock(userId)) {
            // P0-2（2026-08-23）：account.json 写统一走 update（per-user 锁原子 RMW）
            AccountSnapshot updated = accountSnapshotRepository.update(userId, cur -> {
                AccountSnapshot current = cur.orElse(new AccountSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, LocalDate.now()));
                return new AccountSnapshot(
                        current.assets(), current.cash(), current.available(), current.withdrawable(),
                        current.marketValue(), current.pnl(), current.todayPnl(),
                        amount, current.snapshotDate());
            });
            log.info("本金设置 | userId={} | principal → {}（总盈亏 = 资产 {} - 本金 = {}）",
                    userId, amount, updated != null ? updated.assets() : BigDecimal.ZERO,
                    updated != null ? updated.assets().subtract(amount) : BigDecimal.ZERO);
            return updated;
        }
    }

    // ── 历史成交导入（第五份文件：通达信「历史成交查询」导出，2026-08-18）──

    /**
     * 导入历史成交日志（增量补录 + 缺失字段回填）。
     * <p>
     * 把券商成交逐笔补进 {@code trades/} 流水（entryDate=成交日、fee=券商实扣、orderId=成交编号幂等），
     * 供交易历史/复盘/对账使用。设计原则（2026-08-18 确认批次 + 2026-08-23 回填批次）：
     * <ul>
     *   <li><b>不重算持仓/现金</b>——历史成交往往缺窗口前基线（本次 8/3 起、8/3 前已有持仓），
     *       回放重建算不出券商口径（摊薄成本 vs 系统加权平均实测差 3.4 倍）；
     *       持仓/成本/现金以「全量覆盖」导入（positions/import replace + imports/cash）为准</li>
     *   <li><b>幂等 + 回填</b>——按成交编号 orderId 去重，重复导入同一文件不落重复流水；
     *       已存在 orderId 且旧记录成交时间缺失时，用新文件值回填（2026-08-23，用户实测重传不更新）
     *       计入 updated；无编号按 (symbol, direction, entryDate, price, volume) 指纹去重</li>
     *   <li><b>非交易事件跳过</b>——数量 0 行（如股息红利税资金下账）不落流水，计入 nonTrades</li>
     *   <li><b>对账提示</b>——每标的返回流水净增减 vs 当前持仓，指出窗口前基线或未导入成交</li>
     * </ul>
     */
    public HistoricalTradeImportResult importHistoricalTrades(String userId, String content) {
        List<TradingImportParser.HistoricalTradeRow> rows = TradingImportParser.parseHistoricalTrades(content);
        if (rows.isEmpty()) {
            throw new TradingException("无法识别历史成交导出——请确认表头含「成交日期/证券代码/买卖标志」且为通达信历史成交查询导出");
        }
        // 2026-08-25 用户反馈：明显非股票代码（通达信占位段 79/80/81/82，如 799999「登记指定」）一律不落库，
        // 计入 nonTrades（与股息红利税同口径，前端「非交易 N」可见）
        int nonTradable = (int) rows.stream()
                .filter(r -> TradingImportParser.isNonTradableCode(r.symbol())).count();
        if (nonTradable > 0) {
            log.warn("历史成交导入跳过非交易占位代码 {} 条（非股票，不入库）| userId={} | 示例: {}",
                    nonTradable, userId,
                    rows.stream().filter(r -> TradingImportParser.isNonTradableCode(r.symbol()))
                            .map(r -> r.symbol() + " " + r.name()).distinct().toList());
            rows = rows.stream().filter(r -> !TradingImportParser.isNonTradableCode(r.symbol())).toList();
        }
        // RFC 20260825 §5：自动识别双模式——窗口内成交 → 同步（更新持仓/现金/流水 + 每日操作总结）；
        // 窗口外历史 → 补录（只补流水 + 对账提示）。
        // 对抗审查 P1-1：混合窗口拆组并行处理，不再「混一笔超窗成交就整批降级」——
        // 用户导历史成交常带几笔漏掉的更早成交，近日部分照常同步持仓。
        LocalDate windowStart = LocalDate.now().minusDays(10);
        List<TradingImportParser.HistoricalTradeRow> recent = rows.stream()
                .filter(r -> r.entryDate() != null && !r.entryDate().isBefore(windowStart))
                .toList();
        List<TradingImportParser.HistoricalTradeRow> old = rows.stream()
                .filter(r -> r.entryDate() == null || r.entryDate().isBefore(windowStart))
                .toList();
        if (old.isEmpty()) {
            HistoricalTradeImportResult r = importSync(userId, recent);
            return withNonTrades(r, r.nonTrades() + nonTradable);
        }
        // 先补录历史（只补流水），再同步近日（sync 的幂等指纹基于补录后的全量流水）
        HistoricalTradeImportResult appendResult = importAppend(userId, old);
        if (recent.isEmpty()) {
            return withNonTrades(appendResult, appendResult.nonTrades() + nonTradable);
        }
        HistoricalTradeImportResult syncResult = importSync(userId, recent);
        return new HistoricalTradeImportResult(
                appendResult.imported() + syncResult.imported(),
                appendResult.updated() + syncResult.updated(),
                appendResult.skipped() + syncResult.skipped(),
                appendResult.nonTrades() + syncResult.nonTrades() + nonTradable,
                syncResult.lines(), "sync", syncResult.summary());
    }

    /** 复制导入结果并替换 nonTrades（占位代码计数并入，2026-08-25）。 */
    private HistoricalTradeImportResult withNonTrades(HistoricalTradeImportResult r, int nonTrades) {
        return new HistoricalTradeImportResult(r.imported(), r.updated(), r.skipped(),
                nonTrades, r.lines(), r.syncMode(), r.summary());
    }

    /**
     * 同步模式（当日/近日成交导入，RFC 20260825 §5）：幂等过滤 → 按成交时间排序 →
     * 逐笔走 recordTrade 全链路（持仓增减 + 现金 + 手续费 + 逐笔流水 + 时间线记录），
     * 返回对账 + 每日操作总结（含行为标注）。
     */
    private HistoricalTradeImportResult importSync(String userId, List<TradingImportParser.HistoricalTradeRow> rows) {
        long t0 = System.nanoTime(); // 2026-08-25：导入耗时定位（锁等待/幂等/落盘分段）
        // 导入前批次快照：diff 出新增/扣减批次（每日操作总结）
        Map<String, List<TradingLot>> before = tradingLotService.derive(userId);
        int imported = 0, skipped = 0, updated = 0, nonTrades = 0;
        synchronized (tradeLock(userId)) {
            Map<String, TradeRecord> byOrderId = new HashMap<>();
            Set<String> orderIds = new HashSet<>();
            Set<String> fingerprints = new HashSet<>();
            for (TradeRecord t : tradingHistoryRepository.findAll(userId)) {
                if (t.orderId() != null && !t.orderId().isBlank()) {
                    orderIds.add(t.orderId());
                    byOrderId.put(t.orderId(), t);
                } else {
                    fingerprints.add(fingerprint(t.symbol(), t.direction(), t.entryDate(), t.price(), t.volume()));
                }
            }
            List<TradingImportParser.HistoricalTradeRow> toSync = new ArrayList<>();
            for (TradingImportParser.HistoricalTradeRow r : rows) {
                if (r.volume() <= 0) {
                    // 2026-08-25 方案 A：股息类资金事件记账（入账 +现金 / 红利税 −现金，不进持仓/批次）；
                    // 其余数量 0 行（如纯股息红利税无备注识别）计入 nonTrades
                    if (TradingImportParser.isDividendEvent(r)) {
                        applyDividendCash(userId, r);
                    } else {
                        nonTrades++;
                    }
                    continue;
                }
                String oid = r.orderId();
                if (oid != null && !oid.isBlank()) {
                    if (orderIds.contains(oid)) { skipped++; continue; }
                    // 对抗审查 P0-1：手动记录（流水无 orderId）与收盘导入（有 orderId）同笔交叉防重——
                    // 主场景「白天手动记一笔 + 收盘导当天成交」，有 orderId 的行也查指纹防重复入账
                    String fp = fingerprint(r.symbol(), r.direction(), r.entryDate(), r.price(), r.volume());
                    if (fingerprints.contains(fp)) { skipped++; continue; }
                    orderIds.add(oid);
                    fingerprints.add(fp); // 双键都入：同文件内无编号变体也防重
                } else {
                    String fp = fingerprint(r.symbol(), r.direction(), r.entryDate(), r.price(), r.volume());
                    if (fingerprints.contains(fp)) { skipped++; continue; }
                    fingerprints.add(fp);
                }
                toSync.add(r);
            }
            // LIFO 依赖时间序：按成交日期 + 成交时刻排序后逐笔处理（A 股 T+1，顺序确定）
            toSync.sort(java.util.Comparator
                    .comparing((TradingImportParser.HistoricalTradeRow r) -> r.entryDate() != null ? r.entryDate() : LocalDate.MIN)
                    .thenComparing(r -> r.tradeTime() != null ? r.tradeTime() : LocalTime.MIN));
            for (TradingImportParser.HistoricalTradeRow r : toSync) {
                try {
                    // 通达信成交无止损/买点列 → null；批次止损由推导层按默认 −7% 兜底（RFC 20260825）。
                    // orderId 透传流水落盘 = 幂等键；fee 透传券商实扣（后端审查 P1-2，与 append 模式同口径）。
                    recordTradeWithOrderId(userId, r.symbol(), r.name(), r.direction(), r.price(), r.volume(),
                            r.entryDate(), r.tradeTime(), null, null, null, null, r.orderId(), r.fee());
                    imported++;
                } catch (TradingException e) {
                    // 逐条失败不整批回滚（与 /trades/batch 同语义）：跳过继续，失败不阻塞其余
                    log.warn("当日成交同步单笔失败 | userId={} | {} {} | {}", userId, r.direction(), r.symbol(), e.getMessage());
                    skipped++;
                }
            }
        }
        List<ReconcileLine> lines = tradingLotService.reconcile(userId);
        DailyOperationSummary summary = buildDailySummary(userId, rows, before);
        log.info("当日成交同步导入 | userId={} | 同步 {} 笔 | 去重跳过 {} | 非交易 {} | 对账 {} 行 | 买 {} 卖 {} 新增批次 {} 扣减 {} 行为 {} | 耗时 {}ms",
                userId, imported, skipped, nonTrades, lines.size(),
                summary.buyCount(), summary.sellCount(), summary.newLots(), summary.deductedLots(),
                summary.behaviors().size(), (System.nanoTime() - t0) / 1_000_000);
        return new HistoricalTradeImportResult(imported, updated, skipped, nonTrades, lines, "sync", summary);
    }

    /** 补录模式（历史成交导入，原语义）：只补流水不重算持仓/现金，返回对账提示。 */
    private HistoricalTradeImportResult importAppend(String userId, List<TradingImportParser.HistoricalTradeRow> rows) {
        long t0 = System.nanoTime(); // 2026-08-25：导入耗时定位（含锁等待）
        int imported = 0, skipped = 0, updated = 0, nonTrades = 0;
        List<TradeRecord> toAdd = new ArrayList<>();
        synchronized (tradeLock(userId)) {
            Map<String, TradeRecord> byOrderId = new HashMap<>();
            Set<String> orderIds = new HashSet<>();
            Set<String> fingerprints = new HashSet<>();
            for (TradeRecord t : tradingHistoryRepository.findAll(userId)) {
                if (t.orderId() != null && !t.orderId().isBlank()) {
                    orderIds.add(t.orderId());
                    byOrderId.put(t.orderId(), t);
                } else {
                    fingerprints.add(fingerprint(t.symbol(), t.direction(), t.entryDate(), t.price(), t.volume()));
                }
            }
            for (TradingImportParser.HistoricalTradeRow r : rows) {
                if (r.volume() <= 0) {
                    // 2026-08-25 方案 A：股息类资金事件记账（入账 +现金 / 红利税 −现金，不进持仓/批次）
                    if (TradingImportParser.isDividendEvent(r)) {
                        applyDividendCash(userId, r);
                    } else {
                        nonTrades++;
                    }
                    continue;
                }
                String oid = r.orderId();
                if (oid != null && !oid.isBlank()) {
                    if (orderIds.contains(oid)) {
                        // 幂等命中：旧记录缺失成交时间且新文件带时间 → 回填；否则跳过
                        TradeRecord existing = byOrderId.get(oid);
                        if (existing != null && existing.tradeTime() == null && r.tradeTime() != null) {
                            updated += tradingHistoryRepository.backfillTradeTime(
                                    userId, existing.id(), existing.entryDate(), r.tradeTime());
                        } else {
                            skipped++;
                        }
                        continue;
                    }
                    orderIds.add(oid);
                } else {
                    String fp = fingerprint(r.symbol(), r.direction(), r.entryDate(), r.price(), r.volume());
                    if (fingerprints.contains(fp)) { skipped++; continue; }
                    fingerprints.add(fp);
                }
                TradeRecord trade = TradeRecord.of(
                        IdGenerator.monotonic("trade_"),
                        r.symbol(), r.name(), r.direction(), r.price(), r.volume(),
                        r.entryDate(), r.tradeTime(), null, null, null, null, r.fee(),
                        LocalDateTime.now(), null, oid);
                toAdd.add(trade);
                imported++;
            }
            for (TradeRecord t : toAdd) tradingHistoryRepository.append(userId, t);
        }
        List<ReconcileLine> lines = reconcileHistorical(userId, rows);
        log.info("历史成交补录导入 | userId={} | 导入 {} 笔 | 回填 {} 笔 | 去重跳过 {} | 非交易 {} | 对账 {} 行 | 耗时 {}ms",
                userId, imported, updated, skipped, nonTrades, lines.size(), (System.nanoTime() - t0) / 1_000_000);
        return new HistoricalTradeImportResult(imported, updated, skipped, nonTrades, lines, "append", null);
    }

    /**
     * 股息类资金事件记账（2026-08-25 用户拍板方案 A）：
     * 股息入账（发生金额为正）→ 现金 +N；股息红利税（发生金额为负）→ 现金 −N。
     * 不动持仓、不进批次；落一条 volume=0 的流水（amount=发生金额，reason=源文件备注）可回溯。
     * 幂等：股息行按（symbol, entryDate, 发生金额）指纹去重，重复导入不重复记账。
     */
    private void applyDividendCash(String userId, TradingImportParser.HistoricalTradeRow r) {
        long t0 = System.nanoTime();
        BigDecimal occurred = r.occurred();
        if (occurred == null || occurred.signum() == 0) {
            log.warn("股息类事件无发生金额，跳过记账 | userId={} | {} {}", userId, r.symbol(), r.remark());
            return;
        }
        // 幂等指纹（股息行无 orderId）：symbol|entryDate|发生金额绝对值
        // （红利税为负值，流水存绝对值——统一用 abs 防 -7.5 vs 7.5 不匹配导致重复记账）
        String fp = "DIV:" + r.symbol() + "|" + r.entryDate() + "|" + occurred.abs().stripTrailingZeros();
        try {
            synchronized (tradeLock(userId)) {
                List<TradeRecord> all = tradingHistoryRepository.findAll(userId);
                if (all.stream().anyMatch(t -> fp.equals("DIV:" + t.symbol() + "|" + t.entryDate()
                        + "|" + (t.amount() != null ? t.amount() : BigDecimal.ZERO).stripTrailingZeros()))) {
                    log.debug("股息类事件已记账（幂等）| userId={} | {}", userId, fp);
                    return;
                }
                // 现金 ± 发生金额（只动现金/资产，不动本金/持仓）
                accountSnapshotRepository.update(userId, cur -> cur.map(c -> new AccountSnapshot(
                        c.assets().add(occurred),
                        c.cash().add(occurred),
                        c.available().add(occurred),
                        c.withdrawable().add(occurred),
                        c.marketValue(), c.pnl(), c.todayPnl(), c.principal(), c.snapshotDate()))
                        .orElse(null)); // 无账户快照（未导入资金）不初始化，保持既有语义
                // 落流水可回溯：direction = 入账 BUY / 税 SELL，volume 0，amount = 发生金额绝对值，reason = 源文件备注
                TradeDirection dir = occurred.signum() > 0 ? TradeDirection.BUY : TradeDirection.SELL;
                TradeRecord tr = new TradeRecord(
                        IdGenerator.monotonic("trade_"), r.symbol(), r.name(), dir,
                        BigDecimal.ZERO, 0, occurred.abs(), r.entryDate(), r.tradeTime(),
                        null, null, null, r.remark(), null, LocalDateTime.now(), null, null);
                tradingHistoryRepository.append(userId, tr);
            }
        } catch (RuntimeException e) {
            log.warn("股息类事件记账失败 | userId={} | {} | {}", userId, r.symbol(), e.getMessage());
        } finally {
            log.info("股息记账 | userId={} | {} {} 元 | 耗时 {}ms", userId, r.symbol(), r.occurred(),
                    (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** 每日操作总结（RFC 20260825 §6）：客观聚合 + 批次 diff + 行为标注——不耗 AI 秒出。 */    private DailyOperationSummary buildDailySummary(String userId, List<TradingImportParser.HistoricalTradeRow> rows,
                                                    Map<String, List<TradingLot>> before) {
        LocalDate date = rows.stream().map(TradingImportParser.HistoricalTradeRow::entryDate)
                .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(LocalDate.now());
        int buyCount = 0, sellCount = 0;
        double buyAmount = 0, sellAmount = 0;
        for (TradingImportParser.HistoricalTradeRow r : rows) {
            if (r.volume() <= 0) continue;
            double amt = r.price().multiply(BigDecimal.valueOf(r.volume())).doubleValue();
            if (r.direction() == TradeDirection.BUY) { buyCount++; buyAmount += amt; }
            else { sellCount++; sellAmount += amt; }
        }
        // 批次 diff：导入后新增批次 / 被扣减批次
        Map<String, List<TradingLot>> after = tradingLotService.derive(userId);
        Set<String> beforeIds = before.values().stream().flatMap(List::stream)
                .map(TradingLot::lotId).collect(java.util.stream.Collectors.toSet());
        Set<String> afterIds = after.values().stream().flatMap(List::stream)
                .map(TradingLot::lotId).collect(java.util.stream.Collectors.toSet());
        int newLots = (int) afterIds.stream().filter(id -> !beforeIds.contains(id)).count();
        Map<String, TradingLot> beforeById = before.values().stream().flatMap(List::stream)
                .collect(java.util.stream.Collectors.toMap(TradingLot::lotId, l -> l, (a, b) -> a));
        int deductedLots = 0;
        for (List<TradingLot> lots : after.values()) {
            for (TradingLot l : lots) {
                TradingLot b = beforeById.get(l.lotId());
                if (b != null && b.remaining() > l.remaining()) deductedLots++;
            }
        }
        // P2-批次2（审查归口）：多日导入 → 每个交易日各做一次行为标注再合并（同 标的+类型+日期 去重）——
        // 原来只分析最大日期，前几天的亏损加仓/追高被漏标（10 天窗口内一次导多天成交的场景）
        List<TradingLotService.BehaviorNote> behaviors = new ArrayList<>();
        Set<String> behaviorKeys = new HashSet<>();
        for (LocalDate d : rows.stream().map(TradingImportParser.HistoricalTradeRow::entryDate)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList()) {
            for (TradingLotService.BehaviorNote b : tradingLotService.analyzeBehaviors(userId, d)) {
                if (behaviorKeys.add(b.type() + "|" + b.symbol() + "|" + b.date())) {
                    behaviors.add(b);
                }
            }
        }
        return new DailyOperationSummary(date.toString(), buyCount, sellCount,
                round2(buyAmount), round2(sellAmount), newLots, deductedLots, behaviors);
    }

    /** 幂等指纹（无成交编号时）：symbol|direction|entryDate|price|volume。
     *  价格 stripTrailingZeros 归一化（坑：BigDecimal.equals 区分 scale——手动记录 12.0 vs 导入 12.00000000 必须视为同价）。 */
    private String fingerprint(String symbol, TradeDirection direction, LocalDate entryDate,
                               BigDecimal price, int volume) {
        return symbol + "|" + direction + "|" + entryDate + "|"
                + (price != null ? price.stripTrailingZeros().toPlainString() : "") + "|" + volume;
    }

    /** 对账：每标的 流水净增减 vs 当前持仓数量 → 基线缺口提示（只报告，不改数据）。 */
    private List<ReconcileLine> reconcileHistorical(String userId, List<TradingImportParser.HistoricalTradeRow> rows) {
        Map<String, ReconcileAcc> acc = new LinkedHashMap<>();
        for (TradingImportParser.HistoricalTradeRow r : rows) {
            if (r.volume() <= 0) continue;
            ReconcileAcc a = acc.computeIfAbsent(r.symbol(), k -> new ReconcileAcc(r.name()));
            a.count++;
            a.netVolume += r.direction() == TradeDirection.BUY ? r.volume() : -r.volume();
        }
        Map<String, Position> holdings = positionRepository.findAll(userId).stream()
                .collect(java.util.stream.Collectors.toMap(Position::symbol, p -> p, (a, b) -> a));
        List<ReconcileLine> lines = new ArrayList<>();
        for (Map.Entry<String, ReconcileAcc> e : acc.entrySet()) {
            ReconcileAcc a = e.getValue();
            Position h = holdings.get(e.getKey());
            String note;
            if (h == null) {
                note = "当前无持仓——已清仓或快照未含（流水净 " + signed(a.netVolume) + " 股）";
            } else if (h.quantity() == a.netVolume) {
                note = "流水净增减与持仓一致（窗口内成交完整）";
            } else {
                note = "当前持仓 " + h.quantity() + " ≠ 流水净 " + signed(a.netVolume)
                        + "——存在窗口前基线或未导入成交（以持仓快照为准）";
            }
            lines.add(new ReconcileLine(e.getKey(), a.name, a.count, a.netVolume,
                    h != null ? h.quantity() : null, note));
        }
        return lines;
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    /** 对账聚合（可变计数器）。 */
    private static final class ReconcileAcc {
        final String name;
        int count;
        int netVolume;

        ReconcileAcc(String name) {
            this.name = name;
        }
    }

    /** 对账行：每标的 导入笔数 / 流水净增减 / 当前持仓 / 人话提示。 */
    public record ReconcileLine(String symbol, String name, int count, int netVolume,
                                Integer holdings, String note) {}

    /** 历史成交导入结果：导入笔数 / 回填笔数 / 去重跳过 / 非交易事件 / 对账行 / 模式（sync 同步 | append 补录）/ 每日操作总结。 */
    public record HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                              List<ReconcileLine> lines, String syncMode,
                                              DailyOperationSummary summary) {
        /** 兼容旧 5 参构造（补录模式无总结）。 */
        public HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                           List<ReconcileLine> lines) {
            this(imported, updated, skipped, nonTrades, lines, null, null);
        }
    }

    /** 每日操作总结（RFC 20260825 §6，导入/归集后秒出，不耗 AI）：
     *  买卖聚合 + 批次 diff（新增/扣减）+ 行为标注。 */
    public record DailyOperationSummary(
            String date,
            int buyCount,
            int sellCount,
            double buyAmount,
            double sellAmount,
            int newLots,
            int deductedLots,
            List<TradingLotService.BehaviorNote> behaviors
    ) {}

    /** 一键同步持仓结果（2026-08-25）：positionCount 同步后持仓数 / removed 流水已清仓的快照残留 /
     *  keptInitial 保留的初始底仓（快照早于流水的真底仓）。 */
    public record SyncResult(int positionCount, List<String> removed, List<String> keptInitial) {}

    /** 自选导入结果。 */
    public record WatchlistImportResult(int imported) {}

    /** 清仓导入结果。 */
    public record SoldImportResult(int imported) {}

    /** 资金导入结果。 */
    public record CashImportResult(java.math.BigDecimal cash, java.math.BigDecimal assets, int updatedCost) {}


    // ── 内部方法 ──

    /**
     * 更新持仓（RFC 20260816 §2.2）：
     * <ul>
     *   <li>BUY（加仓）：摊平成本；entryDate 保留首买日（不覆盖）；stopLossPrice/buyPoint 更新为最近一次 BUY</li>
     *   <li>SELL：保留 entryDate/stopLossPrice/buyPoint/role</li>
     * </ul>
     */
    private Position updatePosition(String symbol, Position current, TradeDirection direction,
                                    BigDecimal price, int volume,
                                    LocalDate entryDate, BigDecimal stopLossPrice, String buyPoint) {
        switch (direction) {
            case BUY -> {
                // 摊平成本（2026-08-16 含手续费：加买入总成本 = 价×量 + 佣金 + 过户费）
                int newQty = current.quantity() + volume;
                BigDecimal newCost = current.costValue()
                        .add(CommissionCalculator.buyCost(symbol, price, volume))
                        .divide(BigDecimal.valueOf(newQty), 4, java.math.RoundingMode.HALF_UP);
                // 加仓不覆盖首买日：已有 entryDate 保留，缺失时以本次入场日期落盘
                LocalDate effectiveEntryDate = current.entryDate() != null ? current.entryDate() : entryDate;
                return new Position(current.symbol(), current.name(), newQty, newCost, price, LocalDateTime.now(),
                        effectiveEntryDate, stopLossPrice, buyPoint, current.role());
            }
            case SELL -> {
                int newQty = current.quantity() - volume;
                if (newQty <= 0) {
                    // 清仓：返回数量为 0 的持仓，上层应该过滤（止损/买点/入场保留在流水里可回溯）
                    return new Position(current.symbol(), current.name(), 0, BigDecimal.ZERO, price, LocalDateTime.now(),
                            current.entryDate(), current.stopLossPrice(), current.buyPoint(), current.role());
                }
                return new Position(current.symbol(), current.name(), newQty, current.avgCost(), price, LocalDateTime.now(),
                        current.entryDate(), current.stopLossPrice(), current.buyPoint(), current.role());
            }
            default -> throw new IllegalArgumentException("未知交易方向: " + direction);
        }
    }
}
