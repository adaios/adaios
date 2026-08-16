package com.adaiadai.core.domain.trading;

import java.util.List;

/**
 * TransferRepository — 转账流水存储端口（data/{userId}/trading/transfers.json）。
 */
public interface TransferRepository {

    /** 读取全部转账记录；无文件返回空列表。 */
    List<TransferRecord> findAll(String userId);

    /** 追加一条转账记录。 */
    void append(String userId, TransferRecord record);
}
