package com.adaiadai.core.kernel.knowledge;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LifeKnowledgeSource — 生活系统知识源。
 * <p>
 * 不依赖静态文件，而是从 Memory 中自动浮现生活相关的理解。
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

    public LifeKnowledgeSource(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "life";
    }

    @Override
    public String globalContext() {
        List<Memory> lifeMemories = collectLifeMemories(WINDOW_DAYS);
        if (lifeMemories.isEmpty()) return "";

        Map<String, List<String>> byTag = groupByTag(lifeMemories);
        if (byTag.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 生活系统\n\n");
        sb.append("AI 对你近期的生活理解（自动从记忆中浮现）：\n\n");
        for (Map.Entry<String, List<String>> e : byTag.entrySet()) {
            sb.append("【").append(e.getKey()).append("】");
            sb.append(String.join("；", e.getValue().stream().distinct().limit(3).toList()));
            sb.append("\n");
        }

        log.info("LifeKnowledge 生成 | tags={} | memories={}", byTag.size(), lifeMemories.size());
        return sb.toString();
    }

    @Override
    public String enrich(String scene) {
        return "life".equals(scene) ? globalContext() : "";
    }

    private List<Memory> collectLifeMemories(int days) {
        List<Memory> all = memoryService.recent(days);
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
