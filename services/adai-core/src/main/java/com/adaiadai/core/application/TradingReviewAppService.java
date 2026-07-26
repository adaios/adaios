package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.PortfolioSnapshot;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.TradingReviewFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * TradingReviewAppService — 交易复盘应用服务。
 * <p>
 * 编排复盘流程：收集当日交易数据 + 持仓变化 + 近期记录
 * → ContextEngine (trading 场景) → AI 生成复盘笔记 → 写文件。
 */
@Service
public class TradingReviewAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingReviewAppService.class);

    private final RecordRepository recordRepository;
    private final PositionRepository positionRepository;
    private final ContextEngine contextEngine;
    private final AiClient aiClient;
    private final TradingReviewFileRepository reviewRepository;

    public TradingReviewAppService(RecordRepository recordRepository,
                                   PositionRepository positionRepository,
                                   ContextEngine contextEngine,
                                   AiClient aiClient,
                                   TradingReviewFileRepository reviewRepository) {
        this.recordRepository = recordRepository;
        this.positionRepository = positionRepository;
        this.contextEngine = contextEngine;
        this.aiClient = aiClient;
        this.reviewRepository = reviewRepository;
    }

    /**
     * 生成指定日期的交易复盘。
     *
     * @param date 复盘日期
     * @return 生成的复盘笔记内容
     */
    public String generateReview(LocalDate date) {
        log.info("=== 复盘生成开始 | date={} ===", date);

        // 1. 收集当日数据
        List<ContentRecord> todayRecords = recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().equals(date))
                .toList();

        List<Position> positions = positionRepository.findAll();
        PortfolioSnapshot snapshot = positionRepository.snapshot();

        // 2. 组装复盘 prompt
        String prompt = buildReviewPrompt(date, todayRecords, positions, snapshot);

        // 3. AI 生成复盘
        ContextPackage ctx = new ContextPackage(
                "trading", "",
                date + " 交易复盘", prompt, List.of(),
                List.of(), prompt, java.time.LocalDateTime.now(),
                List.of()
        );

        AiUnderstanding understanding = aiClient.understand(ctx);
        String reviewContent = understanding.summary() != null ? understanding.summary() : understanding.rawResponse();

        // 4. 持久化
        reviewRepository.save(date, reviewContent);

        log.info("=== 复盘生成完成 | date={} | length={} ===", date, reviewContent.length());
        return reviewContent;
    }

    /**
     * 获取指定日期的复盘笔记。
     */
    public String getReview(LocalDate date) {
        return reviewRepository.read(date);
    }

    /**
     * 列出所有复盘日期。
     */
    public List<LocalDate> listReviews() {
        return reviewRepository.listAll();
    }

    /**
     * 检测指定日期是否有交易活动（交易相关记录）。
     */
    public boolean hasTradingActivity(LocalDate date) {
        return recordRepository.findAll().stream()
                .filter(r -> r.createdAt().toLocalDate().equals(date))
                .anyMatch(r -> {
                    String content = (r.content() != null ? r.content().toLowerCase() : "")
                            + (r.title() != null ? r.title().toLowerCase() : "");
                    return content.contains("买") || content.contains("卖")
                            || content.contains("仓") || content.contains("股")
                            || content.contains("交易") || content.contains("持仓")
                            || content.contains("止损") || content.contains("止盈");
                });
    }

    // ── 内部方法 ──

    private String buildReviewPrompt(LocalDate date, List<ContentRecord> records,
                                     List<Position> positions, PortfolioSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个个人交易复盘助手。请生成一份简短的交易复盘笔记。\n\n");
        sb.append("复盘日期：").append(date).append("\n\n");

        // 当日记录
        if (!records.isEmpty()) {
            sb.append("## 当日记录\n\n");
            for (ContentRecord r : records) {
                String time = r.createdAt().toLocalTime()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                sb.append("- [").append(time).append("] ").append(r.content()).append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("当日无新记录。\n\n");
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

        // 复盘模板
        sb.append("请按以下格式输出复盘笔记（使用 Markdown）：\n\n");
        sb.append("## ").append(date).append(" 交易复盘\n\n");
        sb.append("### 1. 今日交易执行情况\n");
        sb.append("（基于当日记录，总结交易执行情况）\n\n");
        sb.append("### 2. 持仓变化与关注\n");
        sb.append("（当前持仓状态，需要关注的标的）\n\n");
        sb.append("### 3. 与系统规则对照\n");
        sb.append("（对照交易系统的规则，检查执行是否符合纪律）\n\n");
        sb.append("### 4. 今日教训与心得\n");
        sb.append("（从今天的操作中学到了什么）\n\n");
        sb.append("### 5. 明日关注要点\n");
        sb.append("（明日需要关注的关键信号和待办事项）\n\n");
        sb.append("要求：简洁、可操作性，不做荐股，不做主观预测。每节 2-5 句话即可。");

        return sb.toString();
    }
}
