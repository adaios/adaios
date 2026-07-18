/**
 * AI 模型接入层 — Infrastructure 的一部分。
 * <p>
 * LLM 客户端调用、模型路由、供应商适配。
 * AI 不是独立业务层，而是基础设施能力。
 * <p>
 * 子包：
 * <ul>
 *   <li>{@code llm} — LLM 客户端（OpenAI、Claude、Qwen、DeepSeek）</li>
 *   <li>{@code router} — 模型路由（根据任务类型选择模型）</li>
 *   <li>{@code provider} — 供应商适配层（统一 API 抽象）</li>
 * </ul>
 */
package com.adaiadai.core.infrastructure.ai;
