package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * TradingAppService — 交易领域应用服务。
 * <p>
 * 编排交易记录的完整流程：结构化交易输入 → Record → 更新持仓。
 * 独立的交易业务编排，不同于 RecordFlowAppService 的通用 MVP 流程。
 */
@Service
public class TradingAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingAppService.class);

    private final PositionRepository positionRepository;

    public TradingAppService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
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
    public List<Position> recordTrade(String symbol, String name,
                                      TradeDirection direction,
                                      BigDecimal price, int volume) {
        List<Position> currentPositions = new ArrayList<>(positionRepository.findAll());
        boolean found = false;

        for (int i = 0; i < currentPositions.size(); i++) {
            Position p = currentPositions.get(i);
            if (p.symbol().equals(symbol)) {
                Position updated = updatePosition(p, direction, price, volume);
                currentPositions.set(i, updated);
                found = true;
                break;
            }
        }

        if (!found && direction == TradeDirection.BUY) {
            // 首次买入：新建持仓
            Position newPos = new Position(symbol, name, volume, price, price, LocalDateTime.now());
            currentPositions.add(newPos);
        }

        positionRepository.saveAll(currentPositions);

        log.info("交易已记录 | {} {} {}股@{}元 | 持仓数={}",
                direction, symbol, volume, price, currentPositions.size());

        return currentPositions;
    }

    /**
     * 获取当前投资组合快照。
     */
    public PortfolioSnapshot getPortfolioSnapshot() {
        return positionRepository.snapshot();
    }

    /**
     * 获取所有持仓。
     */
    public List<Position> getPositions() {
        return positionRepository.findAll();
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
