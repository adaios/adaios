package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.infrastructure.market.NameToSymbolResolver;
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
    /** 2026-08-27：截图 OCR 漏代码列时按名称补代码（东财 suggest），保证候选可确认入账。 */
    private final NameToSymbolResolver nameToSymbolResolver;

    public TradeLogCollectService(TradingParseAppService parseAppService,
                                  TradeLogRepository tradeLogRepository,
                                  TradingAppService tradingAppService,
                                  NameToSymbolResolver nameToSymbolResolver) {
        this.parseAppService = parseAppService;
        this.tradeLogRepository = tradeLogRepository;
        this.tradingAppService = tradingAppService;
        this.nameToSymbolResolver = nameToSymbolResolver;
    }

    /** 归集一笔：宽松解析文本 → 当日候选去重入库。返回该用户当日候选全量。 */
    public List<TradeLogCandidate> collect(String userId, String text, String source) {
        return collectDetailed(userId, text, source).candidates();
    }

    /**
     * 归集（带「被丢掉的表格行」）——2026-09-14 P2-交易44。
     * <p>
     * 截图/一句话归集原来只回候选列表，「识别出 2 笔」掩盖了同一张截图里被丢掉的行
     * （状态不符/申购/占位代码/价格数量没认出来）。此处把解析层的丢弃明细一并带出去，
     * 由调用方透出到响应（截图入账是核心工作流，丢行必须可见）。
     */
    public CollectResult collectDetailed(String userId, String text, String source) {
        if (text == null || text.isBlank()) {
            return new CollectResult(todayCandidates(userId), List.of());
        }
        // 2026-08-26：截图归集缺口修复——表格文字（多笔）优先走批量解析，
        // 命中多笔（或表格形态）则逐笔归集；否则回退单笔宽松解析（一句话场景不变）。
        TradingParseAppService.LooseBatchParse batch = parseAppService.parseLooseBatchDetailed(userId, text);
        if (!batch.trades().isEmpty()) {
            return new CollectResult(collectBatch(userId, batch.trades(), source), batch.dropped());
        }
        return new CollectResult(collectSingle(userId, text, source), batch.dropped());
    }

    /** 归集结果（2026-09-14 P2-交易44）：当日候选全量 + 被丢弃的表格行（原文 + 原因）。 */
    public record CollectResult(List<TradeLogCandidate> candidates,
                                List<TradingImportParser.UnparsedLine> dropped) {
        public CollectResult {
            if (dropped == null) dropped = List.of();
        }
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
                // 2026-08-27：文字归集无成交日期 → null（确认时按确认当天）
                null,
                source,
                // complete = symbol + direction + price + volume 全有（TradeLogCandidate javadoc；
                // P1-1：原实现漏了 symbol 检查 → 无代码候选误判 complete=true 落库失败）
                hasSymbol && r.price() != null && r.volume() != null,
                // P2-交易36 治本（2026-09-09）：本期文字归集不抽取 orderId/fee → null（确认后流水补填）
                null, null);
        List<TradeLogCandidate> updated = tradeLogRepository.append(userId, LocalDate.now(), candidate);
        log.info("交易日志归集 | userId={} | {} {} {} | 当日候选 {} 笔",
                userId, r.direction(), candidate.symbol() != null ? candidate.symbol() : candidate.name(),
                r.volume() != null ? r.volume() + "股" : "（数量未知）",
                updated.size());
        return updated;
    }

    /**
     * 批量归集（2026-08-26，截图表格归集）：整批一次落盘。
     *
     * <p>2026-09-18（P0-交易59）：原实现逐笔 {@link TradeLogRepository#append}——同一张截图里的
     * 多行会被**批内互判同笔**，同代码/同方向/同价/同量的分单被静默吞掉（生产实据：000831
     * 两笔各 200 股 @53.300 只剩一笔）。改为 {@link TradeLogRepository#appendBatch}：批内互不判重，
     * 只与批前已有候选去重（重传同一张图仍不翻倍）。
     *
     * @return 该用户当日候选全量
     */
    public List<TradeLogCandidate> collectBatch(String userId, List<TradingParseAppService.ParseResult> parsed,
                                                String source) {
        List<TradeLogCandidate> pending = new java.util.ArrayList<>();
        for (TradingParseAppService.ParseResult r : parsed) {
            if (r == null || !r.matched() || r.direction() == null) continue;
            boolean hasSymbol = r.symbol() != null && !r.symbol().isBlank();
            boolean hasName = r.name() != null && !r.name().isBlank();
            if (!hasSymbol && !hasName) continue; // 同单笔：无 symbol/name 拒绝占位
            // 2026-08-27：VLM OCR 可能漏代码列（thinking 同图两次结果不同）——无代码但有名称
            // → 按名称查代码补 symbol（查不到保持待补充 complete=false，确认时可补）。
            String symbol = r.symbol();
            if (!hasSymbol && hasName) {
                String resolved = nameToSymbolResolver.resolve(r.name());
                if (resolved != null) {
                    symbol = resolved;
                    hasSymbol = true;
                }
            }
            pending.add(new TradeLogCandidate(
                    symbol, r.name(), r.direction(), r.price(), r.volume(),
                    // 2026-08-27：截图表格「日期」列提取（历史成交截图）；当日成交单无日期 → null
                    r.tradeDate(),
                    // 2026-09-18（P0-交易59）：截图「成交时间」列（同价同量分单的区分维度）
                    r.tradeTime(),
                    source,
                    hasSymbol && r.price() != null && r.volume() != null));
        }
        List<TradeLogCandidate> updated = pending.isEmpty()
                ? todayCandidates(userId)
                : tradeLogRepository.appendBatch(userId, LocalDate.now(), pending);
        if (!pending.isEmpty()) {
            log.info("交易日志批量归集 | userId={} | 解析 {} 笔 → 归集 {} 笔 | 当日候选 {} 笔",
                    userId, parsed.size(), pending.size(), updated.size());
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
     * 补写候选成交日期（2026-08-27 二修）：截图归集候选缺日期被 confirm 拒后，
     * 用户在确认前补日期 → 更新当日候选 tradeDate → 可再次确认（成交日 ≠ 确认日不再记错）。
     *
     * @return true=已更新；false=当日无此候选/参数非法
     */
    public boolean setTradeDate(String userId, String symbol, String direction, LocalDate tradeDate) {
        LocalDate today = LocalDate.now();
        boolean updated = tradeLogRepository.updateTradeDate(userId, today, symbol, direction, tradeDate);
        log.info("交易日志候选补日期 | userId={} | {} {} → {} | {}", userId, direction, symbol,
                tradeDate, updated ? "已更新" : "未命中");
        return updated;
    }

    /**
     * 按**行标识**补写候选成交日期（P1-交易54 收尾，2026-09-17）：前端默认路径。
     * 同标的同方向的多笔候选（当日三笔亨通光电各 100 股）只有 id 能定位到其中一条；
     * 旧口径会把它们**一起补上**同一个日期（而那几条可能来自不同的成交日）。
     */
    public boolean setTradeDateById(String userId, String id, LocalDate tradeDate) {
        LocalDate today = LocalDate.now();
        boolean updated = tradeLogRepository.updateTradeDateById(userId, today, id, tradeDate);
        log.info("交易日志候选补日期（按 id）| userId={} | id={} → {} | {}", userId, id,
                tradeDate, updated ? "已更新" : "未命中");
        return updated;
    }

    /**
     * 补写候选成交编号/手续费（P2-交易36 治本，2026-09-09）：截图入账/手动确认成交缺
     * orderId/fee——确认前用户在候选上补填（PUT /trade-log/meta），确认落库时随
     * {@link #confirm(String)} 经 recordTradeWithOrderId 透传流水。
     * <p>按 (symbol, direction) 定位当日候选（与 {@link #setTradeDate} 同口径，参照其写法）；
     * 只覆盖非空新值：orderId 非 null/非 blank 才替换、fee 非 null 才替换；两者皆空直接返回 false。
     * 锁内读-改-写由 {@link TradeLogRepository#updateMeta} 承担。
     *
     * @return true=至少更新了一笔候选；false=当日无此候选/无新值可写
     */
    public boolean updateMeta(String userId, String symbol, String direction,
                              String orderId, BigDecimal fee) {
        boolean hasOrder = orderId != null && !orderId.isBlank();
        boolean hasFee = fee != null;
        if (!hasOrder && !hasFee) return false;
        LocalDate today = LocalDate.now();
        boolean updated = tradeLogRepository.updateMeta(userId, today, symbol, direction,
                hasOrder ? orderId : null, hasFee ? fee : null);
        log.info("交易日志候选补成交元信息 | userId={} | {} {} | orderId={} fee={} | {}",
                userId, direction, symbol,
                hasOrder ? orderId : "（不改）", hasFee ? fee : "（不改）",
                updated ? "已更新" : "未命中");
        return updated;
    }

    /** 按**行标识**补写候选成交元信息（P1-交易54 收尾，2026-09-17）：语义同 {@link #updateMeta}，定位改用 id。 */
    public boolean updateMetaById(String userId, String id, String orderId, BigDecimal fee) {
        boolean hasOrder = orderId != null && !orderId.isBlank();
        boolean hasFee = fee != null;
        if (!hasOrder && !hasFee) return false;
        boolean updated = tradeLogRepository.updateMetaById(userId, LocalDate.now(), id,
                hasOrder ? orderId : null, hasFee ? fee : null);
        log.info("交易日志候选补成交元信息（按 id）| userId={} | id={} | orderId={} fee={} | {}",
                userId, id, hasOrder ? orderId : "（不改）", hasFee ? fee : "（不改）",
                updated ? "已更新" : "未命中");
        return updated;
    }

    /**
     * 候选就地编辑（2026-09-18，RFC 20260918 A1-4）：按行标识改**价格 / 数量 / 方向 / 成交日期 / 手续费**。
     *
     * <p>截图入账的全部价值是省手输，而 VLM 对价格、数量、买卖方向都可能出错——原实现只允许
     * 「全对」或「丢弃重录」（还要重发截图、重新补日期）。传 null = 该字段保持不变。
     *
     * @return true=已更新；false=无此候选 / 没有任何可改字段
     */
    public boolean updateFieldsById(String userId, String id, BigDecimal price, Integer volume,
                                    String direction, LocalDate tradeDate, BigDecimal fee) {
        boolean updated = tradeLogRepository.updateFieldsById(
                userId, LocalDate.now(), id, price, volume, direction, tradeDate, fee);
        log.info("交易日志候选就地编辑 | userId={} | id={} | 价={} 量={} 向={} 日期={} 费={} | {}",
                userId, id, price, volume, direction, tradeDate, fee, updated ? "已更新" : "未命中");
        return updated;
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

    /**
     * 按**行标识**丢弃候选（P1-交易54，2026-09-17 新增）：前端的默认路径。
     * 同标的同方向的多笔候选（生产实据：当日三笔亨通光电买入各 100 股）只有 id 能精确删到一条；
     * 旧口径 symbol+direction 会把它们**一起删掉**。
     * @return true=已移除；false=当日无此 id
     */
    public boolean discardById(String userId, String id) {
        return tradeLogRepository.discardById(userId, LocalDate.now(), id);
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
            return new ConfirmResult(0, 0, 0, 0, List.of(), List.of());
        }
        int done = 0;
        int skipped = 0;
        int ledgerOnly = 0; // 2026-09-18（P0-交易59）：命中券商快照锚定 → 只落流水不改账的笔数
        List<TradeLogCandidate> remaining = new java.util.ArrayList<>();
        List<String> failures = new java.util.ArrayList<>();
        List<String> duplicated = new java.util.ArrayList<>();
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
            // 2026-08-27（用户反馈「今日 4 笔其实是昨天」二修）：**截图归集候选缺成交日期 → 禁止落库**。
            // 首修只把 entryDate 从「确认当天」改为候选 tradeDate，但截图表格无日期列时 tradeDate=null
            // 仍回退确认当天——昨日委托今早确认又被记成今天。二修拍板：截图（source=image）必须有
            // 成交日期才允许落库，缺日期 → 跳过+保留候选+提示补日期（与 P0-1 不完整候选保留同语义）。
            if ("image".equals(c.source()) && c.tradeDate() == null) {
                skipped++;
                remaining.add(c);
                String label = c.name() != null && !c.name().isBlank() ? c.name() : c.symbol();
                failures.add(label + ": 缺少成交日期（截图未识别到日期列），请补充日期后再确认");
                log.info("交易日志确认跳过（截图缺成交日期）| userId={} | {} {} | 请补充日期",
                        userId, c.direction(), c.symbol());
                continue;
            }
            try {
                // 2026-08-27（用户反馈「今日 4 笔其实是昨天」）：成交日期以候选携带的 tradeDate 为准
                // （截图表格「日期」列提取）——成交日 ≠ 确认日不再记错；文字归集无日期才回退确认当天。
                java.time.LocalDate entryDate = c.tradeDate() != null ? c.tradeDate() : today;
                // P1-5（2026-09-19 对抗审查）：未知方向**不静默按 BUY 入账**——旧脏数据或旁路写入
                // 可能留下非法值（"卖出"/"sell"…），按 BUY 落库会把卖出变成加仓（持仓差 2× 股数）。
                if (!"BUY".equals(c.direction()) && !"SELL".equals(c.direction())) {
                    skipped++;
                    remaining.add(c);
                    String lbl = c.name() != null && !c.name().isBlank() ? c.name() : c.symbol();
                    failures.add(lbl + ": 买卖方向不合法（" + c.direction() + "），丢弃后重新发一次截图");
                    log.warn("交易日志确认跳过（方向不合法）| userId={} | {} {} | direction={}",
                            userId, c.symbol(), c.name(), c.direction());
                    continue;
                }
                TradeDirection dir = "SELL".equals(c.direction()) ? TradeDirection.SELL : TradeDirection.BUY;
                // 2026-09-19（P0-1 + P1-4，三官深审）：
                // ① 判重带**成交时间**——候选层已按 tradeTime 区分同价同量分单，这里不带的话
                //    第 2 笔会命中第 1 笔刚落的流水被判「已记过」跳过（卖 400 股只记 200 股）；
                // ② 「判重 → 锚定分派 → 落账」**整段收进同一把 per-user 锁**（原实现锁外先查后写，
                //    并发确认能双落账——pitfalls「检查-再动作竞态」）。锁可重入，内层 recordTradeWithOrderId 安全。
                synchronized (tradingAppService.candidateLock(userId)) {
                    java.util.Optional<com.adaiadai.core.domain.trading.TradeRecord> dup =
                            tradingAppService.findRecordedTrade(userId, c.symbol(), dir,
                                    c.price(), c.volume(), entryDate, c.tradeTime(), c.orderId());
                    if (dup.isPresent()) {
                        String label = c.name() != null && !c.name().isBlank() ? c.name() : c.symbol();
                        String when = dup.get().entryDate() != null ? dup.get().entryDate().toString()
                                : String.valueOf(dup.get().timestamp());
                        duplicated.add(label + ": 这笔之前已经记过了（" + when + " "
                                + dup.get().volume() + " 股 @ " + dup.get().price() + "），没有重复入账");
                        log.info("交易日志确认跳过（同笔已落库）| userId={} | {} {} {}股@{} | 已有流水 {}",
                                userId, dir, c.symbol(), c.volume(), c.price(), dup.get().id());
                        continue; // 已入账 → 不留候选
                    }
                    // 2026-09-18（P0-交易59）：命中「券商快照锚定日」→ **降级为只落流水、不动持仓与现金**，
                    // 不再硬拒（原实现抛「已包含在券商快照中」，而锚定日是「≤」判定且只增不减
                    // → 用户当天成交永远补不回来）。
                    if (tradingAppService.isCoveredByAnchor(userId, entryDate)) {
                        boolean written = tradingAppService.ledgerOnlyTrade(
                                userId, c.symbol(), c.name(), dir,
                                c.price() != null ? c.price() : BigDecimal.ZERO,
                                c.volume() != null ? c.volume() : 0,
                                entryDate,
                                c.tradeTime() != null ? c.tradeTime() : java.time.LocalTime.now(),
                                c.orderId(), c.fee());
                        if (written) {
                            ledgerOnly++;
                            log.info("交易日志确认降级（命中券商快照锚定：只落流水不改账）| userId={} | {} {} {}股@{} | 成交日 {}",
                                    userId, dir, c.symbol(), c.volume(), c.price(), entryDate);
                            continue; // 流水已留痕 → 候选不再保留
                        }
                        // 兜底写入失败：保留候选 + 如实报错（绝不静默吞）
                        String label = c.name() != null && !c.name().isBlank() ? c.name() : c.symbol();
                        failures.add(label + ": 流水没写进去，先把候选留着，稍后再试");
                        remaining.add(c);
                        log.warn("交易日志确认降级失败（流水未留痕，候选保留）| userId={} | {} {} {}股@{}",
                                userId, dir, c.symbol(), c.volume(), c.price());
                        continue;
                    }
                    // P2-交易36 治本（2026-09-09）：完整候选走带 orderId/fee 的 recordTradeWithOrderId
                    // （候选补填的成交编号/手续费透传流水落盘）。
                    tradingAppService.recordTradeWithOrderId(
                            userId,
                            c.symbol(),
                            c.name(),
                            dir,
                            c.price() != null ? c.price() : BigDecimal.ZERO,
                            c.volume() != null ? c.volume() : 0,
                            entryDate,
                            // 2026-09-18（P0-交易59）：截图带上来的成交时间优先（同价同量分单要分得开）
                            c.tradeTime() != null ? c.tradeTime() : java.time.LocalTime.now(),
                            null, null, null, null,
                            c.orderId(), c.fee());
                    done++;
                }
            } catch (Exception e) {
                // P0-1：失败候选保留（不丢），记录人话原因供前端展示
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                String label = c.name() != null && !c.name().isBlank() ? c.name() : c.symbol();
                failures.add(label + ": " + msg);
                remaining.add(c);
                log.warn("交易日志确认落库失败（保留候选）| userId={} | {} {} | {}", userId, c.direction(), c.symbol(), msg);
            }
        }
        // C1（2026-08-23，隔离审查 P2-2）+ P2-交易25（2026-08-29 残余窗口根治）：
        // confirm 读取→处理→save 原在 repository 锁外——处理期间新归集（collect append）的候选
        // 若直接 save(remaining) 会被覆盖清掉；且「锁外读 latest 再锁内 save」的读→写间仍有窗口。
        // 现整体收敛到 repository 锁内原子「读最新 → 合并保留集 → 写回」（saveMerging）：
        // 并发 append 与本写串行化，新候选不再被覆盖。
        tradeLogRepository.saveMerging(userId, today, candidates, remaining);
        log.info("交易日志确认落库 | userId={} | 成功 {} / 降级只记账 {} / 失败 {} / 跳过(不完整) {} / 同笔已记过 {} / 共 {} 笔 | 保留 {} 笔",
                userId, done, ledgerOnly, failures.size(), skipped, duplicated.size(),
                candidates.size(), remaining.size());
        return new ConfirmResult(done, failures.size(), skipped, duplicated.size(), ledgerOnly,
                failures, duplicated);
    }

    /**
     * 确认结果：成功/失败/跳过笔数 + 失败人话明细（P0-1：失败候选已保留，可再次确认）；
     * {@code duplicated}/{@code duplicates} = 与已落库流水同笔而被跳过的候选（2026-09-15 防重复入账）；
     * {@code ledgerOnly} = 命中券商快照锚定、只落流水不改账（持仓/现金以快照为准）的笔数
     * （2026-09-18，P0-交易59：原为硬拒，用户当天成交永远无法入账）。
     */
    public record ConfirmResult(int confirmed, int failed, int skipped, int duplicated, int ledgerOnly,
                                List<String> failures, List<String> duplicates) {
        /** 兼容构造（2026-09-18 新增 ledgerOnly 之前的老口径）：ledgerOnly 缺省 0。 */
        public ConfirmResult(int confirmed, int failed, int skipped, int duplicated,
                             List<String> failures, List<String> duplicates) {
            this(confirmed, failed, skipped, duplicated, 0, failures, duplicates);
        }
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
