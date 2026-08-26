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
        // 2026-08-26：截图归集缺口修复——表格文字（多笔）优先走批量解析，
        // 命中多笔（或表格形态）则逐笔归集；否则回退单笔宽松解析（一句话场景不变）。
        List<TradingParseAppService.ParseResult> batch = parseAppService.parseLooseBatch(userId, text);
        if (!batch.isEmpty()) {
            return collectBatch(userId, batch, source);
        }
        return collectSingle(userId, text, source);
    }

    /** 单笔归集（一句话文字，RFC 20260817 原语义）。 */
    private List<TradeLogCandidate> collectSingle(String userId, String text, String source) {
        // 宽松解析（RFC 20260817）：「清仓了XX」无数量价格也归集为待补充候选（complete=false）
        TradingParseAppService.ParseResult r = parseAppService.parseLoose(userId, text);
        if (!r.matched() || r.direction() == null) return todayCandidates(userId);
        // P1-1（2026-08-18 生产）：symbol 与 name 全无（LLM 幻觉/think 泄漏文本）→ 拒绝归集，
        // 不得落 "unknown" 占位（确认必失败 + 污染去重键 + 推送显示 unknown）。
        boolean hasSymbol = r.symbol() != null && !r.symbol().isBlank();
        boolean hasName = r.name() != null && !r.name().isBlank();
        if (!hasSymbol && !hasName) {
            log.info("交易日志归集跳过（未识别股票）| userId={} | 文本: {}", userId, text);
            return todayCandidates(userId);
        }

        TradeLogCandidate candidate = new TradeLogCandidate(
                r.symbol(),
                r.name(),
                r.direction(),
                r.price(),
                r.volume(),
                source,
                // complete = symbol + direction + price + volume 全有（TradeLogCandidate javadoc；
                // P1-1：原实现漏了 symbol 检查 → 无代码候选误判 complete=true 落库失败）
                hasSymbol && r.price() != null && r.volume() != null
        );
        List<TradeLogCandidate> updated = tradeLogRepository.append(userId, LocalDate.now(), candidate);
        log.info("交易日志归集 | userId={} | {} {} {} | 当日候选 {} 笔",
                userId, r.direction(), candidate.symbol() != null ? candidate.symbol() : candidate.name(),
                r.volume() != null ? r.volume() + "股" : "（数量未知）",
                updated.size());
        return updated;
    }

    /**
     * 批量归集（2026-08-26，截图表格归集）：逐笔 append 到当日候选，
     * 去重复用 {@link TradeLogRepository#append} 的 sameTrade 语义（同 symbol+方向+volume±10% 同笔）。
     *
     * @return 该用户当日候选全量
     */
    public List<TradeLogCandidate> collectBatch(String userId, List<TradingParseAppService.ParseResult> parsed,
                                                String source) {
        List<TradeLogCandidate> updated = todayCandidates(userId);
        int collected = 0;
        for (TradingParseAppService.ParseResult r : parsed) {
            if (r == null || !r.matched() || r.direction() == null) continue;
            boolean hasSymbol = r.symbol() != null && !r.symbol().isBlank();
            boolean hasName = r.name() != null && !r.name().isBlank();
            if (!hasSymbol && !hasName) continue; // 同单笔：无 symbol/name 拒绝占位
            TradeLogCandidate candidate = new TradeLogCandidate(
                    r.symbol(), r.name(), r.direction(), r.price(), r.volume(), source,
                    hasSymbol && r.price() != null && r.volume() != null);
            updated = tradeLogRepository.append(userId, LocalDate.now(), candidate);
            collected++;
        }
        if (collected > 0) {
            log.info("交易日志批量归集 | userId={} | 解析 {} 笔 → 归集 {} 笔 | 当日候选 {} 笔",
                    userId, parsed.size(), collected, updated.size());
        }
        return updated;
    }

    /**
     * 文本是否命中交易表述（宽松解析成功且识别出方向）。
     * 2026-08-20：R2 记录转任务前先判——「清仓了XX」等成交表述归交易归集管线跟踪，
     * 不再转成 TODO 任务（生产「云南锗业清仓止盈」等 5 条脏任务根因，概览残留清仓股名）。
     */
    public boolean isTradeStatement(String text) {
        if (text == null || text.isBlank()) return false;
        TradingParseAppService.ParseResult r = parseAppService.parseLoose("default", text);
        if (!r.matched() || r.direction() == null) return false;
        boolean hasSymbol = r.symbol() != null && !r.symbol().isBlank();
        boolean hasName = r.name() != null && !r.name().isBlank();
        return hasSymbol || hasName;
    }

    /** 当日候选（未确认）。 */
    public List<TradeLogCandidate> todayCandidates(String userId) {
        return tradeLogRepository.findByDate(userId, LocalDate.now());
    }

    /**
     * 丢弃一条当日候选（B6-5，2026-08-23，P1-交易18）：
     * 失败/不完整候选保留后可能成为「钉子户」——15:05 推送反复提醒同一笔；
     * 前端提供丢弃入口（标 symbol+direction），用户确认放弃该笔归集。
     * @return true=已移除；false=当日无此候选
     */
    public boolean discard(String userId, String symbol, String direction) {
        LocalDate today = LocalDate.now();
        return tradeLogRepository.discard(userId, today, symbol, direction);
    }

    /** 用户确认：当日**完整**候选逐笔走 recordTrade 落库。
     *  P0-1（2026-08-23 修复）：落库失败的候选（SELL 超持仓/未持有等）与不完整候选
     *  **回写保留**（不无条件清空），确认过的交易不静默丢失——用户可补全/修正后再次确认。
     *  @return 确认结果（成功/失败/跳过笔数 + 失败人话明细） */
    public ConfirmResult confirm(String userId) {
        // B6-5（2026-08-23，P1-交易14）：单次取 now 贯穿——原 todayCandidates/save/recordTrade
        // 三处 LocalDate.now() 跨午夜时候选昨日残留 + 今日副本（复发信号：now() 推导路径）
        LocalDate today = LocalDate.now();
        List<TradeLogCandidate> candidates = todayCandidates(userId);
        if (candidates.isEmpty()) {
            return new ConfirmResult(0, 0, 0, List.of());
        }
        int done = 0;
        int skipped = 0;
        List<TradeLogCandidate> remaining = new java.util.ArrayList<>();
        List<String> failures = new java.util.ArrayList<>();
        for (TradeLogCandidate c : candidates) {
            if (!c.complete()) {
                // RFC 20260817：数量/价格缺失的候选确认时跳过（recordTrade 0 数量会误伤/静默）；
                // P0-1：保留候选，前端引导补全后再确认
                skipped++;
                remaining.add(c);
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
                        today,
                        java.time.LocalTime.now(), // RFC 20260822：日志确认落库带当下成交时刻
                        null, null, null, null);
                done++;
            } catch (Exception e) {
                // P0-1：失败候选保留（不丢），记录人话原因供前端展示
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                String label = c.name() != null && !c.name().isBlank() ? c.name() : c.symbol();
                failures.add(label + ": " + msg);
                remaining.add(c);
                log.warn("交易日志确认落库失败（保留候选）| userId={} | {} {} | {}", userId, c.direction(), c.symbol(), msg);
            }
        }
        // C1（2026-08-23，隔离审查 P2-2）：confirm 读取→处理→save 在 repository 锁外——
        // 处理期间新归集（collect append）的候选若直接 save(remaining) 会被覆盖清掉。
        // 修：save 前重新读当日全部候选，把「不在本次处理范围」的新候选并入保留集。
        List<TradeLogCandidate> latest = tradeLogRepository.findByDate(userId, today);
        for (TradeLogCandidate n : latest) {
            boolean handled = candidates.stream().anyMatch(c -> c.sameTrade(n));
            boolean alreadyKept = remaining.stream().anyMatch(c -> c.sameTrade(n));
            if (!handled && !alreadyKept) remaining.add(n);
        }
        tradeLogRepository.save(userId, today, remaining);
        log.info("交易日志确认落库 | userId={} | 成功 {} / 失败 {} / 跳过(不完整) {} / 共 {} 笔 | 保留 {} 笔",
                userId, done, failures.size(), skipped, candidates.size(), remaining.size());
        return new ConfirmResult(done, failures.size(), skipped, failures);
    }

    /** 确认结果：成功/失败/跳过笔数 + 失败人话明细（P0-1：失败候选已保留，可再次确认）。 */
    public record ConfirmResult(int confirmed, int failed, int skipped, List<String> failures) {}

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
