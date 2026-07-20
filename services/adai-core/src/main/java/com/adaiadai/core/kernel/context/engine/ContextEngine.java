package com.adaiadai.core.kernel.context.engine;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ContextEngine — 上下文引擎，Kernel 核心能力。
 * <p>
 * Phase 2 重构：
 * <ul>
 *   <li>标签索引关联记录（TagIndexService），替代时间窗口</li>
 *   <li>Memory 回读（按标签聚合）</li>
 *   <li>卡片对话上下文（cardId 传入时加载全部对话轮次）</li>
 *   <li>全局领域上下文（所有 Domain OS 的 globalContext()）</li>
 *   <li>当前日期/星期注入</li>
 * </ul>
 */
@Component
public class ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(ContextEngine.class);

    private static final int MAX_RELATED_RECORDS = 20;
    private static final int MEMORY_DAYS = 7;

    private final IdentityRepository identityRepository;
    private final RecordRepository recordRepository;
    private final TagIndexService tagIndexService;
    private final MemoryService memoryService;
    private final CardFileRepository cardFileRepository;
    private final List<ContextContributor> contributors;

    public ContextEngine(IdentityRepository identityRepository,
                         RecordRepository recordRepository,
                         TagIndexService tagIndexService,
                         MemoryService memoryService,
                         CardFileRepository cardFileRepository,
                         List<ContextContributor> contributors) {
        this.identityRepository = identityRepository;
        this.recordRepository = recordRepository;
        this.tagIndexService = tagIndexService;
        this.memoryService = memoryService;
        this.cardFileRepository = cardFileRepository;
        this.contributors = contributors;
    }

    /**
     * 为指定记录组装上下文包（无卡片上下文）。
     */
    public ContextPackage compose(String scene, ContentRecord record) {
        return compose(scene, record, null);
    }

    /**
     * 为指定的 Record 组装上下文包。
     * <p>
     * 组装流程：
     * <ol>
     *   <li>Identity — 你是谁</li>
     *   <li>卡片上下文 — 当前会话的对话轮次</li>
     *   <li>标签关联记录 — 与当前记录同标签的历史记录</li>
     *   <li>记忆摘要 — AI 对你的近期理解（按标签聚合）</li>
     *   <li>领域上下文 — 场景特定 + 所有 Domain 全局摘要</li>
     * </ol>
     */
    public ContextPackage compose(String scene, ContentRecord record, String cardId) {
        String identityRef = loadIdentitySummary();
        String cardContext = loadCardContext(cardId);
        String relatedRecords = loadRelatedRecords(record);
        String memorySummary = loadMemorySummary();
        String domainContext = enrichFromContributors(scene, identityRef, record);
        String globalContext = loadGlobalContext();
        String prompt = buildPrompt(scene, identityRef, record,
                cardContext, relatedRecords, memorySummary, domainContext, globalContext);

        log.info("ContextPackage 组装完成 | scene={} | record={} | 标签关联={}条 | 预估 tokens={}",
                scene, record.id(),
                relatedRecords.isBlank() ? 0 : relatedRecords.split("\n").length,
                (prompt.length() / 2));

        return new ContextPackage(
                scene, identityRef,
                record.title(), record.content(), record.tags(),
                List.of(cardContext, relatedRecords, memorySummary),
                prompt,
                LocalDateTime.now());
    }

    // ── 内部方法 ──

    private String loadIdentitySummary() {
        Optional<IdentityProfile> profile = identityRepository.load();
        return profile.map(p -> """
                用户身份摘要：
                - 称呼：%s
                - 偏好：%s
                - 协作规则：%s
                """.formatted(
                        p.name(),
                        String.join("; ", p.preferences().values()),
                        String.join("; ", p.rules().values())
                )).orElse("用户身份：未配置");
    }

    /**
     * 加载当前卡片对话上下文（全部轮次）。
     */
    private String loadCardContext(String cardId) {
        if (cardId == null) return "";

        Optional<CardRecord> card = cardFileRepository.findById(cardId);
        if (card.isEmpty()) return "";

        List<CardRecord.Turn> turns = card.get().turns();
        if (turns.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## 当前会话对话历史\n\n");
        for (CardRecord.Turn turn : turns) {
            String prefix = turn.isUser() ? "用户" : "AI";
            sb.append("- [").append(turn.time()).append("] ")
                    .append(prefix).append("：").append(turn.text()).append("\n");
        }

        log.info("卡片上下文已加载 | cardId={} | turns={}", cardId, turns.size());
        return sb.toString();
    }

    /**
     * 加载与当前记录同标签的历史记录（通过 TagIndexService，不限时间）。
     * <p>
     * 如果当前记录还没有标签（新记录，AI 尚未处理），回退到最近记录。
     */
    private String loadRelatedRecords(ContentRecord currentRecord) {
        List<String> tags = currentRecord.tags();
        if (tags == null || tags.isEmpty()) {
            // 回退：没有标签时取最近记录
            List<ContentRecord> allRecords = recordRepository.findAll();
            List<ContentRecord> recent = allRecords.stream()
                    .filter(r -> !r.id().equals(currentRecord.id()))
                    .limit(MAX_RELATED_RECORDS)
                    .collect(Collectors.toList());
            if (recent.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("## 相关历史记录\n\n");
            for (ContentRecord r : recent) {
                String time = r.createdAt().toLocalTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm"));
                String date = r.createdAt().toLocalDate().toString();
                String summary = r.summary() != null && !r.summary().isBlank()
                        ? r.summary()
                        : r.title();
                sb.append("- [").append(date).append(" ").append(time).append("] ")
                        .append("(").append(r.type()).append(") ")
                        .append(summary).append("\n");
            }
            return sb.toString();
        }

        List<String> relatedIds = tagIndexService.findRelatedIds(tags, MAX_RELATED_RECORDS);

        // 加载实际记录
        List<ContentRecord> related = relatedIds.stream()
                .map(id -> recordRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .filter(r -> !r.id().equals(currentRecord.id()))
                .collect(Collectors.toList());

        if (related.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## 相关历史记录\n\n");
        sb.append("（基于标签：").append(String.join("、", tags)).append("）\n\n");
        for (ContentRecord r : related) {
            String time = r.createdAt().toLocalTime()
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
            String date = r.createdAt().toLocalDate().toString();
            String summary = r.summary() != null && !r.summary().isBlank()
                    ? r.summary()
                    : r.title();
            sb.append("- [").append(date).append(" ").append(time).append("] ")
                    .append("(").append(r.type()).append(") ")
                    .append(summary).append("\n");
        }
        return sb.toString();
    }

    /**
     * 加载近期的记忆摘要（按标签聚合）。
     */
    private String loadMemorySummary() {
        List<Memory> recentMemories = memoryService.recent(MEMORY_DAYS);
        if (recentMemories.isEmpty()) {
            return "";
        }

        // 按标签聚合记忆摘要
        Map<String, List<String>> byTag = new LinkedHashMap<>();
        for (Memory m : recentMemories) {
            for (String tag : m.tags()) {
                byTag.computeIfAbsent(tag, k -> new ArrayList<>())
                        .add(m.summary());
            }
        }

        StringBuilder sb = new StringBuilder("## AI 对你的近期理解\n\n");
        for (Map.Entry<String, List<String>> entry : byTag.entrySet()) {
            // 取每个标签下最新的 2 条摘要
            List<String> summaries = entry.getValue().stream()
                    .distinct()
                    .limit(2)
                    .collect(Collectors.toList());
            if (!summaries.isEmpty()) {
                sb.append("【").append(entry.getKey()).append("】")
                        .append(String.join("；", summaries))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 收集场景特定贡献 + 所有全局上下文。
     */
    private String enrichFromContributors(String scene, String identityRef, ContentRecord record) {
        StringBuilder sceneContext = new StringBuilder();

        // 1. 找场景特定贡献者
        for (ContextContributor contributor : contributors) {
            if (!contributor.isDefault() && contributor.supports(scene)) {
                String context = contributor.enrich(identityRef, record);
                if (context != null && !context.isBlank()) {
                    sceneContext.append(context).append("\n\n");
                    log.info("使用场景贡献者: {} | scene={}", contributor.getClass().getSimpleName(), scene);
                }
            }
        }

        return sceneContext.toString().strip();
    }

    /**
     * 加载所有 Domain OS 的全局上下文（GlobalContext）。
     */
    private String loadGlobalContext() {
        StringBuilder sb = new StringBuilder();
        for (ContextContributor contributor : contributors) {
            if (contributor.isDefault()) continue;
            try {
                String globalCtx = contributor.globalContext();
                if (globalCtx != null && !globalCtx.isBlank()) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(globalCtx);
                    log.debug("全局上下文已加载: {}", contributor.getClass().getSimpleName());
                }
            } catch (Exception e) {
                log.warn("全局上下文加载失败: {} | {}", contributor.getClass().getSimpleName(), e.getMessage());
            }
        }
        return sb.toString();
    }

    private String buildPrompt(String scene, String identityRef, ContentRecord record,
                               String cardContext, String relatedRecords,
                               String memorySummary, String domainContext, String globalContext) {
        String todayInfo = "%s %s".formatted(
                LocalDate.now().toString(),
                LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE)
        );

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个个人 AI 助手，正在处理用户的一条新记录。\n\n");
        prompt.append(identityRef).append("\n");
        prompt.append("当前日期：").append(todayInfo).append("\n");
        prompt.append("场景：").append(scene).append("\n");

        // 卡片对话历史
        if (!cardContext.isBlank()) {
            prompt.append("\n").append(cardContext).append("\n");
        }

        // 标签关联历史记录
        if (!relatedRecords.isBlank()) {
            prompt.append("\n").append(relatedRecords).append("\n");
        }

        // 记忆摘要
        if (!memorySummary.isBlank()) {
            prompt.append("\n").append(memorySummary).append("\n");
        }

        // 全局领域上下文（所有 Domain）
        if (!globalContext.isBlank()) {
            prompt.append("\n").append(globalContext).append("\n");
        }

        // 当前记录
        prompt.append("当前记录：\n")
                .append("---\n")
                .append("标题：").append(record.title()).append("\n")
                .append("标签：").append(String.join(", ", record.tags())).append("\n")
                .append("内容：\n").append(record.content()).append("\n")
                .append("---\n");

        // 场景特定领域上下文
        if (domainContext != null && !domainContext.isBlank()) {
            prompt.append("\n当前领域上下文：\n").append(domainContext).append("\n");
        }

        // 场景感知 Prompt
        if ("question".equals(scene)) {
            prompt.append("""

                    请回答用户的问题，同时输出 JSON 格式（不要包裹 markdown 代码块）：
                    {
                      "summary": "你的回答",
                      "tags": ["标签1", "标签2"],
                      "sentiment": "neutral",
                      "actionable": false,
                      "actionSuggestion": null
                    }
                    """);
        } else {
            prompt.append("""

                    请分析这条记录，输出 JSON 格式（不要包裹 markdown 代码块）：
                    {
                      "summary": "一句话摘要",
                      "tags": ["标签1", "标签2", "标签3"],
                      "sentiment": "positive 或 negative 或 neutral",
                      "actionable": true 或 false,
                      "actionSuggestion": "如果需要后续操作，写建议；否则写 null"
                    }
                    """);
        }

        return prompt.toString();
    }
}
