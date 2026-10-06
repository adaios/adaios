package com.adaiadai.core.domain.trading;

import com.adaiadai.core.kernel.context.engine.ContextContributor;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
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
 *
 * <p><b>2026-10-06 §8.2 白名单①（design-final）</b>：注入只出<b>结构 + 比例 + 现价</b>——
 * 标的（代码 · 名称）· 是否持有 · 持有个数 · 盈亏% · 仓位占比 · 现价；
 * <b>规模一律不出</b>（股数 · 成本价 · 市值 · 浮动盈亏金额 · 现金余额 · 总资产）。
 * 标题「## 持仓结构（不含规模）」。原「## 交易系统状态」标题 + 成本价 / 总市值 /
 * 浮动盈亏 / 现金余额汇总行属「规模」泄露，已按白名单移除（旧行为见 git 历史）。
 */
@Component
public class TradingContextContributor implements ContextContributor {

    private static final Logger log = LoggerFactory.getLogger(TradingContextContributor.class);

    private final PositionRepository positionRepository;
    private final MarketDataSource marketDataSource;
    /** 2026-10-06 §8.2 白名单①：现金余额属「规模」不再注入文本；
     *  依赖保留（构造调用方兼容），无读取动作。 */
    private final AccountSnapshotRepository accountSnapshotRepository;

    public TradingContextContributor(PositionRepository positionRepository,
                                     MarketDataSource marketDataSource,
                                     AccountSnapshotRepository accountSnapshotRepository) {
        this.positionRepository = positionRepository;
        this.marketDataSource = marketDataSource;
        this.accountSnapshotRepository = accountSnapshotRepository;
    }

    @Override
    public String globalContext(String userId) {
        List<Position> positions = positionRepository.findAll(userId);
        if (positions.isEmpty()) {
            return "";
        }

        List<String> codes = positions.stream().map(Position::symbol).toList();
        Map<String, MarketData> quotes = marketDataSource.quote(codes);

        // §8.2 白名单①：仓位占比是「比例」可出——占比需要的总市值只用于算比例，不进文本
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            BigDecimal realPrice = md != null ? md.price() : p.currentPrice();
            totalValue = totalValue.add(realPrice.multiply(BigDecimal.valueOf(p.quantity())));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 持仓结构（不含规模）\n\n");
        sb.append("当前持有 ").append(positions.size()).append(" 个仓位：\n");

        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            BigDecimal realPrice = md != null ? md.price() : p.currentPrice();
            BigDecimal value = realPrice.multiply(BigDecimal.valueOf(p.quantity()));
            // 负/零成本 → 百分比无意义（分母不是正的成本），写字面「—」而不是 0.00%：
            // 0% 会被模型读成「没涨没跌」，与「成本已为负」是两回事（2026-09-13 负成本批）
            BigDecimal pnlPct = p.avgCost().compareTo(BigDecimal.ZERO) > 0
                    ? realPrice.subtract(p.avgCost()).divide(p.avgCost(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : null;
            BigDecimal sharePct = totalValue.signum() > 0
                    ? value.multiply(BigDecimal.valueOf(100)).divide(totalValue, 1, RoundingMode.HALF_UP)
                    : null;

            sb.append("- ").append(p.name()).append("(").append(p.symbol()).append(")")
                    .append(" 现价").append(realPrice.stripTrailingZeros().toPlainString())
                    .append(" ").append(pnlPct == null ? "—（成本非正，百分比不适用）"
                            : (pnlPct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                              + pnlPct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%");
            if (sharePct != null) {
                sb.append("，仓位占比 ").append(sharePct.toPlainString()).append("%");
            }
            sb.append("\n");
        }

        return sb.toString().strip();
    }
}
