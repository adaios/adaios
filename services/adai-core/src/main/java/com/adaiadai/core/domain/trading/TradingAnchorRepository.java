package com.adaiadai.core.domain.trading;

import java.time.LocalDate;

/**
 * TradingAnchorRepository — 券商快照锚定存储端口（File First：
 * data/{userId}/trading/snapshot-anchor.json）。
 * <p>
 * 锚定是「全量覆盖导入」（持仓 replace / 资金股份查询）落地的元信息，供增量
 * 推导（sync 回放/手动成交/转账补记）判定「该笔是否已包含在券商快照内」，
 * 防 P2-交易34 类重复入账。
 */
public interface TradingAnchorRepository {

    /** 读取锚定记录；文件缺失/损坏 → 空锚定（视为从未全量导入，不做防重）。 */
    SnapshotAnchor find(String userId);

    /** 记录一次持仓全量 replace 导入（date 通常 = 导入日）。 */
    void updatePositionsReplace(String userId, LocalDate date);

    /** 记录一次资金股份查询导入（date 通常 = 导入日）。 */
    void updateCashImport(String userId, LocalDate date);
}
