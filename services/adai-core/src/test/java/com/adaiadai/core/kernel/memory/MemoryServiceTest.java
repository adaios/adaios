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

    // ── 记忆进化 Phase 2：主题级合并 ──

    private AiUnderstanding insightWithTags(String summary, String insight, List<String> tags) {
        return new AiUnderstanding(summary, insight, null, null, tags, "neutral", "life", false, null, null);
    }

    @Test
    void persist_topicMerge_marksOldSuperseded() {
        Memory first = Memory.fromUnderstanding("rec_t1", insightWithTags("喝茶", "用户喜欢茉莉花茶", List.of("茶")));
        memoryService.persist(first);
        Memory second = Memory.fromUnderstanding("rec_t2", insightWithTags("品茶", "品茶偏好乌龙", List.of("茶", "饮品")));
        memoryService.persist(second);

        List<Memory> all = memoryService.findByDate(first.createdAt().toLocalDate());
        assertEquals(2, all.size());
        Memory old = all.get(0);
        Memory fresh = all.get(1);
        assertTrue(old.superseded(), "旧版本应标 superseded");
        assertEquals(second.id(), old.evolvedTo(), "evolvedTo 指向新版本");
        assertFalse(fresh.superseded(), "新版本不应 superseded");
        assertEquals(old.id(), fresh.topic(), "新版本 topic 锚定旧版本 id");
    }

    @Test
    void persist_noTagOverlap_noMerge() {
        Memory a = Memory.fromUnderstanding("rec_t3", insightWithTags("喝茶", "茶", List.of("茶")));
        Memory b = Memory.fromUnderstanding("rec_t4", insightWithTags("买股", "股票", List.of("股票")));
        memoryService.persist(a);
        memoryService.persist(b);

        List<Memory> all = memoryService.findByDate(a.createdAt().toLocalDate());
        assertEquals(2, all.size());
        assertTrue(all.stream().noneMatch(Memory::superseded), "无重叠标签不应合并");
        assertTrue(all.stream().noneMatch(m -> m.topic() != null), "无重叠标签不应有主题");
    }

    @Test
    void recentActive_excludesSuperseded() {
        memoryService.persist(Memory.fromUnderstanding("rec_t5", insightWithTags("喝茶", "茶", List.of("茶"))));
        memoryService.persist(Memory.fromUnderstanding("rec_t6", insightWithTags("品茶", "乌龙", List.of("茶", "饮品"))));

        List<Memory> active = memoryService.recentActive(7);
        assertTrue(active.stream().noneMatch(m -> m.recordId().equals("rec_t5")), "superseded 版本不应参与回读");
        assertTrue(active.stream().anyMatch(m -> m.recordId().equals("rec_t6")));
    }

    // ── 记忆进化 Phase 3：actionable 闭环 ──

    private AiUnderstanding actionMemo(String summary, String suggestion, List<String> tags) {
        return new AiUnderstanding(summary, summary, null, null, tags, "neutral", "trading", true, suggestion, null);
    }

    @Test
    void markDone_completesActionableMemory() {
        Memory m = Memory.fromUnderstanding("rec_a1", actionMemo("建议减仓", "建议减仓", List.of("交易")));
        memoryService.persist(m);
        assertTrue(m.actionable());

        assertTrue(memoryService.markDone(m.id()));
        List<Memory> loaded = memoryService.findByDate(m.createdAt().toLocalDate());
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).actionable(), "完成后 actionable 应为 false");
        assertNotNull(loaded.get(0).doneAt(), "完成后应记录完成时间");
    }

    @Test
    void findPendingActions_excludesDone() {
        // 两个行动记忆 tags 不重叠，避免主题合并干扰
        Memory pending = Memory.fromUnderstanding("rec_a2", actionMemo("买入机会", "关注半导体", List.of("交易")));
        Memory done = Memory.fromUnderstanding("rec_a3", actionMemo("已处理", "卖出一批", List.of("生活")));
        memoryService.persist(pending);
        memoryService.persist(done);
        memoryService.markDone(done.id());

        List<Memory> pendingActions = memoryService.findPendingActions();
        assertTrue(pendingActions.stream().anyMatch(m -> m.recordId().equals("rec_a2")), "未完成行动应保留");
        assertTrue(pendingActions.stream().noneMatch(m -> m.recordId().equals("rec_a3")), "已完成行动不应出现");
    }

    @Test
    void markDone_notFound_returnsFalse() {
        assertFalse(memoryService.markDone("mem_nonexistent"));
    }
}
