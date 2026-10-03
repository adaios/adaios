package com.adaiadai.core.domain.trading;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * TradingPlanRepository — 次日操作计划存储端口（RFC 20261003-trading-plan-and-review-loop §二）。
 * 实现见 {@code infrastructure/storage/TradingPlanFileRepository}（File First）。
 */
public interface TradingPlanRepository {

    /** 读某一天的计划（不存在返回 empty——**不编造空计划**）。 */
    Optional<TradingPlan> find(String userId, LocalDate date);

    /** 覆盖写入某一天的计划。 */
    void save(String userId, TradingPlan plan);

    /** 已有计划的日期（倒序，供列表/「哪天没写」判断）。 */
    List<LocalDate> dates(String userId);
}
