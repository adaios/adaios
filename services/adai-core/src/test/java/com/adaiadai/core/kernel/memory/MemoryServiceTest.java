package com.adaiadai.core.kernel.memory;

import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryService — 降级沉淀 / AI 洞察升级语义测试。
 * 覆盖 #24 记忆沉淀断裂修复：AI 失败时降级原文入记忆（DEGRADED），AI 恢复后洞察覆盖升级。
 */
class MemoryServiceTest {

    private InMemoryFileStorage fileStorage;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        memoryService = new MemoryService(fileStorage);
    }

    private AiUnderstanding insight(String summary, String insight) {
        return new AiUnderstanding(
                summary, insight,
                null, null,
                List.of("阅读"), "neutral", "life", false, null, null
        );
    }

    @Test
    void fromContentFallback_marksDegraded() {
        Memory degraded = Memory.fromContentFallback("rec_1", "今天买了腾讯股票");

        assertEquals("DEGRADED", degraded.suggestion());
        assertTrue(Memory.isDegraded(degraded));
        assertTrue(degraded.summary().contains("腾讯"));
        assertTrue(degraded.patterns().isEmpty());
    }

    @Test
    void fromContentFallback_truncatesLongContent() {
        String longContent = "很".repeat(200);
        Memory degraded = Memory.fromContentFallback("rec_1", longContent);

        assertTrue(degraded.summary().length() <= 101, "超长原文应截断到 100 字 + 省略号");
    }

    @Test
    void persist_degraded_roundTripKeepsMarker() {
        Memory degraded = Memory.fromContentFallback("rec_2", "用户喜欢读科幻小说");
        memoryService.persist(degraded);

        List<Memory> memories = memoryService.findByDate(degraded.createdAt().toLocalDate());
        assertEquals(1, memories.size());
        assertEquals("rec_2", memories.get(0).recordId());
        assertEquals("DEGRADED", memories.get(0).suggestion());
        assertTrue(Memory.isDegraded(memories.get(0)));
    }

    @Test
    void persist_aiInsight_upgradesDegraded() {
        Memory degraded = Memory.fromContentFallback("rec_3", "用户喜欢读科幻小说");
        memoryService.persist(degraded);

        // AI 恢复后重补，同 recordId 写入洞察 → 应覆盖降级条目
        Memory insight = Memory.fromUnderstanding("rec_3", insight("读科幻", "用户偏好科幻题材"));
        memoryService.persist(insight);

        List<Memory> memories = memoryService.findByDate(insight.createdAt().toLocalDate());
        assertEquals(1, memories.size(), "升级后应只剩 AI 洞察一条");
        assertEquals("rec_3", memories.get(0).recordId());
        assertFalse(Memory.isDegraded(memories.get(0)), "降级标记应被升级清除");
        assertEquals("用户偏好科幻题材", memories.get(0).summary());
        assertEquals(List.of("阅读"), memories.get(0).tags());
    }

    @Test
    void persist_degraded_doesNotOverwriteAiInsight() {
        Memory insight = Memory.fromUnderstanding("rec_4", insight("洞察", "AI 洞察"));
        memoryService.persist(insight);

        // 后到的降级不应覆盖已有 AI 洞察
        memoryService.persist(Memory.fromContentFallback("rec_4", "原文"));

        List<Memory> memories = memoryService.findByDate(insight.createdAt().toLocalDate());
        assertEquals(1, memories.size());
        assertFalse(Memory.isDegraded(memories.get(0)));
        assertEquals("AI 洞察", memories.get(0).summary());
    }

    @Test
    void hasRealMemory_distinguishesDegraded() {
        memoryService.persist(Memory.fromContentFallback("rec_5", "内容"));
        assertFalse(memoryService.hasRealMemory("rec_5"), "仅降级记忆不算 AI 记忆，应可重补");

        memoryService.persist(Memory.fromUnderstanding("rec_5", insight("洞察", "AI 洞察")));
        assertTrue(memoryService.hasRealMemory("rec_5"), "升级后应算 AI 记忆，重补跳过");
    }

    // ── 记忆进化 Phase 1：kind 类型 ──

    @Test
    void deriveKind_preferencePrioritized() {
        AiUnderstanding u = new AiUnderstanding("s", "i",
                List.of(new MemoryPattern("p", 0.8)),
                List.of(new MemoryPreference("pref", 0.9)),
                List.of(), "neutral", "life", false, null, null);
        assertEquals(Memory.KIND_PREFERENCE, Memory.deriveKind(u), "有偏好时偏好优先");
    }

    @Test
    void deriveKind_patternWhenNoPreference() {
        AiUnderstanding u = new AiUnderstanding("s", "i",
                List.of(new MemoryPattern("p", 0.8)),
                null,
                List.of(), "neutral", "life", false, null, null);
        assertEquals(Memory.KIND_PATTERN, Memory.deriveKind(u));
    }

    @Test
    void deriveKind_insightWhenNoPattern() {
        AiUnderstanding u = new AiUnderstanding("s", "洞察内容", null, null,
                List.of(), "neutral", "life", false, null, null);
        assertEquals(Memory.KIND_INSIGHT, Memory.deriveKind(u));
    }

    @Test
    void deriveKind_factWhenNoGain() {
        AiUnderstanding u = new AiUnderstanding("s", null, null, null,
                List.of(), "neutral", "life", false, null, null);
        assertEquals(Memory.KIND_FACT, Memory.deriveKind(u));
    }

    @Test
    void fromUnderstanding_setsDerivedKind() {
        Memory m = Memory.fromUnderstanding("rec_k1",
                new AiUnderstanding("s", "i",
                        List.of(new MemoryPattern("p", 0.8)), null,
                        List.of(), "neutral", "life", false, null, null));
        assertEquals(Memory.KIND_PATTERN, m.kind());
    }

    @Test
    void fromContentFallback_kindIsFact() {
        assertEquals(Memory.KIND_FACT, Memory.fromContentFallback("rec_k2", "原文").kind());
    }

    @Test
    void persist_roundTrip_keepsKind() {
        Memory m = Memory.fromContentFallback("rec_k3", "用户喜欢喝茶");
        memoryService.persist(m);
        List<Memory> loaded = memoryService.findByDate(m.createdAt().toLocalDate());
        assertEquals(1, loaded.size());
        assertEquals(Memory.KIND_FACT, loaded.get(0).kind(), "kind 应随 frontmatter 写读");
    }

    @Test
    void findByKind_filtersByKind() {
        memoryService.persist(Memory.fromContentFallback("rec_k4", "原文fact"));
        memoryService.persist(Memory.fromUnderstanding("rec_k5",
                new AiUnderstanding("s", "i",
                        List.of(new MemoryPattern("p", 0.8)), null,
                        List.of("t"), "neutral", "life", false, null, null)));

        assertTrue(memoryService.findByKind(Memory.KIND_FACT).stream().anyMatch(m -> m.recordId().equals("rec_k4")));
        assertTrue(memoryService.findByKind(Memory.KIND_PATTERN).stream().anyMatch(m -> m.recordId().equals("rec_k5")));
        assertTrue(memoryService.findByKind(Memory.KIND_FACT).stream().noneMatch(m -> m.recordId().equals("rec_k5")));
    }
}
