package com.adaiadai.core.domain.trading;

/**
 * MarketPushEvent — 行情异动推送事件（Layer 5 主动推送）。
 *
 * @param id        事件 ID（IdGenerator 生成，如 push_20260806_093000123）
 * @param symbol    触发异动的股票代码
 * @param name      股票名称
 * @param message   推送文案（中文，直接可展示）
 * @param type      异动类型（loss=止损预警 / gain=放飞提示 / break-cost=跌破成本线 / session=时段 / buy-point=买点）
 * @param time      触发时间（HH:mm）
 * @param title     原标题（B9-1 2026-08-23，P1-推送1 根因修复）：推送发起方传入的标题
 *                  （早盘计划/午间跟踪/尾盘建议/今日操作确认/买点提醒等）——落库透传，
 *                  前端按标题 switch 的徽章配色与「确认并入账」判定依赖它；旧数据（2026-08-23 前）
 *                  无此字段 → null，读取侧按 type 兜底映射
 * @param expiresAt 过期时间（RFC 20260825 §7 推送定时消失，ISO LocalDateTime，可空=旧数据）：
 *                  行情类当天收盘（15:30）消失、汇总类（操作总结/复盘）次日 23:59 消失；
 *                  读取侧过滤过期条目，用户无需手动删时效推送
 */
public record MarketPushEvent(
        String id,
        String symbol,
        String name,
        String message,
        String type,
        String time,
        String title,
        String expiresAt
) {

    /** 兼容旧 6 参构造（B9-1：旧调用/测试无 title → null，读取侧 type 兜底）。 */
    public MarketPushEvent(String id, String symbol, String name, String message, String type, String time) {
        this(id, symbol, name, message, type, time, null, null);
    }

    /** 兼容旧 7 参构造（RFC 20260825：无 expiresAt → null，按类型默认保留期）。 */
    public MarketPushEvent(String id, String symbol, String name, String message, String type, String time, String title) {
        this(id, symbol, name, message, type, time, title, null);
    }
}
