package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RecordRetryService — 定时重补未完成的 AI 处理。
 * <p>
 * 每 15 分钟扫描一次，补两类数据：
 * <ol>
 *   <li>ContentRecord 无 Memory → 重新 AI 理解 + 记忆沉淀</li>
 *   <li>CardRecord 有对话但无 summary/tags → 总结对话 + 写入卡片 + 记忆沉淀</li>
 * </ol>
 * 每条间隔 3 秒，每次每类最多 10 条。
 */
@Service
public class RecordRetryService {

    private static final Logger log = LoggerFactory.getLogger(RecordRetryService.class);
    private static final int BATCH_LIMIT = 10;
    private static final long DELAY_MS = 3000;

    private final RecordRepository recordRepository;
    private final RecordUnderstandingService understandingService;
    private final AiClient aiClient;
    private final MemoryService memoryService;
    private final CardFileRepository cardRepository;

    public RecordRetryService(RecordRepository recordRepository,
                              RecordUnderstandingService understandingService,
                              AiClient aiClient,
                              MemoryService memoryService,
                              CardFileRepository cardRepository) {
        this.recordRepository = recordRepository;
        this.understandingService = understandingService;
        this.aiClient = aiClient;
        this.memoryService = memoryService;
        this.cardRepository = cardRepository;
    }

    /**
     * 每 15 分钟执行一次：先补记录，再补卡片。
     */
    @Scheduled(fixedDelayString = "PT15M")
    public void retryUnprocessed() {
        retryRecords();
        retryCards();
    }

    // ── ContentRecord 补完 ──

    private void retryRecords() {
        List<ContentRecord> allRecords = recordRepository.findAll();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);

        List<ContentRecord> candidates = allRecords.stream()
                .filter(r -> r.createdAt().isBefore(cutoff))
                // 未成功理解才重补：AI 失败时 record.summary 为 "recorded"（兜底），成功为真实摘要。
                // 用记录级判定而非记忆存在性——Phase 5 会跳过无增量记忆（fact），
                // 若用 hasRealMemory 会把"已成功理解但无增量"误判为未处理 → 无限重补烧 AI（P1-1 修复）
                .filter(r -> !alreadyProcessed(r))
                .limit(BATCH_LIMIT)
                .toList();

        if (candidates.isEmpty()) return;

        log.info("重补记录 | 发现 {} 条待补", candidates.size());

        int success = 0, failed = 0;
        for (ContentRecord record : candidates) {
            try {
                processRecord(record);
                success++;
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failed++;
                log.warn("重补记录失败 | recordId={} | error={}", record.id(), e.getMessage());
            }
        }
        log.info("重补记录结束 | 成功={} 失败={}", success, failed);
    }

    /**
     * 记录是否已成功理解（AI 失败时 summary 兜底为 "recorded"）。
     */
    private boolean alreadyProcessed(ContentRecord r) {
        return r.summary() != null && !r.summary().isBlank() && !"recorded".equals(r.summary());
    }

    private void processRecord(ContentRecord record) {
        AiUnderstanding understanding = understandingService.composeAndUnderstand("note", record).understanding();

        String domain = understanding.domain() != null ? understanding.domain() : "life";
        List<String> tags = understanding.tags() != null ? understanding.tags() : List.of();
        String summary = understanding.summary();

        ContentRecord enriched = new ContentRecord(
                record.id(), record.type(), record.source(), record.title(), record.content(),
                tags, record.createdAt(), record.intent(), summary, domain
        );
        recordRepository.save(enriched);

        if (summary != null || (!tags.isEmpty())) {
            Memory memory = Memory.fromUnderstanding(record.id(), understanding);
            memoryService.persist(memory);
        }

        log.info("重补记录完成 | recordId={} | summary=\"{}\" | tags={} | domain={}",
                record.id(), truncate(summary, 40), tags, domain);
    }

    // ── CardRecord 补完 ──

    private void retryCards() {
        List<CardRecord> allCards = cardRepository.findAll();

        List<CardRecord> candidates = allCards.stream()
                .filter(c -> c.turns() != null && !c.turns().isEmpty())
                .filter(c -> c.summary() == null || c.summary().isBlank() || c.tags() == null || c.tags().isEmpty())
                .limit(BATCH_LIMIT)
                .toList();

        if (candidates.isEmpty()) return;

        log.info("重补卡片 | 发现 {} 张待补", candidates.size());

        int success = 0, failed = 0;
        for (CardRecord card : candidates) {
            try {
                processCard(card);
                success++;
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failed++;
                log.warn("重补卡片失败 | cardId={} | error={}", card.id(), e.getMessage());
            }
        }
        log.info("重补卡片结束 | 成功={} 失败={}", success, failed);
    }

    private void processCard(CardRecord card) {
        // 构建对话摘要 prompt（同 ConversationController 一致）
        String turnText = buildTurnText(card.turns());
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

        ContextPackage ctx = ContextPackage.simple(
                "conversation", "", "对话总结", turnText, List.of(), prompt
        );

        AiUnderstanding understanding = aiClient.understand(ctx);

        String summary = understanding.summary();
        List<String> tags = understanding.tags() != null ? understanding.tags() : List.of();

        // 更新卡片 summary + tags
        CardRecord updated = new CardRecord(
                card.id(), card.type(), card.status(),
                tags, card.turns(), summary,
                card.createdAt(), LocalDateTime.now()
        );
        cardRepository.save(updated);

        // 新建一条记录沉淀对话摘要
        String recordId = RecordFileRepository.generateId();
        ContentRecord record = new ContentRecord(
                recordId, "conversation", "ai_summary",
                truncate(summary, 50), summary,
                tags, LocalDateTime.now()
        );
        recordRepository.save(record);

        // 沉淀记忆
        if (summary != null || (!tags.isEmpty())) {
            Memory memory = Memory.fromUnderstanding(recordId, understanding);
            memoryService.persist(memory);
        }

        log.info("重补卡片完成 | cardId={} | summary=\"{}\" | tags={}",
                card.id(), truncate(summary, 40), tags);
    }

    private String buildTurnText(List<CardRecord.Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            String prefix = (i % 2 == 0) ? "我" : "你";
            sb.append(prefix).append("：").append(turns.get(i).text()).append("\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
