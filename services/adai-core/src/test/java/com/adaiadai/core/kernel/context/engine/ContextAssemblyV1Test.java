package com.adaiadai.core.kernel.context.engine;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.context.policy.ContextAssemblyPolicy;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.knowledge.KnowledgeSource;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.search.SearchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 上下文装配 v1 口径测试（RFC 20260929 批 1）。
 * <p>
 * 锁住批 1 的四条行为口径（legacy 的既有断言仍在 {@code ContextEngineTest} 里，本类不重复）：
 * <ol>
 *   <li><b>按轮次分档</b>：短档（≤3 轮）不注入相关记录/检索，且**不读**完整近期记忆；中档读记忆不检索；长档才检索；</li>
 *   <li><b>短档保留极少核心</b>（D1 拍板）：只取未取代的 {@code kind=preference}，带日期与冲突消解口径，条数受配额；</li>
 *   <li><b>收掉「最近 N 条」</b>：无标签回退的相关记录条数由 20 收到 {@code fallbackRecentMax}（v1=2）；</li>
 *   <li><b>L4 命中才注入</b>：交易知识/域全局只在内容命中交易时注入（生活话题不再背交易规则）。</li>
 * </ol>
 * 反向可验证性：以上每条的断言在批 1 之前都必然失败（短档不读记忆、fallback=20、生活话题注入交易知识）。
 */
class ContextAssemblyV1Test {

    private static final String USER = "default";
    private static final String CARD = "card_test";

    private final IdentityRepository identity = mock(IdentityRepository.class);
    private final RecordRepository records = mock(RecordRepository.class);
    private final TagIndexService tagIndex = mock(TagIndexService.class);
    private final MemoryService memory = mock(MemoryService.class);
    private final CardFileRepository cards = mock(CardFileRepository.class);
    private final SearchService search = mock(SearchService.class);
    private final AccountRepository accounts = mock(AccountRepository.class);

    /** name() = trading → 会被 pluginForKnowledge 识别为 trading 插件知识源。 */
    static class TradingSource implements KnowledgeSource {
        @Override public String name() { return PluginRegistry.PLUGIN_TRADING; }
        @Override public String globalContext(String userId) { return "## 交易哲学\n"; }
        @Override public String enrich(String userId, String scene) { return "## 交易系统状态\n"; }
    }

    private PluginService pluginService() {
        return new PluginService(accounts, new PluginRegistry());
    }

    private void grant(String... plugins) {
        when(accounts.findById(USER)).thenReturn(Optional.of(
                new Account(USER, Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), List.of(plugins))));
    }

    /** 默认用户（启用 trading，模拟真实单用户）。只 stub「不分档也会被调用」的那些依赖。 */
    private void stubBasics() {
        when(identity.load(any())).thenReturn(Optional.empty());
        when(tagIndex.findRelatedIds(any(), any(), anyInt())).thenReturn(List.of());
        when(memory.recentActive(any(), anyInt())).thenReturn(List.of());
        when(search.search(any(), anyString())).thenReturn(List.of());
    }

    private ContextEngine engine(ContextAssemblyPolicy policy, List<KnowledgeSource> sources) {
        stubBasics();
        grant(PluginRegistry.PLUGIN_TRADING);
        return new ContextEngine(identity, records, tagIndex, memory, cards,
                List.of(), sources, search, pluginService(), policy);
    }

    /** 造一张 turns 条消息（偶数 = 完整回合）的卡。 */
    private CardRecord card(int turns) {
        List<CardRecord.Turn> ts = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            ts.add(new CardRecord.Turn(i % 2 == 0, "第" + (i + 1) + "轮发言", "10:0" + (i % 10)));
        }
        return new CardRecord(CARD, "conversation", "active", List.of(), ts, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private ContentRecord current(String content) {
        return new ContentRecord("rec_cur", "note", "user_input", "标题", content,
                List.of(), LocalDateTime.now());
    }

    private Memory pref(String summary, int daysAgo) {
        return new Memory("mem_" + Math.abs(summary.hashCode()), "rec_x", null, "preference", summary,
                List.of(), List.of(), List.of("t"), "neutral", false, null,
                LocalDateTime.now().minusDays(daysAgo), null, false, null, null, null);
    }

    private List<ContentRecord> manyRecords(int n) {
        List<ContentRecord> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new ContentRecord("rec_" + i, "note", "user_input", "t" + i, "内容" + i,
                    List.of(), LocalDateTime.now()));
        }
        return list;
    }

    /** 相关历史块里的条目数（每条以 "- [" 开头）。 */
    private int countRelated(String relatedBlock) {
        if (relatedBlock == null || relatedBlock.isBlank()) return 0;
        return (int) relatedBlock.lines().filter(l -> l.startsWith("- [")).count();
    }

    // ── ① 按轮次分档 ──

    @Test
    void shortTier_skipsRelatedRecordsAndFullMemory() {
        // 先建 engine（其内部 stubBasics 会设一遍默认值），再设本用例的具体 stub——顺序不能反
        ContextEngine engine = engine(ContextAssemblyPolicy.v1(), List.of());
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(2)));
        when(records.findAll(USER)).thenReturn(manyRecords(10));

        ContextPackage pkg = engine.compose(USER, "question", current("当前问题"), CARD);

        assertFalse(pkg.prompt().contains("## 相关历史记录"), "短档不应注入相关历史");
        assertFalse(pkg.prompt().contains("## 搜索相关历史记录"), "短档不应注入检索结果");
        // 短档**不走完整近期记忆**（loadMemorySummary 独有的 touchActive 未被调用），
        // 只为「核心偏好」读一次 30 天窗口；检索整层不发起——这才是分档的真实收益。
        verify(memory, never()).touchActive(any());
        verify(memory).recentActive(USER, 30);
        verify(search, never()).search(any(), anyString());
        // 批 1 ③：prompt 里不再重复一份对话历史（改由 messages 承载）
        assertFalse(pkg.prompt().contains("## 当前会话对话历史"), "v1 下 prompt 不应重复对话历史");
    }

    @Test
    void tierBoundaries_3_4_9_10() {
        // 3 轮 → 短档：不读完整记忆（loadMemorySummary 用的是 recentActive）
        MemoryService m3 = mock(MemoryService.class);
        SearchService s3 = mock(SearchService.class);
        composeWith(3, ContextAssemblyPolicy.v1(), m3, s3);
        verify(m3, never()).touchActive(any());   // 短档：不走完整记忆
        verify(m3).recentActive(USER, 30);        // 只读核心偏好窗口
        verify(s3, never()).search(any(), anyString());

        // 4 轮 → 中档：读完整记忆，仍不检索
        MemoryService m4 = mock(MemoryService.class);
        SearchService s4 = mock(SearchService.class);
        composeWith(4, ContextAssemblyPolicy.v1(), m4, s4);
        verify(m4).touchActive(USER);             // 中档：走完整近期记忆
        verify(s4, never()).search(any(), anyString());

        // 9 轮 → 仍是中档
        MemoryService m9 = mock(MemoryService.class);
        SearchService s9 = mock(SearchService.class);
        composeWith(9, ContextAssemblyPolicy.v1(), m9, s9);
        verify(m9).touchActive(USER);             // 仍是中档
        verify(s9, never()).search(any(), anyString());

        // 10 轮 → 长档：允许检索
        MemoryService m10 = mock(MemoryService.class);
        SearchService s10 = mock(SearchService.class);
        composeWith(10, ContextAssemblyPolicy.v1(), m10, s10);
        verify(s10).search(any(), anyString());
    }

    private ContextPackage composeWith(int turns, ContextAssemblyPolicy policy,
                                       MemoryService mem, SearchService srch) {
        when(identity.load(any())).thenReturn(Optional.empty());
        when(records.findAll(any())).thenReturn(List.of());
        CardFileRepository cardRepo = mock(CardFileRepository.class);
        when(cardRepo.findById(USER, CARD)).thenReturn(Optional.of(card(turns)));
        when(mem.recentActive(any(), anyInt())).thenReturn(List.of());
        grant(PluginRegistry.PLUGIN_TRADING);
        return new ContextEngine(identity, records, tagIndex, mem, cardRepo,
                List.of(), List.of(), srch, pluginService(), policy)
                .compose(USER, "question", current("当前问题"), CARD);
    }

    // ── ② 短档保留极少核心（D1） ──

    @Test
    void shortTier_keepsCorePreference_withDateAndConflictRule() {
        ContextEngine engine = engine(ContextAssemblyPolicy.v1(), List.of());
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(2)));
        when(memory.recentActive(USER, 30)).thenReturn(List.of(
                pref("喜欢茉莉花茶", 1), pref("日线级别操作", 5), pref("第三条不该出现", 9)));

        ContextPackage pkg = engine.compose(USER, "question", current("当前问题"), CARD);

        assertEquals("", pkg.relatedRefs().get(0), "v1 下 cardContext 应为空（前文走 messages）");
        String core = pkg.relatedRefs().get(2);
        assertTrue(core.contains("近期偏好"), "核心记忆段应存在（近 30 天窗口，不再自称长期）");
        assertTrue(core.contains("以你刚说的为准"), "必须带冲突消解口径（新话优先）");
        assertTrue(core.contains("喜欢茉莉花茶"));
        assertTrue(core.contains(LocalDate.now().minusDays(1).toString()), "每条要带日期");
        assertFalse(core.contains("第三条不该出现"), "核心记忆条数受配额限制（默认 2 条）");
    }

    @Test
    void shortTier_coreMemoryDisabledByConfig() {
        // core-memory-max: 0 → 短档彻底不注入记忆（留一条可配置的后路）
        ContextAssemblyPolicy off = new ContextAssemblyPolicy(true, 3, 9, 0, 200, 2);
        ContextEngine engine = engine(off, List.of());
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(2)));
        when(memory.recentActive(USER, 30)).thenReturn(List.of(pref("喜欢茉莉花茶", 1)));

        ContextPackage pkg = engine.compose(USER, "question", current("当前问题"), CARD);

        assertEquals("", pkg.relatedRefs().get(2));
    }

    // ── ③ 收掉「最近 20 条」 ──

    @Test
    void fallbackRecent_twoInV1_twentyInLegacy() {
        // v1（中档 5 轮，走到 loadRelatedRecords 的 fallback 分支）
        ContextEngine v1Engine = engine(ContextAssemblyPolicy.v1(), List.of());
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(5)));
        when(records.findAll(USER)).thenReturn(manyRecords(30));
        ContextPackage v1 = v1Engine.compose(USER, "question", current("当前问题"), CARD);
        assertEquals(2, countRelated(v1.relatedRefs().get(1)), "v1 的 fallback 应只取 2 条");

        // legacy 对照：仍是 20 条（同一份代码，只是策略不同）
        ContextEngine legacyEngine = engine(ContextAssemblyPolicy.legacy(), List.of());
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(5)));
        when(records.findAll(USER)).thenReturn(manyRecords(30));
        ContextPackage legacy = legacyEngine.compose(USER, "question", current("当前问题"), CARD);
        assertEquals(20, countRelated(legacy.relatedRefs().get(1)), "legacy 必须保持 20 条（回归保护）");
    }

    // ── ④ L4 命中才注入 ──

    @Test
    void tradingKnowledge_onlyOnTradingContent() {
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(5)));
        when(memory.recent(any(), anyInt())).thenReturn(List.of());
        when(records.findAll(any())).thenReturn(List.of());
        when(search.search(any(), anyString())).thenReturn(List.of());
        when(identity.load(any())).thenReturn(Optional.empty());
        grant(PluginRegistry.PLUGIN_TRADING);

        ContextEngine engine = new ContextEngine(identity, records, tagIndex, memory, cards,
                List.of(), List.of(new TradingSource()), search, pluginService(), ContextAssemblyPolicy.v1());

        // 生活内容 → 不注入交易知识（生产实测：生活话题背了 512 字符交易哲学）
        ContextPackage life = engine.compose(USER, "question", current("今天天气不错"), CARD);
        assertFalse(dynamicRefs(life).contains("交易哲学"), "生活话题不应注入交易知识（断言实际下发通道）");

        // 交易内容 → 注入
        ContextPackage trading = engine.compose(USER, "question", current("今天买入了 600487"), CARD);
        assertTrue(dynamicRefs(trading).contains("交易哲学"), "交易内容应注入交易知识（断言实际下发通道）");
    }

    // ── 稳定前缀（批 1 ④ 的载体） ──

    @Test
    void stableSystem_presentInV1_nullInLegacy() {
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(5)));

        ContextPackage v1 = engine(ContextAssemblyPolicy.v1(), List.of())
                .compose(USER, "question", current("当前问题"), CARD);
        assertNotNull(v1.stableSystem(), "v1 必须产出稳定前缀（供唯一一条 system 使用）");
        assertTrue(v1.stableSystem().contains("当前日期"), "稳定前缀含日期");

        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(5)));
        ContextPackage legacy = engine(ContextAssemblyPolicy.legacy(), List.of())
                .compose(USER, "question", current("当前问题"), CARD);
        assertNull(legacy.stableSystem(), "legacy 不产出稳定前缀（走原有 3 条 system）");
        assertTrue(legacy.prompt().contains("## 当前会话对话历史"), "legacy 仍在 prompt 里带对话历史");
    }

    /** v1 的实际下发内容 = relatedRefs 里非空的部分（与 `DeepSeekAiClient.buildDynamicRefs` 同口径）。 */
    private String dynamicRefs(ContextPackage pkg) {
        return String.join("\n", pkg.relatedRefs());
    }

    // ── 对抗审查随修项（2026-09-29） ──

    @Test
    void noCard_treatedAsLongTier_notShort() {
        // 审查 P1-2：无卡片场景（随手记 / 重补 / 复盘走三参 compose）必须按 LONG 处理——
        // 否则 turns=0 被判短档，相关历史与近期记忆一起被砍，且 loadMemorySummary 停调
        // → touchActive（记忆进化 Phase 4 的回读确认）对新记录停止累积。
        ContextEngine engine = engine(ContextAssemblyPolicy.v1(), List.of());
        when(records.findAll(USER)).thenReturn(manyRecords(5));

        ContextPackage pkg = engine.compose(USER, "note", current("随手记一条"), null);

        assertTrue(pkg.relatedRefs().get(1).contains("## 相关历史记录"),
                "无卡场景应走 LONG：相关历史必须注入");
        verify(memory).touchActive(USER);
    }

    @Test
    void stableSystem_fromEngine_isIdenticalAcrossTurns() {
        // 审查 P2-4：客户端那条稳定性测试两侧硬编码同一常量（≈自比较）。这里验证**引擎产出**的
        // stableSystem 在不同轮次下逐字相同——这才是缓存前缀能命中的真正前提。
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(3)));
        String s3 = engine(ContextAssemblyPolicy.v1(), List.of())
                .compose(USER, "question", current("当前问题"), CARD).stableSystem();

        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(9)));
        String s9 = engine(ContextAssemblyPolicy.v1(), List.of())
                .compose(USER, "question", current("当前问题"), CARD).stableSystem();

        assertEquals(s3, s9, "引擎产出的稳定前缀不随轮次变化");
    }

    @Test
    void shortTier_coreMemoryTruncated_neverLeavesBareHeader() {
        // 审查 P3-2：配额装不下任何一条时，宁可不注入，也不能只留一个光秃秃的标题。
        ContextAssemblyPolicy tiny = new ContextAssemblyPolicy(true, 3, 9, 2, 5, 2);
        ContextEngine engine = engine(tiny, List.of());
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(2)));
        when(memory.recentActive(USER, 30)).thenReturn(List.of(pref("这是一条比较长的偏好描述内容", 1)));

        ContextPackage pkg = engine.compose(USER, "question", current("当前问题"), CARD);

        assertEquals("", pkg.relatedRefs().get(2), "装不下就不注入");
    }

    @Test
    void v1_injectsSearchAndKnowledgeIntoSentPayload() {
        // 审查 P1-1 的回归：v1 不走 buildContextFromPrompt，检索结果与领域知识必须**显式进 relatedRefs**，
        // 否则就是「算完即丢」（对话里 0 注入）。
        when(cards.findById(USER, CARD)).thenReturn(Optional.of(card(10)));
        when(search.search(any(), anyString())).thenReturn(List.of(
                new com.adaiadai.core.kernel.search.SearchResult("rec_1", "note", "旧标题", "旧内容",
                        List.of(), LocalDateTime.now())));
        when(identity.load(any())).thenReturn(Optional.empty());
        grant(PluginRegistry.PLUGIN_TRADING);

        ContextEngine engine = new ContextEngine(identity, records, tagIndex, memory, cards,
                List.of(), List.of(new TradingSource()), search, pluginService(), ContextAssemblyPolicy.v1());
        ContextPackage pkg = engine.compose(USER, "question", current("今天买入了 600487"), CARD);

        String sent = dynamicRefs(pkg);
        assertTrue(sent.contains("## 搜索相关历史记录"), "检索结果必须进实际下发内容");
        assertTrue(sent.contains("交易哲学"), "命中领域的知识源必须进实际下发内容");
    }

}
