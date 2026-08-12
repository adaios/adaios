package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.account.Account;
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
}
