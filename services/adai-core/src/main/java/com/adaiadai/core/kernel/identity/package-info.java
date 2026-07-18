/**
 * Identity — 个人长期稳定信息层。
 * <p>
 * 描述"这个人是谁"，不是简单数据库表，不是动态 Context。
 * 包含：稳定信息（名称、偏好、工作方式）、行为模式（思考风格、风险偏好）、AI 协作规则。
 * <p>
 * 采用 File First：数据以 Markdown 文件形式存储在 {@code data/identity/}，
 * 数据库仅做索引。Context Engine 读取 Identity 文件参与上下文组合。
 */
package com.adaiadai.core.kernel.identity;
