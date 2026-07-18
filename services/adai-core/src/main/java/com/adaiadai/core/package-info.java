/**
 * AdaiOS Core — 个人 AI 操作系统核心运行时。
 * <p>
 * 六边形架构（Hexagonal Architecture）：
 * <ul>
 *   <li>{@code interfaces} — 入站适配器（Controller、Listener）</li>
 *   <li>{@code application} — 应用服务/用例编排</li>
 *   <li>{@code domain} — 领域层（含多个 Domain OS）</li>
 *   <li>{@code infrastructure} — 出站适配器（DB、MQ、文件）</li>
 *   <li>{@code ai} — AI 模型接入与上下文管理</li>
 * </ul>
 */
package com.adaiadai.core;
