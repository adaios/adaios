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
}
