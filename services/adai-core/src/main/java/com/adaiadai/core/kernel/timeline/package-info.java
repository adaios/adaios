/**
 * Timeline — 个人事件流，Record 的时间序列投影。
 * <p>
 * Timeline 不是独立业务实体，而是 Record 发生后自动生成的时间索引和视图。
 * 用于时间查询、回顾浏览、上下文召回。
 * <p>
 * 例如写入一笔交易 Record 后，Timeline 自动生成对应的时间条目。
 * Record 是事实，Timeline 是事实的时间组织。
 */
package com.adaiadai.core.kernel.timeline;
