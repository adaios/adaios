package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.*;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
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

    public TradingAppService(PositionRepository positionRepository,
                             RecordRepository recordRepository,
                             TradingHistoryRepository tradingHistoryRepository,
                             WatchlistRepository watchlistRepository,
                             SoldTradeRepository soldTradeRepository,
                             AccountSnapshotRepository accountSnapshotRepository,
                             TransferRepository transferRepository,
                             MarketDataSource marketDataSource) {
        this.positionRepository = positionRepository;
        this.recordRepository = recordRepository;
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.watchlistRepository = watchlistRepository;
        this.soldTradeRepository = soldTradeRepository;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.transferRepository = transferRepository;
        this.marketDataSource = marketDataSource;
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
                    effectiveEntryDate, effectiveTradeTime, stopLossPrice, buyPoint, targetPrice, reason, recordId);

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
                                   BigDecimal targetPrice, String reason, String sourceRecordId) {
        try {
            TradeRecord trade = TradeRecord.of(
                    IdGenerator.monotonic("trade_"),
                    symbol, name, direction, price, volume,
                    entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason,
                    null, LocalDateTime.now(), sourceRecordId, null);
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
     * 获取所有持仓（注入实时行情：currentPrice=现价 → 盈亏/盈亏% 展示正确）。
     * <p>
     * 行情拉取失败/单票无行情 → 用存储价（=成本价，盈亏 0），降级不报错。
     */
    public List<Position> getPositions(String userId) {
        List<Position> stored = positionRepository.findAll(userId);
        if (stored.isEmpty()) return stored;
        Map<String, MarketData> quotes = Map.of();
        try {
            quotes = marketDataSource.quote(stored.stream().map(Position::symbol).toList());
        } catch (Exception e) {
            log.warn("持仓行情注入失败，使用存储价 | userId={} | {}", userId, e.getMessage());
        }
        if (quotes.isEmpty()) return stored;
        List<Position> result = new ArrayList<>();
        for (Position p : stored) {
            MarketData md = quotes.get(p.symbol());
            if (md != null && md.price() != null && md.price().compareTo(BigDecimal.ZERO) > 0) {
                result.add(new Position(p.symbol(), p.name(), p.quantity(), p.avgCost(), md.price(),
                        p.lastUpdated(), p.entryDate(), p.stopLossPrice(), p.buyPoint(), p.role()));
            } else {
                result.add(p);
            }
        }
        return result;
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

    /** 导入自选股（通达信导出文本，表头定位；按 symbol upsert）。 */
    public WatchlistImportResult watchlistImport(String userId, String content) {
        List<WatchlistItem> parsed = TradingImportParser.parseWatchlist(content);
        if (parsed.isEmpty()) return new WatchlistImportResult(0);
        synchronized (tradeLock(userId)) {
            List<WatchlistItem> current = new ArrayList<>(watchlistRepository.findAll(userId));
            for (WatchlistItem item : parsed) {
                boolean found = false;
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).symbol().equals(item.symbol())) {
                        current.set(i, item); // 覆盖（形态/指标最新）
                        found = true;
                        break;
                    }
                }
                if (!found) current.add(item);
            }
            watchlistRepository.saveAll(userId, current);
        }
        log.info("自选股导入 | userId={} | {} 只", userId, parsed.size());
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
            for (int i = 0; i < current.size(); i++) {
                SoldTrade t = current.get(i);
                String verdict = SoldTradeVerdict.compute(t.holdPnlPct(), t.holdDays());
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
                if (r.volume() <= 0) { nonTrades++; continue; } // 非交易事件（股息红利税等）
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
        log.info("历史成交导入 | userId={} | 导入 {} 笔 | 回填 {} 笔 | 去重跳过 {} | 非交易 {} | 对账 {} 行",
                userId, imported, updated, skipped, nonTrades, lines.size());
        return new HistoricalTradeImportResult(imported, updated, skipped, nonTrades, lines);
    }

    /** 幂等指纹（无成交编号时）：symbol|direction|entryDate|price|volume。 */
    private String fingerprint(String symbol, TradeDirection direction, LocalDate entryDate,
                               BigDecimal price, int volume) {
        return symbol + "|" + direction + "|" + entryDate + "|" + price + "|" + volume;
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

    /** 历史成交导入结果：导入笔数 / 回填笔数 / 去重跳过 / 非交易事件 / 对账行。 */
    public record HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                              List<ReconcileLine> lines) {}

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
