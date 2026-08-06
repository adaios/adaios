package com.adaiadai.core.domain.trading;

/**
 * MarketPushEvent — 行情异动推送事件（Layer 5 主动推送）。
 *
 * @param id      事件 ID（IdGenerator 生成，如 push_20260806_093000123）
 * @param symbol  触发异动的股票代码
 * @param name    股票名称
 * @param message 推送文案（中文，直接可展示）
 * @param type    异动类型（loss=止损预警 / gain=放飞提示 / break-cost=跌破成本线）
 * @param time    触发时间（HH:mm）
 */
public record MarketPushEvent(
        String id,
        String symbol,
        String name,
        String message,
        String type,
        String time
) {
}
