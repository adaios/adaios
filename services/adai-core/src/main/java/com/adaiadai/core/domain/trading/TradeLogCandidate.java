package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * TradeLogCandidate — 当日交易日志候选（RFC 20260817 交易日志自动归集）。
 * <p>
 * 用户发截图/说「清仓了XX」→ 识别出的当日成交动作，**未落库**（待用户确认）。
 * 同 (symbol, direction, volume 近似, 当日) 去重。
 *
 * @param symbol    股票代码
 * @param name      股票名称
 * @param direction BUY / SELL
 * @param price     成交价（截图识别可得；文字无价格 → null = 不完整）
 * @param volume    数量（文字「清仓了XX」无数量 → null = 不完整）
 * @param source    来源（text=文字 / image=截图）
 * @param complete  是否完整（symbol+direction+price+volume 全有）
 */
public record TradeLogCandidate(
        String symbol,
        String name,
        String direction,
        BigDecimal price,
        Integer volume,
        String source,
        boolean complete
) {
    /** 去重键：同 symbol + 方向 + 当日（数量 ±10% 视为同笔）。 */
    public String dedupeKey() {
        // P1-1（2026-08-18 生产）：symbol 缺失（宽松解析未识别代码）时用 name 兜底，
        // 避免所有无代码候选共用 "unknown" 键互相吞并（生产 09:01-09:02 三次归集只剩 2 笔）。
        String key = (symbol != null && !symbol.isBlank()) ? symbol
                : (name != null && !name.isBlank()) ? name
                : "?";
        return key + ":" + direction;
    }
}
