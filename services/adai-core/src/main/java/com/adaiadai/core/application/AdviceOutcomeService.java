package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AdviceEntry;
import com.adaiadai.core.domain.trading.AdviceHistoryRepository;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * AdviceOutcomeService — 建议结果回填（RFC `20260922-trading-decision-copilot` A 批 A3，2026-09-22）。
 *
 * <p>用户要的「铁证④ 可回溯、可追责」落在两半上：**依据快照**（建议当时是什么情况，已在
 * {@code AdviceEntry.basis}）与**结果回填**（这条建议后来怎么样了，就是本类）。
 *
 * <p><b>只记录事实，不判对错</b>：
 * <ul>
 *   <li>{@code priceThen} / {@code priceAfter} / {@code pct} —— 建议日与 **N 个交易日后**的收盘与涨跌幅
 *       （客观、可核对；「这算不算对」是用户复盘时的事，本类不下结论）</li>
 *   <li>{@code userActed} —— 建议发出之后用户对这只票**有没有动作**（{@code traded} / {@code none} / {@code unknown}）；
 *       注意它记录的是「有没有操作」，**不是「有没有听建议」**（听没听需要把动作与建议对齐，属后续口径）</li>
 * </ul>
 *
 * <p><b>宁可留空，不写半成品</b>：K 线取不到、或还没走满 N 个交易日 → 返回 null，**不写 outcome**
 * （下次再试）——半截的「结果」比没有更坏。回填本身**幂等**（已有 outcome 不再覆盖）。
 *
 * <p>本类只读行情 + 写自己那条留痕的 {@code outcome} 字段，不改建议、不动账目。
 */
@Service
public class AdviceOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(AdviceOutcomeService.class);

    /** 回看窗口（交易日）：默认 5——用户 2026-09-22 未反对的建议值，可配置调整。 */
    private final int afterDays;

    private final AdviceHistoryRepository adviceHistoryRepository;
    private final TradingHistoryRepository tradingHistoryRepository;
    private final KlineService klineService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdviceOutcomeService(AdviceHistoryRepository adviceHistoryRepository,
                                TradingHistoryRepository tradingHistoryRepository,
                                KlineService klineService,
                                @Value("${adai.trading.advice-outcome-days:5}") int afterDays) {
        this.adviceHistoryRepository = adviceHistoryRepository;
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.klineService = klineService;
        this.afterDays = afterDays <= 0 ? 5 : afterDays;
    }

    /**
     * 回填某用户「已到期」的建议结果（扫近 3 个月留痕；已有 outcome 的跳过）。
     *
     * @param today 基准日（调用方注入——存储/服务层不自己取 now()，便于测试与回放）
     * @return 本次真正写入的条数
     */
    public int backfill(String userId, LocalDate today) {
        if (today == null || userId == null) return 0;
        int written = 0;
        for (int i = 0; i < 3; i++) {
            LocalDate month = today.minusMonths(i);
            for (AdviceEntry e : adviceHistoryRepository.findByMonth(userId, month)) {
                if (e.outcome() != null && !e.outcome().isBlank()) continue;      // 已回填（幂等）
                if (e.date() == null || e.id() == null || e.symbol() == null || e.symbol().isBlank()) continue;
                // 粗筛：自然日不够 2×N 天，必然还没走满 N 个交易日（省掉无谓的行情请求）
                if (e.date().isAfter(today.minusDays((long) afterDays * 2))) continue;
                String outcome = buildOutcome(userId, e, today);
                if (outcome == null) continue;                                    // 数据不全 → 下次再试
                if (adviceHistoryRepository.updateOutcome(userId, e.date(), e.id(), outcome)) written++;
            }
        }
        return written;
    }

    /**
     * 组装这一条的结果 JSON；数据不全（K 线取不到 / 还没走满 N 个交易日）→ null。
     *
     * <p>行情窗口末端取 {@code min(today, 建议日 + 2N+7 天)}——**不向行情源要未来日期**。
     */
    private String buildOutcome(String userId, AdviceEntry e, LocalDate today) {
        try {
            LocalDate to = e.date().plusDays((long) afterDays * 2 + 7);
            if (to.isAfter(today)) to = today;
            List<Candle> candles = klineService.klineRange(e.symbol(), e.date(), to);
            if (candles == null || candles.isEmpty()) return null;

            int idx = -1;
            for (int i = 0; i < candles.size(); i++) {
                LocalDate d = candles.get(i).date();
                if (d != null && !d.isBefore(e.date())) { idx = i; break; }
            }
            if (idx < 0) return null;                       // 窗口里没有建议日及之后的 K 线
            if (idx + afterDays >= candles.size()) return null;  // 还没走满 N 个交易日 → 等下次

            Candle then = candles.get(idx);
            Candle after = candles.get(idx + afterDays);
            if (then.close() == 0) return null;             // 分母为 0：宁可不给，不给一个假的百分比

            double pct = (after.close() - then.close()) / then.close() * 100;
            var node = objectMapper.createObjectNode();
            node.put("afterDays", afterDays);
            node.put("priceThen", then.close());
            node.put("priceAfter", after.close());
            node.put("pct", Math.round(pct * 100) / 100.0);
            node.put("userActed", detectUserAction(userId, e));
            return objectMapper.writeValueAsString(node);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("建议结果回填失败（该条留待下次，不写半成品）| userId={} | symbol={} | {}",
                    userId, e.symbol(), ex.getMessage());
            return null;
        }
    }

    /**
     * 建议发出之后，用户对这只票**有没有动作**（查流水）。
     *
     * <p>{@code traded} = 有成交 / {@code none} = 没有 / {@code unknown} = 流水读不到
     * （读失败**不谎报 none**——「不知道」和「没操作」是两件事）。
     */
    private String detectUserAction(String userId, AdviceEntry e) {
        try {
            for (TradeRecord t : tradingHistoryRepository.findAll(userId)) {
                if (!e.symbol().equals(t.symbol())) continue;
                if (t.entryDate() != null && t.entryDate().isAfter(e.date())) return "traded";
            }
            return "none";
        } catch (RuntimeException ex) {
            log.warn("建议结果回填：流水读取失败（userActed 记 unknown）| userId={} | {}", userId, ex.getMessage());
            return "unknown";
        }
    }
}
