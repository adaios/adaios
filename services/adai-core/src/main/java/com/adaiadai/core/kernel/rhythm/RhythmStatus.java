package com.adaiadai.core.kernel.rhythm;

/**
 * RhythmStatus — 节律状态（RFC 20260923 §四 D1）。
 * <p>
 * <b>刻意没有 DONE</b>：节律是周期性复现的习惯，没有「完成」这个终点，
 * 只有「暂停」与「退役」（对标 shisad Active Attention 的 workflow_state：closed 是终态，
 * 而 recurring 类永不进 closed）。
 */
public enum RhythmStatus {
    /** 生效中——命中日会作为背景出现在简报里（只作背景，不提醒）。 */
    ACTIVE,
    /** 暂停——保留条目但不再命中（例如「这几个月不发版了」）。 */
    PAUSED,
    /** 退役——彻底不再生效（历史保留，不删除）。 */
    RETIRED
}
