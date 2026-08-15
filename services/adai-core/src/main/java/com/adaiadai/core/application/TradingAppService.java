package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.*;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

    public TradingAppService(PositionRepository positionRepository, RecordRepository recordRepository) {
        this.positionRepository = positionRepository;
        this.recordRepository = recordRepository;
    }

    private Object tradeLock(String userId) {
        return userTradeLocks.computeIfAbsent(userId != null ? userId : "default", k -> new Object());
    }

    /**
     * 记录一笔交易并更新持仓。
     *
     * @param symbol    股票代码
     * @param name      股票名称
     * @param direction 交易方向
     * @param price     成交单价
     * @param volume    成交数量
     * @return 更新后的持仓列表
     */
    public List<Position> recordTrade(String userId, String symbol, String name,
                                      TradeDirection direction,
                                      BigDecimal price, int volume) {
        // #147：读-改-写加每用户锁，防并发交易互相覆盖丢持仓
        synchronized (tradeLock(userId)) {
            // RFC 20260815：name 可空（web 标注"可选"），缺名时以 symbol 兜底（简单方案：symbol 即名）
            String effectiveName = (name == null || name.isBlank()) ? symbol : name;

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
                    Position updated = updatePosition(p, direction, price, volume);
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
                // 首次买入：新建持仓
                Position newPos = new Position(symbol, effectiveName, volume, price, price, LocalDateTime.now());
                currentPositions.add(newPos);
            }

            // 清仓后的 0 持仓行不落盘（findAll 读取时本就过滤，保持文件干净）
            currentPositions.removeIf(p -> p.quantity() <= 0);

            positionRepository.saveAll(userId, currentPositions);

            // RFC 20260815 §6：交易成功后同步写一条 domain=trading 记录（复盘提醒 + 时间线闭环）。
            // 位置在 saveAll 成功之后：recordTrade 失败（校验/存储异常）路径不会留下记录；
            // 窗口内同标题（重试）不重复写（幂等）。
            writeTradingRecord(userId, direction, effectiveName, symbol, price, volume);

            log.info("交易已记录 | {} {} {}股@{}元 | 持仓数={}",
                    direction, symbol, volume, price, currentPositions.size());

            return currentPositions;
        }
    }

    /**
     * 交易成功后写 domain=trading 记录（标题如「买入 京东方A 1000股@5.20」）。
     * <p>
     * 目的：交易进 timeline/记忆 + {@code hasTradingActivity} 关键词（买/卖/股/交易…）命中，闭环复盘提醒。
     * 附加动作 best-effort：记录写入失败不阻塞交易本身（持仓已落库），只告警。
     * 幂等：5 分钟窗口内存在同标题记录（重试）→ 跳过，防重复进时间线。
     */
    private void writeTradingRecord(String userId, TradeDirection direction,
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
                return;
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
        } catch (Exception e) {
            log.warn("交易记录写入失败（不影响交易落库）| symbol={} | {}", symbol, e.getMessage());
        }
    }

    /**
     * 获取当前投资组合快照。
     */
    public PortfolioSnapshot getPortfolioSnapshot(String userId) {
        return positionRepository.snapshot(userId);
    }

    /**
     * 获取所有持仓。
     */
    public List<Position> getPositions(String userId) {
        return positionRepository.findAll(userId);
    }

    // ── 内部方法 ──

    private Position updatePosition(Position current, TradeDirection direction, BigDecimal price, int volume) {
        switch (direction) {
            case BUY -> {
                // 摊平成本
                int newQty = current.quantity() + volume;
                BigDecimal newCost = current.costValue()
                        .add(price.multiply(BigDecimal.valueOf(volume)))
                        .divide(BigDecimal.valueOf(newQty), 4, java.math.RoundingMode.HALF_UP);
                return new Position(current.symbol(), current.name(), newQty, newCost, price, LocalDateTime.now());
            }
            case SELL -> {
                int newQty = current.quantity() - volume;
                if (newQty <= 0) {
                    // 清仓：返回数量为 0 的持仓，上层应该过滤
                    return new Position(current.symbol(), current.name(), 0, BigDecimal.ZERO, price, LocalDateTime.now());
                }
                return new Position(current.symbol(), current.name(), newQty, current.avgCost(), price, LocalDateTime.now());
            }
            default -> throw new IllegalArgumentException("未知交易方向: " + direction);
        }
    }
}
