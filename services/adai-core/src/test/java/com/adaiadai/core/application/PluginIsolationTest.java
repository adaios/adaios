package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.knowledge.KnowledgeSource;
import com.adaiadai.core.kernel.knowledge.LifeKnowledgeSource;
import com.adaiadai.core.kernel.knowledge.ProjectKnowledgeSource;
import com.adaiadai.core.kernel.knowledge.TradingKnowledgeSource;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.search.SearchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多用户插件隔离集成测试（RFC 20260814 T2.7）——真实 KnowledgeSource 挂 ContextEngine，
 * 验证：adai（trading/project 插件）注入交易知识，alice（无插件）不注入且 domain 收敛 life。
 * <p>
 * 依赖 monorepo 内真实 os/{trading,project,life}-os/11-context 知识文件（test cwd = services/adai-core）。
 */
class PluginIsolationTest {

    private final IdentityRepository identity = mock(IdentityRepository.class);
    private final RecordRepository records = mock(RecordRepository.class);
    private final TagIndexService tagIndex = mock(TagIndexService.class);
    private final MemoryService memory = mock(MemoryService.class);
    private final CardFileRepository cards = mock(CardFileRepository.class);
    private final SearchService search = mock(SearchService.class);

    private ContextEngine engine() {
        when(identity.load(any())).thenReturn(Optional.empty());
        when(records.findAll(any())).thenReturn(List.of());
        when(tagIndex.findRelatedIds(any(), any(), anyInt())).thenReturn(List.of());
        when(memory.recent(any(), anyInt())).thenReturn(List.of());
        when(search.search(any(), anyString())).thenReturn(List.of());

        // 真实知识源：trading/project 为插件域，life 为基础服务
        List<KnowledgeSource> sources = List.of(
                new TradingKnowledgeSource("../../os/trading-os/11-context"),
                new ProjectKnowledgeSource("../../os/project-os/11-context"),
                new LifeKnowledgeSource(memory, "../../os/life-os/11-context"));

        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById("adai")).thenReturn(Optional.of(
                new Account("adai", Account.ROLE_ADMIN, true, LocalDate.of(2026, 8, 2),
                        List.of(PluginRegistry.PLUGIN_TRADING, PluginRegistry.PLUGIN_PROJECT))));
        when(accounts.findById("alice")).thenReturn(Optional.of(
                new Account("alice", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), List.of())));
        PluginService pluginService = new PluginService(accounts, new PluginRegistry());

        return new ContextEngine(identity, records, tagIndex, memory, cards,
                List.of(), sources, search, pluginService);
    }

    private ContentRecord record(String content) {
        return new ContentRecord("rec_isolation", "note", "user_input", "标题", content,
                List.of(), LocalDateTime.now());
    }

    @Test
    void tradingKnowledge_onlyInjectedForPluginOwner() {
        ContextEngine engine = engine();

        String adaiPrompt = engine.compose("adai", "note", record("今天买入立昂微，持仓 200 股"), null).prompt();
        String alicePrompt = engine.compose("alice", "note", record("今天买入立昂微，持仓 200 股"), null).prompt();

        assertTrue(adaiPrompt.contains("## 交易系统知识"), "adai 应注入交易系统知识");
        assertFalse(alicePrompt.contains("交易系统知识"), "alice（无 trading 插件）不应注入交易知识");
        assertFalse(alicePrompt.contains("规则调用表"), "alice 不应收到任何交易规则内容");
    }

    @Test
    void d5_domainEnum_andRules_convergeToLife_forNoPluginUser() {
        ContextEngine engine = engine();

        String alicePrompt = engine.compose("alice", "note", record("今天买入立昂微，持仓 200 股"), null).prompt();
        String adaiPrompt = engine.compose("adai", "note", record("今天买入立昂微，持仓 200 股"), null).prompt();

        assertTrue(alicePrompt.contains("\"life(生活)\""), "无插件用户 domain 枚举只剩 life");
        assertFalse(alicePrompt.contains("trading(交易)"), "无插件用户 domain 枚举不应含 trading");
        assertFalse(alicePrompt.contains("→ trading"), "无插件用户 domain 判定规则不应含 trading");
        assertTrue(adaiPrompt.contains("trading(交易)"), "adai 保留 trading domain 判定");
    }

    @Test
    void lifeKnowledge_notGated_injectedForAllUsers() {
        ContextEngine engine = engine();

        String alicePrompt = engine.compose("alice", "note", record("今天去公园散步，心情不错"), null).prompt();
        assertTrue(alicePrompt.contains("生活系统"), "life 基础服务知识不门控，任何用户可注入");
    }
}
