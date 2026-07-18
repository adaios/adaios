/**
 * Memory — 个人记忆管理。
 * <p>
 * 不同于 Record（原始事件）和 Context（当前会话），
 * Memory 是经过筛选、关联、提炼后的个人长期记忆。
 * 负责：记忆的存储、检索、关联、遗忘策略。
 * <p>
 * 与 Context 的关系：Memory 提供跨会话的长期数据，Context Engine
 * 从中选取相关片段组合为当前会话上下文。
 */
package com.adaiadai.core.kernel.memory;
