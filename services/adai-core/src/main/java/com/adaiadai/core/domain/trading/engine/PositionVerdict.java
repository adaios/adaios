package com.adaiadai.core.domain.trading.engine;

/** 仓位判定结论（R81 100万以下分4-5个仓位）。 */
public enum PositionVerdict {
    /** 未超 R81 仓位上限（单票 ≤ 25%）。 */
    OK,
    /** 单票占比超 R81 上限（> 25%）→ suggestion 参考 reduce。 */
    OVER_WEIGHT
}
