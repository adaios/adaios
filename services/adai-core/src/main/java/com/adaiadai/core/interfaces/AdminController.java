package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.RecordFlowAppService;
import com.adaiadai.core.application.RecordRetryService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.infrastructure.ai.interaction.AiInteractionLog;
import com.adaiadai.core.infrastructure.ai.interaction.AiInteractionLogger;
import com.adaiadai.core.infrastructure.storage.CardMigrationService;
import com.adaiadai.core.infrastructure.storage.CardMigrationService.CleanupResult;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AdminController — adai-admin 系统级端点（data/ 文件树 + os/ 知识资产 + 维护操作）。
 * <p>
 * 这些端点读取系统级路径（data/ 全部用户层、os/ 知识库）或执行维护操作
 * （记忆重补/重建/修正、卡片清理、交易规则冲突检测），不走 X-User-Id 用户层，
 * 仅供 adai-admin 管理端使用（v1.0.0）。路径一律 {@code normalize + startsWith} 校验防目录遍历。
 * <p>
 * REVIEW P-be-01（安全）：维护类端点原本是 per-user 端点（仅靠 X-User-Id 隔离），
 * 任何客户端伪造 X-User-Id 即可对任意用户执行重补/重建/修正/清理——已整体迁入
 * {@code /api/v1/admin/**}，由 {@link com.adaiadai.core.infrastructure.security.AdminAuthInterceptor}
 * 强制 X-Admin-Token；目标用户通过 {@code userId} 查询参数显式指定（默认 default）。
 *
 * <pre>
 * GET    /api/v1/admin/files?path=                     → data/ 目录条目列表
 * GET    /api/v1/admin/files/content?path=             → data/ 文件内容
 * GET    /api/v1/admin/knowledge?domain=trading-os&path=  → os/{domain}/ 目录条目列表
 * GET    /api/v1/admin/knowledge/content?path=         → os/ 文件内容
 * POST   /api/v1/admin/records/retry                   → 手动触发记忆重补
 * POST   /api/v1/admin/memory/rebuild?date=            → 重建记忆（重跑 AI）
 * PATCH  /api/v1/admin/memory/{id}                     → 手动修正记忆
 * POST   /api/v1/admin/cards/cleanup                   → 清理卡片冗余记录
 * GET    /api/v1/admin/trading/knowledge/conflicts     → 持仓 vs 规则冲突检测
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** 文件内容预览上限 512KB，避免一次加载大文件。 */
    private static final long MAX_FILE_SIZE = 512 * 1024;

    /** AI 日志单页条数上限（REVIEW #210：防单次拉全量明文历史）。 */
    private static final int MAX_LOG_PAGE_SIZE = 500;

    private static final Set<String> KNOWN_DOMAINS = Set.of("trading-os", "life-os", "project-os");

    private final Path dataRoot;
    private final Path osRoot;
    private final AiInteractionLogger aiInteractionLogger;
    private final MemoryService memoryService;
    private final RecordRepository recordRepository;
    private final RecordFlowAppService recordFlowAppService;
    private final RecordRetryService recordRetryService;
    private final CardMigrationService cardMigrationService;
    private final TradingAppService tradingAppService;

    public AdminController(@Value("${adai.storage.base-path:../../data}") String dataBasePath,
                           @Value("${adai.os-base-path:../../os}") String osBasePath,
                           AiInteractionLogger aiInteractionLogger,
                           MemoryService memoryService,
                           RecordRepository recordRepository,
                           RecordFlowAppService recordFlowAppService,
                           RecordRetryService recordRetryService,
                           CardMigrationService cardMigrationService,
                           TradingAppService tradingAppService) {
        this.dataRoot = Paths.get(dataBasePath).toAbsolutePath().normalize();
        this.osRoot = Paths.get(osBasePath).toAbsolutePath().normalize();
        this.aiInteractionLogger = aiInteractionLogger;
        this.memoryService = memoryService;
        this.recordRepository = recordRepository;
        this.recordFlowAppService = recordFlowAppService;
        this.recordRetryService = recordRetryService;
        this.cardMigrationService = cardMigrationService;
        this.tradingAppService = tradingAppService;
    }

    // ── data/ 文件树浏览 ──

    @GetMapping("/files")
    public ResponseEntity<?> listFiles(@RequestParam(defaultValue = "") String path) {
        try {
            Path dir = safeResolve(dataRoot, path);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return ResponseEntity.notFound().build();
            }
            try (Stream<Path> children = Files.list(dir)) {
                List<Map<String, Object>> entries = children
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .map(p -> entry(dataRoot, p))
                        .collect(Collectors.toList());
                return ResponseEntity.ok(entries);
            }
        } catch (Exception e) {
            log.warn("文件树浏览失败 | path={} | {}", path, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/files/content")
    public ResponseEntity<?> getFileContent(@RequestParam String path) {
        return readContent(dataRoot, path);
    }

    // ── os/ 知识资产浏览 ──

    @GetMapping("/knowledge")
    public ResponseEntity<?> listKnowledge(@RequestParam String domain,
                                           @RequestParam(defaultValue = "") String path) {
        if (!KNOWN_DOMAINS.contains(domain)) {
            return ResponseEntity.badRequest().body(Map.of("error", "domain 仅允许 " + KNOWN_DOMAINS));
        }
        // domain 仅做白名单校验；path 决定浏览位置（相对 os/ 根，浏览 os/trading-os/ 传 path=trading-os）
        try {
            Path dir = safeResolve(osRoot, path);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return ResponseEntity.notFound().build();
            }
            try (Stream<Path> children = Files.list(dir)) {
                List<Map<String, Object>> entries = children
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .map(p -> entry(osRoot, p))
                        .collect(Collectors.toList());
                return ResponseEntity.ok(entries);
            }
        } catch (Exception e) {
            log.warn("知识浏览失败 | domain={} | path={} | {}", domain, path, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/knowledge/content")
    public ResponseEntity<?> getKnowledgeContent(@RequestParam String path) {
        return readContent(osRoot, path);
    }

    // ── AI 交互日志（R1）──

    /**
     * 读取某天的 AI 交互日志（JSONL 解析后的结构化条目）。
     * <p>
     * 供 adai-admin 管理端查看"提示词怎么组装的"；数据源 {@code data/{userId}/ai-logs/}。
     *
     * <pre>
     * GET /api/v1/admin/ai-logs?userId=adai&date=2026-08-12&page=1&size=200  → 当日日志条目列表
     * </pre>
     *
     * REVIEW #210 读取治理：日期早于保留期（{@code adai.ai-log.retention-days}，默认 30 天）
     * 拒绝查询（日志已清理，防扫任意历史）；分页 + size 上限防单次拉全量。
     *
     * @param userId 用户 ID（默认 adai，多账号下可指定）
     * @param date   日期 YYYY-MM-DD（默认今天）
     * @param page   页码（从 1 起，默认 1）
     * @param size   每页条数（默认 200，上限 500）
     */
    @GetMapping("/ai-logs")
    public ResponseEntity<?> getAiLogs(@RequestParam(defaultValue = "adai") String userId,
                                       @RequestParam(defaultValue = "") String date,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "200") int size) {
        if (!userId.matches("[a-zA-Z0-9_-]+")) {
            return ResponseEntity.badRequest().body(Map.of("error", "非法 userId: " + userId));
        }
        LocalDate day;
        if (date.isBlank()) {
            day = LocalDate.now();
        } else {
            try {
                day = LocalDate.parse(date);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "date 需为 YYYY-MM-DD"));
            }
        }
        // #210：拒绝查询已过保留期的日志（早于 oldestRetainableDate 的已被清理）
        if (day.isBefore(aiInteractionLogger.oldestRetainableDate())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "date 早于日志保留期（" + aiInteractionLogger.retentionDays() + " 天），已清理不可查"));
        }
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), MAX_LOG_PAGE_SIZE);
        int offset = (p - 1) * s;
        List<AiInteractionLog> logs = aiInteractionLogger.readDay(userId, day, offset, s);
        int total = aiInteractionLogger.countDay(userId, day);
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "date", day.toString(),
                "page", p,
                "size", s,
                "total", total,
                "count", logs.size(),
                "logs", logs));
    }

    // ── 维护操作（REVIEW P-be-01：自 per-user 路径迁入，受 AdminAuthInterceptor 保护）──
    //
    // 这些端点原本位于 /records、/memory、/cards、/trading 下，仅靠 X-User-Id 隔离，
    // 伪造 X-User-Id 即可对任意用户重补/重建/修正/清理——现整体迁入 /api/v1/admin/**。
    // 目标用户由 userId 查询参数显式指定（默认 default，与 GET /ai-logs 同约定）。

    /**
     * 手动触发记忆重补（admin 维护）。
     * <p>
     * POST /api/v1/admin/records/retry?userId=adai — 对指定用户执行
     * {@link RecordRetryService#retryUnprocessed(String)} 补完缺失的 AI 摘要/标签。
     */
    @PostMapping("/records/retry")
    public ResponseEntity<Map<String, Object>> triggerRetry(
            @RequestParam(defaultValue = "default") String userId) {
        long before = memoryService.count(userId);
        recordRetryService.retryUnprocessed(userId);
        long after = memoryService.count(userId);
        long newMemories = after - before;
        log.info("手动触发重补完成 | userId={} | 记忆: {} → {} ({})", userId, before, after, newMemories);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "memoriesBefore", before,
                "memoriesAfter", after,
                "newMemories", newMemories
        ));
    }

    /**
     * 重建记忆（admin 维护）：遍历没有记忆的历史记录，逐个生成 AI 摘要+标签并沉淀为记忆。
     * <p>
     * POST /api/v1/admin/memory/rebuild?date=2026-07-21&userId=adai
     * POST /api/v1/admin/memory/rebuild?userId=adai（重建该用户全部）
     */
    @PostMapping("/memory/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildMemory(
            @RequestParam(defaultValue = "default") String userId,
            @RequestParam(required = false) String date) {
        List<ContentRecord> allRecords = recordRepository.findAll(userId);

        // 过滤日期
        LocalDate filterDate = date != null ? LocalDate.parse(date) : null;
        List<ContentRecord> targetRecords = allRecords.stream()
                .filter(r -> filterDate == null || r.createdAt().toLocalDate().equals(filterDate))
                .filter(r -> r.intent() == null || "log".equals(r.intent()))
                // #144 幂等：已处理（有持久化 summary）且无降级记忆的记录跳过——
                //   fact-only 记忆被 Phase 5 跳过时无真实记忆痕迹，但也不该重跑烧 AI；
                //   降级记忆（DEGRADED）仍需重跑以升级为洞察；未处理（summary 空白）仍重建。
                //   #189 在写入层修复：persist 失败时 summary 留空（handleStatem），
                //   这里"summary 空白"自然触发重跑，无需额外判据。
                .filter(r -> r.summary() == null || r.summary().isBlank()
                        || memoryService.hasDegradedMemory(userId, r.id()))
                .toList();

        log.info("记忆重建开始 | userId={} | 目标日期={} | 待处理记录={}条",
                userId, date != null ? date : "全部", targetRecords.size());

        int success = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (ContentRecord record : targetRecords) {
            try {
                var result = recordFlowAppService.process(userId, record);
                // #207：未处理（summary 空白）或降级（"recorded"）记录处理后回写真实摘要——
                // 原逻辑只在 summary 空白时回写，降级记录升级后仍留 "recorded"，retry 会再补跑一次；
                // 且长摘要 >50 不得落 "recorded" 哨兵（RetryService 判 !"recorded" 会无限重补），截断保存。
                String oldSummary = record.summary();
                if (oldSummary == null || oldSummary.isBlank() || "recorded".equals(oldSummary)) {
                    String s = result.understanding() != null ? result.understanding().summary() : null;
                    String marker;
                    if (s == null || s.isBlank()) {
                        marker = "recorded";
                    } else if (s.length() > 50) {
                        marker = s.substring(0, 50);
                    } else {
                        marker = s;
                    }
                    recordRepository.save(userId, new ContentRecord(
                            record.id(), record.type(), record.source(), record.title(), record.content(),
                            record.tags(), record.createdAt(),
                            record.intent() != null ? record.intent() : "log",
                            marker, record.domain()
                    ));
                }
                success++;
                log.info("记忆重建成功 | recordId={} | ({}/{})", record.id(), success + failed, targetRecords.size());
            } catch (Exception e) {
                failed++;
                errors.add(record.id() + ": " + e.getMessage());
                log.warn("记忆重建失败 | recordId={} | {}", record.id(), e.getMessage());
            }
        }

        log.info("记忆重建完成 | userId={} | 成功={} | 失败={}", userId, success, failed);

        // 记忆进化 Phase 4：随 rebuild 清理过期条目（superseded 超 60 天 / actionable 完成超 30 天）
        memoryService.cleanup(userId);

        return ResponseEntity.ok(Map.of(
                "success", success,
                "failed", failed,
                "total", targetRecords.size(),
                "errors", errors
        ));
    }

    /**
     * 手动修正记忆（adai-admin 数据管理）：更新 kind/summary/tags/actionable/suggestion。
     * <p>
     * PATCH /api/v1/admin/memory/{id}?userId=adai — 任一字段缺省表示保持原值；找不到返回 404。
     */
    @PatchMapping("/memory/{id}")
    public ResponseEntity<?> updateMemory(
            @PathVariable String id,
            @RequestParam(defaultValue = "default") String userId,
            @RequestBody MemoryUpdateRequest request) {
        boolean updated = memoryService.update(userId, id,
                request.kind(), request.summary(), request.tags(),
                request.actionable(), request.suggestion());
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    public record MemoryUpdateRequest(
            String kind, String summary, List<String> tags,
            Boolean actionable, String suggestion) {}

    /**
     * 清理卡片冗余记录（admin 维护）：删除卡片对话对应的冗余 ContentRecord。
     * <p>
     * POST /api/v1/admin/cards/cleanup?userId=adai
     */
    @PostMapping("/cards/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupRecords(
            @RequestParam(defaultValue = "default") String userId) {
        log.info("清理重复记录开始 | userId={}", userId);
        CleanupResult result = cardMigrationService.cleanupDuplicateRecords(userId);

        log.info("清理完成 | 删除={}条", result.deleted());

        return ResponseEntity.ok(Map.of(
                "deleted", result.deleted(),
                "deletedFiles", result.deletedFiles(),
                "skippedFiles", result.skippedFiles()
        ));
    }

    /**
     * 检测交易规则与当前持仓操作的潜在矛盾（admin 维护）。
     * <p>
     * 读取 {@code os/trading-os/11-context/rules.md} 中的真实规则（#23 修复：不再硬编码规则名），
     * 与当前持仓状态对比，标记可能违反的规则。
     * <ul>
     *   <li>无持仓 → 引用真实规则 R119 空仓也是交易策略 / R4 空头区间只卖不买</li>
     *   <li>仅持 1 个标的 → 引用真实规则 R96 四不原则（单吊风险）</li>
     * </ul>
     * GET /api/v1/admin/trading/knowledge/conflicts?userId=adai
     */
    @GetMapping("/trading/knowledge/conflicts")
    public ResponseEntity<ConflictsResponse> detectConflicts(
            @RequestParam(defaultValue = "default") String userId) {
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

    public record ConflictItem(String rule, String description, String category) {}

    public record ConflictsResponse(List<ConflictItem> conflicts) {}

    /** 从 rules.md 解析出的真实规则条目。 */
    private record RuleInfo(int number, String title, String detail) {}

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

    // ── helpers ──

    /** 解析路径并校验在根内（防目录遍历）。 */
    private Path safeResolve(Path root, String path) {
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法路径: " + path);
        }
        return resolved;
    }

    private String relPath(Path root, Path p) {
        return root.relativize(p).normalize().toString().replace('\\', '/');
    }

    private Map<String, Object> entry(Path root, Path p) {
        boolean isDir = Files.isDirectory(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getFileName().toString());
        m.put("path", relPath(root, p));
        m.put("isDir", isDir);
        if (!isDir) {
            try {
                m.put("size", Files.size(p));
            } catch (IOException e) {
                // ignore
            }
        }
        return m;
    }

    private ResponseEntity<?> readContent(Path root, String path) {
        try {
            Path file = safeResolve(root, path);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return ResponseEntity.notFound().build();
            }
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                return ResponseEntity.badRequest().body(Map.of("error", "文件过大（>512KB），不支持预览: " + path));
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of(
                    "path", relPath(root, file),
                    "size", size,
                    "content", content));
        } catch (Exception e) {
            log.warn("读取内容失败 | path={} | {}", path, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
