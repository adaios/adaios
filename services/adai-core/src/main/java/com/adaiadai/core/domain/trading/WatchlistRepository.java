package com.adaiadai.core.domain.trading;

import java.util.List;

/**
 * WatchlistRepository — 自选股存储端口（File First：data/{userId}/trading/watchlist.json）。
 */
public interface WatchlistRepository {

    /** 读取全部自选股；无文件/损坏返回空列表。 */
    List<WatchlistItem> findAll(String userId);

    /** 全量保存自选股。 */
    void saveAll(String userId, List<WatchlistItem> items);

    /** 归档当前自选列表（覆盖策略的撤销保险，2026-08-27）：复制到
     *  {@code trading/watchlist.json.bak-<suffix>}。默认空实现（mock/无归档后端不受影响）。 */
    default void archive(String userId, String suffix) {}
}
