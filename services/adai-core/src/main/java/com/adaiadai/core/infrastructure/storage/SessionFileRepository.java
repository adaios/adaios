package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.auth.Session;
import com.adaiadai.core.kernel.auth.SessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SessionFileRepository — 基于 JSON 文件的会话存储（RFC 20260901-auth-login）。
 * <p>
 * 会话是系统级数据（不属于任何 {@code data/{userId}/} 用户层），直接读写
 * {@code data/accounts/sessions.json}，不走 FileStorage 的 userId 分层。
 * 文件损坏 → 抛 {@link StorageException}（fail-fast，防止鉴权静默降级成放行）。
 */
@Repository
public class SessionFileRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionFileRepository.class);

    private static final String SESSIONS_FILE = "accounts/sessions.json";

    /** 文件级全局锁：单共享文件跨会话 RMW（沿用 AccountFileRepository B55 模式）。 */
    private static final Object FILE_LOCK = new Object();

    private final Path basePath;
    private final ObjectMapper objectMapper;

    public SessionFileRepository(@Value("${adai.storage.base-path:data}") String basePath) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        // 启动清理由 AuthService（application 层）触发 purgeExpiredBefore——
        // G2 守卫：storage 层不取 now()（时间判定上移到调用方，防路径推导复发）
    }

    private Path sessionsPath() {
        return basePath.resolve(SESSIONS_FILE).normalize();
    }

    @Override
    public Optional<Session> findByTokenHash(String tokenHash) {
        return findAll().stream()
                .filter(s -> Objects.equals(s.tokenHash(), tokenHash))
                .findFirst();
    }

    @Override
    public List<Session> findByUserId(String userId) {
        return findAll().stream()
                .filter(s -> Objects.equals(s.userId(), userId))
                .toList();
    }

    @Override
    public Session save(Session session) {
        synchronized (FILE_LOCK) {
            List<Session> sessions = new ArrayList<>(findAll());
            int idx = -1;
            for (int i = 0; i < sessions.size(); i++) {
                if (sessions.get(i).tokenHash().equals(session.tokenHash())) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                sessions.set(idx, session);
            } else {
                sessions.add(session);
            }
            writeAll(sessions);
            return session;
        }
    }

    @Override
    public boolean deleteByTokenHash(String tokenHash) {
        synchronized (FILE_LOCK) {
            List<Session> sessions = new ArrayList<>(findAll());
            boolean removed = sessions.removeIf(s -> s.tokenHash().equals(tokenHash));
            if (removed) {
                writeAll(sessions);
            }
            return removed;
        }
    }

    @Override
    public int deleteByUserId(String userId) {
        synchronized (FILE_LOCK) {
            List<Session> sessions = new ArrayList<>(findAll());
            int before = sessions.size();
            sessions.removeIf(s -> Objects.equals(s.userId(), userId));
            int removed = before - sessions.size();
            if (removed > 0) {
                writeAll(sessions);
            }
            return removed;
        }
    }

    @Override
    public int purgeExpiredBefore(Instant cutoff) {
        synchronized (FILE_LOCK) {
            List<Session> sessions = new ArrayList<>(findAll());
            int before = sessions.size();
            sessions.removeIf(s -> s.isExpired(cutoff));
            int removed = before - sessions.size();
            if (removed > 0) {
                writeAll(sessions);
            }
            return removed;
        }
    }

    private List<Session> findAll() {
        try {
            if (!Files.exists(sessionsPath())) {
                return List.of();
            }
            String json = Files.readString(sessionsPath(), StandardCharsets.UTF_8);
            List<Session> sessions = objectMapper.readValue(json, new TypeReference<List<Session>>() {});
            return sessions != null ? sessions : List.of();
        } catch (IOException e) {
            log.error("读取会话文件失败: {}", sessionsPath());
            throw new StorageException("读取会话文件失败: " + sessionsPath(), e);
        }
    }

    private void writeAll(List<Session> sessions) {
        try {
            Path target = sessionsPath();
            Files.createDirectories(target.getParent());
            // 原子写：先写临时文件再 move 替换（防写一半崩溃损坏，W-P2-7 模式）
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sessions),
                    StandardCharsets.UTF_8);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("写入会话文件失败: " + sessionsPath(), e);
        }
    }
}
