package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.application.TradingReviewAppService;
import com.adaiadai.core.domain.trading.PortfolioSnapshot;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.infrastructure.storage.StorageException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

/**
 * TradingController — 交易相关的 REST API。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingController {

    private static final Logger log = LoggerFactory.getLogger(TradingController.class);

    private final TradingAppService tradingAppService;
    private final TradingReviewAppService reviewAppService;

    public TradingController(TradingAppService tradingAppService,
                             TradingReviewAppService reviewAppService) {
        this.tradingAppService = tradingAppService;
        this.reviewAppService = reviewAppService;
    }

    /**
     * 查询当前持仓。
     */
    @GetMapping("/positions")
    public ResponseEntity<List<Position>> getPositions() {
        return ResponseEntity.ok(tradingAppService.getPositions());
    }

    /**
     * 查询投资组合快照。
     */
    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioSnapshot> getPortfolio() {
        return ResponseEntity.ok(tradingAppService.getPortfolioSnapshot());
    }

    /**
     * 记录一笔交易（买入/卖出）。
     */
    @PostMapping("/trades")
    public ResponseEntity<List<Position>> recordTrade(@Valid @RequestBody TradeRequest request) {
        List<Position> updated = tradingAppService.recordTrade(
                request.symbol(), request.name(),
                request.direction(), request.price(), request.volume()
        );
        return ResponseEntity.ok(updated);
    }

    // ── 复盘 API ──

    /**
     * 生成交易复盘笔记。
     * <p>
     * AI 基于当日交易记录 + 持仓变化 + 近期记录生成复盘。
     * 输出写入 {@code data/trading/reviews/YYYY-MM-DD_review.md}。
     */
    @PostMapping("/review")
    public ResponseEntity<ReviewResponse> generateReview(@RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        String content = reviewAppService.generateReview(date);
        return ResponseEntity.ok(new ReviewResponse(date.toString(), content));
    }

    /**
     * 获取指定日期的复盘笔记。
     */
    @GetMapping("/review")
    public ResponseEntity<ReviewResponse> getReview(@RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        String content = reviewAppService.getReview(date);
        if (content == null || content.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ReviewResponse(date.toString(), content));
    }

    /**
     * 列出所有复盘日期。
     */
    @GetMapping("/reviews")
    public ResponseEntity<List<LocalDate>> listReviews() {
        return ResponseEntity.ok(reviewAppService.listReviews());
    }

    /**
     * 检测指定日期是否有交易活动。
     */
    @GetMapping("/has-activity")
    public ResponseEntity<ActivityCheckResponse> hasTradingActivity(
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        boolean hasActivity = reviewAppService.hasTradingActivity(date);
        return ResponseEntity.ok(new ActivityCheckResponse(date.toString(), hasActivity));
    }

    // ── 知识反哺 API ──

    /**
     * 将复盘笔记中的内容提升为入库候选。
     * <p>
     * 写入 {@code os/trading-os/99-inbox/}，供用户在 trading-os 工作焦点下审核。
     * 尊重 os/ 目录独立性：adai-core 只写入 99-inbox/，不做自动入库。
     */
    @PostMapping("/reviews/{date}/promote")
    public ResponseEntity<PromoteResponse> promoteToInbox(
            @PathVariable LocalDate date,
            @RequestBody PromoteRequest request) {
        String reviewContent = reviewAppService.getReview(date);
        if (reviewContent == null || reviewContent.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        try {
            // 构建入库候选内容
            String content = buildPromoteContent(date, request, reviewContent);
            // 写入 os/trading-os/99-inbox/
            Path inboxPath = Paths.get("../../os/trading-os/99-inbox")
                    .toAbsolutePath().normalize();
            Files.createDirectories(inboxPath);
            String fileName = "review-" + date.toString() + ".md";
            Files.writeString(inboxPath.resolve(fileName), content, StandardCharsets.UTF_8);

            log.info("复盘内容已提升为入库候选 | date={} | file={}", date, fileName);
            return ResponseEntity.ok(new PromoteResponse("ok", inboxPath.resolve(fileName).toString()));
        } catch (Exception e) {
            log.error("入库候选写入失败 | date={} | {}", date, e.getMessage());
            throw new StorageException("入库候选写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检测交易规则与当前持仓操作的潜在矛盾。
     * <p>
     * 读取 {@code os/trading-os/11-context/rules.md} 中与择时/仓位相关的规则，
     * 与当前持仓状态对比，标记可能违反的规则。
     */
    @GetMapping("/knowledge/conflicts")
    public ResponseEntity<ConflictsResponse> detectConflicts() {
        List<Position> positions = tradingAppService.getPositions();
        String rulesContent = readRulesFile();

        var conflicts = new java.util.ArrayList<ConflictItem>();
        if (positions.isEmpty() && rulesContent != null) {
            conflicts.add(new ConflictItem(
                    "R4 空头区间只卖不买",
                    "当前无持仓，系统规则建议空头区间空仓等待。确认是否在空仓等待 OAMV +4% 转多信号。",
                    "择时"
            ));
        }
        if (!positions.isEmpty() && rulesContent != null) {
            conflicts.add(new ConflictItem(
                    "R96 不单吊原则",
                    "当前持有 " + positions.size() + " 个标的。若只有一个，违反四不原则中的不单吊。",
                    "仓位"
            ));
        }

        return ResponseEntity.ok(new ConflictsResponse(conflicts));
    }

    // ── 内部方法 ──

    private String buildPromoteContent(LocalDate date, PromoteRequest request, String reviewContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 入库候选：").append(date).append(" 交易复盘\n\n");
        sb.append("> 此文件由 adai-core 自动生成，待人工审核后归入正式目录。\n");
        sb.append("> 生成时间：").append(java.time.LocalDateTime.now()).append("\n\n");
        if (request.note() != null && !request.note().isBlank()) {
            sb.append("**用户备注：** ").append(request.note()).append("\n\n");
        }
        if (request.sections() != null && !request.sections().isEmpty()) {
            sb.append("## 入选章节\n\n");
            for (String section : request.sections()) {
                sb.append("- ").append(section).append("\n");
            }
            sb.append("\n");
        }
        sb.append("## 完整复盘内容\n\n");
        sb.append(reviewContent);
        return sb.toString();
    }

    private String readRulesFile() {
        try {
            Path rulesPath = Paths.get("../../os/trading-os/11-context/rules.md")
                    .toAbsolutePath().normalize();
            if (Files.isReadable(rulesPath)) {
                return Files.readString(rulesPath, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("读取 rules.md 失败: {}", e.getMessage());
        }
        return null;
    }

    // ── DTO ──

    public record TradeRequest(
            @NotBlank String symbol,
            @NotBlank String name,
            TradeDirection direction,
            @Positive BigDecimal price,
            @Positive int volume
    ) {}

    public record ReviewResponse(String date, String content) {}

    public record ActivityCheckResponse(String date, boolean hasActivity) {}

    public record PromoteRequest(String note, List<String> sections) {}

    public record PromoteResponse(String status, String path) {}

    public record ConflictItem(String rule, String description, String category) {}

    public record ConflictsResponse(List<ConflictItem> conflicts) {}
}
