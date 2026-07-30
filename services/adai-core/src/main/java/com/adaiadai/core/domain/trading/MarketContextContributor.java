package com.adaiadai.core.domain.trading;

import com.adaiadai.core.kernel.context.engine.ContextContributor;
import com.adaiadai.core.kernel.market.MarketData;
import com.adaiadai.core.kernel.market.MarketDataSource;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MarketContextContributor — 行情上下文贡献者。
 * <p>
 * 为 trading 场景注入实时大盘指数和持仓行情。
 * 持仓使用实时价格替换 {@code positions.md} 中的静态值。
 */
@Component
public class MarketContextContributor implements ContextContributor {

    private static final Logger log = LoggerFactory.getLogger(MarketContextContributor.class);

    private final MarketDataSource marketDataSource;
    private final PositionRepository positionRepository;

    public MarketContextContributor(MarketDataSource marketDataSource, PositionRepository positionRepository) {
        this.marketDataSource = marketDataSource;
        this.positionRepository = positionRepository;
        log.info("MarketContextContributor 已初始化");
    }

    @Override
    public boolean supports(String scene) {
        return "trading".equals(scene);
    }

    @Override
    public String enrich(String identityRef, ContentRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前行情\n\n");

        // 1. 大盘指数
        appendIndices(sb);

        // 2. 持仓行情（实时价格）
        appendPortfolio(sb);

        return sb.toString();
    }

    @Override
    public String globalContext() {
        // 所有场景都注入持仓概览（短版）
        List<Position> positions = positionRepository.findAll();
        if (positions.isEmpty()) return "";

        List<String> codes = positions.stream().map(Position::symbol).toList();
        Map<String, MarketData> quotes = marketDataSource.quote(codes);

        StringBuilder sb = new StringBuilder();
        sb.append("## 交易系统状态\n\n");
        sb.append("当前持有 ").append(positions.size()).append(" 个仓位。");

        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            BigDecimal price = md != null ? md.price() : p.currentPrice();
            BigDecimal pnl = price.subtract(p.avgCost())
                    .multiply(BigDecimal.valueOf(p.quantity()));
            BigDecimal pnlPct = p.avgCost().compareTo(BigDecimal.ZERO) > 0
                    ? price.subtract(p.avgCost()).divide(p.avgCost(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            sb.append("\n- ").append(p.name()).append("(").append(p.symbol()).append(")")
                    .append(" 现价").append(price.stripTrailingZeros().toPlainString())
                    .append(" ").append(pnlPct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                    .append(pnlPct.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("%");
        }

        // 大盘概览（简短一行）
        Map<String, MarketData> indices = marketDataSource.indices();
        if (!indices.isEmpty()) {
            sb.append("\n\n大盘：");
            for (var entry : indices.entrySet()) {
                MarketData idx = entry.getValue();
                sb.append(idx.name()).append(" ")
                        .append(idx.price().stripTrailingZeros().toPlainString())
                        .append("(").append(idx.changePercent().setScale(2, RoundingMode.HALF_UP).toPlainString()).append("%) ");
            }
        }

        return sb.toString();
    }

    // ── 内部方法 ──

    private void appendIndices(StringBuilder sb) {
        Map<String, MarketData> indices = marketDataSource.indices();
        if (indices.isEmpty()) {
            sb.append("**大盘指数：**（暂未获取到行情数据）\n\n");
            return;
        }

        sb.append("**大盘指数：**\n");
        for (var entry : indices.entrySet()) {
            MarketData idx = entry.getValue();
            String arrow = idx.changePercent().compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
            sb.append("- ").append(arrow).append(" ")
                    .append(idx.name()).append(" ")
                    .append(idx.price().stripTrailingZeros().toPlainString())
                    .append(" (").append(formatPct(idx.changePercent())).append(")")
                    .append("\n");
        }
        sb.append("\n");
    }

    private void appendPortfolio(StringBuilder sb) {
        List<Position> positions = positionRepository.findAll();
        if (positions.isEmpty()) {
            sb.append("**当前持仓：** 空仓\n");
            return;
        }

        // 批量查询实时价格
        List<String> codes = positions.stream().map(Position::symbol).toList();
        Map<String, MarketData> quotes = marketDataSource.quote(codes);

        sb.append("**当前持仓：**\n\n");
        sb.append("| 代码 | 名称 | 数量 | 成本价 | 现价 | 市值 | 盈亏 | 盈亏% |\n");
        sb.append("|------|------|------|--------|------|------|------|-------|\n");

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;

        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            BigDecimal price = md != null ? md.price() : p.currentPrice();
            BigDecimal value = price.multiply(BigDecimal.valueOf(p.quantity()));
            BigDecimal cost = p.avgCost().multiply(BigDecimal.valueOf(p.quantity()));
            BigDecimal pnl = value.subtract(cost);
            BigDecimal pnlPct = p.avgCost().compareTo(BigDecimal.ZERO) > 0
                    ? price.subtract(p.avgCost()).divide(p.avgCost(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            totalValue = totalValue.add(value);
            totalPnl = totalPnl.add(pnl);

            sb.append("| ").append(p.symbol())
                    .append(" | ").append(p.name())
                    .append(" | ").append(p.quantity())
                    .append(" | ").append(p.avgCost().stripTrailingZeros().toPlainString())
                    .append(" | ").append(price.stripTrailingZeros().toPlainString());
            if (md != null) {
                sb.append(" (").append(formatPct(md.changePercent())).append(")");
            }
            sb.append(" | ").append(value.setScale(2).toPlainString())
                    .append(" | ").append(pnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                    .append(pnl.setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append(" | ").append(formatPct(pnlPct))
                    .append(" |\n");
        }

        sb.append("\n**汇总：** 总市值=").append(totalValue.setScale(2).toPlainString())
                .append("，浮动盈亏=").append(totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                .append(totalPnl.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append("，现金余额=").append(positionRepository.cashBalance().setScale(2).toPlainString())
                .append("\n");
    }

    private String formatPct(BigDecimal pct) {
        if (pct.compareTo(BigDecimal.ZERO) >= 0) {
            return "+" + pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        }
        return pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
