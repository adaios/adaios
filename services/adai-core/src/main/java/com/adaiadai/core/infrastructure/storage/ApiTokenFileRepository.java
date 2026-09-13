package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.auth.ApiToken;
import com.adaiadai.core.kernel.auth.ApiTokenRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ApiTokenFileRepository — 外部工具令牌的 JSON 文件存储（2026-09-13 外部入口批）。
 * <p>
 * 落在 {@code data/accounts/api-tokens.json}，与会话同级（同属「系统级凭证」，
 * 不属于任何 {@code data/{userId}/} 用户层）——因为鉴权时按 tokenHash 全局查，
 * 那时还不知道 userId。
 * <p>
 * <b>沿用 {@link SessionFileRepository} 的既定纪律，逐条不放松</b>：
 * <ul>
 *   <li><b>文件级锁</b>：单共享文件跨请求 RMW（沿用 B55 模式）</li>
 *   <li><b>原子写</b>：先写 {@code .tmp} 再 ATOMIC_MOVE 替换（防写一半崩溃）</li>
 *   <li><b>损坏 fail-fast</b>：读取失败抛 {@link StorageException}，**不降级成空列表**——
 *       对凭证而言「当作没有令牌」会让所有外部工具静默失效且查不出原因，
 *       更糟的是接下来的写入会把损坏文件覆盖掉、永久丢掉全部令牌。
 *       抛异常则写路径也不会执行（读先于写），两层都保住。</li>
 * </ul>
 */
@Repository
public class ApiTokenFileRepository implements ApiTokenRepository {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenFileRepository.class);

    private static final String TOKENS_FILE = "accounts/api-tokens.json";

    /** 文件级全局锁：单共享文件跨请求 RMW。 */
    private static final Object FILE_LOCK = new Object();

    private final Path basePath;
    private final ObjectMapper objectMapper;

    public ApiTokenFileRepository(@Value("${adai.storage.base-path:data}") String basePath) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // P2-令牌3（2026-09-14 晚间批）：Jackson 默认**忽略尾部多余内容**——截断/写坏的
                // 文件（如 `[...]` 后面半行垃圾）会被解析成功并读出前半段，随后任一写入
                //（连 lastUsedAt 的 5 分钟节流写盘都算）会把半截列表整体回写 → 静默丢令牌。
                // 类注释自称「损坏 fail-fast」，这里必须真的 fail-fast（与 PushDeviceFileRepository 同款修复）。
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private Path tokensPath() {
        return basePath.resolve(TOKENS_FILE).normalize();
    }

    @Override
    public List<ApiToken> findAll() {
        try {
            if (!Files.exists(tokensPath())) {
                return List.of();
            }
            String json = Files.readString(tokensPath(), StandardCharsets.UTF_8);
            List<ApiToken> tokens = objectMapper.readValue(json, new TypeReference<List<ApiToken>>() {});
            return tokens != null ? tokens : List.of();
        } catch (IOException e) {
            log.error("读取外部令牌文件失败: {}", tokensPath());
            throw new StorageException("读取外部令牌文件失败: " + tokensPath(), e);
        }
    }

    @Override
    public List<ApiToken> findByUserId(String userId) {
        return findAll().stream()
                .filter(t -> Objects.equals(t.userId(), userId))
                .toList();
    }

    @Override
    public Optional<ApiToken> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) return Optional.empty();
        return findAll().stream()
                .filter(t -> Objects.equals(t.tokenHash(), tokenHash))
                .findFirst();
    }

    @Override
    public ApiToken save(ApiToken token) {
        synchronized (FILE_LOCK) {
            List<ApiToken> all = new ArrayList<>(findAll());
            int idx = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).tokenHash().equals(token.tokenHash())) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                all.set(idx, token);
            } else {
                all.add(token);
            }
            writeAll(all);
            return token;
        }
    }

    @Override
    public boolean deleteByTokenHash(String tokenHash) {
        synchronized (FILE_LOCK) {
            List<ApiToken> all = new ArrayList<>(findAll());
            boolean removed = all.removeIf(t -> t.tokenHash().equals(tokenHash));
            if (removed) {
                writeAll(all);
            }
            return removed;
        }
    }

    @Override
    public boolean deleteByPrefix(String userId, String tokenPrefix) {
        if (userId == null || tokenPrefix == null || tokenPrefix.isBlank()) return false;
        synchronized (FILE_LOCK) {
            List<ApiToken> all = new ArrayList<>(findAll());
            boolean removed = all.removeIf(t -> Objects.equals(t.userId(), userId)
                    && Objects.equals(t.tokenPrefix(), tokenPrefix));
            if (removed) {
                writeAll(all);
            }
            return removed;
        }
    }

    @Override
    public int deleteByUserId(String userId) {
        synchronized (FILE_LOCK) {
            List<ApiToken> all = new ArrayList<>(findAll());
            int before = all.size();
            all.removeIf(t -> Objects.equals(t.userId(), userId));
            int removed = before - all.size();
            if (removed > 0) {
                writeAll(all);
            }
            return removed;
        }
    }

    private void writeAll(List<ApiToken> tokens) {
        try {
            Path target = tokensPath();
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tokens),
                    StandardCharsets.UTF_8);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("写入外部令牌文件失败: " + tokensPath(), e);
        }
    }
}
