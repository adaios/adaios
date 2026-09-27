package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.ConversationText;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ConversationController — conversation lifecycle endpoints.
 * <p>
 * {@code POST /api/v1/conversations/end} — summarize a conversation,
 * save as a record, return summary + tags.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    /** 条带锁数量：固定 32 条，避免按 cardId 无界累积（同交易锁收敛口径，2026-09-09 批）。 */
    private static final int CARD_LOCK_STRIPES = 32;

    private final AiClient aiClient;
    private final RecordRepository recordRepository;
    private final CardFileRepository cardRepository;
    private final MemoryService memoryService;
    private final Object[] cardLocks = new Object[CARD_LOCK_STRIPES];

    public ConversationController(AiClient aiClient, RecordRepository recordRepository,
                                  CardFileRepository cardRepository,
                                  MemoryService memoryService) {
        this.aiClient = aiClient;
        this.recordRepository = recordRepository;
        this.cardRepository = cardRepository;
        this.memoryService = memoryService;
        for (int i = 0; i < CARD_LOCK_STRIPES; i++) {
            cardLocks[i] = new Object();
        }
    }

    @PostMapping("/end")
    public ResponseEntity<EndConversationResponse> endConversation(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody EndConversationRequest request) {

        // REVIEW P1-对话1：同卡幂等——cardId 非空时，把「幂等判定 + AI 总结 + 落盘 + 卡片回写」
        // 整段收进按 (userId|cardId) 的条带锁。超时重发 / 双端并发再次 end 只会落一条 conversation，
        // 且不会重复调用 AI（检查-再动作竞态，pitfalls「并发花钱」）。
        if (request.cardId() == null || request.cardId().isBlank()) {
            return doEnd(userId, request);
        }
        synchronized (lockFor(userId, request.cardId())) {
            Optional<CardRecord> existing = cardRepository.findById(userId, request.cardId());
            // 对抗审查 P1-A（2026-09-26）：命中幂等键**必须同时比对内容指纹**——「在已结束的卡上
            // 继续聊几轮再点结束」不是重试：只比 recordId 会返回上一次的总结，且新轮次既不落
            // record 也不进记忆（用户会说「我刚说的你没记住」）。指纹不一致 → 正常走 doEnd 新落一条。
            Integer currentHash = request.turns().hashCode();
            if (existing.isPresent()
                    && existing.get().conversationRecordId() != null
                    && sameContentAsRecorded(existing.get(), request.turns(), currentHash)) {
                String recordId = existing.get().conversationRecordId();
                log.info("Conversation end 幂等命中（同卡已落盘，不重复记录） | userId={} | cardId={} | recordId={}",
                        userId, request.cardId(), recordId);
                List<String> tags = recordRepository.findById(userId, recordId)
                        .map(ContentRecord::tags)
                        .orElse(existing.get().tags());
                String summary = existing.get().summary() != null ? existing.get().summary() : "";
                return ResponseEntity.ok(new EndConversationResponse(recordId, summary, tags));
            }
            return doEnd(userId, request);
        }
    }

    /** 条带锁：同一 (userId|cardId) 恒落同一把锁（固定 32 条，无界累积风险为零）。 */
    private Object lockFor(String userId, String cardId) {
        return cardLocks[Math.floorMod((userId + "|" + cardId).hashCode(), CARD_LOCK_STRIPES)];
    }

    /**
     * 本次请求与卡片上记录的「那一段对话」是不是同一段（对抗复核 P2-3 / P2-4，2026-09-26）。
     * <p>
     * 优先级：① 卡片有指纹（`conversationTurnsHash`）→ 比指纹；② **缺指纹**（旧卡，或被
     * `CardMigrationService` / `RecordRetryService` 重写时抹掉键的卡）→ 退化为**比对卡片 turns 的
     * 文本序列**（等价指纹、无需持久化）；③ 两者都比不出来 → 才算「内容变了」。
     * <p>
     * 为什么缺指纹时不直接新落（原注释把方向写反了）：`Integer.equals(null)` 恒 false → 无指纹的卡
     * **永不命中幂等** → 重试会重复落盘 + 重复调 AI + 重复写记忆。对**幂等/花钱**而言，「宁可复用」
     * 才是安全方向（代价只是可能少生成一次新总结）。
     */
    private static boolean sameContentAsRecorded(CardRecord card, List<String> requestTurns, Integer requestHash) {
        Integer cardHash = card.conversationTurnsHash();
        if (cardHash != null) return cardHash.equals(requestHash);
        List<CardRecord.Turn> cardTurns = card.turns();
        if (cardTurns == null || cardTurns.isEmpty()) return false; // 空 turns（占位/预建卡）→ 不比，走新落
        return sameTurnsText(cardTurns, requestTurns);
    }

    /** 卡片 turns 与本次请求 turns 的文本序列是否一致（缺指纹时的等价判据）。 */
    private static boolean sameTurnsText(List<CardRecord.Turn> cardTurns, List<String> requestTurns) {
        if (cardTurns == null || requestTurns == null || cardTurns.size() != requestTurns.size()) return false;
        for (int i = 0; i < cardTurns.size(); i++) {
            String a = cardTurns.get(i).text() == null ? "" : cardTurns.get(i).text().strip();
            String b = requestTurns.get(i) == null ? "" : requestTurns.get(i).strip();
            if (!a.equals(b)) return false;
        }
        return true;
    }

    private ResponseEntity<EndConversationResponse> doEnd(String userId, EndConversationRequest request) {

        log.info("Conversation end | userId={} | turns={} | cardId={}",
                userId, request.turns().size(), request.cardId());

        // Build prompt from all turns
        String turnText = ConversationText.fromAlternating(request.turns());
        String prompt = """
                客观总结这段对话（不超过40字），避免人称代词。
                输出 JSON（不要包裹 markdown 代码块）：
                {
                  "summary": "对话总结",
                  "tags": ["标签1", "标签2"],
                  "sentiment": "neutral",
                  "actionable": false,
                  "actionSuggestion": null
                }

                对话内容：
                %s
                """.formatted(turnText);

        var contextPackage = com.adaiadai.core.kernel.context.engine.ContextPackage.simple(
                "conversation", "",
                "对话总结", turnText, List.of(), prompt
        );

        // R1 AI 交互日志：挂载卡片锚点
        AiTraceContext.set(userId, null, request.cardId(), "conversation");

        // AI 理解失败降级：不 500，用对话原文兜底（与 RecordController 降级模式一致）
        AiUnderstanding understanding;
        try {
            understanding = aiClient.understand(contextPackage);
        } catch (Exception e) {
            log.warn("Conversation end AI 总结失败，降级处理 | cardId={} | err={}",
                    request.cardId(), e.getMessage());
            understanding = null;
        }

        // Save as a record（summary 兜底：AI 成功但 summary 为空也走原文）
        String id = RecordFileRepository.generateId();
        String summaryText;
        List<String> tags;
        if (understanding != null && understanding.summary() != null && !understanding.summary().isBlank()) {
            summaryText = understanding.summary();
            tags = understanding.tags() != null ? understanding.tags() : List.of();
        } else {
            summaryText = fallbackSummary(turnText);
            tags = List.of();
        }

        // E-A 写侧保真（memory-fidelity.md，2026-09-15 用户拍板 P4①）：
        // 正文 = 对话原文（"我：/你："交替），AI 转述降入 summary 字段；source 由
        // ai_summary 改为 user_input —— 作为「本条正文是原话」的新旧可分标记
        // （存量 source=ai_summary 且正文为转述，E-C 存量迁移据此辨识，无需逐条判断）。
        String originalText = turnText.isBlank() ? summaryText : turnText;
        ContentRecord record = new ContentRecord(
                id, "conversation", "user_input",
                truncate(originalText, 50),
                originalText,
                tags,
                LocalDateTime.now(),
                null,          // intent：conversation 不参与 #144 的 question 排除逻辑
                summaryText,   // summary = AI 转述（原先占据正文位置的内容）
                "life"         // domain：本批不扩 domain 透传（RFC 20260822 P1 另行拍板）
        );
        recordRepository.save(userId, record);

        // Update card file with summary + ended status + 幂等键（P1-对话1：同卡再次 end 直接返回该记录）
        if (request.cardId() != null) {
            // 后端审查 P1-2（2026-09-26）：写回前**重新读一次**卡片——append 侧（用户继续发消息走
            // QuestionAppService / MediaRecordAppService 的 findById→withTurn→save）没有与本锁互斥，
            // 若拿「AI 调用之前」读到的旧 turns 写回，会静默覆盖这几轮发言（丢轮次）。以最新 turns
            // 为基底，只改状态 / 摘要 / 幂等键。
            Optional<CardRecord> latest = cardRepository.findById(userId, request.cardId());
            if (latest.isPresent()) {
                // 对抗复核 P2-4：卡片 turns 为空（占位卡 / 预建卡）→ 以**本次请求**为准写指纹；
                // 非空且与请求一致 → 写；非空但不一致（并发 append 落在两次读之间）→ **不写**——
                // 宁可下次重落，也不留 turns(N+k)+hash(N)（那会让 k 轮永远进不了记录/记忆）
                List<CardRecord.Turn> latestTurns = latest.get().turns();
                boolean turnsAligned = latestTurns == null || latestTurns.isEmpty()
                        || sameTurnsText(latestTurns, request.turns());
                Integer hashToPersist = turnsAligned ? request.turns().hashCode() : null;
                CardRecord updated = latest.get()
                        .withStatus("ended")
                        .withSummary(summaryText)
                        .withConversation(id, hashToPersist);
                cardRepository.save(userId, updated);
                log.info("Card updated | cardId={} | status=ended | conversationRecordId={}", request.cardId(), id);
            }
        }

        // 沉淀记忆：AI 成功 → 洞察记忆；失败 → 原文降级（标 DEGRADED，AI 恢复后由重补升级）
        if (understanding != null) {
            Memory memory = Memory.fromUnderstanding(record.id(), understanding);
            memoryService.persist(userId, memory);
        } else {
            try {
                Memory degraded = Memory.fromContentFallback(record.id(), turnText);
                memoryService.persist(userId, degraded);
                log.info("Memory degraded-persisted (AI failed) | recordId={}", id);
            } catch (Exception e) {
                log.debug("Degraded memory persist skipped: {}", e.getMessage());
            }
        }

        log.info("Conversation summary saved | recordId={} | tags={} | cardId={}", id, tags, request.cardId());

        return ResponseEntity.ok(new EndConversationResponse(id, summaryText, tags));
    }

    /** AI 总结失败时的兜底 summary：对话原文（截断 50 字），保证 end 永不因 AI 异常返回 500。 */
    private String fallbackSummary(String turnText) {
        if (turnText == null || turnText.isBlank()) return "对话已结束";
        String clean = turnText.strip();
        return clean.length() > 50 ? clean.substring(0, 50) + "…" : clean;
    }

    /** 标题用的单行截断（title 是派生字段：落盘不存，读回由正文首行重建）。 */
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    public record EndConversationRequest(
            List<String> turns,
            String cardId
    ) {
        public EndConversationRequest { turns = turns != null ? turns : List.of(); }
    }

    public record EndConversationResponse(
            String recordId,
            String summary,
            List<String> tags
    ) {}
}
