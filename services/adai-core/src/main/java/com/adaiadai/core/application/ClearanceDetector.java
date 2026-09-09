package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.PendingClearance;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.SoldTradeVerdict;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ClearanceDetector — 清仓股流水自动收录检测（RFC 20260909 批 1 双轨：flow 自动收录 / pending 提示）。
 * <p>
 * 判定输入：给定 userId 与候选 symbol 集合（三处操作触发点传入：历史成交导入 / recordTrade 卖光 /
 * POST /trading/sync 一键重建）。对每个 symbol 判定：
 * <ul>
 *   <li><b>已清仓</b> = 持仓（positions）不含它 &amp;&amp; 流水有 SELL（volume&gt;0）</li>
 *   <li><b>完整可推导（条件 A）</b>：流水内该 symbol BUY 总量 ≥ SELL 总量（能解释全部卖出来源）
 *       → 推导 SoldTrade（buyDate=最早 BUY 日 / sellDate=最晚 SELL 日 / holdDays / 总笔数 /
 *       holdPnlPct=回合口径 / verdict 规则判 / psychology 空 / <b>provenance=flow</b>）→
 *       {@code upsertFromFlow} 收录。用户拍板 P1：<b>只填空白 symbol</b>——sold.json 已存在（无论
 *       provenance）绝不覆盖。</li>
 *   <li><b>缺基线（条件 B）</b>：BUY 总量 &lt; SELL 总量（买入基线在流水窗口外）→ 不落脏档案，
 *       进 pendingClearances 提示（引导导入清仓股导出补全档案）。</li>
 * </ul>
 * 并发：本服务不加业务锁，写 sold.json 走 {@link SoldTradeRepository#upsertFromFlow}（仓储内部
 * per-user 条带锁读-改-写原子，与现有 sold 写路径同锁），与 TradingAppService 外层 tradeLock 不嵌套。
 */
@Service
public class ClearanceDetector {

    private static final Logger log = LoggerFactory.getLogger(ClearanceDetector.class);

    /** 缺买入基线的人话提示（条件 B：为什么缺 + 怎么补）。 */
    public static final String PENDING_REASON = "已清仓但流水缺买入基线——导入清仓股导出补全档案";

    private final TradingHistoryRepository tradingHistoryRepository;
    private final PositionRepository positionRepository;
    private final SoldTradeRepository soldTradeRepository;
    private final TradingRuleSettingsRepository tradingRuleSettingsRepository;

    public ClearanceDetector(TradingHistoryRepository tradingHistoryRepository,
                             PositionRepository positionRepository,
                             SoldTradeRepository soldTradeRepository,
                             TradingRuleSettingsRepository tradingRuleSettingsRepository) {
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.positionRepository = positionRepository;
        this.soldTradeRepository = soldTradeRepository;
        this.tradingRuleSettingsRepository = tradingRuleSettingsRepository;
    }

    /** 一次推导结果：added=实际收录的 flow 行；pending=缺基线的待补提示。 */
    public record ClearanceOutcome(List<SoldTrade> added, List<PendingClearance> pending) {}

    /**
     * 对候选 symbol 集合做清仓推导（触发点统一入口，best-effort 由调用方包）。
     *
     * @param symbols 候选 symbol 集合（通常=触发操作涉及的 symbol）
     * @return added 为已 upsertFromFlow 收录的行；pending 为缺基线提示
     */
    public ClearanceOutcome sync(String userId, Collection<String> symbols) {
        List<SoldTrade> added = new ArrayList<>();
        List<PendingClearance> pending = new ArrayList<>();
        if (symbols == null || symbols.isEmpty()) return new ClearanceOutcome(added, pending);
        List<TradeRecord> flow = tradingHistoryRepository.findAll(userId);
        Set<String> held = positionRepository.findAll(userId).stream()
                .map(Position::symbol).collect(java.util.stream.Collectors.toSet());
        Set<String> recorded = soldTradeRepository.findAll(userId).stream()
                .map(SoldTrade::symbol).collect(java.util.stream.Collectors.toSet());
        Set<String> seen = new LinkedHashSet<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank() || !seen.add(symbol)) continue;
            if (held.contains(symbol)) continue;     // 仍持有 → 未清仓，不推导
            if (recorded.contains(symbol)) continue; // P1：已存在（import/flow/人工行）→ 绝不覆盖、不提示
            SymbolStats st = collect(flow, symbol);
            if (st.sellVolume <= 0) continue;        // 流水无 SELL → 无从证明清仓
            if (st.buyVolume >= st.sellVolume) {
                SoldTrade derived = derive(userId, symbol, st);
                added.add(derived);
                // 仓储锁内幂等：只填空白 symbol（防御并发竞态，重复 sync 不翻倍）
                soldTradeRepository.upsertFromFlow(userId, derived);
            } else {
                // 买入基线在流水窗口外 → 不落库，只提示
                pending.add(new PendingClearance(symbol, displayName(symbol, st), st.latestSellDate, PENDING_REASON));
            }
        }
        if (!added.isEmpty() || !pending.isEmpty()) {
            log.info("清仓推导 | userId={} | 自动收录 {} 笔 flow | 缺基线提示 {} 笔",
                    userId, added.size(), pending.size());
        }
        return new ClearanceOutcome(added, pending);
    }

    /**
     * 全量扫描待补清仓档案（GET /trading/sold → pendingClearances）：
     * 流水里所有已清仓（持仓无）且 sold.json 无、且 BUY 总量 &lt; SELL 总量（基线外）的 symbol。
     */
    public List<PendingClearance> detectPending(String userId) {
        List<TradeRecord> flow = tradingHistoryRepository.findAll(userId);
        Set<String> held = positionRepository.findAll(userId).stream()
                .map(Position::symbol).collect(java.util.stream.Collectors.toSet());
        Set<String> recorded = soldTradeRepository.findAll(userId).stream()
                .map(SoldTrade::symbol).collect(java.util.stream.Collectors.toSet());
        // 按 symbol 聚合流水（仅 volume>0 的成交笔；股息类资金事件 volume=0 不计）
        Map<String, SymbolStats> bySymbol = new LinkedHashMap<>();
        for (TradeRecord tr : flow) {
            if (tr.volume() <= 0) continue;
            SymbolStats s = bySymbol.computeIfAbsent(tr.symbol(), SymbolStats::new);
            s.add(tr);
        }
        List<PendingClearance> out = new ArrayList<>();
        for (Map.Entry<String, SymbolStats> e : bySymbol.entrySet()) {
            SymbolStats st = e.getValue();
            if (st.sellVolume <= 0) continue;              // 无卖出 → 不算清仓候选
            if (held.contains(e.getKey())) continue;       // 仍持有
            if (recorded.contains(e.getKey())) continue;   // 已有档案（无论来源）
            if (st.buyVolume < st.sellVolume) {
                out.add(new PendingClearance(e.getKey(), displayName(e.getKey(), st),
                        st.latestSellDate, PENDING_REASON));
            }
        }
        return out;
    }

    // ── 内部推导 ──

    /** 聚合某 symbol 流水的判定量（条件 A/B 共用）。 */
    private SymbolStats collect(List<TradeRecord> flow, String symbol) {
        SymbolStats st = new SymbolStats(symbol);
        for (TradeRecord tr : flow) {
            if (tr.volume() <= 0) continue;
            if (!symbol.equals(tr.symbol())) continue;
            st.add(tr);
        }
        return st;
    }

    /** 推导完整 SoldTrade（条件 A：BUY 总量 ≥ SELL 总量，流水回合口径，provenance=flow）。 */
    private SoldTrade derive(String userId, String symbol, SymbolStats st) {
        TradingRuleSettings rules = tradingRuleSettingsRepository.findByUser(userId);
        LocalDate buyDate = st.earliestBuyDate;
        LocalDate sellDate = st.latestSellDate;
        int holdDays = 1;
        if (buyDate != null && sellDate != null) {
            long days = ChronoUnit.DAYS.between(buyDate, sellDate) + 1;
            holdDays = (int) Math.max(1, days);
        }
        // 回合盈亏率：((ΣSELL amount − ΣSELL fee) − (ΣBUY amount + ΣBUY fee)) / (ΣBUY amount + ΣBUY fee) × 100
        double holdPnlPct = 0;
        if (st.buyCost.signum() != 0) {
            holdPnlPct = st.sellProceeds.subtract(st.buyCost)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(st.buyCost, 2, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        String verdict = SoldTradeVerdict.compute(holdPnlPct, holdDays,
                rules.soldStopLossPct(), rules.soldShortHoldDays(), "R66", "R53");
        return new SoldTrade(symbol, displayName(symbol, st), buyDate, sellDate, holdDays,
                String.valueOf(st.count), holdPnlPct, verdict, "", "flow");
    }

    private String displayName(String symbol, SymbolStats st) {
        return st.name != null && !st.name.isBlank() ? st.name : symbol;
    }

    /** 单 symbol 流水聚合（可变计数器）。 */
    private static final class SymbolStats {
        final String symbol;
        long buyVolume;
        long sellVolume;
        /** Σ(BUY amount + fee) —— 买入成本（含费）。 */
        BigDecimal buyCost = BigDecimal.ZERO;
        /** Σ(SELL amount − fee) —— 卖出净得（扣费）。 */
        BigDecimal sellProceeds = BigDecimal.ZERO;
        LocalDate earliestBuyDate;
        LocalDate latestSellDate;
        /** 总成交笔数（BUY+SELL，volume>0）。 */
        long count;
        String name = "";

        SymbolStats(String symbol) {
            this.symbol = symbol;
        }

        void add(TradeRecord t) {
            BigDecimal amount = t.amount() != null ? t.amount() : BigDecimal.ZERO;
            BigDecimal fee = t.fee() != null ? t.fee() : BigDecimal.ZERO;
            if (t.direction() == TradeDirection.BUY) {
                buyVolume += t.volume();
                buyCost = buyCost.add(amount).add(fee);
                if (t.entryDate() != null && (earliestBuyDate == null || t.entryDate().isBefore(earliestBuyDate))) {
                    earliestBuyDate = t.entryDate();
                }
            } else {
                sellVolume += t.volume();
                sellProceeds = sellProceeds.add(amount).subtract(fee);
                if (t.entryDate() != null && (latestSellDate == null || t.entryDate().isAfter(latestSellDate))) {
                    latestSellDate = t.entryDate();
                }
            }
            count++;
            if (t.name() != null && !t.name().isBlank()) name = t.name();
        }
    }
}
