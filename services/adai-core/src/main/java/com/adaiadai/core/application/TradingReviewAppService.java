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
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    /**
     * 复盘生成 system 指令（生成语义）。必须用 generate() 而非 understand()：
     * understand 的默认 system 会引导"输出 JSON summary（3-5 词）"，压制复盘 5 节正文模板。
     */
    private static final String REVIEW_SYSTEM_PROMPT = """
            你是一个个人交易复盘助手。基于用户消息中的上下文（交易系统规则、知识、行情、身份、历史记录）与复盘模板，生成结构化的交易复盘笔记正文。
            严格遵循模板的五个小节（今日交易执行/持仓变化与关注/与系统规则对照/今日教训与心得/明日关注要点）输出正文本身；不要输出 JSON，不要输出 summary，不要用 markdown 代码块包裹，不要使用 emoji。
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

    public TradingReviewAppService(RecordRepository recordRepository,
                                   PositionRepository positionRepository,
                                   AccountSnapshotRepository accountSnapshotRepository,
                                   ContextEngine contextEngine,
                                   AiClient aiClient,
                                   TradingReviewFileRepository reviewRepository,
                                   TradingLotService tradingLotService,
                                   TradingAppService tradingAppService) {
        this.recordRepository = recordRepository;
        this.positionRepository = positionRepository;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.contextEngine = contextEngine;
        this.aiClient = aiClient;
        this.reviewRepository = reviewRepository;
        this.tradingLotService = tradingLotService;
        this.tradingAppService = tradingAppService;
    }

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
