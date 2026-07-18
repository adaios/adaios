/**
 * 领域层 — Domain OS 领域能力。
 * <p>
 * Domain OS 是运行在 Kernel 之上的领域操作系统。
 * 每个 Domain 封装完整的业务逻辑和领域模型，对外只暴露接口（SPI）。
 * <p>
 * 当前 Domain：
 * <ul>
 *   <li>{@code trading} — 金融交易（Trading OS，含研究）</li>
 *   <li>{@code life} — 个人生活管理（Life OS）</li>
 *   <li>{@code project} — 项目管理（Project OS）</li>
 * </ul>
 * <p>
 * Domain 之间不直接依赖，跨域协作通过 {@code application} 层编排。
 */
package com.adaiadai.core.domain;
