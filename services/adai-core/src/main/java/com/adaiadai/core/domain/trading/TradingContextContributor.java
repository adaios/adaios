package com.adaiadai.core.domain.trading;

import com.adaiadai.core.kernel.context.engine.ContextContributor;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * TradingContextContributor — 交易场景的上下文贡献者。
 * <p>
 * 为 trading 场景注入交易系统状态摘要。
 * 实时行情由 {@link MarketContextContributor} 提供，本类专注交易系统状态。
 * <p>
 * 实现 {@link ContextContributor} 接口，被 ContextEngine 自动发现。
 */
@Component
public class TradingContextContributor implements ContextContributor {

    private static final Logger log = LoggerFactory.getLogger(TradingContextContributor.class);

    private final PositionRepository positionRepository;
    private final MarketDataSource marketDataSource;
    /** P2-交易35 治本（2026-09-09）：现金唯一真源 = account.json（S5），不再读 positions.md cashBalance 展示行。 */
    private final AccountSnapshotRepository accountSnapshotRepository;

    public TradingContextContributor(PositionRepository positionRepository,
                                     MarketDataSource marketDataSource,
                                     AccountSnapshotRepository accountSnapshotRepository) {
        this.positionRepository = positionRepository;
        this.marketDataSource = marketDataSource;
        this.accountSnapshotRepository = accountSnapshotRepository;
    }

    @Override
    public boolean supports(String scene) {
        // 行情相关由 MarketContextContributor 处理，本类不重复输出
        return false;
    }

    @Override
    public String enrich(String userId, String identityRef, ContentRecord record) {
        return "";
    }

    @Override
    public String globalContext(String userId) {
        List<Position> positions = positionRepository.findAll(userId);
        if (positions.isEmpty()) {
            return "";
        }

        List<String> codes = positions.stream().map(Position::symbol).toList();
        Map<String, MarketData> quotes = marketDataSource.quote(codes);

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;

        StringBuilder sb = new StringBuilder();
        sb.append("## 交易系统状态\n\n");
        sb.append("当前持有 ").append(positions.size()).append(" 个仓位：\n");

        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            BigDecimal realPrice = md != null ? md.price() : p.currentPrice();
            BigDecimal value = realPrice.multiply(BigDecimal.valueOf(p.quantity()));
            BigDecimal cost = p.avgCost().multiply(BigDecimal.valueOf(p.quantity()));
            BigDecimal pnl = value.subtract(cost);
            // 负/零成本 → 百分比无意义（分母不是正的成本），写字面「—」而不是 0.00%：
            // 0% 会被模型读成「没涨没跌」，与「成本已为负」是两回事（2026-09-13 负成本批）
            BigDecimal pnlPct = p.avgCost().compareTo(BigDecimal.ZERO) > 0
                    ? realPrice.subtract(p.avgCost()).divide(p.avgCost(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : null;

            totalValue = totalValue.add(value);
            totalPnl = totalPnl.add(pnl);

            sb.append("- ").append(p.name()).append("(").append(p.symbol()).append(")")
                    .append(" 现价").append(realPrice.stripTrailingZeros().toPlainString())
                    .append(" 成本").append(p.avgCost().stripTrailingZeros().toPlainString())
                    .append(" ").append(pnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                    .append(pnlPct == null ? "—" : pnlPct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%")
                    .append(pnlPct == null ? "（成本非正，百分比不适用）" : "")
                    .append("\n");
        }

        BigDecimal cash = accountSnapshotRepository.findLatest(userId)
                .map(AccountSnapshot::cash).orElse(BigDecimal.ZERO);
        sb.append("\n总市值 ").append(totalValue.setScale(2).toPlainString())
                .append("，浮动盈亏 ").append(totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                .append(totalPnl.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append("，现金余额 ").append(cash.setScale(2).toPlainString());

        return sb.toString();
    }
}
