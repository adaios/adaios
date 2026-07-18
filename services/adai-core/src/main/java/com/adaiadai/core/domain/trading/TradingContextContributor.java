package com.adaiadai.core.domain.trading;

import com.adaiadai.core.kernel.context.engine.ContextContributor;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TradingContextContributor — 交易场景的上下文贡献者。
 * <p>
 * 为 trading 场景注入当前持仓摘要，使 AI 拥有持仓感知能力。
 * 实现 {@link ContextContributor} 接口，被 ContextEngine 自动发现。
 */
@Component
public class TradingContextContributor implements ContextContributor {

    private static final Logger log = LoggerFactory.getLogger(TradingContextContributor.class);

    private final PositionRepository positionRepository;

    public TradingContextContributor(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    @Override
    public boolean supports(String scene) {
        return "trading".equals(scene);
    }

    @Override
    public String enrich(String identityRef, ContentRecord record) {
        List<Position> positions = positionRepository.findAll();
        if (positions.isEmpty()) {
            log.info("Trading 场景：当前无持仓");
            return "";
        }

        PortfolioSnapshot snapshot = PortfolioSnapshot.of(positions, positionRepository.cashBalance());

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前持仓\n\n");
        sb.append("| 代码 | 名称 | 数量 | 成本价 | 现价 | 市值 | 盈亏 | 盈亏% |\n");
        sb.append("|------|------|------|--------|------|------|------|-------|\n");

        for (Position p : positions) {
            sb.append("| ").append(p.symbol())
                    .append(" | ").append(p.name())
                    .append(" | ").append(p.quantity())
                    .append(" | ").append(p.avgCost().stripTrailingZeros().toPlainString())
                    .append(" | ").append(p.currentPrice().stripTrailingZeros().toPlainString())
                    .append(" | ").append(p.marketValue().stripTrailingZeros().toPlainString())
                    .append(" | ").append(p.pnl().setScale(2).toPlainString())
                    .append(" | ").append(p.pnlPercent().setScale(2).toPlainString()).append("%")
                    .append(" |\n");
        }

        sb.append("\n**汇总**：总市值=")
                .append(snapshot.totalValue().setScale(2).toPlainString())
                .append("，总盈亏=")
                .append(snapshot.totalPnl().setScale(2).toPlainString())
                .append("，现金余额=")
                .append(snapshot.cashBalance().setScale(2).toPlainString());

        return sb.toString();
    }
}
