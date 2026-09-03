package com.adaiadai.core.domain.trading;

/**
 * TradingMarketStagePort — 活跃市值区间端口（domain 侧接口，P1-4 C7 分层红线）。
 * <p>
 * domain/application 层通过本接口读写用户手动判定的活跃市值区间，
 * infra 的 {@code TradingMarketStageRepository} 实现之（防 domain 依赖 infra 具体类）。
 */
public interface TradingMarketStagePort {

    /** 读取用户手动判定的活跃市值区间；无记录/损坏 → null（回退 current.md 规则推断）。 */
    TradingMarketStage findByUser(String userId);

    /** 保存用户手动判定的活跃市值区间（写盘失败抛异常，调用方处理）。 */
    void save(String userId, TradingMarketStage stage);
}
