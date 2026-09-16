package com.adaiadai.core.domain.learn;

import java.time.LocalDate;
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

    /**
     * 读取某日**图片整理**用量（张数；无记录 → 0，不落盘）。
     * <p>
     * P2-learn26（2026-09-16）：图片源是新的花钱点（单次最多 3 次 VLM + 1 次 LLM），
     * 此前唯一限流是「同 user 单任务」——没有日上限，手指快就能一直烧。这里给图片链
     * 一个**轻量日配额**（不弹确认、不打断正常使用，只防无限刷）。
     */
    int imagesOn(String userId, LocalDate day);

    /**
     * 图片整理记账：当日张数累加，返回累加后的张数。
     * <p>
     * 写盘失败抛 {@code StorageException}（fail-visible：记不上账就不该继续花钱）。
     *
     * @param count 本次受理的图片张数（允许负数 = 回退，如受理失败时回收占位）
     */
    int consumeImages(String userId, LocalDate day, int count);
}
