package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.adaiadai.core.kernel.account.AccountRepository;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
        // 老文件迁移（RFC 20260814）：seed admin 若 plugins 为空 → 补默认（owner 必持有受控插件）。
        // 幂等：正常后 findById 即非空，后续启动不再改动。
        List<Account> accounts = findAll();
        boolean changed = false;
        List<Account> normalized = new ArrayList<>();
        for (Account a : accounts) {
            if (Account.SEED_ADMIN_ID.equals(a.userId()) && a.plugins().isEmpty()) {
                normalized.add(new Account(a.userId(), a.role(), a.enabled(), a.createdAt(), SEED_OWNER_PLUGINS));
                changed = true;
                log.info("迁移：seed admin adai 补默认插件 {}", SEED_OWNER_PLUGINS);
            } else {
                normalized.add(a);
            }
        }
        if (changed) {
            writeAll(normalized);
        }
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
        return findAll().stream().filter(a -> a.userId().equals(userId)).findFirst();
    }

    @Override
    public Account save(Account account) {
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

    @Override
    public boolean delete(String userId) {
        List<Account> accounts = new ArrayList<>(findAll());
        boolean removed = accounts.removeIf(a -> a.userId().equals(userId));
        if (removed) {
            writeAll(accounts);
        }
        return removed;
    }

    private void writeAll(List<Account> accounts) {
        try {
            Files.createDirectories(accountsPath().getParent());
            Files.writeString(accountsPath(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(accounts),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("写入账号文件失败: " + accountsPath(), e);
        }
    }
}
