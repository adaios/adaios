/**
 * 展现层 — 外部请求的入口边界。
 * <p>
 * 处理 HTTP 请求、WebSocket、定时任务等外部触发。
 * 仅做协议适配与参数校验，不包含业务逻辑。
 * 委派请求到 {@code application} 层。
 */
package com.adaiadai.core.interfaces;
