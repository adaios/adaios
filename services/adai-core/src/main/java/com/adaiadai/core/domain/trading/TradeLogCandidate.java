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
 * @param tradeDate 成交日期（2026-08-27：截图表格「日期」列提取；当日成交单无日期列 → null = 归集当天）
 * @param source    来源（text=文字 / image=截图）
 * @param complete  是否完整（symbol+direction+price+volume 全有）
 */
public record TradeLogCandidate(
        String symbol,
        String name,
        String direction,
        BigDecimal price,
        Integer volume,
        LocalDate tradeDate,
        String source,
        boolean complete
) {
    /** 去重键：同 symbol + 方向（volume 维度由 {@link #sameTrade} 按 ±10% 区间判定）。 */
    public String dedupeKey() {
        // P1-1（2026-08-18 生产）：symbol 缺失（宽松解析未识别代码）时用 name 兜底，
        // 避免所有无代码候选共用 "unknown" 键互相吞并（生产 09:01-09:02 三次归集只剩 2 笔）。
        String key = (symbol != null && !symbol.isBlank()) ? symbol
                : (name != null && !name.isBlank()) ? name
                : "?";
        return key + ":" + direction;
    }

    /**
     * 是否与另一候选视为同一笔（B6-2，2026-08-23，P1-交易12）：
     * 同 symbol + 方向，且 volume 差 ≤ ±10%（相对大者）——`volume/10*10` 固定 10 股桶
     * 过宽吞笔（10 vs 19 同桶）/过窄漏去重（100 vs 110 分开 → confirm 双落库）双缺陷；
     * 任一方 volume 缺失（不完整候选）按 symbol+direction 同笔（去重键语义不变）。
     */
    public boolean sameTrade(TradeLogCandidate other) {
        if (other == null) return false;
        if (!dedupeKey().equals(other.dedupeKey())) return false;
        if (volume == null || other.volume == null || volume <= 0 || other.volume <= 0) return true;
        int max = Math.max(volume, other.volume);
        long diff = Math.abs((long) volume - other.volume);
        return diff * 10L <= (long) max; // diff/max ≤ 0.10 → ±10% 内同笔
    }
}
