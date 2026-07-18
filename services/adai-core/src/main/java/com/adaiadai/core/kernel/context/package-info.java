/**
 * Context — 上下文引擎，AdaiOS Kernel 核心能力。
 * <p>
 * Context 不是 AI 辅助模块，而是操作系统内核的核心能力。
 * 负责：收集用户相关信息、组合上下文、构建 AI 输入环境、
 * 管理项目上下文、管理领域上下文。
 * <p>
 * Context Engine 通过 {@code identity}、{@code record}、{@code memory}、
 * {@code knowledge} 以及当前 Domain OS 的状态数据，组合成完整的 Context Package，
 * 提供给 AI 模型使用。
 * <p>
 * 子包：
 * <ul>
 *   <li>{@code prompt} — Prompt 管理（上下文构建规则）</li>
 *   <li>{@code token} — Token 管理（窗口长度、压缩策略、优先级）</li>
 *   <li>{@code policy} — 上下文策略（权限、范围、时效）</li>
 * </ul>
 */
package com.adaiadai.core.kernel.context;
