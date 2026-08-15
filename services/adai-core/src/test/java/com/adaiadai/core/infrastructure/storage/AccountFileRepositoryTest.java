package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AccountFileRepository 测试。
 * 验证 seed 预置、JSON 读写、upsert、删除。
 */
class AccountFileRepositoryTest {

    @TempDir
    Path tempDir;

    private AccountFileRepository repo() {
        return new AccountFileRepository(tempDir.toString());
    }

    @Test
    void init_seedsAdminAccount() {
        var repo = repo();
        repo.init();
        List<Account> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals(Account.SEED_ADMIN_ID, all.get(0).userId());
        assertEquals(Account.ROLE_ADMIN, all.get(0).role());
        assertTrue(all.get(0).enabled());
        // RFC 20260814：seed admin 默认持有受控插件 trading/project（owner）
        assertEquals(List.of(PluginRegistry.PLUGIN_TRADING, PluginRegistry.PLUGIN_PROJECT), all.get(0).plugins());
    }

    @Test
    void init_migratesExistingSeedAdmin_missingPluginsGetsDefaults() throws Exception {
        // RFC 20260814 迁移：老 accounts.json 的 seed admin 无 plugins 字段 → 启动补默认（幂等）
        String legacy = """
                [ { "userId" : "adai", "role" : "admin", "enabled" : true, "createdAt" : "2026-08-02" } ]
                """;
        Files.createDirectories(tempDir.resolve("accounts"));
        Files.writeString(tempDir.resolve("accounts/accounts.json"), legacy, StandardCharsets.UTF_8);

        var repo = repo();
        repo.init();

        assertEquals(List.of(PluginRegistry.PLUGIN_TRADING, PluginRegistry.PLUGIN_PROJECT),
                repo.findById(Account.SEED_ADMIN_ID).get().plugins(),
                "seed admin 老文件无 plugins → 迁移补默认");

        // 幂等：再 init 不重复写
        repo.init();
        assertEquals(List.of(PluginRegistry.PLUGIN_TRADING, PluginRegistry.PLUGIN_PROJECT),
                repo.findById(Account.SEED_ADMIN_ID).get().plugins());
    }

    @Test
    void init_doesNotGrantPluginsToNonSeedUsers() throws Exception {
        // 迁移只补 seed admin；普通用户老文件无 plugins → 保持空（新用户只有基础服务）
        String legacy = """
                [ { "userId" : "alice", "role" : "user", "enabled" : true, "createdAt" : "2026-08-02" } ]
                """;
        Files.createDirectories(tempDir.resolve("accounts"));
        Files.writeString(tempDir.resolve("accounts/accounts.json"), legacy, StandardCharsets.UTF_8);

        var repo = repo();
        repo.init();

        assertTrue(repo.findById("alice").get().plugins().isEmpty(),
                "普通用户老文件无 plugins → 保持空，不误授予插件");
    }

    @Test
    void init_patchClearedPlugins_notOverriddenByMigration() throws Exception {
        // REVIEW P1-4：「删了又出现」K28 镜像——PATCH 显式清空（"plugins":[]）后，启动迁移不得再补默认。
        // 区分「老文件无字段」与「字段存在但为空」：只迁移前者。
        String json = """
                [ { "userId" : "adai", "role" : "admin", "enabled" : true, "createdAt" : "2026-08-02", "plugins" : [ ] } ]
                """;
        Files.createDirectories(tempDir.resolve("accounts"));
        Files.writeString(tempDir.resolve("accounts/accounts.json"), json, StandardCharsets.UTF_8);

        var repo = repo();
        repo.init();

        assertTrue(repo.findById(Account.SEED_ADMIN_ID).get().plugins().isEmpty(),
                "PATCH 显式清空的 plugins 不应被启动迁移补回默认（管理端清空 = 用户决策）");
    }

    @Test
    void save_roundtrip_preservesPlugins() {
        var repo = repo();
        repo.init();
        repo.save(new Account("alice", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2),
                List.of(PluginRegistry.PLUGIN_TRADING)));

        assertEquals(List.of(PluginRegistry.PLUGIN_TRADING), repo.findById("alice").get().plugins(),
                "plugins 应序列化后 round-trip 保留");
    }

    @Test
    void saveAndFindAll_roundtrip() {
        var repo = repo();
        repo.init();
        repo.save(new Account("alice", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2)));

        List<Account> all = repo.findAll();
        assertEquals(2, all.size());
        Optional<Account> alice = repo.findById("alice");
        assertTrue(alice.isPresent());
        assertEquals(Account.ROLE_USER, alice.get().role());
        assertTrue(alice.get().enabled());
    }

    @Test
    void save_upsertsExistingAccount() {
        var repo = repo();
        repo.init();
        repo.save(new Account("alice", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2)));
        repo.save(new Account("alice", Account.ROLE_USER, false, LocalDate.of(2026, 8, 2)));

        assertEquals(2, repo.findAll().size());
        assertFalse(repo.findById("alice").get().enabled());
    }

    @Test
    void delete_removesAccount() {
        var repo = repo();
        repo.init();
        repo.save(new Account("bob", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2)));

        assertTrue(repo.delete("bob"));
        assertFalse(repo.delete("bob"), "重复删除应返回 false");
        assertTrue(repo.findById("bob").isEmpty());
    }

    @Test
    void findById_missing_returnsEmpty() {
        var repo = repo();
        repo.init();
        assertTrue(repo.findById("ghost").isEmpty());
    }

    @Test
    void createdAt_serializesAsIsoString_notArray() throws Exception {
        // freeze #3：LocalDate 序列化为 ISO 字符串，与其他 JSON 风格一致（曾为 [年,月,日] 数组）
        var repo = repo();
        repo.init();

        String json = Files.readString(tempDir.resolve("accounts/accounts.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"createdAt\" : \"2026-08-02\""),
                "createdAt 应为 ISO 字符串，实际: " + json);
        assertFalse(json.contains("[ 2026, 8, 2 ]"), "不应再序列化为数组: " + json);
    }

    @Test
    void findAll_readsLegacyArrayCreatedAt() throws Exception {
        // REVIEW #202 回归：旧版 accounts.json 用 [年,月,日] 数组存 createdAt，
        // JavaTimeModule 的 LocalDate 反序列化应兼容数组格式（_fromArray），账号不因格式迁移丢失。
        String legacy = """
                [ { "userId" : "olduser", "role" : "user", "enabled" : true, "createdAt" : [ 2026, 7, 15 ] } ]
                """;
        Files.createDirectories(tempDir.resolve("accounts"));
        Files.writeString(tempDir.resolve("accounts/accounts.json"), legacy, StandardCharsets.UTF_8);

        var repo = repo();
        repo.init();

        List<Account> accounts = repo.findAll();
        assertEquals(1, accounts.size(), "旧数组格式应被解析，不丢账号");
        assertEquals("olduser", accounts.get(0).userId());
        assertEquals(LocalDate.of(2026, 7, 15), accounts.get(0).createdAt(),
                "旧数组 [2026,7,15] 应解析为 LocalDate 2026-07-15");
    }

    @Test
    void mergePlugins_addAndRemove_atomic() {
        // REVIEW S-R2：服务端合并——基于当前存储状态 add/remove（读改写在同一临界区）
        var repo = repo();
        repo.init();
        repo.save(new Account("alice", "user", true, LocalDate.of(2026, 8, 2),
                List.of("trading")));

        Account afterAdd = repo.mergePlugins("alice", List.of("project"), List.of());
        assertEquals(List.of("trading", "project"), afterAdd.plugins(), "add 应追加");

        Account afterRemove = repo.mergePlugins("alice", List.of(), List.of("trading"));
        assertEquals(List.of("project"), afterRemove.plugins(), "remove 应移除");
    }

    @Test
    void mergePlugins_addDuplicate_idempotent() {
        var repo = repo();
        repo.init();
        repo.save(new Account("alice", "user", true, LocalDate.of(2026, 8, 2),
                List.of("trading")));

        Account merged = repo.mergePlugins("alice", List.of("trading"), List.of());
        assertEquals(List.of("trading"), merged.plugins(), "重复 add 幂等（不重复追加）");
    }
}
