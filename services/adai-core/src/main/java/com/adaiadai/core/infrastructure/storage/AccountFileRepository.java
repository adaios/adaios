package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        if (!Files.exists(accountsPath())) {
            log.info("账号文件不存在，预置 seed 管理员 adai");
            List<Account> seed = new ArrayList<>();
            seed.add(new Account(Account.SEED_ADMIN_ID, Account.ROLE_ADMIN, true, LocalDate.of(2026, 8, 2)));
            writeAll(seed);
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
