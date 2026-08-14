package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
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
    private final AccountRepository accountRepository;
    private final PluginService pluginService;

    public RecordRetryService(RecordRepository recordRepository,
                              RecordUnderstandingService understandingService,
                              AiClient aiClient,
                              MemoryService memoryService,
                              CardFileRepository cardRepository,
                              AccountRepository accountRepository,
                              PluginService pluginService) {
        this.recordRepository = recordRepository;
        this.understandingService = understandingService;
        this.aiClient = aiClient;
        this.memoryService = memoryService;
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.pluginService = pluginService;
    }

    /**
     * 每 15 分钟执行一次：先补记录，再补卡片。
     * 遍历全部启用账号逐用户重补（P0 #128：不再硬编码 default）。
     * REVIEW #227：过滤禁用账号——与 MarketAlertService 口径一致，禁用账号重补无意义却烧 AI。
     */
    @Scheduled(fixedDelayString = "PT15M")
    public void retryUnprocessed() {
        List<String> userIds = accountRepository.findAll().stream()
                .filter(Account::enabled)
                .map(Account::userId)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            // #227：default 已随数据迁移移除（#212），无启用账号时与行情轮询一致 warn + 跳过，
            // 不再 fallback "default"（那会给不存在/禁用账号烧 AI）
            log.warn("定时重补：无启用账号，跳过（预期外，账号表应至少含 seed adai）");
            return;
        }
        for (String userId : userIds) {
            log.info("定时重补 | userId={}", userId);
            retryUnprocessed(userId);
        }
    }

    /**
     * 手动触发重补（Controller 传 userId，多用户架构预留）。
     */
    public void retryUnprocessed(String userId) {
        retryRecords(userId);
        retryCards(userId);
    }

    // ── ContentRecord 补完 ──

    private void retryRecords(String userId) {
        List<ContentRecord> allRecords = recordRepository.findAll(userId);
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
                processRecord(userId, record);
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

    private void processRecord(String userId, ContentRecord record) {
        AiUnderstanding understanding = understandingService.composeAndUnderstand(userId, "note", record).understanding();

        // REVIEW S-3：重补路径与 RecordController/QuestionAppService 同口径走 gateDomain——
        // 无插件用户重补成功不得落盘 trading/project 标注（D5 核心不变量铺满所有持久化入口）。
        String domain = pluginService.gateDomain(userId, understanding.domain());
        List<String> tags = understanding.tags() != null ? understanding.tags() : List.of();
        String summary = understanding.summary();

        ContentRecord enriched = new ContentRecord(
                record.id(), record.type(), record.source(), record.title(), record.content(),
                tags, record.createdAt(), record.intent(), summary, domain
        );
        recordRepository.save(userId, enriched);

        if (summary != null || (!tags.isEmpty())) {
            Memory memory = Memory.fromUnderstanding(record.id(), understanding);
            memoryService.persist(userId, memory);
        }

        log.info("重补记录完成 | recordId={} | summary=\"{}\" | tags={} | domain={}",
                record.id(), truncate(summary, 40), tags, domain);
    }

    // ── CardRecord 补完 ──

    private void retryCards(String userId) {
        List<CardRecord> allCards = cardRepository.findAll(userId);

        List<CardRecord> candidates = allCards.stream()
                .filter(c -> c.turns() != null && !c.turns().isEmpty())
                // 只补 summary 空白的卡片。tags 空不代表未处理（AI 可合法返回空标签），
                // 若把 tags 空也算待补，会与"processCard 写回后仍空 tags"构成死循环。
                .filter(c -> c.summary() == null || c.summary().isBlank())
                .limit(BATCH_LIMIT)
                .toList();

        if (candidates.isEmpty()) return;

        log.info("重补卡片 | 发现 {} 张待补", candidates.size());

        int success = 0, failed = 0;
        for (CardRecord card : candidates) {
            try {
                processCard(userId, card);
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

    private void processCard(String userId, CardRecord card) {
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

        // R1 AI 交互日志：挂载卡片锚点
        AiTraceContext.set(userId, null, card.id(), "retry");

        AiUnderstanding understanding = aiClient.understand(ctx);

        String summary = understanding.summary();
        List<String> tags = understanding.tags() != null ? understanding.tags() : List.of();

        // 更新卡片 summary + tags。保留原 updatedAt —— 重补是后台修复，不是用户活跃，
        // 若置为 now 会把历史卡片重新归到"今天"（Feed 日期按 updatedAt 归日）。
        CardRecord updated = new CardRecord(
                card.id(), card.type(), card.status(),
                tags, card.turns(), summary,
                card.createdAt(), card.updatedAt() != null ? card.updatedAt() : card.createdAt()
        );
        cardRepository.save(userId, updated);

        // 新建一条记录沉淀对话摘要
        String recordId = RecordFileRepository.generateId();
        ContentRecord record = new ContentRecord(
                recordId, "conversation", "ai_summary",
                truncate(summary, 50), summary,
                tags, LocalDateTime.now()
        );
        recordRepository.save(userId, record);

        // 沉淀记忆
        if (summary != null || (!tags.isEmpty())) {
            Memory memory = Memory.fromUnderstanding(recordId, understanding);
            memoryService.persist(userId, memory);
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
