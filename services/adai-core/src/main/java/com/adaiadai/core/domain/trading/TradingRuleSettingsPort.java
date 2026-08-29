package com.adaiadai.core.domain.trading;

/**
 * TradingRuleSettingsPort — 交易规则参数配置端口（domain 侧接口，P1-4 C7 分层红线修复）。
 * <p>
 * 2026-08-30 审查：domain 层不得直接依赖 infrastructure 具体类——
 * {@code DefaultTradingRuleEngine} 原直接注入 infra Repository，违反
 * {@code interfaces → application → domain/kernel ← infrastructure} 依赖方向。
 * 本接口在 domain 定义，infra 的 {@code TradingRuleSettingsRepository} 实现之。
 */
public interface TradingRuleSettingsPort {

    /** 读取用户规则参数配置；无文件/损坏/非法 → 默认值（fail-closed）。 */
    TradingRuleSettings findByUser(String userId);

    /** 保存用户规则参数配置（写盘失败抛异常，调用方处理）。 */
    void save(String userId, TradingRuleSettings settings);

    /** 用户是否有规则配置（无 = 未启用任何自定义规则，走默认/降级）。 */
    boolean exists(String userId);
}
