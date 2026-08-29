package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.kernel.ai.JsonTailFilter;
import com.adaiadai.core.kernel.ai.StreamingAiClient;
import com.adaiadai.core.infrastructure.ai.llm.LlmResponseParser;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * QuestionAppService — 问答处理服务。
 * <p>
 * 处理 QUESTION 意图：存 Record（保证会话连续性），
 * 通过 ContextEngine 获取会话历史 + 卡片上下文 + 记忆回读，返回 AI 回答。
 */
@Service
public class QuestionAppService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAppService.class);

    private final ContextEngine contextEngine;
    private final CardFileRepository cardRepository;
    private final RecordRepository recordRepository;
    private final MemoryService memoryService;
    private final AiClient aiClient;
    private final StreamingAiClient streamingAiClient;
    private final PluginService pluginService;

    public QuestionAppService(ContextEngine contextEngine, CardFileRepository cardRepository,
                              RecordRepository recordRepository,
                              MemoryService memoryService,
                              AiClient aiClient,
                              StreamingAiClient streamingAiClient,
                              PluginService pluginService) {
        this.contextEngine = contextEngine;
        this.cardRepository = cardRepository;
        this.recordRepository = recordRepository;
        this.memoryService = memoryService;
        this.aiClient = aiClient;
        this.streamingAiClient = streamingAiClient;
        this.pluginService = pluginService;
    }

    /**
     * 回答用户提问（带 cardId 上下文）。
     */
    public AnswerResult answer(String userId, ContentRecord record) {
        return answer(userId, record, null);
    }

    /**
     * 回答用户提问（默认 QUESTION 场景）。
     *
     * @param record 当前记录
     * @param cardId 可选卡片 ID，提供完整对话上下文
     */
    public AnswerResult answer(String userId, ContentRecord record, String cardId) {
        return answer(userId, record, cardId, "question");
    }

    /**
     * 回答用户提问（指定场景）。
     *
     * @param record 当前记录
     * @param cardId 可选卡片 ID，提供完整对话上下文
     * @param scene  场景标识："question" / "decision"
     */
    public AnswerResult answer(String userId, ContentRecord record, String cardId, String scene) {
        log.info("=== 问答流程开始 | userId={} | recordId={} | cardId={} | scene={} ===",
                userId, record.id(), cardId, scene);

        // R1 AI 交互日志：挂载关联锚点（记录 + 卡片），LoggingAiClient 装饰器读取
        AiTraceContext.set(userId, record.id(), cardId, "question");

        // ContextEngine 负责组装：Identity + 会话历史 + 卡片上下文 + 记忆回读 + Knowledge + 领域上下文
        ContextPackage contextPackage = contextEngine.compose(userId, scene, record, cardId);

        // AI 理解（回答问题 + 生成标签）
        AiUnderstanding understanding = aiClient.understand(contextPackage);

        log.info("=== 问答流程完成 | 标签={} ===", understanding.tags());

        return finishAnswer(userId, record, cardId, understanding, t -> { });
    }

    /**
     * 流式问答（ai-calling-governance 批 2，REVIEW P2-用户2：SSE 端点 POST /records/ask-stream）。
     * <p>
     * 编排：compose 上下文 → {@link StreamingAiClient#streamGenerate} 逐块产出
     * （经 {@link JsonTailFilter} 剥离 AI 回复末尾的 JSON 回执尾巴）→ onComplete 前完成
     * 与 {@link #answer} 完全一致的副作用（domain 门控 / record 标签回写 / card AI turn / 记忆沉淀）。
     * <p>
     * 降级矩阵（ai-calling-governance §⑤）：首块增量前失败 → 同步 {@code understand} 重试一次
     * （全文一次性 onDelta）；已发出增量后的中途失败 → onError（用户轮次已在 controller 落卡，不丢）。
     */
    public void answerStream(String userId, ContentRecord record, String cardId, AnswerStreamHandler handler) {
        log.info("=== 流式问答开始 | userId={} | recordId={} | cardId={} ===", userId, record.id(), cardId);

        AiTraceContext.set(userId, record.id(), cardId, "question");
        ContextPackage contextPackage = contextEngine.compose(userId, "question", record, cardId);

        StringBuilder full = new StringBuilder();
        JsonTailFilter filter = new JsonTailFilter();
        boolean[] emitted = {false};
        String raw;
        try {
            raw = streamingAiClient.streamGenerate(contextPackage, null, chunk -> {
                full.append(chunk);
                String out = filter.filter(chunk);
                if (!out.isEmpty()) {
                    emitted[0] = true;
                    handler.onDelta(out);
                }
            });
        } catch (Exception streamEx) {
            if (emitted[0]) {
                log.warn("流式生成中途失败（已发出约 {} 字符）| {}", full.length(), streamEx.getMessage());
                handler.onError("阿呆说到一半断线了，请重试");
                return;
            }
            // 未发出任何增量（连接失败/HTTP 非 200/空内容）→ 降级非流式重试一次
            log.warn("流式生成失败（未发出增量），降级非流式 | {}", streamEx.getMessage());
            try {
                AiUnderstanding understanding = aiClient.understand(contextPackage);
                raw = understanding.rawResponse();
            } catch (Exception syncEx) {
                log.error("流式与降级非流式均失败 | stream={} | sync={}",
                        streamEx.getMessage(), syncEx.getMessage());
                handler.onError("阿呆一时没想好怎么回答，请稍后重试");
                return;
            }
        }

        AiUnderstanding understanding = LlmResponseParser.parse(raw);
        // 流式成功（曾发出增量）：草稿已逐块发出，补发为空；降级路径（未发出增量）：全文一次性补发
        AnswerResult result = finishAnswer(userId, record, cardId, understanding,
                emitted[0] ? t -> { } : handler::onDelta);

        // 尾巴 flush：确认为 JSON 回执 → 丢弃缓冲；全文无 JSON（extractJson 落空）→ 缓冲是正文，补发
        String held = filter.flush(LlmResponseParser.extractJson(raw) != null);
        if (!held.isEmpty()) handler.onDelta(held);

        handler.onComplete(new StreamResult(result.recordId(), result.summary(),
                result.tags(), result.rawResponse(), result.domain()));
        log.info("=== 流式问答完成 | recordId={} | 标签={} ===", record.id(), understanding.tags());
    }

    /**
     * 流式问答回调（AskStreamController 适配为 SSE 事件；测试直接收集）。
     */
    public interface AnswerStreamHandler {
        /** 已剥离 JSON 尾巴的正文增量（前端流式草稿逐段追加）。 */
        void onDelta(String chunk);

        /** 流正常结束：与 {@link #answer} 相同的落盘副作用已完成，前端以 text 为权威定稿。 */
        void onComplete(StreamResult result);

        /** 中途失败（已发出增量后断流）：message 为可直接展示的人话。 */
        void onError(String message);
    }

    /**
     * 流式问答结果（meta 事件载荷）：text 为剥离 JSON 后的最终正文。
     */
    public record StreamResult(String recordId, String summary, List<String> tags,
                               String text, String domain) {}

    /**
     * answer / answerStream 共用收尾：domain 门控 → record 标签回写（cardId 为空时）→
     * 剥离 JSON → card AI turn 追加 → 记忆沉淀。
     *
     * @param textOut 额外的正文出口（流式降级路径一次性补发全文；同步路径为空操作）
     */
    private AnswerResult finishAnswer(String userId, ContentRecord record, String cardId,
                                      AiUnderstanding understanding,
                                      java.util.function.Consumer<String> textOut) {
        // D5（RFC 20260814）：AI 判定的 domain 若属未启用插件 → 收敛 life
        String domain = pluginService.gateDomain(userId, understanding.domain());

        // 将 AI 返回的标签写回 Record，触发 TagIndexService 更新索引
        // 只有非卡片续接（无 cardId）时才存记录，避免重复拆分
        // #144：无条件持久化 intent=question + summary——rebuild 借此排除 question 记录，避免重跑烧 AI
        // #188：AI 空 tags 时保留用户提交标签（record.tags()），不抹空
        if (cardId == null) {
            List<String> effectiveTags = (understanding.tags() != null && !understanding.tags().isEmpty())
                    ? understanding.tags()
                    : record.tags();
            ContentRecord enriched = new ContentRecord(
                    record.id(), record.type(), record.source(), record.title(), record.content(),
                    effectiveTags,
                    record.createdAt(), "question", understanding.summary(),
                    domain
            );
            recordRepository.save(userId, enriched);
            log.info("Record 标签已更新 | recordId={} | tags={}", record.id(), effectiveTags);
        }

        // 剥离 AI 原始回复末尾的 JSON 元数据，只保留自然语言（REVIEW #13/#11）：
        // 实时显示与刷新后 parseTurns 一致，card 文件不再混入游离 JSON 块
        String aiText = LlmResponseParser.extractNaturalText(understanding.rawResponse());
        if (aiText == null || aiText.isBlank()) {
            aiText = understanding.summary();
        }
        textOut.accept(aiText);

        // Save AI turn to card if cardId present
        if (cardId != null) {
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

            Optional<CardRecord> existing = cardRepository.findById(userId, cardId);
            if (existing.isPresent()) {
                CardRecord updated = existing.get()
                        .withTurn(false, aiText, timeStr);
                cardRepository.save(userId, updated);
                log.info("AI turn saved to card | cardId={} | len={}", cardId,
                        aiText != null ? aiText.length() : 0);
            }
        }

        // Persist AI understanding as memory
        if (understanding.summary() != null || (understanding.tags() != null && !understanding.tags().isEmpty())) {
            try {
                Memory memory = Memory.fromUnderstanding(record.id(), cardId, understanding); // cardId=对话卡，删除双匹配（08-14）
                memoryService.persist(userId, memory);
                log.info("Memory persisted for question | recordId={} | summary=\"{}\"", record.id(),
                        understanding.summary() != null && understanding.summary().length() > 40
                                ? understanding.summary().substring(0, 40) + "..."
                                : understanding.summary());
            } catch (Exception e) {
                log.debug("Memory persist skipped: {}", e.getMessage());
            }
        }

        return new AnswerResult(
                record.id(),
                understanding.summary(),
                understanding.tags(),
                aiText,
                domain
        );
    }

    // ── 卡片续聊共享逻辑（RecordController 同步路径与 AskStreamController 流式路径复用）──

    /**
     * 超时重发判定（REVIEW S-9 链条）：同 cardId 最近一轮用户问句与本次相同且卡片 5 分钟内活跃
     * → 视为前端超时重发。命中返回已有回答（调用方直接返回，不 append、不烧 AI）。
     */
    public Optional<AnswerResult> findDuplicateResend(String userId, String cardId, String content) {
        if (content == null) return Optional.empty();
        Optional<CardRecord> cardOpt = cardRepository.findById(userId, cardId);
        if (cardOpt.isEmpty()) return Optional.empty();
        CardRecord card = cardOpt.get();
        if (card.turns() == null || card.turns().isEmpty()) return Optional.empty();

        String normalized = content.strip();
        if (normalized.isEmpty()) return Optional.empty();

        // 从后往前找最近一轮用户问句
        CardRecord.Turn lastUser = null;
        for (int i = card.turns().size() - 1; i >= 0; i--) {
            if (card.turns().get(i).isUser()) {
                lastUser = card.turns().get(i);
                break;
            }
        }
        if (lastUser == null || lastUser.text() == null
                || !lastUser.text().strip().equals(normalized)) {
            return Optional.empty();
        }

        // 窗口判定：updatedAt 距现在 ≤ 5 分钟（updatedAt 每轮 append 刷新为 now）
        LocalDateTime now = LocalDateTime.now();
        boolean resend = card.updatedAt() != null
                && !card.updatedAt().isBefore(now.minus(RESEND_WINDOW));
        if (!resend) return Optional.empty();

        // 取最近一轮 AI 回答作为回答正文（若无 AI turn 则空串）
        String lastAi = "";
        if (card.turns() != null) {
            for (int i = card.turns().size() - 1; i >= 0; i--) {
                if (!card.turns().get(i).isUser()) {
                    lastAi = card.turns().get(i).text() != null ? card.turns().get(i).text() : "";
                    break;
                }
            }
        }
        return Optional.of(new AnswerResult(cardId, card.summary(),
                card.tags() != null ? card.tags() : List.of(), lastAi, "life"));
    }

    /** 超时重发判定窗口：前端 AI 超时断开后重发通常发生在几十秒内，5 分钟足够覆盖。 */
    private static final java.time.Duration RESEND_WINDOW = java.time.Duration.ofMinutes(5);

    /**
     * 确保卡片存在并追加用户轮次（首问建卡 / 续聊 append，与 RecordController.handleQuestion 同口径）。
     */
    public void ensureCardWithUserTurn(String userId, String cardId, String content, LocalDateTime createdAt) {
        java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        String timeStr = createdAt.format(timeFmt);
        Optional<CardRecord> existing = cardRepository.findById(userId, cardId);
        if (existing.isEmpty()) {
            CardRecord card = new CardRecord(
                    cardId, "conversation", "active",
                    List.of(), List.of(new CardRecord.Turn(true, content, timeStr)),
                    null, createdAt, createdAt
            );
            cardRepository.save(userId, card);
            log.info("Card created (first turn) | cardId={}", cardId);
        } else {
            CardRecord updated = existing.get()
                    .withTurn(true, content, timeStr);
            cardRepository.save(userId, updated);
            log.info("Card append | cardId={} | contentLen={}", cardId, content != null ? content.length() : 0);
        }
    }

    public record AnswerResult(
            String recordId,
            String summary,
            List<String> tags,
            String rawResponse,
            String domain
    ) {}
}
