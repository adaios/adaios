package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.infrastructure.storage.TradeLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * TradeLogCollectService — 当日交易日志自动归集（RFC 20260817 交易日志自动归集）。
 * <p>
 * 用户发成交截图或说「清仓了XX」→ 识别为当日成交 → 归集为候选（**未落库**，待用户确认）。
 * 三步流水线：
 * <ol>
 *   <li>判定（仅 trading 插件用户触发，由调用方门控）：截图/文字 → {@link TradingParseAppService#parse} 结构化</li>
 *   <li>归集去重：同 (symbol, direction, 当日) 只记一笔，存 TradeLogRepository</li>
 *   <li>收盘确认：15:05 定时任务推送「今日操作汇总，是否完整」→ 用户确认后走 recordTrade 落库</li>
 * </ol>
 * 阿呆只归集不落库——确认后才写交易模块（建议引擎哲学：不替用户做决定）。
 */
@Service
public class TradeLogCollectService {

    private static final Logger log = LoggerFactory.getLogger(TradeLogCollectService.class);

    private final TradingParseAppService parseAppService;
    private final TradeLogRepository tradeLogRepository;
    private final TradingAppService tradingAppService;

    public TradeLogCollectService(TradingParseAppService parseAppService,
                                  TradeLogRepository tradeLogRepository,
                                  TradingAppService tradingAppService) {
        this.parseAppService = parseAppService;
        this.tradeLogRepository = tradeLogRepository;
        this.tradingAppService = tradingAppService;
    }

    /** 归集一笔：宽松解析文本 → 当日候选去重入库。返回该用户当日候选全量。 */
    public List<TradeLogCandidate> collect(String userId, String text, String source) {
        if (text == null || text.isBlank()) return todayCandidates(userId);
        // 宽松解析（RFC 20260817）：「清仓了XX」无数量价格也归集为待补充候选（complete=false）
        TradingParseAppService.ParseResult r = parseAppService.parseLoose(userId, text);
        if (!r.matched() || r.symbol() == null && (r.name() == null || r.name().isBlank())) {
            return todayCandidates(userId);
        }
        if (r.direction() == null) return todayCandidates(userId);

        TradeLogCandidate candidate = new TradeLogCandidate(
                r.symbol() != null ? r.symbol() : "unknown",
                r.name(),
                r.direction(),
                r.price(),
                r.volume(),
                source,
                r.price() != null && r.volume() != null
        );
        List<TradeLogCandidate> updated = tradeLogRepository.append(userId, LocalDate.now(), candidate);
        log.info("交易日志归集 | userId={} | {} {} {} | 当日候选 {} 笔",
                userId, r.direction(), candidate.symbol(), r.volume() != null ? r.volume() + "股" : "（数量未知）",
                updated.size());
        return updated;
    }

    /** 当日候选（未确认）。 */
    public List<TradeLogCandidate> todayCandidates(String userId) {
        return tradeLogRepository.findByDate(userId, LocalDate.now());
    }

    /** 用户确认：当日**完整**候选逐笔走 recordTrade 落库，然后清空候选。
     *  不完整候选（complete=false，缺数量/价格）不落库——返回跳过数，前端引导去交易模块补全。 */
    public int confirm(String userId) {
        List<TradeLogCandidate> candidates = todayCandidates(userId);
        int done = 0;
        int skipped = 0;
        for (TradeLogCandidate c : candidates) {
            if (!c.complete()) {
                // RFC 20260817：数量/价格缺失的候选确认时跳过（recordTrade 0 数量会误伤/静默）
                skipped++;
                log.info("交易日志确认跳过（不完整）| userId={} | {} {} | 请去交易模块补全",
                        userId, c.direction(), c.symbol());
                continue;
            }
            try {
                tradingAppService.recordTrade(
                        userId,
                        c.symbol(),
                        c.name(),
                        "SELL".equals(c.direction()) ? TradeDirection.SELL : TradeDirection.BUY,
                        c.price() != null ? c.price() : BigDecimal.ZERO,
                        c.volume() != null ? c.volume() : 0,
                        LocalDate.now(),
                        null, null, null, null);
                done++;
            } catch (Exception e) {
                log.warn("交易日志确认落库失败（跳过）| userId={} | {} {} | {}", userId, c.direction(), c.symbol(), e.getMessage());
            }
        }
        tradeLogRepository.save(userId, LocalDate.now(), List.of());
        log.info("交易日志确认落库 | userId={} | 成功 {} / 跳过(不完整) {} / 共 {} 笔", userId, done, skipped, candidates.size());
        return done;
    }

    /** 收盘确认文案：当日候选汇总（供 15:05 推送 / 前端展示）。 */
    public String summarize(List<TradeLogCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("📋 今日操作汇总\n");
        for (TradeLogCandidate c : candidates) {
            sb.append("· ").append(c.name() != null && !c.name().isBlank() ? c.name() : c.symbol())
                    .append(" ").append("BUY".equals(c.direction()) ? "买入" : "卖出");
            if (c.volume() != null) sb.append(" ").append(c.volume()).append(" 股");
            if (c.price() != null) sb.append(" @").append(c.price());
            if (!c.complete()) sb.append("（数量/价格待补充）");
            sb.append("\n");
        }
        sb.append("是否完整？不完整说一声，我引导你去交易模块补全。");
        return sb.toString();
    }
}
