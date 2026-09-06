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
}
