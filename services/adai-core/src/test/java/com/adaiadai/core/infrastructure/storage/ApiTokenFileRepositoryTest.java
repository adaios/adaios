package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.auth.ApiToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiTokenFileRepositoryTest — 外部工具令牌的落盘（2026-09-13 外部入口批）。
 * <p>
 * 覆盖：往返读写、账号隔离、按前缀撤销的 owner 限定，以及一条**关键的 fail-fast**：
 * 文件损坏时必须抛异常而不是降级成空列表——凭证降级成「没有令牌」会让所有外部工具
 * 静默失效且查不出原因，更糟的是随后的写入会把损坏文件覆盖掉、永久丢掉全部令牌。
 */
class ApiTokenFileRepositoryTest {

    @TempDir
    Path tempDir;

    private ApiTokenFileRepository repo() {
        return new ApiTokenFileRepository(tempDir.toString());
    }

    private Path tokensFile() {
        return tempDir.resolve("accounts/api-tokens.json");
    }

    private ApiToken token(String hash, String prefix, String userId, String label) {
        return new ApiToken(hash, prefix, userId, label, Set.of("learn:digest"),
                Instant.parse("2026-09-13T10:00:00Z"), null);
    }

    @Test
    void saveAndFind_roundTrip() {
        ApiTokenFileRepository repo = repo();
        repo.save(token("h1", "adai_aaaa1111", "adai", "快捷指令"));

        Optional<ApiToken> found = repo.findByTokenHash("h1");

        assertTrue(found.isPresent());
        assertEquals("adai", found.get().userId());
        assertEquals("快捷指令", found.get().label());
        assertEquals(Set.of("learn:digest"), found.get().scopes());
        assertEquals(Instant.parse("2026-09-13T10:00:00Z"), found.get().createdAt());
    }

    @Test
    void noFile_returnsEmpty_notError() {
        assertTrue(repo().findAll().isEmpty());
        assertTrue(repo().findByTokenHash("nope").isEmpty());
    }

    @Test
    void findByUserId_isolatesAccounts() {
        ApiTokenFileRepository repo = repo();
        repo.save(token("h1", "adai_a1", "adai", "我的"));
        repo.save(token("h2", "adai_b1", "someone-else", "别人的"));

        assertEquals(1, repo.findByUserId("adai").size());
        assertEquals("我的", repo.findByUserId("adai").get(0).label());
        assertEquals(2, repo.findAll().size(), "全局查找仍能看到全部（鉴权路径需要）");
    }

    @Test
    void deleteByPrefix_isOwnerScoped() {
        ApiTokenFileRepository repo = repo();
        repo.save(token("h1", "adai_a1", "adai", "我的"));
        repo.save(token("h2", "adai_a1", "someone-else", "前缀巧合相同"));

        assertFalse(repo.deleteByPrefix("adai", "adai_none"),
                "不存在的 prefix 删除失败");
        assertTrue(repo.deleteByPrefix("adai", "adai_a1"));
        assertEquals(0, repo.findByUserId("adai").size(), "本人的令牌已删");
        assertEquals(0, repo.findByUserId("adai").size());
        assertEquals(1, repo.findByUserId("someone-else").size(),
                "前缀相同但属于别人 → 不该被删掉");
    }

    @Test
    void deleteByUserId_removesAllOfThatAccount() {
        ApiTokenFileRepository repo = repo();
        repo.save(token("h1", "adai_a1", "adai", "a"));
        repo.save(token("h2", "adai_a2", "adai", "b"));
        repo.save(token("h3", "adai_b1", "someone-else", "c"));

        assertEquals(2, repo.deleteByUserId("adai"));
        assertEquals(0, repo.findByUserId("adai").size());
        assertEquals(1, repo.findAll().size());
    }

    @Test
    void save_updatesExistingByHash_notDuplicating() {
        ApiTokenFileRepository repo = repo();
        repo.save(token("h1", "adai_a1", "adai", "旧"));
        repo.save(new ApiToken("h1", "adai_a1", "adai", "新", Set.of("learn:digest"),
                Instant.parse("2026-09-13T10:00:00Z"), Instant.parse("2026-09-13T11:00:00Z")));

        assertEquals(1, repo.findAll().size());
        assertEquals("新", repo.findByTokenHash("h1").orElseThrow().label());
        assertEquals(Instant.parse("2026-09-13T11:00:00Z"),
                repo.findByTokenHash("h1").orElseThrow().lastUsedAt());
    }

    /**
     * 凭证文件的损坏必须**被发现**。
     * <p>
     * 降级成空列表是这里最危险的「看似稳妥」选择：所有外部工具会静默失效、日志干净、
     * 而且下一次写入直接把损坏内容覆盖掉——令牌全丢且无从追查。
     */
    @Test
    void corruptFile_failsFast_onRead() throws Exception {
        Files.createDirectories(tokensFile().getParent());
        Files.writeString(tokensFile(), "{ 这不是合法 JSON", StandardCharsets.UTF_8);

        assertThrows(StorageException.class, () -> repo().findAll());
        assertThrows(StorageException.class, () -> repo().findByTokenHash("h1"));
    }

    /** 读失败时写路径也不该执行——否则损坏文件会被覆盖，令牌永久丢失。 */
    @Test
    void corruptFile_writeAlsoRefuses_contentPreserved() throws Exception {
        Files.createDirectories(tokensFile().getParent());
        String broken = "{ 这不是合法 JSON";
        Files.writeString(tokensFile(), broken, StandardCharsets.UTF_8);

        assertThrows(StorageException.class, () -> repo().save(token("h1", "adai_a1", "adai", "x")));
        assertEquals(broken, Files.readString(tokensFile(), StandardCharsets.UTF_8),
                "损坏文件必须原样保留，供人工修复");
    }

    @Test
    void writesAtomically_noTmpFileLeftBehind() {
        ApiTokenFileRepository repo = repo();
        repo.save(token("h1", "adai_a1", "adai", "x"));

        assertTrue(Files.exists(tokensFile()));
        assertFalse(Files.exists(tokensFile().resolveSibling("api-tokens.json.tmp")),
                "原子写完成后不该残留 .tmp");
    }
}
