package com.adaiadai.core.kernel.memory;

/**
 * MemorySummary — 记忆摘要（用于 Context Engine 选取相关记忆）。
 *
 * @param date    记忆日期
 * @param summary 记忆摘要
 */
public record MemorySummary(String date, String summary) {
}
