package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.application.ActionReviewService;
import com.adaiadai.core.infrastructure.ai.llm.LlmResponseParser;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.CardLockRegistry;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.ConversationText;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.todo.Todo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>
 * 并发（REVIEW P2-工程13，2026-10-05）：卡片写回与 append 侧（QuestionAppService /
 * MediaRecordAppService）共用 {@link CardLockRegistry} 的同一把 per-card 锁；锁顺序见该类注释。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final AiClient aiClient;
    private final RecordRepository recordRepository;
    private final CardFileRepository cardRepository;
    private final MemoryService memoryService;
    /** 共享 per-card 锁池（append 侧注入的是同一个 Spring 单例；P2-工程13）。 */
    private final CardLockRegistry cardLockRegistry;
    /**
     * REVIEW P2-交易73：对话里给出的动作 → 既有待办 + 记忆的待行动事项。
     * <p>
     * 挂在「对话结束」而不是「记录保存」的原因：阿呆给出动作的地方是**对话**（assistant 侧），
     * 而 {@code RecordToTodoLinker} 只看用户自己写的记录（intent=log + actionable）。
     * 对话结束是这场对话**唯一的收口点**，也是动作最后一次被完整看见的时刻。
     */
    private final ActionReviewService actionReviewService;

    /** Spring 主构造：注入共享锁池——与 QuestionAppService / MediaRecordAppService 是同一实例。 */
    @Autowired
    public ConversationController(AiClient aiClient, RecordRepository recordRepository,
                                  CardFileRepository cardRepository,
                                  MemoryService memoryService,
                                  CardLockRegistry cardLockRegistry,
                                  ActionReviewService actionReviewService) {
        this.aiClient = aiClient;
        this.recordRepository = recordRepository;
        this.cardRepository = cardRepository;
        this.memoryService = memoryService;
        this.cardLockRegistry = cardLockRegistry;
        this.actionReviewService = actionReviewService;
    }

    /**
     * 兼容构造（5 参，测试/旧调用）：自建**私有**锁池——仅适用于单线程用例或与 append 侧
     * 无并发的场景（锁只对 end↔end 生效）。生产走 Spring 主构造，不经过这里。
     */
    public ConversationController(AiClient aiClient, RecordRepository recordRepository,
                                  CardFileRepository cardRepository,
                                  MemoryService memoryService,
                                  ActionReviewService actionReviewService) {
        this(aiClient, recordRepository, cardRepository, memoryService,
                new CardLockRegistry(), actionReviewService);
    }

    @PostMapping("/end")
    public ResponseEntity<EndConversationResponse> endConversation(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody EndConversationRequest request) {

        // REVIEW P1-对话1：同卡幂等——cardId 非空时，把「幂等判定 + AI 总结 + 落盘 + 卡片回写」
        // 整段收进按 (userId|cardId) 的**结束闸**。超时重发 / 双端并发再次 end 只会落一条 conversation，
        // 且不会重复调用 AI（检查-再动作竞态，pitfalls「并发花钱」）。
        //
        // REVIEW P2-工程13（2026-10-05）：结束闸（endLock）与卡片 RMW 锁（cardLock）**分成两把**，
        // 锁顺序固定 endLock → cardLock，两把都来自共享的 CardLockRegistry（详见该类注释）：
        //   · endLock 跨 AI 调用持有 → 保住 P1-对话1 的「并发 end 只烧一次 AI」；
        //   · cardLock 只包住卡片写回的读-改-写（doEnd 内）→ append 侧（用户发消息）不会被
        //     AI 调用时长阻塞。此前是一把锁包住 AI + 全部落盘，append 若共用它会一并发消息卡住秒级。
        if (request.cardId() == null || request.cardId().isBlank()) {
            return doEnd(userId, request);
        }
        synchronized (cardLockRegistry.endLock(userId, request.cardId())) {
            Optional<CardRecord> existing = cardRepository.findById(userId, request.cardId());
            // 对抗审查 P1-A（2026-09-26）：命中幂等键**必须同时比对内容指纹**——「在已结束的卡上
            // 继续聊几轮再点结束」不是重试：只比 recordId 会返回上一次的总结，且新轮次既不落
            // record 也不进记忆（用户会说「我刚说的你没记住」）。指纹不一致 → 正常走 doEnd 新落一条。
            Integer currentHash = request.turns().hashCode();
            if (existing.isPresent()
                    && existing.get().conversationRecordId() != null
                    && sameContentAsRecorded(existing.get(), request.turns(), currentHash)) {
                String recordId = existing.get().conversationRecordId();
                // REVIEW P2-工程14（2026-09-26 夜间批 4）：幂等键与记录**分属两个文件**（card_*.md / rec_*.md），
                // 没有跨文件事务——卡片写成功而记录被删 / 损坏 / 迁移丢失时，键会悬空。此时还回那个 id 就是
                // 「假成功」：前端拿着一个查不到、打不开的 recordId。判据：宁可重落一条真实存在的记录，
                // 也不返回幽灵 id。
                // 选 (b) 失效该键（忽略悬空键 → 走正常流程新落一条），而不是 (a) 用卡片信息重建记录：
                //   · 重建会**绕过唯一的正规写入路径** doEnd——记录与记忆（MemoryService.persist）是 1:1 的，
                //     重建出来的记录没有配套记忆，等于用一次「二等写」制造新的不一致（修一个幽灵、生一个孤儿）；
                //   · 卡片上的 summary/tags 可能本身就是陈旧/缺失来源（记录被删/迁移丢失时卡片未必完好），
                //     重建会把不可信数据固化成「成功」；
                //   · doEnd 是本方法已充分验证的路径（AI 降级兜底、turns 指纹对齐、幂等键回写、记忆沉淀），
                //     走它才保证返回的 id 可查、可打开、且**只落一条**。
                // 代价：这一条罕见路径会多调一次 AI。正确性优先于这点成本（幽灵 id 是用户可见的坏体验）。
                Optional<ContentRecord> live = recordRepository.findById(userId, recordId);
                if (live.isEmpty()) {
                    log.warn("Conversation end 幂等键悬空（记录文件已不存在，卡片键失效，改走新落一条）"
                                    + " | userId={} | cardId={} | ghostRecordId={}",
                            userId, request.cardId(), recordId);
                    // 不显式清键：卡片写回现在与 append 侧共用同一把 cardLock（P2-工程13），但这条路径
                    // 尚未持锁、用的是旧快照；doEnd 末尾会在 cardLock 内重读最新卡片并用新落的 id 覆盖这个悬空键。
                    return doEnd(userId, request);
                }
                log.info("Conversation end 幂等命中（同卡已落盘，不重复记录） | userId={} | cardId={} | recordId={}",
                        userId, request.cardId(), recordId);
                List<String> tags = live.map(ContentRecord::tags).orElse(existing.get().tags());
                String summary = existing.get().summary() != null ? existing.get().summary() : "";
                return ResponseEntity.ok(new EndConversationResponse(recordId, summary, tags));
            }
            return doEnd(userId, request);
        }
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
                  "actionSuggestion": null,
                  "actions": ["这场对话里你明确让对方去做的具体事，一条一句、像你自己在跟他说话；没有就是空数组，最多3条，不许编"]
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

        // REVIEW P2-交易73：对话里给出的动作要有「待回看」的载体——落进**既有待办**
        // （data/{userId}/todos/，用户会看会点会划掉的那份；不新造第三套存储）。
        // 抽不出来 / 模型没给 / 这段对话没动作 → 空清单，这里一个字都不写（沉默是默认项）。
        List<String> actions = LlmResponseParser.parseActions(
                understanding != null ? understanding.rawResponse() : null);
        List<Todo> captured = actionReviewService.captureFromConversation(userId, id, actions);
        if (!captured.isEmpty()) {
            log.info("Conversation actions captured | userId={} | recordId={} | actions={}",
                    userId, id, captured.stream().map(Todo::title).toList());
        }

        // Update card file with summary + ended status + 幂等键（P1-对话1：同卡再次 end 直接返回该记录）
        if (request.cardId() != null) {
            // 锁顺序：endLock → cardLock（本方法只在 endConversation 的 endLock 内被调用，cardId 非空；
            // 锁内只做卡片读-改-写，AI 调用与记录/记忆落盘都在锁外）。
            //
            // REVIEW P2-工程13（2026-10-05）：cardLock 是**共享**的 per-card RMW 锁——append 侧
            // （QuestionAppService.ensureCardWithUserTurn / finishAnswer 的 AI turn、
            // MediaRecordAppService.appendQaToImageCard）持同一把锁，读写窗口被真正消除；
            // 下面的「写回前重新读一次」保留为兜底（跨进程/多实例部署时锁不跨 JVM，
            // 且可挡住任何尚未接线的旧写路径）。
            synchronized (cardLockRegistry.cardLock(userId, request.cardId())) {
                // 后端审查 P1-2（2026-09-26）：写回前**重新读一次**卡片——以最新 turns
                // 为基底，只改状态 / 摘要 / 幂等键。
                Optional<CardRecord> latest = cardRepository.findById(userId, request.cardId());
                if (latest.isPresent()) {
                    // 显式写回前钩子（P2-工程13 发现11）：并发用例在此确定性挂起，不再靠「第 N 次 findById」
                    cardRepository.beforeWriteback(userId, request.cardId());
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
        }

        // 沉淀记忆：AI 成功 → 洞察记忆；失败 → 原文降级（标 DEGRADED，AI 恢复后由重补升级）
        if (understanding != null) {
            Memory memory = Memory.fromUnderstanding(record.id(), understanding);
            // REVIEW P2-交易73：这段对话里阿呆给的动作 → 记忆的「待行动事项」。
            // 落成 actionable 后，ContextEngine 下次对话会把它注入（「## 待行动事项」）；
            // 用户在待办清单里划掉对应那条时，TodoAppService 会反向 markDone 这条记忆，
            // 于是它不再被捞回——闭环不靠用户「自己想起来」。
            String pending = ActionReviewService.joinForMemory(actions);
            if (pending != null) {
                memory = memory.withActionable(true, pending);
            }
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
