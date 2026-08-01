package com.adaiadai.core.kernel.context.engine;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.knowledge.KnowledgeSource;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.search.SearchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ContextEngine 单元测试。
 * 覆盖：领域场景路由（内容关键词 → trading/project/life），
 * 保证交易知识源在交易内容时被触发（回归：之前 scene 恒为 note/question，知识从不注入）。
 */
class ContextEngineTest {

    private final IdentityRepository identity = mock(IdentityRepository.class);
    private final RecordRepository records = mock(RecordRepository.class);
    private final TagIndexService tagIndex = mock(TagIndexService.class);
    private final MemoryService memory = mock(MemoryService.class);
    private final CardFileRepository cards = mock(CardFileRepository.class);
    private final SearchService search = mock(SearchService.class);

    /** 记录 KnowledgeSource.enrich() 收到的 scene，验证领域路由。 */
    static class RecordingKnowledgeSource implements KnowledgeSource {
        String receivedScene;
        @Override public String name() { return "test"; }
        @Override public String globalContext() { return "## 全局知识\n"; }
        @Override public String enrich(String scene) { this.receivedScene = scene; return "## 场景知识\n"; }
    }

    /** supports("trading") 的场景贡献者，记录是否被触发。 */
    static class RecordingContributor implements ContextContributor {
        String supportsScene;
        boolean enriched;
        @Override public boolean supports(String scene) { this.supportsScene = scene; return "trading".equals(scene); }
        @Override public String enrich(String identityRef, ContentRecord record) { this.enriched = true; return "## 交易场景上下文\n"; }
    }

    private ContextEngine newEngine(RecordingKnowledgeSource knowledge, RecordingContributor contributor) {
        when(identity.load()).thenReturn(Optional.empty());
        when(records.findAll()).thenReturn(List.of());
        when(tagIndex.findRelatedIds(any(), anyInt())).thenReturn(List.of());
        when(memory.recent(anyInt())).thenReturn(List.of());
        when(search.search(anyString())).thenReturn(List.of());
        return new ContextEngine(identity, records, tagIndex, memory, cards,
                List.of(contributor), List.of(knowledge), search);
    }

    private ContentRecord record(String content) {
        return new ContentRecord("rec_test", "note", "user_input", "标题", content,
                List.of(), LocalDateTime.now());
    }

    @Test
    void tradingContent_routesToTradingScene_triggeringKnowledgeAndContributor() {
        RecordingKnowledgeSource knowledge = new RecordingKnowledgeSource();
        RecordingContributor contributor = new RecordingContributor();
        ContextEngine engine = newEngine(knowledge, contributor);

        engine.compose("note", record("今天买了立昂微，持仓 200 股"), null);

        assertEquals("trading", knowledge.receivedScene, "交易内容应路由到 trading 场景");
        assertEquals("trading", contributor.supportsScene);
        assertEquals(true, contributor.enriched, "trading 场景贡献者应被触发");
    }

    @Test
    void lifeContent_routesToLifeScene() {
        RecordingKnowledgeSource knowledge = new RecordingKnowledgeSource();
        RecordingContributor contributor = new RecordingContributor();
        ContextEngine engine = newEngine(knowledge, contributor);

        engine.compose("note", record("今天天气不错，出去散步了"), null);

        assertEquals("life", knowledge.receivedScene);
        assertEquals(false, contributor.enriched, "life 内容不应触发 trading 贡献者");
    }

    @Test
    void projectContent_routesToProjectScene() {
        RecordingKnowledgeSource knowledge = new RecordingKnowledgeSource();
        RecordingContributor contributor = new RecordingContributor();
        ContextEngine engine = newEngine(knowledge, contributor);

        engine.compose("question", record("B 方向 Phase 4 的任务进度怎么样"), null);

        assertEquals("project", knowledge.receivedScene);
    }

    @Test
    void reviewContent_routesToTradingScene_triggeringKnowledge() {
        // #12：TradingReviewAppService 的合成复盘记录（含"复盘/持仓/买入"关键词）
        // 必须路由到 trading 场景，让交易规则/知识真正进入复盘 prompt。
        RecordingKnowledgeSource knowledge = new RecordingKnowledgeSource();
        RecordingContributor contributor = new RecordingContributor();
        ContextEngine engine = newEngine(knowledge, contributor);

        engine.compose("trading", record("复盘日期：2026-08-01\n## 当日记录\n- 买入立昂微\n## 当前持仓\n持仓 200 股"), null);

        assertEquals("trading", knowledge.receivedScene, "复盘内容应路由到 trading 场景");
        assertEquals(true, contributor.enriched, "trading 贡献者应被触发（复盘注入交易知识）");
    }
}
