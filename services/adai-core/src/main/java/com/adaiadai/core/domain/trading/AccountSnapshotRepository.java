package com.adaiadai.core.domain.trading;

import java.util.Optional;
import java.util.function.Function;

/**
 * AccountSnapshotRepository — 账户快照存储端口（File First：data/{userId}/trading/account.json）。
 */
public interface AccountSnapshotRepository {

    /** 读取最近一次账户快照；无文件返回 empty。 */
    Optional<AccountSnapshot> findLatest(String userId);

    /** 保存账户快照。 */
    void save(String userId, AccountSnapshot snapshot);

    /**
     * 原子读-改-写（P0-2，2026-08-23）：per-user 锁内 findLatest → fn 计算 → save。
     * <p>
     * 解决 account.json 多写路径（recordTrade / importCashQuery / recordTransfer /
     * setPrincipal / closeAccountUpdate）并发 RMW 互相覆盖（原子写只防写坏、不防覆盖）。
     * <p>
     * 语义（B6-4，2026-08-23）：fn 返回 {@code null} = 不保存（无快照且不初始化）→ 返回 null；
     * <b>写失败抛 {@code StorageException}（RuntimeException 子类）</b>——调用方须感知，勿按成功继续。
     *
     * @param userId 用户
     * @param fn     接收当前快照（无则 empty），返回新快照；返回 {@code null} 表示不保存
     * @return 新快照（fn 返回 null 时为 null）
     * @throws RuntimeException 写文件失败（实现抛 StorageException）
     */
    AccountSnapshot update(String userId, Function<Optional<AccountSnapshot>, AccountSnapshot> fn);
}
