package com.adaiadai.core.domain.trading;

import java.util.Optional;

/**
 * AccountSnapshotRepository — 账户快照存储端口（File First：data/{userId}/trading/account.json）。
 */
public interface AccountSnapshotRepository {

    /** 读取最近一次账户快照；无文件返回 empty。 */
    Optional<AccountSnapshot> findLatest(String userId);

    /** 保存账户快照。 */
    void save(String userId, AccountSnapshot snapshot);
}
