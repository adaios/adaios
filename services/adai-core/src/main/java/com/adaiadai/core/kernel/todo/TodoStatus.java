package com.adaiadai.core.kernel.todo;

/**
 * TodoStatus — 待办状态（两态，RFC 20260917 §4.1）。
 * <p>
 * DOING / CANCELLED 已随看板一起退役：取消即删除，不做中间态。
 */
public enum TodoStatus {
    /** 未完成（清单上半区）。 */
    OPEN,
    /** 已完成（清单下半区，默认折叠）。 */
    DONE
}
