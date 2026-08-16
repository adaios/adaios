package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.application.RecordToTaskLinker;
import com.adaiadai.core.application.RecordUnderstandingService;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.context.IntentRecognizer.Intent;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RecordController — unified input entry.
 * <p>
 * For STATEMENT: saves record + ContextEngine (Identity + TagIndex + Memory + Date) → AI tags/summary.
 * For QUESTION: saves record + full ContextEngine + AI answer.
 */
@RestController
@RequestMapping("/api/v1/records")
public class RecordController {

    private static final Logger log = LoggerFactory.getLogger(RecordController.class);

    private final IntentRecognizer intentRecognizer;
    private final QuestionAppService questionAppService;
    private final RecordUnderstandingService understandingService;
    private final RecordRepository recordRepository;
    private final CardFileRepository cardRepository;
    private final MemoryService memoryService;
    private final RecordToTaskLinker recordToTaskLinker;
    private final PluginService pluginService;

    public RecordController(IntentRecognizer intentRecognizer,
                            QuestionAppService questionAppService,
                            RecordUnderstandingService understandingService,
                            RecordRepository recordRepository,
                            CardFileRepository cardRepository,
                            MemoryService memoryService,
                            RecordToTaskLinker recordToTaskLinker,
                            PluginService pluginService) {
        this.intentRecognizer = intentRecognizer;
        this.questionAppService = questionAppService;
        this.understandingService = understandingService;
        this.recordRepository = recordRepository;
        this.cardRepository = cardRepository;
        this.memoryService = memoryService;
        this.recordToTaskLinker = recordToTaskLinker;
        this.pluginService = pluginService;
    }

    @PostMapping
    public ResponseEntity<?> createRecord(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @Valid @RequestBody CreateRecordRequest request) {
        ContentRecord record = buildRecord(request);

        // cardId present AND card file exists → continuation of chat, append turn to card only
        if (request.cardId() != null && cardRepository.findById(userId, request.cardId()).isPresent()) {
            log.info("Card continuation | cardId={} | content=\"{}\"", request.cardId(), truncate(request.content(), 40));
            return handleQuestion(userId, record, request.cardId());
        }

        // New record: save content record first
        recordRepository.save(userId, record);

        try {
            // New card or first request with cardId: resolve intent once
            Intent intent = resolveIntent(userId, request, record);

            log.info("Intent | intent={} | recordId={} | cardId={} | content=\"{}\" | manual={}",
                    intent, record.id(), request.cardId(), truncate(request.content(), 40), request.intent() != null);

            if (intent == Intent.QUESTION) {
                // #181 rebuild 幂等：首问带新 cardId 时 record 已在上方落盘（intent=null），
                // 补写 intent=question——rebuild 借此排除 question 记录，避免当 log 重跑烧 AI
                // （answer() 因 cardId != null 不落盘，此标记必须在 controller 补）
                if (request.cardId() != null) {
                    ContentRecord questionMarked = new ContentRecord(
                            record.id(), record.type(), record.source(), record.title(), record.content(),
                            record.tags(), record.createdAt(), "question", null, record.domain()
                    );
                    recordRepository.save(userId, questionMarked);
                }
                return handleQuestion(userId, record, request.cardId());
            }
            return handleStatem(userId, record);
        } catch (Exception e) {
            // AI 处理失败 → 保留用户记录（不删除，数据不丢），AI 增强留空，
            // 由 RecordRetryService 每 15 分钟自动补齐 summary/tags
            log.warn("AI processing failed, record kept for retry | id={} | error={}",
                    record.id(), e.getMessage());
            ContentRecord kept = new ContentRecord(
                    record.id(), record.type(), record.source(), record.title(), record.content(),
                    List.of(), record.createdAt(), "log", "recorded", "life"
            );
            recordRepository.save(userId, kept);

            // 降级沉淀：AI 失败也入记忆（原文标 DEGRADED），AI 恢复后由重补升级为洞察
            try {
                Memory degraded = Memory.fromContentFallback(record.id(), record.content());
                memoryService.persist(userId, degraded);
                log.info("Memory degraded-persisted (AI failed) | recordId={}", record.id());
            } catch (Exception memEx) {
                log.debug("Degraded memory persist skipped: {}", memEx.getMessage());
            }

            return ResponseEntity.ok(new StatemResponse(
                    "log", record.id(), record.content(), List.of(), "recorded", "life"
            ));
        }
    }

    /**
     * Resolve intent: manual override > AI. No regex fallback, no silent log.
     */
    private Intent resolveIntent(String userId, CreateRecordRequest request, ContentRecord record) {
        // 1. Manual override
        if (request.intent() != null) {
            return switch (request.intent()) {
                case "question" -> Intent.QUESTION;
                default -> Intent.STATEMENT;
            };
        }

        // 2. AI-based — throws on failure, never silently returns STATEMENT
        // R1 AI 交互日志：意图识别无 record，挂 userId + source 让日志正确落 data/{userId}/ai-logs
        AiTraceContext.set(userId, null, null, "intent");
        return intentRecognizer.recognizeWithAi(record.content());
    }

    /**
     * STATEMENT: save record + ContextEngine (Identity + TagIndex + Memory + Date) → AI tags/summary.
     */
    private ResponseEntity<StatemResponse> handleStatem(String userId, ContentRecord record) {
        List<String> tags = Collections.emptyList();
        String summary = null;
        String domain = "life";
        AiUnderstanding understanding = null;

        try {
            // 走 ContextEngine 获取完整上下文（Identity + 标签索引 + Memory 回读 + 日期/星期）
            understanding = understandingService.composeAndUnderstand(userId, "note", record).understanding();
            tags = understanding.tags();
            summary = understanding.summary();
            // D5（RFC 20260814）：AI 判定的 domain 若属未启用插件 → 收敛 life（无插件用户不标交易/项目）
            domain = pluginService.gateDomain(userId, understanding.domain());
        } catch (Exception e) {
            log.debug("AI tagging skipped for statement: {}", e.getMessage());
        }

        // #207：AI 成功但摘要 >50 字不得落 "recorded" 哨兵——否则 RetryService.alreadyProcessed
        // 判 !"recorded".equals(summary) 把它当未处理，每 15 分钟无限重补烧 AI。真实摘要截断保存。
        if (summary == null || summary.isBlank()) {
            summary = "recorded"; // 真正失败兜底（降级语义，重补可重跑）
        } else if (summary.length() > 50) {
            summary = summary.substring(0, 50);
        }

        // #189 顺序调整：先 persist 记忆，成功后才把 summary 落盘——
        // 原顺序先写 summary 后 persist，persist 失败时记录"有 summary 无记忆"，
        // rebuild 幂等过滤（summary 非空即跳过）永久丢失该记录的重跑机会。
        // 现在 persist 失败时 record 保持 summary 空白，rebuild 靠"summary 空白"重跑。
        boolean memoryPersisted = false;
        // Persist AI understanding as memory (use full understanding with insight/patterns/preferences)
        if (understanding != null) {
            try {
                Memory memory = Memory.fromUnderstanding(record.id(), understanding);
                memoryService.persist(userId, memory);
                memoryPersisted = true;
                log.info("Memory persisted for statement | recordId={} | insight=\"{}\" | patterns={} | preferences={}",
                        record.id(), truncate(understanding.insight() != null ? understanding.insight() : summary, 40),
                        understanding.patterns() != null ? understanding.patterns().size() : 0,
                        understanding.preferences() != null ? understanding.preferences().size() : 0);
            } catch (Exception e) {
                log.debug("Memory persist skipped for statement: {}", e.getMessage());
            }
        } else {
            // AI 理解失败 → 降级沉淀：原文入记忆（标 DEGRADED），AI 恢复后由重补升级为洞察
            try {
                Memory degraded = Memory.fromContentFallback(record.id(), record.content());
                memoryService.persist(userId, degraded);
                memoryPersisted = true;
                log.info("Memory degraded-persisted (AI failed) | recordId={}", record.id());
            } catch (Exception e) {
                log.debug("Degraded memory persist skipped for statement: {}", e.getMessage());
            }
        }

        // Re-save with summary+tags+domain persisted to file
        // #188：AI 空 tags 时保留用户提交标签（record.tags()），不抹空
        ContentRecord enriched = new ContentRecord(
                record.id(), record.type(), record.source(), record.title(), record.content(),
                tags != null && !tags.isEmpty() ? tags : record.tags(),
                record.createdAt(), "log", memoryPersisted ? summary : null, domain
        );
        recordRepository.save(userId, enriched);

        // R2：记录自动转待办（通用化，RFC 20260814 D1——任何 domain 的可执行记录都转）。
        // best-effort：失败不阻塞记录返回。
        String taskTitle = summary != null && !"recorded".equals(summary) ? summary : record.title();
        recordToTaskLinker.link(userId, record.id(), "log", enriched.tags(),
                taskTitle, enriched.content(),
                understanding != null && understanding.actionable());

        return ResponseEntity.ok(new StatemResponse(
                "log", record.id(), record.content(), tags, summary, domain
        ));
    }

    /**
     * QUESTION: save record + AI answer. Create card if first turn.
     */
    private ResponseEntity<QuestionResponse> handleQuestion(String userId, ContentRecord record, String cardId) {
        // Ensure card exists: create if this is the first turn
        if (cardId != null) {
            java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            String timeStr = record.createdAt().format(timeFmt);
            Optional<CardRecord> existing = cardRepository.findById(userId, cardId);
            if (existing.isEmpty()) {
                CardRecord card = new CardRecord(
                        cardId, "conversation", "active",
                        List.of(), List.of(new CardRecord.Turn(true, record.content(), timeStr)),
                        null, record.createdAt(), record.createdAt()
                );
                cardRepository.save(userId, card);
                log.info("Card created (first turn) | cardId={}", cardId);
            } else {
                // Append user turn to existing card
                CardRecord updated = existing.get()
                        .withTurn(true, record.content(), timeStr);
                cardRepository.save(userId, updated);
                log.info("Card append | cardId={} | content=\"{}\"", cardId, truncate(record.content(), 40));
            }
        }

        QuestionAppService.AnswerResult result = questionAppService.answer(userId, record, cardId);
        log.info("Answer completed | recordId={} | cardId={}", result.recordId(), cardId);

        return ResponseEntity.ok(new QuestionResponse(
                "question",
                result.recordId(),
                result.summary(),
                result.tags(),
                result.rawResponse(),
                result.domain()
        ));
    }

    private ContentRecord buildRecord(CreateRecordRequest request) {
        String id = RecordFileRepository.generateId();
        return new ContentRecord(
                id,
                request.type() != null ? request.type() : "note",
                "user_input",
                request.content().length() > 50
                        ? request.content().substring(0, 50) + "..."
                        : request.content(),
                request.content(),
                request.tags() != null ? request.tags() : List.of(),
                LocalDateTime.now()
        );
    }

    public record CreateRecordRequest(
            @NotBlank(message = "content cannot be empty")
            @Size(min = 1, max = 10000, message = "content length must be 1-10000")
            String content,
            String type,
            List<String> tags,
            String intent,       // "log" | "question" | null (auto)
            String cardId        // optional: card ID for active conversation
    ) {}

    public record StatemResponse(
            String intent,
            String recordId,
            String content,
            List<String> tags,
            String summary,
            String domain
    ) {}

    public record QuestionResponse(
            String intent,
            String recordId,
            String summary,
            List<String> tags,
            String rawResponse,
            String domain
    ) {}

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRecord(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id) {
        log.info("Delete record | id={}", id);
        // 不管 rec_ 还是 card_ 前缀，两个仓库都清理：
        // - rec_ 文件可能也在 cards/ 目录（旧版前端经 endConversation 存过去的）
        // - card_ 文件可能也有对应的 ContentRecord
        recordRepository.deleteById(userId, id);
        cardRepository.deleteById(userId, id);
        memoryService.deleteByRecordId(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/domain")
    public ResponseEntity<Void> updateDomain(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id, @RequestBody Map<String, String> body) {
        String domain = body.get("domain");
        if (domain == null || !List.of("life", "trading", "project").contains(domain)) {
            return ResponseEntity.badRequest().build();
        }
        // REVIEW P1-W13（B41）：手动写入口与 AI 判定同口径走 gateDomain——
        // 无插件用户不得手动把记录标为 trading/project
        String gated = pluginService.gateDomain(userId, domain);
        log.info("Update domain | id={} | domain={} → {} | userId={}", id, domain, gated, userId);
        recordRepository.updateDomain(userId, id, gated);
        return ResponseEntity.noContent().build();
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
