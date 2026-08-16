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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final MarketDataSource marketDataSource;

    public TradingAppService(PositionRepository positionRepository,
                             RecordRepository recordRepository,
                             TradingHistoryRepository tradingHistoryRepository,
                             MarketDataSource marketDataSource) {
        this.positionRepository = positionRepository;
        this.recordRepository = recordRepository;
        this.tradingHistoryRepository = tradingHistoryRepository;
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
                                      LocalDate entryDate, BigDecimal stopLossPrice, String buyPoint,
                                      BigDecimal targetPrice, String reason) {
        // #147：读-改-写加每用户锁，防并发交易互相覆盖丢持仓
        synchronized (tradeLock(userId)) {
            // RFC 20260815：name 可空（web 标注"可选"），缺名时以 symbol 兜底（简单方案：symbol 即名）
            String effectiveName = (name == null || name.isBlank()) ? symbol : name;
            // RFC 20260816：入场日期缺省今天（用户可补录）
            LocalDate effectiveEntryDate = entryDate != null ? entryDate : LocalDate.now();

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
                    Position updated = updatePosition(p, direction, price, volume,
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
                // 首次买入：新建持仓（首买日 + 止损/买点落盘；role 由 web 编辑，初始 null）
                Position newPos = new Position(symbol, effectiveName, volume, price, price, LocalDateTime.now(),
                        effectiveEntryDate, stopLossPrice, buyPoint, null);
                currentPositions.add(newPos);
            }

            // 清仓后的 0 持仓行不落盘（findAll 读取时本就过滤，保持文件干净）
            currentPositions.removeIf(p -> p.quantity() <= 0);

            positionRepository.saveAll(userId, currentPositions);

            // RFC 20260815 §6：交易成功后同步写一条 domain=trading 记录（复盘提醒 + 时间线闭环）。
            // 位置在 saveAll 成功之后：recordTrade 失败（校验/存储异常）路径不会留下记录；
            // 窗口内同标题（重试）不重复写（幂等）。返回时间线 Record ID 作为流水 sourceRecordId。
            String recordId = writeTradingRecord(userId, direction, effectiveName, symbol, price, volume);

            // RFC 20260816 §2.1：逐笔流水真相源（BUY/SELL 都写）。best-effort：
            // 持仓已落库，流水写入失败不阻塞交易本身（与 writeTradingRecord 同口径），只告警。
            appendTradeRecord(userId, symbol, effectiveName, direction, price, volume,
                    effectiveEntryDate, stopLossPrice, buyPoint, targetPrice, reason, recordId);

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
                                   BigDecimal price, int volume, LocalDate entryDate,
                                   BigDecimal stopLossPrice, String buyPoint,
                                   BigDecimal targetPrice, String reason, String sourceRecordId) {
        try {
            TradeRecord trade = TradeRecord.of(
                    IdGenerator.monotonic("trade_"),
                    symbol, name, direction, price, volume,
                    entryDate, stopLossPrice, buyPoint, targetPrice, reason,
                    null, LocalDateTime.now(), sourceRecordId);
            tradingHistoryRepository.append(userId, trade);
        } catch (Exception e) {
            log.warn("交易流水写入失败（不影响交易落库）| symbol={} | {}", symbol, e.getMessage());
        }
    }

    /**
     * 获取当前投资组合快照。
     */
    public PortfolioSnapshot getPortfolioSnapshot(String userId) {
        return positionRepository.snapshot(userId);
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
     *
     * @param items 导入项（代码/名称/数量/成本 + 可选止损/买点/角色/入场日期）
     */
    public PositionImportResult importPositions(String userId, List<PositionImportItem> items) {
        if (items == null || items.isEmpty()) {
            return new PositionImportResult(0, List.of());
        }
        synchronized (tradeLock(userId)) {
            List<Position> current = new ArrayList<>(positionRepository.findAll(userId));
            List<String> missingStopLoss = new ArrayList<>();
            int imported = 0;

            for (PositionImportItem item : items) {
                String symbol = item.symbol();
                if (symbol == null || symbol.isBlank()) continue;
                // name 缺失 → 行情补全（代码带名称）
                String name = (item.name() == null || item.name().isBlank())
                        ? (lookupName(symbol) != null ? lookupName(symbol) : symbol)
                        : item.name();

                boolean found = false;
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).symbol().equals(symbol)) {
                        Position p = current.get(i);
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
                if (item.stopLossPrice() == null) {
                    missingStopLoss.add(symbol + " " + name);
                }
                imported++;
            }

            current.removeIf(p -> p.quantity() <= 0);
            positionRepository.saveAll(userId, current);
            log.info("持仓初始化导入 | userId={} | 导入 {} 只 | 未设止损 {} 只",
                    userId, imported, missingStopLoss.size());
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

    // ── 内部方法 ──

    /**
     * 更新持仓（RFC 20260816 §2.2）：
     * <ul>
     *   <li>BUY（加仓）：摊平成本；entryDate 保留首买日（不覆盖）；stopLossPrice/buyPoint 更新为最近一次 BUY</li>
     *   <li>SELL：保留 entryDate/stopLossPrice/buyPoint/role</li>
     * </ul>
     */
    private Position updatePosition(Position current, TradeDirection direction, BigDecimal price, int volume,
                                    LocalDate entryDate, BigDecimal stopLossPrice, String buyPoint) {
        switch (direction) {
            case BUY -> {
                // 摊平成本
                int newQty = current.quantity() + volume;
                BigDecimal newCost = current.costValue()
                        .add(price.multiply(BigDecimal.valueOf(volume)))
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
