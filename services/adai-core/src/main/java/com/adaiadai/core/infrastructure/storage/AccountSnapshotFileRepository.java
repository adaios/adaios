package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * AccountSnapshotFileRepository — 账户快照文件存储（data/{userId}/trading/account.json）。
 */
@Repository
public class AccountSnapshotFileRepository implements AccountSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(AccountSnapshotFileRepository.class);
    private static final String PATH = "trading/account.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileStorage fileStorage;

    public AccountSnapshotFileRepository(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public Optional<AccountSnapshot> findLatest(String userId) {
        String content = fileStorage.read(userId, PATH);
        if (content == null || content.isBlank()) return Optional.empty();
        try {
            var n = MAPPER.readTree(content);
            return Optional.of(new AccountSnapshot(
                    num(n.path("assets")),
                    num(n.path("cash")),
                    num(n.path("available")),
                    num(n.path("withdrawable")),
                    num(n.path("marketValue")),
                    num(n.path("pnl")),
                    num(n.path("todayPnl")),
                    parseDate(n.path("snapshotDate").asText())));
        } catch (Exception e) {
            log.warn("读取账户快照失败 | userId={} | {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String userId, AccountSnapshot s) {
        try {
            var n = MAPPER.createObjectNode();
            n.put("assets", s.assets());
            n.put("cash", s.cash());
            n.put("available", s.available());
            n.put("withdrawable", s.withdrawable());
            n.put("marketValue", s.marketValue());
            n.put("pnl", s.pnl());
            n.put("todayPnl", s.todayPnl());
            n.put("snapshotDate", s.snapshotDate().toString());
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(n));
        } catch (Exception e) {
            log.warn("保存账户快照失败 | userId={} | {}", userId, e.getMessage());
        }
    }

    private BigDecimal num(com.fasterxml.jackson.databind.JsonNode n) {
        return n.isMissingNode() || n.isNull() ? BigDecimal.ZERO : n.decimalValue();
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
