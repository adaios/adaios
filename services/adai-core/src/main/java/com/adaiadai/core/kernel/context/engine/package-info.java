/**
 * Context Engine — Kernel 核心能力。
 * <p>
 * 负责将 Identity、Record、Memory 等组件组合为面向 AI 的上下文包（Context Package）。
 * 每个场景（交易、生活、研究）可定义自己的 Context 组合规则。
 * <p>
 * Context Engine 是 AdaiOS 区别于传统 CRUD 应用的核心：
 * AI 不直接访问数据库，而是通过 Context Engine 获取结构化、场景化的上下文。
 */
package com.adaiadai.core.kernel.context.engine;
