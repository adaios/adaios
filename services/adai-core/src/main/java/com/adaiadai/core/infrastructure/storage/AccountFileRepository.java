package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AccountFileRepository — 基于 JSON 文件的账号存储实现。
 * <p>
 * 账号是系统级数据（不属于任何 {@code data/{userId}/} 用户层），直接读写
 * {@code data/accounts/accounts.json}，不走 FileStorage 的 userId 分层。
 * 首次启动自动预置 seed 管理员 {@code adai}（防止系统无管理员）。
 */
@Repository
public class AccountFileRepository implements AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(AccountFileRepository.class);

    private static final String ACCOUNTS_FILE = "accounts/accounts.json";

    private final Path basePath;
    private final ObjectMapper objectMapper;

    public AccountFileRepository(@Value("${adai.storage.base-path:data}") String basePath) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
        // freeze #3：LocalDate 统一序列化为 ISO 字符串（"2026-08-02"），
        // 与其余 JSON（memory/tags）风格一致；读取兼容旧数组格式 [年,月,日]
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** seed 管理员默认插件（RFC 20260814：owner 拥有受控插件 trading/project）。 */
    private static final List<String> SEED_OWNER_PLUGINS =
            List.of(PluginRegistry.PLUGIN_TRADING, PluginRegistry.PLUGIN_PROJECT);

    @PostConstruct
    public void init() {
        if (!Files.exists(accountsPath())) {
            log.info("账号文件不存在，预置 seed 管理员 adai（plugins={}）", SEED_OWNER_PLUGINS);
            List<Account> seed = new ArrayList<>();
            seed.add(new Account(Account.SEED_ADMIN_ID, Account.ROLE_ADMIN, true,
                    LocalDate.of(2026, 8, 2), SEED_OWNER_PLUGINS));
            writeAll(seed);
            return;
        }
        // 老文件迁移（RFC 20260814 + REVIEW P1-4）：读**原始字段存在性**而非 isEmpty——
        // PATCH 显式清空（"plugins":[]）不应被下次启动迁移推翻（「删了又出现」K28 镜像）。
        // 幂等：正常后 findById 即非空，后续启动不再改动。
        Map<String, Boolean> rawPresence = rawPluginsFieldPresence();
        List<Account> accounts = findAll();
        boolean changed = false;
        List<Account> normalized = new ArrayList<>();
        for (Account a : accounts) {
            // 仅当 seed admin 的原始 JSON 确实缺 plugins 字段（老文件）才补默认；
            // 字段存在（哪怕为空数组 = PATCH 显式清空）一律不碰。
            if (Account.SEED_ADMIN_ID.equals(a.userId())
                    && !rawPresence.getOrDefault(a.userId(), false)) {
                normalized.add(new Account(a.userId(), a.role(), a.enabled(), a.createdAt(), SEED_OWNER_PLUGINS));
                changed = true;
                log.info("迁移：seed admin adai 老文件无 plugins 字段 → 补默认 {}", SEED_OWNER_PLUGINS);
            } else {
                normalized.add(a);
            }
        }
        if (changed) {
            writeAll(normalized);
        }
    }

    /**
     * 读取原始 JSON 中各账号是否显式携带 plugins 字段（P1-4：区分「老文件无字段」与「PATCH 清空为空数组」）。
     */
    private Map<String, Boolean> rawPluginsFieldPresence() {
        Map<String, Boolean> presence = new HashMap<>();
        try {
            String json = Files.readString(accountsPath(), StandardCharsets.UTF_8);
            JsonNode array = objectMapper.readTree(json);
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    if (node.has("userId")) {
                        presence.put(node.get("userId").asText(), node.has("plugins"));
                    }
                }
            }
        } catch (IOException e) {
            // 与 findAll 同口径：文件损坏 → 抛 StorageException（fail-fast，调用方兜底）
            throw new StorageException("读取账号文件失败: " + accountsPath(), e);
        }
        return presence;
    }

    private Path accountsPath() {
        return basePath.resolve(ACCOUNTS_FILE).normalize();
    }

    @Override
    public List<Account> findAll() {
        try {
            if (!Files.exists(accountsPath())) {
                return List.of();
            }
            String json = Files.readString(accountsPath(), StandardCharsets.UTF_8);
            List<Account> accounts = objectMapper.readValue(json, new TypeReference<List<Account>>() {});
            return accounts != null ? accounts : List.of();
        } catch (IOException e) {
            log.error("读取账号文件失败: {}", accountsPath());
            throw new StorageException("读取账号文件失败: " + accountsPath(), e);
        }
    }

    @Override
    public Optional<Account> findById(String userId) {
        return findAll().stream().filter(a -> Objects.equals(a.userId(), userId)).findFirst();
    }

    @Override
    public Account save(Account account) {
        synchronized (FILE_LOCK) {
        List<Account> accounts = new ArrayList<>(findAll());
        int idx = -1;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).userId().equals(account.userId())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            accounts.set(idx, account);
        } else {
            accounts.add(account);
        }
        writeAll(accounts);
        return account;
        }
    }

    @Override
    public Account mergePlugins(String userId, List<String> add, List<String> remove) {
        // REVIEW S-R2：账号级锁——合并与写回同一临界区，并发 toggle 顺序执行各自合并最新状态
        synchronized ((userId != null ? userId : "").intern()) {
            Account existing = findById(userId)
                    .orElseThrow(() -> new StorageException("账号不存在: " + userId));
            List<String> merged = new ArrayList<>(existing.plugins());
            if (add != null) {
                for (String p : add) {
                    if (p != null && !merged.contains(p)) merged.add(p);
                }
            }
            if (remove != null) {
                merged.removeAll(remove);
            }
            Account updated = new Account(existing.userId(), existing.role(),
                    existing.enabled(), existing.createdAt(), merged);
            return save(updated);
        }
    }

    @Override
    public boolean delete(String userId) {
        synchronized (FILE_LOCK) {
        List<Account> accounts = new ArrayList<>(findAll());
        boolean removed = accounts.removeIf(a -> a.userId().equals(userId));
        if (removed) {
            writeAll(accounts);
        }
        return removed;
        }
    }

    // P1-4（2026-08-17 走查）：单共享文件跨用户 RMW——per-user 锁挡不住并发，
    // save/delete/writeAll 全部走文件级全局锁（B55）
    private static final Object FILE_LOCK = new Object();

    private void writeAll(List<Account> accounts) {
        try {
            Path target = accountsPath();
            Files.createDirectories(target.getParent());
            // W-P2-7（2026-08-17）：原子写——先写临时文件再 move 替换。
            // 此前 Files.writeString 截断直写，写一半崩溃 → accounts.json 损坏 → 全系统起不来
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(accounts),
                    StandardCharsets.UTF_8);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("写入账号文件失败: " + accountsPath(), e);
        }
    }
}
