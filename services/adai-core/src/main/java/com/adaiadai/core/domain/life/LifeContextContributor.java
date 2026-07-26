package com.adaiadai.core.domain.life;

import com.adaiadai.core.kernel.context.engine.ContextContributor;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LifeContextContributor — 生活场景上下文贡献者。
 * <p>
 * 为 life 场景注入近期生活相关记忆的摘要。
 * 不依赖预建知识库，从 Memory 中自动浮现。
 */
@Component
public class LifeContextContributor implements ContextContributor {

    private static final Logger log = LoggerFactory.getLogger(LifeContextContributor.class);

    private static final Set<String> LIFE_TAGS = Set.of(
            "运动", "饮食", "心情", "睡眠", "社交", "学习", "阅读",
            "出行", "购物", "工作", "日记", "习惯", "健康", "娱乐",
            "家务", "个人护理", "咖啡", "生活", "天气", "日常"
    );

    private final MemoryService memoryService;

    public LifeContextContributor(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public boolean supports(String scene) {
        return "life".equals(scene);
    }

    @Override
    public String enrich(String identityRef, ContentRecord record) {
        List<Memory> lifeMemories = collectLifeMemories(7);
        if (lifeMemories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Life OS\n\n");
        sb.append("Recent life activity:\n");
        for (Memory m : lifeMemories.stream().limit(10).toList()) {
            String date = m.createdAt().toLocalDate().toString();
            sb.append("- [").append(date).append("] ").append(m.summary()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String globalContext() {
        return "";
        // Life global context is provided by LifeKnowledgeSource
    }

    private List<Memory> collectLifeMemories(int days) {
        return memoryService.recent(days).stream()
                .filter(m -> m.tags().stream().anyMatch(LIFE_TAGS::contains))
                .sorted(Comparator.comparing(Memory::createdAt).reversed())
                .toList();
    }
}
