package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.PortfolioSnapshot;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.infrastructure.ai.llm.LlmResponseParser;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingReviewFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * TradingReviewAppService — 交易复盘应用服务。
 * <p>
 * 编排复盘流程：收集当日交易数据 + 持仓变化 + 近期记录
 * → ContextEngine (trading 场景，注入交易规则/知识/行情) → AI 生成复盘笔记 → 写文件。
 * <p>
 * #12 修复：复盘不再手拼 prompt 绕过 ContextEngine——改为构造合成复盘记录走
 * {@code contextEngine.compose("trading", record)}，让 TradingContextContributor /
 * TradingKnowledgeSource / MarketContextContributor 的规则与行情真正进复盘上下文。
 */
@Service
public class TradingReviewAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingReviewAppService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 提交状态：已有复盘（不重复生成，前端直接 GET 展示）。 */
    public static final String STATUS_EXISTS = "exists";
    /** 提交状态：同一日期复盘正在生成中（连点/双端并发只跑一次 AI）。 */
    public static final String STATUS_RUNNING = "running";
    /** 提交状态：已受理，后台生成中（前端轮询 GET /trading/review 直到 200）。 */
    public static final String STATUS_PENDING = "pending";

    /** 复盘生成专用执行器（ReviewSubmitConfig，2 线程 + 有限队列，拒绝时抛 TradingException）。 */
    private final Executor reviewSubmitExecutor;
    /** 生成中集合（key = userId|date）——同日去重，防重复点击烧两次 AI（2026-09-07 复盘超时修复批）。 */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    /**
     * 复盘生成 system 指令（生成语义）。必须用 generate() 而非 understand()：
     * understand 的默认 system 会引导"输出 JSON summary（3-5 词）"，压制复盘 5 节正文模板。
     */
    private static final String REVIEW_SYSTEM_PROMPT = """
            你是一个个人交易复盘助手。基于用户消息中的上下文（交易系统规则、知识、行情、身份、历史记录）与复盘模板，生成结构化的交易复盘笔记正文。
            严格遵循模板的五个小节（今日交易执行/持仓变化与关注/与系统规则对照/今日教训与心得/明日关注要点）输出正文本身；不要输出 JSON，不要输出 summary，不要用 markdown 代码块包裹，不要使用 emoji。
            【对抗审 🤔9（2026-09-05）：若上下文含「建议对照」段（阿呆当时说 → 你做了 → 结果），必须原样保留其数字与对照关系并入「与系统规则对照」小节——不得丢弃、不得改写数字、不得用指责语气（主语是你 vs 你的历史，讲事实不下判断）】。
            """.strip();

    private final RecordRepository recordRepository;
    private final PositionRepository positionRepository;
    private final AccountSnapshotRepository accountSnapshotRepository;
    private final ContextEngine contextEngine;
    private final AiClient aiClient;
    private final TradingReviewFileRepository reviewRepository;
    /** RFC 20260825：行为标注注入（记录即标注进当晚复盘——亏损加仓/追高/破止损未走等）。 */
    private final TradingLotService tradingLotService;
    /** 2026-08-26 复盘卡点：hasTradingActivity 改查当日真实成交（getDailyTradeSummary.count），
     *  复盘生成与「今日有成交」绑定（用户拍板：导入成交后才可生成复盘）。 */
    private final TradingAppService tradingAppService;
    /** RFC 20260905 B②③：建议留痕——卖出回查「阿呆当时说 X」→ 复盘对照段。 */
    private final com.adaiadai.core.domain.trading.AdviceHistoryRepository adviceHistoryRepository;

    /** 测试/历史调用便捷构造：同步执行器（Runnable 直接跑），语义等价旧同步行为。 */
    public TradingReviewAppService(RecordRepository recordRepository,
                                   PositionRepository positionRepository,
                                   AccountSnapshotRepository accountSnapshotRepository,
                                   ContextEngine contextEngine,
                                   AiClient aiClient,
                                   TradingReviewFileRepository reviewRepository,
                                   TradingLotService tradingLotService,
                                   TradingAppService tradingAppService,
                                   com.adaiadai.core.domain.trading.AdviceHistoryRepository adviceHistoryRepository) {
        this(recordRepository, positionRepository, accountSnapshotRepository, contextEngine, aiClient,
                reviewRepository, tradingLotService, tradingAppService, adviceHistoryRepository,
                Runnable::run);
    }

    @Autowired
    public TradingReviewAppService(RecordRepository recordRepository,
                                   PositionRepository positionRepository,
                                   AccountSnapshotRepository accountSnapshotRepository,
                                   ContextEngine contextEngine,
                                   AiClient aiClient,
                                   TradingReviewFileRepository reviewRepository,
                                   TradingLotService tradingLotService,
                                   TradingAppService tradingAppService,
                                   com.adaiadai.core.domain.trading.AdviceHistoryRepository adviceHistoryRepository,
                                   @Qualifier("reviewSubmitExecutor") Executor reviewSubmitExecutor) {
        this.recordRepository = recordRepository;
        this.positionRepository = positionRepository;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.contextEngine = contextEngine;
        this.aiClient = aiClient;
        this.reviewRepository = reviewRepository;
        this.tradingLotService = tradingLotService;
        this.tradingAppService = tradingAppService;
        this.adviceHistoryRepository = adviceHistoryRepository;
        this.reviewSubmitExecutor = reviewSubmitExecutor;
    }

    /**
     * 提交生成指定日期复盘（2026-09-07 复盘超时修复批：点击即返回，不阻塞请求线程）。
     * <p>
     * AI 生成实测 77~176s，远超前端 15s/120s 客户端超时——原同步 POST 必然前端先断、
     * 结果「看似没反应」（复盘其实在后端已生成落盘）。改为：后台执行器生成 + 前端轮询
     * {@code GET /trading/review?date=} 直到文件就绪。
     * <p>
     * 去重三态：文件已存在 → {@link #STATUS_EXISTS}（不重复烧 AI，前端即刻 GET 展示）；
     * 同 user+date 正在生成 → {@link #STATUS_RUNNING}（连点/双端并发只跑一次）；
     * 否则受理 → {@link #STATUS_PENDING}（后台生成，生成失败只记日志、不落半成品）。
     *
     * @return 提交状态（date + status）
     */
    public ReviewSubmitResult submitReview(String userId, LocalDate date) {
        // 已有复盘 → 直接返回 exists（前端 GET 立即展示；今天已生成过的复盘不再重跑）
        try {
            String existing = reviewRepository.read(userId, date);
            if (existing != null && !existing.isBlank()) {
                return new ReviewSubmitResult(date.toString(), STATUS_EXISTS);
            }
        } catch (Exception e) {
            log.warn("复盘存在性检查失败，按无复盘处理 | userId={} | date={} | {}", userId, date, e.getMessage());
        }

        String key = userId + "|" + date;
        if (!inflight.add(key)) {
            return new ReviewSubmitResult(date.toString(), STATUS_RUNNING);
        }
        try {
            reviewSubmitExecutor.execute(() -> {
                try {
                    generateReview(userId, date);
                } catch (Exception e) {
                    log.error("复盘后台生成失败 | userId={} | date={}", userId, date);
                    log.error("复盘生成异常", e);
                } finally {
                    inflight.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            inflight.remove(key);
            throw new com.adaiadai.core.domain.trading.TradingException("复盘任务队列已满，请稍后重试");
        }
        return new ReviewSubmitResult(date.toString(), STATUS_PENDING);
    }

    /** 复盘提交结果（POST /trading/review 响应：date + status，见 {@code submitReview}）。 */
    public record ReviewSubmitResult(String date, String status) {}

    /**
     * 生成指定日期的交易复盘。
     *
     * @param date 复盘日期
     * @return 生成的复盘笔记内容
     */
    public String generateReview(String userId, LocalDate date) {
        log.info("=== 复盘生成开始 | userId={} | date={} ===", userId, date);

        // 1. 收集当日数据
        List<ContentRecord> todayRecords = recordRepository.findAll(userId).stream()
                .filter(r -> r.createdAt().toLocalDate().equals(date))
                .toList();

        List<Position> positions = positionRepository.findAll(userId);
        // S5（2026-08-17）：现金唯一真源 = account.json 的 AccountSnapshot.cash（不再用 positions.md snapshot）
        java.math.BigDecimal cash = accountSnapshotRepository.findLatest(userId)
                .map(AccountSnapshot::cash)
                .orElse(java.math.BigDecimal.ZERO);
        PortfolioSnapshot snapshot = PortfolioSnapshot.of(positions, cash);

        // 2. 复盘正文：当日记录 + 持仓 + 行为标注（作为合成记录的 content，含交易关键词触发 trading 场景）
        String reviewBody = buildReviewBody(date, todayRecords, positions, snapshot, userId);

        // 3. 合成复盘记录 → 走 ContextEngine，注入交易规则/知识/行情/身份/历史/记忆
        ContentRecord reviewRecord = new ContentRecord(
                RecordFileRepository.generateId(), "review", "trading_review",
                date + " 交易复盘", reviewBody,
                List.of("trading", "复盘"), LocalDateTime.now()
        );
        ContextPackage ctx = contextEngine.compose(userId, "trading", reviewRecord);
        log.info("复盘上下文组装完成 | 注入交易知识+行情 | prompt={}字",
                ctx.prompt() != null ? ctx.prompt().length() : 0);

        // 4. 用注入后的上下文 + 复盘模板组装最终 prompt（去掉 compose 的分析指令段）
        String fullPrompt = buildReviewPrompt(ctx, date);
        ContextPackage reviewCtx = new ContextPackage(
                ctx.scene(), ctx.identityRef(), ctx.recordTitle(), ctx.recordContent(),
                ctx.recordTags(), ctx.relatedRefs(), fullPrompt, ctx.assembledAt(),
                ctx.conversationHistory()
        );

        // 5. AI 生成复盘（生成语义：无 JSON 摘要指令，按复盘模板输出正文）
        // R1 AI 交互日志：挂载复盘记录锚点
        AiTraceContext.set(userId, reviewRecord.id(), null, "trading_review");
        String reviewContent = aiClient.generate(reviewCtx, REVIEW_SYSTEM_PROMPT);
        // #202：AI 偶发用 ```markdown 围栏包裹复盘正文，剥离围栏防渲染破坏
        reviewContent = LlmResponseParser.stripCodeFences(reviewContent);

        // 6. 持久化
        reviewRepository.save(userId, date, reviewContent);

        log.info("=== 复盘生成完成 | userId={} | date={} | length={} ===", userId, date, reviewContent.length());
        return reviewContent;
    }

    /**
     * 获取指定日期的复盘笔记。
     */
    public String getReview(String userId, LocalDate date) {
        return reviewRepository.read(userId, date);
    }

    /**
     * 列出所有复盘日期。
     */
    public List<LocalDate> listReviews(String userId) {
        return reviewRepository.listAll(userId);
    }

    /**
     * 检测指定日期是否有交易活动（交易相关记录）。
     */
    /**
     * 检测指定日期是否有交易活动（2026-08-26 复盘卡点，用户拍板）：
     * **当日真实成交 > 0** 才算有——废除旧「关键词扫描对话记录」（聊到"买/仓/股"即误报；
     * 导入成交后若记录文本不带关键词反而不报）。口径与复盘数据源一致：
     * 无当日成交 → 复盘无可写「今日交易执行」→ 前端横幅/按钮不出现或引导先导入。
     */
    public boolean hasTradingActivity(String userId, LocalDate date) {
        return tradingAppService.getDailyTradeSummary(userId, date).count() > 0;
    }

    /**
     * 当前持仓一行摘要（简报注入用，2026-08-17）。
     * <p>
     * 防止简报 LLM 拿历史买入记录自行算盈亏（曾产出「京东方浮盈11.73%」而实际亏 3.8%）。
     * 每行：名称（代码）成本 X 现价 Y 盈亏 Z%（权威口径，勿从旧记录推算）。
     */
    public List<String> positionSummaryLines(String userId) {
        List<Position> positions = positionRepository.findAll(userId);
        List<String> lines = new java.util.ArrayList<>();
        for (Position p : positions) {
            StringBuilder sb = new StringBuilder();
            sb.append(p.name()).append("（").append(p.symbol()).append("）")
                    .append(" 成本 ").append(p.avgCost() != null ? p.avgCost().stripTrailingZeros().toPlainString() : "-")
                    .append(" 现价 ").append(p.currentPrice() != null ? p.currentPrice().stripTrailingZeros().toPlainString() : "-")
                    .append(" 盈亏 ").append(p.pnl() != null ? p.pnl().setScale(2).toPlainString() : "-")
                    .append(" 盈亏% ").append(p.pnlPercent() != null ? p.pnlPercent().setScale(2).toPlainString() : "-");
            if (p.effectiveStopLoss() != null) {
                sb.append(" 止损 ").append(p.effectiveStopLoss().stripTrailingZeros().toPlainString());
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    // ── 内部方法 ──

    /**
     * RFC 20260905 B③：建议闭环对照段。
     * <p>
     * 当日清仓（sold.sellDate == 复盘日）的标的 → 回查近 30 天建议留痕 →
     * 生成「阿呆当时说 X → 你做了 Y → 结果 Z」客观对照（数字代码算，LLM 只组织语言，红线①）。
     * <p>
     * 数字口径：建议日价格 vs 清仓日实际结果（holdPnlPct 由清仓表提供）——
     * 只陈述「建议动作 vs 实际动作 + 结果」，不下价值判断（合规：主语是你）。
     */
    private String buildAdviceCompareSection(String userId, LocalDate date) {
        List<com.adaiadai.core.domain.trading.SoldTrade> soldDay;
        try {
            soldDay = tradingAppService.soldList(userId).stream()
                    .filter(s -> date.equals(s.sellDate()))
                    .toList();
        } catch (Exception e) {
            return "";
        }
        if (soldDay.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 建议对照（阿呆当时说 → 你做了什么 → 结果）\n\n");
        boolean any = false;
        for (com.adaiadai.core.domain.trading.SoldTrade s : soldDay) {
            // P1-2（2026-09-05 三官深审）：以该笔 sellDate 为锚回查卖前建议（不用 now()——
            // 历史复盘会错窗口；且必须滤 date ≤ sellDate，防「阿呆当时说」引用清仓后生成的建议）
            java.time.LocalDate windowStart = s.sellDate().minusDays(30);
            java.time.YearMonth m = java.time.YearMonth.from(s.sellDate());
            java.time.YearMonth m0 = java.time.YearMonth.from(windowStart);
            java.util.List<com.adaiadai.core.domain.trading.AdviceEntry> preSell = new java.util.ArrayList<>();
            try {
                for (java.time.YearMonth ym = m; !ym.isBefore(m0); ym = ym.minusMonths(1)) {
                    for (com.adaiadai.core.domain.trading.AdviceEntry h
                            : adviceHistoryRepository.findByMonth(userId, ym.atDay(1))) {
                        if (!s.symbol().equals(h.symbol())) continue;
                        if (h.date() == null || h.date().isAfter(s.sellDate()) || h.date().isBefore(windowStart)) continue;
                        preSell.add(h);
                    }
                }
            } catch (Exception e) {
                continue;
            }
            if (preSell.isEmpty()) continue;
            any = true;
            // 卖前最近一条（createdAt 降序取首；无 createdAt 按 date 兜底）
            com.adaiadai.core.domain.trading.AdviceEntry latest = preSell.stream()
                    .sorted(java.util.Comparator.comparing(
                            (com.adaiadai.core.domain.trading.AdviceEntry h) -> h.date(),
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .findFirst().orElse(null);
            if (latest == null) continue;
            // ⚠️8（2026-09-05 对抗审）：建议动作英文枚举 → 中文（复盘是给用户读的，不是给机器）
            String suggestion = humanSuggestion(latest.suggestion());
            sb.append("- **").append(s.name()).append("（").append(s.symbol()).append("）**\n");
            sb.append("  - 阿呆当时说（").append(latest.date()).append("）：").append(suggestion);
            if (latest.reason() != null && !latest.reason().isBlank()) {
                sb.append(" —— ").append(latest.reason());
            }
            sb.append("\n");
            sb.append("  - 你做了：").append(s.sellDate()).append(" 清仓，持仓期 ").append(s.holdPnlPct()).append("%\n");
            sb.append("  - 规则对照：").append(s.verdict() != null && !s.verdict().isBlank()
                    ? s.verdict() : "（无判定）").append("\n");
        }
        if (!any) return "";
        sb.append("\n> 数字由系统从建议留痕与清仓史推导；这份对照帮你看见「计划 vs 执行」的距离。\n");
        return sb.toString();
    }

    /** ⚠️8（2026-09-05 对抗审）：建议动作枚举 → 中文（复盘用户可读；null/未知 → 占位）。 */
    private String humanSuggestion(String raw) {
        if (raw == null || raw.isBlank()) return "（当时未给出明确建议）";
        return switch (raw.strip().toLowerCase()) {
            case "buy" -> "加仓";
            case "hold" -> "持有";
            case "reduce" -> "减仓";
            case "clear" -> "清仓";
            default -> raw;
        };
    }

    /**
     * 复盘正文：当日记录 + 当前持仓 + 汇总。作为合成记录的 content 传给 ContextEngine。
     */
    private String buildReviewBody(LocalDate date, List<ContentRecord> records,
                                   List<Position> positions, PortfolioSnapshot snapshot, String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("复盘日期：").append(date).append("\n\n");

        // 当日记录
        if (!records.isEmpty()) {
            sb.append("## 当日记录\n\n");
            for (ContentRecord r : records) {
                String time = r.createdAt().toLocalTime().format(TIME_FORMATTER);
                sb.append("- [").append(time).append("] ").append(r.content()).append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("当日无新记录。\n\n");
        }

        // RFC 20260825：当日行为标注（亏损加仓/追高/短线新开/破止损未走/浮盈回吐/短线超期）
        try {
            List<TradingLotService.BehaviorNote> behaviors = tradingLotService.analyzeBehaviors(userId, date);
            if (!behaviors.isEmpty()) {
                sb.append("## 当日行为标注（阿呆观察，纪律对照）\n\n");
                for (TradingLotService.BehaviorNote b : behaviors) {
                    sb.append("- 【").append(b.label()).append("】").append(b.message()).append("\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.warn("复盘行为标注注入失败（不影响复盘生成）| userId={} | {}", userId, e.getMessage());
        }

        // RFC 20260905 B③：建议闭环对照段——当日卖出的标的，「阿呆当时说 X → 你做了 Y → 结果 Z」
        try {
            String adviceCompare = buildAdviceCompareSection(userId, date);
            if (adviceCompare != null && !adviceCompare.isBlank()) {
                sb.append(adviceCompare).append("\n");
            }
        } catch (Exception e) {
            log.warn("复盘建议对照注入失败（不影响复盘生成）| userId={} | {}", userId, e.getMessage());
        }

        // 当前持仓
        if (!positions.isEmpty()) {
            sb.append("## 当前持仓\n\n");
            sb.append("| 代码 | 名称 | 数量 | 成本 | 现价 | 市值 | 盈亏 | 盈亏% |\n");
            sb.append("|------|------|------|------|------|------|------|-------|\n");
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
            sb.append("\n**汇总**：总市值=").append(snapshot.totalValue().setScale(2).toPlainString())
                    .append("，总盈亏=").append(snapshot.totalPnl().setScale(2).toPlainString())
                    .append("，现金=").append(snapshot.cashBalance().setScale(2).toPlainString()).append("\n\n");
        } else {
            sb.append("当前无持仓。\n\n");
        }

        return sb.toString();
    }

    /**
     * 复盘最终 prompt：ContextEngine 注入的上下文（知识/规则/行情/身份/历史）+ 复盘模板。
     * 去掉 compose 自带的"请分析这条记录，输出 JSON"指令段，避免与复盘格式冲突。
     */
    private String buildReviewPrompt(ContextPackage ctx, LocalDate date) {
        String base = ctx.prompt();
        int idx = base.indexOf("请分析这条记录");
        String contextOnly = (idx > 0 ? base.substring(0, idx) : base).strip();
        return contextOnly + "\n\n" + reviewTemplate(date);
    }

    private String reviewTemplate(LocalDate date) {
        return """
                你是一个个人交易复盘助手。请基于以上注入的上下文（交易系统规则、知识、行情、身份、历史记录）生成一份简短的交易复盘笔记。

                ## %s 交易复盘

                ### 1. 今日交易执行情况
                （基于当日记录，总结交易执行情况）

                ### 2. 持仓变化与关注
                （当前持仓状态，需要关注的标的）

                ### 3. 与系统规则对照
                （对照交易系统的规则，检查执行是否符合纪律——引用具体规则）

                ### 4. 今日教训与心得
                （从今天的操作中学到了什么）

                ### 5. 明日关注要点
                （明日需要关注的关键信号和待办事项）

                要求：简洁、可操作性，不做荐股，不做主观预测。每节 2-5 句话即可。重点：第 3 节必须对照交易系统规则（参考注入的交易知识/规则）。
                """.formatted(date);
    }
}
