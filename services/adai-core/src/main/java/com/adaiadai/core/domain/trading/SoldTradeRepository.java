package com.adaiadai.core.domain.trading;

import java.util.List;

/**
 * SoldTradeRepository — 清仓股存储端口（File First：data/{userId}/trading/sold.json）。
 */
public interface SoldTradeRepository {

    /** 读取全部清仓股；无文件/损坏返回空列表。 */
    List<SoldTrade> findAll(String userId);

    /** 全量保存清仓股。 */
    void saveAll(String userId, List<SoldTrade> trades);
}
