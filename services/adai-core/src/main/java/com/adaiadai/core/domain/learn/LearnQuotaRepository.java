package com.adaiadai.core.domain.learn;

import java.time.YearMonth;

/**
 * LearnQuotaRepository — 转写配额记账存储端口（RFC 20260912 §3.8 费用可控条 4）。
 * <p>
 * 实现归 {@code infrastructure/storage}（落 {@code data/{userId}/learn/_quota.json}，
 * File First；按月分键 → 月初自动重置，不需要定时任务）。
 */
public interface LearnQuotaRepository {

    /** 读取指定账期用量（无记录 → 全零视图，不落盘）。 */
    LearnQuota view(String userId, YearMonth month);

    /**
     * 记账：本月用量累加（时长 + 费用），返回累加后的视图。
     * <p>
     * 写盘失败抛 {@code StorageException}（fail-visible：记账不成功就不该继续花钱）。
     */
    LearnQuota consume(String userId, YearMonth month, int seconds, double yuan);
}
