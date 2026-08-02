package com.adaiadai.core.kernel.knowledge;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * LifeKnowledgeSource — 生活系统知识源。
 * <p>
 * 两部分组成：
 * <ol>
 *   <li>静态身份声明 — 读取 {@code os/life-os/11-context/identity.md}（Life OS 定位与边界）</li>
 *   <li>动态记忆聚合 — 从 Memory 中浮现生活标签相关的近期理解</li>
 * </ol>
 * 用户无需专门配置 Life OS——只要日常输入被 AI 打上生活标签，
 * LifeKnowledgeSource 就会自动聚合为生活知识块，注入 AI 上下文。
 * <p>
 * 生活标签：运动、饮食、心情、睡眠、社交、学习、出行、购物、工作、日记、习惯、健康、娱乐、家务、个人护理
 */
@Component
public class LifeKnowledgeSource implements KnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(LifeKnowledgeSource.class);

    private static final Set<String> LIFE_TAGS = Set.of(
            "运动", "饮食", "心情", "睡眠", "社交", "学习", "阅读",
            "出行", "购物", "工作", "日记", "习惯", "健康", "娱乐",
            "家务", "个人护理", "咖啡", "生活", "天气", "日常"
    );

    private static final int WINDOW_DAYS = 7;

    private final MemoryService memoryService;
    private final Path contextDir;

    private String cachedIdentity;
    private Instant lastLoadTime;

    public LifeKnowledgeSource(MemoryService memoryService,
                               @Value("${adai.knowledge.life-os-path:../../os/life-os/11-context}") String contextPath) {
        this.memoryService = memoryService;
        this.contextDir = Paths.get(contextPath).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "life";
    }

    @Override
    public String globalContext(String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 生活系统\n\n");

        String identity = loadIdentity();
        String memoryAgg = buildMemoryContext(userId);

        if (!identity.isBlank()) {
            sb.append(identity).append("\n\n");
        }
        if (!memoryAgg.isBlank()) {
            sb.append(memoryAgg);
        }

        String result = sb.toString().strip();
        return "## 生活系统".equals(result) ? "" : result;
    }

    @Override
    public String enrich(String userId, String scene) {
        return "life".equals(scene) ? globalContext(userId) : "";
    }

    // ── 身份声明（文件读取 + 时间戳缓存）──

    private String loadIdentity() {
        refreshIfChanged();
        return cachedIdentity != null ? cachedIdentity : "";
    }

    private void refreshIfChanged() {
        if (!Files.isDirectory(contextDir)) return;
        Path file = contextDir.resolve("identity.md");
        try {
            if (!Files.isReadable(file)) return;
            Instant mod = Files.getLastModifiedTime(file).toInstant();
            if (lastLoadTime == null || mod.isAfter(lastLoadTime)) {
                cachedIdentity = Files.readString(file, StandardCharsets.UTF_8);
                lastLoadTime = Instant.now();
                log.info("LifeKnowledge identity 已加载 | {}KB", cachedIdentity.length() / 1024);
            }
        } catch (IOException e) {
            log.warn("LifeKnowledge identity 读取失败: {}", e.getMessage());
        }
    }

    // ── 记忆聚合 ──

    private String buildMemoryContext(String userId) {
        List<Memory> lifeMemories = collectLifeMemories(userId, WINDOW_DAYS);
        if (lifeMemories.isEmpty()) return "";

        Map<String, List<String>> byTag = groupByTag(lifeMemories);
        if (byTag.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("AI 对你近期的生活理解（自动从记忆中浮现）：\n\n");
        for (Map.Entry<String, List<String>> e : byTag.entrySet()) {
            sb.append("【").append(e.getKey()).append("】");
            sb.append(String.join("；", e.getValue().stream().distinct().limit(3).toList()));
            sb.append("\n");
        }

        log.info("LifeKnowledge 记忆聚合 | tags={} | memories={}", byTag.size(), lifeMemories.size());
        return sb.toString();
    }

    private List<Memory> collectLifeMemories(String userId, int days) {
        List<Memory> all = memoryService.recent(userId, days);
        return all.stream()
                .filter(m -> m.tags().stream().anyMatch(LIFE_TAGS::contains))
                .sorted(Comparator.comparing(Memory::createdAt).reversed())
                .toList();
    }

    private Map<String, List<String>> groupByTag(List<Memory> memories) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (Memory m : memories) {
            for (String tag : m.tags()) {
                if (!LIFE_TAGS.contains(tag)) continue;
                map.computeIfAbsent(tag, k -> new ArrayList<>()).add(m.summary());
            }
        }
        return map;
    }
}
