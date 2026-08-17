package com.adaiadai.core.kernel.memory;

import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
        memoryService.persist("default",degraded);

        List<Memory> memories = memoryService.findByDate("default",degraded.createdAt().toLocalDate());
        assertEquals(1, memories.size());
        assertEquals("rec_2", memories.get(0).recordId());
        assertEquals("DEGRADED", memories.get(0).suggestion());
        assertTrue(Memory.isDegraded(memories.get(0)));
    }

    @Test
    void persist_aiInsight_upgradesDegraded() {
        Memory degraded = Memory.fromContentFallback("rec_3", "用户喜欢读科幻小说");
        memoryService.persist("default",degraded);

        // AI 恢复后重补，同 recordId 写入洞察 → 应覆盖降级条目
        Memory insight = Memory.fromUnderstanding("rec_3", insight("读科幻", "用户偏好科幻题材"));
        memoryService.persist("default",insight);

        List<Memory> memories = memoryService.findByDate("default",insight.createdAt().toLocalDate());
        assertEquals(1, memories.size(), "升级后应只剩 AI 洞察一条");
        assertEquals("rec_3", memories.get(0).recordId());
        assertFalse(Memory.isDegraded(memories.get(0)), "降级标记应被升级清除");
        assertEquals("用户偏好科幻题材", memories.get(0).summary());
        assertEquals(List.of("阅读"), memories.get(0).tags());
    }

    @Test
    void persist_degraded_doesNotOverwriteAiInsight() {
        Memory insight = Memory.fromUnderstanding("rec_4", insight("洞察", "AI 洞察"));
        memoryService.persist("default",insight);

        // 后到的降级不应覆盖已有 AI 洞察
        memoryService.persist("default",Memory.fromContentFallback("rec_4", "原文"));

        List<Memory> memories = memoryService.findByDate("default",insight.createdAt().toLocalDate());
        assertEquals(1, memories.size());
        assertFalse(Memory.isDegraded(memories.get(0)));
        assertEquals("AI 洞察", memories.get(0).summary());
    }

    @Test
    void hasRealMemory_distinguishesDegraded() {
        memoryService.persist("default",Memory.fromContentFallback("rec_5", "内容"));
        assertFalse(memoryService.hasRealMemory("default","rec_5"), "仅降级记忆不算 AI 记忆，应可重补");

        memoryService.persist("default",Memory.fromUnderstanding("rec_5", insight("洞察", "AI 洞察")));
        assertTrue(memoryService.hasRealMemory("default","rec_5"), "升级后应算 AI 记忆，重补跳过");
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
        memoryService.persist("default",m);
        List<Memory> loaded = memoryService.findByDate("default",m.createdAt().toLocalDate());
        assertEquals(1, loaded.size());
        assertEquals(Memory.KIND_FACT, loaded.get(0).kind(), "kind 应随 frontmatter 写读");
    }

    @Test
    void findByKind_filtersByKind() {
        memoryService.persist("default",Memory.fromContentFallback("rec_k4", "原文fact"));
        memoryService.persist("default",Memory.fromUnderstanding("rec_k5",
                new AiUnderstanding("s", "i",
                        List.of(new MemoryPattern("p", 0.8)), null,
                        List.of("t"), "neutral", "life", false, null, null)));

        assertTrue(memoryService.findByKind("default",Memory.KIND_FACT).stream().anyMatch(m -> m.recordId().equals("rec_k4")));
        assertTrue(memoryService.findByKind("default",Memory.KIND_PATTERN).stream().anyMatch(m -> m.recordId().equals("rec_k5")));
        assertTrue(memoryService.findByKind("default",Memory.KIND_FACT).stream().noneMatch(m -> m.recordId().equals("rec_k5")));
    }

    // ── 08-14 删除残留修复：cardId 关联 + 双匹配删除 ──

    @Test
    void persist_withCardId_roundTripKeepsCardId() {
        Memory m = Memory.fromUnderstanding("rec_c1", "card_123", insight("洞察", "AI 洞察"));
        memoryService.persist("default", m);

        List<Memory> loaded = memoryService.findByDate("default", m.createdAt().toLocalDate());
        assertEquals(1, loaded.size());
        assertEquals("card_123", loaded.get(0).cardId(), "cardId 应随 frontmatter 写读（08-14）");
        assertEquals("rec_c1", loaded.get(0).recordId(), "recordId 应保留");
    }

    @Test
    void deleteByRecordId_matchesCardId() {
        // 旧卡删除场景：卡片 id（card_xxx）与来源记录 id（rec_xxx）分离，
        // 记忆 cardId 记录卡片 id，删除卡片时按 cardId 也能匹配删除（08-14 残留根因）
        Memory m = Memory.fromUnderstanding("rec_c2", "card_456", insight("洞察", "AI 洞察"));
        memoryService.persist("default", m);

        boolean deleted = memoryService.deleteByRecordId("default", "card_456");
        assertTrue(deleted, "按 cardId 应能删除记忆");
        assertTrue(memoryService.findByDate("default", m.createdAt().toLocalDate()).isEmpty(), "删除后记忆应清空");
    }

    @Test
    void deleteByRecordId_matchesRecordId_stillWorks() {
        // 非卡片记忆：recordId 匹配路径不受影响
        Memory m = Memory.fromContentFallback("rec_c3", "普通记录");
        memoryService.persist("default", m);

        assertTrue(memoryService.deleteByRecordId("default", "rec_c3"));
        assertTrue(memoryService.findByDate("default", m.createdAt().toLocalDate()).isEmpty());
    }

    // ── 记忆进化 Phase 2：主题级合并 ──

    private AiUnderstanding insightWithTags(String summary, String insight, List<String> tags) {
        return new AiUnderstanding(summary, insight, null, null, tags, "neutral", "life", false, null, null);
    }

    @Test
    void persist_topicMerge_marksOldSuperseded() {
        Memory first = Memory.fromUnderstanding("rec_t1", insightWithTags("喝茶", "用户喜欢茉莉花茶", List.of("茶")));
        memoryService.persist("default",first);
        Memory second = Memory.fromUnderstanding("rec_t2", insightWithTags("品茶", "品茶偏好乌龙", List.of("茶", "饮品")));
        memoryService.persist("default",second);

        List<Memory> all = memoryService.findByDate("default",first.createdAt().toLocalDate());
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
        memoryService.persist("default",a);
        memoryService.persist("default",b);

        List<Memory> all = memoryService.findByDate("default",a.createdAt().toLocalDate());
        assertEquals(2, all.size());
        assertTrue(all.stream().noneMatch(Memory::superseded), "无重叠标签不应合并");
        assertTrue(all.stream().noneMatch(m -> m.topic() != null), "无重叠标签不应有主题");
    }

    @Test
    void recentActive_excludesSuperseded() {
        memoryService.persist("default",Memory.fromUnderstanding("rec_t5", insightWithTags("喝茶", "茶", List.of("茶"))));
        memoryService.persist("default",Memory.fromUnderstanding("rec_t6", insightWithTags("品茶", "乌龙", List.of("茶", "饮品"))));

        List<Memory> active = memoryService.recentActive("default",7);
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
        memoryService.persist("default",m);
        assertTrue(m.actionable());

        assertTrue(memoryService.markDone("default",m.id()));
        List<Memory> loaded = memoryService.findByDate("default",m.createdAt().toLocalDate());
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).actionable(), "完成后 actionable 应为 false");
        assertNotNull(loaded.get(0).doneAt(), "完成后应记录完成时间");
    }

    @Test
    void clearActionable_clearsActionableKeepsDoneAtNull() {
        // R2 方案 A：记录转任务后清记忆待办——actionable=false 但**不设 doneAt**（问题未解决，跟踪归任务）
        Memory m = Memory.fromUnderstanding("rec_ca1", actionMemo("建议减仓", "建议减仓", List.of("交易")));
        memoryService.persist("default", m);
        assertTrue(m.actionable());

        memoryService.clearActionable("default", "rec_ca1");
        List<Memory> loaded = memoryService.findByDate("default", m.createdAt().toLocalDate());
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).actionable(), "清除后 actionable 应为 false");
        assertNull(loaded.get(0).doneAt(), "清除待办≠完成：不应设 doneAt（区别于 markDone）");
    }

    @Test
    void clearActionable_nonActionable_doesNothing() {
        Memory m = Memory.fromContentFallback("rec_ca2", "普通记录");
        memoryService.persist("default", m);
        assertFalse(m.actionable());

        memoryService.clearActionable("default", "rec_ca2");
        List<Memory> loaded = memoryService.findByDate("default", m.createdAt().toLocalDate());
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).actionable());
        assertNull(loaded.get(0).doneAt(), "非 actionable 记忆不受影响");
    }

    @Test
    void findPendingActions_excludesDone() {
        // 两个行动记忆 tags 不重叠，避免主题合并干扰
        Memory pending = Memory.fromUnderstanding("rec_a2", actionMemo("买入机会", "关注半导体", List.of("交易")));
        Memory done = Memory.fromUnderstanding("rec_a3", actionMemo("已处理", "卖出一批", List.of("生活")));
        memoryService.persist("default",pending);
        memoryService.persist("default",done);
        memoryService.markDone("default",done.id());

        List<Memory> pendingActions = memoryService.findPendingActions("default");
        assertTrue(pendingActions.stream().anyMatch(m -> m.recordId().equals("rec_a2")), "未完成行动应保留");
        assertTrue(pendingActions.stream().noneMatch(m -> m.recordId().equals("rec_a3")), "已完成行动不应出现");
    }

    @Test
    void markDone_notFound_returnsFalse() {
        assertFalse(memoryService.markDone("default","mem_nonexistent"));
    }

    @Test
    void findPendingActions_excludesEmptySuggestion() {
        // 2026-08-10 生产修复：actionable=true 但 suggestion 为空 → 过滤（异常产物，非有效待办）
        Memory emptyAction = Memory.fromUnderstanding("rec_a4", actionMemo("", "", List.of("交易")));
        Memory validAction = Memory.fromUnderstanding("rec_a5", actionMemo("有效建议", "关注持仓", List.of("生活")));
        memoryService.persist("default",emptyAction);
        memoryService.persist("default",validAction);

        List<Memory> pendingActions = memoryService.findPendingActions("default");
        assertTrue(pendingActions.stream().anyMatch(m -> m.recordId().equals("rec_a5")), "有建议的行动应保留");
        assertTrue(pendingActions.stream().noneMatch(m -> m.recordId().equals("rec_a4")), "无建议的空行动应过滤");
    }

    // ── 记忆进化 Phase 4：时效与淘汰 ──

    @Test
    void findAllPatterns_decaysOldMemories() {
        // 旧记忆（20 天前，高置信度 0.9）vs 新记忆（今天，0.8）——衰减后新记忆权重更高
        Memory old = new Memory("mem_old", "rec_p1", Memory.KIND_PATTERN, "旧模式",
                List.of(new MemoryPattern("旧模式", 0.9)), null, List.of("t1"), "neutral", false, null,
                LocalDateTime.now().minusDays(20), null, false, null, null, null);
        Memory fresh = new Memory("mem_fresh", "rec_p2", Memory.KIND_PATTERN, "新模式",
                List.of(new MemoryPattern("新模式", 0.8)), null, List.of("t2"), "neutral", false, null,
                LocalDateTime.now(), null, false, null, null, null);
        memoryService.persist("default",old);
        memoryService.persist("default",fresh);

        List<MemoryPattern> patterns = memoryService.findAllPatterns("default");
        assertEquals("新模式", patterns.get(0).content(), "时效衰减后新记忆应优先");
    }

    @Test
    void cleanup_removesSupersededOver60Days() {
        Memory oldSuperseded = new Memory("mem_old2", "rec_p3", Memory.KIND_INSIGHT, "旧洞察",
                List.of(), null, List.of("x"), "neutral", false, null,
                LocalDateTime.now().minusDays(61), "topic_x", true, "mem_next", null, null);
        memoryService.persist("default",oldSuperseded);
        LocalDate date = oldSuperseded.createdAt().toLocalDate();
        assertEquals(1, memoryService.findByDate("default",date).size());

        memoryService.cleanup("default");
        assertEquals(0, memoryService.findByDate("default",date).size(), "超 60 天 superseded 应被清理");
    }

    @Test
    void touchActive_updatesLastConfirmed() {
        LocalDateTime twoDaysAgo = LocalDateTime.now().minusDays(2);
        Memory m = new Memory("mem_t", "rec_p4", Memory.KIND_INSIGHT, "内容洞察",
                List.of(), null, List.of("y"), "neutral", false, null,
                twoDaysAgo, null, false, null, null, twoDaysAgo);
        memoryService.persist("default",m);

        memoryService.touchActive("default");
        Memory loaded = memoryService.findByRecordId("default","rec_p4").orElseThrow();
        assertNotNull(loaded.lastConfirmed(), "回读确认应写入 lastConfirmed");
        assertTrue(loaded.lastConfirmed().isAfter(twoDaysAgo), "lastConfirmed 应更新到当前");
    }

    // ── 记忆进化 Phase 5：筛选降噪 ──

    @Test
    void persist_factWithoutGain_skips() {
        // fact 类（无洞察/模式/偏好）记忆无信息增量，不沉淀（records 已覆盖原文+摘要）
        Memory fact = new Memory("mem_n1", "rec_n1", Memory.KIND_FACT, "简短摘要",
                List.of(), null, List.of("t"), "neutral", false, null,
                LocalDateTime.now(), null, false, null, null, null);
        memoryService.persist("default",fact);

        assertTrue(memoryService.findByDate("default",LocalDate.now()).isEmpty(), "无增量 fact 不应沉淀");
        assertFalse(memoryService.hasRealMemory("default","rec_n1"), "无增量记忆不算 AI 记忆");
    }

    @Test
    void persist_degradedFact_exemptFromScreening() {
        // 降级记忆（DEGRADED，AI 失败保底）豁免筛选，仍应沉淀
        memoryService.persist("default",Memory.fromContentFallback("rec_n2", "原文"));
        assertTrue(memoryService.hasRealMemory("default","rec_n2") == false, "降级记忆存在但不算 AI 记忆");
        assertFalse(memoryService.findByDate("default",LocalDate.now()).isEmpty(), "降级保底应保留");
    }

    @Test
    void persist_questionActionable_insightNull_isKept() {
        // QUESTION/对话/卡片路径：AI 输出 actionable=true + actionSuggestion 但 insight=null
        // （真实生产形状——P1-1 修复：此类记忆不得被 Phase 5 筛选丢弃）
        AiUnderstanding u = new AiUnderstanding("s", null, null, null,
                List.of("交易"), "neutral", "trading", true, "周五前交报告", null);
        Memory m = Memory.fromUnderstanding("rec_a4", u);
        assertEquals(Memory.KIND_DECISION, m.kind(), "actionable 行动建议应判 decision");

        memoryService.persist("default",m);
        assertTrue(memoryService.hasRealMemory("default","rec_a4"), "actionable 记忆不应被筛选丢弃");
        assertTrue(memoryService.findPendingActions("default").stream().anyMatch(x -> x.recordId().equals("rec_a4")),
                "待办应包含 actionable 记忆");
    }

    @Test
    void parseEntries_bodyContainingSeparator_doesNotSplitEntry() {
        // 2026-08-17 生产修复：正文内容含裸 `---`（笑话/故事分隔线）时，旧正则会在正文内
        // 提前截断 → 后半正文被误当下一条目 frontmatter → createdAt 缺失、记忆丢失（生产 110 条告警）。
        // 新正则要求条目结束的 --- 后必须紧跟 frontmatter 键值行才截断。
        String content = """
                ---
                id: mem_a1
                recordId: rec_a1
                kind: insight
                tags: []
                sentiment: neutral
                actionable: false
                patterns: []
                preferences: []
                suggestion:
                createdAt: 2026-07-01T10:00:00
                ---
                好的阿呆，给你讲个笑话解解闷 😄
                ---
                一只企鹅想跟北极熊做朋友，于是决定去找它。
                ---
                id: mem_a2
                recordId: rec_a2
                kind: insight
                tags: []
                sentiment: neutral
                actionable: false
                patterns: []
                preferences: []
                suggestion:
                createdAt: 2026-07-02T10:00:00
                ---
                第二条记忆正文
                """;
        fileStorage.write("default", "memory/2026/07.md", content);

        List<com.adaiadai.core.kernel.memory.Memory> entries =
                memoryService.findByDate("default", LocalDate.of(2026, 7, 2));
        assertEquals(1, entries.size(), "第二条记忆应完整解析出（不被正文内 --- 截断）");
        assertEquals("mem_a2", entries.get(0).id());
        assertEquals("第二条记忆正文", entries.get(0).summary());
    }
}
