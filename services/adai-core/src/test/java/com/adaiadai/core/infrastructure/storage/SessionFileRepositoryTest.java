package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.auth.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionFileRepository tests（RFC 20260901-auth-login）。
 * 覆盖：save/find/delete/deleteByUserId/purgeExpired、损坏文件 fail-fast。
 */
class SessionFileRepositoryTest {

    @TempDir
    Path tempDir;

    private SessionFileRepository repo;

    @BeforeEach
    void setUp() {
        repo = new SessionFileRepository(tempDir.toString());
    }

    private Session session(String tokenHash, String userId, Instant expiresAt) {
        Instant now = Instant.now();
        return new Session(tokenHash, userId, now, now, expiresAt);
    }

    @Test
    void saveAndFind_roundTrip() {
        Session s = session("h1", "adai", Instant.now().plusSeconds(3600));
        repo.save(s);
        Optional<Session> found = repo.findByTokenHash("h1");
        assertTrue(found.isPresent());
        assertEquals("adai", found.get().userId());
    }

    @Test
    void save_overwritesSameTokenHash() {
        repo.save(session("h1", "adai", Instant.now().plusSeconds(3600)));
        repo.save(session("h1", "other", Instant.now().plusSeconds(3600)));
        assertEquals("other", repo.findByTokenHash("h1").get().userId());
        assertEquals(1, repo.findByUserId("adai").size() + repo.findByUserId("other").size());
    }

    @Test
    void findByUserId_returnsAllSessions() {
        repo.save(session("h1", "adai", Instant.now().plusSeconds(3600)));
        repo.save(session("h2", "adai", Instant.now().plusSeconds(3600)));
        repo.save(session("h3", "other", Instant.now().plusSeconds(3600)));
        assertEquals(2, repo.findByUserId("adai").size());
        assertEquals(1, repo.findByUserId("other").size());
    }

    @Test
    void deleteByTokenHash_removesSession() {
        repo.save(session("h1", "adai", Instant.now().plusSeconds(3600)));
        assertTrue(repo.deleteByTokenHash("h1"));
        assertTrue(repo.findByTokenHash("h1").isEmpty());
        assertFalse(repo.deleteByTokenHash("h1"));
    }

    @Test
    void deleteByUserId_removesAllSessions() {
        repo.save(session("h1", "adai", Instant.now().plusSeconds(3600)));
        repo.save(session("h2", "adai", Instant.now().plusSeconds(3600)));
        repo.save(session("h3", "other", Instant.now().plusSeconds(3600)));
        assertEquals(2, repo.deleteByUserId("adai"));
        assertTrue(repo.findByUserId("adai").isEmpty());
        assertEquals(1, repo.findByUserId("other").size());
    }

    @Test
    void purgeExpired_removesOnlyExpired() {
        repo.save(session("h_expired", "adai", Instant.now().minusSeconds(10)));
        repo.save(session("h_fresh", "adai", Instant.now().plusSeconds(3600)));
        int purged = repo.purgeExpiredBefore(Instant.now());
        assertEquals(1, purged);
        assertTrue(repo.findByTokenHash("h_expired").isEmpty());
        assertTrue(repo.findByTokenHash("h_fresh").isPresent());
    }

    @Test
    void missingFile_returnsEmpty() {
        assertTrue(repo.findByTokenHash("h1").isEmpty());
        assertEquals(List.of(), repo.findByUserId("adai"));
    }

    @Test
    void corruptedFile_throwsStorageException() throws IOException {
        Path sessions = tempDir.resolve("accounts").resolve("sessions.json");
        Files.createDirectories(sessions.getParent());
        Files.writeString(sessions, "{not valid json");
        assertThrows(StorageException.class, () -> repo.findByTokenHash("h1"));
    }
}
