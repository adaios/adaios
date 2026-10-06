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
import java.util.stream.Collectors;

/**
 * MarketContextContributor — 行情上下文贡献者。
 * <p>
 * 为 trading 场景注入实时大盘指数和持仓行情。
 * 持仓使用实时价格替换 {@code positions.md} 中的静态值。
 *
 * <p><b>2026-10-06 §8.2 白名单②（design-final）</b>：大盘（指数点位 · 涨跌幅，公共行情）可出；
 * 持仓表只出「代码 · 名称 · 现价 · 盈亏%」；<b>数量列 · 成本价列 · 市值列 · 盈亏金额列一律不出</b>，
 * 汇总行（总市值 / 浮动盈亏 / 现金余额）删除；标题「## 大盘与持仓行情」。
 */
@Component
public class MarketContextContributor implements ContextContributor {

    private static final Logger log = LoggerFactory.getLogger(MarketContextContributor.class);

    private final MarketDataSource marketDataSource;
    private final PositionRepository positionRepository;
    /** 2026-10-06 §8.2 白名单②：现金余额属「规模」不再注入文本；
     *  依赖保留（构造调用方兼容），无读取动作。 */
    private final AccountSnapshotRepository accountSnapshotRepository;

    public MarketContextContributor(MarketDataSource marketDataSource, PositionRepository positionRepository,
                                    AccountSnapshotRepository accountSnapshotRepository) {
        this.marketDataSource = marketDataSource;
        this.positionRepository = positionRepository;
        this.accountSnapshotRepository = accountSnapshotRepository;
        log.info("MarketContextContributor 已初始化");
    }

    @Override
    public boolean supports(String scene) {
        return "trading".equals(scene);
    }

    @Override
    public String enrich(String userId, String identityRef, ContentRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 大盘与持仓行情\n\n");

        // 1. 大盘指数
        appendIndices(sb);

        // 2. 持仓行情（实时价格；§8.2 白名单②：只出代码/名称/现价/盈亏%）
        appendPortfolio(userId, sb);

        return sb.toString();
    }

    @Override
    public String globalContext(String userId) {
        // 所有场景都注入交易系统状态（短版）：大盘指数始终注入，持仓按需
        StringBuilder sb = new StringBuilder();
        sb.append("## 大盘与持仓行情\n\n");

        List<Position> positions = positionRepository.findAll(userId);
        if (positions.isEmpty()) {
            sb.append("当前无持仓记录。\n");
        } else {
            sb.append("当前持有 ").append(positions.size()).append(" 个仓位。");

            List<String> codes = positions.stream().map(Position::symbol).toList();
            Map<String, MarketData> quotes = marketDataSource.quote(codes);

            for (Position p : positions) {
                MarketData md = quotes.get(p.symbol());
                BigDecimal price = md != null ? md.price() : p.currentPrice();
                BigDecimal pnl = price.subtract(p.avgCost())
                        .multiply(BigDecimal.valueOf(p.quantity()));
                // 负/零成本 → 百分比无意义，写字面「—」而不是 0.00%（2026-09-13 负成本批）
                BigDecimal pnlPct = p.avgCost().compareTo(BigDecimal.ZERO) > 0
                        ? price.subtract(p.avgCost()).divide(p.avgCost(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        : null;

                sb.append("\n- ").append(p.name()).append("(").append(p.symbol()).append(")")
                        .append(" 现价").append(price.stripTrailingZeros().toPlainString())
                        .append(" ")
                        .append(pnlPct == null ? "—（成本非正，百分比不适用）"
                                : (pnlPct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                                  + pnlPct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%");
            }
        }

        // 大盘概览（始终注入，不依赖持仓存在）
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

        String result = sb.toString().strip();
        return result.isBlank() ? "" : result;
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

    private void appendPortfolio(String userId, StringBuilder sb) {
        List<Position> positions = positionRepository.findAll(userId);
        if (positions.isEmpty()) {
            sb.append("**当前持仓：** 空仓\n");
            return;
        }

        // 批量查询实时价格
        List<String> codes = positions.stream().map(Position::symbol).toList();
        Map<String, MarketData> quotes = marketDataSource.quote(codes);

        sb.append("**当前持仓：**\n\n");
        // §8.2 白名单②：只出代码/名称/现价/盈亏%——数量列 · 成本价列 · 市值列 · 盈亏金额列不出
        sb.append("| 代码 | 名称 | 现价 | 盈亏% |\n");
        sb.append("|------|------|------|-------|\n");

        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            BigDecimal price = md != null ? md.price() : p.currentPrice();
            BigDecimal pnlPct = p.avgCost().compareTo(BigDecimal.ZERO) > 0
                    ? price.subtract(p.avgCost()).divide(p.avgCost(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : null;

            sb.append("| ").append(p.symbol())
                    .append(" | ").append(p.name())
                    .append(" | ").append(price.stripTrailingZeros().toPlainString());
            if (md != null) {
                sb.append(" (").append(formatPct(md.changePercent())).append(")");
            }
            sb.append(" | ").append(formatPct(pnlPct))
                    .append(" |\n");
        }
    }

    private String formatPct(BigDecimal pct) {
        // null = 无意义（负/零成本，2026-09-13 负成本批）→ 字面「—」，不冒充 0%
        if (pct == null) {
            return "—";
        }
        if (pct.compareTo(BigDecimal.ZERO) >= 0) {
            return "+" + pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        }
        return pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
