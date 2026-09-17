package com.adaiadai.core.kernel.plugin;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PluginService — 按账号解析启用插件 + D5 domain 收敛（RFC 20260814）。
 */
class PluginServiceTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PluginService service = new PluginService(accounts, new PluginRegistry());

    @BeforeEach
    void stubAccounts() {
        // adai = owner 受控插件（含存量残留脏数据 "project"，应被过滤）；alice = 新用户无插件；ghost = 不存在
        when(accounts.findById("adai")).thenReturn(Optional.of(
                new Account("adai", Account.ROLE_ADMIN, true, LocalDate.of(2026, 8, 2),
                        List.of(PluginRegistry.PLUGIN_TRADING, "project"))));
        when(accounts.findById("alice")).thenReturn(Optional.of(
                new Account("alice", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), List.of())));
        when(accounts.findById("bob")).thenReturn(Optional.of(
                new Account("bob", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2),
                        List.of(PluginRegistry.PLUGIN_TRADING, "hacking"))));
        when(accounts.findById("ghost")).thenReturn(Optional.empty());
    }

    @Test
    void enabledPlugins_byAccount() {
        assertEquals(java.util.Set.of("trading"), service.enabledPlugins("adai"),
                "RFC 20260917 撤 project 后，存量账号里的 \"project\" 被自动过滤，只剩 trading");
        assertFalse(service.enabledPlugins("adai").contains("project"), "残留 project 名不应出现");
        assertTrue(service.enabledPlugins("alice").isEmpty(), "新用户无插件 = 只有基础服务");
        assertTrue(service.enabledPlugins("ghost").isEmpty(), "账号不存在 → 空");
    }

    @Test
    void enabledPlugins_filtersUnknownPluginNames() {
        assertEquals(java.util.Set.of("trading"), service.enabledPlugins("bob"),
                "未知插件名被过滤，防脏数据");
    }

    @Test
    void hasPlugin() {
        assertTrue(service.hasPlugin("adai", PluginRegistry.PLUGIN_TRADING));
        assertFalse(service.hasPlugin("alice", PluginRegistry.PLUGIN_TRADING));
        assertFalse(service.hasPlugin("alice", null));
    }

    @Test
    void gateDomain_keepsEnabledPlugins() {
        assertEquals("trading", service.gateDomain("adai", "trading"));
        // RFC 20260917：project 插件已撤 → 即使账号有残留脏数据，domain=project 也一律收敛 life
        assertEquals("life", service.gateDomain("adai", "project"));
        assertEquals("life", service.gateDomain("adai", "life"));
    }

    @Test
    void gateDomain_convergesToLife_forUnavailablePlugins() {
        // D5：无 trading 插件 → AI 判定的 domain 收敛为 life；null/未知同样归 life
        assertEquals("life", service.gateDomain("alice", "trading"));
        assertEquals("life", service.gateDomain("alice", "project"), "project 插件已撤，无插件用户更归 life");
        assertEquals("life", service.gateDomain("alice", "life"), "life 本身不动");
        assertEquals("life", service.gateDomain("alice", null));
    }

    @Test
    void invalidate_clearsCache() {
        // 08-15 后端×6（2026-08-17）：缓存 + 失效——改插件后下一次读取重解析
        assertEquals(java.util.Set.of("trading"), service.enabledPlugins("adai"));
        when(accounts.findById("adai")).thenReturn(Optional.of(
                new Account("adai", Account.ROLE_ADMIN, true, LocalDate.of(2026, 8, 2),
                        List.of(PluginRegistry.PLUGIN_TRADING, PluginRegistry.PLUGIN_LEARN))));
        assertEquals(java.util.Set.of("trading"), service.enabledPlugins("adai"),
                "TTL 内缓存旧值");
        service.invalidate("adai");
        assertEquals(java.util.Set.of("trading", "learn"), service.enabledPlugins("adai"),
                "invalidate 后读到新值");
    }
}