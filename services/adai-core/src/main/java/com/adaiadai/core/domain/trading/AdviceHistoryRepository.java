package com.adaiadai.core.domain.trading;

import java.time.LocalDate;
import java.util.List;

/**
 * AdviceHistoryRepository — 持仓建议留痕存储端口（RFC 20260905 B①，File First）。
 * <p>
 * 建议快照落 {@code data/{userId}/trading/advice-history/{yyyy-MM}.json}——按月 JSON 数组
 * （对齐 trades/ 月文件模式，跨月回查按需扫月）。B 闭环「卖出回查」读本端口。
 */
public interface AdviceHistoryRepository {

    /** 追加一条建议留痕（读当月 → 追加 → 原子覆盖写回；per-user 锁防并发丢条目）。 */
    void append(String userId, AdviceEntry entry);

    /** 查询某月起的所有建议条目（含该月）；无文件/损坏返回空列表。 */
    List<AdviceEntry> findByMonth(String userId, LocalDate month);

    /**
     * 查询某 symbol 最近 N 天建议（跨月扫描回查，B② 卖出回查用）；按生成时刻倒序。
     *
     * @param today 查询基准日（调用方传 now——G2：storage 层不得出现 now()，路径/窗口推导一律外部注入）
     */
    List<AdviceEntry> findBySymbolRecent(String userId, String symbol, int days, LocalDate today);

    /**
     * 回填某条留痕的「结果」（RFC 20260922 A 批 A3，2026-09-22）。
     *
     * <p>按 {@code entryId} 在 {@code date} 所属月文件里就地更新 {@code outcome}（其余字段原样保留）；
     * **已有 outcome 的条目直接返回 true（幂等：结果只写一次，不覆盖）**。
     * 锁内 read-modify-write（与 append 同一把条带锁）。
     *
     * @param date 条目日期（决定落哪个 {@code yyyy-MM} 文件——不扫全部月份）
     * @return true = 已写入或本就已回填；false = 没找到该条目 / 写失败（调用方按「下次再试」处理，**不静默当成功**）
     */
    boolean updateOutcome(String userId, LocalDate date, String entryId, String outcomeJson);
}
