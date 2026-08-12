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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public ResponseEntity<List<Position>> getPositions(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(tradingAppService.getPositions(userId));
    }

    /**
     * 查询投资组合快照。
     */
    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioSnapshot> getPortfolio(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(tradingAppService.getPortfolioSnapshot(userId));
    }

    /**
     * 记录一笔交易（买入/卖出）。
     */
    @PostMapping("/trades")
    public ResponseEntity<List<Position>> recordTrade(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody TradeRequest request) {
        List<Position> updated = tradingAppService.recordTrade(
                userId, request.symbol(), request.name(),
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
    public ResponseEntity<ReviewResponse> generateReview(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        String content = reviewAppService.generateReview(userId, date);
        return ResponseEntity.ok(new ReviewResponse(date.toString(), content));
    }

    /**
     * 获取指定日期的复盘笔记。
     */
    @GetMapping("/review")
    public ResponseEntity<ReviewResponse> getReview(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        String content = reviewAppService.getReview(userId, date);
        if (content == null || content.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ReviewResponse(date.toString(), content));
    }

    /**
     * 列出所有复盘日期。
     */
    @GetMapping("/reviews")
    public ResponseEntity<List<LocalDate>> listReviews(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(reviewAppService.listReviews(userId));
    }

    /**
     * 检测指定日期是否有交易活动。
     */
    @GetMapping("/has-activity")
    public ResponseEntity<ActivityCheckResponse> hasTradingActivity(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDate).now()}") LocalDate date) {
        boolean hasActivity = reviewAppService.hasTradingActivity(userId, date);
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
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable LocalDate date,
            @RequestBody PromoteRequest request) {
        String reviewContent = reviewAppService.getReview(userId, date);
        if (reviewContent == null || reviewContent.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        try {
            // 构建入库候选内容
            String content = buildPromoteContent(date, request, reviewContent);
            // #203：候选文件尾保证换行（markdown 文件约定 EOF newline）
            if (!content.endsWith("\n")) content += "\n";
            // 写入 os/trading-os/99-inbox/
            Path inboxPath = Paths.get("../../os/trading-os/99-inbox")
                    .toAbsolutePath().normalize();
            Files.createDirectories(inboxPath);
            // #211：文件名符合 trading-os 全流水线约定 `YYYY-MM-DD_主题.md`
            // （原硬编码 `review-{date}.md` 不符，已入库的候选文件一并按此改名）
            String fileName = date.toString() + "_交易复盘.md";
            Files.writeString(inboxPath.resolve(fileName), content, StandardCharsets.UTF_8);

            log.info("复盘内容已提升为入库候选 | date={} | file={}", date, fileName);
            // #178：提示入库候选不会自动融入 AI context——需在 trading-os 工作流审核融合后重建 11-context
            String message = "已写入入库候选。该内容不会自动进入 AI 上下文：请在交易知识库工作流（os/trading-os）审核后归入正式目录，并在收敛时重建 11-context。";
            return ResponseEntity.ok(new PromoteResponse("ok", inboxPath.resolve(fileName).toString(), message));
        } catch (Exception e) {
            log.error("入库候选写入失败 | date={} | {}", date, e.getMessage());
            throw new StorageException("入库候选写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检测交易规则与当前持仓操作的潜在矛盾。
     * <p>
     * 读取 {@code os/trading-os/11-context/rules.md} 中的真实规则（#23 修复：不再硬编码规则名），
     * 与当前持仓状态对比，标记可能违反的规则。
     * <ul>
     *   <li>无持仓 → 引用真实规则 R119 空仓也是交易策略 / R4 空头区间只卖不买</li>
     *   <li>仅持 1 个标的 → 引用真实规则 R96 四不原则（单吊风险）</li>
     * </ul>
     */
    @GetMapping("/knowledge/conflicts")
    public ResponseEntity<ConflictsResponse> detectConflicts(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        List<Position> positions = tradingAppService.getPositions(userId);
        List<RuleInfo> rules = parseRules(readRulesFile());

        var conflicts = new ArrayList<ConflictItem>();

        if (rules.isEmpty()) {
            // rules.md 不可读/无规则：不硬编码，返回空（诚实优于写死字符串）
            log.warn("detectConflicts: rules.md 不可读或为空，跳过规则对照");
            return ResponseEntity.ok(new ConflictsResponse(conflicts));
        }

        // 空仓检查 → 引用真实规则（优先 R119 空仓也是交易策略，回退 R4 空头区间只卖不买）
        if (positions.isEmpty()) {
            findRule(rules, "空仓也是交易策略")
                    .or(() -> findRule(rules, "只卖不买"))
                    .ifPresent(rule -> conflicts.add(new ConflictItem(
                            "R" + rule.number() + " " + rule.title(),
                            "当前无持仓。规则：" + rule.detail() + "。确认当前空仓是否符合择时信号（活跃市值绿柱下降期空仓 = 正确执行 R4）。",
                            "择时"
                    )));
        }

        // 单吊检查（仅持 1 个标的）→ 引用真实规则 R96 四不原则
        if (positions.size() == 1) {
            findRule(rules, "四不原则").ifPresent(rule -> conflicts.add(new ConflictItem(
                    "R" + rule.number() + " " + rule.title(),
                    "当前仅持有 1 个标的（" + positions.get(0).name() + "）。规则：" + rule.detail() + "。若未分仓，检查是否违反四不原则。",
                    "仓位"
            )));
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
        // #184：promote 内容脱敏——复盘含真实持仓（股数/市值/成本/现价/现金），
        // 入库候选进 git 追踪的 os/ 目录，必须替换为占位符。
        // 知识价值在 R/E 规则引用与仓位结构讨论，不在具体持仓数字。
        // 标的名保留（公开信息 + 规则引用需要标的语境）；大盘指数等公开行情不误伤。
        sb.append(sanitizeReviewContent(reviewContent));
        return sb.toString();
    }

    /**
     * #184：复盘内容脱敏——替换真实持仓数字为占位符。
     * <p>
     * 关键词引导的正则（持有/市值/成本/现价/现金余额），只命中持仓数字，
     * 不误伤大盘指数等公开行情（不含这些关键词）。标的名保留（公开信息）。
     */
    static String sanitizeReviewContent(String content) {
        if (content == null || content.isBlank()) return content;
        String s = content;
        // 持仓数量：持有100股 → 持有N股
        s = s.replaceAll("持有\\s*\\d+(?:\\.\\d+)?\\s*股", "持有N股");
        // 市值：市值14万 → 市值（已脱敏）
        s = s.replaceAll("市值\\s*[\\d.]+\\s*(?:万|千|亿)?", "市值（已脱敏）");
        // 现金余额：现金余额为零 → 现金余额（已脱敏）
        s = s.replaceAll("现金余额[^，。；\\n]*", "现金余额（已脱敏）");
        // 成本/现价/止损价：成本1400现价1400 → 成本（已脱敏）现价（已脱敏）
        s = s.replaceAll("(成本|现价|止损位|止损价)\\s*[\\d.]+", "$1（已脱敏）");
        return s;
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

    /**
     * 解析 rules.md 规则条目：{@code **R{n} 标题** + > 描述}。
     * 标题与描述取自真实规则内容，供 conflict 检测引用（不再硬编码）。
     */
    private List<RuleInfo> parseRules(String content) {
        if (content == null || content.isBlank()) return List.of();
        Pattern pattern = Pattern.compile(
                "\\*\\*R(\\d+)\\s+([^*\\n]+?)\\s*\\*\\*(?:\\n>\\s*([^\\n]+))?");
        Matcher matcher = pattern.matcher(content);

        List<RuleInfo> rules = new ArrayList<>();
        while (matcher.find()) {
            rules.add(new RuleInfo(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2).strip(),
                    matcher.group(3) != null ? matcher.group(3).strip() : ""
            ));
        }
        return rules;
    }

    /**
     * 按关键词在规则列表中查找第一条匹配规则（标题 + 描述）。
     */
    private Optional<RuleInfo> findRule(List<RuleInfo> rules, String keyword) {
        return rules.stream()
                .filter(r -> (r.title() + " " + r.detail()).contains(keyword))
                .findFirst();
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

    public record PromoteResponse(String status, String path, String message) {}

    public record ConflictItem(String rule, String description, String category) {}

    public record ConflictsResponse(List<ConflictItem> conflicts) {}

    /** 从 rules.md 解析出的真实规则条目。 */
    private record RuleInfo(int number, String title, String detail) {}
}
