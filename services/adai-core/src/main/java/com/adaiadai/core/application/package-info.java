/**
 * 应用层 — 用例编排与事务边界。
 * <p>
 * 负责跨 Domain 的流程编排、事务管理、权限校验。
 * 不包含领域逻辑，仅协调 {@code domain} 层的多个聚合。
 */
package com.adaiadai.core.application;
