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
import java.util.function.Function;

/**
 * AccountSnapshotFileRepository — 账户快照文件存储（data/{userId}/trading/account.json）。
 * <p>
 * P0-2（2026-08-23）：per-user 读写锁——account.json 是多写路径共享的 RMW 载体
 * （recordTrade / importCashQuery / recordTransfer / setPrincipal / closeAccountUpdate），
 * 统一经 {@link #update} 原子读-改-写，防并发互相覆盖（文件原子写只防写坏、不防 RMW 覆盖）。
 */
@Repository
public class AccountSnapshotFileRepository implements AccountSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(AccountSnapshotFileRepository.class);
    private static final String PATH = "trading/account.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** per-user 写锁（P0-2，2026-08-23 注释修正）：同一 userId 的读-改-写串行。
     *  C6（隔离审查战略）：锁为**单实例内**进程锁（ConcurrentHashMap）——TradingAppService.tradeLock
     *  （业务 RMW）+ 本锁（文件原子写）双层；多实例部署同写 data/ 即失效（当前单实例，注释如实）。
     *  跨文件一致性（positions/account/流水）无原子手段——收盘与交易并发窗口已文档化（trading-features §8）。
     *  P2-交易28（2026-08-29）：原 ConcurrentHashMap 锁池按 userId 无界增长（#179 任意 userId 可撑爆内存）——
     *  改固定 16 条带锁（个人系统并发度低，条带串行可接受；从根上消除 map 增长）。 */
    private static final int LOCK_STRIPES = 16;
    private final Object[] locks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new Object();
    }

    private static Object lockFor(Object[] stripes, String userId) {
        int h = (userId != null ? userId : "default").hashCode();
        return stripes[(h ^ (h >>> 16)) & (stripes.length - 1)];
    }

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
                    num(n.path("principal")),
                    parseDate(n.path("snapshotDate").asText())));
        } catch (Exception e) {
            log.warn("读取账户快照失败 | userId={} | {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String userId, AccountSnapshot s) {
        update(userId, cur -> s);
    }

    @Override
    public AccountSnapshot update(String userId, Function<Optional<AccountSnapshot>, AccountSnapshot> fn) {
        Object lock = lockFor(locks, userId);
        synchronized (lock) {
            Optional<AccountSnapshot> current = findLatest(userId);
            AccountSnapshot next = fn.apply(current);
            if (next == null) return null; // fn 决定不保存（无快照且不初始化）
            // B6-4（2026-08-23，P1-交易11）：写失败抛 StorageException（不再静默返回 null）——
            // 调用方须感知账目未落盘，不得按成功继续（recordTrade 等已接 try/catch 告警）
            write(userId, next);
            return next;
        }
    }

    /** 写文件；失败抛 StorageException（B6-4：不再静默 warn 假装成功）。 */
    private void write(String userId, AccountSnapshot s) {
        try {
            var n = MAPPER.createObjectNode();
            n.put("assets", s.assets());
            n.put("cash", s.cash());
            n.put("available", s.available());
            n.put("withdrawable", s.withdrawable());
            n.put("marketValue", s.marketValue());
            n.put("pnl", s.pnl());
            n.put("todayPnl", s.todayPnl());
            n.put("principal", s.principal());
            n.put("snapshotDate", s.snapshotDate().toString());
            fileStorage.write(userId, PATH, MAPPER.writeValueAsString(n));
        } catch (StorageException e) {
            throw e; // 存储层已抛的原样透传
        } catch (Exception e) {
            log.error("保存账户快照失败——账目未落盘（持仓/流水可能已写入）| userId={} | {}", userId, e.getMessage());
            throw new StorageException("保存账户快照失败（账目未落盘）: " + e.getMessage(), e);
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
