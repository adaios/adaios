package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.account.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
}
