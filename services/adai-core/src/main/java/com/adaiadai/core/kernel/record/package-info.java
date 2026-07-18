/**
 * Record — 最小个人事件单元。
 * <p>
 * 用户输入、系统采集、外部数据的最小记录单元。
 * 来源包括：用户主动输入、系统自动采集、外部数据导入。
 * <p>
 * Record 是一切上层能力的事实基础。Timeline、Memory、Knowledge 都基于 Record 构建。
 * <p>
 * 采用 File First：每个 Record 作为文件存储，数据库做索引。
 */
package com.adaiadai.core.kernel.record;
