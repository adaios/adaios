/**
 * 基础设施层 — 技术适配与外部集成。
 * <p>
 * 遵循依赖倒置原则，所有实现类实现 {@code kernel} 或 {@code domain} 层定义的接口。
 * <p>
 * 子包：
 * <ul>
 *   <li>{@code database} — 数据库访问（JPA Repository、JDBC）</li>
 *   <li>{@code storage} — 文件存储（本地文件系统、云存储）</li>
 *   <li>{@code search} — 搜索（全文搜索、向量检索）</li>
 *   <li>{@code ai} — AI 模型接入（LLM 客户端、路由、供应商适配）</li>
 * </ul>
 */
package com.adaiadai.core.infrastructure;
